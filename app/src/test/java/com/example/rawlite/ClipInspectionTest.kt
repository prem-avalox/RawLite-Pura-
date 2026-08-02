package com.example.rawlite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClipInspectionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun raw10Inspection_usesCheckpointAndPreservesTimelineGap() {
        val directory = temporaryFolder.newFolder("RawLite_Test_RAW10")
        directory.resolve("clip.json").writeText(
            """{"format":"rawlite-raw10","width":4,"height":2,"row_bytes":5,"frame_rate":30}"""
        )
        directory.resolve("clip.raw10").writeBytes(ByteArray(30))
        directory.resolve("clip.state").writeText(
            "version=1\nstatus=closed\ncompleted_frames=2\nraw_bytes=20\n"
        )
        directory.resolve("frames.csv").writeText(
            frameHeader() +
                "0,1000000000,100,10000000,33333333,0.0,2.0,1.0,1.0,1.8\n" +
                "2,1066666666,100,10000000,33333333,0.0,2.0,1.0,1.0,1.8\n"
        )

        val summary = requireNotNull(Raw10Clip.inspect(directory))

        assertEquals(2, summary.frameCount)
        assertEquals(3, summary.timelineFrameCount)
        assertTrue(summary.finalized)
        assertEquals(30, summary.fps)
    }

    @Test
    fun raw10Inspection_ignoresIncompleteRawTail() {
        val directory = temporaryFolder.newFolder("RawLite_Test_Truncated")
        directory.resolve("clip.json").writeText(
            """{"format":"rawlite-raw10","width":4,"height":2,"row_bytes":5,"frame_rate":24}"""
        )
        directory.resolve("clip.raw10").writeBytes(ByteArray(15))
        directory.resolve("clip.state").writeText(
            "version=1\nstatus=recording\ncompleted_frames=1\nraw_bytes=10\n"
        )
        directory.resolve("frames.csv").writeText(
            frameHeader() +
                "0,1000000000,100,10000000,41666666,0.0,2.0,1.0,1.0,1.8\n"
        )

        val summary = requireNotNull(Raw10Clip.inspect(directory))

        assertEquals(1, summary.frameCount)
        assertEquals(1, summary.timelineFrameCount)
        assertFalse(summary.finalized)
    }

    @Test
    fun raw10Inspection_rejectsUnknownFormat() {
        val directory = temporaryFolder.newFolder("RawLite_Test_Unknown")
        directory.resolve("clip.json").writeText(
            """{"format":"unknown","width":4,"height":2,"row_bytes":5}"""
        )
        directory.resolve("clip.raw10").writeBytes(ByteArray(10))

        assertNull(Raw10Clip.inspect(directory))
    }

    @Test
    fun pure10Inspection_usesRecordedFrameCountWithoutDecoderDependency() {
        val directory = temporaryFolder.newFolder("RawLite_Test_Pure10")
        directory.resolve("clip.json").writeText(
            """{
                "format":"rawlite-pure10",
                "video_file":"pure10.mp4",
                "width":2032,
                "height":1536,
                "frame_rate":24,
                "encoded_frames":48,
                "finalized":true
            }""".trimIndent()
        )
        directory.resolve("pure10.mp4").writeBytes(byteArrayOf(1))

        val summary = requireNotNull(Pure10Clip.inspect(directory))

        assertEquals("PURE 10", summary.captureFormat)
        assertEquals(48, summary.frameCount)
        assertEquals(48, summary.timelineFrameCount)
        assertTrue(summary.finalized)
    }

    private fun frameHeader(): String =
        "frame,timestamp_ns,iso,exposure_ns,frame_duration_ns,focus_diopters," +
            "wb_r,wb_ge,wb_go,wb_b\n"
}
