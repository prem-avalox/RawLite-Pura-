package com.example.rawlite

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class NativeTenBitConverterTest {
    @Test
    fun exactPixel6CodecCapacity_acceptsLastUvRowWithoutPadding() {
        val fixture = Fixture()

        val elapsedNs = fixture.convert(fixture.requiredOutputBytes)

        assertTrue("native conversion failed: $elapsedNs", elapsedNs >= 0L)
    }

    @Test
    fun oneByteShortPixel6CodecCapacity_isRejected() {
        val fixture = Fixture()

        val result = fixture.convert(fixture.requiredOutputBytes - 1)

        assertEquals(-22L, result)
    }

    private class Fixture {
        private val width = 2032
        private val height = 1536
        private val rawRowStride = width * 10 / 8
        private val outputRowStrideBytes = 4096
        private val outputSliceHeight = height
        val requiredOutputBytes =
            outputRowStrideBytes * outputSliceHeight +
                outputRowStrideBytes * (height / 2 - 1) +
                width * 2

        private val raw = ByteBuffer.allocateDirect(rawRowStride * height)

        fun convert(outputBytes: Int): Long = NativeTenBitConverter.raw10ToP010Hlg(
            raw = raw,
            rawOffset = 0,
            rawLimit = raw.capacity(),
            rawRowStride = rawRowStride,
            output = ByteBuffer.allocateDirect(outputBytes),
            sourceWidth = width,
            sourceHeight = height,
            cropX = 0,
            cropY = 0,
            outputWidth = width,
            outputHeight = height,
            outputRowStrideBytes = outputRowStrideBytes,
            outputSliceHeight = outputSliceHeight,
            colorFilterArrangement = 2,
            blackLevel = 64,
            whiteLevel = 1023,
            redGain = 2.0f,
            blueGain = 1.8f,
            exposureGain = 1.0f
        )
    }
}
