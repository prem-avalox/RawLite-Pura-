package com.example.rawlite

import java.io.File
import java.io.IOException

/** Creates capture directories without ever reusing an existing take. */
internal object CaptureStorage {
    fun createUniqueDirectory(base: File, stem: String): File {
        if ((!base.exists() && !base.mkdirs()) || !base.isDirectory) {
            throw IOException("No se pudo preparar el directorio de captura")
        }
        for (suffix in 0..999) {
            val name = if (suffix == 0) stem else "%s_%02d".format(stem, suffix)
            val candidate = File(base, name)
            if (candidate.mkdir()) return candidate
        }
        throw IOException("No se pudo reservar un nombre único para la toma")
    }
}
