package com.hermesmarcano.zen.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.hermesmarcano.zen.core.RestToneSynth
import com.hermesmarcano.zen.core.VolumeCurve
import com.hermesmarcano.zen.zenApp

/** Fin del descanso: sonido corto y bajo, vibración y una notificación. Ignora avisos viejos (descanso saltado o cambiado). */
class RestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RestTimer.ACTION_DONE) return
        val expected = intent.getLongExtra(RestTimer.EXTRA_ENDS_AT, 0L)
        val rest = context.zenApp.workouts.data.value.active?.rest
        // Si se saltó o se ajustó el descanso, la hora de fin ya no coincide: este aviso es de un descanso anterior.
        if (rest == null || kotlin.math.abs(rest.endsAt - expected) > STALE_TOLERANCE_MILLIS) return

        val pending = goAsync()
        RestTimer.showDone(context)
        vibrate(context)
        val track = playTone()
        Handler(Looper.getMainLooper()).postDelayed({
            track?.let { runCatching { it.stop() }; it.release() }
            pending.finish()
        }, TONE_MILLIS)
    }

    private fun vibrate(context: Context) {
        try {
            val vibrator = context.getSystemService(Vibrator::class.java) ?: return
            if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 120, 180), -1))
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo vibrar", e)
        }
    }

    private fun playTone(): AudioTrack? = try {
        // Por el flujo de notificaciones: respeta el modo silencio, a diferencia de la alarma de despertar.
        StaticTrack.create(
            RestToneSynth.samples(SAMPLE_RATE), SAMPLE_RATE, loop = false,
            gain = VolumeCurve.gain(VolumeCurve.REST_LEVEL), usage = AudioAttributes.USAGE_NOTIFICATION,
        )
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo reproducir el aviso de descanso", e)
        null
    }

    private companion object {
        const val TAG = "RestReceiver"
        const val SAMPLE_RATE = 44_100
        const val TONE_MILLIS = 900L
        const val STALE_TOLERANCE_MILLIS = 1_500L
    }
}
