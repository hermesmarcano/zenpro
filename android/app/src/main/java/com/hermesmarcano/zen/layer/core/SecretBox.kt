package com.hermesmarcano.zen.layer.core

import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifrado de lo que no debe quedar en claro en el disco (el token del cerebro, la cola de envío y el audio pendiente). Es
 * una interfaz pura: en Android la clave vive en el Keystore (ver `LayerSecrets`) y en las pruebas es una clave de software.
 *
 * `context` se mezcla como dato autenticado: un valor cifrado para un uso (el token) no se puede pasar por otro
 * (un elemento de la cola, un archivo de audio) aunque alguien pudiera reescribir el archivo.
 */
interface SecretBox {
    /** `IV ‖ cifrado ‖ etiqueta`. Lanza `IOException` si no se puede cifrar: NUNCA se guarda en claro como plan B. */
    @Throws(IOException::class)
    fun sealBytes(plain: ByteArray, context: String): ByteArray

    /** Los bytes originales, o `null` si están manipulados, son de otro contexto, o la clave ya no existe. */
    fun openBytes(sealed: ByteArray, context: String): ByteArray?

    /** Texto cifrado: `enc1:` + base64. */
    @Throws(IOException::class)
    fun seal(plain: String, context: String): String =
        SealedText.PREFIX + Base64.getEncoder().withoutPadding().encodeToString(sealBytes(plain.toByteArray(Charsets.UTF_8), context))

    fun open(sealed: String, context: String): String? {
        if (!SealedText.isSealed(sealed)) return null
        val raw = try {
            Base64.getDecoder().decode(sealed.substring(SealedText.PREFIX.length))
        } catch (_: IllegalArgumentException) {
            return null
        }
        return openBytes(raw, context)?.toString(Charsets.UTF_8)
    }
}

object SealedText {
    const val PREFIX = "enc1:"

    fun isSealed(value: String): Boolean = value.startsWith(PREFIX)
}

/** AES-256-GCM con IV aleatorio por mensaje. */
class AesGcmSecretBox(private val key: () -> SecretKey) : SecretBox {
    override fun sealBytes(plain: ByteArray, context: String): ByteArray = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Sin IV propio: la clave del Keystore exige cifrado aleatorizado, y las de software también lo generan solas.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        cipher.iv + cipher.doFinal(plain)
    } catch (e: GeneralSecurityException) {
        throw IOException("No se pudo cifrar", e)
    } catch (e: RuntimeException) {
        throw IOException("No se pudo cifrar", e) // el Keystore a veces falla con ProviderException
    }

    override fun openBytes(sealed: ByteArray, context: String): ByteArray? {
        if (sealed.size < IV_BYTES + TAG_BYTES) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, sealed, 0, IV_BYTES))
            cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}

/** Claves de software (pruebas): sin Keystore, vive solo en memoria. */
object SoftwareKeys {
    fun random(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
