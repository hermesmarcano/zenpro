package com.hermesmarcano.zen.core

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Tonos de alarma incluidos (sintetizados: sin archivos ni licencias). */
enum class AlarmSound(val id: String, val title: String, val hint: String) {
    CLASSIC("classic", "Clásico", "Pitidos agudos, el más difícil de ignorar"),
    SOFT("soft", "Suave", "Notas ascendentes, para empezar bajito"),
    BELL("bell", "Campana", "Campanadas con eco"),
    PULSE("pulse", "Pulso", "Golpes graves y regulares"),
    MORNING("morning", "Mañana", "Arpegio brillante que sube");

    companion object {
        fun fromId(id: String?): AlarmSound = entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}

/** Sonidos de "ya estás despierto" que suenan al verificar el ancla. */
enum class CompletionSound(val id: String, val title: String) {
    CHIME("chime", "Campanilla"),
    FANFARE("fanfare", "Fanfarria"),
    COIN("coin", "Moneda"),
    BOWL("bowl", "Cuenco");

    companion object {
        fun fromId(id: String?): CompletionSound = entries.firstOrNull { it.id == id } ?: CHIME
    }
}

/** Utilidades de síntesis compartidas (sumar notas con envolvente y normalizar). */
internal object Synth {
    class Partial(val ratio: Double, val amplitude: Float)

    val SINE = listOf(Partial(1.0, 1f))

    /**
     * Suma una nota en [buffer]: ataque lineal, caída exponencial (constante [tau]) y un cierre
     * corto a cero para que nunca haya clics. Lo que se pase del final del buffer se recorta.
     */
    fun note(
        buffer: FloatArray,
        sampleRate: Int,
        startSeconds: Double,
        durationSeconds: Double,
        frequency: Double,
        amplitude: Float,
        partials: List<Partial> = SINE,
        attackSeconds: Double = 0.008,
        tau: Double = Double.POSITIVE_INFINITY,
    ) {
        val first = (startSeconds * sampleRate).toInt()
        val frames = (durationSeconds * sampleRate).toInt()
        val attack = maxOf(1, (attackSeconds * sampleRate).toInt())
        val release = maxOf(1, (0.02 * sampleRate).toInt())
        for (offset in 0 until frames) {
            val frame = first + offset
            if (frame < 0 || frame >= buffer.size) continue
            val t = offset.toDouble() / sampleRate
            val rise = minOf(1f, offset.toFloat() / attack)
            val fall = minOf(1f, (frames - 1 - offset).toFloat() / release)
            val decay = if (tau.isInfinite()) 1f else exp(-t / tau).toFloat()
            var wave = 0f
            for (p in partials) wave += p.amplitude * sin(2 * PI * frequency * p.ratio * t).toFloat()
            buffer[frame] += amplitude * rise * fall * decay * wave
        }
    }

    /** Escala para que el pico sea [peak] (nunca amplifica por encima de lo que ya hay). */
    fun normalize(buffer: FloatArray, peak: Float = 0.8f) {
        val max = buffer.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
        if (max <= 0f) return
        val scale = peak / max
        for (i in buffer.indices) buffer[i] *= scale
    }
}

/** Sintetiza los tonos de alarma. Todos son bucles que empiezan y terminan en silencio. */
object AlarmToneSynth {
    /** Duración del bucle clásico (se conserva por compatibilidad con las pruebas existentes). */
    const val LOOP_DURATION = 2.0
    private const val BEEP_DURATION = 0.12
    private val beepStarts = doubleArrayOf(0.0, 0.2, 0.4, 0.6)
    private val frequencies = doubleArrayOf(880.0, 1175.0, 880.0, 1175.0)
    private const val ENVELOPE = 0.008
    private const val AMPLITUDE = 0.8f

    fun loopSeconds(sound: AlarmSound): Double = when (sound) {
        AlarmSound.CLASSIC -> LOOP_DURATION
        AlarmSound.SOFT -> 3.0
        AlarmSound.BELL -> 3.2
        AlarmSound.PULSE -> 1.2
        AlarmSound.MORNING -> 3.0
    }

    fun samples(sampleRate: Int = 44_100): FloatArray = samples(AlarmSound.CLASSIC, sampleRate)

    fun samples(sound: AlarmSound, sampleRate: Int = 44_100): FloatArray = when (sound) {
        AlarmSound.CLASSIC -> classic(sampleRate)
        AlarmSound.SOFT -> {
            val b = FloatArray((loopSeconds(sound) * sampleRate).toInt())
            val soft = listOf(Synth.Partial(1.0, 1f), Synth.Partial(2.0, 0.18f))
            doubleArrayOf(523.25, 659.25, 783.99, 659.25).forEachIndexed { i, f ->
                Synth.note(b, sampleRate, i * 0.5, 1.1, f, 0.8f, soft, attackSeconds = 0.03, tau = 0.35)
            }
            Synth.normalize(b); b
        }
        AlarmSound.BELL -> {
            val b = FloatArray((loopSeconds(sound) * sampleRate).toInt())
            val bell = listOf(Synth.Partial(1.0, 1f), Synth.Partial(2.76, 0.5f), Synth.Partial(5.4, 0.25f))
            Synth.note(b, sampleRate, 0.0, 1.6, 880.0, 0.8f, bell, tau = 0.55)
            Synth.note(b, sampleRate, 1.6, 1.4, 659.25, 0.8f, bell, tau = 0.55)
            Synth.normalize(b); b
        }
        AlarmSound.PULSE -> {
            val b = FloatArray((loopSeconds(sound) * sampleRate).toInt())
            val thump = listOf(Synth.Partial(1.0, 1f), Synth.Partial(3.0, 0.3f))
            Synth.note(b, sampleRate, 0.0, 0.28, 330.0, 0.8f, thump, attackSeconds = 0.01, tau = 0.25)
            Synth.note(b, sampleRate, 0.4, 0.28, 330.0, 0.8f, thump, attackSeconds = 0.01, tau = 0.25)
            Synth.normalize(b); b
        }
        AlarmSound.MORNING -> {
            val b = FloatArray((loopSeconds(sound) * sampleRate).toInt())
            val bright = listOf(Synth.Partial(1.0, 1f), Synth.Partial(2.0, 0.35f), Synth.Partial(3.0, 0.15f))
            doubleArrayOf(523.25, 659.25, 783.99, 1046.5, 1318.5).forEachIndexed { i, f ->
                Synth.note(b, sampleRate, i * 0.22, 0.9, f, 0.8f, bright, attackSeconds = 0.01, tau = 0.3)
            }
            Synth.normalize(b); b
        }
    }

    private fun classic(sampleRate: Int): FloatArray {
        val frameCount = (LOOP_DURATION * sampleRate).toInt()
        val buffer = FloatArray(frameCount)
        val beepFrames = (BEEP_DURATION * sampleRate).toInt()
        val envelopeFrames = maxOf(1, (ENVELOPE * sampleRate).toInt())

        for ((index, start) in beepStarts.withIndex()) {
            val firstFrame = (start * sampleRate).toInt()
            val frequency = frequencies[index]
            for (offset in 0 until beepFrames) {
                val frame = firstFrame + offset
                if (frame >= frameCount) break
                val fadeIn = minOf(1f, offset.toFloat() / envelopeFrames)
                val fadeOut = minOf(1f, (beepFrames - 1 - offset).toFloat() / envelopeFrames)
                val phase = 2 * PI * frequency * offset / sampleRate
                buffer[frame] = AMPLITUDE * fadeIn * fadeOut * sin(phase).toFloat()
            }
        }
        return buffer
    }
}

/** Sintetiza los sonidos de éxito (una sola vez, sin bucle). */
object CompletionToneSynth {
    fun durationSeconds(sound: CompletionSound): Double = when (sound) {
        CompletionSound.CHIME -> 2.0
        CompletionSound.FANFARE -> 2.4
        CompletionSound.COIN -> 1.0
        CompletionSound.BOWL -> 3.2
    }

    fun samples(sound: CompletionSound, sampleRate: Int = 44_100): FloatArray {
        val b = FloatArray((durationSeconds(sound) * sampleRate).toInt())
        when (sound) {
            CompletionSound.CHIME -> {
                val glass = listOf(Synth.Partial(1.0, 1f), Synth.Partial(2.0, 0.3f), Synth.Partial(4.0, 0.1f))
                doubleArrayOf(523.25, 659.25, 783.99, 1046.5).forEachIndexed { i, f ->
                    Synth.note(b, sampleRate, i * 0.13, 1.4, f, 0.8f, glass, tau = 0.45)
                }
            }
            CompletionSound.FANFARE -> {
                val brass = listOf(Synth.Partial(1.0, 1f), Synth.Partial(2.0, 0.5f), Synth.Partial(3.0, 0.3f), Synth.Partial(4.0, 0.15f))
                doubleArrayOf(392.0, 523.25, 659.25).forEachIndexed { i, f ->
                    Synth.note(b, sampleRate, i * 0.16, 0.25, f, 0.6f, brass, attackSeconds = 0.02)
                }
                for (f in doubleArrayOf(523.25, 659.25, 783.99, 1046.5)) {
                    Synth.note(b, sampleRate, 0.55, 1.7, f, 0.45f, brass, attackSeconds = 0.03, tau = 0.8)
                }
            }
            CompletionSound.COIN -> {
                val square = listOf(Synth.Partial(1.0, 1f), Synth.Partial(3.0, 0.33f), Synth.Partial(5.0, 0.2f))
                Synth.note(b, sampleRate, 0.0, 0.09, 987.77, 0.7f, square, attackSeconds = 0.003)
                Synth.note(b, sampleRate, 0.09, 0.8, 1318.51, 0.7f, square, attackSeconds = 0.003, tau = 0.25)
            }
            CompletionSound.BOWL -> {
                val bowl = listOf(Synth.Partial(1.0, 1f), Synth.Partial(2.0, 0.5f), Synth.Partial(3.0, 0.25f), Synth.Partial(4.2, 0.12f))
                Synth.note(b, sampleRate, 0.0, 3.0, 261.63, 0.8f, bowl, attackSeconds = 0.02, tau = 1.0)
            }
        }
        Synth.normalize(b)
        return b
    }
}

/** Aviso corto de "se acabó el descanso": dos notas suaves, sin bucle. Medio segundo, para no tapar la música. */
object RestToneSynth {
    const val DURATION_SECONDS = 0.7

    fun samples(sampleRate: Int = 44_100): FloatArray {
        val b = FloatArray((DURATION_SECONDS * sampleRate).toInt())
        val soft = listOf(Synth.Partial(1.0, 1f), Synth.Partial(2.0, 0.2f))
        Synth.note(b, sampleRate, 0.0, 0.3, 659.25, 0.8f, soft, attackSeconds = 0.01, tau = 0.12)
        Synth.note(b, sampleRate, 0.22, 0.45, 880.0, 0.8f, soft, attackSeconds = 0.01, tau = 0.18)
        Synth.normalize(b)
        return b
    }
}
