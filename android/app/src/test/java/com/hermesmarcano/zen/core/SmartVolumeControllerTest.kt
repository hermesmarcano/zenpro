package com.hermesmarcano.zen.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartVolumeControllerTest {
    private val profile = SmartVolumeProfile()

    private class Sim(profile: SmartVolumeProfile) {
        val controller = SmartVolumeController(profile, startTime = 0.0)
        var now = 0.0
        var last = SmartVolumeOutput(0f, null)
        fun run(seconds: Double, motion: MotionState, engaged: Boolean = false, dt: Double = 0.1) {
            val end = now + seconds
            while (now < end - 1e-9) {
                now += dt
                last = controller.update(now, motion, engaged)
            }
        }
    }

    @Test fun startsLowAndVibrating() {
        val sim = Sim(profile)
        sim.run(0.1, MotionState.STILL)
        assertTrue(sim.last.volume in 0.14f..0.2f)
        assertNotNull(sim.last.vibrationInterval)
    }

    @Test fun rampsUpWhileUntouched() {
        val sim = Sim(profile)
        sim.run(45.0, MotionState.STILL)
        val mid = sim.last.volume
        sim.run(60.0, MotionState.STILL)
        assertTrue(mid in 0.45f..0.7f)
        assertEquals(1f, sim.last.volume, 1e-4f)
    }

    @Test fun vibrationGetsFasterAsVolumeRises() {
        val sim = Sim(profile)
        sim.run(0.1, MotionState.STILL)
        val slow = sim.last.vibrationInterval!!
        sim.run(100.0, MotionState.STILL)
        val fast = sim.last.vibrationInterval!!
        assertTrue(fast < slow)
        assertEquals(profile.fastestVibrationInterval, fast, 1e-6)
    }

    @Test fun pickupHalvesThenDropsToCarriedAndStopsVibrating() {
        val sim = Sim(profile)
        sim.run(80.0, MotionState.STILL) // casi al máximo
        val before = sim.last.volume
        sim.run(0.5, MotionState.MOVING)
        val half = sim.last.volume
        assertTrue("al coger baja pero no de golpe: $half", half < before && half > before * 0.5f - 0.01f)
        sim.run(10.0, MotionState.MOVING)
        assertEquals(profile.carriedVolume, sim.last.volume, 1e-4f)
        assertNull(sim.last.vibrationInterval)
    }

    @Test fun volumeNeverJumpsFasterThanTheSlewLimit() {
        val sim = Sim(profile)
        sim.run(80.0, MotionState.STILL)
        var previous = sim.last.volume
        for (i in 1..100) {
            sim.now += 0.1
            val out = sim.controller.update(sim.now, MotionState.MOVING, false)
            assertTrue(previous - out.volume <= profile.maxFallPerSecond * 0.1f + 1e-5f)
            previous = out.volume
        }
    }

    @Test fun leftStillAfterCarryingReturnsToHalfAndRampsToFull() {
        val sim = Sim(profile)
        sim.run(5.0, MotionState.MOVING) // lo cogió
        sim.run(profile.stationaryDelay + 4.0, MotionState.STILL) // lo dejó
        assertTrue(sim.controller.phase is SmartVolumeController.Phase.LeftBehind)
        sim.run(5.0, MotionState.STILL)
        assertTrue("sube hacia el 50%: ${sim.last.volume}", sim.last.volume in 0.4f..0.65f)
        assertNotNull(sim.last.vibrationInterval)
        sim.run(70.0, MotionState.STILL)
        assertEquals(1f, sim.last.volume, 1e-4f)
    }

    @Test fun briefStillnessWhileCarryingDoesNotRaiseVolume() {
        val sim = Sim(profile)
        sim.run(5.0, MotionState.MOVING)
        sim.run(profile.stationaryDelay - 1.0, MotionState.STILL)
        sim.run(2.0, MotionState.MOVING)
        sim.run(profile.stationaryDelay - 1.0, MotionState.STILL)
        assertTrue(sim.controller.phase is SmartVolumeController.Phase.Carried)
        assertEquals(profile.carriedVolume, sim.last.volume, 1e-4f)
    }

    @Test fun engagedUserHoldingFirmDoesNotTriggerLeftBehind() {
        val sim = Sim(profile)
        sim.run(5.0, MotionState.MOVING)
        sim.run(60.0, MotionState.STILL, engaged = true)
        assertTrue(sim.controller.phase is SmartVolumeController.Phase.Carried)
        assertEquals(profile.carriedVolume, sim.last.volume, 1e-4f)
    }

    @Test fun engagedUserCountsAsPickupEvenIfStill() {
        val sim = Sim(profile)
        sim.run(30.0, MotionState.STILL, engaged = true)
        assertTrue(sim.controller.phase is SmartVolumeController.Phase.Carried)
    }

    @Test fun pickingUpAgainAfterLeftBehindDropsTheVolume() {
        val sim = Sim(profile)
        sim.run(3.0, MotionState.MOVING)
        sim.run(80.0, MotionState.STILL)
        assertEquals(1f, sim.last.volume, 1e-3f)
        sim.run(8.0, MotionState.MOVING)
        assertEquals(profile.carriedVolume, sim.last.volume, 1e-4f)
    }

    @Test fun clockGoingBackwardsDoesNotMoveBackwards() {
        val sim = Sim(profile)
        sim.run(10.0, MotionState.STILL)
        val v = sim.last.volume
        val out = sim.controller.update(5.0, MotionState.STILL, false)
        assertEquals(v, out.volume, 1e-6f)
    }

    @Test fun volumeStaysInRangeAlways() {
        val sim = Sim(profile)
        val states = listOf(MotionState.STILL, MotionState.MOVING)
        for (i in 0 until 2000) {
            sim.now += 0.07
            val out = sim.controller.update(sim.now, states[(i / 37) % 2], i % 211 < 20)
            assertTrue(out.volume in 0f..1f)
        }
    }
}
