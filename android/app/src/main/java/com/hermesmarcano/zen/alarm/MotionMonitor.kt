package com.hermesmarcano.zen.alarm

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.hermesmarcano.zen.core.GravityFilter
import com.hermesmarcano.zen.core.MotionClassifier
import com.hermesmarcano.zen.core.MotionSample
import com.hermesmarcano.zen.core.MotionState
import kotlin.math.sqrt

/**
 * Adaptador de sensores: convierte acelerómetro + giroscopio en [MotionSample] para el
 * clasificador puro. Sin sensores, el estado se queda en STILL y el volumen simplemente sube.
 */
class MotionMonitor(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val thread = HandlerThread("zen-motion")
    private val classifier = MotionClassifier()
    private val gravityFilter = GravityFilter()
    private var usesLinearSensor = false
    private var rotation = 0.0

    @Volatile
    var state: MotionState = MotionState.STILL
        private set

    @Volatile
    private var suppressedUntilNanos = 0L

    fun start() {
        thread.start()
        val handler = Handler(thread.looper)
        val linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val accelerometer = linear ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        usesLinearSensor = linear != null
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        thread.quitSafely()
    }

    /** La propia vibración de la alarma mueve el teléfono: se ignoran los sensores mientras dura el pulso. */
    fun suppress(seconds: Double) {
        suppressedUntilNanos = SystemClock.elapsedRealtimeNanos() + (seconds * 1e9).toLong()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                rotation = sqrt((x * x + y * y + z * z).toDouble())
            }
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
                if (SystemClock.elapsedRealtimeNanos() < suppressedUntilNanos) return
                val x = event.values[0].toDouble()
                val y = event.values[1].toDouble()
                val z = event.values[2].toDouble()
                val metersPerSecondSquared = if (usesLinearSensor) sqrt(x * x + y * y + z * z) else gravityFilter.linearMagnitude(x, y, z)
                state = classifier.ingest(
                    MotionSample(event.timestamp / 1e9, metersPerSecondSquared / STANDARD_GRAVITY, rotation),
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val STANDARD_GRAVITY = 9.80665
    }
}
