package com.example.rawlite.gallery

import com.example.rawlite.ClipSummary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryExportEstimatorTest {
    @Test
    fun estimate_countsFormatsAndTemporaryDuplication() {
        val raw = clip("RAW10", byteCount = 10_000, width = 4, height = 2, frames = 3)
        val pure = clip("PURE 10", byteCount = 20_000)
        val dng = clip("CinemaDNG", byteCount = 30_000)

        val estimate = GalleryExportEstimator.estimate(listOf(raw, pure, dng))

        assertEquals(3, estimate.clipCount)
        assertEquals(1, estimate.raw10Count)
        assertEquals(1, estimate.pure10Count)
        assertEquals(1, estimate.cinemaDngCount)
        assertEquals(60_000, estimate.sourceBytes)
        assertEquals(60_000, estimate.extraOutputBytes)
        assertEquals(120_000, estimate.temporaryWorkingBytes)
    }

    private fun clip(
        format: String,
        byteCount: Long,
        width: Int = 1,
        height: Int = 1,
        frames: Int = 1
    ): ClipSummary = ClipSummary(
        directory = File(format),
        camera = "Camera",
        width = width,
        height = height,
        fps = 24,
        frameCount = frames,
        timelineFrameCount = frames,
        byteCount = byteCount,
        finalized = true,
        audioDescription = null,
        captureFormat = format
    )
}
