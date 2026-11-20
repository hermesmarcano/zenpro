package com.hermesmarcano.zen.layer.capture

import android.app.Application
import android.content.Context
import com.hermesmarcano.zen.BuildConfig
import com.hermesmarcano.zen.layer.core.BlockActions
import com.hermesmarcano.zen.layer.core.CommandApplier
import com.hermesmarcano.zen.layer.core.DraftController
import com.hermesmarcano.zen.layer.core.ForegroundTracker
import com.hermesmarcano.zen.layer.core.LayerEvent
import com.hermesmarcano.zen.layer.core.LayerPipeline
import com.hermesmarcano.zen.layer.core.LimitEngine
import com.hermesmarcano.zen.layer.core.OutboxStore
import com.hermesmarcano.zen.layer.core.SystemDayClock
import com.hermesmarcano.zen.layer.core.TimelineStore
import com.hermesmarcano.zen.core.GameMessages
import com.hermesmarcano.zen.data.GameEngine
import com.hermesmarcano.zen.layer.data.AudioFiles
import com.hermesmarcano.zen.layer.data.LayerDatabase
import com.hermesmarcano.zen.layer.data.LayerStateRepository
import com.hermesmarcano.zen.layer.data.SealedChatStore
import com.hermesmarcano.zen.layer.data.SqliteOutbox
import com.hermesmarcano.zen.layer.data.SqliteTimeline
import com.hermesmarcano.zen.layer.link.BrainClient
import com.hermesmarcano.zen.layer.link.ChatController
import com.hermesmarcano.zen.layer.link.EventJson
import com.hermesmarcano.zen.layer.link.SyncEngine
import com.hermesmarcano.zen.layer.link.brainConfigOf
import com.hermesmarcano.zen.zenApp
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Estado "en vivo" de los servicios: un proceso, una sola copia. Sirve para saber si el sistema realmente tiene
 * conectado cada servicio (Xiaomi puede dejar el interruptor de Ajustes en "activado" con el servicio muerto).
 */
object LayerLive {
    @Volatile var accessibilityConnected = false
    @Volatile var listenerConnected = false
    @Volatile var audioRunning = false

    /** El chat está grabando la voz de la persona: la escucha ambiental suelta el micrófono (no debe oír ni guardar lo que se le dice al chat). */
    @Volatile var chatRecording = false

    /** Desde cuándo: la escucha ambiental solo respeta la bandera durante lo que puede durar una grabación (por si algo la dejara pegada). */
    @Volatile var chatRecordingAtMs = 0L
}

/** Todas las piezas de la capa, creadas una vez por proceso. Los servicios la sacan de [ZenApp][com.hermesmarcano.zen.ZenApp]. */
class LayerRuntime(private val app: Application) {
    val dayClock = SystemDayClock()

    /** Una sola clave del Keystore para el token, la cola de envío y el audio pendiente. */
    private val secrets = LayerSecrets.create()
    val state = LayerStateRepository(File(app.filesDir, "layer/layer.json"), secrets)
    val database: LayerDatabase by lazy { LayerDatabase(app) }
    val timeline: TimelineStore by lazy { SqliteTimeline(database) }
    val outbox: OutboxStore by lazy { SqliteOutbox(database, secrets) }
    val audioFiles = AudioFiles(File(app.filesDir, "layer/audio"), secrets)

    /** Un solo hilo para guardar y sincronizar: conserva el orden y deja libre el hilo principal. */
    val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "zen-layer-io").apply { isDaemon = true } }

    /** Hilo aparte para los trabajos periódicos y la red: una sincronización lenta no debe retrasar el guardado de lo capturado. */
    private val jobs: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "zen-layer-job").apply { isDaemon = true } }

    fun runJob(block: () -> Unit) {
        jobs.execute {
            try {
                block()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Encola trabajo en [io]. Una excepción suelta en un hilo de Android mata la app, así que aquí se traga: la capa
     * puede fallar en lo suyo (disco lleno, base bloqueada) sin llevarse por delante la alarma de ZEN.
     */
    fun runIo(block: () -> Unit) {
        io.execute {
            try {
                block()
            } catch (_: Exception) {
            }
        }
    }

    val replies = ReplyActionCache(app)
    val notifier: AndroidNotifier by lazy { AndroidNotifier(app) }
    val tracker = ForegroundTracker(dayClock)

    val pipeline: LayerPipeline by lazy {
        LayerPipeline(state, timeline, outbox, app.packageName, EventJson::encode, onEnqueued = { LayerScheduler.requestFlush(app) })
    }
    val limits: LimitEngine by lazy { LimitEngine(state, tracker, dayClock, app.packageName) }
    val blockActions: BlockActions by lazy {
        BlockActions(state, dayClock, app.packageName) { title, text, pkg ->
            runIo { pipeline.onSystem(LayerEvent.Kind.SYSTEM, title, text, pkg) }
        }
    }
    val drafts: DraftController by lazy { DraftController(state, replies, pipeline) }
    val commands: CommandApplier by lazy { CommandApplier(state, blockActions, drafts, notifier, app.packageName) }
    val brain: BrainClient by lazy { BrainClient("ZEN-Layer/${BuildConfig.VERSION_NAME}") }
    val sync: SyncEngine by lazy { SyncEngine(state, outbox, audioFiles, brain, commands) }

    /** La conversación con el cerebro, cifrada en el teléfono. Un hilo propio para la red (una respuesta puede tardar más de un minuto). */
    // Un grupo de hilos, no uno: tras «Nueva conversación» la petición vieja puede seguir esperando al servidor (hasta ~2 min) y la nueva no debe hacer cola detrás.
    private val chatNet: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "zen-layer-chat").apply { isDaemon = true } }
    val chat: ChatController by lazy {
        ChatController(
            api = brain,
            brain = { brainConfigOf(state.current) },
            paused = { state.current.capture.isPaused(System.currentTimeMillis()) },
            store = SealedChatStore(File(app.filesDir, "layer/chat.bin"), secrets),
            recorder = AndroidVoiceRecorder(app),
            // Mismas validaciones y topes que las órdenes de la cola.
            // Con la pantalla a la vista el chat ya enseña lo que se hizo; si la respuesta llega con ella cerrada, avisa como las órdenes de la cola.
            applyCommand = { commands.apply(it, announce = !chat.watched) },
            appLabel = { pkg -> state.current.limits.firstOrNull { it.pkg == pkg }?.label?.takeIf { it.isNotBlank() } ?: pkg },
            onTurn = ::recordChatTurn,
            net = Executor { task -> chatNet.execute { guarded(task) } },
            io = Executor { task -> runIo { task.run() } },
        )
    }

    /** Una excepción suelta en un hilo de Android mata la app: aquí se traga (el controlador ya cuenta con fallos de red). */
    private fun guarded(task: Runnable) {
        try {
            task.run()
        } catch (_: Exception) {
        }
    }

    /** Una conversación que cuenta: da su XP del día (con tope) y devuelve lo que se ganó, ya redactado. */
    private fun recordChatTurn(voice: Boolean): List<String> {
        var lines: List<String> = emptyList()
        try {
            app.zenApp.repository.update { s ->
                GameEngine.recordChat(s, voice, LocalDate.now()).also { lines = GameMessages.describe(it.events) }.state
            }
        } catch (_: IOException) {
            return emptyList() // no se guardó: no se anuncia un XP que no existe
        }
        return lines
    }

    /** Identificador anónimo de este teléfono para el cerebro. Se genera una vez. */
    fun ensureDeviceId() {
        if (state.current.deviceId.isNotBlank()) return
        try {
            state.update { if (it.deviceId.isBlank()) it.copy(deviceId = UUID.randomUUID().toString()) else it }
        } catch (_: IOException) {
        }
    }
}

val Context.layer: LayerRuntime get() = zenApp.layer
