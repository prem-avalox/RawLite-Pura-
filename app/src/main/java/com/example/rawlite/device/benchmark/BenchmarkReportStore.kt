package com.example.rawlite.device.benchmark

import android.content.Context
import com.example.rawlite.device.CameraCapability
import com.example.rawlite.device.CameraControlCapability
import com.example.rawlite.device.CodecProfileLevel
import com.example.rawlite.device.DeviceCapabilityProfile
import com.example.rawlite.device.EncoderCapability
import com.example.rawlite.device.IntRect
import com.example.rawlite.device.LensCapability
import com.example.rawlite.device.Matrix3x3
import com.example.rawlite.device.NumericRange
import com.example.rawlite.device.OutputCapability
import com.example.rawlite.device.SensorCapability
import com.example.rawlite.device.StreamCapability
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class BenchmarkReportStore(private val context: Context) {
    private val reportDirectory: File
        get() = File(context.filesDir, "device-profiles")

    fun save(report: DeviceBenchmarkReport): File {
        val directory = reportDirectory.apply { mkdirs() }
        val destination = File(directory, "active-benchmark.json")
        val temporary = File(directory, "active-benchmark.json.tmp")
        temporary.writeText(report.toJson().toString(2))
        if (!temporary.renameTo(destination)) {
            destination.writeText(temporary.readText())
            temporary.delete()
        }
        return destination
    }

    fun reportFile(): File = File(reportDirectory, "active-benchmark.json")
}

internal class BenchmarkWorkspace(context: Context) : AutoCloseable {
    private val root = File(context.externalCacheDir ?: context.cacheDir, "rawlite-benchmark")
    val directory: File

    init {
        cleanupAbandoned(context)
        directory = File(root, "run-${System.currentTimeMillis()}")
        check(directory.mkdirs()) { "No se pudo preparar el entorno temporal" }
    }

    override fun close() {
        directory.deleteRecursively()
        root.listFiles()?.takeIf { it.isEmpty() }?.let { root.delete() }
    }

    companion object {
        fun cleanupAbandoned(context: Context) {
            listOfNotNull(context.externalCacheDir, context.cacheDir)
                .map { File(it, "rawlite-benchmark") }
                .forEach { it.deleteRecursively() }
        }
    }
}

private fun DeviceBenchmarkReport.toJson(): JSONObject = JSONObject().apply {
    put("schema_version", schemaVersion)
    put("generated_at_epoch_ms", generatedAtEpochMs)
    put("device_profile", profile.toJson())
    put("temperature_before_c", temperatureBeforeC)
    put("temperature_after_c", temperatureAfterC)
    put("thermal_status_before", thermalStatusBefore)
    put("thermal_status_after", thermalStatusAfter)
    put("available_storage_before_bytes", availableStorageBeforeBytes)
    put("available_storage_after_bytes", availableStorageAfterBytes)
    put("total_storage_bytes", totalStorageBytes)
    put("sensor_cadence", JSONArray().apply { sensorCadence.forEach { put(it.toJson()) } })
    put("storage", JSONArray().apply { storage.forEach { put(it.toJson()) } })
    put("isp", JSONArray().apply { isp.forEach { put(it.toJson()) } })
    put("encoders", JSONArray().apply { encoders.forEach { put(it.toJson()) } })
    put("modes", JSONArray().apply { modes.forEach { put(it.toJson()) } })
    put("completed_stages", JSONArray(completedStages.map { it.name }))
    put("interrupted", interrupted)
}

private fun DeviceCapabilityProfile.toJson(): JSONObject = JSONObject().apply {
    put("schema_version", profileSchemaVersion)
    put("identity", JSONObject().apply {
        put("manufacturer", identity.manufacturer)
        put("model", identity.model)
        put("device", identity.device)
        put("build_fingerprint", identity.buildFingerprint)
        put("sdk", identity.sdk)
    })
    put("cameras", JSONArray().apply { cameras.forEach { put(it.toJson()) } })
    put("encoders", JSONArray().apply { encoders.forEach { put(it.toJson()) } })
}

private fun CameraCapability.toJson(): JSONObject = JSONObject().apply {
    put("camera_id", cameraId)
    put("facing", facing.name)
    put("raw_sensor_sizes", JSONArray(rawSensorSizes.sortedByDescending { it.width.toLong() * it.height }
        .map { "${it.width}x${it.height}" }))
    put("raw10_sizes", JSONArray(raw10Sizes.sortedByDescending { it.width.toLong() * it.height }
        .map { "${it.width}x${it.height}" }))
    put("maximum_raw_outputs", maximumRawOutputs)
    put("evidence", evidence.name)
    put("hardware_level", hardwareLevel ?: JSONObject.NULL)
    put("sensor_orientation_degrees", sensorOrientationDegrees ?: JSONObject.NULL)
    put("available_capabilities", JSONArray(availableCapabilities.sorted()))
    put("cropped_raw_declared", croppedRawDeclared)
    put("hlg10_declared", hlg10Declared)
    put("logical_camera_id", logicalCameraId ?: JSONObject.NULL)
    put("physical_camera_ids", JSONArray(physicalCameraIds.sorted()))
    put("sensor", sensor.toJson())
    put("lens", lens.toJson())
    put("controls", controls.toJson())
    put("outputs", outputs.toJson())
    put("streams", JSONArray().apply { streams.forEach { put(it.toJson()) } })
}

private fun StreamCapability.toJson(): JSONObject = JSONObject().apply {
    put("format", format.name)
    put("width", dimensions.width)
    put("height", dimensions.height)
    put("minimum_frame_duration_ns", minimumFrameDurationNs)
    put("stall_duration_ns", stallDurationNs)
    put("maximum_declared_fps", maximumDeclaredFps ?: JSONObject.NULL)
}

private fun EncoderCapability.toJson(): JSONObject = JSONObject().apply {
    put("name", name)
    put("hevc_main10", hevcMain10)
    put("p010", p010)
    put("hardware_accelerated", hardwareAccelerated)
    put("evidence", evidence.name)
    put("width_alignment", widthAlignment)
    put("height_alignment", heightAlignment)
    put("minimum_bitrate", minimumBitRate ?: JSONObject.NULL)
    put("maximum_bitrate", maximumBitRate ?: JSONObject.NULL)
    put("minimum_width", minimumWidth ?: JSONObject.NULL)
    put("maximum_width", maximumWidth ?: JSONObject.NULL)
    put("minimum_height", minimumHeight ?: JSONObject.NULL)
    put("maximum_height", maximumHeight ?: JSONObject.NULL)
    put("mime_type", mimeType)
    put("profiles", JSONArray().apply { profiles.forEach { put(it.toJson()) } })
    put("color_formats", JSONArray(colorFormats.sorted()))
    put("maximum_supported_instances", maximumSupportedInstances)
    put("bitrate_modes", JSONArray(bitrateModes.map { it.name }.sorted()))
    put("supported_frame_rate_range", supportedFrameRateRange?.toJson() ?: JSONObject.NULL)
    put("performance_points", JSONArray(performancePoints))
}

private fun SensorCapability.toJson(): JSONObject = JSONObject().apply {
    put("active_array", activeArray?.toJson() ?: JSONObject.NULL)
    put("pre_correction_active_array", preCorrectionActiveArray?.toJson() ?: JSONObject.NULL)
    put("pixel_array", pixelArray?.let { "${it.width}x${it.height}" } ?: JSONObject.NULL)
    put("physical_width_mm", physicalWidthMm ?: JSONObject.NULL)
    put("physical_height_mm", physicalHeightMm ?: JSONObject.NULL)
    put("color_filter_arrangement", colorFilterArrangement ?: JSONObject.NULL)
    put("black_level_pattern", JSONArray(blackLevelPattern))
    put("white_level", whiteLevel ?: JSONObject.NULL)
    put("sensitivity_range", sensitivityRange?.toJson() ?: JSONObject.NULL)
    put("maximum_analog_sensitivity", maximumAnalogSensitivity ?: JSONObject.NULL)
    put("exposure_time_range_ns", exposureTimeRangeNs?.toJson() ?: JSONObject.NULL)
    put("maximum_frame_duration_ns", maximumFrameDurationNs ?: JSONObject.NULL)
    put("timestamp_source", timestampSource ?: JSONObject.NULL)
    put("lens_shading_applied", lensShadingApplied ?: JSONObject.NULL)
    put("reference_illuminant_1", referenceIlluminant1 ?: JSONObject.NULL)
    put("reference_illuminant_2", referenceIlluminant2 ?: JSONObject.NULL)
    put("calibration_transform_1", calibrationTransform1?.toJson() ?: JSONObject.NULL)
    put("calibration_transform_2", calibrationTransform2?.toJson() ?: JSONObject.NULL)
    put("color_transform_1", colorTransform1?.toJson() ?: JSONObject.NULL)
    put("color_transform_2", colorTransform2?.toJson() ?: JSONObject.NULL)
    put("forward_matrix_1", forwardMatrix1?.toJson() ?: JSONObject.NULL)
    put("forward_matrix_2", forwardMatrix2?.toJson() ?: JSONObject.NULL)
}

private fun LensCapability.toJson(): JSONObject = JSONObject().apply {
    put("focal_lengths_mm", JSONArray(focalLengthsMm))
    put("apertures", JSONArray(apertures))
    put("minimum_focus_distance_diopters", minimumFocusDistanceDiopters ?: JSONObject.NULL)
    put("hyperfocal_distance_diopters", hyperfocalDistanceDiopters ?: JSONObject.NULL)
    put("focus_distance_calibration", focusDistanceCalibration ?: JSONObject.NULL)
    put("optical_stabilization_modes", JSONArray(opticalStabilizationModes.sorted()))
    put("maximum_digital_zoom", maximumDigitalZoom ?: JSONObject.NULL)
    put("zoom_ratio_range", zoomRatioRange?.toJson() ?: JSONObject.NULL)
}

private fun CameraControlCapability.toJson(): JSONObject = JSONObject().apply {
    put("manual_sensor", manualSensor)
    put("manual_post_processing", manualPostProcessing)
    put("manual_exposure_available", manualExposureAvailable)
    put("raw", raw)
    put("af_modes", JSONArray(afModes.sorted()))
    put("ae_modes", JSONArray(aeModes.sorted()))
    put("awb_modes", JSONArray(awbModes.sorted()))
    put("target_fps_ranges", JSONArray().apply { targetFpsRanges.forEach { put(it.toJson()) } })
    put("maximum_af_regions", maximumAfRegions)
    put("maximum_ae_regions", maximumAeRegions)
    put("maximum_awb_regions", maximumAwbRegions)
    put("ae_lock_available", aeLockAvailable)
    put("awb_lock_available", awbLockAvailable)
    put("video_stabilization_modes", JSONArray(videoStabilizationModes.sorted()))
    put("color_correction_modes", JSONArray(colorCorrectionModes.sorted()))
    put("flash_available", flashAvailable)
}

private fun OutputCapability.toJson(): JSONObject = JSONObject().apply {
    put("maximum_raw_outputs", maximumRawOutputs)
    put("maximum_processed_outputs", maximumProcessedOutputs)
    put("maximum_stalling_outputs", maximumStallingOutputs)
    put("maximum_input_streams", maximumInputStreams)
}

private fun IntRect.toJson(): JSONObject = JSONObject().apply {
    put("left", left)
    put("top", top)
    put("right", right)
    put("bottom", bottom)
}

private fun NumericRange.toJson(): JSONObject = JSONObject().apply {
    put("minimum", minimum)
    put("maximum", maximum)
}

private fun Matrix3x3.toJson(): JSONArray = JSONArray(values.map {
    JSONObject().apply {
        put("numerator", it.numerator)
        put("denominator", it.denominator)
    }
})

private fun CodecProfileLevel.toJson(): JSONObject = JSONObject().apply {
    put("profile", profile)
    put("level", level)
}

private fun SensorCadenceResult.toJson(): JSONObject = JSONObject().apply {
    put("camera_id", cameraId)
    put("physical_camera_id", physicalCameraId ?: JSONObject.NULL)
    put("width", dimensions.width)
    put("height", dimensions.height)
    put("requested_fps", requestedFps)
    put("declared_maximum_fps", declaredMaximumFps ?: JSONObject.NULL)
    put("received_frames", receivedFrames)
    put("duration_s", durationSeconds)
    put("achieved_fps", achievedFps)
    put("inferred_lost_frames", inferredLostFrames)
    put("row_stride_bytes", rowStrideBytes)
    put("intervals_ms", intervals.toJson())
    put("state", state.name)
    put("error", error ?: JSONObject.NULL)
}

private fun StorageBenchmarkResult.toJson(): JSONObject = JSONObject().apply {
    put("width", dimensions.width)
    put("height", dimensions.height)
    put("requested_fps", requestedFps)
    put("row_stride_bytes", rowStrideBytes)
    put("frame_bytes", frameBytes)
    put("frames_written", framesWritten)
    put("bytes_written", bytesWritten)
    put("duration_s", durationSeconds)
    put("throughput_mb_s", throughputMegabytesPerSecond)
    put("write_calls_ms", writeCalls.toJson())
    put("close_ms", closeMilliseconds)
    put("io_mode", ioMode)
    put("direct_fallbacks", directFallbacks)
    put("sustainable_fps_with_margin", sustainableFpsWithMargin)
    put("deadline_misses", deadlineMisses)
    put("maximum_lag_ms", maximumLagMs)
    put("final_lag_ms", finalLagMs)
    put("state", state.name)
    put("error", error ?: JSONObject.NULL)
}

private fun IspBenchmarkResult.toJson(): JSONObject = JSONObject().apply {
    put("source_width", sourceDimensions.width)
    put("source_height", sourceDimensions.height)
    put("output_width", outputDimensions.width)
    put("output_height", outputDimensions.height)
    put("frames_processed", framesProcessed)
    put("requested_fps", requestedFps)
    put("conversion_ms", conversion.toJson())
    put("realtime_headroom", realTimeHeadroom)
    put("state", state.name)
    put("error", error ?: JSONObject.NULL)
}

private fun EncoderBenchmarkResult.toJson(): JSONObject = JSONObject().apply {
    put("codec", codecName)
    put("width", dimensions.width)
    put("height", dimensions.height)
    put("requested_fps", requestedFps)
    put("bitrate", bitRate)
    put("submitted_frames", submittedFrames)
    put("encoded_frames", encodedFrames)
    put("elapsed_s", elapsedSeconds)
    put("throughput_fps", throughputFps)
    put("realtime_headroom", realTimeHeadroom)
    put("state", state.name)
    put("error", error ?: JSONObject.NULL)
}

private fun ModeCertification.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("state", state.name)
    put("limiting_stage", limitingStage?.name ?: JSONObject.NULL)
    put("reason", reason)
    put("recording_tier", recordingTier.name)
    put("maximum_verified_duration_s", maximumVerifiedDurationSeconds)
}

private fun Distribution.toJson(): JSONObject = JSONObject().apply {
    put("count", count)
    put("average", averageMs)
    put("p50", p50Ms)
    put("p95", p95Ms)
    put("p99", p99Ms)
    put("maximum", maximumMs)
    put("standard_deviation", standardDeviationMs)
}
