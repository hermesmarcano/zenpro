package com.hermesmarcano.zen.layer.data

import com.hermesmarcano.zen.layer.core.ChatFailure
import com.hermesmarcano.zen.layer.core.ChatMessage
import com.hermesmarcano.zen.layer.core.ChatProposal
import com.hermesmarcano.zen.layer.core.ChatRole
import com.hermesmarcano.zen.layer.core.ChatSnapshot
import com.hermesmarcano.zen.layer.core.CountingBox
import com.hermesmarcano.zen.layer.core.softwareBox
import java.io.File
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SealedChatStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val file by lazy { File(tmp.root, "layer/chat.bin") }
    private val snapshot = ChatSnapshot(
        "0f9c2d4e-aaaa-bbbb-cccc-1234567890ab",
        listOf(
            ChatMessage("1", ChatRole.USER, "mi contraseña es hunter2", 10, voice = true),
            ChatMessage("2", ChatRole.BRAIN, "No la guardes aquí.", 11),
            ChatMessage("3", ChatRole.BRAIN, "Cena con Marta", 12, proposal = ChatProposal("c1", "Cena con Marta", 5_000_000L, 8_600_000L)),
            ChatMessage("4", ChatRole.NOTE, "Pausé la capa durante 15 min.", 13),
            ChatMessage("5", ChatRole.USER, "¿y ahora?", 14, failure = ChatFailure.TIMEOUT),
        ),
    )

    @Test fun roundTripKeepsEverything() {
        val store = SealedChatStore(file, softwareBox())
        store.save(snapshot)
        assertEquals(snapshot, store.load())
    }

    @Test fun nothingSavedMeansNothingToLoad() {
        assertNull(SealedChatStore(file, softwareBox()).load())
    }

    @Test fun theFileHoldsNoReadableConversation() {
        SealedChatStore(file, softwareBox()).save(snapshot)
        val raw = file.readText()
        assertTrue(raw.startsWith("enc1:"))
        for (secret in listOf("hunter2", "Cena con Marta", "0f9c2d4e", "contraseña")) assertFalse(secret, secret in raw)
    }

    @Test fun ifItCannotBeSealedNothingIsWrittenInThePlain() {
        val box = CountingBox().also { it.down = true }
        try {
            SealedChatStore(file, box).save(snapshot)
            fail("debería fallar")
        } catch (_: IOException) {
        }
        assertFalse(file.exists())
        assertFalse(File(file.parentFile, "chat.bin.tmp").exists())
    }

    @Test fun aFailedSaveKeepsThePreviousConversation() {
        val box = CountingBox()
        val store = SealedChatStore(file, box)
        store.save(snapshot)
        box.down = true
        try {
            store.save(snapshot.copy(messages = emptyList()))
            fail("debería fallar")
        } catch (_: IOException) {
        }
        box.down = false
        assertEquals(snapshot, store.load())
    }

    @Test fun aTransientKeystoreFailureIsNotMistakenForALostConversation() {
        val box = CountingBox()
        val store = SealedChatStore(file, box)
        store.save(snapshot)
        box.down = true
        try {
            store.load()
            fail("debería lanzar para reintentar")
        } catch (_: IOException) {
        }
        assertTrue(file.exists()) // no se borra lo que solo estaba inaccesible un momento
        box.down = false
        assertEquals(snapshot, store.load())
    }

    @Test fun aTamperedOrForeignFileIsDroppedAndStartsOver() {
        SealedChatStore(file, softwareBox()).save(snapshot)
        assertNull(SealedChatStore(file, softwareBox()).load()) // otra clave: irrecuperable
        assertFalse(file.exists())

        file.parentFile!!.mkdirs()
        file.writeText("enc1:AAAA")
        assertNull(SealedChatStore(file, softwareBox()).load())
        assertFalse(file.exists())

        file.writeText("no cifrado en absoluto")
        assertNull(SealedChatStore(file, softwareBox()).load())
        assertFalse(file.exists())
    }

    @Test fun anEncryptedFileOfAnotherPurposeDoesNotLoadAsAChat() {
        val box = softwareBox()
        file.parentFile!!.mkdirs()
        file.writeText(box.seal(SealedChatStore.encode(snapshot), "otro:contexto"))
        assertNull(SealedChatStore(file, box).load())
    }

    @Test fun clearRemovesTheFile() {
        val store = SealedChatStore(file, softwareBox())
        store.save(snapshot)
        store.clear()
        assertFalse(file.exists())
    }

    @Test fun clearAlsoRemovesALeftoverTemporaryFile() {
        val store = SealedChatStore(file, softwareBox())
        store.save(snapshot)
        File(file.parentFile, "chat.bin.tmp").writeText("resto")
        store.clear()
        assertEquals(emptyList<String>(), file.parentFile!!.list()!!.toList())
        store.clear() // sin nada que borrar tampoco falla
    }

    @Test fun clearFailsLoudlyIfTheFileCannotBeRemoved() {
        // Un directorio no vacío con ese nombre: `File.delete()` devuelve false. No se debe decir «borrado» sin estarlo.
        file.mkdirs()
        File(file, "x").writeText("x")
        try {
            SealedChatStore(file, softwareBox()).clear()
            fail("debería lanzar")
        } catch (_: IOException) {
        }
    }

    @Test fun savingTwiceReplacesAtomicallyWithoutLeavingTemporaries() {
        val store = SealedChatStore(file, softwareBox())
        store.save(snapshot)
        store.save(snapshot.copy(messages = snapshot.messages.take(1)))
        assertEquals(1, store.load()?.messages?.size)
        assertEquals(listOf("chat.bin"), file.parentFile!!.list()!!.toList())
    }

    // ---------- Un archivo dañado no debe tumbar nada ----------

    @Test fun decodeRejectsABadSessionAndSkipsBadMessages() {
        assertNull(SealedChatStore.decode("""{"session":"corta","messages":[]}"""))
        assertNull(SealedChatStore.decode("""{"session":"con espacios y raros ñ","messages":[]}"""))
        assertNull(SealedChatStore.decode("no json"))
        val ok = SealedChatStore.decode(
            """{"session":"abcdefgh-1234","messages":[
                {"id":"","role":"USER","text":"sin id"},
                {"id":"a","role":"HACKER","text":"rol raro"},
                {"id":"b","role":"USER","text":"bueno","at":5,"failure":"NO_EXISTE"},
                {"id":"b","role":"USER","text":"repetido"},
                "no soy un objeto"]}""",
        )!!
        assertEquals(listOf("b"), ok.messages.map { it.id })
        assertEquals("bueno", ok.messages.single().text)
        assertNull(ok.messages.single().failure)
    }

    @Test fun decodeDropsAProposalThatTheAppWouldNeverAccept() {
        fun proposalOf(json: String) = SealedChatStore.decode(
            """{"session":"abcdefgh-1234","messages":[{"id":"a","role":"BRAIN","text":"x","proposal":$json}]}""",
        )!!.messages.single().proposal
        assertEquals(ChatProposal("c", "Cena", 10, 20), proposalOf("""{"cid":"c","title":"Cena","start":10,"end":20}"""))
        assertNull(proposalOf("""{"cid":"c","title":"","start":10,"end":20}"""))
        assertNull(proposalOf("""{"cid":"c","title":"Cena","start":20,"end":10}"""))
        assertNull(proposalOf("""{"cid":"c","title":"Cena","start":0,"end":10}"""))
        assertNull(proposalOf("""{"cid":"c","title":"${"x".repeat(101)}","start":10,"end":20}"""))
    }

    @Test fun decodeCapsTheHistoryAndTheTextOfEachMessage() {
        val many = (1..150).joinToString(",") { """{"id":"m$it","role":"USER","text":"${"y".repeat(5_000)}"}""" }
        val decoded = SealedChatStore.decode("""{"session":"abcdefgh-1234","messages":[$many]}""")!!
        assertEquals(100, decoded.messages.size)
        assertEquals("m150", decoded.messages.last().id)
        assertTrue(decoded.messages.all { it.text.length <= 4_000 })
    }

    @Test fun encodeIsValidJsonWithTheSession() {
        assertEquals(snapshot.session, JSONObject(SealedChatStore.encode(snapshot)).getString("session"))
    }
}
