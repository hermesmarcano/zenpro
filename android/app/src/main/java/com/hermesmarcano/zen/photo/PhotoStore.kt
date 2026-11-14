package com.hermesmarcano.zen.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.hermesmarcano.zen.core.GrayImage
import com.hermesmarcano.zen.data.PhotoAnchor
import com.hermesmarcano.zen.data.ZenState
import java.io.File
import java.io.IOException

/** Fotos de referencia de las anclas por foto, como JPEG dentro del almacenamiento privado de la app. */
object PhotoStore {
    /** Lado mayor máximo de lo que se guarda: suficiente para el descriptor y barato en disco. */
    const val MAX_SIDE = 800
    private const val ANALYSIS_SIDE = 320

    private fun root(context: Context) = File(context.filesDir, "anchors")
    private fun dir(context: Context, anchorId: String) = File(root(context), anchorId)

    @Throws(IOException::class)
    fun saveReference(context: Context, anchorId: String, bitmap: Bitmap): String {
        val directory = dir(context, anchorId)
        if (!directory.exists() && !directory.mkdirs()) throw IOException("No se pudo crear $directory")
        val name = "ref-${System.nanoTime()}.jpg"
        File(directory, name).outputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) throw IOException("No se pudo codificar la foto")
        }
        return name
    }

    fun file(context: Context, anchorId: String, name: String): File? =
        if (isSafeName(name)) File(dir(context, anchorId), name) else null

    fun delete(context: Context, anchorId: String, name: String) {
        if (isSafeName(name)) File(dir(context, anchorId), name).delete()
    }

    fun loadGray(context: Context, anchorId: String, name: String): GrayImage? {
        if (!isSafeName(name)) return null
        val file = File(dir(context, anchorId), name)
        if (!file.exists()) return null
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
        return bitmap.toGrayImage().also { bitmap.recycle() }
    }

    fun deleteAnchor(context: Context, anchorId: String) {
        dir(context, anchorId).deleteRecursively()
    }

    /** Borra carpetas de anclas por foto que ya no existen en el estado (alta abandonada, ancla eliminada). */
    fun deleteOrphans(context: Context, state: ZenState) {
        val keep = state.anchors.filterIsInstance<PhotoAnchor>().map { it.id }.toSet()
        root(context).listFiles()?.forEach { if (it.name !in keep) it.deleteRecursively() }
    }

    private fun isSafeName(name: String) = name.isNotEmpty() && '/' !in name && '\\' !in name && ".." !in name
}

/** Escala de grises (Rec. 601) con el lado mayor limitado: el descriptor trabaja a 64x48, esto solo abarata el paso previo. */
fun Bitmap.toGrayImage(maxSide: Int = 320): GrayImage {
    val scale = maxSide.toFloat() / maxOf(width, height)
    val source = if (scale < 1f) {
        Bitmap.createScaledBitmap(this, maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()), true)
    } else {
        this
    }
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    val gray = FloatArray(pixels.size) {
        val p = pixels[it]
        0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
    }
    val image = GrayImage(source.width, source.height, gray)
    if (source !== this) source.recycle()
    return image
}

fun Bitmap.rotated(degrees: Int): Bitmap {
    if (degrees % 360 == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

fun Bitmap.limitedTo(maxSide: Int): Bitmap {
    val scale = maxSide.toFloat() / maxOf(width, height)
    if (scale >= 1f) return this
    return Bitmap.createScaledBitmap(this, maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()), true)
}
