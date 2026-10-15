package com.example.rawlite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        assertEquals(1, summary.lostFrameCount)
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
        assertEquals(0, summary.lostFrameCount)
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
    fun raw10cInspection_usesOnlyCompleteRecoverableChunks() {
        val directory = temporaryFolder.newFolder("RawLite_Test_RAW10C")
        directory.resolve("clip.json").writeText(
            """{"format":"rawlite-raw10","width":4,"height":2,"row_bytes":5,"frame_rate":24}"""
        )
        val container = ByteArrayOutputStream().apply {
            write(containerHeader(width = 4, height = 2, rowBytes = 5))
            write(frameChunk(frame = 0, payload = ByteArray(10) { it.toByte() }))
            write(frameChunk(frame = 1, payload = ByteArray(10) { (it + 10).toByte() }))
            write(frameChunk(frame = 2, payload = ByteArray(10)).copyOf(17))
        }.toByteArray()
        directory.resolve(Raw10FrameStore.COMPRESSED_FILE).writeBytes(container)
        directory.resolve("clip.state").writeText(
            "version=2\nstatus=recording\ncompleted_frames=3\nraw_bytes=${container.size}\n"
        )
        directory.resolve("frames.csv").writeText(
            frameHeader() +
                "0,1000000000,100,10000000,41666666,0.0,2.0,1.0,1.0,1.8\n" +
                "1,1041666666,100,10000000,41666666,0.0,2.0,1.0,1.0,1.8\n" +
                "2,1083333332,100,10000000,41666666,0.0,2.0,1.0,1.0,1.8\n"
        )

        val summary = requireNotNull(Raw10Clip.inspect(directory))

        assertEquals(2, summary.frameCount)
        assertEquals(2, summary.timelineFrameCount)
        assertFalse(summary.finalized)
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

        assertEquals("10-BIT", summary.captureFormat)
        assertEquals(48, summary.frameCount)
        assertEquals(48, summary.timelineFrameCount)
        assertTrue(summary.finalized)
    }

    @Test
    fun eightBitInspection_usesRecordedFrameCountWithoutDecoderDependency() {
        val directory = temporaryFolder.newFolder("RawLite_Test_8Bit")
        directory.resolve("clip.json").writeText(
            """{
                "format":"rawlite-8bit",
                "video_file":"eight_bit.mp4",
                "width":1920,
                "height":1080,
                "frame_rate":30,
                "encoded_frames":60,
                "finalized":true
            }""".trimIndent()
        )
        directory.resolve("eight_bit.mp4").writeBytes(byteArrayOf(1))

        val summary = requireNotNull(Pure10Clip.inspect(directory))

        assertEquals("8-BIT", summary.captureFormat)
        assertEquals(60, summary.frameCount)
        assertEquals(60, summary.timelineFrameCount)
        assertTrue(summary.finalized)
    }

    private fun frameHeader(): String =
        "frame,timestamp_ns,iso,exposure_ns,frame_duration_ns,focus_diopters," +
            "wb_r,wb_ge,wb_go,wb_b\n"

    private fun containerHeader(width: Int, height: Int, rowBytes: Int): ByteArray =
        ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RLR10C01".toByteArray(Charsets.US_ASCII))
            putInt(64)
            putInt(1)
            putInt(width)
            putInt(height)
            putInt(rowBytes)
            putInt(rowBytes * height)
            putInt(1)
            putInt(1)
        }.array()

    private fun frameChunk(frame: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(32 + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RLF1".toByteArray(Charsets.US_ASCII))
            putInt(32)
            putInt(frame)
            putInt(0)
            putInt(payload.size)
            putInt(payload.size)
            putInt(0)
            putInt(0)
            put(payload)
        }.array()
}
