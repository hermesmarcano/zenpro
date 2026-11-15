package com.hermesmarcano.zen.android

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.hermesmarcano.zen.ZenApp
import com.hermesmarcano.zen.core.GrayImage
import com.hermesmarcano.zen.core.PhotoVerifier
import com.hermesmarcano.zen.core.QrCodec
import com.hermesmarcano.zen.core.SyntheticScenes
import com.hermesmarcano.zen.data.PhotoAnchor
import com.hermesmarcano.zen.data.QrAnchor
import com.hermesmarcano.zen.data.ZenState
import com.hermesmarcano.zen.photo.PhotoStore
import com.hermesmarcano.zen.photo.limitedTo
import com.hermesmarcano.zen.photo.rotated
import com.hermesmarcano.zen.photo.toGrayImage
import com.hermesmarcano.zen.ui.PrintableAnchor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cadenas completas que mezclan Bitmap/Canvas/JPEG reales (gráficos nativos) con la lógica pura. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ZenApp::class, sdk = [34])
class PipelineTest {
    private val app: ZenApp = ApplicationProvider.getApplicationContext()

    private fun GrayImage.toBitmap(): Bitmap {
        val argb = IntArray(pixels.size) { val v = pixels[it].toInt().coerceIn(0, 255); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    }

    @Test fun printedSheetStillContainsAScannableCodeForTheAnchor() {
        val anchor = QrAnchor("3f2b8c1e-5d4a-4e7b-9a10-aabbccddeeff", "Espejo del baño con un nombre bastante largo para recortar", "Zk3-secret_Q9", "042917")
        val sheet = PrintableAnchor.sheet(anchor.label, anchor.backupCode, anchor.payload)
        assertEquals(1200, sheet.width)
        assertEquals(1600, sheet.height)
        val pixels = IntArray(sheet.width * sheet.height).also { sheet.getPixels(it, 0, sheet.width, 0, 0, sheet.width, sheet.height) }
        // Lo que decodificaría una cámara al ver la hoja impresa: debe ser exactamente el ancla.
        val decoded = QrCodec.decodeArgb(pixels, sheet.width, sheet.height)
        assertEquals(anchor.payload, decoded)
        assertTrue(anchor.matches(decoded!!))
    }

    @Test fun onScreenQrBitmapIsScannable() {
        val payload = "zen://anchor/abc/def"
        val bitmap = PrintableAnchor.qrBitmap(payload)
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        assertEquals(payload, QrCodec.decodeArgb(pixels, bitmap.width, bitmap.height))
    }

    @Test fun photoReferenceSurvivesJpegStorageAndMatchesALaterShot() {
        val scene = SyntheticScenes.Scene(640, 480, 21)
        val anchorId = PhotoAnchor.newId()
        val name = PhotoStore.saveReference(app, anchorId, scene.render().toBitmap())

        val reference = PhotoStore.loadGray(app, anchorId, name)
        assertNotNull(reference)
        val sameSpotAtDawn = scene.render(brightness = 0.45f, noise = 4f, noiseSeed = 5, shiftX = 0.01f).toBitmap().toGrayImage()
        val outcome = PhotoVerifier.verify(sameSpotAtDawn, listOf(reference!!)) as PhotoVerifier.Outcome.Compared
        assertTrue("distancia ${outcome.bestDistance}", outcome.isMatch)

        val elsewhere = SyntheticScenes.Scene(640, 480, 22).render(noise = 2f, noiseSeed = 6).toBitmap().toGrayImage()
        val rejected = PhotoVerifier.verify(elsewhere, listOf(reference)) as PhotoVerifier.Outcome.Compared
        assertFalse("distancia ${rejected.bestDistance}", rejected.isMatch)
    }

    @Test fun photoHelpersRotateAndShrink() {
        val big = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        val rotated = big.rotated(90)
        assertEquals(1200, rotated.width)
        assertEquals(1600, rotated.height)
        val small = rotated.limitedTo(800)
        assertEquals(800, maxOf(small.width, small.height))
        assertEquals(600, minOf(small.width, small.height))
        assertTrue(big.rotated(0) === big)
        assertTrue(small.limitedTo(2000) === small)
    }

    @Test fun storeRejectsPathTraversalAndCleansOrphans() {
        val keep = PhotoStore.saveReference(app, "keep", Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        PhotoStore.saveReference(app, "orphan", Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        assertNull(PhotoStore.loadGray(app, "keep", "../../state.json"))
        assertNull(PhotoStore.file(app, "keep", "a/b.jpg"))
        PhotoStore.deleteOrphans(app, ZenState(anchors = listOf(PhotoAnchor("keep", "x", "000000", listOf(keep)))))
        assertTrue(File(app.filesDir, "anchors/keep").exists())
        assertFalse(File(app.filesDir, "anchors/orphan").exists())
    }

    @Test fun grayConversionOfAnExtremeAspectRatioKeepsDimensionsValid() {
        val strip = Bitmap.createBitmap(4000, 3, Bitmap.Config.ARGB_8888)
        val gray = strip.toGrayImage()
        assertTrue(gray.width >= 1 && gray.height >= 1)
        assertEquals(gray.width * gray.height, gray.pixels.size)
    }
}
