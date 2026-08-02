package com.example.rawlite.capture.audio

import com.example.rawlite.AudioCaptureSummary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvSyncMetadataFactoryTest {
    @Test
    fun `uses boottime offset and preserves timeline gaps`() {
        val audio = audioSummary(sampleZeroNs = 900_000_000L, frames = 48_000L)
        val metadata = AvSyncMetadataFactory.create(
            AvSyncContext(
                cameraUsesBoottime = true,
                cameraFirstTimestampNs = 1_000_000_000L,
                cameraLastTimestampNs = 1_900_000_000L,
                lastTimelineFrame = 29,
                savedFrames = 27,
                cadenceGaps = 3
            ),
            audio
        )

        assertEquals("CLOCK_BOOTTIME", metadata.getString("video_clock"))
        assertEquals(100_000_000L, metadata.getLong("camera_start_minus_audio_start_ns"))
        assertEquals(1_900_000_000L, metadata.getLong("audio_end_timestamp_ns"))
        assertEquals(0L, metadata.getLong("audio_end_minus_camera_last_ns"))
        assertEquals(30, metadata.getInt("timeline_frames"))
        assertEquals(3, metadata.getInt("cadence_gaps"))
    }

    @Test
    fun `does not invent synchronization for an unknown camera clock`() {
        val metadata = AvSyncMetadataFactory.create(
            AvSyncContext(false, 1_000L, 2_000L, 0, 1, 0),
            audioSummary(sampleZeroNs = 500L, frames = 0L)
        )

        assertEquals("CAMERA_UNKNOWN", metadata.getString("video_clock"))
        assertTrue(metadata.isNull("camera_start_minus_audio_start_ns"))
    }

    private fun audioSummary(sampleZeroNs: Long, frames: Long) = AudioCaptureSummary(
        file = File("audio.wav"),
        sampleRate = 48_000,
        channels = 2,
        bitsPerSample = 24,
        source = "UNPROCESSED",
        frames = frames,
        dataBytes = frames * 6,
        sampleZeroBoottimeNs = sampleZeroNs,
        stopRequestedBoottimeNs = 2_000_000_000L,
        stoppedBoottimeNs = 2_010_000_000L,
        finalized = true,
        error = null
    )
}
