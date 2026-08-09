package com.example.rawlite.gallery

import com.example.rawlite.RawFrameInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory

class Raw10CinemaDngConverterTest {
    @Test
    fun `unpack RAW10 expands four pixel groups into little endian unsigned 16`() {
        val packed = byteArrayOf(
            0, 0, 0, 0, 0b1110_0100.toByte(),
            1, 2, 3, 4, 0b0001_1011
        )
        val output = ByteArray(8 * 2)

        Raw10CinemaDngConverter.unpackRaw10ToLittleEndian16(
            packed = packed,
            rowBytes = 10,
            width = 8,
            height = 1,
            output = output
        )

        assertArrayEquals(
            shortArrayOf(0, 1, 2, 3, 7, 10, 13, 16),
            output.toShortArrayLe()
        )
    }

    @Test
    fun `DNG writer emits resolve compatible baseline tags`() {
        val directory = createTempDirectory().toFile()
        val destination = File(directory, "frame_000000.dng")
        val metadata = JSONObject().apply {
            put("camera", "Principal · 6.8 mm")
            put("camera_id", "2")
            put("width", 4)
            put("height", 2)
            put("cfa", "GBRG")
            put("black_level", JSONArray().put(64).put(64).put(64).put(64))
            put("white_level", 1023)
            put("color_matrix_1", identityMatrix())
            put("color_matrix_2", identityMatrix())
            put("reference_illuminant_1", 17)
            put("reference_illuminant_2", 21)
        }
        val mosaic = ByteArray(4 * 2 * 2) { index -> index.toByte() }

        CinemaDngWriter.write(
            destination = destination,
            mosaicLittleEndian16 = mosaic,
            metadata = metadata,
            frame = RawFrameInfo(
                frame = 0,
                timestampNs = 1,
                iso = 100,
                exposureNs = 10_000_000,
                frameDurationNs = 66_666_666,
                focusDiopters = 0f,
                wbRed = 2f,
                wbGreenEven = 1f,
                wbGreenOdd = 1f,
                wbBlue = 1.5f
            )
        )

        val tags = readTiffTags(destination)
        assertEquals(4, tags.getValue(256).firstLong())
        assertEquals(2, tags.getValue(257).firstLong())
        assertEquals(16, tags.getValue(258).firstShort())
        assertEquals(1, tags.getValue(259).firstShort())
        assertEquals(32803, tags.getValue(262).firstShort())
        assertEquals("Google", tags.getValue(271).ascii())
        assertEquals("Pixel 6", tags.getValue(272).ascii())
        assertEquals(listOf(1, 4, 0, 0), tags.getValue(50706).bytes().map { it.toInt() and 0xff })
        assertEquals(1023, tags.getValue(50717).firstLong())
        assertEquals(mosaic.size.toLong(), tags.getValue(279).firstLong())
    }

    @Test
    fun `converter creates sibling CinemaDNG clip from RAW10 package`() {
        val root = createTempDirectory().toFile()
        val source = File(root, "RAW10_20260808_120000").apply { mkdirs() }
        File(source, "clip.json").writeText(raw10Metadata(width = 4, height = 2).toString(2))
        File(source, "frames.csv").writeText(
            "frame,timestamp_ns,iso,exposure_ns,frame_duration_ns,focus_diopters,wb_r,wb_ge,wb_go,wb_b\n" +
                "0,1000,100,10000000,66666666,0.0,2.0,1.0,1.0,1.5\n" +
                "1,2000,100,10000000,66666666,0.0,2.0,1.0,1.0,1.5\n"
        )
        File(source, "clip.state").writeText(
            "version=1\nstatus=closed\ncompleted_frames=2\nraw_bytes=20\nwidth=4\nheight=2\nrow_bytes=5\n"
        )
        File(source, "clip.raw10").writeBytes(
            byteArrayOf(
                0, 0, 0, 0, 0b1110_0100.toByte(),
                1, 2, 3, 4, 0b0001_1011,
                0, 0, 0, 0, 0b1110_0100.toByte(),
                1, 2, 3, 4, 0b0001_1011
            )
        )

        val result = Raw10CinemaDngConverter.convert(source) {}

        assertEquals(null, result.error)
        assertEquals(2, result.convertedFrames)
        val output = requireNotNull(result.output)
        assertEquals("CINEMADNG_20260808_120000", output.name)
        assertTrue(File(output, "dng/frame_000000.dng").isFile)
        assertTrue(File(output, "dng/frame_000001.dng").isFile)
        val clip = JSONObject(File(output, "clip.json").readText())
        assertEquals("rawlite-dng", clip.getString("format"))
        assertEquals("RAW10_20260808_120000", clip.getString("source_directory"))
    }

    @Test
    fun `DNG writer preserves Pixel 6 DNG color profile tags`() {
        val directory = createTempDirectory().toFile()
        val destination = File(directory, "frame_000000.dng")
        val metadata = raw10Metadata(width = 4, height = 2).apply {
            put("unique_camera_model", "Google Pixel 6")
            put("baseline_exposure", 0.59)
            put("color_matrix_1", pixel6ColorMatrix1())
            put("color_matrix_2", pixel6ColorMatrix2())
        }
        val mosaic = ByteArray(4 * 2 * 2) { index -> index.toByte() }

        CinemaDngWriter.write(
            destination = destination,
            mosaicLittleEndian16 = mosaic,
            metadata = metadata,
            frame = RawFrameInfo(
                frame = 0,
                timestampNs = 1,
                iso = 100,
                exposureNs = 10_000_000,
                frameDurationNs = 66_666_666,
                focusDiopters = 0f,
                wbRed = 2f,
                wbGreenEven = 1f,
                wbGreenOdd = 1f,
                wbBlue = 1.5f
            )
        )

        val tags = readTiffTags(destination)
        assertEquals("Google Pixel 6", tags.getValue(50708).ascii())
        assertEquals(0.59, tags.getValue(50730).firstSignedRational(), 0.0001)
        assertEquals(
            listOf(12453 to 10000, -6954 to 10000, 134 to 10000),
            tags.getValue(50721).signedRationals().take(3)
        )
        assertFalse(tags.containsKey(50723))
        assertFalse(tags.containsKey(50724))
        assertFalse(tags.containsKey(50964))
        assertFalse(tags.containsKey(50965))
    }

    @Test
    fun `Pixel 6 main DNG profile replaces incomplete camera color metadata`() {
        val metadata = raw10Metadata(width = 2032, height = 1536).apply {
            put("calibration_matrix_1", identityMatrix())
            put("forward_matrix_1", identityMatrix())
        }

        Pixel6MainDngColorProfile.apply(metadata)

        assertEquals("Google Pixel 6 main DNG", metadata.getString("color_profile"))
        assertEquals("Google Pixel 6", metadata.getString("unique_camera_model"))
        assertEquals(0.59, metadata.getDouble("baseline_exposure"), 0.0001)
        assertEquals(12453, metadata.getJSONArray("color_matrix_1").getJSONArray(0).getInt(0))
        assertFalse(metadata.has("calibration_matrix_1"))
        assertFalse(metadata.has("forward_matrix_1"))
    }

    @Test
    fun `Pixel 6 main DNG profile covers UHD derived metadata`() {
        val metadata = raw10Metadata(width = 3840, height = 2160)

        Pixel6MainDngColorProfile.apply(metadata)

        assertEquals("Google Pixel 6 main DNG", metadata.getString("color_profile"))
        assertEquals("Google Pixel 6", metadata.getString("unique_camera_model"))
        assertEquals(10537, metadata.getJSONArray("color_matrix_2").getJSONArray(0).getInt(0))
    }

    private fun identityMatrix(): JSONArray = JSONArray().apply {
        listOf(
            1 to 1, 0 to 1, 0 to 1,
            0 to 1, 1 to 1, 0 to 1,
            0 to 1, 0 to 1, 1 to 1
        ).forEach { (numerator, denominator) ->
            put(JSONArray().put(numerator).put(denominator))
        }
    }

    private fun raw10Metadata(width: Int, height: Int): JSONObject = JSONObject().apply {
        put("format", "rawlite-raw10")
        put("camera", "Principal · 6.8 mm")
        put("camera_id", "2")
        put("width", width)
        put("height", height)
        put("frame_rate", 15)
        put("row_bytes", (width * 10 + 7) / 8)
        put("cfa", "GBRG")
        put("black_level", JSONArray().put(64).put(64).put(64).put(64))
        put("white_level", 1023)
        put("color_matrix_1", identityMatrix())
        put("color_matrix_2", identityMatrix())
        put("reference_illuminant_1", 17)
        put("reference_illuminant_2", 21)
    }

    private fun pixel6ColorMatrix1(): JSONArray = JSONArray().apply {
        listOf(
            12453 to 10000, -6954 to 10000, 134 to 10000,
            -2949 to 10000, 11718 to 10000, 1364 to 10000,
            71 to 10000, 1626 to 10000, 6290 to 10000
        ).forEach { (numerator, denominator) ->
            put(JSONArray().put(numerator).put(denominator))
        }
    }

    private fun pixel6ColorMatrix2(): JSONArray = JSONArray().apply {
        listOf(
            10537 to 10000, -4544 to 10000, -906 to 10000,
            -3178 to 10000, 11784 to 10000, 1552 to 10000,
            -109 to 10000, 1865 to 10000, 5324 to 10000
        ).forEach { (numerator, denominator) ->
            put(JSONArray().put(numerator).put(denominator))
        }
    }

    private data class TiffTag(val type: Int, val count: Int, val raw: ByteArray, val file: ByteArray) {
        fun firstShort(): Int = ByteBuffer.wrap(valueBytes(2)).order(ByteOrder.LITTLE_ENDIAN)
            .short.toInt() and 0xffff

        fun firstLong(): Long = ByteBuffer.wrap(valueBytes(4)).order(ByteOrder.LITTLE_ENDIAN)
            .int.toLong() and 0xffff_ffffL

        fun ascii(): String = valueBytes(1).toString(Charsets.US_ASCII).trimEnd('\u0000')

        fun bytes(): ByteArray = valueBytes(1)

        fun firstSignedRational(): Double = signedRationals().first().let { (numerator, denominator) ->
            numerator.toDouble() / denominator.toDouble()
        }

        fun signedRationals(): List<Pair<Int, Int>> {
            val bytes = valueBytes(8)
            return (0 until count).map { index ->
                val offset = index * 8
                val buffer = ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN)
                buffer.int to buffer.int
            }
        }

        private fun valueBytes(typeSize: Int): ByteArray {
            val size = count * typeSize
            if (size <= 4) return raw.copyOfRange(0, size)
            val offset = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).int
            return file.copyOfRange(offset, offset + size)
        }
    }

    private fun readTiffTags(file: File): Map<Int, TiffTag> {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals('I'.code.toByte(), bytes[0])
        assertEquals('I'.code.toByte(), bytes[1])
        assertEquals(42, buffer.getShort(2).toInt())
        val ifdOffset = buffer.getInt(4)
        val count = buffer.getShort(ifdOffset).toInt() and 0xffff
        val tags = linkedMapOf<Int, TiffTag>()
        var offset = ifdOffset + 2
        repeat(count) {
            val tag = buffer.getShort(offset).toInt() and 0xffff
            val type = buffer.getShort(offset + 2).toInt() and 0xffff
            val valueCount = buffer.getInt(offset + 4)
            val raw = bytes.copyOfRange(offset + 8, offset + 12)
            tags[tag] = TiffTag(type, valueCount, raw, bytes)
            offset += 12
        }
        return tags
    }

    private fun ByteArray.toShortArrayLe(): ShortArray {
        val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(size / 2) { buffer.short }
    }
}
