package com.hermesmarcano.zen.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoDescriptorTest {
    private fun descriptor(image: GrayImage) = PhotoDescriptor.from(image)!!

    private val threshold = PhotoMatchPolicy.DEFAULT_THRESHOLD

    @Test fun identicalImagesHaveZeroDistance() {
        val scene = SyntheticScenes.Scene(320, 240, 1)
        assertEquals(0f, descriptor(scene.render()).distanceTo(descriptor(scene.render())), 1e-6f)
    }

    @Test fun flatImageIsNotInformative() {
        assertNull(PhotoDescriptor.from(GrayImage(160, 120, FloatArray(160 * 120) { 90f })))
    }

    @Test fun blackPhotoWithSensorNoiseIsNotInformative() {
        val random = java.util.Random(3)
        val noisy = FloatArray(160 * 120) { (random.nextGaussian().toFloat() * 0.8f).coerceAtLeast(0f) }
        assertNull(PhotoDescriptor.from(GrayImage(160, 120, noisy)))
    }

    @Test fun differentOrientationsAreIncomparable() {
        val scene = SyntheticScenes.Scene(320, 240, 1)
        val landscape = descriptor(scene.render())
        val portrait = descriptor(scene.render(width = 240, height = 320))
        assertEquals(PhotoDescriptor.INCOMPARABLE, landscape.distanceTo(portrait), 0f)
        assertTrue(PhotoDescriptor.INCOMPARABLE >= threshold)
    }

    @Test fun portraitPhotosOfTheSamePlaceMatch() {
        val scene = SyntheticScenes.Scene(240, 320, 5)
        val reference = descriptor(scene.render())
        val candidate = descriptor(scene.render(brightness = 0.7f, noise = 4f, noiseSeed = 9, shiftX = 0.01f))
        assertTrue(reference.distanceTo(candidate) < threshold)
    }

    @Test fun resolutionDoesNotChangeTheDescriptorMuch() {
        val scene = SyntheticScenes.Scene(320, 240, 2)
        val high = descriptor(scene.render(width = 640, height = 480))
        val low = descriptor(scene.render(width = 320, height = 240))
        assertTrue(high.distanceTo(low) < threshold / 2)
    }

    @Test fun samePlaceUnderLightingAndNoiseVariationsMatches() {
        val worst = (1L..20L).maxOf { seed ->
            val scene = SyntheticScenes.Scene(320, 240, seed)
            val reference = descriptor(scene.render())
            listOf(
                scene.render(brightness = 0.35f, noise = 3f, noiseSeed = seed),              // madrugada
                scene.render(brightness = 1f, gamma = 1.6f, noise = 2f, noiseSeed = seed),   // contraste distinto
                scene.render(shiftX = 0.015f, shiftY = 0.01f, noise = 2f, noiseSeed = seed), // cámara movida un poco
                scene.render(brightness = 0.6f, shiftX = -0.02f, noise = 5f, noiseSeed = seed),
            ).maxOf { reference.distanceTo(descriptor(it)) }
        }
        println("peor distancia mismo lugar = $worst (umbral $threshold)")
        assertTrue("peor distancia $worst >= umbral $threshold", worst < threshold)
    }

    @Test fun differentPlacesAreRejected() {
        val scenes = (1L..20L).map { SyntheticScenes.Scene(320, 240, it) }
        var best = Float.MAX_VALUE
        for (i in scenes.indices) for (j in scenes.indices) {
            if (i == j) continue
            best = minOf(best, descriptor(scenes[i].render()).distanceTo(descriptor(scenes[j].render(brightness = 0.6f, noise = 2f))))
        }
        println("mejor distancia entre lugares distintos = $best (umbral $threshold)")
        assertTrue("lugares distintos demasiado cercanos: $best <= $threshold", best > threshold)
    }

    @Test fun aLargeShiftIsRejected() {
        val scene = SyntheticScenes.Scene(320, 240, 4)
        val reference = descriptor(scene.render())
        val elsewhere = descriptor(scene.render(shiftX = 0.35f, shiftY = 0.25f))
        assertTrue(reference.distanceTo(elsewhere) > threshold)
    }

    @Test fun resizeAveragesAreaAndKeepsRange() {
        val image = GrayImage(4, 4, FloatArray(16) { if (it % 2 == 0) 0f else 200f })
        val small = image.resized(2, 2)
        assertTrue(small.pixels.all { it in 0f..200f })
        assertEquals(100f, small.pixels[0], 1e-3f)
        assertNotNull(small)
    }

    @Test(expected = IllegalArgumentException::class)
    fun grayImageValidatesPixelCount() {
        GrayImage(3, 3, FloatArray(8))
    }
}

class PhotoVerifierTest {
    private val scene = SyntheticScenes.Scene(320, 240, 11)
    private val other = SyntheticScenes.Scene(320, 240, 12)
    private val flat = GrayImage(160, 120, FloatArray(160 * 120) { 70f })

    @Test fun samePlaceMatches() {
        val outcome = PhotoVerifier.verify(scene.render(brightness = 0.5f, noise = 3f), listOf(scene.render())) as PhotoVerifier.Outcome.Compared
        assertTrue(outcome.isMatch)
    }

    @Test fun differentPlaceDoesNotMatch() {
        val outcome = PhotoVerifier.verify(other.render(), listOf(scene.render())) as PhotoVerifier.Outcome.Compared
        assertTrue(!outcome.isMatch)
    }

    @Test fun anyOfSeveralReferencesCanMatch() {
        val outcome = PhotoVerifier.verify(scene.render(brightness = 0.5f, noise = 2f), listOf(other.render(), scene.render())) as PhotoVerifier.Outcome.Compared
        assertTrue(outcome.isMatch)
    }

    @Test fun flatCandidateIsUnreadableNeverAMatch() {
        assertEquals(PhotoVerifier.Outcome.Unreadable, PhotoVerifier.verify(flat, listOf(scene.render())))
    }

    @Test fun noUsableReferencesIsUnreadableNeverAMatch() {
        assertEquals(PhotoVerifier.Outcome.Unreadable, PhotoVerifier.verify(scene.render(), emptyList()))
        assertEquals(PhotoVerifier.Outcome.Unreadable, PhotoVerifier.verify(scene.render(), listOf(flat)))
    }

    @Test fun identicalFrameIsTreatedAsUnreadableByThePolicyNotAMatch() {
        // Distancia exactamente 0 (descriptor degenerado o la misma imagen): la política la descarta.
        val outcome = PhotoVerifier.verify(scene.render(), listOf(scene.render()))
        assertEquals(PhotoVerifier.Outcome.Unreadable, outcome)
    }

    @Test fun wrongOrientationIsNeverAMatch() {
        val portrait = scene.render(width = 240, height = 320)
        val outcome = PhotoVerifier.verify(portrait, listOf(scene.render())) as PhotoVerifier.Outcome.Compared
        assertTrue(!outcome.isMatch)
    }
}
