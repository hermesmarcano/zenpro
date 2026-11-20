package com.hermesmarcano.zen.data

import java.io.File
import java.io.IOException

/** Lectura y escritura de archivos de estado compartida por los repositorios (estado y entrenamientos). */
internal object AtomicFiles {
    /**
     * Escribe [text] en un archivo temporal y lo renombra sobre [file]: un corte de energía a mitad de guardado
     * nunca deja el archivo a medias. Lanza [IOException] si no se pudo.
     */
    @Throws(IOException::class)
    fun write(file: File, text: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw IOException("No se pudo crear $parent")
        val temp = File(parent, file.name + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            // Algunos sistemas de archivos no sustituyen al renombrar.
            file.delete()
            if (!temp.renameTo(file)) throw IOException("No se pudo guardar ${file.name}")
        }
    }

    /** Lee y decodifica [file]. Si no se puede (JSON roto, valor fuera de rango), lo aparta como `.corrupt` y devuelve `null`. */
    fun <T> readOrQuarantine(file: File, decode: (String) -> T): T? = try {
        decode(file.readText(Charsets.UTF_8))
    } catch (_: Exception) {
        try {
            file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true)
            file.delete()
        } catch (_: Exception) {
            // Mejor arrancar vacío que no arrancar.
        }
        null
    }
}
