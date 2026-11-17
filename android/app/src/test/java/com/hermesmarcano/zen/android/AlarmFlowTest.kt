package com.hermesmarcano.zen.android

import android.app.AlarmManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.hermesmarcano.zen.ZenApp
import com.hermesmarcano.zen.alarm.AlarmActions
import com.hermesmarcano.zen.alarm.AlarmReceiver
import com.hermesmarcano.zen.alarm.AlarmRuntime
import com.hermesmarcano.zen.alarm.AlarmScheduler
import com.hermesmarcano.zen.alarm.AlarmService
import com.hermesmarcano.zen.alarm.BootReceiver
import com.hermesmarcano.zen.core.Achievement
import com.hermesmarcano.zen.core.Weekday
import com.hermesmarcano.zen.data.Trip
import com.hermesmarcano.zen.data.AlarmEntity
import com.hermesmarcano.zen.data.QrAnchor
import com.hermesmarcano.zen.data.ZenState
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = ZenApp::class, sdk = [34])
class AlarmFlowTest {
    private val app: ZenApp = ApplicationProvider.getApplicationContext()
    private val repository get() = app.repository
    private val alarmManager get() = app.getSystemService(AlarmManager::class.java)
    private val anchor = QrAnchor("anchor1", "Espejo", "secret", "123456")

    @Before fun reset() {
        AlarmRuntime.end()
        repository.update { ZenState(emptyList(), listOf(anchor)) }
    }

    @After fun tearDown() = AlarmRuntime.end()

    private fun alarm(id: String = "al1", days: Set<Weekday> = emptySet(), enabled: Boolean = true, next: Long? = System.currentTimeMillis()) =
        AlarmEntity(id, 6, 30, days, enabled, anchor.id, next)

    private fun fire(id: String) =
        AlarmReceiver().onReceive(app, Intent(AlarmScheduler.ACTION_FIRE).putExtra(AlarmScheduler.EXTRA_ALARM_ID, id))

    // --- Programación ---

    @Test fun savingAnAlarmSchedulesAnAlarmClock() {
        AlarmActions.save(app, AlarmEntity.new(6, 30, emptySet(), anchor.id), enabled = true)
        val scheduled = shadowOf(alarmManager).nextScheduledAlarm
        assertNotNull(scheduled)
        val stored = repository.state.value.alarms.single()
        assertTrue(stored.enabled)
        assertEquals(stored.nextFireAt, scheduled!!.triggerAtTime)
        assertNotNull("debe ser una alarma de reloj (con showIntent)", scheduled.showIntent)
    }

    @Test fun disablingOrDeletingCancelsTheSystemAlarm() {
        AlarmActions.save(app, AlarmEntity.new(6, 30, emptySet(), anchor.id), enabled = true)
        val id = repository.state.value.alarms.single().id
        AlarmActions.setEnabled(app, id, false)
        assertNull(shadowOf(alarmManager).nextScheduledAlarm)
        AlarmActions.setEnabled(app, id, true)
        assertNotNull(shadowOf(alarmManager).nextScheduledAlarm)
        AlarmActions.delete(app, id)
        assertNull(shadowOf(alarmManager).nextScheduledAlarm)
        assertTrue(repository.state.value.alarms.isEmpty())
    }

    @Test fun twoAlarmsAreScheduledIndependently() {
        AlarmActions.save(app, AlarmEntity.new(6, 30, emptySet(), anchor.id), enabled = true)
        AlarmActions.save(app, AlarmEntity.new(7, 45, emptySet(), anchor.id), enabled = true)
        assertEquals(2, shadowOf(alarmManager).scheduledAlarms.size)
    }

    // --- Disparo ---

    @Test fun dueAlarmStartsTheServiceAndOneShotTurnsOff() {
        repository.update { it.copy(alarms = listOf(alarm())) }
        fire("al1")
        val started = shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(AlarmService::class.java.name, started.component!!.className)
        assertEquals("al1", started.getStringExtra(AlarmService.EXTRA_ALARM_ID))
        val after = repository.state.value.alarm("al1")!!
        assertFalse(after.enabled)
        assertNull(after.nextFireAt)
    }

    @Test fun repeatingAlarmIsRescheduledAfterFiring() {
        repository.update { it.copy(alarms = listOf(alarm(days = Weekday.entries.toSet()))) }
        fire("al1")
        assertNotNull(shadowOf(app).nextStartedService)
        val after = repository.state.value.alarm("al1")!!
        assertTrue(after.enabled)
        assertTrue(after.nextFireAt!! > System.currentTimeMillis())
        assertEquals(after.nextFireAt, shadowOf(alarmManager).nextScheduledAlarm!!.triggerAtTime)
    }

    @Test fun staleOrUnknownFiringsAreIgnored() {
        repository.update { it.copy(alarms = listOf(alarm(next = System.currentTimeMillis() + 3_600_000), alarm(id = "off", enabled = false, next = null))) }
        fire("al1")      // aún no toca (PendingIntent obsoleto)
        fire("off")      // desactivada
        fire("missing")  // borrada
        assertNull(shadowOf(app).nextStartedService)
    }

    // --- Reinicio / cambio de hora ---

    @Test fun bootReschedulesEnabledAlarmsAndDropsMissedOneShots() {
        val now = System.currentTimeMillis()
        repository.update {
            it.copy(
                alarms = listOf(
                    alarm(id = "future", next = now + 3_600_000),
                    alarm(id = "missed", next = now - 3_600_000),
                    alarm(id = "repeat", days = Weekday.entries.toSet(), next = now - 3_600_000),
                ),
            )
        }
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        val state = repository.state.value
        assertTrue(state.alarm("future")!!.enabled)
        assertFalse(state.alarm("missed")!!.enabled)
        assertTrue(state.alarm("repeat")!!.nextFireAt!! > now)
        assertEquals(2, shadowOf(alarmManager).scheduledAlarms.size)
    }

    @Test fun bootJustAfterTheAlarmTimeStillRingsIt() {
        val now = System.currentTimeMillis()
        repository.update { it.copy(alarms = listOf(alarm(id = "just", next = now - 60_000))) }
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(repository.state.value.alarm("just")!!.enabled)
        // Programada en el pasado: el sistema la entrega enseguida y el receptor la acepta.
        fire("just")
        assertNotNull(shadowOf(app).nextStartedService)
    }

    @Test fun unrelatedBroadcastsDoNothing() {
        repository.update { it.copy(alarms = listOf(alarm(next = System.currentTimeMillis() - 3_600_000))) }
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BATTERY_LOW))
        assertTrue(repository.state.value.alarm("al1")!!.enabled)
    }

    // --- Servicio ---

    private fun startService(alarmId: String): org.robolectric.android.controller.ServiceController<AlarmService> {
        val controller = Robolectric.buildService(AlarmService::class.java, AlarmService.ringIntent(app, alarmId))
        controller.create().startCommand(0, 1)
        return controller
    }

    @Test fun serviceRingsInForegroundAndStopsOnRequest() {
        val controller = startService("al1")
        val service = controller.get()
        assertEquals("al1", AlarmRuntime.ring.value?.alarmId)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        Thread.sleep(400) // deja correr el bucle de volumen unas vueltas: no debe lanzar
        assertEquals("al1", AlarmRuntime.ring.value?.alarmId)

        controller.withIntent(AlarmService.stopIntent(app)).startCommand(0, 2)
        assertNull(AlarmRuntime.ring.value)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test fun ringRemembersTheAnchorEvenIfTheAlarmIsDeletedWhileRinging() {
        repository.update { it.copy(alarms = listOf(alarm())) }
        val controller = startService("al1")
        assertEquals(anchor.id, AlarmRuntime.ring.value?.anchorId)
        AlarmActions.delete(app, "al1")
        assertEquals(anchor.id, AlarmRuntime.ring.value?.anchorId)
        controller.withIntent(AlarmService.stopIntent(app)).startCommand(0, 2)
    }

    @Test fun silentAlarmStreamIsRaisedWhileRingingAndRestoredAfterwards() {
        val audio = app.getSystemService(android.media.AudioManager::class.java)
        audio.setStreamVolume(android.media.AudioManager.STREAM_ALARM, 0, 0)
        val controller = startService("al1")
        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
        assertTrue(audio.getStreamVolume(android.media.AudioManager.STREAM_ALARM) >= (max * 0.6).toInt())
        controller.withIntent(AlarmService.stopIntent(app)).startCommand(0, 2)
        assertEquals(0, audio.getStreamVolume(android.media.AudioManager.STREAM_ALARM))
    }

    @Test fun alreadyLoudStreamIsLeftUntouched() {
        val audio = app.getSystemService(android.media.AudioManager::class.java)
        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
        audio.setStreamVolume(android.media.AudioManager.STREAM_ALARM, max, 0)
        val controller = startService("al1")
        controller.withIntent(AlarmService.stopIntent(app)).startCommand(0, 2)
        assertEquals(max, audio.getStreamVolume(android.media.AudioManager.STREAM_ALARM))
    }

    @Test fun secondAlarmDoesNotReplaceTheFirstOne() {
        val controller = startService("first")
        controller.withIntent(AlarmService.ringIntent(app, "second")).startCommand(0, 2)
        assertEquals("first", AlarmRuntime.ring.value?.alarmId)
        controller.withIntent(AlarmService.stopIntent(app)).startCommand(0, 3)
        assertNull(AlarmRuntime.ring.value)
    }

    @Test fun stopWithoutRingingIsHarmless() {
        val controller = Robolectric.buildService(AlarmService::class.java, AlarmService.stopIntent(app))
        controller.create().startCommand(0, 1)
        assertNull(AlarmRuntime.ring.value)
    }

    @Test fun destroyingTheServiceWhileRingingClearsSharedState() {
        val controller = startService("al1")
        controller.destroy()
        assertNull(AlarmRuntime.ring.value)
    }

    @Test fun ringIntentWithoutAlarmIdStopsInsteadOfRingingSilently() {
        val controller = Robolectric.buildService(AlarmService::class.java, Intent(app, AlarmService::class.java).setAction(AlarmService.ACTION_RING))
        controller.create().startCommand(0, 1)
        assertNull(AlarmRuntime.ring.value)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = ZenApp::class, sdk = [34])
class TripFlowTest {
    private val app: ZenApp = ApplicationProvider.getApplicationContext()
    private val repository get() = app.repository
    private val alarmManager get() = app.getSystemService(AlarmManager::class.java)
    private val home = QrAnchor("home", "Espejo", "s1", "111111")
    private val hotel = QrAnchor("hotel", "Hotel", "s2", "222222")
    private val today get() = java.time.LocalDate.now()

    @Before fun reset() {
        AlarmRuntime.end()
        repository.update { ZenState(emptyList(), listOf(home, hotel)) }
    }

    @After fun tearDown() = AlarmRuntime.end()

    private fun repeatingAlarm(id: String = "al") = AlarmEntity.new(6, 30, Weekday.entries.toSet(), home.id).copy(id = id)

    @Test fun pausingMovesRepeatingAlarmsPastThePauseAndSchedulesThem() {
        AlarmActions.save(app, repeatingAlarm(), enabled = true)
        val before = repository.state.value.alarm("al")!!.nextFireAt!!
        AlarmActions.startTrip(app, com.hermesmarcano.zen.core.TripPolicy.start(today.toEpochDay(), 3, null))
        val after = repository.state.value.alarm("al")!!
        val firstDayAfter = today.plusDays(3).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertTrue("el aviso debe quedar después de la pausa", after.nextFireAt!! >= firstDayAfter)
        assertTrue(after.nextFireAt!! > before)
        assertEquals(after.nextFireAt, shadowOf(alarmManager).nextScheduledAlarm!!.triggerAtTime)
        assertTrue(Achievement.TRAVELER.id in repository.state.value.progress.achievements)
    }

    @Test fun aOneShotInsideThePauseIsDisabledAndCancelled() {
        AlarmActions.save(app, AlarmEntity.new(6, 30, emptySet(), home.id), enabled = true)
        AlarmActions.startTrip(app, com.hermesmarcano.zen.core.TripPolicy.start(today.toEpochDay(), 3, null))
        assertFalse(repository.state.value.alarms.single().enabled)
        assertNull(shadowOf(alarmManager).nextScheduledAlarm)
    }

    @Test fun savingAOneShotDuringAPauseReportsThatItWillNotRing() {
        AlarmActions.startTrip(app, com.hermesmarcano.zen.core.TripPolicy.start(today.toEpochDay(), 5, null))
        val saved = AlarmActions.save(app, AlarmEntity.new(6, 30, emptySet(), home.id), enabled = true)
        assertFalse("la interfaz usa esto para avisar", saved.enabled)
        val toggled = AlarmActions.setEnabled(app, saved.id, true)
        assertFalse(toggled!!.enabled)
        assertNull(AlarmActions.setEnabled(app, "no-existe", true))
    }

    @Test fun endingThePauseEarlyBringsTheAlarmBack() {
        AlarmActions.save(app, repeatingAlarm(), enabled = true)
        val original = repository.state.value.alarm("al")!!.nextFireAt!!
        AlarmActions.startTrip(app, com.hermesmarcano.zen.core.TripPolicy.start(today.toEpochDay(), 7, null))
        AlarmActions.endTrip(app)
        assertNull(repository.state.value.trip)
        assertEquals(original, repository.state.value.alarm("al")!!.nextFireAt)
        assertEquals(original, shadowOf(alarmManager).nextScheduledAlarm!!.triggerAtTime)
    }

    @Test fun savingAnAlarmDuringAPauseSchedulesItAfterThePause() {
        AlarmActions.startTrip(app, com.hermesmarcano.zen.core.TripPolicy.start(today.toEpochDay(), 5, null))
        AlarmActions.save(app, repeatingAlarm(), enabled = true)
        val firstDayAfter = today.plusDays(5).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertTrue(repository.state.value.alarm("al")!!.nextFireAt!! >= firstDayAfter)
    }

    @Test fun aFiringDuringThePauseIsIgnoredAndTheAlarmRescheduled() {
        repository.update { it.copy(alarms = listOf(repeatingAlarm().copy(enabled = true, nextFireAt = System.currentTimeMillis()))) }
        repository.update { it.copy(trip = Trip(today.toEpochDay() + 2, null)) }
        AlarmReceiver().onReceive(app, Intent(AlarmScheduler.ACTION_FIRE).putExtra(AlarmScheduler.EXTRA_ALARM_ID, "al"))
        assertNull("en pausa no debe sonar", shadowOf(app).nextStartedService)
        val next = repository.state.value.alarm("al")!!.nextFireAt!!
        assertTrue(next >= today.plusDays(3).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
    }

    @Test fun theTravelAnchorIsRequiredWhileTheTripLasts() {
        repository.update { it.copy(alarms = listOf(repeatingAlarm()), trip = Trip(today.toEpochDay() + 2, hotel.id)) }
        val controller = Robolectric.buildService(AlarmService::class.java, AlarmService.ringIntent(app, "al"))
        controller.create().startCommand(0, 1)
        assertEquals("hotel", AlarmRuntime.ring.value?.anchorId)
        controller.withIntent(AlarmService.stopIntent(app)).startCommand(0, 2)
        AlarmRuntime.end()
    }

    @Test fun afterTheTripTheHomeAnchorIsBack() {
        repository.update { it.copy(alarms = listOf(repeatingAlarm()), trip = Trip(today.toEpochDay() - 1, hotel.id)) }
        val controller = Robolectric.buildService(AlarmService::class.java, AlarmService.ringIntent(app, "al"))
        controller.create().startCommand(0, 1)
        assertEquals("home", AlarmRuntime.ring.value?.anchorId)
        controller.withIntent(AlarmService.stopIntent(app)).startCommand(0, 2)
    }

    @Test fun ifTheTravelAnchorWasDeletedTheAlarmStillHasAnAnchor() {
        repository.update { it.copy(alarms = listOf(repeatingAlarm()), trip = Trip(today.toEpochDay() + 2, "borrada")) }
        val controller = Robolectric.buildService(AlarmService::class.java, AlarmService.ringIntent(app, "al"))
        controller.create().startCommand(0, 1)
        assertEquals("home", AlarmRuntime.ring.value?.anchorId)
        controller.withIntent(AlarmService.stopIntent(app)).startCommand(0, 2)
    }

    @Test fun anExpiredTripIsClearedWhenTheAppStarts() {
        repository.update { it.copy(trip = Trip(today.toEpochDay() - 3, null)) }
        // Lo que hace ZenApp.onCreate: la limpieza usa las mismas reglas.
        val today = today.toEpochDay()
        repository.update { it.copy(trip = it.trip?.takeIf { t -> com.hermesmarcano.zen.core.TripPolicy.isActive(t, today) }) }
        assertNull(repository.state.value.trip)
    }
}
