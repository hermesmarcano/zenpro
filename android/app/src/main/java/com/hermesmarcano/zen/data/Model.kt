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

/** Sonido elegido: uno de los incluidos (por id) o un archivo del usuario copiado al almacenamiento de la app. */
sealed interface SoundChoice {
    data class Preset(val id: String) : SoundChoice

    /** [fileName] vive en `filesDir/sounds/`; [displayName] es el nombre original, solo para mostrar. */
    data class Custom(val fileName: String, val displayName: String) : SoundChoice
}

data class ZenSettings(
    val alarmSound: SoundChoice = SoundChoice.Preset("classic"),
    val completionSound: SoundChoice = SoundChoice.Preset("chime"),
    /** Volumen (percibido) con el que empieza a sonar la alarma, en %. */
    val startVolumePercent: Int = DEFAULT_START_VOLUME_PERCENT,
    /** El usuario ya vio la pista de "mantén y arrastra" en la lista de tareas. */
    val taskHintSeen: Boolean = false,
) {
    /** Nivel inicial en 0..1, acotado: por debajo de 5 % no se oiría y por encima de 50 % deja de ser "empezar bajo". */
    val startVolume: Float get() = startVolumePercent.coerceIn(MIN_START_VOLUME_PERCENT, MAX_START_VOLUME_PERCENT) / 100f

    companion object {
        const val DEFAULT_START_VOLUME_PERCENT = 15
        const val MIN_START_VOLUME_PERCENT = 5
        const val MAX_START_VOLUME_PERCENT = 50
        val START_VOLUME_OPTIONS = listOf(10, 15, 20, 30)
    }
}

/** Racha de despertares verificados en el ancla. */
data class WakeStats(
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val totalWakeups: Int = 0,
    /** Último día (epoch day, zona local) con un despertar verificado. */
    val lastWakeDay: Long? = null,
    /** Segundos que tardó en llegar al ancla el último despertar. */
    val lastSolveSeconds: Int? = null,
)

/** Tarea del día. La prioridad no se guarda: la da la posición en la lista del día (ver TaskBoard). */
data class TaskItem(
    val id: String,
    val title: String,
    val done: Boolean,
    /** Día (epoch day, zona local) al que pertenece. */
    val day: Long,
    val position: Int,
    /** Ya se dio el XP de esta tarea: marcar/desmarcar no se puede usar para ganarlo varias veces. */
    val xpAwarded: Boolean = false,
)

/** Rango de días [from, to] (epoch day, ambos incluidos). */
data class DayRange(val from: Long, val to: Long) {
    fun contains(day: Long) = day in from..to
}

/**
 * Modo viaje. Sin [anchorId] es una **pausa**: las alarmas no suenan hasta el final de [untilEpochDay].
 * Con [anchorId] las alarmas siguen sonando pero se apagan en ese otro ancla (p. ej. un QR de repuesto
 * impreso, o una foto hecha en el hotel).
 */
data class Trip(val untilEpochDay: Long, val anchorId: String?) {
    val isPause: Boolean get() = anchorId == null
}

/** Juego sobrio: XP, nivel, racha de tarea prioritaria y logros. Ver Gamification. */
data class Progress(
    val xp: Int = 0,
    /** Racha de días con la tarea prioritaria cumplida (misma política que la de despertares). */
    val mitStreak: WakeStats = WakeStats(),
    val tasksCompleted: Int = 0,
    val mitsCompleted: Int = 0,
    val perfectDays: Int = 0,
    val fullDays: Int = 0,
    val tripsStarted: Int = 0,
    /** XP de tareas ganado hoy (con tope diario) y el día al que se refiere. */
    val taskXpDay: Long? = null,
    val taskXpToday: Int = 0,
    val completeDayBonusDay: Long? = null,
    val perfectDayBonusDay: Long? = null,
    /** id de logro -> día (epoch day) en que se desbloqueó. */
    val achievements: Map<String, Long> = emptyMap(),
    /** Días de pausa por viaje: no cuentan como día perdido para las rachas. */
    val pausedRanges: List<DayRange> = emptyList(),
)

data class ZenState(
    val alarms: List<AlarmEntity> = emptyList(),
    val anchors: List<Anchor> = emptyList(),
    val settings: ZenSettings = ZenSettings(),
    val stats: WakeStats = WakeStats(),
    val tasks: List<TaskItem> = emptyList(),
    val trip: Trip? = null,
    val progress: Progress = Progress(),
) {
    fun anchor(id: String?): Anchor? = anchors.firstOrNull { it.id == id }
    fun alarm(id: String?): AlarmEntity? = alarms.firstOrNull { it.id == id }
    fun isAnchorInUse(anchorId: String): Boolean = alarms.any { it.anchorId == anchorId } || trip?.anchorId == anchorId
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
