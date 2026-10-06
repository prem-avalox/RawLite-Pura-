package com.example.rawlite

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackDiagnosticsTest {
    @Test
    fun sequenceProgress_exposesStoredAndTimelineFrames() {
        val status = PlaybackFrameStatus(storedFrames = 64, timelineFrames = 91, lostFrames = 27)

        assertEquals(
            "FOT. 20/64  TL 31/91",
            PlaybackDiagnostics.progressLabel(status, storedFrame = 20, timelineFrame = 31)
        )
        assertEquals("PERD. 27", PlaybackDiagnostics.lossLabel(status))
    }

    @Test
    fun pure10Progress_isHonestAboutEncodedFrames() {
        val status = PlaybackFrameStatus(storedFrames = 64, timelineFrames = 91, lostFrames = 27)

        assertEquals(
            "TL 31/91  ENC. 64",
            PlaybackDiagnostics.progressLabel(status, timelineFrame = 31)
        )
    }

    @Test
    fun pure10Timeline_usesPlaybackPosition() {
        assertEquals(31, PlaybackDiagnostics.pure10TimelineFrame(1_000, 30, 91))
        assertEquals(91, PlaybackDiagnostics.pure10TimelineFrame(9_000, 30, 91))
    }
}
