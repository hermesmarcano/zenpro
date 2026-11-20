package com.hermesmarcano.zen.android

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.hermesmarcano.zen.ZenApp
import com.hermesmarcano.zen.alarm.AlarmRuntime
import com.hermesmarcano.zen.alarm.AlarmService
import com.hermesmarcano.zen.alarm.CompletionPlayer
import com.hermesmarcano.zen.alarm.SoundPreview
import com.hermesmarcano.zen.alarm.TonePlayer
import com.hermesmarcano.zen.core.VolumeCurve
import com.hermesmarcano.zen.data.AlarmEntity
import com.hermesmarcano.zen.data.QrAnchor
import com.hermesmarcano.zen.data.SoundChoice
import com.hermesmarcano.zen.data.ZenSettings
import com.hermesmarcano.zen.data.ZenState
import com.hermesmarcano.zen.sound.SoundStorage
import java.io.File
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = ZenApp::class, sdk = [34])
class SoundFeatureTest {
    private val app: ZenApp = ApplicationProvider.getApplicationContext()
    private val soundsDir get() = File(app.filesDir, "sounds")
    private val originalReader = SoundStorage.durationReader

    @Before fun reset() {
        AlarmRuntime.end()
        app.repository.update { ZenState() }
        soundsDir.deleteRecursively()
    }

    @After fun tearDown() {
        SoundStorage.durationReader = originalReader
        AlarmRuntime.end()
    }

    private fun tempAudio(bytes: Int, name: String = "src.mp3"): Uri {
        val f = File(app.cacheDir, name).apply { writeBytes(ByteArray(bytes) { (it % 251).toByte() }) }
        return Uri.fromFile(f)
    }

    // --- Volumen inicial: el defecto que reportó el usuario ---

    @Test fun theAlarmStartsAtAQuietGainByDefault() {
        val player = TonePlayer(app)
        player.start(ZenSettings().startVolume)
        assertTrue("ganancia inicial ${player.currentGain}", player.currentGain < 0.05f)
        assertEquals(VolumeCurve.gain(0.15f), player.currentGain, 1e-6f)
        player.stop()
    }

    @Test fun volumeFollowsThePerceptualCurve() {
        val player = TonePlayer(app).also { it.start(0.1f) }
        player.setVolume(0.5f)
        assertEquals(VolumeCurve.gain(0.5f), player.currentGain, 1e-6f)
        player.setVolume(1f)
        assertEquals(1f, player.currentGain, 1e-6f)
        player.stop()
    }

    @Test fun theServiceStartsQuietForEveryStartVolumeTheUserCanPick() {
        for (percent in ZenSettings.START_VOLUME_OPTIONS) {
            app.repository.update { it.copy(settings = it.settings.copy(startVolumePercent = percent)) }
            val controller = Robolectric.buildService(AlarmService::class.java, AlarmService.ringIntent(app, "al"))
            controller.create().startCommand(0, 1)
            val gain = controller.get().currentToneGain
            assertTrue("$percent %: ganancia $gain", gain in 0f..0.16f)
            assertEquals("$percent %", VolumeCurve.gain(percent / 100f), gain, 0.01f)
            controller.withIntent(AlarmService.stopIntent(app)).startCommand(0, 2)
            AlarmRuntime.end()
        }
    }

    @Test fun lowerStartVolumeMeansQuieterStart() {
        fun startGain(percent: Int): Float {
            app.repository.update { it.copy(settings = it.settings.copy(startVolumePercent = percent)) }
            val controller = Robolectric.buildService(AlarmService::class.java, AlarmService.ringIntent(app, "al"))
            controller.create().startCommand(0, 1)
            val gain = controller.get().currentToneGain
            controller.withIntent(AlarmService.stopIntent(app)).startCommand(0, 2)
            AlarmRuntime.end()
            return gain
        }
        assertTrue(startGain(10) < startGain(20))
        assertTrue(startGain(20) < startGain(30))
    }

    // --- Reproductores ---

    @Test fun everyPresetStartsAndStops() {
        for (sound in com.hermesmarcano.zen.core.AlarmSound.entries) {
            val p = TonePlayer(app, SoundChoice.Preset(sound.id))
            p.start(0.2f)
            p.setVolume(0.4f)
            p.stop()
            p.stop() // doble parada inofensiva
        }
    }

    @Test fun aMissingOrBrokenCustomFileFallsBackInsteadOfGoingSilent() {
        val missing = TonePlayer(app, SoundChoice.Custom("nope.audio", "x"))
        missing.start(0.15f)
        assertEquals(VolumeCurve.gain(0.15f), missing.currentGain, 1e-6f)
        missing.stop()

        soundsDir.mkdirs()
        File(soundsDir, "broken.audio").writeBytes(ByteArray(2_000) { 7 })
        val broken = TonePlayer(app, SoundChoice.Custom("broken.audio", "roto.mp3"))
        broken.start(0.15f) // MediaPlayer no puede abrirlo: cae al tono clásico sin lanzar
        broken.setVolume(0.3f)
        broken.stop()
    }

    @Test fun pathTraversalInACustomNameIsIgnored() {
        val p = TonePlayer(app, SoundChoice.Custom("../state.json", "x"))
        p.start(0.15f)
        p.stop()
        assertNull(SoundStorage.file(app, "../state.json"))
    }

    /**
     * El AudioTrack de Robolectric no se inicializa (no hay audio nativo): aquí solo se comprueba que
     * ningún camino lanza ni deja la reproducción a medias; que suene de verdad se valida en teléfono.
     */
    @Test fun completionPlayerNeverThrowsEvenWhenAudioIsUnavailable() {
        val player = CompletionPlayer(app)
        for (sound in com.hermesmarcano.zen.core.CompletionSound.entries) player.play(SoundChoice.Preset(sound.id))
        player.play(SoundChoice.Custom("nope.audio", "x"))
        player.play(SoundChoice.Custom("../state.json", "x"))
        player.stop()
        player.stop()
    }

    @Test fun theSuccessSoundIsScaledToTwentyPercentForPresetsAndFiles() {
        val player = CompletionPlayer(app)
        assertEquals(VolumeCurve.gain(0.20f), player.gain, 1e-6f)
        assertTrue("no puede sonar a tope", player.gain < 0.1f)
    }

    @Test fun previewStartsAndStops() {
        val preview = SoundPreview(app)
        preview.playAlarm(SoundChoice.Preset("soft"), 0.15f)
        assertTrue(preview.isPlaying)
        preview.playCompletion(SoundChoice.Preset("coin"))
        assertTrue(preview.isPlaying)
        preview.stop()
        assertFalse(preview.isPlaying)
        preview.stop()
    }

    // --- Importar archivos propios ---

    @Test fun importCopiesAValidFileIntoPrivateStorage() {
        SoundStorage.durationReader = { 12_000L }
        val result = SoundStorage.import(app, tempAudio(50_000), "alarm") as SoundStorage.ImportResult.Success
        val file = SoundStorage.file(app, result.choice.fileName)
        assertNotNull(file)
        assertEquals(50_000L, file!!.length())
        assertEquals("src.mp3".length > 0, true)
        assertTrue(file.parentFile!!.path.startsWith(app.filesDir.path))
    }

    @Test fun importRejectsFilesTheSystemCannotDecode() {
        SoundStorage.durationReader = { null }
        val result = SoundStorage.import(app, tempAudio(5_000), "alarm") as SoundStorage.ImportResult.Failure
        assertTrue(result.reason.contains("audio"))
        assertTrue("no queda basura", soundsDir.listFiles().isNullOrEmpty())
    }

    @Test fun importRejectsTooShortAndTooLongAudio() {
        SoundStorage.durationReader = { 100L }
        assertTrue(SoundStorage.import(app, tempAudio(5_000), "alarm") is SoundStorage.ImportResult.Failure)
        SoundStorage.durationReader = { 11 * 60_000L }
        assertTrue(SoundStorage.import(app, tempAudio(5_000), "alarm") is SoundStorage.ImportResult.Failure)
        assertTrue(soundsDir.listFiles().isNullOrEmpty())
    }

    @Test fun importStopsCopyingHugeFiles() {
        SoundStorage.durationReader = { 5_000L }
        val huge = tempAudio(21 * 1024 * 1024)
        val result = SoundStorage.import(app, huge, "alarm") as SoundStorage.ImportResult.Failure
        assertTrue(result.reason.contains("pesa"))
        assertTrue(soundsDir.listFiles().isNullOrEmpty())
    }

    @Test fun importOfAnUnreadableUriFailsCleanly() {
        val result = SoundStorage.import(app, Uri.parse("file:///no/existe.mp3"), "alarm")
        assertTrue(result is SoundStorage.ImportResult.Failure)
    }

    @Test fun orphanFilesAreDeletedButUsedOnesKept() {
        soundsDir.mkdirs()
        File(soundsDir, "used.audio").writeText("x")
        File(soundsDir, "used2.audio").writeText("x")
        File(soundsDir, "orphan.audio").writeText("x")
        val state = ZenState(settings = ZenSettings(SoundChoice.Custom("used.audio", "a"), SoundChoice.Custom("used2.audio", "b"), 15))
        SoundStorage.deleteOrphans(app, state)
        assertEquals(setOf("used.audio", "used2.audio"), soundsDir.list()!!.toSet())
        SoundStorage.deleteOrphans(app, ZenState())
        assertTrue(soundsDir.listFiles().isNullOrEmpty())
    }

    @Test fun anAlarmWithACustomSoundStillRingsWhenTheFileVanished() {
        app.repository.update {
            it.copy(
                settings = it.settings.copy(alarmSound = SoundChoice.Custom("gone.audio", "x")),
                alarms = listOf(AlarmEntity("al", 7, 0, emptySet(), true, null, null)),
                anchors = listOf(QrAnchor("a", "x", "s", "000000")),
            )
        }
        val controller = Robolectric.buildService(AlarmService::class.java, AlarmService.ringIntent(app, "al"))
        controller.create().startCommand(0, 1)
        assertEquals("al", AlarmRuntime.ring.value?.alarmId)
        assertTrue(controller.get().currentToneGain >= 0f)
        controller.withIntent(AlarmService.stopIntent(app)).startCommand(0, 2)
    }
}
