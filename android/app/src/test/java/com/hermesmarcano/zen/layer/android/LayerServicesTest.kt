package com.hermesmarcano.zen.layer.android

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import com.hermesmarcano.zen.ZenApp
import com.hermesmarcano.zen.layer.capture.AmbientAudioService
import com.hermesmarcano.zen.layer.capture.LayerAccessibilityService
import com.hermesmarcano.zen.layer.capture.LayerActionReceiver
import com.hermesmarcano.zen.layer.capture.LayerLive
import com.hermesmarcano.zen.layer.capture.LayerNotificationListener
import com.hermesmarcano.zen.layer.capture.LayerRuntime
import com.hermesmarcano.zen.layer.capture.layer
import com.hermesmarcano.zen.layer.core.BlockEntry
import com.hermesmarcano.zen.layer.core.BlockReason
import com.hermesmarcano.zen.layer.core.DraftStatus
import com.hermesmarcano.zen.layer.core.LayerEvent
import com.hermesmarcano.zen.layer.core.LimitRule
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private const val TIKTOK = "com.zhiliaoapp.musically"
private const val WHATSAPP = "com.whatsapp"

private fun Context.runtime(): LayerRuntime = layer

/** Espera a que termine lo que ya se encoló en el hilo de E/S de la capa. */
private fun LayerRuntime.drain() {
    io.submit {}.get(5, TimeUnit.SECONDS)
}

private fun Context.notifications(): List<Notification> =
    shadowOf(getSystemService(NotificationManager::class.java)).allNotifications

private fun windowChanged(pkg: String): AccessibilityEvent =
    AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply { packageName = pkg }

private fun contentChanged(pkg: String): AccessibilityEvent =
    AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply { packageName = pkg }

/** Un proveedor que siempre devuelve [root]. */
private fun rootOf(root: AccessibilityNodeInfo): () -> AccessibilityNodeInfo? = { root }

private fun node(text: String? = null, pkg: String? = null, visible: Boolean = true, password: Boolean = false, vararg kids: AccessibilityNodeInfo): AccessibilityNodeInfo {
    val n = AccessibilityNodeInfo.obtain()
    if (text != null) n.text = text
    if (pkg != null) n.packageName = pkg
    n.isVisibleToUser = visible
    n.isPassword = password
    kids.forEach { shadowOf(n).addChild(it) }
    return n
}

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(application = ZenApp::class, sdk = [34])
class LayerAccessibilityServiceTest {
    init { useSoftwareSecrets() }

    private val app: ZenApp = ApplicationProvider.getApplicationContext()
    private val runtime get() = app.runtime()
    private lateinit var service: LayerAccessibilityService

    @Before fun connect() {
        LayerLive.accessibilityConnected = false
        service = Robolectric.setupService(LayerAccessibilityService::class.java)
        LayerAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
        runtime.drain()
    }

    @After fun clean() {
        service.onUnbind(Intent())
        LayerLive.accessibilityConnected = false
    }

    /** Lo leído de la pantalla (sin los eventos de sistema, como "servicio conectado"). */
    private fun screenEvents() = runtime.timeline.recent(50).filter { it.kind == LayerEvent.Kind.SCREEN }

    private fun homePresses() = shadowOf(service as AccessibilityService).globalActionsPerformed.count { it == AccessibilityService.GLOBAL_ACTION_HOME }

    private fun block(pkg: String, reason: BlockReason = BlockReason.MANUAL) =
        runtime.state.update { it.copy(blocks = listOf(BlockEntry(pkg, System.currentTimeMillis() + 3_600_000L, reason))) }

    @Test fun connectingMarksTheServiceAliveAndCreatesTheDeviceId() {
        assertTrue(LayerLive.accessibilityConnected)
        assertTrue(runtime.state.current.deviceId.isNotBlank())
    }

    @Test fun unbindingMarksItDead() {
        service.onUnbind(Intent())
        assertFalse(LayerLive.accessibilityConnected)
    }

    @Test fun aBlockedAppIsSentHomeAndNotifiedOnlyOnceInARow() {
        block(TIKTOK)
        service.onAccessibilityEvent(windowChanged(TIKTOK))
        assertEquals(1, homePresses())
        // Un segundo evento pegado al primero no vuelve a pulsar Inicio (evita bucles) ni a avisar.
        service.onAccessibilityEvent(windowChanged(TIKTOK))
        assertEquals(1, homePresses())
        assertEquals(1, app.notifications().count { it.extras.getString(Notification.EXTRA_TITLE) == "App bloqueada" })
    }

    @Test fun anUnblockedAppIsLeftAlone() {
        block(TIKTOK)
        service.onAccessibilityEvent(windowChanged(WHATSAPP))
        assertEquals(0, homePresses())
    }

    @Test fun protectedAppsAreNeverSentHomeEvenWithABogusBlock() {
        block("com.android.settings", BlockReason.BRAIN)
        service.onAccessibilityEvent(windowChanged("com.android.settings"))
        service.onAccessibilityEvent(windowChanged(app.packageName))
        assertEquals(0, homePresses())
    }

    @Test fun theForegroundAppIsTrackedAndKeyboardsDoNotChangeIt() {
        service.onAccessibilityEvent(windowChanged(WHATSAPP))
        assertEquals(WHATSAPP, runtime.limits.foregroundPackage())
        service.onAccessibilityEvent(windowChanged("com.miui.securityinputmethod"))
        assertEquals(WHATSAPP, runtime.limits.foregroundPackage())
        service.onAccessibilityEvent(windowChanged("com.miui.home"))
        assertNull(runtime.limits.foregroundPackage())
    }

    @Test fun passingTheDailyLimitPostsAFactualNudge() {
        val now = System.currentTimeMillis()
        runtime.state.update {
            it.copy(
                limits = listOf(LimitRule(TIKTOK, "TikTok", dailyLimitMs = 45 * 60_000L)),
                dailyDay = runtime.dayClock.dayOf(now),
                usageTotals = mapOf(TIKTOK to 50 * 60_000L),
            )
        }
        runtime.limits.restore(now)
        service.onAccessibilityEvent(windowChanged(TIKTOK))
        val nudge = app.notifications().single { it.extras.getString(Notification.EXTRA_TITLE) == "Tiempo en TikTok" }
        assertEquals("Llevas 50 min en TikTok. Tu límite de hoy era 45 min.", nudge.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("sin bloqueo: solo aviso", 0, homePresses())
    }

    @Test fun screenTextIsCapturedWithoutPasswordsOrInvisibleViews() {
        runtime.state.update { it.copy(capture = it.capture.copy(screenText = true)) }
        val root = node(
            pkg = WHATSAPP,
            kids = arrayOf(
                node("Marta: ¿cenamos hoy?"),
                node("clave-secreta-123", password = true),
                node("texto oculto", visible = false),
                node("nos vemos a las nueve"),
            ),
        )
        service.activeRootProvider = rootOf(root)
        service.onAccessibilityEvent(windowChanged(WHATSAPP))
        runtime.drain()
        val event = screenEvents().single()
        assertEquals(WHATSAPP, event.pkg)
        assertTrue(event.text.contains("¿cenamos hoy?"))
        assertTrue(event.text.contains("nos vemos a las nueve"))
        assertFalse(event.text.contains("clave-secreta"))
        assertFalse(event.text.contains("oculto"))
    }

    @Test fun nothingIsReadWhenTheSourceIsOff() {
        service.activeRootProvider = rootOf(node(pkg = WHATSAPP, kids = arrayOf(node("hola mundo largo"))))
        service.onAccessibilityEvent(windowChanged(WHATSAPP))
        runtime.drain()
        assertTrue(screenEvents().isEmpty())
    }

    @Test fun sensitiveAppsAreNeverRead() {
        runtime.state.update { it.copy(capture = it.capture.copy(screenText = true)) }
        service.activeRootProvider = rootOf(node(pkg = "com.bbva.mx", kids = arrayOf(node("saldo: 12,345.00 pesos"))))
        service.onAccessibilityEvent(windowChanged("com.bbva.mx"))
        runtime.drain()
        assertTrue(screenEvents().isEmpty())
    }

    @Test fun contentFromAnotherWindowIsNotMistakenForTheForegroundApp() {
        runtime.state.update { it.copy(capture = it.capture.copy(screenText = true)) }
        service.onAccessibilityEvent(windowChanged(WHATSAPP))
        runtime.drain()
        // La raíz activa es de otra app: no se guarda como si fuera WhatsApp.
        service.activeRootProvider = rootOf(node(pkg = "com.otra.app", kids = arrayOf(node("texto de otra app larga"))))
        service.onAccessibilityEvent(contentChanged(WHATSAPP))
        runtime.drain()
        assertTrue(screenEvents().none { it.text.contains("otra app") })
    }

    @Test fun screenOffEndsTheSession() {
        service.onAccessibilityEvent(windowChanged(WHATSAPP))
        assertNotNull(runtime.limits.foregroundPackage())
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(runtime.limits.foregroundPackage())
    }

    @Test fun eventsWithoutAPackageAreIgnored() {
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertEquals(0, homePresses())
    }
}

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(application = ZenApp::class, sdk = [34])
class LayerNotificationListenerTest {
    init { useSoftwareSecrets() }

    private val app: ZenApp = ApplicationProvider.getApplicationContext()
    private val runtime get() = app.runtime()
    private lateinit var listener: LayerNotificationListener

    @Before fun setUp() {
        listener = Robolectric.setupService(LayerNotificationListener::class.java)
        runtime.state.update { it.copy(capture = it.capture.copy(notifications = true)) }
    }

    private fun message(title: String = "Marta", text: String = "¿cenamos hoy?", reply: Boolean = true, category: String? = Notification.CATEGORY_MESSAGE): Notification {
        val pi = PendingIntent.getBroadcast(app, 0, Intent("zen.test.REPLY").setPackage(app.packageName), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(app, "c").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
        if (category != null) builder.setCategory(category)
        if (reply) {
            builder.addAction(
                NotificationCompat.Action.Builder(0, "Responder", pi)
                    .addRemoteInput(RemoteInput.Builder("k").setAllowFreeFormInput(true).build()).build(),
            )
        }
        return builder.build()
    }

    @Test fun anAllowedMessageIsStoredAndItsReplyIsRemembered() {
        listener.handle(WHATSAPP, "0|a|1", message(), ongoing = false)
        runtime.drain()
        val event = runtime.timeline.recent(10).single()
        assertEquals(LayerEvent.Kind.NOTIFICATION, event.kind)
        assertEquals("Marta", event.title)
        assertEquals("¿cenamos hoy?", event.text)
        assertEquals("$WHATSAPP|0|a|1", event.extra["conv"])
        assertEquals("Marta", runtime.replies.target("$WHATSAPP|0|a|1")!!.contact)
    }

    @Test fun aMessageWithoutAReplyButtonIsStoredButNotReplyable() {
        listener.handle(WHATSAPP, "k", message(reply = false), ongoing = false)
        runtime.drain()
        assertNull(runtime.timeline.recent(10).single().extra["conv"])
        assertEquals(0, runtime.replies.size())
    }

    @Test fun codesAreRedactedBeforeStoring() {
        listener.handle("com.example.app", "k", message(title = "Código de verificación", text = "482913", reply = false), ongoing = false)
        runtime.drain()
        assertFalse(runtime.timeline.recent(10).single().text.contains("482913"))
    }

    @Test fun nothingIsStoredWhenTheSourceIsOffOrPaused() {
        runtime.state.update { it.copy(capture = it.capture.copy(notifications = false)) }
        listener.handle(WHATSAPP, "k", message(), ongoing = false)
        runtime.state.update { it.copy(capture = it.capture.copy(notifications = true, pausedUntilMs = System.currentTimeMillis() + 60_000)) }
        listener.handle(WHATSAPP, "k2", message(text = "otro mensaje"), ongoing = false)
        runtime.drain()
        assertEquals(0, runtime.timeline.count())
        assertEquals("ni se guarda la acción de responder", 0, runtime.replies.size())
    }

    @Test fun sensitiveAndOwnAppsAndNoiseAreIgnored() {
        listener.handle("com.bbva.mx", "k", message(), ongoing = false)
        listener.handle(app.packageName, "k", message(), ongoing = false)
        listener.handle("com.spotify.music", "k", message(category = Notification.CATEGORY_TRANSPORT), ongoing = true)
        listener.handle(WHATSAPP, "k", message(category = null), ongoing = true) // fija y no es un mensaje
        runtime.drain()
        assertEquals(0, runtime.timeline.count())
    }

    @Test fun aRemovedNotificationCanNoLongerBeAnswered() {
        listener.handle(WHATSAPP, "k", message(), ongoing = false)
        runtime.drain()
        assertNotNull(runtime.replies.target("$WHATSAPP|k"))
        runtime.replies.remove("$WHATSAPP|k")
        assertNull(runtime.replies.target("$WHATSAPP|k"))
    }
}

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(application = ZenApp::class, sdk = [34])
class LayerActionReceiverTest {
    init { useSoftwareSecrets() }

    private val app: ZenApp = ApplicationProvider.getApplicationContext()
    private val runtime get() = app.runtime()
    private val key = "$WHATSAPP|conv-1"

    private fun fire(action: String, vararg extras: Pair<String, String>) {
        val intent = Intent(action)
        extras.forEach { (k, v) -> intent.putExtra(k, v) }
        LayerActionReceiver().onReceive(app, intent)
        runtime.drain()
    }

    private fun repliableDraft(): String {
        val pi = PendingIntent.getBroadcast(app, 0, Intent("zen.test.REPLY").setPackage(app.packageName), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, "c").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Marta").setContentText("hola")
            .addAction(NotificationCompat.Action.Builder(0, "Responder", pi).addRemoteInput(RemoteInput.Builder("k").setAllowFreeFormInput(true).build()).build())
            .build()
        runtime.replies.put(key, WHATSAPP, "Marta", n)
        return runtime.drafts.propose(key, "claro, a las 9")!!.id
    }

    @Test fun approvingSendsTheReplyOnceAndClearsTheNotification() {
        val id = repliableDraft()
        fire(LayerActionReceiver.ACTION_APPROVE_DRAFT, LayerActionReceiver.EXTRA_ID to id)
        assertEquals(DraftStatus.SENT, runtime.state.current.drafts.single().status)
        val sent = shadowOf(app as android.app.Application).broadcastIntents.filter { it.action == "zen.test.REPLY" }
        assertEquals(1, sent.size)
        assertEquals("claro, a las 9", RemoteInput.getResultsFromIntent(sent.single())!!.getCharSequence("k").toString())
        // Un segundo toque no reenvía.
        fire(LayerActionReceiver.ACTION_APPROVE_DRAFT, LayerActionReceiver.EXTRA_ID to id)
        assertEquals(1, shadowOf(app as android.app.Application).broadcastIntents.count { it.action == "zen.test.REPLY" })
    }

    @Test fun approvingAVanishedConversationTellsTheUser() {
        val id = repliableDraft()
        runtime.replies.remove(key)
        fire(LayerActionReceiver.ACTION_APPROVE_DRAFT, LayerActionReceiver.EXTRA_ID to id)
        assertEquals(DraftStatus.FAILED, runtime.state.current.drafts.single().status)
        assertTrue(app.notifications().any { it.extras.getString(Notification.EXTRA_TITLE) == "Respuesta no enviada" })
    }

    @Test fun discardingNeverSends() {
        val id = repliableDraft()
        fire(LayerActionReceiver.ACTION_DISCARD_DRAFT, LayerActionReceiver.EXTRA_ID to id)
        assertEquals(DraftStatus.DISCARDED, runtime.state.current.drafts.single().status)
        assertTrue(shadowOf(app as android.app.Application).broadcastIntents.none { it.action == "zen.test.REPLY" })
    }

    @Test fun blockTodayBlocksUntilMidnightButRefusesProtectedApps() {
        fire(LayerActionReceiver.ACTION_BLOCK_TODAY, LayerActionReceiver.EXTRA_PKG to TIKTOK)
        assertEquals(TIKTOK, runtime.state.current.blocks.single().pkg)
        fire(LayerActionReceiver.ACTION_BLOCK_TODAY, LayerActionReceiver.EXTRA_PKG to "com.android.settings")
        assertEquals(1, runtime.state.current.blocks.size)
        assertTrue(app.notifications().any { it.extras.getString(Notification.EXTRA_TITLE) == "No se puede bloquear" })
    }

    @Test fun pauseSetsTheGlobalPause() {
        val before = System.currentTimeMillis()
        fire(LayerActionReceiver.ACTION_PAUSE, LayerActionReceiver.EXTRA_MINUTES to "60")
        val until = runtime.state.current.capture.pausedUntilMs
        assertTrue(until >= before + 59 * 60_000L && until <= before + 61 * 60_000L)
    }

    @Test fun aSillyPauseDurationIsClamped() {
        fire(LayerActionReceiver.ACTION_PAUSE, LayerActionReceiver.EXTRA_MINUTES to "999999")
        assertTrue(runtime.state.current.capture.pausedUntilMs <= System.currentTimeMillis() + 24 * 60 * 60_000L + 1_000)
    }

    @Test fun stoppingTheAudioTurnsTheSourceOff() {
        runtime.state.update { it.copy(capture = it.capture.copy(ambientAudio = true)) }
        fire(LayerActionReceiver.ACTION_STOP_AUDIO)
        assertFalse(runtime.state.current.capture.ambientAudio)
    }

    @Test fun anUnknownActionDoesNothing() {
        val before = runtime.state.current
        fire("com.example.UNKNOWN")
        assertEquals(before, runtime.state.current)
    }
}

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(application = ZenApp::class, sdk = [34])
class AmbientAudioServiceTest {
    init { useSoftwareSecrets() }

    private val app: ZenApp = ApplicationProvider.getApplicationContext()
    private val runtime get() = app.runtime()

    @Before fun reset() {
        LayerLive.audioRunning = false
    }

    private fun ready() {
        runtime.state.update {
            it.copy(capture = it.capture.copy(ambientAudio = true), brainUrl = "https://brain.example.com", brainToken = "tok")
        }
        shadowOf(app as android.app.Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @Test fun preconditionsAreCheckedInOrder() {
        assertEquals(AmbientAudioService.StartCheck.SOURCE_OFF, AmbientAudioService.preconditions(app, runtime))
        runtime.state.update { it.copy(capture = it.capture.copy(ambientAudio = true)) }
        assertEquals(AmbientAudioService.StartCheck.NO_BRAIN, AmbientAudioService.preconditions(app, runtime))
        runtime.state.update { it.copy(brainUrl = "https://brain.example.com", brainToken = "tok") }
        assertEquals(AmbientAudioService.StartCheck.NO_PERMISSION, AmbientAudioService.preconditions(app, runtime))
        shadowOf(app as android.app.Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals(AmbientAudioService.StartCheck.OK, AmbientAudioService.preconditions(app, runtime))
    }

    @Test fun startDoesNotLaunchAServiceWhenAPreconditionFails() {
        assertEquals(AmbientAudioService.StartCheck.SOURCE_OFF, AmbientAudioService.start(app))
        assertNull(shadowOf(app as android.app.Application).nextStartedService)
    }

    @Test fun startLaunchesTheServiceWhenEverythingIsInPlace() {
        ready()
        assertEquals(AmbientAudioService.StartCheck.OK, AmbientAudioService.start(app))
        assertNotNull(shadowOf(app as android.app.Application).nextStartedService)
    }

    @Test fun ifTheStateChangesBeforeTheServiceStartsItClosesWithoutListening() {
        // Caso de carrera: se arrancó, pero el usuario apagó la fuente justo antes de onStartCommand.
        val controller = Robolectric.buildService(AmbientAudioService::class.java).create()
        controller.startCommand(0, 1)
        assertFalse(LayerLive.audioRunning)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test fun theServiceListensWithAVisibleNotificationAndStopsCleanly() {
        ready()
        val controller = Robolectric.buildService(AmbientAudioService::class.java).create()
        controller.startCommand(0, 1)
        val shadow = shadowOf(controller.get())
        assertTrue(LayerLive.audioRunning)
        assertNotNull("el aviso fijo es el indicador de que se escucha", shadow.lastForegroundNotification)
        assertEquals("ZEN está escuchando", shadow.lastForegroundNotification.extras.getString(Notification.EXTRA_TITLE))
        controller.destroy()
        assertFalse(LayerLive.audioRunning)
    }
}
