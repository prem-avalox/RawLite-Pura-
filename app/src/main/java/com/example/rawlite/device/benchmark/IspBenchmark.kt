package com.example.rawlite.device.benchmark

import android.hardware.camera2.CameraCharacteristics
import com.example.rawlite.NativeTenBitConverter
import com.example.rawlite.device.Dimensions
import java.nio.ByteBuffer
import kotlin.math.ceil

internal class IspBenchmark {
    fun run(
        source: Dimensions,
        output: Dimensions,
        targetFps: Int,
        durationSeconds: Double
    ): IspBenchmarkResult {
        val rawRowStride = align((source.width * 10 + 7) / 8, 64)
        val outputRowStride = align(output.width * 2, 64)
        val raw = ByteBuffer.allocateDirect(rawRowStride * source.height)
        val p010 = ByteBuffer.allocateDirect(outputRowStride * output.height * 3 / 2)
        fillPattern(raw)
        val frameCount = ceil(targetFps * durationSeconds).toInt().coerceAtLeast(2)
        val durations = ArrayList<Long>(frameCount)
        val cropX = ((source.width - output.width) / 2).coerceAtLeast(0) and -2
        val cropY = ((source.height - output.height) / 2).coerceAtLeast(0) and -2
        return try {
            require(output.width <= source.width && output.height <= source.height)
            repeat(frameCount) {
                val durationNs = NativeTenBitConverter.raw10ToP010Hlg(
                    raw = raw,
                    rawOffset = 0,
                    rawLimit = raw.capacity(),
                    rawRowStride = rawRowStride,
                    output = p010,
                    sourceWidth = source.width,
                    sourceHeight = source.height,
                    cropX = cropX,
                    cropY = cropY,
                    outputWidth = output.width,
                    outputHeight = output.height,
                    outputRowStrideBytes = outputRowStride,
                    outputSliceHeight = output.height,
                    colorFilterArrangement = CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB,
                    blackLevel = 64,
                    whiteLevel = 1023,
                    exposureGain = 1f,
                    redGain = 1f,
                    greenEvenGain = 1f,
                    greenOddGain = 1f,
                    blueGain = 1f,
                    cameraRgbToRec2020 = IDENTITY_MATRIX,
                    lensShadingGains = null,
                    lensShadingRows = 0,
                    lensShadingColumns = 0
                )
                check(durationNs >= 0L) { "El conversor nativo rechazó la geometría" }
                durations += durationNs
            }
            val distribution = Distribution.fromNanoseconds(durations)
            val headroom = if (distribution.averageMs > 0.0) {
                1_000.0 / distribution.averageMs / targetFps
            } else {
                0.0
            }
            IspBenchmarkResult(
                sourceDimensions = source,
                outputDimensions = output,
                framesProcessed = durations.size,
                requestedFps = targetFps,
                conversion = distribution,
                realTimeHeadroom = headroom,
                state = ModeCertificationPolicy.throughputState(headroom)
            )
        } catch (error: Throwable) {
            IspBenchmarkResult(
                sourceDimensions = source,
                outputDimensions = output,
                framesProcessed = durations.size,
                requestedFps = targetFps,
                conversion = Distribution.fromNanoseconds(durations),
                realTimeHeadroom = 0.0,
                state = CertificationState.REJECTED,
                error = error.message ?: error.javaClass.simpleName
            )
        }
    }

    private fun fillPattern(buffer: ByteBuffer) {
        buffer.clear()
        while (buffer.remaining() >= Long.SIZE_BYTES) buffer.putLong(0x8020080200802008uL.toLong())
        while (buffer.hasRemaining()) buffer.put(0x80.toByte())
        buffer.clear()
    }

    private fun align(value: Int, alignment: Int): Int =
        ((value + alignment - 1) / alignment) * alignment

    companion object {
        private val IDENTITY_MATRIX = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )
    }
}
