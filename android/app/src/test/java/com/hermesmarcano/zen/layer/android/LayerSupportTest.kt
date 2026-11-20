package com.hermesmarcano.zen.layer.android

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.hermesmarcano.zen.R
import com.hermesmarcano.zen.ZenApp
import com.hermesmarcano.zen.layer.capture.AccessibilityHealer
import com.hermesmarcano.zen.layer.capture.AmbientAudioService
import com.hermesmarcano.zen.layer.capture.AndroidNotifier
import com.hermesmarcano.zen.layer.capture.LayerAccessibilityService
import com.hermesmarcano.zen.layer.capture.LayerActionReceiver
import com.hermesmarcano.zen.layer.capture.LayerIntents
import com.hermesmarcano.zen.layer.capture.LayerJobService
import com.hermesmarcano.zen.layer.capture.LayerLive
import com.hermesmarcano.zen.layer.capture.LayerMaintenance
import com.hermesmarcano.zen.layer.capture.LayerNotificationListener
import com.hermesmarcano.zen.layer.capture.LayerScheduler
import com.hermesmarcano.zen.layer.capture.LayerStatus
import com.hermesmarcano.zen.layer.capture.layer
import com.hermesmarcano.zen.layer.core.AccessibilitySetting
import com.hermesmarcano.zen.layer.core.BlockEntry
import com.hermesmarcano.zen.layer.core.BlockReason
import com.hermesmarcano.zen.layer.core.Draft
import com.hermesmarcano.zen.layer.core.DraftStatus
import com.hermesmarcano.zen.layer.core.HealDecision
import com.hermesmarcano.zen.layer.core.LimitDecision
import com.hermesmarcano.zen.layer.core.LimitRule
import com.hermesmarcano.zen.layer.core.OutboxItem
import com.hermesmarcano.zen.layer.core.PcmSegment
import com.hermesmarcano.zen.layer.core.SelfHealPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private fun ZenApp.posted(): List<Notification> = shadowOf(getSystemService(NotificationManager::class.java)).allNotifications

private fun ZenApp.titles(): List<String?> = posted().map { it.extras.getString(Notification.EXTRA_TITLE) }

@RunWith(RobolectricTestRunner::class)
@Config(application = ZenApp::class, sdk = [34])
class LayerManifestTest {
    private val app: ZenApp = ApplicationProvider.getApplicationContext()
    private val pm: PackageManager get() = app.packageManager

    @Test fun theAccessibilityServiceIsBoundOnlyByTheSystemAndIsDiscoverable() {
        val info = pm.getServiceInfo(ComponentName(app, LayerAccessibilityService::class.java), PackageManager.GET_META_DATA)
        assertTrue(info.exported)
        assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, info.permission)
        assertTrue("falta su configuración", info.metaData.getInt("android.accessibilityservice") != 0)
        val found = pm.queryIntentServices(Intent("android.accessibilityservice.AccessibilityService").setPackage(app.packageName), 0)
        assertTrue(found.any { it.serviceInfo.name == LayerAccessibilityService::class.java.name })
    }

    @Test fun theAccessibilityConfigurationReadsWindowsButNeverTouchesOrInjects() {
        val parser = app.resources.getXml(R.xml.layer_accessibility_config)
        while (parser.eventType != android.content.res.XmlResourceParser.START_TAG) parser.next()
        val ns = "http://schemas.android.com/apk/res/android"
        assertTrue(parser.getAttributeBooleanValue(ns, "canRetrieveWindowContent", false))
        assertNull("no pide hacer gestos", parser.getAttributeValue(ns, "canPerformGestures"))
        assertNull("no pide filtrar teclas", parser.getAttributeValue(ns, "canRequestFilterKeyEvents"))
        assertNull("no pide modo exploración táctil", parser.getAttributeValue(ns, "canRequestTouchExplorationMode"))
        val flags = parser.getAttributeIntValue(ns, "accessibilityFlags", 0)
        assertTrue(flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0)
        assertEquals(0, flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS)
        assertEquals(0, flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE)
        assertTrue(parser.getAttributeResourceValue(ns, "description", 0) != 0)
    }

    @Test fun theNotificationListenerIsBoundOnlyByTheSystem() {
        val info = pm.getServiceInfo(ComponentName(app, LayerNotificationListener::class.java), 0)
        assertTrue(info.exported)
        assertEquals(Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE, info.permission)
        val found = pm.queryIntentServices(Intent("android.service.notification.NotificationListenerService").setPackage(app.packageName), 0)
        assertTrue(found.any { it.serviceInfo.name == LayerNotificationListener::class.java.name })
    }

    @Test fun theMicrophoneServiceIsPrivateAndTyped() {
        val info = pm.getServiceInfo(ComponentName(app, AmbientAudioService::class.java), 0)
        assertFalse(info.exported)
        assertTrue(info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0)
    }

    @Test fun theJobServiceAndTheActionReceiverArePrivate() {
        val job = pm.getServiceInfo(ComponentName(app, LayerJobService::class.java), 0)
        assertFalse(job.exported)
        assertEquals("android.permission.BIND_JOB_SERVICE", job.permission)
        assertFalse(pm.getReceiverInfo(ComponentName(app, LayerActionReceiver::class.java), 0).exported)
    }

    @Test fun theLayerPermissionsAreDeclared() {
        val requested = requested()
        for (p in listOf(
            Manifest.permission.RECORD_AUDIO, "android.permission.FOREGROUND_SERVICE_MICROPHONE", Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "android.permission.WRITE_SECURE_SETTINGS",
        )) assertTrue("falta $p", p in requested)
    }

    /** Mínimo privilegio: nada de esto hace falta y no debe colarse por accidente. */
    @Test fun noMorePowerIsRequestedThanTheLayerNeeds() {
        val requested = requested()
        for (p in listOf(
            "android.permission.READ_SMS", "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS", "android.permission.READ_CONTACTS",
            "android.permission.READ_CALL_LOG", "android.permission.CALL_PHONE", "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION", "android.permission.QUERY_ALL_PACKAGES", "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.READ_EXTERNAL_STORAGE", "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.BIND_DEVICE_ADMIN", "android.permission.CAPTURE_AUDIO_OUTPUT", "android.permission.READ_PHONE_STATE",
        )) assertFalse("no debería pedir $p", p in requested)
    }

    private fun requested(): Set<String> = pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions!!.toSet()
}

@RunWith(RobolectricTestRunner::class)
@Config(application = ZenApp::class, sdk = [34])
class AndroidNotifierTest {
    private val app: ZenApp = ApplicationProvider.getApplicationContext()
    private val notifier get() = app.layer.notifier
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    @Test fun bothChannelsExistAndTheStatusOneIsQuiet() {
        notifier
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(AndroidNotifier.CHANNEL_STATUS).importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel(AndroidNotifier.CHANNEL_ALERTS).importance)
    }

    @Test fun aDraftOffersSendAndDiscardAndHidesItsTextOnTheLockScreen() {
        val draft = Draft("d1", "k", "com.whatsapp", "Marta", "claro, a las 9", 1L, 2L)
        notifier.draftReady(draft)
        val n = app.posted().single()
        assertEquals("Respuesta para Marta", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(listOf("Enviar", "Descartar"), n.actions.map { it.title.toString() })
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertFalse("la versión pública no lleva el texto", n.publicVersion.extras.getString(Notification.EXTRA_TEXT).orEmpty().contains("claro"))
        notifier.cancelDraft("d1")
        assertTrue(app.posted().isEmpty())
    }

    @Test fun theNudgeStatesAFactAndOffersToBlock() {
        val rule = LimitRule("com.zhiliaoapp.musically", "TikTok", 45 * 60_000L)
        notifier.nudge(LimitDecision.Nudge(rule, 36 * 60_000L, 80))
        val soft = app.posted().single()
        assertEquals("Tiempo en TikTok", soft.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Llevas 36 min en TikTok; tu límite de hoy es 45 min.", soft.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(listOf("Bloquear hasta mañana"), soft.actions.map { it.title.toString() })
        // El aviso del 100 % sustituye al del 80 %: una sola notificación por app, no una pila.
        notifier.nudge(LimitDecision.Nudge(rule, 46 * 60_000L, 100))
        val full = app.posted().single()
        assertEquals("Llevas 46 min en TikTok. Tu límite de hoy era 45 min.", full.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test fun theListeningNoticeIsOngoingAndCannotBeMissed() {
        val n = notifier.listening()
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("ZEN está escuchando", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(listOf("Pausar 1 h", "Detener"), n.actions.map { it.title.toString() })
    }

    @Test fun thePausedNoticeSaysUntilWhenAndOffersToStop() {
        val n = notifier.paused(System.currentTimeMillis() + 3_600_000L)
        assertEquals("ZEN en pausa", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(listOf("Detener"), n.actions.map { it.title.toString() })
    }

    @Test fun blockedNamesTheAppWhenItIsNotInstalled() {
        notifier.blocked(BlockEntry("com.zhiliaoapp.musically", System.currentTimeMillis() + 3_600_000L, BlockReason.LIMIT))
        assertEquals("App bloqueada", app.titles().single())
    }

    @Test fun differentDraftsGetDifferentNotificationIds() {
        assertTrue(AndroidNotifier.draftNotificationId("a") != AndroidNotifier.draftNotificationId("b"))
        assertEquals(AndroidNotifier.draftNotificationId("a"), AndroidNotifier.draftNotificationId("a"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = ZenApp::class, sdk = [34])
class LayerSchedulerTest {
    private val app: ZenApp = ApplicationProvider.getApplicationContext()
    private val scheduler get() = app.getSystemService(JobScheduler::class.java)
    private val runtime get() = app.layer

    private fun jobIds() = scheduler.allPendingJobs.map { it.id }

    @Test fun nothingIsScheduledWhileTheLayerIsUnused() {
        LayerScheduler.sync(app)
        assertTrue(jobIds().isEmpty())
    }

    @Test fun usingASourceSchedulesAPersistedPeriodicJobOnlyOnce() {
        runtime.state.update { it.copy(capture = it.capture.copy(notifications = true)) }
        LayerScheduler.sync(app)
        LayerScheduler.sync(app)
        val job = scheduler.allPendingJobs.single()
        assertEquals(LayerScheduler.JOB_MAINTENANCE, job.id)
        assertTrue(job.isPeriodic)
        assertEquals(15 * 60_000L, job.intervalMillis)
        assertTrue(job.isPersisted)
    }

    @Test fun turningEverythingOffCancelsTheJobs() {
        runtime.state.update { it.copy(capture = it.capture.copy(screenText = true)) }
        LayerScheduler.sync(app)
        LayerScheduler.requestFlush(app)
        assertEquals(2, jobIds().size)
        runtime.state.update { it.copy(capture = it.capture.copy(screenText = false)) }
        LayerScheduler.sync(app)
        assertTrue(jobIds().isEmpty())
    }

    @Test fun aFlushWaitsForNetworkAndIsNotRescheduledWhileOneIsPending() {
        LayerScheduler.requestFlush(app)
        val first = scheduler.allPendingJobs.single { it.id == LayerScheduler.JOB_FLUSH }
        assertEquals(JobInfo.NETWORK_TYPE_ANY, first.networkType)
        assertEquals(30_000L, first.minLatencyMillis)
        LayerScheduler.requestFlush(app)
        assertEquals(1, jobIds().count { it == LayerScheduler.JOB_FLUSH })
    }

    @Test fun aBrainAloneIsEnoughToKeepTheMaintenanceRunning() {
        runtime.state.update { it.copy(brainUrl = "https://brain.example.com", brainToken = "t") }
        LayerScheduler.sync(app)
        assertEquals(listOf(LayerScheduler.JOB_MAINTENANCE), jobIds())
    }

    @Test fun enqueuingAnEventAsksForAFlushOnlyWhenThereIsABrain() {
        runtime.state.update { it.copy(capture = it.capture.copy(notifications = true)) }
        runtime.pipeline.onNotification("com.whatsapp", "Marta", "hola")
        assertTrue(jobIds().none { it == LayerScheduler.JOB_FLUSH })
        runtime.state.update { it.copy(brainUrl = "https://brain.example.com", brainToken = "t") }
        runtime.pipeline.onNotification("com.whatsapp", "Marta", "otro mensaje")
        assertTrue(jobIds().any { it == LayerScheduler.JOB_FLUSH })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = ZenApp::class, sdk = [34])
class LayerMaintenanceTest {
    private val app: ZenApp = ApplicationProvider.getApplicationContext()
    private val runtime get() = app.layer

    @Before fun reset() {
        LayerLive.audioRunning = false
        LayerLive.accessibilityConnected = false
        LayerLive.listenerConnected = false
    }

    @Test fun maintenanceCreatesTheDeviceIdAndSkipsASyncWithoutABrain() {
        LayerMaintenance.run(app)
        assertTrue(runtime.state.current.deviceId.isNotBlank())
        assertNull(runtime.state.current.lastSyncOkAtMs)
    }

    @Test fun staleDraftsExpireAndOrphanAudioIsDeleted() {
        val now = System.currentTimeMillis()
        runtime.state.update { it.copy(drafts = listOf(Draft("d", "k", "p", "c", "t", now - 10_000, now - 1))) }
        val orphan = runtime.audioFiles.save(PcmSegment(ShortArray(160), 0, 16_000))
        val queued = runtime.audioFiles.save(PcmSegment(ShortArray(160), 0, 16_000))
        runtime.outbox.enqueue(OutboxItem("q", OutboxItem.Kind.AUDIO, queued, now))
        val old = now - 10 * 60_000L
        File(app.filesDir, "layer/audio/$orphan").setLastModified(old)
        File(app.filesDir, "layer/audio/$queued").setLastModified(old)

        LayerMaintenance.run(app, now)

        assertEquals(DraftStatus.EXPIRED, runtime.state.current.drafts.single().status)
        assertNull(runtime.audioFiles.file(orphan))
        assertNotNull("lo que sigue en la cola se conserva", runtime.audioFiles.file(queued))
    }

    @Test fun oldQueuedItemsAreDroppedAndTheirAudioWithThem() {
        val now = System.currentTimeMillis()
        val name = runtime.audioFiles.save(PcmSegment(ShortArray(160), 0, 16_000))
        runtime.outbox.enqueue(OutboxItem("old", OutboxItem.Kind.AUDIO, name, now - 5L * 24 * 3_600_000))
        LayerMaintenance.run(app, now)
        assertEquals(0, runtime.outbox.count())
        assertNull(runtime.audioFiles.file(name))
    }

    @Test fun aMicrophoneThatShouldBeListeningButIsNotRaisesAnAttentionNotice() {
        runtime.state.update { it.copy(capture = it.capture.copy(ambientAudio = true), brainUrl = "https://brain.example.com", brainToken = "t") }
        LayerMaintenance.run(app)
        assertTrue(app.titles().contains("El micrófono se detuvo"))
    }

    @Test fun noMicrophoneNoticeWhenItIsRunningOrNotWanted() {
        LayerMaintenance.run(app)
        assertFalse(app.titles().contains("El micrófono se detuvo"))
        runtime.state.update { it.copy(capture = it.capture.copy(ambientAudio = true), brainUrl = "https://brain.example.com", brainToken = "t") }
        LayerLive.audioRunning = true
        LayerMaintenance.run(app)
        assertFalse(app.titles().contains("El micrófono se detuvo"))
    }

    @Test fun oneBrokenStepDoesNotStopTheOthers() {
        // Un cerebro con dirección inválida: la sincronización se salta y el resto del mantenimiento sigue.
        runtime.state.update { it.copy(brainUrl = "http://example.com", brainToken = "t", drafts = listOf(Draft("d", "k", "p", "c", "t", 1, 2))) }
        LayerMaintenance.run(app, 1_000L)
        assertEquals(DraftStatus.EXPIRED, runtime.state.current.drafts.single().status)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = ZenApp::class, sdk = [34])
class AccessibilityHealerTest {
    private val app: ZenApp = ApplicationProvider.getApplicationContext()
    private val runtime get() = app.layer
    private val now = 10 * 60 * 60_000L

    @Before fun reset() {
        LayerLive.accessibilityConnected = false
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
    }

    private fun grantSecure() = shadowOf(app as Application).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
    private fun want() = runtime.state.update { it.copy(capture = it.capture.copy(screenText = true)) }
    private fun enabledList() = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    private fun list(on: Boolean) = Settings.Secure.putString(
        app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        if (on) AccessibilitySetting.withEnabled("otra/app.Servicio", AccessibilityHealer.component(app)) else "otra/app.Servicio",
    )

    @Test fun theComponentNameIsTheFlattenedServiceName() {
        assertEquals(ComponentName(app, LayerAccessibilityService::class.java).flattenToString(), AccessibilityHealer.component(app))
    }

    @Test fun nothingHappensWhenTheLayerDoesNotNeedAccessibility() {
        assertEquals(HealDecision.Healthy, AccessibilityHealer.run(app, now))
        assertEquals("", enabledList())
    }

    @Test fun aServiceThatIsSwitchedOffIsSwitchedOnWhenWeMayWrite() {
        want(); grantSecure(); list(false)
        assertEquals(HealDecision.Enable, AccessibilityHealer.run(app, now))
        assertTrue(AccessibilityHealer.isListed(app))
        assertTrue("sin pisar a las demás", enabledList().contains("otra/app.Servicio"))
        assertEquals(listOf(now), runtime.state.current.repairsMs)
    }

    @Test fun withoutThePermissionItOnlyTellsTheUserAndOnlyEverySixHours() {
        want(); list(false)
        assertEquals(HealDecision.NeedUser(HealDecision.Reason.NOT_ENABLED), AccessibilityHealer.run(app, now))
        assertEquals("Accesibilidad apagada", app.titles().single())
        assertEquals(now, runtime.state.current.lastRepairNoticeAtMs)
        assertFalse("no se escribió nada", AccessibilityHealer.isListed(app))
        // Una hora después no se vuelve a avisar.
        AccessibilityHealer.run(app, now + 3_600_000L)
        assertEquals(now, runtime.state.current.lastRepairNoticeAtMs)
        // Pasadas 6 horas, sí.
        AccessibilityHealer.run(app, now + 6 * 3_600_000L)
        assertEquals(now + 6 * 3_600_000L, runtime.state.current.lastRepairNoticeAtMs)
    }

    @Test fun aHealthyServiceClearsAnOldDownMark() {
        want(); list(true); LayerLive.accessibilityConnected = true
        runtime.state.update { it.copy(accessibilityDownSinceMs = now - 1_000) }
        assertEquals(HealDecision.Healthy, AccessibilityHealer.run(app, now))
        assertNull(runtime.state.current.accessibilityDownSinceMs)
    }

    @Test fun aListedButDeadServiceWaitsThenRestarts() {
        want(); grantSecure(); list(true)
        assertEquals(HealDecision.Wait(now), AccessibilityHealer.run(app, now))
        assertEquals(now, runtime.state.current.accessibilityDownSinceMs)
        // Aún dentro de la gracia: espera.
        assertTrue(AccessibilityHealer.run(app, now + 30_000L) is HealDecision.Wait)
        // Pasada la gracia: lo apaga y lo vuelve a encender.
        assertEquals(HealDecision.Restart, AccessibilityHealer.run(app, now + SelfHealPolicy.GRACE_MS))
        assertTrue(AccessibilityHealer.isListed(app))
        assertEquals(1, runtime.state.current.repairsMs.size)
        assertNull(runtime.state.current.accessibilityDownSinceMs)
    }

    @Test fun aDeadServiceWithoutPermissionAsksTheUserToToggleIt() {
        want(); list(true)
        runtime.state.update { it.copy(accessibilityDownSinceMs = now - SelfHealPolicy.GRACE_MS) }
        assertEquals(HealDecision.NeedUser(HealDecision.Reason.NOT_RESPONDING), AccessibilityHealer.run(app, now))
        assertEquals("La accesibilidad no responde", app.titles().single())
    }

    @Test fun repairsAreCappedSoItNeverFlapsForever() {
        want(); grantSecure(); list(false)
        runtime.state.update { it.copy(repairsMs = listOf(now - 1 * 3_600_000L, now - 2 * 3_600_000L, now - 3 * 3_600_000L, now - 4 * 3_600_000L)) }
        assertEquals(HealDecision.NeedUser(HealDecision.Reason.REPAIR_LIMIT), AccessibilityHealer.run(app, now))
        assertFalse(AccessibilityHealer.isListed(app))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = ZenApp::class, sdk = [34])
class LayerStatusTest {
    private val app: ZenApp = ApplicationProvider.getApplicationContext()

    @Test fun theHealthSnapshotReflectsTheStateAndTheLiveFlags() {
        LayerLive.accessibilityConnected = true
        app.layer.state.update {
            it.copy(capture = it.capture.copy(screenText = true), limits = listOf(LimitRule("a.b", "A", 1)), autostartConfirmed = true, lastSyncOkAtMs = 5L)
        }
        val input = LayerStatus.healthInput(app, 100L)
        assertTrue(input.settings.screenText)
        assertTrue(input.hasLimits)
        assertTrue(input.accessibilityConnected)
        assertFalse(input.accessibilityListed)
        assertTrue(input.autostartConfirmed)
        assertEquals(5L, input.lastSyncOkAtMs)
        assertEquals(100L, input.nowMs)
        assertFalse(input.brainConfigured)
        LayerLive.accessibilityConnected = false
    }

    @Test fun microphoneAndBatteryReadFromTheSystem() {
        assertFalse(LayerStatus.hasMicrophone(app))
        shadowOf(app as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(LayerStatus.hasMicrophone(app))
        assertFalse(LayerStatus.isBatteryUnrestricted(app))
    }

    @Test fun theShortcutsPointWhereTheyShould() {
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, LayerIntents.accessibility().action)
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, LayerIntents.notificationAccess().action)
        val battery = LayerIntents.batteryExemption(app)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, battery.action)
        assertEquals("package:${app.packageName}", battery.dataString)
        assertEquals("com.miui.securitycenter", LayerIntents.xiaomiAutostart().component!!.packageName)
        val xiaomiBattery = LayerIntents.xiaomiBattery(app)
        assertEquals("com.miui.powerkeeper", xiaomiBattery.component!!.packageName)
        assertEquals(app.packageName, xiaomiBattery.getStringExtra("package_name"))
        assertEquals("ZEN Xiaomi", xiaomiBattery.getStringExtra("package_label"))
    }

    @Test fun openFirstReturnsFalseWhenNothingOpens() {
        // Sin ninguna pantalla que resuelva, no debe lanzar: devuelve false para que la interfaz use otro plan.
        val shadowApp = shadowOf(app as Application)
        shadowApp.checkActivities(true)
        assertFalse(LayerIntents.openFirst(app, Intent("com.example.NO_EXISTE")))
    }

    @Test fun openFirstSkipsAMissingScreenAndUsesTheNext() {
        val shadowApp = shadowOf(app as Application)
        shadowApp.checkActivities(true)
        val settings = Intent(Settings.ACTION_SETTINGS)
        shadowOf(app.packageManager).addResolveInfoForIntent(settings, android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply { packageName = "com.android.settings"; name = "Settings" }
        })
        assertTrue(LayerIntents.openFirst(app, Intent("com.example.NO_EXISTE"), settings))
        assertEquals(Settings.ACTION_SETTINGS, shadowApp.nextStartedActivity.action)
    }

    @Test fun xiaomiIsDetectedFromTheBrand() {
        assertFalse(LayerStatus.isXiaomi())
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "MANUFACTURER", "Xiaomi")
        try {
            assertTrue(LayerStatus.isXiaomi())
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "MANUFACTURER", "robolectric")
        }
    }
}
