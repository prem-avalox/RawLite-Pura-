package com.example.rawlite

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import android.widget.TextView
import com.example.rawlite.core.config.Pixel6ReferenceProfile
import com.example.rawlite.gallery.Pixel6MainDngColorProfile
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

class TenBitCameraProbeActivity : Activity() {
    private data class CameraTarget(
        val label: String,
        val logicalId: String,
        val physicalId: String,
        val characteristics: CameraCharacteristics,
        val rawSize: Size
    )

    private lateinit var status: TextView
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private lateinit var codecThread: HandlerThread
    private lateinit var codecHandler: Handler
    private lateinit var outputDirectory: File
    private lateinit var outputFile: File
    private lateinit var reportFile: File
    private var rawReader: ImageReader? = null
    private var previewReader: ImageReader? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxerTrack = -1
    private var muxerStarted = false
    private val stopping = AtomicBoolean(false)
    private val receivedFrames = AtomicInteger()
    private val submittedFrames = AtomicInteger()
    private val droppedEncoderFrames = AtomicInteger()
    private val outputFrames = AtomicInteger()
    private val conversionTotalNs = AtomicLong()
    private val conversionMaxNs = AtomicLong()
    private val loggedInputGeometry = AtomicBoolean(false)
    private val firstTimestampNs = AtomicLong()
    private val lastTimestampNs = AtomicLong()
    private var temperatureBefore = 0.0
    private var thermalBefore = 0
    private var processCpuBeforeMs = 0L
    private lateinit var target: CameraTarget

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
            textSize = 18f
            text = "Preparando prueba RAW10 → HLG10…"
        }
        setContentView(status)
        cameraThread = HandlerThread("TenBitCamera").apply { start() }
        cameraHandler = Handler(cameraThread.looper)
        codecThread = HandlerThread("TenBitCodec").apply { start() }
        codecHandler = Handler(codecThread.looper)
        outputDirectory = File(getExternalFilesDir(null), "camera-10bit-probe").apply {
            deleteRecursively()
            mkdirs()
        }
        outputFile = File(outputDirectory, "gn1-raw10-hlg10.mp4")
        reportFile = File(outputDirectory, "report.json")
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            fail("Permiso de cámara no concedido")
            return
        }
        cameraHandler.post { startProbe() }
    }

    private fun startProbe() {
        try {
            if (checkSelfPermission(Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                fail("Permiso de cámara no concedido")
                return
            }
            val manager = getSystemService(CameraManager::class.java)
            target = findCamera(manager, intent.getBooleanExtra("ultrawide", false))
            temperatureBefore = batteryTemperatureC()
            thermalBefore = currentThermalStatus()
            processCpuBeforeMs = Process.getElapsedCpuTime()
            startEncoder(target.rawSize)
            updateStatus("GN1 ${target.rawSize.width}×${target.rawSize.height} · 24 FPS\nCodificando HLG10…")
            manager.openCamera(target.logicalId, cameraExecutor, cameraCallback)
        } catch (error: Throwable) {
            fail("No se pudo iniciar: ${error.message}", error)
        }
    }

    private fun findCamera(manager: CameraManager, ultrawide: Boolean): CameraTarget {
        for (logicalId in manager.cameraIdList) {
            val logical = manager.getCameraCharacteristics(logicalId)
            if (logical.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            for (physicalId in logical.physicalCameraIds) {
                val chars = manager.getCameraCharacteristics(physicalId)
                val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull() ?: continue
                if (ultrawide == (focal >= 5f)) continue
                val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.RAW10)
                    ?.filter { kotlin.math.abs(it.width.toDouble() / it.height - 4.0 / 3.0) < 0.03 }
                    .orEmpty()
                val reduced = sizes.filter { it.width in 1800..2500 }
                    .maxByOrNull { it.width.toLong() * it.height }
                    ?: error("El GN1 no expone RAW10 reducido")
                return CameraTarget(
                    if (ultrawide) "Ultra gran angular" else "GN1",
                    logicalId,
                    physicalId,
                    chars,
                    reduced
                )
            }
        }
        error("No se encontró la cámara física solicitada")
    }

    private fun startEncoder(size: Size) {
        val codec = MediaCodec.createByCodecName(PROFILE.preferredHevcMain10Encoder)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        Log.i(TAG, "Encoder input format: ${codec.inputFormat}")
        encoder = codec
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        codecHandler.post { drainEncoder() }
    }

    private fun configureCamera(device: CameraDevice) {
        val size = target.rawSize
        rawReader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW10, 8).apply {
            setOnImageAvailableListener({ reader -> consumeRaw(reader) }, cameraHandler)
        }
        previewReader = ImageReader.newInstance(960, 540, ImageFormat.YUV_420_888, 3).apply {
            setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.close() }, cameraHandler)
        }
        val rawSurface = requireNotNull(rawReader).surface
        val previewSurface = requireNotNull(previewReader).surface
        val rawOutput = OutputConfiguration(rawSurface).apply { setPhysicalCameraId(target.physicalId) }
        val previewOutput = OutputConfiguration(previewSurface).apply { setPhysicalCameraId(target.physicalId) }
        val configuration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(rawOutput, previewOutput),
            cameraExecutor,
            sessionCallback
        )
        device.createCaptureSession(configuration)
    }

    private fun consumeRaw(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return
        try {
            if (stopping.get()) return
            val timestamp = image.timestamp
            if (firstTimestampNs.compareAndSet(0L, timestamp)) {
                lastTimestampNs.set(timestamp)
            }
            receivedFrames.incrementAndGet()
            lastTimestampNs.set(timestamp)
            if (timestamp - firstTimestampNs.get() >= TEST_DURATION_NS) {
                stopProbe()
                return
            }

            val codec = encoder ?: return
            val inputIndex = codec.dequeueInputBuffer(5_000)
            if (inputIndex < 0) {
                droppedEncoderFrames.incrementAndGet()
                return
            }
            val output = requireNotNull(codec.getInputBuffer(inputIndex))
            if (loggedInputGeometry.compareAndSet(false, true)) {
                Log.i(
                    TAG,
                    "Encoder input capacity=${output.capacity()} width=${image.width} height=${image.height} " +
                        "bytes=${image.width * image.height * 3} format=${codec.inputFormat}"
                )
            }
            val plane = image.planes[0]
            val raw = plane.buffer
            output.clear()
            val inputFormat = codec.inputFormat
            val outputRowStrideBytes = inputFormat.getInteger(MediaFormat.KEY_STRIDE)
            val outputSliceHeight = inputFormat.getInteger(MediaFormat.KEY_SLICE_HEIGHT)
            val outputBytes = outputRowStrideBytes * outputSliceHeight +
                outputRowStrideBytes * (image.height / 2 - 1) + image.width * 2
            val chars = target.characteristics
            val blackPattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            val black = if (blackPattern != null) {
                (blackPattern.getOffsetForIndex(0, 0) + blackPattern.getOffsetForIndex(1, 0) +
                    blackPattern.getOffsetForIndex(0, 1) + blackPattern.getOffsetForIndex(1, 1)) / 4
            } else 64
            val white = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
            val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
            val conversionNs = NativeTenBitConverter.raw10ToP010Hlg(
                raw,
                raw.position(),
                raw.limit(),
                plane.rowStride,
                output,
                sourceWidth = image.width,
                sourceHeight = image.height,
                cropX = 0,
                cropY = 0,
                outputWidth = image.width,
                outputHeight = image.height,
                outputRowStrideBytes,
                outputSliceHeight,
                cfa,
                black,
                white,
                exposureGain = 1.0f,
                redGain = 2.0f,
                greenEvenGain = 1.0f,
                greenOddGain = 1.0f,
                blueGain = 1.6f,
                cameraRgbToRec2020 = Pixel6MainDngColorProfile.whiteBalancedCameraRgbToRec2020(
                    redGain = 2.0f,
                    greenEvenGain = 1.0f,
                    greenOddGain = 1.0f,
                    blueGain = 1.6f
                )
            )
            if (conversionNs < 0) {
                droppedEncoderFrames.incrementAndGet()
                codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                return
            }
            conversionTotalNs.addAndGet(conversionNs)
            conversionMaxNs.updateAndGet { previous -> max(previous, conversionNs) }
            val presentationUs = (timestamp - firstTimestampNs.get()).coerceAtLeast(0L) / 1_000L
            codec.queueInputBuffer(
                inputIndex,
                0,
                outputBytes,
                presentationUs,
                0
            )
            submittedFrames.incrementAndGet()
        } catch (error: Throwable) {
            Log.e(TAG, "RAW conversion failed", error)
            droppedEncoderFrames.incrementAndGet()
        } finally {
            image.close()
        }
    }

    private fun drainEncoder() {
        val codec = encoder ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val localMuxer = requireNotNull(muxer)
                        muxerTrack = localMuxer.addTrack(codec.outputFormat)
                        localMuxer.start()
                        muxerStarted = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (stopping.get()) continue
                    }
                    else -> if (index >= 0) {
                        val buffer = requireNotNull(codec.getOutputBuffer(index))
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            requireNotNull(muxer).writeSampleData(muxerTrack, buffer, info)
                            outputFrames.incrementAndGet()
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                        if (eos) break
                    }
                }
            }
            finishProbe()
        } catch (error: Throwable) {
            fail("Error drenando HEVC: ${error.message}", error)
        }
    }

    private fun stopProbe() {
        if (!stopping.compareAndSet(false, true)) return
        updateStatus("Finalizando y verificando…")
        session?.close()
        session = null
        rawReader?.setOnImageAvailableListener(null, null)
        previewReader?.setOnImageAvailableListener(null, null)
        cameraHandler.post { queueEncoderEos() }
    }

    private fun queueEncoderEos() {
        val codec = encoder ?: return
        repeat(200) {
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val timestamp = lastTimestampNs.get() - firstTimestampNs.get()
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    timestamp.coerceAtLeast(0L) / 1_000L + 1,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                return
            }
        }
        fail("No se pudo enviar EOS al encoder")
    }

    private fun finishProbe() {
        val durationNs = (lastTimestampNs.get() - firstTimestampNs.get()).coerceAtLeast(1L)
        val received = receivedFrames.get()
        val submitted = submittedFrames.get()
        val outputCount = outputFrames.get()
        val report = JSONObject().apply {
            put("camera", target.label)
            put("physical_id", target.physicalId)
            put("width", target.rawSize.width)
            put("height", target.rawSize.height)
            put("target_fps", FPS)
            put("duration_s", durationNs / 1_000_000_000.0)
            put("received_frames", received)
            put("submitted_frames", submitted)
            put("encoder_drops", droppedEncoderFrames.get())
            put("output_frames", outputCount)
            put("received_fps", received * 1_000_000_000.0 / durationNs)
            put("output_fps", outputCount * 1_000_000_000.0 / durationNs)
            put("conversion_average_ms", if (submitted > 0) conversionTotalNs.get() / submitted / 1_000_000.0 else 0.0)
            put("conversion_max_ms", conversionMaxNs.get() / 1_000_000.0)
            put("process_cpu_ms", Process.getElapsedCpuTime() - processCpuBeforeMs)
            put("temperature_before_c", temperatureBefore)
            put("temperature_after_c", batteryTemperatureC())
            put("thermal_status_before", thermalBefore)
            put("thermal_status_after", currentThermalStatus())
            put("file_bytes", outputFile.length())
            put("actual_mbps", outputFile.length() * 8.0 / durationNs * 1_000.0)
            put("requested_mbps", BIT_RATE / 1_000_000)
            put("codec", PROFILE.preferredHevcMain10Encoder)
            put("format", "HEVC Main10 P010 BT.2020 HLG")
        }
        reportFile.writeText(report.toString(2))
        closeResources()
        Log.i(TAG, "Camera probe finished: $report")
        updateStatus(
            "Prueba terminada\n" +
                "${"%.2f".format(report.getDouble("output_fps"))} FPS · " +
                "${report.getInt("encoder_drops")} pérdidas\n" +
                "${"%.1f".format(report.getDouble("conversion_average_ms"))} ms conversión · " +
                "${"%.1f".format(report.getDouble("actual_mbps"))} Mb/s"
        )
    }

    private fun currentThermalStatus(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(PowerManager::class.java).currentThermalStatus
        } else {
            0
        }
    }

    private fun closeResources() {
        try { encoder?.stop() } catch (_: Throwable) {}
        encoder?.release()
        encoder = null
        try { if (muxerStarted) muxer?.stop() } catch (_: Throwable) {}
        muxer?.release()
        muxer = null
        rawReader?.close()
        previewReader?.close()
        camera?.close()
        camera = null
    }

    private fun fail(message: String, error: Throwable? = null) {
        if (error != null) Log.e(TAG, message, error) else Log.e(TAG, message)
        stopping.set(true)
        closeResources()
        updateStatus(message)
    }

    private fun updateStatus(message: String) {
        runOnUiThread { status.text = message }
    }

    private fun batteryTemperatureC(): Double {
        val battery = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return battery?.getIntExtra("temperature", 0)?.div(10.0) ?: 0.0
    }

    override fun onDestroy() {
        if (!stopping.get()) stopping.set(true)
        closeResources()
        if (::cameraThread.isInitialized) cameraThread.quitSafely()
        if (::codecThread.isInitialized) codecThread.quitSafely()
        super.onDestroy()
    }

    private val cameraExecutor by lazy { Executor { command -> cameraHandler.post(command) } }
    private val cameraCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            camera = device
            configureCamera(device)
        }

        override fun onDisconnected(device: CameraDevice) = fail("Cámara desconectada")
        override fun onError(device: CameraDevice, error: Int) = fail("Error de cámara: $error")
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(configured: CameraCaptureSession) {
            session = configured
            val request = requireNotNull(camera).createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(requireNotNull(rawReader).surface)
                addTarget(requireNotNull(previewReader).surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, 1_000_000_000L / 48L)
                set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / FPS)
                set(CaptureRequest.SENSOR_SENSITIVITY, 200)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(FPS, FPS))
            }.build()
            configured.setRepeatingRequest(request, null, cameraHandler)
        }

        override fun onConfigureFailed(session: CameraCaptureSession) = fail("Falló la sesión Camera2")
    }

    companion object {
        private val PROFILE = Pixel6ReferenceProfile.capture
        private const val TAG = "RawLite10BitCamera"
        private const val FPS = 24
        private val BIT_RATE = PROFILE.pure10BitRate
        private const val TEST_DURATION_NS = 10_000_000_000L
    }
}
