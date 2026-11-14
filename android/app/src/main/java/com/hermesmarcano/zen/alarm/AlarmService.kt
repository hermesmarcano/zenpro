package com.hermesmarcano.zen.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.ServiceCompat
import com.hermesmarcano.zen.core.SmartVolumeController
import com.hermesmarcano.zen.core.SmartVolumeProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hace sonar la alarma: tono con volumen inteligente, vibración y sensores. Sigue sonando aunque
 * la app se cierre; solo [ACTION_STOP] (que la pantalla de apagado envía tras verificar el ancla)
 * lo detiene.
 */
class AlarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var tone: TonePlayer? = null
    private var motion: MotionMonitor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocus: AudioFocusRequest? = null
    private var savedAlarmVolume: Int? = null
    private var ringing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRinging()
            return START_NOT_STICKY
        }

        val alarmId = intent?.getStringExtra(EXTRA_ALARM_ID)
        // El estado compartido debe existir antes de publicar la notificación: la pantalla de
        // apagado puede abrirse en cuanto aparece, y si no encuentra alarma sonando se cerraría.
        val startedAt = SystemClock.elapsedRealtime() / 1000.0
        val isNewRing = alarmId != null && !ringing
        if (isNewRing) AlarmRuntime.begin(RingState(alarmId!!, startedAt))

        // Debe llamarse enseguida tras startForegroundService o el sistema mata el proceso.
        val notification = Notifications.ringing(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        try {
            ServiceCompat.startForeground(this, Notifications.ID_RINGING, notification, type)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground falló", e)
            AlarmRuntime.end()
            Notifications.postFallback(this)
            stopSelf()
            return START_NOT_STICKY
        }

        if (alarmId == null) {
            // Reinicio del servicio sin datos: si ya había una alarma sonando, se conserva.
            if (!ringing) stopRinging()
            return START_NOT_STICKY
        }
        // Una segunda alarma mientras suena la primera no interrumpe: ya hay que ir al ancla.
        if (isNewRing) beginRinging(startedAt)
        return START_REDELIVER_INTENT
    }

    private fun beginRinging(startedAt: Double) {
        ringing = true
        val profile = SmartVolumeProfile()

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zen:ringing")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }

        raiseAlarmStreamVolume()
        requestAudioFocus()

        val player = TonePlayer(this).also { it.start(profile.startVolume) }
        tone = player
        val monitor = MotionMonitor(this).also { it.start() }
        motion = monitor
        val controller = SmartVolumeController(profile, startTime = startedAt)
        val vibrator = vibrator()

        loop = scope.launch {
            var nextVibration = startedAt
            while (isActive) {
                val now = SystemClock.elapsedRealtime() / 1000.0
                val out = controller.update(now, monitor.state, AlarmRuntime.userEngaged)
                player.setVolume(out.volume)

                val interval = out.vibrationInterval
                if (interval != null && now >= nextVibration) {
                    pulse(vibrator, out.volume)
                    monitor.suppress(PULSE_SUPPRESS_SECONDS)
                    nextVibration = now + interval
                }
                if (interval == null) vibrator?.cancel()
                delay(TICK_MS)
            }
        }
    }

    private fun stopRinging() {
        loop?.cancel()
        loop = null
        tone?.stop()
        tone = null
        motion?.stop()
        motion = null
        vibrator()?.cancel()
        audioFocus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        audioFocus = null
        restoreAlarmStreamVolume()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ringing = false
        AlarmRuntime.end()
        Notifications.cancelAll(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Si el sistema destruye el servicio sin pasar por stopRinging, no dejar sensores ni wake lock colgados.
        if (ringing) {
            loop?.cancel()
            tone?.stop()
            motion?.stop()
            wakeLock?.let { if (it.isHeld) it.release() }
            restoreAlarmStreamVolume()
            AlarmRuntime.end()
        }
        scope.cancel()
        super.onDestroy()
    }

    /** Si el usuario dejó el volumen de alarmas en 0 (o casi), la alarma sería inútil. */
    private fun raiseAlarmStreamVolume() {
        try {
            val audio = getSystemService(AudioManager::class.java)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val current = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val floor = (max * ALARM_STREAM_FLOOR).toInt().coerceAtLeast(1)
            if (current < floor) {
                savedAlarmVolume = current
                audio.setStreamVolume(AudioManager.STREAM_ALARM, floor, 0)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No se pudo ajustar el volumen de alarmas", e)
        }
    }

    private fun restoreAlarmStreamVolume() {
        val saved = savedAlarmVolume ?: return
        savedAlarmVolume = null
        try {
            getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, saved, 0)
        } catch (_: SecurityException) {
        }
    }

    private fun requestAudioFocus() {
        val audio = getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            .setOnAudioFocusChangeListener { /* la alarma no cede */ }
            .build()
        audio.requestAudioFocus(request)
        audioFocus = request
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    @Suppress("DEPRECATION")
    private fun pulse(vibrator: Vibrator?, volume: Float) {
        if (vibrator == null || !vibrator.hasVibrator()) return
        val effect = if (vibrator.hasAmplitudeControl()) {
            val amplitude = (60 + 195 * volume).toInt().coerceIn(1, 255)
            VibrationEffect.createOneShot(PULSE_MS, amplitude)
        } else {
            VibrationEffect.createOneShot(PULSE_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        vibrator.vibrate(effect, attributes)
    }

    companion object {
        private const val TAG = "AlarmService"
        const val ACTION_RING = "com.hermesmarcano.zen.action.RING"
        const val ACTION_STOP = "com.hermesmarcano.zen.action.STOP"
        const val EXTRA_ALARM_ID = "alarmId"
        private const val TICK_MS = 100L
        private const val PULSE_MS = 350L
        private const val PULSE_SUPPRESS_SECONDS = 0.6
        private const val ALARM_STREAM_FLOOR = 0.6
        private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L

        fun ringIntent(context: Context, alarmId: String): Intent =
            Intent(context, AlarmService::class.java).setAction(ACTION_RING).putExtra(EXTRA_ALARM_ID, alarmId)

        fun stopIntent(context: Context): Intent = Intent(context, AlarmService::class.java).setAction(ACTION_STOP)
    }
}
