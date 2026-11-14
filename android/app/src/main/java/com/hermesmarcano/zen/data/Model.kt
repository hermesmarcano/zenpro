package com.hermesmarcano.zen.data

import com.hermesmarcano.zen.core.Weekday
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID

data class AlarmEntity(
    val id: String,
    val hour: Int,
    val minute: Int,
    val weekdays: Set<Weekday>,
    val enabled: Boolean,
    val anchorId: String?,
    /** Próximo disparo programado (epoch ms). `null` si está desactivada. */
    val nextFireAt: Long?,
) {
    val isRepeating: Boolean get() = weekdays.isNotEmpty()

    companion object {
        fun new(hour: Int, minute: Int, weekdays: Set<Weekday>, anchorId: String?) = AlarmEntity(
            id = UUID.randomUUID().toString(),
            hour = hour,
            minute = minute,
            weekdays = weekdays,
            enabled = false,
            anchorId = anchorId,
            nextFireAt = null,
        )
    }
}

/** Lugar físico que hay que demostrar para apagar la alarma. */
sealed interface Anchor {
    val id: String
    val label: String

    /** Salida de emergencia impresa junto al ancla; se habilita solo tras una espera (ver BackupCodePolicy). */
    val backupCode: String
}

data class QrAnchor(
    override val id: String,
    override val label: String,
    val secret: String,
    override val backupCode: String,
) : Anchor {
    val payload: String get() = "zen://anchor/$id/$secret"

    /** Comparación en tiempo constante: el secreto es lo único que impide fabricar el QR. */
    fun matches(scanned: String): Boolean =
        MessageDigest.isEqual(scanned.toByteArray(Charsets.UTF_8), payload.toByteArray(Charsets.UTF_8))

    companion object {
        fun new(label: String): QrAnchor = QrAnchor(
            id = UUID.randomUUID().toString(),
            label = label,
            secret = Secrets.token(),
            backupCode = Secrets.backupCode(),
        )
    }
}

/** [references] son nombres de archivo JPEG dentro del directorio del ancla (ver PhotoStore). */
data class PhotoAnchor(
    override val id: String,
    override val label: String,
    override val backupCode: String,
    val references: List<String>,
) : Anchor {
    companion object {
        const val MAX_REFERENCES = 3
        fun newId(): String = UUID.randomUUID().toString()
    }
}

fun Anchor.matchesBackupCode(entered: String): Boolean =
    MessageDigest.isEqual(entered.toByteArray(Charsets.UTF_8), backupCode.toByteArray(Charsets.UTF_8))

data class ZenState(
    val alarms: List<AlarmEntity> = emptyList(),
    val anchors: List<Anchor> = emptyList(),
) {
    fun anchor(id: String?): Anchor? = anchors.firstOrNull { it.id == id }
    fun alarm(id: String?): AlarmEntity? = alarms.firstOrNull { it.id == id }
    fun isAnchorInUse(anchorId: String): Boolean = alarms.any { it.anchorId == anchorId }
}

object Secrets {
    private val random = SecureRandom()

    /** 16 bytes aleatorios en base64 URL-safe, sin relleno. */
    fun token(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Seis dígitos, con ceros a la izquierda. */
    fun backupCode(): String = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000))
}
