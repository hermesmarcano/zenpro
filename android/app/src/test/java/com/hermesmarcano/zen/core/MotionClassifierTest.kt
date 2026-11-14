package com.hermesmarcano.zen.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionClassifierTest {
    private fun feed(c: MotionClassifier, from: Double, seconds: Double, accel: Double, rot: Double, hz: Int = 50): MotionState {
        var state = c.state
        val steps = (seconds * hz).toInt()
        for (i in 1..steps) state = c.ingest(MotionSample(from + i.toDouble() / hz, accel, rot))
        return state
    }

    @Test fun startsStill() = assertEquals(MotionState.STILL, MotionClassifier().state)

    @Test fun sensorNoiseStaysStill() =
        assertEquals(MotionState.STILL, feed(MotionClassifier(), 0.0, 5.0, 0.004, 0.01))

    @Test fun walkingIsMoving() =
        assertEquals(MotionState.MOVING, feed(MotionClassifier(), 0.0, 3.0, 0.15, 0.6))

    @Test fun pickingUpCountsEvenWithoutRotation() =
        assertEquals(MotionState.MOVING, feed(MotionClassifier(), 0.0, 2.0, 0.1, 0.0))

    @Test fun returnsToStillWhenPutDown() {
        val c = MotionClassifier()
        feed(c, 0.0, 3.0, 0.15, 0.6)
        assertEquals(MotionState.STILL, feed(c, 3.0, 4.0, 0.003, 0.005))
    }

    @Test fun hysteresisPreventsFlickerInTheMiddleBand() {
        val c = MotionClassifier()
        feed(c, 0.0, 3.0, 0.15, 0.6)
        // Puntuación ~0.7: entre quieto (0.4) y movimiento (1.0). Estando en MOVING, se mantiene.
        val tuning = MotionClassifier.Tuning()
        val accel = tuning.accelerationScale * 0.7
        assertEquals(MotionState.MOVING, feed(c, 3.0, 0.6, accel, 0.0))
        // Y estando quieto, la misma banda no lo activa.
        val d = MotionClassifier()
        assertEquals(MotionState.STILL, feed(d, 0.0, 3.0, accel, 0.0))
    }

    @Test fun aSingleBumpDoesNotMakeItMovingForLong() {
        val c = MotionClassifier()
        feed(c, 0.0, 2.0, 0.002, 0.0)
        c.ingest(MotionSample(2.01, 0.5, 0.0))
        assertEquals(MotionState.STILL, feed(c, 2.01, 3.0, 0.002, 0.0))
    }

    @Test fun timestampsGoingBackwardsResetTheWindow() {
        val c = MotionClassifier()
        feed(c, 100.0, 2.0, 0.2, 0.8)
        // El sensor se reinicia y su reloj vuelve a ~0: no debe quedar basura de la ventana anterior.
        val state = feed(c, 0.0, 3.0, 0.001, 0.0)
        assertEquals(MotionState.STILL, state)
    }

    @Test fun resetClearsEverything() {
        val c = MotionClassifier()
        feed(c, 0.0, 2.0, 0.2, 0.8)
        c.reset()
        assertEquals(MotionState.STILL, c.state)
    }

    @Test fun gravityFilterIgnoresAConstantGravityVector() {
        val f = GravityFilter()
        var last = 0.0
        repeat(200) { last = f.linearMagnitude(0.0, 0.0, 9.81) }
        assertTrue("magnitud residual $last", last < 0.05)
    }

    @Test fun gravityFilterDetectsShaking() {
        val f = GravityFilter()
        repeat(100) { f.linearMagnitude(0.0, 0.0, 9.81) }
        var peak = 0.0
        for (i in 0 until 40) peak = maxOf(peak, f.linearMagnitude(if (i % 2 == 0) 4.0 else -4.0, 0.0, 9.81))
        assertTrue(peak > 2.0)
    }

    @Test fun gravityFilterFirstSampleDoesNotSpike() {
        assertEquals(0.0, GravityFilter().linearMagnitude(0.3, 9.7, 1.2), 0.9)
    }
}
