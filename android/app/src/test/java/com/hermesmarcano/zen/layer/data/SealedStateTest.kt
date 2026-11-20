package com.hermesmarcano.zen.layer.data

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
