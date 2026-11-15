package com.hermesmarcano.zen.android

import android.content.Intent
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.hermesmarcano.zen.ZenApp
import com.hermesmarcano.zen.alarm.AlarmRuntime
import com.hermesmarcano.zen.alarm.RingState
import com.hermesmarcano.zen.data.QrAnchor
import com.hermesmarcano.zen.data.ZenState
import com.hermesmarcano.zen.ui.MainActivity
import com.hermesmarcano.zen.ui.RingingActivity
import com.hermesmarcano.zen.ui.SolveMethod
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ZenApp::class, sdk = [34])
class ActivityTest {
    private val app: ZenApp = ApplicationProvider.getApplicationContext()

    private val controllers = mutableListOf<org.robolectric.android.controller.ActivityController<*>>()

    /**
     * Las pantallas tienen efectos que se reprograman solos: una actividad que se queda viva sigue
     * recomponiendo durante las pruebas siguientes y Compose nunca llega a "reposo". Hay que destruirlas.
     */
    @Before fun reset() {
        app.repository.update { ZenState() }
    }

    @After fun tearDown() {
        controllers.forEach { runCatching { it.pause().stop().destroy() } }
        controllers.clear()
        AlarmRuntime.end()
    }

    private fun <T : android.app.Activity> launch(clazz: Class<T>): T {
        val controller = Robolectric.buildActivity(clazz).also { controllers += it }.setup()
        return controller.get()
    }

    @Test fun mainActivityOpensNormallyWhenNothingRings() {
        AlarmRuntime.end()
        val activity = launch(MainActivity::class.java)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun mainActivityRedirectsToTheRingingScreenWhenAnAlarmIsRinging() {
        AlarmRuntime.begin(RingState("al", 0.0, "a"))
        val activity = launch(MainActivity::class.java)
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(RingingActivity::class.java.name, started.component!!.className)
    }

    @Test fun ringingActivityStaysOnScreenAndSwallowsBack() {
        app.repository.update { ZenState(emptyList(), listOf(QrAnchor("a", "Espejo", "s", "123456"))) }
        AlarmRuntime.begin(RingState("al", android.os.SystemClock.elapsedRealtime() / 1000.0, "a"))
        val activity = launch(RingingActivity::class.java)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue("atrás no debe cerrar la pantalla de apagado", !activity.isFinishing)
    }

    private fun solve(activity: RingingActivity, method: SolveMethod) {
        val m = RingingActivity::class.java.getDeclaredMethod("stopAlarm", SolveMethod::class.java).apply { isAccessible = true }
        m.invoke(activity, method)
    }

    @Test fun solvingAtTheAnchorStopsTheServiceCountsTheStreakAndShowsTheCompletion() {
        AlarmRuntime.begin(RingState("al", 0.0, null))
        val activity = launch(RingingActivity::class.java)
        solve(activity, SolveMethod.ANCHOR)
        val stop = shadowOf(app).nextStartedService
        assertEquals(com.hermesmarcano.zen.alarm.AlarmService.ACTION_STOP, stop.action)
        assertNull(AlarmRuntime.ring.value)
        assertTrue("la pantalla de éxito sigue abierta", !activity.isFinishing)
        val stats = app.repository.state.value.stats
        assertEquals(1, stats.currentStreak)
        assertEquals(1, stats.totalWakeups)
        assertEquals(java.time.LocalDate.now().toEpochDay(), stats.lastWakeDay)
    }

    @Test fun backClosesTheCompletionScreenButNeverTheRingingOne() {
        AlarmRuntime.begin(RingState("al", 0.0, null))
        val activity = launch(RingingActivity::class.java)
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue("sonando: atrás no cierra", !activity.isFinishing)
        solve(activity, SolveMethod.ANCHOR)
        assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0)
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue("apagada: atrás cierra la pantalla de éxito", activity.isFinishing)
    }

    @Test fun anEmergencyStopNeitherCountsNorBreaksTheStreak() {
        app.repository.update { it.copy(stats = com.hermesmarcano.zen.data.WakeStats(4, 6, 20, java.time.LocalDate.now().minusDays(1).toEpochDay(), 30)) }
        AlarmRuntime.begin(RingState("al", 0.0, null))
        val activity = launch(RingingActivity::class.java)
        solve(activity, SolveMethod.EMERGENCY)
        assertNull(AlarmRuntime.ring.value)
        assertEquals(4, app.repository.state.value.stats.currentStreak)
        assertEquals(20, app.repository.state.value.stats.totalWakeups)
        assertTrue(!activity.isFinishing)
    }

    @Test fun twoSolvesTheSameDayDoNotInflateTheStreak() {
        AlarmRuntime.begin(RingState("al", 0.0, null))
        solve(launch(RingingActivity::class.java), SolveMethod.ANCHOR)
        AlarmRuntime.begin(RingState("al2", 0.0, null))
        solve(launch(RingingActivity::class.java), SolveMethod.ANCHOR)
        val stats = app.repository.state.value.stats
        assertEquals(1, stats.currentStreak)
        assertEquals(2, stats.totalWakeups)
    }
}
