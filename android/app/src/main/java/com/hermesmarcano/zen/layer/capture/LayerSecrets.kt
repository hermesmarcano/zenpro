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
    internal var factory: () -> SecretBox = {
        val key = KeystoreKey()
        AesGcmSecretBox(key::forSealing, key::forOpening)
    }

    fun create(): SecretBox = factory()
}

internal class KeystoreKey(private val alias: String = ALIAS) {
    private fun store(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /**
     * Para ABRIR: la clave que ya existe, o `null` si ya no está (lo cifrado con ella es irrecuperable). Nunca la borra ni la
     * crea: un fallo pasajero del Keystore (lanza) no debe destruir una clave buena ni, con ella, todo lo guardado.
     */
    fun forOpening(): SecretKey? = store().getKey(alias, null) as? SecretKey

    /**
     * Para CIFRAR: la que existe o una nueva. Solo si la clave no se puede usar dos veces seguidas se da por perdida
     * (p. ej. invalidada al cambiar el bloqueo de pantalla), se borra y se crea otra: lo cifrado con la vieja ya era irrecuperable.
     */
    @Synchronized
    fun forSealing(): SecretKey {
        val store = store()
        repeat(2) {
            try {
                return (store.getKey(alias, null) as? SecretKey) ?: create()
            } catch (_: UnrecoverableKeyException) {
                // se reintenta una vez
            }
        }
        store.deleteEntry(alias)
        return create()
    }

    private fun create(): SecretKey {
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
