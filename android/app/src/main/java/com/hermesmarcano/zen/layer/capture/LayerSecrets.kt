package com.hermesmarcano.zen.layer.capture

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.hermesmarcano.zen.layer.core.AesGcmSecretBox
import com.hermesmarcano.zen.layer.core.SecretBox
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * La clave con la que se cifra el token del cerebro y la cola de envío vive en el Keystore de Android (respaldado por
 * hardware en el Redmi Note 8): el archivo, por sí solo, no sirve de nada fuera de este teléfono. La clave no pide
 * desbloqueo del usuario a propósito: los servicios la usan con la pantalla apagada.
 *
 * Si el Keystore no funciona, NO se cae a guardar en claro: el cambio falla y la pantalla lo dice.
 */
object LayerSecrets {
    /** Las pruebas de Robolectric (que no tienen Keystore) lo sustituyen por una clave de software. */
    @Volatile
    internal var factory: () -> SecretBox = { AesGcmSecretBox(KeystoreKey()::get) }

    fun create(): SecretBox = factory()
}

internal class KeystoreKey(private val alias: String = ALIAS) {
    @Volatile
    private var cached: SecretKey? = null

    @Synchronized
    fun get(): SecretKey {
        cached?.let { return it }
        return loadOrCreate().also { cached = it }
    }

    private fun loadOrCreate(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val existing = try {
            store.getKey(alias, null) as? SecretKey
        } catch (_: UnrecoverableKeyException) {
            store.deleteEntry(alias) // clave invalidada (p. ej. tras cambiar el bloqueo de pantalla): se crea otra
            null
        }
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "zen-layer-v1"
    }
}
