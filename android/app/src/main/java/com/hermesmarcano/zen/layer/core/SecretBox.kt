package com.hermesmarcano.zen.layer.core

import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.AEADBadTagException
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
 *
 * Al abrir hay DOS tipos de fallo y no se pueden confundir (confundirlos borraba datos por un fallo pasajero):
 *  - **permanente** (manipulado, de otro contexto, la clave ya no existe): devuelve `null`; lo cifrado no se recuperará nunca.
 *  - **pasajero** (el Keystore no responde ahora): lanza `IOException`; hay que reintentar más tarde y NO descartar nada.
 */
interface SecretBox {
    /** `IV ‖ cifrado ‖ etiqueta`. Lanza `IOException` si no se puede cifrar: NUNCA se guarda en claro como plan B. */
    @Throws(IOException::class)
    fun sealBytes(plain: ByteArray, context: String): ByteArray

    /** Los bytes originales; `null` si es irrecuperable; `IOException` si ahora no se puede saber (reintentar). */
    @Throws(IOException::class)
    fun openBytes(sealed: ByteArray, context: String): ByteArray?

    /** Texto cifrado: `enc1:` + base64. */
    @Throws(IOException::class)
    fun seal(plain: String, context: String): String =
        SealedText.PREFIX + Base64.getEncoder().withoutPadding().encodeToString(sealBytes(plain.toByteArray(Charsets.UTF_8), context))

    @Throws(IOException::class)
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

/**
 * AES-256-GCM con IV aleatorio por mensaje. [sealKey] da (o crea) la clave para cifrar. [openKey] da la existente para abrir:
 * `null` si ya no existe (lo cifrado con ella es irrecuperable) y lanza si el almacén falla ahora mismo (pasajero).
 */
class AesGcmSecretBox(private val sealKey: () -> SecretKey, private val openKey: () -> SecretKey?) : SecretBox {
    constructor(key: () -> SecretKey) : this(key, key)

    override fun sealBytes(plain: ByteArray, context: String): ByteArray = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Sin IV propio: la clave del Keystore exige cifrado aleatorizado, y las de software también lo generan solas.
        cipher.init(Cipher.ENCRYPT_MODE, sealKey())
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        cipher.iv + cipher.doFinal(plain)
    } catch (e: GeneralSecurityException) {
        throw IOException("No se pudo cifrar", e)
    } catch (e: RuntimeException) {
        throw IOException("No se pudo cifrar", e) // el Keystore a veces falla con ProviderException
    }

    override fun openBytes(sealed: ByteArray, context: String): ByteArray? {
        if (sealed.size < IV_BYTES + TAG_BYTES) return null
        val key = try {
            openKey()
        } catch (e: Exception) {
            throw IOException("El almacén de claves no responde", e)
        } ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, sealed, 0, IV_BYTES))
            cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } catch (_: AEADBadTagException) {
            null // manipulado, de otro contexto o cifrado con otra clave: irrecuperable
        } catch (e: GeneralSecurityException) {
            throw IOException("No se pudo descifrar ahora", e) // p. ej. clave que el almacén no deja usar en este momento
        } catch (e: RuntimeException) {
            throw IOException("No se pudo descifrar ahora", e)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}

/**
 * Recuerda el último texto cifrado de cada uso: mientras el texto no cambie, cifrar devuelve el MISMO resultado sin tocar el
 * almacén de claves (no es repetir un IV: no es un cifrado nuevo). El estado de la capa se reescribe muy a menudo y el
 * token casi nunca cambia: sin esto, cada escritura —incluidas las del hilo principal— pasaría por el Keystore y un fallo
 * pasajero de este rompería hasta pulsar «Detener».
 */
class MemoizedSecretBox(private val inner: SecretBox) : SecretBox {
    private val last = HashMap<String, Pair<String, String>>() // contexto → (claro, cifrado)

    override fun sealBytes(plain: ByteArray, context: String): ByteArray = inner.sealBytes(plain, context)

    override fun openBytes(sealed: ByteArray, context: String): ByteArray? = inner.openBytes(sealed, context)

    @Synchronized
    override fun seal(plain: String, context: String): String {
        last[context]?.let { (p, s) -> if (p == plain) return s }
        return inner.seal(plain, context).also { last[context] = plain to it }
    }

    @Synchronized
    override fun open(sealed: String, context: String): String? =
        inner.open(sealed, context)?.also { last[context] = it to sealed }
}

/** Claves de software (pruebas): sin Keystore, vive solo en memoria. */
object SoftwareKeys {
    fun random(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
