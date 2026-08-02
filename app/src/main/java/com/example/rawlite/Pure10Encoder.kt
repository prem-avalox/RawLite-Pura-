package com.example.rawlite

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.util.Size
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

internal data class Pure10Summary(
    val submittedFrames: Int,
    val encodedFrames: Int,
    val encoderDrops: Int,
    val conversionAverageMs: Double,
    val conversionMaxMs: Double,
    val firstTimestampNs: Long,
    val lastTimestampNs: Long,
    val finalized: Boolean,
    val error: String?
)

internal class Pure10Encoder(
    private val outputFile: File,
    private val size: Size,
    private val fps: Int,
    private val bitRate: Int,
    private val rotationDegrees: Int,
    private val characteristics: CameraCharacteristics
) {
    private val outputExecutor = Executors.newSingleThreadExecutor()
    private val outputFinished = CountDownLatch(1)
    private val submittedFrames = AtomicInteger()
    private val encodedFrames = AtomicInteger()
    private val encoderDrops = AtomicInteger()
    private val conversionTotalNs = AtomicLong()
    private val conversionMaxNs = AtomicLong()
    private val firstTimestampNs = AtomicLong()
    private val lastTimestampNs = AtomicLong()

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    @Volatile private var finishing = false
    @Volatile private var outputError: String? = null
    private var inputRowStrideBytes = 0
    private var inputSliceHeight = 0
    private var minimumInputBufferBytes = 0

    fun start() {
        require(size.width <= 3840 && size.height <= 2176) {
            "PURE 10 requiere la resolución RAW10 reducida"
        }
        val localCodec = MediaCodec.createByCodecName(CODEC_NAME)
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            size.width,
            size.height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
            )
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
        localCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        localCodec.start()
        val inputFormat = localCodec.inputFormat
        inputRowStrideBytes = if (inputFormat.containsKey(MediaFormat.KEY_STRIDE)) {
            inputFormat.getInteger(MediaFormat.KEY_STRIDE)
        } else {
            size.width * 2
        }
        inputSliceHeight = if (inputFormat.containsKey(MediaFormat.KEY_SLICE_HEIGHT)) {
            inputFormat.getInteger(MediaFormat.KEY_SLICE_HEIGHT)
        } else {
            size.height
        }
        require(inputRowStrideBytes >= size.width * 2 && inputSliceHeight >= size.height) {
            "Geometría P010 inválida del encoder"
        }
        minimumInputBufferBytes = (
            inputRowStrideBytes.toLong() * inputSliceHeight +
                inputRowStrideBytes.toLong() * (size.height / 2 - 1) +
                size.width.toLong() * 2
            ).toInt()
        codec = localCodec
        muxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        ).apply { setOrientationHint(rotationDegrees) }
        outputExecutor.execute(::drainOutput)
    }

    fun submit(
        image: Image,
        timestampNs: Long,
        redGain: Float,
        blueGain: Float,
        exposureGain: Float
    ): Boolean {
        if (finishing || image.format != ImageFormat.RAW10) return false
        val localCodec = codec ?: return false
        val inputIndex = localCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (inputIndex < 0) {
            encoderDrops.incrementAndGet()
            return false
        }
        return try {
            val output = requireNotNull(localCodec.getInputBuffer(inputIndex))
            if (output.capacity() < minimumInputBufferBytes) {
                throw IllegalStateException(
                    "Buffer P010 corto: ${output.capacity()} < $minimumInputBufferBytes"
                )
            }
            val plane = image.planes[0]
            val raw = plane.buffer
            output.clear()
            val blackPattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            val black = if (blackPattern == null) {
                64
            } else {
                (
                    blackPattern.getOffsetForIndex(0, 0) +
                        blackPattern.getOffsetForIndex(1, 0) +
                        blackPattern.getOffsetForIndex(0, 1) +
                        blackPattern.getOffsetForIndex(1, 1)
                    ) / 4
            }
            val white = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
            val cfa = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
            ) ?: CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
            val conversionNs = NativeTenBitConverter.raw10ToP010Hlg(
                raw = raw,
                rawOffset = raw.position(),
                rawLimit = raw.limit(),
                rawRowStride = plane.rowStride,
                output = output,
                width = size.width,
                height = size.height,
                outputRowStrideBytes = inputRowStrideBytes,
                outputSliceHeight = inputSliceHeight,
                colorFilterArrangement = cfa,
                blackLevel = black,
                whiteLevel = white,
                redGain = redGain.coerceIn(0.25f, 8f),
                blueGain = blueGain.coerceIn(0.25f, 8f),
                exposureGain = exposureGain.coerceIn(0.25f, 8f)
            )
            if (conversionNs < 0L) {
                localCodec.queueInputBuffer(inputIndex, 0, 0, 0L, 0)
                encoderDrops.incrementAndGet()
                false
            } else {
                conversionTotalNs.addAndGet(conversionNs)
                conversionMaxNs.updateAndGet { previous -> max(previous, conversionNs) }
                firstTimestampNs.compareAndSet(0L, timestampNs)
                lastTimestampNs.set(timestampNs)
                val presentationUs = (timestampNs - firstTimestampNs.get())
                    .coerceAtLeast(0L) / 1_000L
                localCodec.queueInputBuffer(
                    inputIndex,
                    0,
                    output.capacity(),
                    presentationUs,
                    0
                )
                submittedFrames.incrementAndGet()
                true
            }
        } catch (error: Throwable) {
            outputError = error.message ?: error.javaClass.simpleName
            encoderDrops.incrementAndGet()
            runCatching { localCodec.queueInputBuffer(inputIndex, 0, 0, 0L, 0) }
            Log.e(TAG, "PURE 10 conversion failed", error)
            false
        }
    }

    fun finish(): Pure10Summary {
        if (!finishing) {
            finishing = true
            queueEndOfStream()
        }
        val outputCompleted = outputFinished.await(FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!outputCompleted) {
            outputError = "Tiempo de espera agotado al finalizar HEVC"
        }
        val containerClosed = closeResources()
        val submitted = submittedFrames.get()
        return Pure10Summary(
            submittedFrames = submitted,
            encodedFrames = encodedFrames.get(),
            encoderDrops = encoderDrops.get(),
            conversionAverageMs = if (submitted > 0) {
                conversionTotalNs.get() / submitted / 1_000_000.0
            } else {
                0.0
            },
            conversionMaxMs = conversionMaxNs.get() / 1_000_000.0,
            firstTimestampNs = firstTimestampNs.get(),
            lastTimestampNs = lastTimestampNs.get(),
            finalized = outputCompleted && containerClosed && outputError == null &&
                encodedFrames.get() > 0,
            error = outputError
        )
    }

    fun abort() {
        finishing = true
        outputFinished.countDown()
        closeResources()
    }

    private fun queueEndOfStream() {
        val localCodec = codec ?: return
        repeat(200) {
            val inputIndex = localCodec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val presentationUs = (lastTimestampNs.get() - firstTimestampNs.get())
                    .coerceAtLeast(0L) / 1_000L + 1L
                localCodec.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    presentationUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                return
            }
        }
        outputError = "No se pudo enviar EOS al encoder HEVC"
        outputFinished.countDown()
    }

    private fun drainOutput() {
        val localCodec = codec ?: return outputFinished.countDown()
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                when (val index = localCodec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val localMuxer = requireNotNull(muxer)
                        trackIndex = localMuxer.addTrack(localCodec.outputFormat)
                        localMuxer.start()
                        muxerStarted = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        val buffer = requireNotNull(localCodec.getOutputBuffer(index))
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            requireNotNull(muxer).writeSampleData(trackIndex, buffer, info)
                            encodedFrames.incrementAndGet()
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        localCodec.releaseOutputBuffer(index, false)
                        if (eos) break
                    }
                }
            }
        } catch (error: Throwable) {
            outputError = error.message ?: error.javaClass.simpleName
            Log.e(TAG, "PURE 10 output failed", error)
        } finally {
            outputFinished.countDown()
        }
    }

    private fun closeResources(): Boolean {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        val containerClosed = muxerStarted && runCatching { muxer?.stop() }.isSuccess
        runCatching { muxer?.release() }
        muxer = null
        outputExecutor.shutdown()
        return containerClosed
    }

    companion object {
        const val VIDEO_FILE = "pure10.mp4"
        const val BIT_RATE = 50_000_000
        const val HLG_MIDTONE_GAIN = 3.0f
        const val CODEC_NAME = "c2.exynos.hevc.encoder"
        private const val TAG = "RawLitePure10"
        private const val INPUT_TIMEOUT_US = 5_000L
        private const val FINISH_TIMEOUT_SECONDS = 15L
    }
}
