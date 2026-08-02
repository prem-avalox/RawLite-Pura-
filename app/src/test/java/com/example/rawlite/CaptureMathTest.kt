package com.example.rawlite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptureMathTest {
    @Test
    fun rotationDegrees_backCamera_coversPhysicalOrientations() {
        assertEquals(90, CaptureMath.rotationDegrees(90, 0, frontFacing = false))
        assertEquals(180, CaptureMath.rotationDegrees(90, 90, frontFacing = false))
        assertEquals(0, CaptureMath.rotationDegrees(90, 270, frontFacing = false))
    }

    @Test
    fun rotationDegrees_frontCamera_reversesDisplayCompensation() {
        assertEquals(90, CaptureMath.rotationDegrees(90, 0, frontFacing = true))
        assertEquals(0, CaptureMath.rotationDegrees(90, 90, frontFacing = true))
        assertEquals(180, CaptureMath.rotationDegrees(90, 270, frontFacing = true))
    }

    @Test
    fun rotationDegrees_normalizesInputs() {
        assertEquals(0, CaptureMath.rotationDegrees(450, -90, frontFacing = false))
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
}
