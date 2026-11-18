package com.hermesmarcano.zen.android

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.hermesmarcano.zen.ZenApp
import com.hermesmarcano.zen.alarm.RestReceiver
import com.hermesmarcano.zen.alarm.RestTimer
import com.hermesmarcano.zen.core.ExerciseLibrary
import com.hermesmarcano.zen.core.RestToneSynth
import com.hermesmarcano.zen.core.VolumeCurve
import com.hermesmarcano.zen.data.RestState
import com.hermesmarcano.zen.data.SessionExercise
import com.hermesmarcano.zen.data.SetEntry
import com.hermesmarcano.zen.data.WorkoutData
import com.hermesmarcano.zen.data.WorkoutSession
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

/** El cronómetro de descanso fuera de la app: alarma exacta, notificación y receptor. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ZenApp::class, sdk = [34])
class RestTimerTest {
    private val app: ZenApp = ApplicationProvider.getApplicationContext()
    private val alarms get() = app.getSystemService(AlarmManager::class.java)
    private val notifications get() = app.getSystemService(NotificationManager::class.java)

    @Before fun reset() {
        app.workouts.update { WorkoutData() }
        RestTimer.cancel(app)
    }

    private fun sessionWithRest(rest: RestState?) = WorkoutSession(
        "s", null, "Libre", System.currentTimeMillis() - 60_000, null,
        listOf(SessionExercise(ExerciseLibrary.preset("bench_press")!!, 90, null, listOf(SetEntry(60.0, 8, done = true, completedAt = 1L), SetEntry(60.0, 8)))),
        rest = rest,
    )

    @Test fun syncSchedulesTheAlarmAndShowsACountdownNotification() {
        val ends = System.currentTimeMillis() + 90_000
        RestTimer.sync(app, RestState(System.currentTimeMillis(), ends, 90))
        assertEquals(ends, shadowOf(alarms).nextScheduledAlarm!!.triggerAtTime)
        val n = shadowOf(notifications).getNotification(RestTimer.ID_NOTIFICATION)
        assertNotNull(n)
        assertTrue(n.extras.getBoolean("android.chronometerCountDown"))
        assertTrue(n.extras.getBoolean("android.showChronometer"))
    }

    @Test fun syncWithoutRestCancelsEverything() {
        RestTimer.sync(app, RestState(System.currentTimeMillis(), System.currentTimeMillis() + 60_000, 60))
        RestTimer.sync(app, null)
        assertNull(shadowOf(alarms).nextScheduledAlarm)
        assertNull(shadowOf(notifications).getNotification(RestTimer.ID_NOTIFICATION))
    }

    @Test fun reschedulingReplacesTheOldAlarm() {
        val now = System.currentTimeMillis()
        RestTimer.sync(app, RestState(now, now + 60_000, 60))
        RestTimer.sync(app, RestState(now, now + 75_000, 75))
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
        assertEquals(now + 75_000, shadowOf(alarms).nextScheduledAlarm!!.triggerAtTime)
    }

    @Test fun anAlreadyOverRestDoesNotScheduleAnything() {
        val now = System.currentTimeMillis()
        RestTimer.sync(app, RestState(now - 90_000, now - 1_000, 90))
        assertNull(shadowOf(alarms).nextScheduledAlarm)
    }

    private fun fire(endsAt: Long) =
        RestReceiver().onReceive(app, Intent(RestTimer.ACTION_DONE).putExtra(RestTimer.EXTRA_ENDS_AT, endsAt))

    @Test fun theReceiverNotifiesWhenTheRestStillMatches() {
        val ends = System.currentTimeMillis() + 1_000
        app.workouts.update { it.copy(active = sessionWithRest(RestState(ends - 90_000, ends, 90))) }
        fire(ends)
        val n = shadowOf(notifications).getNotification(RestTimer.ID_NOTIFICATION)
        assertNotNull(n)
        assertFalse("es el aviso de terminado, no la cuenta atrás", n.extras.getBoolean("android.showChronometer"))
    }

    @Test fun theReceiverIgnoresAStaleAlarm() {
        val ends = System.currentTimeMillis() + 60_000
        app.workouts.update { it.copy(active = sessionWithRest(RestState(ends - 90_000, ends, 90))) }
        fire(ends - 30_000) // la hora se cambió con +/- 15 s o se saltó el descanso
        assertNull(shadowOf(notifications).getNotification(RestTimer.ID_NOTIFICATION))
    }

    @Test fun theReceiverIgnoresItWhenThereIsNoSessionOrNoRest() {
        fire(System.currentTimeMillis())
        app.workouts.update { it.copy(active = sessionWithRest(null)) }
        fire(System.currentTimeMillis())
        assertNull(shadowOf(notifications).getNotification(RestTimer.ID_NOTIFICATION))
    }

    @Test fun theReceiverIgnoresOtherActions() {
        val ends = System.currentTimeMillis()
        app.workouts.update { it.copy(active = sessionWithRest(RestState(ends - 90_000, ends, 90))) }
        RestReceiver().onReceive(app, Intent("otra.accion").putExtra(RestTimer.EXTRA_ENDS_AT, ends))
        assertNull(shadowOf(notifications).getNotification(RestTimer.ID_NOTIFICATION))
    }

    @Test fun theReceiverIsPrivateAndRegistered() {
        val info = app.packageManager.getReceiverInfo(ComponentName(app, RestReceiver::class.java), 0)
        assertFalse(info.exported)
    }

    @Test fun manifestRequestsExactAlarms() {
        val info = app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toSet()
        assertTrue("android.permission.USE_EXACT_ALARM" in requested)
    }

    @Test fun theRestSoundIsShortAndQuiet() {
        val samples = RestToneSynth.samples(44_100)
        assertTrue(samples.size / 44_100.0 < 1.0)
        assertTrue(samples.maxOf { kotlin.math.abs(it) } <= 0.81f)
        assertTrue("más bajo que un sonido a tope", VolumeCurve.gain(VolumeCurve.REST_LEVEL) < 0.35f)
        assertTrue(VolumeCurve.REST_LEVEL > VolumeCurve.COMPLETION_LEVEL / 2)
    }
}
