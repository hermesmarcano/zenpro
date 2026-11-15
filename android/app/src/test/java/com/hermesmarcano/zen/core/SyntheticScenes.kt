package com.hermesmarcano.zen.core

import java.util.Random
import kotlin.math.pow

/** Escenas sintéticas para probar el descriptor: rectángulos, un degradado y un par de líneas. */
object SyntheticScenes {
    class Scene(val width: Int, val height: Int, val seed: Long) {
        private class Rect(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val level: Float)

        private val rects: List<Rect>
        private val gradient: Float

        init {
            val random = Random(seed)
            gradient = 40f + random.nextFloat() * 60f
            rects = List(9) {
                val x = random.nextFloat() * 0.8f
                val y = random.nextFloat() * 0.8f
                Rect(x, y, x + 0.08f + random.nextFloat() * 0.25f, y + 0.08f + random.nextFloat() * 0.25f, random.nextFloat() * 255f)
            }
        }

        /** [shiftX]/[shiftY] en fracción de la imagen (cámara movida unos centímetros). */
        fun render(
            shiftX: Float = 0f,
            shiftY: Float = 0f,
            brightness: Float = 1f,
            gamma: Float = 1f,
            noise: Float = 0f,
            noiseSeed: Long = 1,
            zoom: Float = 1f,
            rotationDegrees: Float = 0f,
            blurRadius: Int = 0,
            width: Int = this.width,
            height: Int = this.height,
        ): GrayImage {
            val random = Random(noiseSeed)
            val pixels = FloatArray(width * height)
            for (py in 0 until height) for (px in 0 until width) {
                // Zoom y giro alrededor del centro de la imagen (la cámara no está exactamente donde estuvo).
                val cx = (px.toFloat() / width - 0.5f) / zoom
                val cy = (py.toFloat() / height - 0.5f) / zoom
                val cos = kotlin.math.cos(Math.toRadians(rotationDegrees.toDouble())).toFloat()
                val sin = kotlin.math.sin(Math.toRadians(rotationDegrees.toDouble())).toFloat()
                val u = 0.5f + cx * cos - cy * sin + shiftX
                val v = 0.5f + cx * sin + cy * cos + shiftY
                var value = 60f + gradient * u + 0.5f * gradient * v
                for (r in rects) if (u >= r.x0 && u < r.x1 && v >= r.y0 && v < r.y1) value = r.level
                value = (value / 255f).coerceIn(0f, 1f).pow(gamma) * 255f * brightness
                value += (random.nextGaussian().toFloat()) * noise
                pixels[py * width + px] = value.coerceIn(0f, 255f)
            }
            val out = GrayImage(width, height, pixels)
            return if (blurRadius > 0) boxBlur(out, blurRadius) else out
        }

        private fun boxBlur(image: GrayImage, radius: Int): GrayImage {
            val w = image.width; val h = image.height
            val result = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var sum = 0f; var n = 0
                for (dy in -radius..radius) for (dx in -radius..radius) {
                    val xx = x + dx; val yy = y + dy
                    if (xx in 0 until w && yy in 0 until h) { sum += image[xx, yy]; n++ }
                }
                result[y * w + x] = sum / n
            }
            return GrayImage(w, h, result)
        }
    }
}
