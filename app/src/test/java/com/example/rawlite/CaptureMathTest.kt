package com.example.rawlite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptureMathTest {
    @Test
    fun rotationDegrees_backCamera_coversPhysicalOrientations() {
        assertEquals(90, CaptureMath.rotationDegrees(90, 0, frontFacing = false))
        assertEquals(0, CaptureMath.rotationDegrees(90, 90, frontFacing = false))
        assertEquals(180, CaptureMath.rotationDegrees(90, 270, frontFacing = false))
    }

    @Test
    fun rotationDegrees_frontCamera_reversesDisplayCompensation() {
        assertEquals(90, CaptureMath.rotationDegrees(90, 0, frontFacing = true))
        assertEquals(180, CaptureMath.rotationDegrees(90, 90, frontFacing = true))
        assertEquals(0, CaptureMath.rotationDegrees(90, 270, frontFacing = true))
    }

    @Test
    fun rotationDegrees_normalizesInputs() {
        assertEquals(180, CaptureMath.rotationDegrees(450, -90, frontFacing = false))
    }

    @Test
    fun timelineFrame_preservesSensorCadenceGaps() {
        val first = 1_000_000_000L
        val frame0 = CaptureMath.timelineFrame(first, first, -1, 30)
        val frame1 = CaptureMath.timelineFrame(first + 33_333_333L, first, frame0.frame, 30)
        val frame3 = CaptureMath.timelineFrame(first + 100_000_000L, first, frame1.frame, 30)

        assertEquals(CaptureMath.TimelineResult(0, 0), frame0)
        assertEquals(CaptureMath.TimelineResult(1, 0), frame1)
        assertEquals(CaptureMath.TimelineResult(3, 1), frame3)
    }

    @Test
    fun timelineFrame_neverMovesBackward() {
        val result = CaptureMath.timelineFrame(
            timestampNs = 1_010_000_000L,
            firstTimestampNs = 1_000_000_000L,
            lastTimelineFrame = 8,
            fps = 30
        )
        assertEquals(CaptureMath.TimelineResult(9, 0), result)
    }

    @Test
    fun timelineFrame_fallsBackWhenClockIsUnavailable() {
        assertEquals(
            CaptureMath.TimelineResult(5, 0),
            CaptureMath.timelineFrame(0L, 0L, 4, 24)
        )
    }

    @Test
    fun timelineFrame_rejectsInvalidFps() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptureMath.timelineFrame(1L, 1L, -1, 0)
        }
    }

    @Test
    fun indexForTimeline_returnsLatestAvailableFrameBeforeGap() {
        val frames = intArrayOf(0, 1, 4, 5)
        assertEquals(0, CaptureMath.indexForTimeline(frames.size, -1) { frames[it] })
        assertEquals(1, CaptureMath.indexForTimeline(frames.size, 3) { frames[it] })
        assertEquals(2, CaptureMath.indexForTimeline(frames.size, 4) { frames[it] })
        assertEquals(3, CaptureMath.indexForTimeline(frames.size, 100) { frames[it] })
    }

    @Test
    fun sequencePosition_usesTimelineRatherThanSavedFrameIndex() {
        assertEquals(133, CaptureMath.sequencePositionMs(4, 0, 30))
    }

    @Test
    fun audioTarget_appliesSignedOffsetAndClampsToMedia() {
        assertEquals(125, CaptureMath.audioTargetMs(100, 25, 1_000))
        assertEquals(0, CaptureMath.audioTargetMs(10, -25, 1_000))
        assertEquals(1_000, CaptureMath.audioTargetMs(990, 25, 1_000))
    }

    @Test
    fun audioTarget_compensatesAStableOutputLeadWithoutChangingMetadata() {
        assertEquals(80, CaptureMath.audioTargetMs(0, 125, 1_000, 45))
        assertEquals(1_000, CaptureMath.audioTargetMs(990, 125, 1_000, 45))
    }

    @Test
    fun calculateCropRegion_16x9_on_4x3_sensor_cropsVerticallyWithEvenAlignment() {
        val result = CaptureMath.calculateCropRegion(
            activeWidth = 4080,
            activeHeight = 3072,
            targetWidth = 1920,
            targetHeight = 1080
        )
        assertEquals(0, result.left)
        assertEquals(388, result.top)
        assertEquals(4080, result.width)
        assertEquals(2294, result.height)
        assertEquals(4080, result.right)
        assertEquals(2682, result.bottom)
        assertEquals(0, result.left % 2)
        assertEquals(0, result.top % 2)
        assertEquals(0, result.width % 2)
        assertEquals(0, result.height % 2)
    }

    @Test
    fun calculateCropRegion_4x3_on_4x3_sensor_cropsHorizontallyWithEvenAlignment() {
        val result = CaptureMath.calculateCropRegion(
            activeWidth = 4080,
            activeHeight = 3072,
            targetWidth = 2032,
            targetHeight = 1536
        )
        assertEquals(8, result.left)
        assertEquals(0, result.top)
        assertEquals(4064, result.width)
        assertEquals(3072, result.height)
        assertEquals(4072, result.right)
        assertEquals(3072, result.bottom)
        assertEquals(0, result.left % 2)
        assertEquals(0, result.top % 2)
        assertEquals(0, result.width % 2)
        assertEquals(0, result.height % 2)
    }

    @Test
    fun calculateCropRegion_full_sensor_preservesActiveDimensions() {
        val result = CaptureMath.calculateCropRegion(
            activeWidth = 4080,
            activeHeight = 3072,
            targetWidth = 4080,
            targetHeight = 3072
        )
        assertEquals(0, result.left)
        assertEquals(0, result.top)
        assertEquals(4080, result.width)
        assertEquals(3072, result.height)
    }

    @Test
    fun calculateCropRegion_active_offset_shiftsCropRegion() {
        val result = CaptureMath.calculateCropRegion(
            activeWidth = 4080,
            activeHeight = 3072,
            targetWidth = 1920,
            targetHeight = 1080,
            activeLeft = 16,
            activeTop = 16
        )
        assertEquals(16, result.left)
        assertEquals(404, result.top)
        assertEquals(4080, result.width)
        assertEquals(2294, result.height)
    }

    @Test
    fun fitWithin_landscape_20x9_with_16x9_createsHorizontalPillarbox() {
        val result = CaptureMath.fitWithin(
            containerWidth = 2400f,
            containerHeight = 1080f,
            contentWidth = 1920f,
            contentHeight = 1080f
        )
        assertEquals(1.0f, result.scale, 0.001f)
        assertEquals(240f, result.left, 0.001f)
        assertEquals(0f, result.top, 0.001f)
        assertEquals(1920f, result.width, 0.001f)
        assertEquals(1080f, result.height, 0.001f)
        assertEquals(2160f, result.right, 0.001f)
        assertEquals(1080f, result.bottom, 0.001f)
    }

    @Test
    fun fitWithin_landscape_20x9_with_4x3_createsHorizontalPillarbox() {
        val result = CaptureMath.fitWithin(
            containerWidth = 2400f,
            containerHeight = 1080f,
            contentWidth = 1280f,
            contentHeight = 960f
        )
        assertEquals(1.125f, result.scale, 0.001f)
        assertEquals(480f, result.left, 0.001f)
        assertEquals(0f, result.top, 0.001f)
        assertEquals(1440f, result.width, 0.001f)
        assertEquals(1080f, result.height, 0.001f)
    }

    @Test
    fun fitWithin_portrait_20x9_with_16x9_createsVerticalLetterbox() {
        val result = CaptureMath.fitWithin(
            containerWidth = 1080f,
            containerHeight = 2400f,
            contentWidth = 1080f,
            contentHeight = 1920f
        )
        assertEquals(1.0f, result.scale, 0.001f)
        assertEquals(0f, result.left, 0.001f)
        assertEquals(240f, result.top, 0.001f)
        assertEquals(1080f, result.width, 0.001f)
        assertEquals(1920f, result.height, 0.001f)
    }

    @Test
    fun fitWithin_handlesZeroOrNegativeDimensions() {
        val result = CaptureMath.fitWithin(0f, 0f, 1920f, 1080f)
        assertEquals(0f, result.left, 0.001f)
        assertEquals(0f, result.top, 0.001f)
        assertEquals(0f, result.width, 0.001f)
        assertEquals(0f, result.height, 0.001f)
    }
}
