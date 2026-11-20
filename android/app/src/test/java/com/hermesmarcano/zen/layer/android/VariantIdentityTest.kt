package com.hermesmarcano.zen.layer.android

import androidx.test.core.app.ApplicationProvider
import com.hermesmarcano.zen.BuildConfig
import com.hermesmarcano.zen.R
import com.hermesmarcano.zen.ZenApp
import com.hermesmarcano.zen.layer.ui.adbGrantCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Esta rama es una variante PERSONAL, separada de la app normal (ver CLAUDE.md). Estas pruebas fijan lo que la separa:
 * si alguien le devuelve el ID o el nombre de la app normal, la instalación pisaría a ZEN y compartiría sus datos.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ZenApp::class, sdk = [34])
class VariantIdentityTest {
    private val app: ZenApp = ApplicationProvider.getApplicationContext()

    @Test fun theVariantHasItsOwnApplicationId() {
        assertEquals("com.hermesmarcano.zen.xiaomi", BuildConfig.APPLICATION_ID)
        assertNotEquals("com.hermesmarcano.zen", BuildConfig.APPLICATION_ID)
        assertTrue(BuildConfig.VERSION_NAME.endsWith("-xiaomi"))
    }

    @Test fun theVariantHasItsOwnNameEverywhereTheUserSeesIt() {
        assertEquals("ZEN Xiaomi", app.getString(R.string.app_name))
        // Si las dos apps están instaladas, en Accesibilidad y en Acceso a notificaciones deben poder distinguirse.
        assertTrue(app.getString(R.string.layer_accessibility_label).startsWith("ZEN Xiaomi"))
        assertTrue(app.getString(R.string.layer_listener_label).startsWith("ZEN Xiaomi"))
    }

    @Test fun theAdbCommandNamesThisVariantNotTheNormalApp() {
        val command = adbGrantCommand("com.hermesmarcano.zen.xiaomi")
        assertEquals("adb shell pm grant com.hermesmarcano.zen.xiaomi android.permission.WRITE_SECURE_SETTINGS", command)
    }
}
