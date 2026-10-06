package com.example.rawlite

import com.example.rawlite.core.model.CaptureOutput
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/** Creates capture directories without ever reusing an existing take. */
internal object CaptureStorage {
    private val legacyPrefixes = listOf("RawLite_")
    private val capturePrefixes = mapOf(
        CaptureOutput.RAW10 to "RAW10",
        CaptureOutput.PURE10 to "PURE10",
        CaptureOutput.CINEMA_DNG to "CINEMADNG"
    )

    fun captureStem(output: CaptureOutput, timestamp: String): String {
        val prefix = capturePrefixes.getValue(output)
        return "${prefix}_$timestamp"
    }

    fun displayName(directoryName: String): String {
        val prefixes = legacyPrefixes + capturePrefixes.values.map { "${it}_" }
        return prefixes.firstOrNull { directoryName.startsWith(it) }
            ?.let { directoryName.removePrefix(it) }
            ?: directoryName
    }

    /** Stable capture time derived from the take name, unaffected by thumbnail/cache writes. */
    fun captureTimeMillis(directory: File): Long {
        val timestamp = displayName(directory.name).take(15)
        if (!timestamp.matches(Regex("\\d{8}_\\d{6}"))) return directory.lastModified()
        return runCatching {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
                isLenient = false
            }.parse(timestamp)?.time
        }.getOrNull() ?: directory.lastModified()
    }

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
