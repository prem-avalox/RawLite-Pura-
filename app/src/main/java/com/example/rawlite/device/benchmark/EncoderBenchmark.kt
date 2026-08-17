package com.example.rawlite.device.benchmark

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.example.rawlite.device.Dimensions
import kotlin.math.ceil

internal class EncoderBenchmark {
    fun run(
        codecName: String,
        dimensions: Dimensions,
        targetFps: Int,
        bitRate: Int,
        durationSeconds: Double
    ): EncoderBenchmarkResult {
        var codec: MediaCodec? = null
        var submitted = 0
        var encoded = 0
        var elapsedNs = 0L
        return try {
            codec = MediaCodec.createByCodecName(codecName)
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                dimensions.width,
                dimensions.height
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
                )
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val targetFrames = ceil(targetFps * durationSeconds).toInt().coerceAtLeast(2)
            val info = MediaCodec.BufferInfo()
            val startedNs = System.nanoTime()
            val deadlineNs = startedNs + MAXIMUM_RUN_NS
            while (submitted < targetFrames && System.nanoTime() < deadlineNs) {
                val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val input = requireNotNull(codec.getInputBuffer(inputIndex))
                    input.clear()
                    fillNeutralP010(input)
                    val size = input.position()
                    input.flip()
                    codec.queueInputBuffer(
                        inputIndex,
                        0,
                        size,
                        submitted * 1_000_000L / targetFps,
                        0
                    )
                    submitted += 1
                }
                encoded += drainAvailable(codec, info)
            }
            check(submitted == targetFrames) { "El encoder dejó de aceptar entrada" }
            queueEndOfStream(codec, submitted * 1_000_000L / targetFps)
            var ended = false
            while (!ended && System.nanoTime() < deadlineNs) {
                when (val index = codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            encoded += 1
                        }
                        ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
            check(ended) { "El encoder no finalizó dentro del límite" }
            elapsedNs = (System.nanoTime() - startedNs).coerceAtLeast(1L)
            val elapsedSeconds = elapsedNs / 1_000_000_000.0
            val throughput = encoded / elapsedSeconds
            val headroom = throughput / targetFps
            EncoderBenchmarkResult(
                codecName = codecName,
                dimensions = dimensions,
                requestedFps = targetFps,
                bitRate = bitRate,
                submittedFrames = submitted,
                encodedFrames = encoded,
                elapsedSeconds = elapsedSeconds,
                throughputFps = throughput,
                realTimeHeadroom = headroom,
                state = if (encoded == submitted) {
                    ModeCertificationPolicy.throughputState(headroom)
                } else {
                    CertificationState.REJECTED
                }
            )
        } catch (error: Throwable) {
            EncoderBenchmarkResult(
                codecName = codecName,
                dimensions = dimensions,
                requestedFps = targetFps,
                bitRate = bitRate,
                submittedFrames = submitted,
                encodedFrames = encoded,
                elapsedSeconds = elapsedNs / 1_000_000_000.0,
                throughputFps = 0.0,
                realTimeHeadroom = 0.0,
                state = CertificationState.REJECTED,
                error = error.message ?: error.javaClass.simpleName
            )
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
        }
    }

    private fun drainAvailable(codec: MediaCodec, info: MediaCodec.BufferInfo): Int {
        var frames = 0
        while (true) {
            when (val index = codec.dequeueOutputBuffer(info, 0L)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                MediaCodec.INFO_TRY_AGAIN_LATER -> return frames
                else -> if (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        frames += 1
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    private fun queueEndOfStream(codec: MediaCodec, presentationTimeUs: Long) {
        repeat(200) {
            val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index >= 0) {
                codec.queueInputBuffer(
                    index,
                    0,
                    0,
                    presentationTimeUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                return
            }
        }
        error("No se pudo enviar el final de flujo al encoder")
    }

    private fun fillNeutralP010(buffer: java.nio.ByteBuffer) {
        while (buffer.remaining() >= Long.SIZE_BYTES) buffer.putLong(0x2000200020002000L)
        while (buffer.hasRemaining()) buffer.put(0x20.toByte())
    }

    companion object {
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val OUTPUT_TIMEOUT_US = 10_000L
        private const val MAXIMUM_RUN_NS = 20_000_000_000L
    }
}
