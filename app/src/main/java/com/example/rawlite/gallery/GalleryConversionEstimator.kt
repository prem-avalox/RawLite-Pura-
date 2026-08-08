package com.example.rawlite.gallery

import com.example.rawlite.ClipSummary

internal data class GalleryConversionEstimate(
    val selectedClipCount: Int,
    val raw10ClipCount: Int,
    val ignoredClipCount: Int,
    val raw10SourceBytes: Long,
    val estimatedCinemaDngBytes: Long
) {
    val temporaryWorkingBytes: Long = raw10SourceBytes + estimatedCinemaDngBytes
}

internal object GalleryConversionEstimator {
    fun estimateRaw10ToCinemaDng(clips: List<ClipSummary>): GalleryConversionEstimate {
        var raw10 = 0
        var sourceBytes = 0L
        var outputBytes = 0L
        clips.forEach { clip ->
            if (clip.captureFormat == "RAW10") {
                raw10++
                sourceBytes += clip.byteCount
                outputBytes += estimateCinemaDngBytes(clip)
            }
        }
        return GalleryConversionEstimate(
            selectedClipCount = clips.size,
            raw10ClipCount = raw10,
            ignoredClipCount = clips.size - raw10,
            raw10SourceBytes = sourceBytes,
            estimatedCinemaDngBytes = outputBytes
        )
    }

    private fun estimateCinemaDngBytes(clip: ClipSummary): Long {
        val dngPayload = clip.width.toLong() * clip.height.toLong() * 2L *
            clip.frameCount.coerceAtLeast(1)
        return maxOf(dngPayload, clip.byteCount).coerceAtLeast(0L)
    }
}
