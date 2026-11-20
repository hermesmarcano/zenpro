package com.hermesmarcano.zen.layer.android

import com.hermesmarcano.zen.layer.capture.LayerSecrets
import com.hermesmarcano.zen.layer.core.AesGcmSecretBox
import com.hermesmarcano.zen.layer.core.SoftwareKeys

/** Robolectric no tiene Keystore: las pruebas cifran con una clave de software (el cifrado en sí se prueba aparte, en JVM). */
internal fun useSoftwareSecrets() {
    val key = SoftwareKeys.random()
    LayerSecrets.factory = { AesGcmSecretBox { key } }
}
