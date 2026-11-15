package com.hermesmarcano.zen.sound

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.hermesmarcano.zen.core.SoundImportPolicy
import com.hermesmarcano.zen.data.SoundChoice
import com.hermesmarcano.zen.data.ZenState
import java.io.File
import java.io.IOException

/** Archivos de audio elegidos por el usuario, copiados al almacenamiento privado de la app. */
object SoundStorage {
    sealed interface ImportResult {
        data class Success(val choice: SoundChoice.Custom) : ImportResult
        data class Failure(val reason: String) : ImportResult
    }

    private fun dir(context: Context) = File(context.filesDir, "sounds")

    fun file(context: Context, fileName: String): File? =
        if (SoundImportPolicy.isSafeFileName(fileName)) File(dir(context), fileName).takeIf { it.isFile } else null

    /**
     * Copia el audio elegido (con un tope de tamaño) y comprueba que el sistema pueda leerlo. Se
     * copia, no se guarda el enlace: el permiso sobre el archivo original puede desaparecer y la
     * alarma no puede quedarse sin sonido por eso. Bloqueante: llamar fuera del hilo principal.
     */
    fun import(context: Context, uri: Uri, role: String): ImportResult {
        val directory = dir(context)
        if (!directory.exists() && !directory.mkdirs()) return ImportResult.Failure("No se pudo guardar el archivo.")
        val target = File(directory, "$role-${System.nanoTime()}.audio")
        try {
            var copied = 0L
            val input = context.contentResolver.openInputStream(uri) ?: return ImportResult.Failure("No se pudo abrir el archivo.")
            input.use { source ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > SoundImportPolicy.MAX_BYTES) {
                            target.delete()
                            return ImportResult.Failure("El archivo pesa demasiado (máximo 20 MB).")
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
            val duration = durationReader(target)
            val verdict = SoundImportPolicy.validate(copied, duration)
            if (verdict is SoundImportPolicy.Result.Rejected) {
                target.delete()
                return ImportResult.Failure(verdict.reason)
            }
            return ImportResult.Success(SoundChoice.Custom(target.name, displayName(context, uri) ?: "Mi sonido"))
        } catch (e: IOException) {
            target.delete()
            return ImportResult.Failure("No se pudo copiar el archivo.")
        } catch (e: SecurityException) {
            target.delete()
            return ImportResult.Failure("Android no dejó leer ese archivo.")
        }
    }

    /** Lector de duración; las pruebas lo sustituyen (MediaMetadataRetriever no decodifica en JVM). */
    @androidx.annotation.VisibleForTesting
    internal var durationReader: (File) -> Long? = ::durationOf

    private fun durationOf(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
            null // RuntimeException si no es audio decodificable
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.take(60) else null
        }
    } catch (_: Exception) {
        null
    }

    /** Borra los archivos que ningún ajuste usa (sonido reemplazado, importación a medias). */
    fun deleteOrphans(context: Context, state: ZenState) {
        val keep = listOf(state.settings.alarmSound, state.settings.completionSound)
            .filterIsInstance<SoundChoice.Custom>().map { it.fileName }.toSet()
        dir(context).listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }
}
