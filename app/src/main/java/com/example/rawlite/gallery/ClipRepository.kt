package com.example.rawlite.gallery

import com.example.rawlite.ClipSummary
import com.example.rawlite.DngSequenceClip
import com.example.rawlite.Pure10Clip
import com.example.rawlite.Raw10Clip
import java.io.File

/** Filesystem boundary for the gallery. Inspection is read-only; deletion remains explicit. */
internal class ClipRepository {
    fun list(baseDirectory: File?): List<ClipSummary> = baseDirectory
        ?.listFiles { file -> file.isDirectory && file.name.startsWith(CLIP_PREFIX) }
        ?.mapNotNull(::inspect)
        ?.sortedByDescending { it.directory.lastModified() }
        .orEmpty()

    fun delete(summary: ClipSummary): Boolean = summary.directory.deleteRecursively()

    private fun inspect(directory: File): ClipSummary? =
        Pure10Clip.inspect(directory)
            ?: Raw10Clip.inspect(directory)
            ?: DngSequenceClip.inspect(directory)

    companion object {
        private const val CLIP_PREFIX = "RawLite_"
    }
}
