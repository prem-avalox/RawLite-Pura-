package com.example.rawlite.gallery

import com.example.rawlite.RawFrameInfo
import com.example.rawlite.LensShadingSample
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong

internal object CinemaDngWriter {
    fun write(
        destination: File,
        mosaicLittleEndian16: ByteArray,
        metadata: JSONObject,
        frame: RawFrameInfo,
        lensShading: LensShadingSample? = null
    ) {
        val width = metadata.getInt("width")
        val height = metadata.getInt("height")
        require(mosaicLittleEndian16.size == width * height * 2) {
            "Mosaico DNG inválido: ${mosaicLittleEndian16.size}"
        }
        val entries = dngEntries(metadata, frame, lensShading).toMutableList()
        entries += entry(TAG_IMAGE_WIDTH, TYPE_LONG, longs(width.toLong()))
        entries += entry(TAG_IMAGE_LENGTH, TYPE_LONG, longs(height.toLong()))
        entries += entry(TAG_BITS_PER_SAMPLE, TYPE_SHORT, shorts(16))
        entries += entry(TAG_COMPRESSION, TYPE_SHORT, shorts(1))
        entries += entry(TAG_PHOTOMETRIC, TYPE_SHORT, shorts(PHOTOMETRIC_CFA))
        entries += entry(TAG_STRIP_OFFSETS, TYPE_LONG, longs(0))
        entries += entry(TAG_SAMPLES_PER_PIXEL, TYPE_SHORT, shorts(1))
        entries += entry(TAG_ROWS_PER_STRIP, TYPE_LONG, longs(height.toLong()))
        entries += entry(TAG_STRIP_BYTE_COUNTS, TYPE_LONG, longs(mosaicLittleEndian16.size.toLong()))
        entries += entry(TAG_X_RESOLUTION, TYPE_RATIONAL, rationals(72 to 1))
        entries += entry(TAG_Y_RESOLUTION, TYPE_RATIONAL, rationals(72 to 1))
        entries += entry(TAG_RESOLUTION_UNIT, TYPE_SHORT, shorts(2))
        entries += ascii(TAG_SOFTWARE, "RawLite converter")
        writeTiff(destination, entries, mosaicLittleEndian16)
    }

    private fun dngEntries(
        metadata: JSONObject,
        frame: RawFrameInfo,
        lensShading: LensShadingSample?
    ): List<Entry> {
        val width = metadata.getInt("width")
        val height = metadata.getInt("height")
        val cfa = metadata.optString("cfa", "RGGB")
        val cameraName = metadata.optString("camera", "RawLite")
        val isPixel6Main = metadata.optString("camera_id") in setOf("0", "2") &&
            cameraName.startsWith("Principal") &&
            cfa == "GBRG"
        val make = if (isPixel6Main) "Google" else "RawLite"
        val model = if (isPixel6Main) "Pixel 6" else cameraName
        val black = metadata.optJSONArray("black_level") ?: JSONArray().apply {
            repeat(4) { put(0) }
        }
        val neutral = asShotNeutral(frame)
        val output = mutableListOf<Entry>()
        output += entry(TAG_NEW_SUBFILE_TYPE, TYPE_LONG, longs(0))
        output += ascii(TAG_MAKE, make)
        output += ascii(TAG_MODEL, model)
        output += entry(TAG_ORIENTATION, TYPE_SHORT, shorts(1))
        output += entry(TAG_CFA_REPEAT_PATTERN_DIM, TYPE_SHORT, shorts(2, 2))
        output += entry(TAG_CFA_PATTERN, TYPE_BYTE, bytes(*cfaPattern(cfa)))
        output += entry(TAG_EXPOSURE_TIME, TYPE_RATIONAL, rationals(rational(frame.exposureNs / 1e9)))
        output += entry(TAG_ISO_SPEED, TYPE_SHORT, shorts(frame.iso.coerceAtLeast(0)))
        output += entry(TAG_DNG_VERSION, TYPE_BYTE, bytes(1, 4, 0, 0))
        output += entry(
            TAG_DNG_BACKWARD_VERSION,
            TYPE_BYTE,
            if (lensShading != null) bytes(1, 3, 0, 0) else bytes(1, 1, 0, 0)
        )
        output += ascii(
            TAG_UNIQUE_CAMERA_MODEL,
            metadata.optString("unique_camera_model", "RawLite $cameraName")
        )
        output += entry(TAG_CFA_PLANE_COLOR, TYPE_BYTE, bytes(0, 1, 2))
        output += entry(TAG_CFA_LAYOUT, TYPE_SHORT, shorts(1))
        output += entry(TAG_BLACK_LEVEL_REPEAT_DIM, TYPE_SHORT, shorts(2, 2))
        output += entry(
            TAG_BLACK_LEVEL,
            TYPE_RATIONAL,
            rationals(*(0 until 4).map { rational(black.optDouble(it, 0.0)) }.toTypedArray())
        )
        output += entry(TAG_WHITE_LEVEL, TYPE_LONG, longs(metadata.optLong("white_level", 1023L)))
        output += entry(TAG_DEFAULT_SCALE, TYPE_RATIONAL, rationals(1 to 1, 1 to 1))
        output += entry(TAG_DEFAULT_CROP_ORIGIN, TYPE_LONG, longs(0, 0))
        output += entry(TAG_DEFAULT_CROP_SIZE, TYPE_LONG, longs(width.toLong(), height.toLong()))
        output += matrixEntry(metadata, "color_matrix_1", TAG_COLOR_MATRIX_1)
        output += matrixEntry(metadata, "color_matrix_2", TAG_COLOR_MATRIX_2)
        output += entry(TAG_AS_SHOT_NEUTRAL, TYPE_RATIONAL, rationals(*neutral))
        if (metadata.has("baseline_exposure")) {
            output += entry(
                TAG_BASELINE_EXPOSURE,
                TYPE_SRATIONAL,
                signedRationals(rational(metadata.optDouble("baseline_exposure", 0.0)))
            )
        }
        output += entry(
            TAG_CALIBRATION_ILLUMINANT_1,
            TYPE_SHORT,
            shorts(metadata.optInt("reference_illuminant_1", 21))
        )
        output += entry(
            TAG_CALIBRATION_ILLUMINANT_2,
            TYPE_SHORT,
            shorts(metadata.optInt("reference_illuminant_2", 17))
        )
        output += entry(TAG_ACTIVE_AREA, TYPE_LONG, longs(0, 0, height.toLong(), width.toLong()))
        optionalMatrix(metadata, "calibration_matrix_1", TAG_CAMERA_CALIBRATION_1)?.let(output::add)
        optionalMatrix(metadata, "calibration_matrix_2", TAG_CAMERA_CALIBRATION_2)?.let(output::add)
        optionalMatrix(metadata, "forward_matrix_1", TAG_FORWARD_MATRIX_1)?.let(output::add)
        optionalMatrix(metadata, "forward_matrix_2", TAG_FORWARD_MATRIX_2)?.let(output::add)
        lensShading?.let {
            val opcodeList = gainMapOpcodeList(it, width, height, cfa)
            output += Entry(TAG_OPCODE_LIST_2, TYPE_UNDEFINED, opcodeList.size, opcodeList)
        }
        return output
    }

    /** DNG 1.3 GainMap opcodes. Opcode payloads are always big-endian. */
    private fun gainMapOpcodeList(
        sample: LensShadingSample,
        width: Int,
        height: Int,
        cfa: String
    ): ByteArray {
        val channels = when (cfa) {
            "GRBG" -> intArrayOf(1, 0, 3, 2)
            "GBRG" -> intArrayOf(1, 3, 0, 2)
            "BGGR" -> intArrayOf(3, 1, 2, 0)
            else -> intArrayOf(0, 1, 2, 3)
        }
        val parameterBytes = 76 + sample.rows * sample.columns * Float.SIZE_BYTES
        return ByteArrayOutputStream(4 + 4 * (16 + parameterBytes)).apply {
            writeIntBe(4)
            for (site in 0 until 4) {
                writeIntBe(OPCODE_GAIN_MAP)
                writeIntBe(DNG_VERSION_1_3)
                writeIntBe(0)
                writeIntBe(parameterBytes)
                writeIntBe(site ushr 1) // Top
                writeIntBe(site and 1) // Left
                writeIntBe(height) // Bottom, exclusive
                writeIntBe(width) // Right, exclusive
                writeIntBe(0) // Plane
                writeIntBe(1) // Planes
                writeIntBe(2) // RowPitch
                writeIntBe(2) // ColPitch
                writeIntBe(sample.rows)
                writeIntBe(sample.columns)
                writeDoubleBe(1.0 / (sample.rows - 1))
                writeDoubleBe(1.0 / (sample.columns - 1))
                writeDoubleBe(0.0)
                writeDoubleBe(0.0)
                writeIntBe(1) // MapPlanes
                val channel = channels[site]
                for (row in 0 until sample.rows) {
                    for (column in 0 until sample.columns) {
                        writeFloatBe(
                            sample.gains[(row * sample.columns + column) * 4 + channel]
                        )
                    }
                }
            }
        }.toByteArray()
    }

    private fun asShotNeutral(frame: RawFrameInfo): Array<Pair<Long, Long>> {
        val red = frame.wbRed.coerceAtLeast(0.0001f)
        val green = ((frame.wbGreenEven + frame.wbGreenOdd) / 2f).coerceAtLeast(0.0001f)
        val blue = frame.wbBlue.coerceAtLeast(0.0001f)
        val raw = doubleArrayOf(1.0 / red, 1.0 / green, 1.0 / blue)
        val scale = raw.maxOrNull()?.coerceAtLeast(0.0001) ?: 1.0
        return raw.map { rational(it / scale) }.toTypedArray()
    }

    private fun matrixEntry(metadata: JSONObject, key: String, tag: Int): Entry {
        return optionalMatrix(metadata, key, tag)
            ?: error("La toma no contiene matriz de color válida: $key")
    }

    private fun optionalMatrix(metadata: JSONObject, key: String, tag: Int): Entry? {
        val values = metadata.optJSONArray(key) ?: return null
        if (values.length() != 9) return null
        val pairs = ArrayList<Pair<Long, Long>>(9)
        for (index in 0 until values.length()) {
            val pair = values.optJSONArray(index) ?: return null
            pairs += pair.optLong(0, 0L) to pair.optLong(1, 1L).let {
                if (it == 0L) 1L else it
            }
        }
        return entry(tag, TYPE_SRATIONAL, signedRationals(*pairs.toTypedArray()))
    }

    private fun writeTiff(destination: File, originalEntries: List<Entry>, image: ByteArray) {
        val entries = originalEntries.sortedBy { it.tag }.toMutableList()
        val entryCount = entries.size
        val ifdStart = TIFF_HEADER_BYTES
        var dataCursor = align2(ifdStart + 2 + entryCount * 12 + 4)
        val externalValues = mutableListOf<Pair<Int, ByteArray>>()
        for (entry in entries) {
            if (entry.value.size > 4) {
                dataCursor = align2(dataCursor)
                entry.valueOffset = dataCursor
                externalValues += dataCursor to entry.value
                dataCursor += entry.value.size
            }
        }
        val imageOffset = align2(dataCursor)
        entries.replaceAll { entry ->
            if (entry.tag == TAG_STRIP_OFFSETS) {
                entry.copy(value = longs(imageOffset.toLong()).bytes)
            } else {
                entry
            }
        }

        val output = ByteArrayOutputStream(imageOffset + image.size)
        output.write(byteArrayOf('I'.code.toByte(), 'I'.code.toByte()))
        output.writeShortLe(42)
        output.writeIntLe(ifdStart)
        output.writeShortLe(entryCount)
        entries.forEach { entry ->
            output.writeShortLe(entry.tag)
            output.writeShortLe(entry.type)
            output.writeIntLe(entry.count)
            if (entry.value.size <= 4) {
                output.write(entry.value)
                repeat(4 - entry.value.size) { output.write(0) }
            } else {
                output.writeIntLe(entry.valueOffset)
            }
        }
        output.writeIntLe(0)
        externalValues.forEach { (offset, bytes) ->
            while (output.size() < offset) output.write(0)
            output.write(bytes)
        }
        while (output.size() < imageOffset) output.write(0)
        output.write(image)

        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeBytes(output.toByteArray())
        if (destination.exists() && !destination.delete()) {
            throw IllegalStateException("No se pudo reemplazar ${destination.name}")
        }
        require(temporary.renameTo(destination)) { "No se pudo cerrar ${destination.name}" }
    }

    private data class Entry(
        val tag: Int,
        val type: Int,
        val count: Int,
        val value: ByteArray,
        var valueOffset: Int = 0
    )

    private fun entry(tag: Int, type: Int, value: TypedValue): Entry =
        Entry(tag, type, value.count, value.bytes)

    private fun ascii(tag: Int, value: String): Entry {
        val bytes = (value.encodeToByteArray() + byteArrayOf(0))
        return Entry(tag, TYPE_ASCII, bytes.size, bytes)
    }

    private data class TypedValue(val count: Int, val bytes: ByteArray)

    private fun bytes(vararg values: Int) = TypedValue(
        values.size,
        ByteArray(values.size) { values[it].toByte() }
    )

    private fun shorts(vararg values: Int): TypedValue {
        val output = ByteArrayOutputStream(values.size * 2)
        values.forEach { output.writeShortLe(it) }
        return TypedValue(values.size, output.toByteArray())
    }

    private fun longs(vararg values: Long): TypedValue {
        val output = ByteArrayOutputStream(values.size * 4)
        values.forEach { output.writeIntLe(it.toInt()) }
        return TypedValue(values.size, output.toByteArray())
    }

    private fun rationals(vararg values: Pair<Long, Long>): TypedValue {
        val output = ByteArrayOutputStream(values.size * 8)
        values.forEach { (numerator, denominator) ->
            output.writeIntLe(numerator.coerceIn(0L, UINT32_MAX).toInt())
            output.writeIntLe(denominator.coerceIn(1L, UINT32_MAX).toInt())
        }
        return TypedValue(values.size, output.toByteArray())
    }

    private fun signedRationals(vararg values: Pair<Long, Long>): TypedValue {
        val output = ByteArrayOutputStream(values.size * 8)
        values.forEach { (numerator, denominator) ->
            output.writeIntLe(numerator.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
            output.writeIntLe(denominator.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt())
        }
        return TypedValue(values.size, output.toByteArray())
    }

    private fun rational(value: Double, limit: Long = 1_000_000L): Pair<Long, Long> {
        if (!value.isFinite()) return 0L to 1L
        var bestNumerator = value.roundToLong()
        var bestDenominator = 1L
        var bestError = abs(value - bestNumerator)
        var denominator = 1L
        while (denominator <= limit) {
            val numerator = (value * denominator).roundToLong()
            val error = abs(value - numerator.toDouble() / denominator)
            if (error < bestError) {
                bestNumerator = numerator
                bestDenominator = denominator
                bestError = error
                if (error == 0.0) break
            }
            denominator *= 10L
        }
        val gcd = gcd(abs(bestNumerator), bestDenominator)
        return bestNumerator / gcd to bestDenominator / gcd
    }

    private fun cfaPattern(value: String): IntArray = when (value) {
        "GRBG" -> intArrayOf(1, 0, 2, 1)
        "GBRG" -> intArrayOf(1, 2, 0, 1)
        "BGGR" -> intArrayOf(2, 1, 1, 0)
        else -> intArrayOf(0, 1, 1, 2)
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    private fun ByteArrayOutputStream.writeIntBe(value: Int) {
        write((value ushr 24) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private fun ByteArrayOutputStream.writeFloatBe(value: Float) =
        writeIntBe(value.toRawBits())

    private fun ByteArrayOutputStream.writeDoubleBe(value: Double) {
        val bits = value.toRawBits()
        writeIntBe((bits ushr 32).toInt())
        writeIntBe(bits.toInt())
    }

    private fun gcd(a: Long, b: Long): Long {
        var x = a
        var y = b
        while (y != 0L) {
            val next = x % y
            x = y
            y = next
        }
        return x.coerceAtLeast(1L)
    }

    private fun align2(value: Int): Int = if (value and 1 == 0) value else value + 1

    private const val TIFF_HEADER_BYTES = 8
    private const val UINT32_MAX = 0xffff_ffffL
    private const val TYPE_BYTE = 1
    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5
    private const val TYPE_UNDEFINED = 7
    private const val TYPE_SRATIONAL = 10
    private const val PHOTOMETRIC_CFA = 32803

    private const val TAG_NEW_SUBFILE_TYPE = 254
    private const val TAG_IMAGE_WIDTH = 256
    private const val TAG_IMAGE_LENGTH = 257
    private const val TAG_BITS_PER_SAMPLE = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_PHOTOMETRIC = 262
    private const val TAG_MAKE = 271
    private const val TAG_MODEL = 272
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_ORIENTATION = 274
    private const val TAG_SAMPLES_PER_PIXEL = 277
    private const val TAG_ROWS_PER_STRIP = 278
    private const val TAG_STRIP_BYTE_COUNTS = 279
    private const val TAG_X_RESOLUTION = 282
    private const val TAG_Y_RESOLUTION = 283
    private const val TAG_RESOLUTION_UNIT = 296
    private const val TAG_SOFTWARE = 305
    private const val TAG_CFA_REPEAT_PATTERN_DIM = 33421
    private const val TAG_CFA_PATTERN = 33422
    private const val TAG_EXPOSURE_TIME = 33434
    private const val TAG_ISO_SPEED = 34855
    private const val TAG_DNG_VERSION = 50706
    private const val TAG_DNG_BACKWARD_VERSION = 50707
    private const val TAG_UNIQUE_CAMERA_MODEL = 50708
    private const val TAG_CFA_PLANE_COLOR = 50710
    private const val TAG_CFA_LAYOUT = 50711
    private const val TAG_BLACK_LEVEL_REPEAT_DIM = 50713
    private const val TAG_BLACK_LEVEL = 50714
    private const val TAG_WHITE_LEVEL = 50717
    private const val TAG_DEFAULT_SCALE = 50718
    private const val TAG_DEFAULT_CROP_ORIGIN = 50719
    private const val TAG_DEFAULT_CROP_SIZE = 50720
    private const val TAG_COLOR_MATRIX_1 = 50721
    private const val TAG_COLOR_MATRIX_2 = 50722
    private const val TAG_CAMERA_CALIBRATION_1 = 50723
    private const val TAG_CAMERA_CALIBRATION_2 = 50724
    private const val TAG_AS_SHOT_NEUTRAL = 50728
    private const val TAG_BASELINE_EXPOSURE = 50730
    private const val TAG_CALIBRATION_ILLUMINANT_1 = 50778
    private const val TAG_CALIBRATION_ILLUMINANT_2 = 50779
    private const val TAG_ACTIVE_AREA = 50829
    private const val TAG_FORWARD_MATRIX_1 = 50964
    private const val TAG_FORWARD_MATRIX_2 = 50965
    private const val TAG_OPCODE_LIST_2 = 51009
    private const val OPCODE_GAIN_MAP = 9
    private const val DNG_VERSION_1_3 = 0x01030000
}
