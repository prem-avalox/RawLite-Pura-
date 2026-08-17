package com.example.rawlite.device.benchmark

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import com.example.rawlite.core.config.Pixel6ReferenceProfile
import com.example.rawlite.device.AndroidDeviceCapabilityProbe
import com.example.rawlite.device.CameraCapability
import com.example.rawlite.device.DeviceCapabilityProfile
import com.example.rawlite.device.Dimensions
import com.example.rawlite.device.StreamFormat

internal class DeviceBenchmarkRunner(
    private val context: Context,
    private val cameraManager: CameraManager,
    private val cancelled: () -> Boolean = { false }
) {
    fun run(
        request: BenchmarkRequest,
        onProgress: (String) -> Unit = {}
    ): DeviceBenchmarkReport {
        val temperatureBefore = batteryTemperatureC()
        val thermalBefore = thermalStatus()
        val storageBefore = storageSnapshot()
        val profile = AndroidDeviceCapabilityProbe(cameraManager).probe()
        val completed = linkedSetOf(BenchmarkStage.HAL)
        val cadenceResults = mutableListOf<SensorCadenceResult>()
        val storageResults = mutableListOf<StorageBenchmarkResult>()
        val ispResults = mutableListOf<IspBenchmarkResult>()
        val encoderResults = mutableListOf<EncoderBenchmarkResult>()
        val sensorTargets = SensorSweepPlanner.targets(profile)
        val primaryTarget = SensorSweepPlanner.primaryBackTarget(sensorTargets)
        val primaryRawSize = primaryTarget?.dimensions

        BenchmarkWorkspace(context).use { workspace ->
            if (BenchmarkStage.SENSOR_CADENCE in request.stages && sensorTargets.isNotEmpty()) {
                val cadence = SensorCadenceBenchmark(context, cameraManager)
                sensorTargets.forEach { target ->
                    request.frameRates.forEach fpsLoop@ { fps ->
                        if (cancelled()) return@fpsLoop
                        val declaredMaximum = target.camera.streams.firstOrNull {
                            it.format == StreamFormat.RAW10 && it.dimensions == target.dimensions
                        }?.maximumDeclaredFps
                        if (declaredMaximum != null && declaredMaximum + FPS_TOLERANCE < fps) {
                            cadenceResults += SensorCadenceBenchmark.unavailable(
                                cameraId = target.camera.cameraId,
                                dimensions = target.dimensions,
                                targetFps = fps,
                                declaredMaximumFps = declaredMaximum,
                                message = "El stream declara un máximo de ${"%.2f".format(declaredMaximum)} FPS"
                            )
                            return@fpsLoop
                        }
                        onProgress(
                            "Sensor ${target.camera.cameraId} RAW10 " +
                                "${target.dimensions.width}x${target.dimensions.height} ${fps} FPS…"
                        )
                        cadenceResults += cadence.run(
                            cameraId = target.camera.cameraId,
                            dimensions = target.dimensions,
                            targetFps = fps,
                            durationSeconds = request.durationSeconds,
                            declaredMaximumFps = declaredMaximum
                        )
                    }
                }
                completed += BenchmarkStage.SENSOR_CADENCE
            }

            if (!cancelled() && BenchmarkStage.STORAGE in request.stages && sensorTargets.isNotEmpty()) {
                sensorTargets.map { it.dimensions }.distinct().forEach dimensionsLoop@ { dimensions ->
                    request.frameRates.forEach fpsLoop@ { fps ->
                        if (cancelled()) return@fpsLoop
                        onProgress(
                            "Midiendo escritura RAW10 ${dimensions.width}x${dimensions.height} ${fps} FPS…"
                        )
                        storageResults += StorageBenchmark(workspace.directory).run(
                            dimensions = dimensions,
                            targetFps = fps,
                            durationSeconds = request.durationSeconds,
                            inputRowStrideBytes = cadenceResults.firstOrNull {
                                it.dimensions == dimensions && it.rowStrideBytes > 0
                            }?.rowStrideBytes
                        )
                    }
                }
                completed += BenchmarkStage.STORAGE
            }

            val hlgOutputs = primaryRawSize?.let(::chooseHlgOutputs).orEmpty()
            if (!cancelled() && BenchmarkStage.ISP in request.stages &&
                primaryRawSize != null && hlgOutputs.isNotEmpty()
            ) {
                val isp = IspBenchmark()
                hlgOutputs.forEach { output ->
                    request.frameRates.forEach { fps ->
                        if (cancelled()) return@forEach
                        onProgress("Midiendo ISP ${output.width}x${output.height} ${fps} FPS…")
                        ispResults += isp.run(
                            source = primaryRawSize,
                            output = output,
                            targetFps = fps,
                            durationSeconds = request.durationSeconds
                        )
                    }
                }
                completed += BenchmarkStage.ISP
            }

            val encoder = profile.encoders.firstOrNull {
                it.hardwareAccelerated && it.hevcMain10 && it.p010
            }
            if (!cancelled() && BenchmarkStage.ENCODER in request.stages &&
                encoder != null && hlgOutputs.isNotEmpty()
            ) {
                hlgOutputs.forEach { output ->
                    request.frameRates.forEach { fps ->
                        if (cancelled()) return@forEach
                        onProgress("Midiendo HEVC ${output.width}x${output.height} ${fps} FPS…")
                        encoderResults += EncoderBenchmark().run(
                            codecName = encoder.name,
                            dimensions = output,
                            targetFps = fps,
                            bitRate = Pixel6ReferenceProfile.capture.pure10BitRate,
                            durationSeconds = request.durationSeconds
                        )
                    }
                }
                completed += BenchmarkStage.ENCODER
            }
        }

        val interrupted = cancelled()
        val storageAfter = storageSnapshot()
        return DeviceBenchmarkReport(
            schemaVersion = DeviceBenchmarkReport.CURRENT_SCHEMA_VERSION,
            generatedAtEpochMs = System.currentTimeMillis(),
            profile = profile,
            temperatureBeforeC = temperatureBefore,
            temperatureAfterC = batteryTemperatureC(),
            thermalStatusBefore = thermalBefore,
            thermalStatusAfter = thermalStatus(),
            availableStorageBeforeBytes = storageBefore.availableBytes,
            availableStorageAfterBytes = storageAfter.availableBytes,
            totalStorageBytes = storageBefore.totalBytes,
            sensorCadence = cadenceResults,
            storage = storageResults,
            isp = ispResults,
            encoders = encoderResults,
            modes = certifyModes(
                request,
                sensorTargets,
                primaryTarget,
                cadenceResults,
                storageResults,
                ispResults,
                encoderResults
            ),
            completedStages = completed,
            interrupted = interrupted
        )
    }

    private fun chooseHlgOutputs(source: Dimensions): List<Dimensions> = buildList {
        if (source.width % 2 == 0 && source.height % 2 == 0) add(source)
        if (source.width >= 1920 && source.height >= 1080) add(Dimensions(1920, 1080))
    }.distinct()

    private fun certifyModes(
        request: BenchmarkRequest,
        sensorTargets: List<SensorSweepTarget>,
        primaryTarget: SensorSweepTarget?,
        cadence: List<SensorCadenceResult>,
        storage: List<StorageBenchmarkResult>,
        isp: List<IspBenchmarkResult>,
        encoders: List<EncoderBenchmarkResult>
    ): List<ModeCertification> {
        if (sensorTargets.isEmpty()) {
            return listOf(
                ModeCertification(
                    id = "RAW10",
                    state = CertificationState.UNAVAILABLE,
                    limitingStage = BenchmarkStage.HAL,
                    reason = "El HAL no declara una salida RAW10",
                    recordingTier = RecordingValidationTier.REJECTED
                )
            )
        }
        return buildList {
            sensorTargets.forEach { target ->
                request.frameRates.forEach { fps ->
                    val cadenceResult = cadence.firstOrNull {
                        it.sensorId == target.camera.cameraId && it.requestedFps == fps
                    }
                    val storageResult = storage.firstOrNull {
                        it.dimensions == target.dimensions && it.requestedFps == fps
                    }
                    add(componentCertification(
                        id = "RAW10 sensor ${target.camera.cameraId} " +
                            "${target.dimensions.width}x${target.dimensions.height} ${fps}p",
                        required = listOf(
                            BenchmarkStage.SENSOR_CADENCE to cadenceResult?.state,
                            BenchmarkStage.STORAGE to storageResult?.state
                        )
                    ))
                }
            }

            val primary = primaryTarget ?: return@buildList
            val hlgOutputs = chooseHlgOutputs(primary.dimensions)
            request.frameRates.forEach { fps ->
                val cadenceResult = cadence.firstOrNull {
                    it.sensorId == primary.camera.cameraId && it.requestedFps == fps
                }
                hlgOutputs.forEach { output ->
                    val ispResult = isp.firstOrNull {
                        it.requestedFps == fps && it.outputDimensions == output
                    }
                    val encoderResult = encoders.firstOrNull {
                        it.requestedFps == fps && it.dimensions == output
                    }
                    add(componentCertification(
                        id = "HLG10 sensor ${primary.camera.cameraId} " +
                            "${output.width}x${output.height} ${fps}p",
                        required = listOf(
                            BenchmarkStage.SENSOR_CADENCE to cadenceResult?.state,
                            BenchmarkStage.ISP to ispResult?.state,
                            BenchmarkStage.ENCODER to encoderResult?.state
                        )
                    ))
                }
            }
        }
    }

    private fun componentCertification(
        id: String,
        required: List<Pair<BenchmarkStage, CertificationState?>>
    ): ModeCertification {
        val unavailable = required.firstOrNull { it.second == CertificationState.UNAVAILABLE }
        if (unavailable != null) {
            return ModeCertification(
                id = id,
                state = CertificationState.UNAVAILABLE,
                limitingStage = unavailable.first,
                reason = "El HAL no declara esta combinación",
                recordingTier = RecordingValidationTier.REJECTED
            )
        }
        val rejected = required.firstOrNull { it.second == CertificationState.REJECTED }
        if (rejected != null) {
            return ModeCertification(
                id = id,
                state = CertificationState.REJECTED,
                limitingStage = rejected.first,
                reason = "La etapa ${rejected.first.name} no sostuvo la carga solicitada",
                recordingTier = RecordingValidationTier.REJECTED
            )
        }
        val missing = required.firstOrNull { it.second == null }
        if (missing != null) {
            return ModeCertification(
                id = id,
                state = CertificationState.DECLARED,
                limitingStage = missing.first,
                reason = "Pendiente medir ${missing.first.name}"
            )
        }
        val withoutMargin = required.firstOrNull { it.second == CertificationState.MEASURED }
        if (withoutMargin != null) {
            return ModeCertification(
                id = id,
                state = CertificationState.MEASURED,
                limitingStage = withoutMargin.first,
                reason = "La etapa ${withoutMargin.first.name} alcanza la carga, pero no el margen mínimo",
                recordingTier = RecordingValidationTier.NOT_TESTED
            )
        }
        return ModeCertification(
            id = id,
            state = CertificationState.COMPONENT_VERIFIED,
            limitingStage = null,
            reason = "Las etapas aisladas pasan; todavía no existe una prueba de grabación completa",
            recordingTier = RecordingValidationTier.ISOLATED_COMPONENTS
        )
    }

    private fun batteryTemperatureC(): Double {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return battery?.getIntExtra("temperature", 0)?.div(10.0) ?: 0.0
    }

    private fun thermalStatus(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(PowerManager::class.java).currentThermalStatus
    } else {
        0
    }

    private fun storageSnapshot(): StorageSnapshot {
        val stat = StatFs(context.filesDir.absolutePath)
        return StorageSnapshot(stat.availableBytes, stat.totalBytes)
    }

    private data class StorageSnapshot(val availableBytes: Long, val totalBytes: Long)

    companion object {
        private const val FPS_TOLERANCE = 0.05
    }
}

internal data class SensorSweepTarget(
    val camera: CameraCapability,
    val dimensions: Dimensions
)

internal object SensorSweepPlanner {
    fun targets(profile: DeviceCapabilityProfile): List<SensorSweepTarget> {
        val camerasById = profile.cameras.associateBy { it.cameraId }
        val physicalIds = profile.cameras.flatMapTo(linkedSetOf()) { it.physicalCameraIds }
        return profile.cameras
            .asSequence()
            .filter { it.raw10Sizes.isNotEmpty() }
            .filter { camera ->
                camera.cameraId in physicalIds || camera.physicalCameraIds.none { physicalId ->
                    camerasById[physicalId]?.raw10Sizes?.isNotEmpty() == true
                }
            }
            .map { camera -> SensorSweepTarget(camera, representativeRaw10(camera)) }
            .sortedWith(
                compareBy<SensorSweepTarget> { facingOrder(it.camera.facing) }
                    .thenByDescending { maximumRawArea(it.camera) }
                    .thenBy { it.camera.cameraId }
            )
            .toList()
    }

    fun primaryBackTarget(targets: List<SensorSweepTarget>): SensorSweepTarget? = targets
        .filter { it.camera.facing == CameraCapability.Facing.BACK }
        .maxByOrNull { maximumRawArea(it.camera) }
        ?: targets.firstOrNull()

    private fun representativeRaw10(camera: CameraCapability): Dimensions = camera.raw10Sizes
        .filter { it.width in 1800..2500 && it.height in 1000..1800 }
        .maxByOrNull { it.width.toLong() * it.height }
        ?: camera.raw10Sizes.minBy { it.width.toLong() * it.height }

    private fun maximumRawArea(camera: CameraCapability): Long =
        (camera.rawSensorSizes.ifEmpty { camera.raw10Sizes })
            .maxOfOrNull { it.width.toLong() * it.height } ?: 0L

    private fun facingOrder(facing: CameraCapability.Facing): Int = when (facing) {
        CameraCapability.Facing.BACK -> 0
        CameraCapability.Facing.FRONT -> 1
        CameraCapability.Facing.EXTERNAL -> 2
        CameraCapability.Facing.UNKNOWN -> 3
    }
}

private val SensorCadenceResult.sensorId: String
    get() = physicalCameraId ?: cameraId
