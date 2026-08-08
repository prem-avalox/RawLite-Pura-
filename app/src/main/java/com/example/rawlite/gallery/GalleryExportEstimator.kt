package com.example.rawlite.gallery

import com.example.rawlite.ClipSummary

internal data class GalleryExportEstimate(
    val clipCount: Int,
    val raw10Count: Int,
    val pure10Count: Int,
    val cinemaDngCount: Int,
    val sourceBytes: Long,
    val extraOutputBytes: Long
) {
    val temporaryWorkingBytes: Long = sourceBytes + extraOutputBytes
}

internal object GalleryExportEstimator {
    fun estimate(clips: List<ClipSummary>): GalleryExportEstimate {
        var raw10 = 0
        var pure10 = 0
        var cinemaDng = 0
        var sourceBytes = 0L
        var outputBytes = 0L
        clips.forEach { clip ->
            sourceBytes += clip.byteCount
            outputBytes += estimateOutputBytes(clip)
            when (clip.captureFormat) {
                "RAW10" -> raw10++
                "PURE 10" -> pure10++
                "CinemaDNG" -> cinemaDng++
            }
        }
        return GalleryExportEstimate(
            clipCount = clips.size,
            raw10Count = raw10,
            pure10Count = pure10,
            cinemaDngCount = cinemaDng,
            sourceBytes = sourceBytes,
            extraOutputBytes = outputBytes
        )
    }

    private fun estimateOutputBytes(clip: ClipSummary): Long = when (clip.captureFormat) {
        "RAW10" -> {
            val dngPayload = clip.width.toLong() * clip.height.toLong() * 2L *
                clip.frameCount.coerceAtLeast(1)
            maxOf(dngPayload, clip.byteCount)
        }
        "PURE 10" -> clip.byteCount
        "CinemaDNG" -> clip.byteCount
        else -> clip.byteCount
    }.coerceAtLeast(0L)
}
