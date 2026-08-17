package com.example.rawlite.gallery

import com.example.rawlite.RawFrameInfo
import com.example.rawlite.LensShadingSample
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
    fun `DNG writer embeds Camera2 shading as standard GainMap opcodes`() {
        val directory = createTempDirectory().toFile()
        val destination = File(directory, "frame_000000.dng")
        val metadata = raw10Metadata(width = 4, height = 2)
        val shading = LensShadingSample(
            rows = 2,
            columns = 2,
            gains = floatArrayOf(
                1.1f, 1.2f, 1.3f, 1.4f,
                1.5f, 1.6f, 1.7f, 1.8f,
                1.9f, 2.0f, 2.1f, 2.2f,
                2.3f, 2.4f, 2.5f, 2.6f
            )
        )

        CinemaDngWriter.write(
            destination,
            ByteArray(16),
            metadata,
            RawFrameInfo(0, 1, 100, 10_000_000, 41_666_666, 0f, 2f, 1f, 1f, 1.5f),
            shading
        )

        val tags = readTiffTags(destination)
        assertEquals(listOf(1, 3, 0, 0), tags.getValue(50707).bytes().map { it.toInt() and 0xff })
        val opcodeList = tags.getValue(51009).bytes()
        val bigEndian = ByteBuffer.wrap(opcodeList).order(ByteOrder.BIG_ENDIAN)
        assertEquals(4, bigEndian.int)
        assertEquals(9, bigEndian.int)
        assertEquals(0x01030000, bigEndian.int)
        assertEquals(0, bigEndian.int)
        assertEquals(76 + 4 * Float.SIZE_BYTES, bigEndian.int)
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
    fun `converter preserves 24 bit stereo audio byte alignment across copy chunks`() {
        val root = createTempDirectory().toFile()
        val source = File(root, "RAW10_20260808_130000").apply { mkdirs() }
        val width = 4
        val height = 2
        val frameCount = 20
        val fps = 24
        File(source, "clip.json").writeText(
            raw10Metadata(width = width, height = height).apply {
                put("frame_rate", fps)
            }.toString(2)
        )
        File(source, "frames.csv").writeText(buildString {
            append("frame,timestamp_ns,iso,exposure_ns,frame_duration_ns,focus_diopters,wb_r,wb_ge,wb_go,wb_b\n")
            repeat(frameCount) { frame ->
                append("$frame,${1_000L + frame},100,10000000,41666666,0.0,2.0,1.0,1.0,1.5\n")
            }
        })
        File(source, "clip.state").writeText(
            "version=1\nstatus=closed\ncompleted_frames=$frameCount\n" +
                "raw_bytes=${frameCount * 10}\nwidth=$width\nheight=$height\nrow_bytes=5\n"
        )
        File(source, "clip.raw10").writeBytes(
            ByteArray(frameCount * 10) { index -> (index and 0xff).toByte() }
        )
        val sampleRate = 48_000
        val channels = 2
        val bits = 24
        val frameBytes = channels * bits / 8
        val outputAudioFrames = frameCount * sampleRate / fps
        val sourceAudioFrames = outputAudioFrames + 1_000
        val sourcePayload = ByteArray(sourceAudioFrames * frameBytes) { index ->
            ((index * 37 + 11) and 0xff).toByte()
        }
        File(source, "audio.wav").writeBytes(waveHeader(sourcePayload.size, sampleRate, channels, bits) + sourcePayload)
        File(source, "audio.json").writeText(
            JSONObject().apply {
                put("format", "wav-pcm")
                put("status", "closed")
                put("file", "audio.wav")
                put("sample_rate", sampleRate)
                put("channels", channels)
                put("bits_per_sample", bits)
                put("frames", sourceAudioFrames)
                put("data_bytes", sourcePayload.size)
                put("finalized", true)
            }.toString(2)
        )
        File(source, "sync.json").writeText(
            JSONObject().apply {
                put("version", 1)
                put("camera_start_minus_audio_start_ns", 0)
            }.toString(2)
        )

        val result = Raw10CinemaDngConverter.convert(source) {}

        assertEquals(null, result.error)
        val output = requireNotNull(result.output)
        val converted = File(output, "audio.wav").readBytes()
        val payload = converted.copyOfRange(44, converted.size)
        assertEquals(outputAudioFrames * frameBytes, payload.size)
        assertArrayEquals(sourcePayload.copyOfRange(0, payload.size), payload)
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

    @Test
    fun `Pixel 6 D65 camera to Rec2020 matrix is finite`() {
        val matrix = Pixel6MainDngColorProfile.CAMERA_RGB_TO_REC2020_D65

        assertEquals(9, matrix.size)
        assertTrue(matrix.all(Float::isFinite))
        assertTrue(matrix.any { kotlin.math.abs(it) > 0.5f })
    }

    @Test
    fun `Pixel 6 camera to Rec2020 matrix interpolates for daylight white balance`() {
        val d65 = Pixel6MainDngColorProfile.cameraRgbToRec2020(6500)
        val daylight = Pixel6MainDngColorProfile.cameraRgbToRec2020(5250)

        assertTrue(daylight.all(Float::isFinite))
        assertTrue(daylight.indices.any { kotlin.math.abs(daylight[it] - d65[it]) > 0.005f })
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

    private fun waveHeader(payloadBytes: Int, sampleRate: Int, channels: Int, bits: Int): ByteArray {
        val frameBytes = channels * bits / 8
        return ByteBuffer.allocate(44)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + payloadBytes)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1)
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(sampleRate * frameBytes)
                putShort(frameBytes.toShort())
                putShort(bits.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(payloadBytes)
            }
            .array()
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
