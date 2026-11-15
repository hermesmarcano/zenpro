package com.hermesmarcano.zen.android

import android.Manifest
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.hermesmarcano.zen.ZenApp
import com.hermesmarcano.zen.alarm.AlarmRuntime
import com.hermesmarcano.zen.alarm.RingState
import com.hermesmarcano.zen.data.AlarmEntity
import com.hermesmarcano.zen.data.PhotoAnchor
import com.hermesmarcano.zen.data.QrAnchor
import com.hermesmarcano.zen.data.ZenState
import com.hermesmarcano.zen.ui.AlarmEditScreen
import com.hermesmarcano.zen.ui.HomeScreen
import com.hermesmarcano.zen.ui.PhotoAnchorSetup
import com.hermesmarcano.zen.ui.QrAnchorSetup
import com.hermesmarcano.zen.ui.RingingScreen
import com.hermesmarcano.zen.ui.SolveMethod
import com.hermesmarcano.zen.ui.ZenTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Humo de las pantallas: que compongan sin lanzar y muestren lo esencial. No sustituye probar en un teléfono. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ZenApp::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UiSmokeTest {
    @get:Rule val compose = createComposeRule()
    private val app: ZenApp = ApplicationProvider.getApplicationContext()
    private val qr = QrAnchor("qa", "Espejo del baño", "secret", "123456")
    private val photo = PhotoAnchor("pa", "Patio", "654321", listOf("ref-1.jpg"))

    @Before fun reset() {
        AlarmRuntime.end()
        app.repository.update { ZenState(emptyList(), listOf(qr, photo)) }
    }

    @After fun tearDown() = AlarmRuntime.end()

    /**
     * Las pantallas tienen relojes que se reprograman solos (`delay` en bucle). Con el reloj de la
     * prueba en avance automático, cada vuelta cambia un valor y Compose nunca queda "en reposo":
     * el avance se hace a mano donde hace falta (advanceTimeBy).
     */
    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { ZenTheme { content() } }
    }

    // --- Inicio ---

    @Test fun homeShowsEmptyStateAndPermissionCard() {
        show { HomeScreen(onNewAlarm = {}, onEditAlarm = {}) }
        compose.onNodeWithText("Aún no tienes alarmas", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Nueva alarma").assertIsDisplayed()
        compose.onNodeWithText("Para que la alarma funcione bien").assertIsDisplayed()
    }

    @Test fun homeListsAlarmsWithTheirAnchorAndCountdown() {
        val next = System.currentTimeMillis() + 3 * 3_600_000L
        app.repository.update { it.copy(alarms = listOf(AlarmEntity("a", 7, 5, emptySet(), true, qr.id, next))) }
        show { HomeScreen(onNewAlarm = {}, onEditAlarm = {}) }
        compose.onNodeWithText("07:05").assertIsDisplayed()
        compose.onNodeWithText("Se apaga en: Espejo del baño").assertIsDisplayed()
        compose.onNodeWithText("Suena en", substring = true).assertIsDisplayed()
    }

    @Test fun alarmWithoutAnchorIsFlagged() {
        app.repository.update { it.copy(alarms = listOf(AlarmEntity("a", 7, 0, emptySet(), false, null, null))) }
        show { HomeScreen(onNewAlarm = {}, onEditAlarm = {}) }
        compose.onNodeWithText("Falta elegir el lugar").assertIsDisplayed()
    }

    @Test fun newAlarmButtonNavigates() {
        var clicked = false
        show { HomeScreen(onNewAlarm = { clicked = true }, onEditAlarm = {}) }
        compose.onNodeWithText("Nueva alarma").performClick()
        assertTrue(clicked)
    }

    // --- Editor ---

    @Test fun editorRequiresAnAnchorBeforeSaving() {
        app.repository.update { it.copy(anchors = emptyList()) }
        show { AlarmEditScreen(alarmId = null, onClose = {}) }
        compose.onNodeWithText("Nueva alarma").assertIsDisplayed()
        compose.onNodeWithText("Crea un lugar", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Guardar y activar").assertIsDisplayed()
    }

    @Test fun editorSavesAnAlarmForTheChosenAnchor() {
        var closed = false
        show { AlarmEditScreen(alarmId = null, onClose = { closed = true }) }
        compose.onNodeWithText("Espejo del baño").performClick()
        compose.onNodeWithText("Guardar y activar").performClick()
        compose.waitForIdle()
        assertTrue(closed)
        val saved = app.repository.state.value.alarms.single()
        assertEquals(qr.id, saved.anchorId)
        assertTrue(saved.enabled)
    }

    // --- Altas de ancla ---

    @Test fun qrSetupWalksThroughLabelAndShowsTheCode() {
        show { QrAnchorSetup(onDone = {}, onCancel = {}) }
        compose.onNodeWithText("¿Dónde vas a pegar el código?").assertIsDisplayed()
        compose.onNodeWithText("Generar código").assertIsDisplayed()
    }

    @Test fun photoSetupStartsAtTheLabelStep() {
        show { PhotoAnchorSetup(onDone = {}, onCancel = {}) }
        compose.onNodeWithText("¿Qué lugar vas a fotografiar?").assertIsDisplayed()
    }

    // --- Éxito, ajustes y racha ---

    /**
     * `performScrollTo` anima el desplazamiento y espera fotogramas: con el reloj parado se cuelga. Solo
     * es seguro en pantallas sin relojes en bucle (Ajustes no tiene ninguno).
     */
    private fun scrollingNeedsFrames() {
        compose.mainClock.autoAdvance = true
    }

    @Test fun completionScreenShowsTheStreakAfterVerifyingTheAnchor() {
        show {
            com.hermesmarcano.zen.ui.CompletionScreen(
                com.hermesmarcano.zen.ui.Completion(SolveMethod.ANCHOR, 72, 7, 9, 31, streakGrew = true, newBest = false, milestone = "Una semana entera."),
                onContinue = {},
            )
        }
        compose.onNodeWithText("Despierto.").assertIsDisplayed()
        compose.onNodeWithText("Llegaste en 1 min 12 s.").assertIsDisplayed()
        compose.onNodeWithText("7 días").assertIsDisplayed()
        compose.onNodeWithText("Una semana entera.").assertIsDisplayed()
        compose.onNodeWithText("Empezar el día").assertIsDisplayed()
    }

    @Test fun completionScreenDoesNotRepeatTheMilestoneOnASecondWakeTheSameDay() {
        show {
            com.hermesmarcano.zen.ui.CompletionScreen(
                com.hermesmarcano.zen.ui.Completion(SolveMethod.ANCHOR, 20, 7, 7, 32, streakGrew = false, newBest = false, milestone = "Una semana entera."),
                onContinue = {},
            )
        }
        compose.onNodeWithText("Una semana entera.").assertDoesNotExist()
    }

    @Test fun completionScreenForAnEmergencyStopIsHonestAboutIt() {
        var closed = false
        show {
            com.hermesmarcano.zen.ui.CompletionScreen(
                com.hermesmarcano.zen.ui.Completion(SolveMethod.EMERGENCY, 130, 0, 0, 0, false, false, null),
                onContinue = { closed = true },
            )
        }
        compose.onNodeWithText("Alarma apagada.").assertIsDisplayed()
        compose.onNodeWithText("Sin verificar el lugar", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Empezar el día").performClick()
        assertTrue(closed)
    }

    @Test fun homeShowsTheCurrentStreakAndOpensSettings() {
        app.repository.update { it.copy(stats = com.hermesmarcano.zen.data.WakeStats(3, 5, 12, java.time.LocalDate.now().toEpochDay(), 40)) }
        var opened = false
        show { HomeScreen(onNewAlarm = {}, onEditAlarm = {}, onOpenSettings = { opened = true }) }
        compose.onNodeWithText("Racha: 3 días").assertIsDisplayed()
        compose.onNodeWithText("Ajustes").performClick()
        assertTrue(opened)
    }

    @Test fun homeHidesAStreakThatWasBroken() {
        app.repository.update { it.copy(stats = com.hermesmarcano.zen.data.WakeStats(3, 5, 12, java.time.LocalDate.now().minusDays(5).toEpochDay(), 40)) }
        show { HomeScreen(onNewAlarm = {}, onEditAlarm = {}) }
        compose.onNodeWithText("Racha", substring = true).assertDoesNotExist()
    }

    @Test fun settingsListsTheSoundsAndPersistsTheChoices() {
        show { com.hermesmarcano.zen.ui.SettingsScreen(onClose = {}) }
        scrollingNeedsFrames()
        compose.onNodeWithText("Sonido de la alarma").assertIsDisplayed()
        compose.onNodeWithText("Clásico").assertIsDisplayed()
        compose.onNodeWithText("Campana").performScrollTo().performClick()
        assertEquals(com.hermesmarcano.zen.data.SoundChoice.Preset("bell"), app.repository.state.value.settings.alarmSound)
        compose.onNodeWithText("30 %").performScrollTo().performClick()
        assertEquals(30, app.repository.state.value.settings.startVolumePercent)
        compose.onNodeWithText("Fanfarria").performScrollTo().performClick()
        assertEquals(com.hermesmarcano.zen.data.SoundChoice.Preset("fanfare"), app.repository.state.value.settings.completionSound)
    }

    @Test fun settingsShowsTheDefaultsAndTheStats() {
        app.repository.update { it.copy(stats = com.hermesmarcano.zen.data.WakeStats(2, 8, 40, java.time.LocalDate.now().toEpochDay(), 50)) }
        show { com.hermesmarcano.zen.ui.SettingsScreen(onClose = {}) }
        scrollingNeedsFrames()
        compose.onNodeWithText("Volumen inicial").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("15 %").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Despertares verificados").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("40").performScrollTo().assertIsDisplayed()
    }

    // --- Pantalla de apagado ---

    private fun ring(alarmId: String = "al", anchor: String = qr.id) {
        app.repository.update { it.copy(alarms = listOf(AlarmEntity(alarmId, 6, 30, emptySet(), false, anchor, null))) }
        AlarmRuntime.begin(RingState(alarmId, SystemClock.elapsedRealtime() / 1000.0, anchor))
    }

    @Test fun ringingScreenForQrOffersScanAndGatesTheBackupCode() {
        ring()
        show { RingingScreen(onSolved = {}, onNothingRinging = {}) }
        compose.onNodeWithText("Ve a: Espejo del baño").assertIsDisplayed()
        compose.onNodeWithText("Escanear el código").assertIsDisplayed()
        compose.onNodeWithText("Código de respaldo disponible en", substring = true).assertIsDisplayed()
    }

    @Test fun deletingTheAlarmWhileRingingDoesNotRemoveTheAnchorRequirement() {
        ring()
        app.repository.update { it.copy(alarms = emptyList()) }
        show { RingingScreen(onSolved = {}, onNothingRinging = {}) }
        compose.onNodeWithText("Ve a: Espejo del baño").assertIsDisplayed()
        compose.onNodeWithText("Escanear el código").assertIsDisplayed()
    }

    @Test fun ringingScreenForPhotoOffersTheCamera() {
        ring(anchor = photo.id)
        show { RingingScreen(onSolved = {}, onNothingRinging = {}) }
        compose.onNodeWithText("Ve a: Patio").assertIsDisplayed()
        compose.onNodeWithText("Tomar la foto del lugar").assertIsDisplayed()
    }

    @Test fun backupCodeAppearsOnlyAfterTheDelay() {
        ring()
        show { RingingScreen(onSolved = {}, onNothingRinging = {}) }
        compose.onNodeWithText("Código de respaldo disponible en", substring = true).assertIsDisplayed()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(125))
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        compose.onNodeWithText("Apagar con el código").assertIsDisplayed()
    }

    @Test fun anAlarmWithoutAnchorCanStillBeStoppedAfterTheDelay() {
        ring(anchor = "deleted")
        show { RingingScreen(onSolved = {}, onNothingRinging = {}) }
        compose.onNodeWithText("Podrás apagarla en", substring = true).assertIsDisplayed()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(125))
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        compose.onNodeWithText("Apagar alarma").assertIsDisplayed()
    }

    @Test fun ringingScreenClosesItselfWhenNothingIsRinging() {
        var closed = false
        show { RingingScreen(onSolved = {}, onNothingRinging = { closed = true }) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertTrue(closed)
    }

    @Test fun openingTheScannerWithoutCameraHardwareFailsGracefully() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        ring()
        show { RingingScreen(onSolved = {}, onNothingRinging = {}) }
        compose.onNodeWithText("Escanear el código").performClick()
        compose.waitForIdle()
        // Sin cámara (JVM) debe volver a la pantalla con un aviso, no colgarse ni lanzar.
        compose.onNodeWithText("Ve a: Espejo del baño").assertIsDisplayed()
    }
}
