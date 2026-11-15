package com.hermesmarcano.zen.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/** Imagen en escala de grises (0..255), fila a fila. */
class GrayImage(val width: Int, val height: Int, val pixels: FloatArray) {
    init {
        require(width > 0 && height > 0) { "tamaño inválido: ${width}x$height" }
        require(pixels.size == width * height) { "se esperaban ${width * height} píxeles, hay ${pixels.size}" }
    }

    operator fun get(x: Int, y: Int): Float = pixels[y * width + x]

    /** Reescala promediando el área cubierta por cada píxel destino. */
    fun resized(newWidth: Int, newHeight: Int): GrayImage {
        val out = FloatArray(newWidth * newHeight)
        for (ty in 0 until newHeight) {
            val sy0 = ty * height / newHeight
            val sy1 = maxOf(sy0 + 1, ((ty + 1) * height + newHeight - 1) / newHeight).coerceAtMost(height)
            for (tx in 0 until newWidth) {
                val sx0 = tx * width / newWidth
                val sx1 = maxOf(sx0 + 1, ((tx + 1) * width + newWidth - 1) / newWidth).coerceAtMost(width)
                var sum = 0f
                for (sy in sy0 until sy1) for (sx in sx0 until sx1) sum += pixels[sy * width + sx]
                out[ty * newWidth + tx] = sum / ((sy1 - sy0) * (sx1 - sx0))
            }
        }
        return GrayImage(newWidth, newHeight, out)
    }
}

/**
 * Descriptor de "este lugar": histogramas de orientación de bordes por celdas (estilo HOG) con
 * normalización por bloques. Los bordes son mucho más estables que los píxeles ante cambios de
 * luz (no es lo mismo el patio a las 6 de la mañana que a mediodía), y la normalización evita
 * que el ruido de una foto oscura se amplifique en las zonas lisas.
 *
 * Es lógica pura y se prueba con variaciones sintéticas (ver `PhotoDescriptorTest`); el umbral
 * final sigue necesitando fotos reales [CALIBRAR EN DISPOSITIVO].
 */
class PhotoDescriptor private constructor(
    private val landscape: Boolean,
    private val values: FloatArray,
    private val blockCount: Int,
) {
    /**
     * Distancia en [0, ~1.5]: 0 = idénticas. Fotos con distinta orientación (horizontal contra
     * vertical) no son comparables y cuentan como diferentes.
     */
    fun distanceTo(other: PhotoDescriptor): Float {
        if (landscape != other.landscape || values.size != other.values.size) return INCOMPARABLE
        var sum = 0.0
        for (i in values.indices) {
            val d = (values[i] - other.values[i]).toDouble()
            sum += d * d
        }
        return sqrt(sum / blockCount).toFloat()
    }

    companion object {
        const val INCOMPARABLE = 2f

        private const val CELL = 8
        private const val BINS = 8
        private const val LANDSCAPE_WIDTH = 64
        private const val LANDSCAPE_HEIGHT = 48

        /** Gradiente medio (0..255) por debajo del cual la imagen es "lisa" (objetivo tapado, foto negra). */
        private const val MIN_MEAN_GRADIENT = 1.0f

        /** Suavizado de la normalización: los bloques mucho más débiles que la escena no se amplifican. */
        private const val NORMALIZATION_EPSILON = 0.5
        private const val CLIP = 0.3

        /**
         * `null` si la imagen no contiene información útil (lisa): una foto con el objetivo
         * tapado nunca debe poder coincidir con una referencia.
         */
        fun from(image: GrayImage): PhotoDescriptor? {
            val landscape = image.width >= image.height
            val width = if (landscape) LANDSCAPE_WIDTH else LANDSCAPE_HEIGHT
            val height = if (landscape) LANDSCAPE_HEIGHT else LANDSCAPE_WIDTH

            val blurred = blur(image.resized(width, height))
            val gradients = gradients(blurred)
            if (gradients.meanMagnitude < MIN_MEAN_GRADIENT) return null

            val cellsX = width / CELL
            val cellsY = height / CELL
            val histograms = FloatArray(cellsX * cellsY * BINS)
            val magnitudeFloor = 0.5f * gradients.meanMagnitude
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val magnitude = gradients.magnitude[y * width + x] - magnitudeFloor
                    if (magnitude <= 0f) continue
                    // Orientación sin signo en [0, π), repartida entre los dos bins vecinos.
                    var angle = atan2(gradients.gy[y * width + x], gradients.gx[y * width + x])
                    if (angle < 0f) angle += PI.toFloat()
                    val position = angle / PI.toFloat() * BINS - 0.5f
                    val lower = floor(position).toInt()
                    val fraction = position - lower
                    val firstBin = ((lower % BINS) + BINS) % BINS
                    val secondBin = (firstBin + 1) % BINS

                    // Voto espacial bilineal entre las 4 celdas más cercanas: un desplazamiento de unos
                    // píxeles mueve peso entre celdas vecinas en vez de saltar de golpe de una a otra.
                    val cellX = (x + 0.5f) / CELL - 0.5f
                    val cellY = (y + 0.5f) / CELL - 0.5f
                    val x0 = floor(cellX).toInt()
                    val y0 = floor(cellY).toInt()
                    val fx = cellX - x0
                    val fy = cellY - y0
                    for (dy in 0..1) {
                        val cy = y0 + dy
                        if (cy !in 0 until cellsY) continue
                        val wy = if (dy == 0) 1 - fy else fy
                        for (dx in 0..1) {
                            val cx = x0 + dx
                            if (cx !in 0 until cellsX) continue
                            val weight = magnitude * wy * (if (dx == 0) 1 - fx else fx)
                            val base = (cy * cellsX + cx) * BINS
                            histograms[base + firstBin] += weight * (1 - fraction)
                            histograms[base + secondBin] += weight * fraction
                        }
                    }
                }
            }

            // Bloques de 2x2 celdas con solape; el nivel de la escena fija el suavizado.
            val blocksX = cellsX - 1
            val blocksY = cellsY - 1
            val blockSize = 4 * BINS
            val raw = FloatArray(blocksX * blocksY * blockSize)
            var energySum = 0.0
            for (by in 0 until blocksY) {
                for (bx in 0 until blocksX) {
                    var offset = (by * blocksX + bx) * blockSize
                    var norm = 0.0
                    for (cy in 0..1) for (cx in 0..1) {
                        val base = ((by + cy) * cellsX + (bx + cx)) * BINS
                        for (bin in 0 until BINS) {
                            val v = histograms[base + bin]
                            raw[offset++] = v
                            norm += v.toDouble() * v
                        }
                    }
                    energySum += sqrt(norm)
                }
            }
            val sceneLevel = energySum / (blocksX * blocksY)
            if (sceneLevel <= 0.0) return null
            val epsilon = NORMALIZATION_EPSILON * sceneLevel

            val values = FloatArray(raw.size)
            for (block in 0 until blocksX * blocksY) {
                val start = block * blockSize
                var norm = 0.0
                for (i in 0 until blockSize) norm += raw[start + i].toDouble() * raw[start + i]
                val scale = 1.0 / sqrt(norm + epsilon * epsilon)
                for (i in 0 until blockSize) {
                    values[start + i] = minOf((raw[start + i] * scale).toFloat(), CLIP.toFloat())
                }
            }
            return PhotoDescriptor(landscape, values, blocksX * blocksY)
        }

        private fun blur(image: GrayImage): GrayImage {
            val w = image.width
            val h = image.height
            val horizontal = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val left = image[maxOf(x - 1, 0), y]
                val right = image[minOf(x + 1, w - 1), y]
                horizontal[y * w + x] = (left + 2 * image[x, y] + right) / 4f
            }
            val out = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val up = horizontal[maxOf(y - 1, 0) * w + x]
                val down = horizontal[minOf(y + 1, h - 1) * w + x]
                out[y * w + x] = (up + 2 * horizontal[y * w + x] + down) / 4f
            }
            return GrayImage(w, h, out)
        }

        private class Gradients(val gx: FloatArray, val gy: FloatArray, val magnitude: FloatArray, val meanMagnitude: Float)

        private fun gradients(image: GrayImage): Gradients {
            val w = image.width
            val h = image.height
            val gx = FloatArray(w * h)
            val gy = FloatArray(w * h)
            val magnitude = FloatArray(w * h)
            var sum = 0.0
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val dx = (image[x + 1, y - 1] + 2 * image[x + 1, y] + image[x + 1, y + 1]) -
                    (image[x - 1, y - 1] + 2 * image[x - 1, y] + image[x - 1, y + 1])
                val dy = (image[x - 1, y + 1] + 2 * image[x, y + 1] + image[x + 1, y + 1]) -
                    (image[x - 1, y - 1] + 2 * image[x, y - 1] + image[x + 1, y - 1])
                val i = y * w + x
                gx[i] = dx / 8f
                gy[i] = dy / 8f
                magnitude[i] = hypot(gx[i], gy[i])
                sum += magnitude[i]
            }
            return Gradients(gx, gy, magnitude, (sum / (w * h)).toFloat())
        }
    }
}
