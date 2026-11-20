package com.hermesmarcano.zen.layer.data

import com.hermesmarcano.zen.layer.core.softwareBox
import com.hermesmarcano.zen.layer.core.CountingBox
import com.hermesmarcano.zen.layer.core.AesGcmSecretBox
import com.hermesmarcano.zen.layer.core.LayerState
import com.hermesmarcano.zen.layer.core.SealedText
import com.hermesmarcano.zen.layer.core.SecretBox
import com.hermesmarcano.zen.layer.core.SoftwareKeys
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SealedStateTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = SoftwareKeys.random()
    private val box = AesGcmSecretBox { key }
    private val token = "tok-MUY-secreto-0123456789"

    private fun file() = File(tmp.root, "layer/layer.json")

    @Test fun theTokenIsNeverWrittenInThePlain() {
        val repo = LayerStateRepository(file(), box)
        repo.update { it.copy(brainUrl = "https://brain.example.com", brainToken = token) }
        val onDisk = file().readText()
        assertFalse("el token no puede estar en el archivo", onDisk.contains(token))
        assertTrue(onDisk.contains(SealedText.PREFIX))
        assertEquals(token, LayerStateRepository(file(), box).current.brainToken)
    }

    @Test fun anEmptyTokenStaysEmptyAndNeedsNoKey() {
        val noKey = AesGcmSecretBox { throw IllegalStateException("sin Keystore") }
        LayerStateRepository(file(), noKey).update { it.copy(deviceId = "d") }
        assertEquals("d", LayerStateRepository(file(), noKey).current.deviceId)
    }

    @Test fun aTokenFromTheOldPlainFormatIsMigratedOnLoad() {
        file().parentFile!!.mkdirs()
        file().writeText("""{"version":1,"brain":{"url":"https://brain.example.com","token":"$token","deviceId":"d"}}""")
        val repo = LayerStateRepository(file(), box)
        assertEquals("se sigue pudiendo usar", token, repo.current.brainToken)
        assertFalse("y ya no está en claro", file().readText().contains(token))
        assertEquals(token, LayerStateRepository(file(), box).current.brainToken)
    }

    @Test fun ifTheKeyIsGoneTheTokenIsEmptyNotGarbage() {
        LayerStateRepository(file(), box).update { it.copy(brainToken = token) }
        val otherKey = AesGcmSecretBox(SoftwareKeys.random().let { k -> { k } })
        assertEquals("", LayerStateRepository(file(), otherKey).current.brainToken)
    }

    @Test fun aTamperedTokenIsDiscarded() {
        LayerStateRepository(file(), box).update { it.copy(brainToken = token) }
        file().writeText(file().readText().replace("enc1:", "enc1:AAAA"))
        assertEquals("", LayerStateRepository(file(), box).current.brainToken)
    }

    @Test fun anUnchangedTokenIsNotSealedAgainOnEveryWrite() {
        val counting = CountingBox()
        val repo = LayerStateRepository(file(), counting)
        repo.update { it.copy(brainUrl = "https://brain.example.com", brainToken = token) }
        val sealsAfterSaving = counting.seals
        repeat(5) { n -> repo.update { it.copy(deviceId = "d$n") } }
        assertEquals("el uso diario, los bloqueos… no pasan por el Keystore", sealsAfterSaving, counting.seals)
        counting.down = true
        repo.update { it.copy(deviceId = "con-el-keystore-caido") } // un fallo pasajero no impide guardar lo que no toca al token
        assertEquals("con-el-keystore-caido", repo.current.deviceId)
    }

    @Test fun aTransientKeystoreFailureAtStartDoesNotEraseTheToken() {
        val inner = softwareBox()
        LayerStateRepository(file(), CountingBox(inner)).update { it.copy(brainUrl = "https://brain.example.com", brainToken = token) }

        val down = CountingBox(inner).apply { down = true }
        val repo = LayerStateRepository(file(), down)
        assertEquals("ahora mismo no se puede abrir", "", repo.current.brainToken)
        repo.update { it.copy(deviceId = "otro-cambio") }
        assertTrue("el token cifrado sigue en el archivo aunque se escriba otro cambio", file().readText().contains("enc1:"))

        down.down = false // vuelve el Keystore
        repo.update { it.copy(deviceId = "ya-vuelve") }
        assertEquals("y el siguiente cambio lo recupera", token, repo.current.brainToken)
        assertEquals(token, LayerStateRepository(file(), CountingBox(inner)).current.brainToken)
    }

    @Test fun anUnreadableTokenIsDroppedWhenTheBrainIsRemoved() {
        val inner = softwareBox()
        LayerStateRepository(file(), CountingBox(inner)).update { it.copy(brainUrl = "https://brain.example.com", brainToken = token) }
        val down = CountingBox(inner).apply { down = true }
        val repo = LayerStateRepository(file(), down)
        repo.update { it.copy(brainUrl = "", brainToken = "") } // «Quitar cerebro»
        assertFalse(file().readText().contains("enc1:"))
        down.down = false
        repo.update { it.copy(deviceId = "x") }
        assertEquals("", repo.current.brainToken)
    }

    @Test fun ifSealingFailsTheChangeFailsAndNothingIsWrittenInThePlain() {
        val broken = object : SecretBox {
            override fun sealBytes(plain: ByteArray, context: String): ByteArray = throw IOException("Keystore roto")
            override fun openBytes(sealed: ByteArray, context: String): ByteArray? = null
        }
        val repo = LayerStateRepository(file(), broken)
        try {
            repo.update { it.copy(brainToken = token) }
            fail("debe fallar en vez de guardar en claro")
        } catch (_: IOException) {
        }
        assertEquals("", repo.current.brainToken)
        assertFalse(file().exists() && file().readText().contains(token))
    }
}
