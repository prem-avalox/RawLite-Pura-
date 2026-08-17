package com.example.rawlite

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameIntegrityTest {
    @Test
    fun rawSequence_countsMissingTimelineFrames() {
        assertEquals(22, FrameIntegrity.lostFrameCount(64, 86))
    }

    @Test
    fun convertedCinemaDng_keepsRepeatedFramesVisibleAsCaptureLosses() {
        assertEquals(
            22,
            FrameIntegrity.lostFrameCount(
                storedFrames = 86,
                timelineFrames = 86,
                repeatedFrames = 22
            )
        )
    }

    @Test
    fun intactSequence_reportsZero() {
        assertEquals(0, FrameIntegrity.lostFrameCount(86, 86))
    }
}
