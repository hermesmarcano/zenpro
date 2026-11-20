package com.hermesmarcano.zen.layer.data

import com.hermesmarcano.zen.layer.core.CountingBox
import com.hermesmarcano.zen.layer.core.BlockEntry
import com.hermesmarcano.zen.layer.core.BlockReason
import com.hermesmarcano.zen.layer.core.CaptureSettings
import com.hermesmarcano.zen.layer.core.DayWindow
import com.hermesmarcano.zen.layer.core.Draft
import com.hermesmarcano.zen.layer.core.DraftStatus
import com.hermesmarcano.zen.layer.core.LayerState
import com.hermesmarcano.zen.layer.core.LimitRule
import com.hermesmarcano.zen.layer.core.PcmSegment
import com.hermesmarcano.zen.layer.core.PendingRule
import com.hermesmarcano.zen.layer.core.WavEncoder
import com.hermesmarcano.zen.layer.core.softwareBox
import java.io.File
import java.io.IOException
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val box = softwareBox()

private fun fullState() = LayerState(
    capture = CaptureSettings(screenText = true, notifications = true, ambientAudio = false, pausedUntilMs = 123L, denyPackages = setOf("a.b", "c.d")),
    limits = listOf(
        LimitRule("com.zhiliaoapp.musically", "TikTok", 45 * 60_000L, blockAtLimit = true, window = DayWindow(22 * 60, 7 * 60)),
        LimitRule("com.instagram.android", "Instagram", 0L),
    ),
    pendingRules = listOf(
        PendingRule("com.instagram.android", null, 99L),
        PendingRule("com.zhiliaoapp.musically", LimitRule("com.zhiliaoapp.musically", "TikTok", 3_600_000L), 100L),
    ),
    blocks = listOf(BlockEntry("x.y", 5_000L, BlockReason.BRAIN)),
    passes = mapOf("x.y" to 6_000L),
    relaxRequests = mapOf("x.y" to 7_000L),
    drafts = listOf(Draft("d1", "wa|marta", "com.whatsapp", "Marta", "claro, a las 9", 1L, 2L, DraftStatus.SENT)),
    brainUrl = "https://brain.example.com",
    brainToken = "tok",
    deviceId = "dev-1",
    commandCursor = "cur",
    seenCommandIds = listOf("c1", "c2"),
    lastSyncOkAtMs = 55L,
    lastSyncError = "boom",
    dailyDay = 20_000L,
    usageTotals = mapOf("x.y" to 1_000L),
    nudgesSent = mapOf("x.y" to setOf(80, 100)),
    relaxGranted = 2,
    autostartConfirmed = true,
    repairsMs = listOf(1L, 2L),
    accessibilityDownSinceMs = 77L,
    lastRepairNoticeAtMs = 88L,
)

class LayerStateJsonTest {
    @Test fun roundTripKeepsEverything() {
        val s = fullState()
        assertEquals(s, LayerStateJson.decode(LayerStateJson.encode(s, box), box))
    }

    @Test fun defaultStateRoundTrips() {
        assertEquals(LayerState(), LayerStateJson.decode(LayerStateJson.encode(LayerState(), box), box))
    }

    @Test fun anEmptyObjectGivesTheDefaults() = assertEquals(LayerState(), LayerStateJson.decode("{}", box))

    @Test fun notJsonThrows() {
        try {
            LayerStateJson.decode("esto no es json", box)
            fail("debería lanzar")
        } catch (_: JSONException) {
        }
    }

    @Test fun unknownFieldsAreIgnored() {
        val json = "{\"version\":99,\"future\":{\"x\":1},\"capture\":{\"screenText\":true,\"nuevo\":5}}"
        assertTrue(LayerStateJson.decode(json, box).capture.screenText)
    }

    @Test fun brokenEntriesAreDroppedNotFatal() {
        val json = """
            {"limits":[{"pkg":""},{"pkg":"ok.app","dailyLimitMs":-5,"window":{"start":10,"end":10}},"basura",7],
             "blocks":[{"pkg":""},{"pkg":"b.app","untilMs":9,"reason":"NOPE"}],
             "drafts":[{"id":"","conversationKey":"k"},{"id":"d","conversationKey":"k","status":"???"}],
             "pendingRules":[{"pkg":"x","effectiveAtMs":1,"rule":{"pkg":""}},{"pkg":"y","effectiveAtMs":2,"rule":null}],
             "daily":{"relaxGranted":-3,"usage":{"a":-1,"b":5}}}
        """.trimIndent()
        val s = LayerStateJson.decode(json, box)
        val rule = s.limits.single()
        assertEquals("ok.app", rule.pkg)
        assertEquals(0L, rule.dailyLimitMs) // negativo → 0
        assertNull(rule.window) // inválida → sin franja
        assertEquals(BlockReason.MANUAL, s.blocks.single().reason)
        assertEquals(DraftStatus.DISCARDED, s.drafts.single().status)
        assertEquals(0, s.relaxGranted)
        assertEquals(mapOf("b" to 5L), s.usageTotals)
        // Una regla pendiente ilegible NO se convierte en "quitar la regla"; una explícitamente nula sí.
        assertEquals(listOf("y"), s.pendingRules.map { it.pkg })
        assertNull(s.pendingRules.single().rule)
    }

    @Test fun negativePauseIsClamped() {
        assertEquals(0L, LayerStateJson.decode("{\"capture\":{\"pausedUntilMs\":-10}}", box).capture.pausedUntilMs)
    }
}

class LayerStateRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun startsEmptyAndPersistsUpdates() {
        val file = File(tmp.root, "layer/layer.json")
        val repo = LayerStateRepository(file, box)
        assertEquals(LayerState(), repo.current)
        repo.update { it.copy(brainUrl = "https://x.example", capture = it.capture.copy(screenText = true)) }
        assertTrue(file.exists())
        val reopened = LayerStateRepository(file, box)
        assertEquals("https://x.example", reopened.current.brainUrl)
        assertTrue(reopened.state.value.capture.screenText)
    }

    @Test fun anUnchangedUpdateDoesNotTouchTheDisk() {
        val file = File(tmp.root, "layer.json")
        val repo = LayerStateRepository(file, box)
        repo.update { it }
        assertFalse(file.exists())
    }

    @Test fun aFailedWriteLeavesTheMemoryStateUntouched() {
        val blocker = tmp.newFile("no-es-un-directorio")
        val repo = LayerStateRepository(File(blocker, "layer.json"), box) // el "directorio" padre es un archivo
        try {
            repo.update { it.copy(brainUrl = "https://x.example") }
            fail("debería lanzar IOException")
        } catch (_: IOException) {
        }
        assertEquals("", repo.current.brainUrl)
    }

    @Test fun aCorruptFileIsSetAsideAndTheAppStarts() {
        val file = tmp.newFile("layer.json")
        file.writeText("{ esto está roto")
        val repo = LayerStateRepository(file, box)
        assertEquals(LayerState(), repo.current)
        assertTrue(File(tmp.root, "layer.json.corrupt").exists())
        // Y se puede seguir usando con normalidad.
        repo.update { it.copy(deviceId = "d") }
        assertEquals("d", LayerStateRepository(file, box).current.deviceId)
    }

    @Test fun noTemporaryFileIsLeftBehind() {
        val file = File(tmp.root, "layer.json")
        LayerStateRepository(file, box).update { it.copy(deviceId = "d") }
        assertEquals(listOf("layer.json"), tmp.root.list()!!.toList())
    }

    @Test fun theFlowEmitsTheLatestState() {
        val repo = LayerStateRepository(File(tmp.root, "layer.json"), box)
        repo.update { it.copy(deviceId = "uno") }
        repo.update { it.copy(deviceId = "dos") }
        assertEquals("dos", repo.state.value.deviceId)
    }
}

class AudioFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val segment = PcmSegment(ShortArray(1_600) { (it % 100).toShort() }, 0, 16_000)

    private val box = softwareBox()
    private fun files() = AudioFiles(File(tmp.root, "audio"), box)

    @Test fun savesAnEncryptedWavAndReadsItBackByName() {
        val audio = files()
        val name = audio.save(segment)
        val file = audio.file(name)!!
        assertEquals("cifrado: IV de 12 + etiqueta de 16 bytes más que el WAV", 44L + 3_200 + 28, file.length())
        assertNotEquals("en disco no se ve la cabecera de un WAV", "RIFF", String(file.readBytes(), 0, 4))
        val wav = audio.read(name)!!
        assertEquals(44 + 3_200, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(file.length(), audio.totalBytes())
    }

    @Test fun anotherKeyATamperedFileOrAnotherNameCannotBeRead() {
        val audio = files()
        val name = audio.save(segment)
        assertNull("otra clave", AudioFiles(File(tmp.root, "audio"), softwareBox()).read(name))
        val file = audio.file(name)!!
        val bytes = file.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x55).toByte()
        file.writeBytes(bytes)
        assertNull("manipulado", audio.read(name))
        // Un archivo bueno con otro nombre válido tampoco se abre: el nombre va dentro de lo autenticado.
        val other = audio.save(segment)
        val copyName = "seg-" + "0".repeat(8) + "-0000-0000-0000-" + "0".repeat(12) + ".wav"
        File(tmp.root, "audio/$other").copyTo(File(tmp.root, "audio/$copyName"))
        assertNull(audio.read(copyName))
        assertNull(audio.read("../x.wav"))
    }

    @Test fun aKeystoreThatFailsRightNowThrowsInsteadOfReportingTheAudioAsGone() {
        val counting = CountingBox()
        val audio = AudioFiles(File(tmp.root, "audio"), counting)
        val name = audio.save(segment)
        counting.down = true
        try {
            audio.read(name)
            fail("un fallo pasajero no puede parecer un audio perdido")
        } catch (_: IOException) {
        }
        assertNotNull("y el archivo sigue ahí", audio.file(name))
        counting.down = false
        assertEquals(44 + 3_200, audio.read(name)!!.size)
        assertNull(audio.read("seg-" + "9".repeat(8) + "-9999-9999-9999-" + "9".repeat(12) + ".wav")) // no existe: null
    }

    @Test fun aPlainWavFromAnOlderVersionIsStillReadable() {
        val audio = files()
        val dir = File(tmp.root, "audio").apply { mkdirs() }
        val name = "seg-" + "1".repeat(8) + "-1111-1111-1111-" + "1".repeat(12) + ".wav"
        File(dir, name).writeBytes(WavEncoder.encode(segment.samples, segment.sampleRate))
        assertEquals(44 + 3_200, audio.read(name)!!.size)
    }

    @Test fun onlyOurOwnNamesAreAccepted() {
        val audio = files()
        audio.save(segment)
        for (bad in listOf("../x.wav", "..", "seg-1.wav", "/etc/passwd", "seg-" + "a".repeat(36) + ".wav.tmp", "", "a/b.wav")) {
            assertNull("«$bad» no debería resolver", audio.file(bad))
        }
        // Y un nombre inválido tampoco borra nada fuera.
        val outside = tmp.newFile("fuera.wav")
        audio.delete("../fuera.wav")
        assertTrue(outside.exists())
    }

    @Test fun deleteRemovesTheFile() {
        val audio = files()
        val name = audio.save(segment)
        audio.delete(name)
        assertNull(audio.file(name))
        audio.delete(name) // borrar dos veces no falla
    }

    @Test fun orphansAreDeletedExceptKeptAndRecentOnes() {
        val audio = files()
        val keep = audio.save(segment)
        val orphan = audio.save(segment)
        val recent = audio.save(segment)
        val dir = File(tmp.root, "audio")
        val old = System.currentTimeMillis() - 10 * 60_000
        File(dir, keep).setLastModified(old)
        File(dir, orphan).setLastModified(old)
        val leftover = File(dir, "$orphan.tmp")
        leftover.writeText("a medias")
        leftover.setLastModified(old)
        val removed = audio.deleteOrphans(setOf(keep), System.currentTimeMillis())
        assertEquals(2, removed) // el huérfano y su temporal
        assertNotNull(audio.file(keep))
        assertNull(audio.file(orphan))
        assertNotNull("el reciente aún puede estar encolándose", audio.file(recent))
    }

    @Test fun deleteAllEmptiesTheFolder() {
        val audio = files()
        audio.save(segment)
        audio.save(segment)
        audio.deleteAll()
        assertEquals(0L, audio.totalBytes())
    }

    @Test fun savingToAnUnwritableLocationThrows() {
        val blocker = tmp.newFile("archivo")
        try {
            AudioFiles(File(blocker, "audio"), box).save(segment)
            fail("debería lanzar")
        } catch (_: IOException) {
        }
    }

    @Test fun totalBytesOfAMissingFolderIsZero() = assertEquals(0L, files().totalBytes())
}
