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
import com.example.rawlite.core.config.Pixel6ReferenceProfile
import com.example.rawlite.gallery.Pixel6MainDngColorProfile
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

internal data class Pure10Summary(
    val submittedFrames: Int,
    val encodedFrames: Int,
    val encoderDrops: Int,
    val queueAverageMs: Double,
    val queueMaxMs: Double,
    val conversionAverageMs: Double,
    val conversionMaxMs: Double,
    val processingAverageMs: Double,
    val processingMaxMs: Double,
    val firstTimestampNs: Long,
    val lastTimestampNs: Long,
    val finalized: Boolean,
    val error: String?
)

internal class Pure10Encoder(
    private val outputFile: File,
    private val sourceSize: Size,
    private val outputSize: Size,
    private val fps: Int,
    private val bitRate: Int,
    private val rotationDegrees: Int,
    private val characteristics: CameraCharacteristics,
    private val codecName: String = CODEC_NAME
) {
    private var cameraRgbToRec2020 = Pixel6MainDngColorProfile.CAMERA_RGB_TO_REC2020_D65.copyOf()
    private var matrixRedGain = Float.NaN
    private var matrixGreenEvenGain = Float.NaN
    private var matrixGreenOddGain = Float.NaN
    private var matrixBlueGain = Float.NaN
    private var captureLensShading: LensShadingSample? = null
    private val outputExecutor = Executors.newSingleThreadExecutor()
    private val outputFinished = CountDownLatch(1)
    private val submittedFrames = AtomicInteger()
    private val encodedFrames = AtomicInteger()
    private val encoderDrops = AtomicInteger()
    private val queueTotalNs = AtomicLong()
    private val queueMaxNs = AtomicLong()
    private val conversionTotalNs = AtomicLong()
    private val conversionMaxNs = AtomicLong()
    private val processingTotalNs = AtomicLong()
    private val processingMaxNs = AtomicLong()
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
    private var cropX = 0
    private var cropY = 0
    private val blackLevel = characteristics
        .get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        ?.let { pattern ->
            (
                pattern.getOffsetForIndex(0, 0) +
                    pattern.getOffsetForIndex(1, 0) +
                    pattern.getOffsetForIndex(0, 1) +
                    pattern.getOffsetForIndex(1, 1)
                ) / 4
        } ?: 64
    private val whiteLevel = characteristics
        .get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
    private val colorFilterArrangement = characteristics.get(
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
    ) ?: CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB

    fun start() {
        require(outputSize.width <= PROFILE.pure10MaximumWidth &&
            outputSize.height <= PROFILE.pure10MaximumHeight
        ) {
            "PURE 10 requiere la resolución RAW10 reducida"
        }
        require(outputSize.width <= sourceSize.width && outputSize.height <= sourceSize.height) {
            "La salida HEVC no puede exceder la fuente RAW10"
        }
        cropX = ((sourceSize.width - outputSize.width) / 2) and -2
        cropY = ((sourceSize.height - outputSize.height) / 2) and -2
        val localCodec = MediaCodec.createByCodecName(codecName)
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            outputSize.width,
            outputSize.height
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
            outputSize.width * 2
        }
        inputSliceHeight = if (inputFormat.containsKey(MediaFormat.KEY_SLICE_HEIGHT)) {
            inputFormat.getInteger(MediaFormat.KEY_SLICE_HEIGHT)
        } else {
            outputSize.height
        }
        require(inputRowStrideBytes >= outputSize.width * 2 && inputSliceHeight >= outputSize.height) {
            "Geometría P010 inválida del encoder"
        }
        minimumInputBufferBytes = (
            inputRowStrideBytes.toLong() * inputSliceHeight +
                inputRowStrideBytes.toLong() * (outputSize.height / 2 - 1) +
                outputSize.width.toLong() * 2
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
        greenEvenGain: Float,
        greenOddGain: Float,
        blueGain: Float,
        lensShading: LensShadingSample?,
        exposureGain: Float,
        queuedAtNs: Long
    ): Boolean {
        if (finishing || image.format != ImageFormat.RAW10) return false
        val processingStartedNs = System.nanoTime()
        val queueNs = (processingStartedNs - queuedAtNs).coerceAtLeast(0L)
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
            if (captureLensShading == null && lensShading != null) {
                captureLensShading = lensShading
            }
            val stableLensShading = captureLensShading
            updateColorTransform(redGain, greenEvenGain, greenOddGain, blueGain)
            output.clear()
            val conversionNs = NativeTenBitConverter.raw10ToP010Hlg(
                raw = raw,
                rawOffset = raw.position(),
                rawLimit = raw.limit(),
                rawRowStride = plane.rowStride,
                output = output,
                sourceWidth = sourceSize.width,
                sourceHeight = sourceSize.height,
                cropX = cropX,
                cropY = cropY,
                outputWidth = outputSize.width,
                outputHeight = outputSize.height,
                outputRowStrideBytes = inputRowStrideBytes,
                outputSliceHeight = inputSliceHeight,
                colorFilterArrangement = colorFilterArrangement,
                blackLevel = blackLevel,
                whiteLevel = whiteLevel,
                exposureGain = exposureGain.coerceIn(0.25f, 8f),
                redGain = redGain,
                greenEvenGain = greenEvenGain,
                greenOddGain = greenOddGain,
                blueGain = blueGain,
                cameraRgbToRec2020 = cameraRgbToRec2020,
                lensShadingGains = stableLensShading?.gains,
                lensShadingRows = stableLensShading?.rows ?: 0,
                lensShadingColumns = stableLensShading?.columns ?: 0
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
                val processingNs = System.nanoTime() - processingStartedNs
                queueTotalNs.addAndGet(queueNs)
                queueMaxNs.updateAndGet { previous -> max(previous, queueNs) }
                processingTotalNs.addAndGet(processingNs)
                processingMaxNs.updateAndGet { previous -> max(previous, processingNs) }
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

    private fun updateColorTransform(
        redGain: Float,
        greenEvenGain: Float,
        greenOddGain: Float,
        blueGain: Float
    ) {
        if (!gainChanged(redGain, matrixRedGain) &&
            !gainChanged(greenEvenGain, matrixGreenEvenGain) &&
            !gainChanged(greenOddGain, matrixGreenOddGain) &&
            !gainChanged(blueGain, matrixBlueGain)
        ) return
        cameraRgbToRec2020 = Pixel6MainDngColorProfile.whiteBalancedCameraRgbToRec2020(
            redGain,
            greenEvenGain,
            greenOddGain,
            blueGain
        )
        matrixRedGain = redGain
        matrixGreenEvenGain = greenEvenGain
        matrixGreenOddGain = greenOddGain
        matrixBlueGain = blueGain
    }

    private fun gainChanged(current: Float, previous: Float): Boolean =
        !previous.isFinite() || abs(current - previous) >= COLOR_GAIN_UPDATE_THRESHOLD

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
            queueAverageMs = if (submitted > 0) {
                queueTotalNs.get() / submitted / 1_000_000.0
            } else {
                0.0
            },
            queueMaxMs = queueMaxNs.get() / 1_000_000.0,
            conversionAverageMs = if (submitted > 0) {
                conversionTotalNs.get() / submitted / 1_000_000.0
            } else {
                0.0
            },
            conversionMaxMs = conversionMaxNs.get() / 1_000_000.0,
            processingAverageMs = if (submitted > 0) {
                processingTotalNs.get() / submitted / 1_000_000.0
            } else {
                0.0
            },
            processingMaxMs = processingMaxNs.get() / 1_000_000.0,
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
        private val PROFILE = Pixel6ReferenceProfile.capture
        const val VIDEO_FILE = "pure10.mp4"
        val BIT_RATE = PROFILE.pure10BitRate
        val CODEC_NAME = PROFILE.preferredHevcMain10Encoder
        private const val TAG = "RawLitePure10"
        private const val INPUT_TIMEOUT_US = 5_000L
        private const val FINISH_TIMEOUT_SECONDS = 15L
        private const val COLOR_GAIN_UPDATE_THRESHOLD = 0.0005f
    }
}
