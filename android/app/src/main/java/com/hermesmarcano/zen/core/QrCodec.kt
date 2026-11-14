package com.hermesmarcano.zen.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Matriz de módulos de un QR ya con zona de silencio. `true` = módulo oscuro. */
class QrMatrix(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]

    /** Raster ARGB (negro/blanco) de `scale` píxeles por módulo. */
    fun toArgb(scale: Int): IntArray {
        require(scale > 0)
        val side = size * scale
        val out = IntArray(side * side)
        for (y in 0 until side) for (x in 0 until side) {
            out[y * side + x] = if (this[x / scale, y / scale]) BLACK else WHITE
        }
        return out
    }

    companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}

/** Codifica y decodifica los QR de ancla. Sin dependencias de Android, así se prueba en JVM. */
object QrCodec {
    /** Mínimo de la especificación: 4 módulos de margen blanco. Sin él, muchos lectores no encuentran el código. */
    const val QUIET_ZONE_MODULES = 4

    fun encode(payload: String, quietZone: Int = QUIET_ZONE_MODULES): QrMatrix {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val bare = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
        val margin = quietZone.coerceAtLeast(0)
        val size = bare.width + 2 * margin
        val dark = BooleanArray(size * size)
        for (y in 0 until bare.height) for (x in 0 until bare.width) {
            dark[(y + margin) * size + (x + margin)] = bare[x, y]
        }
        return QrMatrix(size, dark)
    }

    /** `null` si no hay un QR legible en el fotograma. */
    fun decodeLuminance(luminance: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        if (width <= 0 || height <= 0 || luminance.size < rowStride * (height - 1) + width) return null
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return decode(source)
    }

    fun decodeArgb(pixels: IntArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        return decode(RGBLuminanceSource(width, height, pixels))
    }

    private fun decode(source: LuminanceSource): String? {
        val reader = MultiFormatReader()
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        )
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: NotFoundException) {
            null
        } catch (_: com.google.zxing.ChecksumException) {
            null
        } catch (_: com.google.zxing.FormatException) {
            null
        } finally {
            reader.reset()
        }
    }
}
