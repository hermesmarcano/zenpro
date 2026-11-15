package com.hermesmarcano.zen.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.hermesmarcano.zen.core.QrCodec
import java.io.File

/**
 * Hoja lista para imprimir: el QR, el nombre del lugar y el código de respaldo. Imprimir el
 * código junto al QR lo mantiene útil sin ser un atajo: solo está a la vista estando en el sitio.
 */
object PrintableAnchor {
    private const val WIDTH = 1200
    private const val HEIGHT = 1600

    fun qrBitmap(payload: String, scale: Int = 12): Bitmap {
        val matrix = QrCodec.encode(payload)
        val side = matrix.size * scale
        return Bitmap.createBitmap(matrix.toArgb(scale), side, side, Bitmap.Config.ARGB_8888)
    }

    fun sheet(label: String, backupCode: String, payload: String): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        fun centered(text: String, size: Float, color: Int, baseline: Float, typeface: Typeface = Typeface.DEFAULT) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = size
                this.typeface = typeface
                textAlign = Paint.Align.CENTER
            }
            // Una etiqueta larga se recorta con puntos suspensivos en vez de salirse de la hoja.
            val fitted = android.text.TextUtils.ellipsize(text, paint, WIDTH - 160f, android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(fitted, WIDTH / 2f, baseline, paint)
        }

        centered(label, 76f, Color.BLACK, 190f, Typeface.DEFAULT_BOLD)
        // Escala entera: todos los módulos con el mismo número de píxeles, sin bordes borrosos al imprimir.
        val matrix = QrCodec.encode(payload)
        val scale = (900 / matrix.size).coerceAtLeast(1)
        val side = matrix.size * scale
        val qr = Bitmap.createBitmap(matrix.toArgb(scale), side, side, Bitmap.Config.ARGB_8888)
        canvas.drawBitmap(qr, (WIDTH - side) / 2f, 260f + (900 - side) / 2f, null)
        centered("Código de respaldo", 44f, Color.DKGRAY, 1_270f)
        centered(backupCode, 110f, Color.BLACK, 1_400f, Typeface.MONOSPACE)
        centered("ZEN · solo úsalo si falla la cámara", 34f, Color.GRAY, 1_520f)
        return bitmap
    }

    /** Guarda la hoja y abre el selector de compartir (imprimir, guardar, mensajería). */
    fun share(context: Context, sheet: Bitmap, label: String) {
        val directory = File(context.cacheDir, "shared").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val file = File(directory, "zen-ancla.png")
        file.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Código de $label")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Compartir para imprimir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
