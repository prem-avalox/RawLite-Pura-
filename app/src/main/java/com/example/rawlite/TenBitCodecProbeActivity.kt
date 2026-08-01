package com.example.rawlite

import android.app.Activity
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder

class TenBitCodecProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread({ runProbe() }, "TenBitCodecProbe").start()
    }

    private fun runProbe() {
        val outputDirectory = File(getExternalFilesDir(null), "codec-probe").apply {
            deleteRecursively()
            mkdirs()
        }
        val report = JSONObject()
        report.put("device", Build.MODEL)
        report.put("android", Build.VERSION.RELEASE)
        report.put("sdk", Build.VERSION.SDK_INT)
        report.put("temperature_before_c", batteryTemperatureC())

        val encoders = JSONArray()
        val hevcEncoders = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any {
                it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
            }
        }
        hevcEncoders.forEach { info ->
            val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            encoders.put(JSONObject().apply {
                put("name", info.name)
                put("hardware", Build.VERSION.SDK_INT < 29 || info.isHardwareAccelerated)
                put("software", Build.VERSION.SDK_INT >= 29 && info.isSoftwareOnly)
                put("color_formats", JSONArray(caps.colorFormats.toList()))
                put("supports_p010", caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010))
                put("profiles", JSONArray().apply {
                    caps.profileLevels.forEach { level ->
                        put(JSONObject().apply {
                            put("profile", level.profile)
                            put("level", level.level)
                        })
                    }
                })
            })
        }
        report.put("hevc_encoders", encoders)

        val attempts = JSONArray()
        val candidates = hevcEncoders.filter { info ->
            Build.VERSION.SDK_INT < 29 || info.isHardwareAccelerated
        }
        for (info in candidates) {
            attempts.put(runEncodeAttempt(info.name, outputDirectory))
        }
        report.put("attempts", attempts)
        report.put("temperature_after_c", batteryTemperatureC())

        File(outputDirectory, "report.json").writeText(report.toString(2))
        Log.i(TAG, "Probe finished: ${report.toString()}")
        runOnUiThread { finish() }
    }

    private fun runEncodeAttempt(codecName: String, outputDirectory: File): JSONObject {
        val result = JSONObject().apply { put("codec", codecName) }
        val output = File(outputDirectory, codecName.replace('.', '_') + "-main10.mp4")
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        val startedNs = System.nanoTime()
        try {
            codec = MediaCodec.createByCodecName(codecName)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var inputFrame = 0
            var outputFrames = 0
            var trackIndex = -1
            var muxerStarted = false
            var inputEnded = false
            var outputEnded = false
            val deadlineNs = System.nanoTime() + 15_000_000_000L

            while (!outputEnded && System.nanoTime() < deadlineNs) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = requireNotNull(codec.getInputBuffer(inputIndex))
                        if (inputFrame < FRAME_COUNT) {
                            fillP010(input, inputFrame)
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                FRAME_BYTES,
                                inputFrame * 1_000_000L / FPS,
                                0
                            )
                            inputFrame++
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                inputFrame * 1_000_000L / FPS,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "Output format changed twice" }
                        val outputFormat = codec.outputFormat
                        result.put("output_format", outputFormat.toString())
                        trackIndex = muxer.addTrack(outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val buffer = requireNotNull(codec.getOutputBuffer(outputIndex))
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }
                        if (info.size > 0) {
                            check(muxerStarted) { "Encoded data arrived before format" }
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, buffer, info)
                            outputFrames++
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            check(outputEnded) { "Timed out waiting for encoder EOS" }
            result.put("success", true)
            result.put("input_frames", inputFrame)
            result.put("output_frames", outputFrames)
            result.put("bytes", output.length())
            result.put("path", output.absolutePath)
        } catch (error: Throwable) {
            Log.e(TAG, "P010/Main10 failed for $codecName", error)
            result.put("success", false)
            result.put("error", "${error.javaClass.simpleName}: ${error.message}")
            output.delete()
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            codec?.release()
            try {
                muxer?.stop()
            } catch (_: Throwable) {
            }
            muxer?.release()
            result.put("elapsed_ms", (System.nanoTime() - startedNs) / 1_000_000L)
        }
        return result
    }

    private fun fillP010(buffer: java.nio.ByteBuffer, frameIndex: Int) {
        buffer.clear()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        check(buffer.capacity() >= FRAME_BYTES) {
            "Input buffer ${buffer.capacity()} is smaller than $FRAME_BYTES"
        }
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val value10 = 64 + ((x * 3 + y * 2 + frameIndex * 13) % 877)
                buffer.putShort((value10 shl 6).toShort())
            }
        }
        val cb10 = 512 + (frameIndex % 17) - 8
        val cr10 = 512 - (frameIndex % 13) + 6
        repeat(WIDTH * HEIGHT / 4) {
            buffer.putShort((cb10 shl 6).toShort())
            buffer.putShort((cr10 shl 6).toShort())
        }
        buffer.flip()
    }

    private fun batteryTemperatureC(): Double {
        val battery = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return battery?.getIntExtra("temperature", 0)?.div(10.0) ?: 0.0
    }

    companion object {
        private const val TAG = "RawLite10BitProbe"
        private const val WIDTH = 960
        private const val HEIGHT = 540
        private const val FPS = 24
        private const val FRAME_COUNT = 48
        private const val BIT_RATE = 20_000_000
        private const val FRAME_BYTES = WIDTH * HEIGHT * 3
    }
}
