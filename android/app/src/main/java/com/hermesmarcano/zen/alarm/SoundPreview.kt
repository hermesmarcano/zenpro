package com.hermesmarcano.zen.alarm

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.hermesmarcano.zen.data.SoundChoice

/** Vista previa de sonidos desde Ajustes: suena unos segundos y se corta sola. */
class SoundPreview(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tone: TonePlayer? = null
    private var completion: CompletionPlayer? = null
    private val autoStop = Runnable { stop() }

    /** El nivel es el volumen *percibido* con el que empezaría a sonar la alarma (ver [com.hermesmarcano.zen.core.VolumeCurve]). */
    fun playAlarm(choice: SoundChoice, level: Float) {
        stop()
        tone = TonePlayer(context, choice).also { it.start(level) }
        main.postDelayed(autoStop, PREVIEW_MS)
    }

    fun playCompletion(choice: SoundChoice) {
        stop()
        completion = CompletionPlayer(context).also { it.play(choice) }
        main.postDelayed(autoStop, PREVIEW_MS)
    }

    fun stop() {
        main.removeCallbacks(autoStop)
        tone?.stop()
        tone = null
        completion?.stop()
        completion = null
    }

    val isPlaying: Boolean get() = tone != null || completion != null

    private companion object {
        const val PREVIEW_MS = 4_000L
    }
}
