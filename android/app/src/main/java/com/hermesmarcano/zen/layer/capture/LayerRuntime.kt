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
import com.hermesmarcano.zen.layer.data.AudioFiles
import com.hermesmarcano.zen.layer.data.LayerDatabase
import com.hermesmarcano.zen.layer.data.LayerStateRepository
import com.hermesmarcano.zen.layer.data.SqliteOutbox
import com.hermesmarcano.zen.layer.data.SqliteTimeline
import com.hermesmarcano.zen.layer.link.BrainClient
import com.hermesmarcano.zen.layer.link.EventJson
import com.hermesmarcano.zen.layer.link.SyncEngine
import com.hermesmarcano.zen.zenApp
import java.io.File
import java.io.IOException
import java.util.UUID
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
}

/** Todas las piezas de la capa, creadas una vez por proceso. Los servicios la sacan de [ZenApp][com.hermesmarcano.zen.ZenApp]. */
class LayerRuntime(private val app: Application) {
    val dayClock = SystemDayClock()
    val state = LayerStateRepository(File(app.filesDir, "layer/layer.json"))
    val database: LayerDatabase by lazy { LayerDatabase(app) }
    val timeline: TimelineStore by lazy { SqliteTimeline(database) }
    val outbox: OutboxStore by lazy { SqliteOutbox(database) }
    val audioFiles = AudioFiles(File(app.filesDir, "layer/audio"))

    /** Un solo hilo para guardar y sincronizar: conserva el orden y deja libre el hilo principal. */
    val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "zen-layer-io").apply { isDaemon = true } }

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
