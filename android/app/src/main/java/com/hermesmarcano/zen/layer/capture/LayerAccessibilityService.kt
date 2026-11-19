package com.hermesmarcano.zen.layer.capture

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.hermesmarcano.zen.layer.core.BlockEntry
import com.hermesmarcano.zen.layer.core.LayerEvent
import com.hermesmarcano.zen.layer.core.LimitEngine
import com.hermesmarcano.zen.layer.core.ScreenTextCollector
import com.hermesmarcano.zen.layer.core.Source
import com.hermesmarcano.zen.layer.core.TextNode
import com.hermesmarcano.zen.layer.core.WindowClassifier
import com.hermesmarcano.zen.layer.core.WindowKind
import java.util.concurrent.atomic.AtomicBoolean

/** Un nodo de la pantalla visto como [TextNode]. Libera (recicla) los nodos que pide, como exige Android antiguo. */
internal class AccessibilityTextNode(private val info: AccessibilityNodeInfo) : TextNode {
    override val text: CharSequence? get() = info.text
    override val isPassword: Boolean get() = info.isPassword
    override val isVisible: Boolean get() = info.isVisibleToUser
    override val childCount: Int get() = info.childCount
    override fun child(index: Int): TextNode? = info.getChild(index)?.let(::AccessibilityTextNode)

    @Suppress("DEPRECATION")
    override fun release() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) info.recycle()
    }
}

/**
 * Los ojos y el freno de la capa. Hace tres cosas:
 *  1. Sabe qué app está delante y cuánto tiempo lleva, para los límites de tiempo.
 *  2. Si esa app está bloqueada (por límite, franja u orden del cerebro), la saca de primer plano (pulsa "Inicio").
 *  3. Lee el texto de la pantalla (sin contraseñas ni apps sensibles) y lo pasa por la tubería de privacidad.
 *
 * Nunca toca Ajustes ni el teléfono (ver `NeverBlock`): el usuario siempre puede desactivar este servicio.
 */
class LayerAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val capturing = AtomicBoolean(false)
    private val lastBlockNotice = HashMap<String, Long>()
    private var lastEnforceAtMs = 0L
    private var tickScheduled = false
    private var receiverRegistered = false

    /**
     * Costura para pruebas: Robolectric no tiene conexión real con el sistema de accesibilidad y no puede entregar una
     * ventana activa. En producción es siempre `null` y se usa [rootInActiveWindow].
     */
    internal var activeRootProvider: (() -> AccessibilityNodeInfo?)? = null

    private fun activeRoot(): AccessibilityNodeInfo? = activeRootProvider?.invoke() ?: rootInActiveWindow

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) runtimeOrNull()?.limits?.onScreenOff(System.currentTimeMillis())
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            tickScheduled = false
            val runtime = runtimeOrNull() ?: return
            applyOutcome(runtime.limits.tick(System.currentTimeMillis()))
            scheduleTickIfNeeded()
        }
    }

    private fun runtimeOrNull(): LayerRuntime? = try {
        layer
    } catch (_: Exception) {
        null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        LayerLive.accessibilityConnected = true
        val runtime = runtimeOrNull() ?: return
        runtime.runIo {
            runtime.ensureDeviceId()
            runtime.limits.restore(System.currentTimeMillis())
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        runtime.runIo { runtime.pipeline.onSystem(LayerEvent.Kind.SYSTEM, "Servicio de accesibilidad conectado", "") }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val runtime = runtimeOrNull() ?: return
        val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        val now = System.currentTimeMillis()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val kind = WindowClassifier.classify(pkg, isInputMethodWindow(event))
                applyOutcome(runtime.limits.onWindow(pkg, kind, now))
                if (kind == WindowKind.APP) captureScreen(runtime, pkg, force = true)
                scheduleTickIfNeeded()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Si el servicio arrancó con una app ya abierta, aún no sabe cuál está delante.
                if (runtime.limits.foregroundPackage() == null && WindowClassifier.classify(pkg) == WindowKind.APP) {
                    applyOutcome(runtime.limits.onWindow(pkg, WindowKind.APP, now))
                    scheduleTickIfNeeded()
                }
                if (runtime.limits.foregroundPackage() == pkg) captureScreen(runtime, pkg, force = false)
            }
            else -> Unit
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        LayerLive.accessibilityConnected = false
        handler.removeCallbacks(tick)
        tickScheduled = false
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: IllegalArgumentException) {
            }
            receiverRegistered = false
        }
        runtimeOrNull()?.limits?.onScreenOff(System.currentTimeMillis())
    }

    /** Lee la pantalla en el hilo de E/S: el árbol puede ser grande y no debe frenar la interfaz. */
    private fun captureScreen(runtime: LayerRuntime, pkg: String, force: Boolean) {
        val pipeline = runtime.pipeline
        if (force) {
            if (!pipeline.decision(Source.SCREEN, pkg).allowed) return
        } else if (!pipeline.screenReady(pkg)) {
            return
        }
        // Una lectura a la vez: las demás se descartan y la siguiente recoge lo nuevo.
        if (!capturing.compareAndSet(false, true)) return
        runtime.runIo {
            try {
                val root = activeRoot() ?: return@runIo
                try {
                    if (root.packageName?.toString() != pkg) return@runIo
                    val lines = ScreenTextCollector.collect(AccessibilityTextNode(root))
                    if (lines.isNotEmpty()) pipeline.onScreen(pkg, lines, force)
                } finally {
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) root.recycle()
                }
            } finally {
                capturing.set(false)
            }
        }
    }

    private fun applyOutcome(outcome: LimitEngine.Outcome) {
        val runtime = runtimeOrNull() ?: return
        outcome.nudge?.let { runtime.notifier.nudge(it) }
        outcome.enforce?.let { enforce(runtime, it) }
    }

    /** Saca la app bloqueada de primer plano. Con un pequeño margen para no entrar en bucle con la pantalla de inicio. */
    private fun enforce(runtime: LayerRuntime, entry: BlockEntry) {
        val now = System.currentTimeMillis()
        if (now - lastEnforceAtMs < ENFORCE_DEBOUNCE_MS) return
        lastEnforceAtMs = now
        performGlobalAction(GLOBAL_ACTION_HOME)
        if (now - (lastBlockNotice[entry.pkg] ?: 0L) >= BLOCK_NOTICE_EVERY_MS) {
            lastBlockNotice[entry.pkg] = now
            runtime.notifier.blocked(entry)
            runtime.runIo { runtime.pipeline.onSystem(LayerEvent.Kind.SYSTEM, "Bloqueo aplicado", "Se sacó ${entry.pkg} de primer plano (${entry.reason.name.lowercase()}).", entry.pkg) }
        }
    }

    private fun scheduleTickIfNeeded() {
        val runtime = runtimeOrNull() ?: return
        if (tickScheduled || !runtime.limits.needsTick(runtime.limits.foregroundPackage())) return
        tickScheduled = true
        handler.postDelayed(tick, TICK_MS)
    }

    /** ¿El evento viene de la ventana del teclado? Cubre teclados que no están en la lista. */
    private fun isInputMethodWindow(event: AccessibilityEvent): Boolean = try {
        windows.any { it.id == event.windowId && it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val TICK_MS = 30_000L
        const val ENFORCE_DEBOUNCE_MS = 1_200L
        const val BLOCK_NOTICE_EVERY_MS = 60_000L
    }
}
