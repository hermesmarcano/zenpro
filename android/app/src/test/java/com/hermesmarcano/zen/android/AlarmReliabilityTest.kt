package com.hermesmarcano.zen.android

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.hermesmarcano.zen.ZenApp
import com.hermesmarcano.zen.alarm.AlarmActions
import com.hermesmarcano.zen.alarm.AlarmReceiver
import com.hermesmarcano.zen.alarm.AlarmRuntime
import com.hermesmarcano.zen.alarm.AlarmScheduler
import com.hermesmarcano.zen.alarm.AlarmService
import com.hermesmarcano.zen.alarm.BootReceiver
import com.hermesmarcano.zen.alarm.Notifications
import com.hermesmarcano.zen.alarm.RingState
import com.hermesmarcano.zen.core.AlarmHealth
import com.hermesmarcano.zen.core.AlarmHealth.Kind
import com.hermesmarcano.zen.core.Achievement
import com.hermesmarcano.zen.core.Weekday
import com.hermesmarcano.zen.data.AlarmEntity
import com.hermesmarcano.zen.data.QrAnchor
import com.hermesmarcano.zen.data.ZenState
import com.hermesmarcano.zen.ui.HomeScreen
import com.hermesmarcano.zen.ui.MainActivity
import com.hermesmarcano.zen.ui.ReliabilityScreen
import com.hermesmarcano.zen.ui.ZenTheme
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val MIN = 60_000L
private const val HOUR = 60 * MIN

/** Diagnóstico de alarmas: qué llegó, cuándo, y qué hace la app cuando el sistema no entrega el aviso. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ZenApp::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AlarmReliabilityTest {
    @get:Rule val compose = createComposeRule()
    private val app: ZenApp = ApplicationProvider.getApplicationContext()
    private val repository get() = app.repository
    private val log get() = app.alarmLog
    private val alarmManager get() = app.getSystemService(AlarmManager::class.java)
    private val notifications get() = app.getSystemService(NotificationManager::class.java)
    private val anchor = QrAnchor("anchor1", "baño", "secret", "123456")

    @Before fun reset() {
        AlarmRuntime.end()
        repository.update { ZenState(emptyList(), listOf(anchor)) }
        compose.mainClock.autoAdvance = false
        // El registro es de toda la sesión de pruebas de esta JVM: se vacía con un nuevo repositorio lógico.
        clearLog()
    }

    @After fun tearDown() = AlarmRuntime.end()

    private fun clearLog() = log.clear()

    private fun alarm(next: Long?, days: Set<Weekday> = Weekday.entries.toSet(), enabled: Boolean = true) =
        AlarmEntity("al1", 5, 30, days, enabled, anchor.id, next)

    private fun fire(id: String) =
        AlarmReceiver().onReceive(app, Intent(AlarmScheduler.ACTION_FIRE).putExtra(AlarmScheduler.EXTRA_ALARM_ID, id))

    private fun kinds() = log.events.value.map { it.kind }

    // --- Registro de lo que hace el sistema ---

    @Test fun schedulingIsLoggedWithTheTimeItWasSetFor() {
        AlarmActions.save(app, AlarmEntity.new(5, 30, emptySet(), anchor.id), enabled = true)
        val scheduled = log.events.value.single { it.kind == Kind.SCHEDULED }
        assertEquals(repository.state.value.alarms.single().nextFireAt, scheduled.expectedAt)
    }

    @Test fun anOnTimeDeliveryIsLoggedAsOnTime() {
        val now = System.currentTimeMillis()
        repository.update { it.copy(alarms = listOf(alarm(next = now - 2_000))) }
        fire("al1")
        val event = log.events.value.single { it.kind == Kind.FIRED }
        assertFalse(AlarmHealth.isLate(event))
        assertNotNull(shadowOf(app).nextStartedService)
    }

    @Test fun aLateDeliveryStillRingsAndLeavesAProblemOnRecord() {
        val now = System.currentTimeMillis()
        repository.update { it.copy(alarms = listOf(alarm(next = now - 2 * HOUR))) }
        fire("al1")
        assertEquals("el aviso retrasado suena igualmente", AlarmService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
        val event = log.events.value.single { it.kind == Kind.FIRED }
        assertTrue(AlarmHealth.isLate(event))
        assertEquals(1, AlarmHealth.problems(log.events.value, System.currentTimeMillis(), 0).size)
        assertTrue(repository.state.value.alarm("al1")!!.nextFireAt!! > now)
    }

    @Test fun aDiscardedDeliveryExplainsWhy() {
        repository.update { it.copy(alarms = listOf(alarm(next = null, enabled = false))) }
        fire("al1")
        fire("missing")
        val notes = log.events.value.filter { it.kind == Kind.DISCARDED }.map { it.note }
        assertEquals(listOf("estaba desactivada", "ya no existe"), notes)
    }

    @Test fun bootAndTimeChangesAreLoggedWithTheirReason() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_TIME_CHANGED))
        BootReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        BootReceiver().onReceive(app, Intent("otra.cosa"))
        assertEquals(listOf("cambio de hora", "actualización de ZEN"), log.events.value.filter { it.kind == Kind.RESTORED }.map { it.note })
    }

    // --- Alarmas que el sistema no entregó ---

    @Test fun anOverdueRepeatingAlarmIsRecordedAsMissedAndMovedToItsNextTime() {
        val now = System.currentTimeMillis()
        repository.update { it.copy(alarms = listOf(alarm(next = now - 84 * MIN))) }
        val missed = AlarmActions.healOverdue(app, now)
        assertEquals(listOf("al1"), missed.map { it.id })
        val event = log.events.value.single { it.kind == Kind.MISSED }
        assertEquals(now - 84 * MIN, event.expectedAt)
        val after = repository.state.value.alarm("al1")!!
        assertTrue(after.enabled)
        assertTrue("ya no se queda en el pasado", after.nextFireAt!! > now)
        assertEquals(after.nextFireAt, shadowOf(alarmManager).nextScheduledAlarm!!.triggerAtTime)
    }

    @Test fun anOverdueOneShotIsTurnedOff() {
        val now = System.currentTimeMillis()
        repository.update { it.copy(alarms = listOf(alarm(next = now - HOUR, days = emptySet()))) }
        AlarmActions.healOverdue(app, now)
        assertFalse(repository.state.value.alarm("al1")!!.enabled)
        assertTrue(kinds().contains(Kind.MISSED))
    }

    @Test fun aSlightlyLateAlarmIsLeftAloneBecauseTheDeliveryMayStillArrive() {
        val now = System.currentTimeMillis()
        repository.update { it.copy(alarms = listOf(alarm(next = now - 2 * MIN))) }
        assertTrue(AlarmActions.healOverdue(app, now).isEmpty())
        assertEquals(now - 2 * MIN, repository.state.value.alarm("al1")!!.nextFireAt)
        assertTrue(log.events.value.none { it.kind == Kind.MISSED })
    }

    @Test fun nothingIsHealedWhileAnAlarmIsRinging() {
        val now = System.currentTimeMillis()
        repository.update { it.copy(alarms = listOf(alarm(next = now - HOUR))) }
        AlarmRuntime.begin(RingState("al1", 0.0, anchor.id))
        assertTrue(AlarmActions.healOverdue(app, now).isEmpty())
        assertEquals(now - HOUR, repository.state.value.alarm("al1")!!.nextFireAt)
    }

    @Test fun openingTheAppHealsAfterAShortWaitSoALateDeliveryCanRingFirst() {
        val now = System.currentTimeMillis()
        repository.update { it.copy(alarms = listOf(alarm(next = now - 84 * MIN))) }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertTrue("aún no", log.events.value.none { it.kind == Kind.MISSED })
        shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.SECONDS)
        assertTrue(kinds().contains(Kind.MISSED))
        assertTrue(repository.state.value.alarm("al1")!!.nextFireAt!! > now)
        activity.pause().stop().destroy()
    }

    @Test fun ifTheLateDeliveryArrivesDuringTheWaitNothingIsMarkedMissed() {
        val now = System.currentTimeMillis()
        repository.update { it.copy(alarms = listOf(alarm(next = now - 84 * MIN))) }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        fire("al1") // el sistema entrega por fin el aviso retrasado
        AlarmRuntime.begin(RingState("al1", 0.0, anchor.id))
        shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.SECONDS)
        assertTrue(log.events.value.none { it.kind == Kind.MISSED })
        activity.pause().stop().destroy()
    }

    // --- Prueba de 1 minuto ---

    @Test fun theSelfTestGoesThroughTheRealSchedulerAndIsLogged() {
        val at = AlarmActions.startSelfTest(app, System.currentTimeMillis())
        assertEquals(at, shadowOf(alarmManager).nextScheduledAlarm!!.triggerAtTime)
        assertEquals(at, log.events.value.single { it.kind == Kind.TEST_SCHEDULED }.expectedAt)
        AlarmActions.cancelSelfTest(app)
        assertNull(shadowOf(alarmManager).nextScheduledAlarm)
    }

    private fun notificationTitle() =
        shadowOf(notifications).getNotification(Notifications.ID_DIAGNOSTIC)?.extras?.getString("android.title")

    @Test fun anOnTimeSelfTestPassesGivesTheAchievementAndNeverRings() {
        AlarmActions.startSelfTest(app, System.currentTimeMillis() - 59_000)
        fire(AlarmHealth.SELF_TEST_ID)
        assertEquals("Prueba superada", notificationTitle())
        assertNull("la prueba no hace sonar la alarma de verdad", shadowOf(app).nextStartedService)
        assertEquals(1, repository.state.value.progress.alarmTestsPassed)
        assertTrue(Achievement.ALARM_CHECKED.id in repository.state.value.progress.achievements)
        assertEquals(0, repository.state.value.progress.xp)
        val result = AlarmHealth.lastTestResult(log.events.value)!!
        assertFalse(AlarmHealth.isLate(result))
    }

    @Test fun aLateSelfTestIsReportedAndDoesNotCount() {
        AlarmActions.startSelfTest(app, System.currentTimeMillis() - 10 * MIN)
        fire(AlarmHealth.SELF_TEST_ID)
        assertEquals("La prueba llegó tarde", notificationTitle())
        assertEquals(0, repository.state.value.progress.alarmTestsPassed)
        assertTrue(Achievement.ALARM_CHECKED.id !in repository.state.value.progress.achievements)
        assertTrue(AlarmHealth.isLate(AlarmHealth.lastTestResult(log.events.value)!!))
    }

    @Test fun cancellingTheTestIsNotAFailure() {
        AlarmActions.startSelfTest(app, System.currentTimeMillis() - 5 * MIN)
        AlarmActions.cancelSelfTest(app)
        assertTrue(kinds().contains(Kind.TEST_CANCELLED))
        assertNull(AlarmHealth.pendingTest(log.events.value, System.currentTimeMillis()))
        assertFalse(AlarmHealth.testFailed(log.events.value, System.currentTimeMillis()))
    }

    @Test fun theSelfTestIsNeverTreatedAsARealAlarm() {
        AlarmActions.startSelfTest(app, System.currentTimeMillis() - 59_000)
        fire(AlarmHealth.SELF_TEST_ID)
        assertTrue(repository.state.value.alarms.isEmpty())
        assertTrue(log.events.value.none { it.kind == Kind.FIRED || it.kind == Kind.DISCARDED })
    }

    // --- Pantallas ---

    @Test fun homeNeverSaysLessThanOneMinuteForAnAlarmThatAlreadyPassed() {
        val now = System.currentTimeMillis()
        repository.update { it.copy(alarms = listOf(alarm(next = now - 84 * MIN).copy(hour = 5, minute = 30))) }
        compose.setContent { ZenTheme { HomeScreen(onNewAlarm = {}, onEditAlarm = {}) } }
        compose.onNodeWithText("No sonó a su hora (05:30).").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTextCount("Suena en menos de 1 min") == 0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

    @Test fun homeShowsTheMissedAlarmCardAndDismissingItSticks() {
        val now = System.currentTimeMillis()
        log.record(AlarmHealth.Event(now - MIN, Kind.MISSED, "al1", now - 84 * MIN))
        var opened = false
        compose.setContent { ZenTheme { HomeScreen(onNewAlarm = {}, onEditAlarm = {}, onOpenReliability = { opened = true }) } }
        compose.onNodeWithText("Una alarma no sonó a su hora").assertIsDisplayed()
        compose.onNodeWithText("Cómo arreglarlo").performClick()
        assertTrue(opened)
        compose.onNodeWithText("Entendido").performClick()
        compose.mainClock.advanceTimeBy(300)
        assertTrue(repository.state.value.settings.diagnosticsAckAt > 0)
        assertEquals(0, compose.onAllNodesWithTextCount("Una alarma no sonó a su hora"))
    }

    @Test fun homeWithoutProblemsStaysQuiet() {
        compose.setContent { ZenTheme { HomeScreen(onNewAlarm = {}, onEditAlarm = {}) } }
        assertEquals(0, compose.onAllNodesWithTextCount("Una alarma no sonó a su hora"))
        compose.onNodeWithText("¿Suena a su hora? Fiabilidad de la alarma").assertIsDisplayed()
    }

    @Test fun reliabilityScreenStartsATestAndShowsTheLog() {
        val now = System.currentTimeMillis()
        log.record(AlarmHealth.Event(now - HOUR, Kind.MISSED, "al1", now - 2 * HOUR))
        compose.setContent { ZenTheme { ReliabilityScreen(androidx.compose.ui.Modifier, onBack = {}) } }
        compose.onNodeWithText("Prueba de 1 minuto").assertIsDisplayed()
        // La lista es perezosa: el registro está más abajo y hay que desplazarse hasta él.
        compose.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(androidx.compose.ui.test.hasText("No sonó la de las", substring = true))
        compose.onNode(androidx.compose.ui.test.hasText("No sonó la de las", substring = true)).assertExists()
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Empezar prueba"))
        compose.onNodeWithText("Empezar prueba").performClick()
        compose.mainClock.advanceTimeBy(1_500)
        assertNotNull(shadowOf(alarmManager).nextScheduledAlarm)
        assertTrue(kinds().contains(Kind.TEST_SCHEDULED))
        compose.onNodeWithText("Cancelar prueba").assertExists()
    }

    @Test fun reliabilityScreenReportsAFailedTest() {
        val now = System.currentTimeMillis()
        log.record(AlarmHealth.Event(now - 5 * MIN, Kind.TEST_SCHEDULED, AlarmHealth.SELF_TEST_ID, now - 4 * MIN))
        compose.setContent { ZenTheme { ReliabilityScreen(androidx.compose.ui.Modifier, onBack = {}) } }
        compose.onNodeWithText("La última prueba no llegó.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Empezar prueba").assertExists()
    }
}
