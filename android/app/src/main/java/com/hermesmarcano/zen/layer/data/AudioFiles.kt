package com.hermesmarcano.zen.layer.data

import com.hermesmarcano.zen.layer.core.PcmSegment
import com.hermesmarcano.zen.layer.core.WavEncoder
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Los tramos de voz grabados, como WAV en un directorio privado de la app. Todo acceso es por NOMBRE y el nombre
 * se valida: el nombre viaja dentro de la cola de envío y nunca debe poder apuntar fuera del directorio.
 */
class AudioFiles(private val dir: File) {
    /** Guarda el tramo (escritura atómica) y devuelve el nombre del archivo. */
    @Throws(IOException::class)
    fun save(segment: PcmSegment): String {
        if (!dir.exists() && !dir.mkdirs()) throw IOException("No se pudo crear $dir")
        val name = "seg-${UUID.randomUUID()}.wav"
        val temp = File(dir, "$name.tmp")
        temp.writeBytes(WavEncoder.encode(segment.samples, segment.sampleRate))
        if (!temp.renameTo(File(dir, name))) {
            temp.delete()
            throw IOException("No se pudo guardar el audio")
        }
        return name
    }

    /** El archivo, o `null` si el nombre no es uno de los nuestros o no existe. */
    fun file(name: String): File? {
        if (!VALID_NAME.matches(name)) return null
        return File(dir, name).takeIf { it.isFile }
    }

    fun delete(name: String) {
        if (VALID_NAME.matches(name)) File(dir, name).delete()
    }

    fun totalBytes(): Long = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /** Borra los archivos que ya no están en la cola (y los temporales rotos), salvo los muy recientes. */
    fun deleteOrphans(keep: Set<String>, nowMs: Long, minAgeMs: Long = 60_000L): Int {
        var removed = 0
        for (f in dir.listFiles().orEmpty()) {
            if (!f.isFile || f.name in keep || nowMs - f.lastModified() < minAgeMs) continue
            if (f.delete()) removed++
        }
        return removed
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    companion object {
        private val VALID_NAME = Regex("seg-[0-9a-f-]{36}\\.wav")
    }
}
