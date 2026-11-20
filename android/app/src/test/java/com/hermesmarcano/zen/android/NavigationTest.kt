package com.hermesmarcano.zen.android

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.hermesmarcano.zen.ZenApp
import com.hermesmarcano.zen.alarm.AlarmRuntime
import com.hermesmarcano.zen.core.Achievement
import com.hermesmarcano.zen.core.TripPolicy
import com.hermesmarcano.zen.data.AlarmEntity
import com.hermesmarcano.zen.data.Progress
import com.hermesmarcano.zen.data.QrAnchor
import com.hermesmarcano.zen.data.Trip
import com.hermesmarcano.zen.data.ZenState
import com.hermesmarcano.zen.ui.MainActivity
import com.hermesmarcano.zen.ui.ProgressScreen
import com.hermesmarcano.zen.ui.TripScreen
import com.hermesmarcano.zen.ui.ZenTheme
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ZenApp::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TripAndProgressUiTest {
    @get:Rule val compose = createComposeRule()
    private val app: ZenApp = ApplicationProvider.getApplicationContext()
    private val repo get() = app.repository
    private val today = LocalDate.now()
    private val home = QrAnchor("home", "Espejo del baño", "s", "123456")
    private val hotel = QrAnchor("hotel", "Hotel", "s2", "654321")
    private val announced = mutableListOf<String>()
    private var closed = false

    @Before fun reset() {
        repo.update { ZenState(anchors = listOf(home, hotel)) }
        announced.clear(); closed = false
    }

    private fun showTrip() {
        compose.mainClock.autoAdvance = false
        compose.setContent { ZenTheme { TripScreen(onClose = { closed = true }, announce = { t, _, _ -> announced += t }) } }
    }

    @Test fun theTripScreenOffersPauseOrAnotherPlace() {
        showTrip()
        compose.onNodeWithText("Pausar mis alarmas").assertIsDisplayed()
        compose.onNodeWithText("Apagarlas en otro lugar").assertIsDisplayed()
        compose.onNodeWithText("Activar modo viaje").assertIsDisplayed()
    }

    @Test fun startingAPauseStoresItAndCloses() {
        repo.update { it.copy(alarms = listOf(AlarmEntity.new(6, 30, com.hermesmarcano.zen.core.Weekday.entries.toSet(), "home"))) }
        showTrip()
        compose.onNodeWithText("Activar modo viaje").performClick()
        compose.mainClock.advanceTimeBy(300)
        assertEquals(Trip(today.toEpochDay() + 2, null), repo.state.value.trip) // 3 días por defecto, hoy cuenta
        assertTrue(closed)
        assertTrue(announced.single().startsWith("Modo viaje activado"))
        assertTrue(announced.single().contains("Logro: Con la maleta lista"))
    }

    @Test fun preparedAtNightOneDayCoversTomorrowsAlarm() {
        val tomorrowAt = today.plusDays(1).atTime(6, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        repo.update { it.copy(alarms = listOf(AlarmEntity("a", 6, 30, com.hermesmarcano.zen.core.Weekday.entries.toSet(), true, "home", tomorrowAt))) }
        showTrip()
        compose.onNodeWithText("1").performClick() // un día
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("desde tu próxima alarma", substring = true).assertExists()
        compose.onNodeWithText("Activar modo viaje").performClick()
        compose.mainClock.advanceTimeBy(300)
        assertEquals(Trip(today.toEpochDay() + 1, null), repo.state.value.trip)
    }

    @Test fun anotherPlaceNeedsAnAnchorBeforeItCanStart() {
        showTrip()
        compose.onNodeWithText("Apagarlas en otro lugar").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("¿Dónde te apagarás?").assertIsDisplayed()
        compose.onNodeWithText("Hotel").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Activar modo viaje").performClick()
        compose.mainClock.advanceTimeBy(300)
        assertEquals(Trip(today.toEpochDay() + 2, "hotel"), repo.state.value.trip)
    }

    @Test fun anActiveTripCanBeEndedAndTheAnchorIsProtected() {
        repo.update { it.copy(trip = TripPolicy.start(today.toEpochDay(), 4, "hotel")) }
        assertTrue(repo.state.value.isAnchorInUse("hotel"))
        showTrip()
        compose.onNodeWithText("Te apagas en Hotel", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Terminar modo viaje ahora").performClick()
        compose.mainClock.advanceTimeBy(300)
        assertNull(repo.state.value.trip)
        assertTrue(closed)
    }

    @Test fun progressShowsLevelStreaksAndLockedAndUnlockedAchievements() {
        repo.update {
            it.copy(
                stats = com.hermesmarcano.zen.data.WakeStats(3, 5, 12, today.toEpochDay(), 40),
                progress = Progress(xp = 150, tasksCompleted = 9, achievements = mapOf(Achievement.FIRST_WAKE.id to today.toEpochDay())),
            )
        }
        compose.setContent { ZenTheme { ProgressScreen(androidx.compose.ui.Modifier, onOpenSettings = {}) } }
        compose.onNodeWithText("Nivel 2").assertIsDisplayed()
        compose.onNodeWithText("Nivel 2 · Constante").assertIsDisplayed()
        compose.onNodeWithText("Tareas completadas").assertIsDisplayed()
        // La lista es perezosa y ahora es larga (estadísticas de entreno + más logros): se desplaza hasta cada elemento.
        val list = compose.onNode(androidx.compose.ui.test.hasScrollAction())
        list.performScrollToNode(androidx.compose.ui.test.hasText("Logros · 1 de ${Achievement.entries.size}"))
        compose.onNodeWithText("Logros · 1 de ${Achievement.entries.size}").assertExists()
        list.performScrollToNode(androidx.compose.ui.test.hasText("Primer despertar"))
        compose.onNodeWithText("Primer despertar").assertExists()
        list.performScrollToNode(androidx.compose.ui.test.hasText("Un mes"))
        compose.onNodeWithText("Un mes").assertExists()
        list.performScrollToNode(androidx.compose.ui.test.hasText("Diez toneladas"))
        compose.onNodeWithText("Diez toneladas").assertExists()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ZenApp::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AppShellTest {
    private val app: ZenApp = ApplicationProvider.getApplicationContext()

    @Before fun reset() {
        AlarmRuntime.end()
        app.repository.update { ZenState() }
    }

    @After fun tearDown() = AlarmRuntime.end()

    private fun launch(intent: Intent? = null): org.robolectric.android.controller.ActivityController<MainActivity> {
        val builder = if (intent != null) Robolectric.buildActivity(MainActivity::class.java, intent) else Robolectric.buildActivity(MainActivity::class.java)
        return builder.setup()
    }

    @Test fun theAppOpensOnTodayWithTheThreeTabs() {
        val activity = launch()
        assertNotNull(activity.get())
        activity.pause().stop().destroy()
    }

    @Test fun anIntentCanAskForTheTodayTab() {
        val intent = Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_TODAY)
        val activity = launch(intent)
        assertEquals(MainActivity.ROUTE_TODAY, activity.get().intent.getStringExtra(MainActivity.EXTRA_ROUTE))
        activity.pause().stop().destroy()
    }
}
