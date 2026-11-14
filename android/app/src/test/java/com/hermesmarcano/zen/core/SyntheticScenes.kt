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
            width: Int = this.width,
            height: Int = this.height,
        ): GrayImage {
            val random = Random(noiseSeed)
            val pixels = FloatArray(width * height)
            for (py in 0 until height) for (px in 0 until width) {
                val u = px.toFloat() / width + shiftX
                val v = py.toFloat() / height + shiftY
                var value = 60f + gradient * u + 0.5f * gradient * v
                for (r in rects) if (u >= r.x0 && u < r.x1 && v >= r.y0 && v < r.y1) value = r.level
                value = (value / 255f).coerceIn(0f, 1f).pow(gamma) * 255f * brightness
                value += (random.nextGaussian().toFloat()) * noise
                pixels[py * width + px] = value.coerceIn(0f, 255f)
            }
            return GrayImage(width, height, pixels)
        }
    }
}
