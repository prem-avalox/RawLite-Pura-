package com.example.rawlite.device.benchmark

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import com.example.rawlite.device.Dimensions
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class SensorCadenceBenchmark(
    private val context: Context,
    private val cameraManager: CameraManager
) {
    fun run(
        cameraId: String,
        dimensions: Dimensions,
        targetFps: Int,
        durationSeconds: Double,
        declaredMaximumFps: Double?
    ): SensorCadenceResult {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return failed(cameraId, null, dimensions, targetFps, declaredMaximumFps, "Sin permiso de cámara")
        }
        val target = runCatching { resolveTarget(cameraId, dimensions) }.getOrElse { error ->
            return failed(
                cameraId,
                null,
                dimensions,
                targetFps,
                declaredMaximumFps,
                error.message ?: error.javaClass.simpleName
            )
        }
        val run = CadenceRun(target, targetFps, durationSeconds)
        return run.execute(declaredMaximumFps)
    }

    private fun resolveTarget(cameraId: String, dimensions: Dimensions): CameraTarget {
        cameraManager.cameraIdList.forEach { logicalId ->
            val logical = cameraManager.getCameraCharacteristics(logicalId)
            val physicalId = when {
                logicalId == cameraId -> null
                logical.physicalCameraIds.contains(cameraId) -> cameraId
                else -> return@forEach
            }
            val characteristics = if (physicalId == null) {
                logical
            } else {
                cameraManager.getCameraCharacteristics(physicalId)
            }
            val rawSizes = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )?.getOutputSizes(ImageFormat.RAW10).orEmpty()
            check(rawSizes.any { it.width == dimensions.width && it.height == dimensions.height }) {
                "La cámara $cameraId no expone RAW10 ${dimensions.width}x${dimensions.height}"
            }
            return CameraTarget(
                logicalId = logicalId,
                physicalId = physicalId,
                characteristics = characteristics,
                dimensions = dimensions,
                previewSize = choosePreviewSize(characteristics)
            )
        }
        error("No se encontró una ruta lógica hacia la cámara $cameraId")
    }

    private fun choosePreviewSize(characteristics: CameraCharacteristics): Size {
        val sizes = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        return sizes.minByOrNull { size ->
            kotlin.math.abs(size.width.toLong() * size.height - 640L * 480L)
        } ?: error("La cámara no expone una salida YUV de previsualización")
    }

    private inner class CadenceRun(
        private val target: CameraTarget,
        private val targetFps: Int,
        private val durationSeconds: Double
    ) {
        private val thread = HandlerThread("RawLiteHalCadence").apply { start() }
        private val handler = Handler(thread.looper)
        private val executor = Executor { command -> handler.post(command) }
        private val finished = AtomicBoolean(false)
        private val latch = CountDownLatch(1)
        private val timestampsNs = Collections.synchronizedList(mutableListOf<Long>())
        private val failure = AtomicReference<String?>(null)
        @Volatile private var rowStrideBytes = 0
        private var firstObservedTimestampNs = 0L
        private var measurementStartTimestampNs = 0L
        private var camera: CameraDevice? = null
        private var session: CameraCaptureSession? = null
        private var rawReader: ImageReader? = null
        private var previewReader: ImageReader? = null

        fun execute(declaredMaximumFps: Double?): SensorCadenceResult {
            return try {
                handler.post(::openCamera)
                val timeoutSeconds = durationSeconds.toLong() + 8L
                if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                    finish("Tiempo de espera agotado")
                    latch.await(2L, TimeUnit.SECONDS)
                }
                val timestamps = synchronized(timestampsNs) { timestampsNs.toList() }
                val (achievedFps, inferredLosses, distribution) = BenchmarkMath.cadence(
                    timestamps,
                    targetFps
                )
                val measuredDuration = if (timestamps.size >= 2) {
                    (timestamps.last() - timestamps.first()) / 1_000_000_000.0
                } else {
                    0.0
                }
                val error = failure.get()
                SensorCadenceResult(
                    cameraId = target.logicalId,
                    physicalCameraId = target.physicalId,
                    dimensions = target.dimensions,
                    requestedFps = targetFps,
                    declaredMaximumFps = declaredMaximumFps,
                    receivedFrames = timestamps.size,
                    durationSeconds = measuredDuration,
                    achievedFps = achievedFps,
                    inferredLostFrames = inferredLosses,
                    intervals = distribution,
                    state = if (error == null) {
                        ModeCertificationPolicy.sensorState(
                            requestedFps = targetFps,
                            receivedFrames = timestamps.size,
                            durationSeconds = measuredDuration,
                            achievedFps = achievedFps,
                            inferredLostFrames = inferredLosses,
                            intervalP99Ms = distribution.p99Ms
                        )
                    } else {
                        CertificationState.REJECTED
                    },
                    rowStrideBytes = rowStrideBytes,
                    error = error
                )
            } catch (error: Throwable) {
                failed(
                    target.logicalId,
                    target.physicalId,
                    target.dimensions,
                    targetFps,
                    declaredMaximumFps,
                    error.message ?: error.javaClass.simpleName
                )
            } finally {
                finish(null)
                thread.quitSafely()
                thread.join(2_000L)
            }
        }

        private fun openCamera() {
            try {
                if (context.checkSelfPermission(Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    finish("Sin permiso de cámara")
                    return
                }
                cameraManager.openCamera(target.logicalId, executor, cameraCallback)
            } catch (error: Throwable) {
                finish(error.message ?: error.javaClass.simpleName)
            }
        }

        private val cameraCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                configureSession(device)
            }

            override fun onDisconnected(device: CameraDevice) = finish("Cámara desconectada")

            override fun onError(device: CameraDevice, error: Int) =
                finish("Camera2 error $error")
        }

        private fun configureSession(device: CameraDevice) {
            try {
                rawReader = ImageReader.newInstance(
                    target.dimensions.width,
                    target.dimensions.height,
                    ImageFormat.RAW10,
                    5
                ).apply {
                    setOnImageAvailableListener({ reader -> consumeRaw(reader) }, handler)
                }
                previewReader = ImageReader.newInstance(
                    target.previewSize.width,
                    target.previewSize.height,
                    ImageFormat.YUV_420_888,
                    3
                ).apply {
                    setOnImageAvailableListener({ reader ->
                        reader.acquireLatestImage()?.close()
                    }, handler)
                }
                val outputs = listOf(
                    outputConfiguration(requireNotNull(rawReader).surface),
                    outputConfiguration(requireNotNull(previewReader).surface)
                )
                device.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputs,
                        executor,
                        sessionCallback
                    )
                )
            } catch (error: Throwable) {
                finish(error.message ?: error.javaClass.simpleName)
            }
        }

        private fun outputConfiguration(surface: android.view.Surface): OutputConfiguration =
            OutputConfiguration(surface).apply {
                target.physicalId?.let(::setPhysicalCameraId)
            }

        private val sessionCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                session = configured
                try {
                    val request = requireNotNull(camera).createCaptureRequest(
                        CameraDevice.TEMPLATE_RECORD
                    ).apply {
                        addTarget(requireNotNull(rawReader).surface)
                        addTarget(requireNotNull(previewReader).surface)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNs())
                        set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / targetFps)
                        set(CaptureRequest.SENSOR_SENSITIVITY, sensitivity())
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        chooseFpsRange()?.let {
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                        }
                    }.build()
                    configured.setRepeatingRequest(request, null, handler)
                } catch (error: Throwable) {
                    finish(error.message ?: error.javaClass.simpleName)
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) =
                finish("El HAL rechazó la combinación RAW10 + preview")
        }

        private fun consumeRaw(reader: ImageReader) {
            val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
            try {
                if (finished.get()) return
                if (rowStrideBytes == 0) rowStrideBytes = image.planes.firstOrNull()?.rowStride ?: 0
                if (firstObservedTimestampNs == 0L) firstObservedTimestampNs = image.timestamp
                if (image.timestamp - firstObservedTimestampNs < WARMUP_DURATION_NS) return
                if (measurementStartTimestampNs == 0L) {
                    measurementStartTimestampNs = image.timestamp
                }
                timestampsNs += image.timestamp
                if (image.timestamp - measurementStartTimestampNs >=
                    (durationSeconds * 1_000_000_000.0).toLong()
                ) {
                    finish(null)
                }
            } finally {
                image.close()
            }
        }

        private fun exposureTimeNs(): Long {
            val range = target.characteristics.get(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
            ) ?: return 1_000_000_000L / (targetFps * 2L)
            return (1_000_000_000L / (targetFps * 2L)).coerceIn(range.lower, range.upper)
        }

        private fun sensitivity(): Int {
            val range = target.characteristics.get(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            ) ?: return 200
            return 200.coerceIn(range.lower, range.upper)
        }

        private fun chooseFpsRange(): Range<Int>? = target.characteristics.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        )?.filter { it.lower <= targetFps && it.upper >= targetFps }
            ?.minWithOrNull(compareBy<Range<Int>> { it.upper - it.lower }.thenBy { it.upper })

        private fun finish(message: String?) {
            if (message != null) failure.compareAndSet(null, message)
            if (!finished.compareAndSet(false, true)) return
            runCatching { session?.stopRepeating() }
            runCatching { session?.abortCaptures() }
            session?.close()
            session = null
            rawReader?.setOnImageAvailableListener(null, null)
            previewReader?.setOnImageAvailableListener(null, null)
            rawReader?.close()
            previewReader?.close()
            rawReader = null
            previewReader = null
            camera?.close()
            camera = null
            latch.countDown()
        }
    }

    private data class CameraTarget(
        val logicalId: String,
        val physicalId: String?,
        val characteristics: CameraCharacteristics,
        val dimensions: Dimensions,
        val previewSize: Size
    )

    private fun failed(
        cameraId: String,
        physicalCameraId: String?,
        dimensions: Dimensions,
        targetFps: Int,
        declaredMaximumFps: Double?,
        message: String
    ): SensorCadenceResult = SensorCadenceResult(
        cameraId = cameraId,
        physicalCameraId = physicalCameraId,
        dimensions = dimensions,
        requestedFps = targetFps,
        declaredMaximumFps = declaredMaximumFps,
        receivedFrames = 0,
        durationSeconds = 0.0,
        achievedFps = 0.0,
        inferredLostFrames = 0,
        intervals = Distribution.fromNanoseconds(emptyList()),
        state = CertificationState.REJECTED,
        error = message
    )

    companion object {
        private const val WARMUP_DURATION_NS = 1_000_000_000L

        fun unavailable(
            cameraId: String,
            dimensions: Dimensions,
            targetFps: Int,
            declaredMaximumFps: Double?,
            message: String
        ): SensorCadenceResult = SensorCadenceResult(
            cameraId = cameraId,
            physicalCameraId = null,
            dimensions = dimensions,
            requestedFps = targetFps,
            declaredMaximumFps = declaredMaximumFps,
            receivedFrames = 0,
            durationSeconds = 0.0,
            achievedFps = 0.0,
            inferredLostFrames = 0,
            intervals = Distribution.fromNanoseconds(emptyList()),
            state = CertificationState.UNAVAILABLE,
            error = message
        )
    }
}
