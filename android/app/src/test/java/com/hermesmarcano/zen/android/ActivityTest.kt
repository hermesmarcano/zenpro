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
import org.junit.After
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

    @Test fun ringingActivityStopsTheServiceAndClearsStateWhenSolved() {
        AlarmRuntime.begin(RingState("al", 0.0, null))
        val activity = launch(RingingActivity::class.java)
        val method = RingingActivity::class.java.getDeclaredMethod("stopAlarm").apply { isAccessible = true }
        method.invoke(activity)
        val stop = shadowOf(app).nextStartedService
        assertEquals(Intent(app, com.hermesmarcano.zen.alarm.AlarmService::class.java).setAction(com.hermesmarcano.zen.alarm.AlarmService.ACTION_STOP).action, stop.action)
        assertNull(AlarmRuntime.ring.value)
        assertTrue(activity.isFinishing)
    }
}
