package com.example.rawlite

import android.hardware.camera2.CameraCharacteristics
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Pure10NativeGoldenTest {
    @Test
    fun raw10ToP010HlgRemainsBitExact() {
        val width = 1920
        val height = 1080
        val rawStride = ((width * 10 + 7) / 8 + 63) and -64
        val outputStride = ((width * 2 + 63) / 64) * 64
        val raw = ByteBuffer.allocateDirect(rawStride * height)
        fillDeterministicRaw10(raw, rawStride, width, height)
        val output = ByteBuffer.allocateDirect(outputStride * height * 3 / 2)
        val shading = floatArrayOf(
            1.00f, 1.01f, 1.02f, 1.03f,
            1.04f, 1.05f, 1.06f, 1.07f,
            1.08f, 1.09f, 1.10f, 1.11f,
            1.12f, 1.13f, 1.14f, 1.15f,
            1.16f, 1.17f, 1.18f, 1.19f,
            1.20f, 1.21f, 1.22f, 1.23f
        )

        val elapsedNs = NativeTenBitConverter.raw10ToP010Hlg(
            raw = raw,
            rawOffset = 0,
            rawLimit = raw.capacity(),
            rawRowStride = rawStride,
            output = output,
            sourceWidth = width,
            sourceHeight = height,
            cropX = 0,
            cropY = 0,
            outputWidth = width,
            outputHeight = height,
            outputRowStrideBytes = outputStride,
            outputSliceHeight = height,
            colorFilterArrangement =
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG,
            blackLevel = 64,
            whiteLevel = 1023,
            exposureGain = 1.505247f,
            redGain = 2.03f,
            greenEvenGain = 1.0f,
            greenOddGain = 1.01f,
            blueGain = 1.72f,
            cameraRgbToRec2020 = floatArrayOf(
                0.7694588f, 0.42820382f, -0.19766256f,
                -0.11730336f, 1.3089911f, -0.19168775f,
                -0.054129966f, -0.5719075f, 1.6260375f
            ),
            lensShadingGains = shading,
            lensShadingRows = 2,
            lensShadingColumns = 3
        )

        assertTrue(elapsedNs > 0L)
        assertEquals(EXPECTED_CRC32, crc32(output))

        val cachedOutput = ByteBuffer.allocateDirect(outputStride * height * 3 / 2)
        val cachedElapsedNs = NativeTenBitConverter.raw10ToP010Hlg(
            raw = raw,
            rawOffset = 0,
            rawLimit = raw.capacity(),
            rawRowStride = rawStride,
            output = cachedOutput,
            sourceWidth = width,
            sourceHeight = height,
            cropX = 0,
            cropY = 0,
            outputWidth = width,
            outputHeight = height,
            outputRowStrideBytes = outputStride,
            outputSliceHeight = height,
            colorFilterArrangement =
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG,
            blackLevel = 64,
            whiteLevel = 1023,
            exposureGain = 1.505247f,
            redGain = 2.03f,
            greenEvenGain = 1.0f,
            greenOddGain = 1.01f,
            blueGain = 1.72f,
            cameraRgbToRec2020 = floatArrayOf(
                0.7694588f, 0.42820382f, -0.19766256f,
                -0.11730336f, 1.3089911f, -0.19168775f,
                -0.054129966f, -0.5719075f, 1.6260375f
            ),
            lensShadingGains = null,
            lensShadingRows = 0,
            lensShadingColumns = 0
        )
        assertTrue(cachedElapsedNs > 0L)
        assertEquals(EXPECTED_CRC32, crc32(cachedOutput))
    }

    private fun fillDeterministicRaw10(
        output: ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int
    ) {
        for (y in 0 until height) {
            val row = y * rowStride
            for (x in 0 until width step 4) {
                val values = IntArray(4) { lane ->
                    64 + ((x * 13 + y * 17 + lane * 101) % 960)
                }
                val group = row + (x / 4) * 5
                repeat(4) { lane -> output.put(group + lane, (values[lane] shr 2).toByte()) }
                output.put(
                    group + 4,
                    (
                        (values[0] and 3) or
                            ((values[1] and 3) shl 2) or
                            ((values[2] and 3) shl 4) or
                            ((values[3] and 3) shl 6)
                        ).toByte()
                )
            }
        }
    }

    private fun crc32(buffer: ByteBuffer): Long {
        val duplicate = buffer.duplicate().apply {
            clear()
        }
        val crc = CRC32()
        val bytes = ByteArray(64 * 1024)
        while (duplicate.hasRemaining()) {
            val count = minOf(duplicate.remaining(), bytes.size)
            duplicate.get(bytes, 0, count)
            crc.update(bytes, 0, count)
        }
        return crc.value
    }

    companion object {
        private const val EXPECTED_CRC32 = 3_408_928_496L
    }
}
