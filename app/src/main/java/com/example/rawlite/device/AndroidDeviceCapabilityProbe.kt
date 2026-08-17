package com.example.rawlite.device

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Range

/** Passive capability discovery. It never opens a camera, configures a session or writes footage. */
internal class AndroidDeviceCapabilityProbe(private val cameraManager: CameraManager) {
    fun probe(): DeviceCapabilityProfile = DeviceCapabilityProfile(
        identity = DeviceIdentity(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            buildFingerprint = Build.FINGERPRINT,
            sdk = Build.VERSION.SDK_INT
        ),
        cameras = cameraCapabilities(),
        encoders = encoderCapabilities()
    )

    private fun cameraCapabilities(): List<CameraCapability> {
        val characteristicsById = linkedMapOf<String, CameraCharacteristics>()
        val logicalParentByPhysicalId = mutableMapOf<String, String>()
        cameraManager.cameraIdList.forEach { logicalId ->
            val logical = runCatching {
                cameraManager.getCameraCharacteristics(logicalId)
            }.getOrNull() ?: return@forEach
            characteristicsById[logicalId] = logical
            logical.physicalCameraIds.forEach { physicalId ->
                logicalParentByPhysicalId.putIfAbsent(physicalId, logicalId)
                runCatching { cameraManager.getCameraCharacteristics(physicalId) }
                    .getOrNull()
                    ?.let { characteristicsById.putIfAbsent(physicalId, it) }
            }
        }
        return characteristicsById.map { (cameraId, characteristics) ->
            val streamMap = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )
            val availableCapabilities = characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            )?.toSet().orEmpty()
            val sensor = sensorCapability(characteristics)
            CameraCapability(
                cameraId = cameraId,
                facing = facing(characteristics.get(CameraCharacteristics.LENS_FACING)),
                rawSensorSizes = streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)
                    ?.map { Dimensions(it.width, it.height) }?.toSet().orEmpty(),
                raw10Sizes = streamMap?.getOutputSizes(ImageFormat.RAW10)
                    ?.map { Dimensions(it.width, it.height) }?.toSet().orEmpty(),
                maximumRawOutputs = characteristics.get(
                    CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_RAW
                ) ?: 0,
                evidence = SupportEvidence.DECLARED,
                hardwareLevel = characteristics.get(
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL
                ),
                sensorOrientationDegrees = characteristics.get(
                    CameraCharacteristics.SENSOR_ORIENTATION
                ),
                availableCapabilities = availableCapabilities,
                streams = streamCapabilities(streamMap),
                croppedRawDeclared = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)
                        ?.contains(
                            CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_CROPPED_RAW.toLong()
                        ) ?: false
                } else false,
                hlg10Declared = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    characteristics.get(
                        CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES
                    )?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) ?: false
                } else false,
                logicalCameraId = logicalParentByPhysicalId[cameraId],
                physicalCameraIds = characteristics.physicalCameraIds,
                sensor = sensor,
                lens = lensCapability(characteristics),
                controls = controlCapability(characteristics, availableCapabilities, sensor),
                outputs = outputCapability(characteristics)
            )
        }
    }

    private fun sensorCapability(characteristics: CameraCharacteristics): SensorCapability {
        val blackPattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val blackLevels = if (blackPattern == null) emptyList() else List(4) { index ->
            blackPattern.getOffsetForIndex(index % 2, index / 2)
        }
        val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        return SensorCapability(
            activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?.toIntRect(),
            preCorrectionActiveArray = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE
            )?.toIntRect(),
            pixelArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                ?.let { Dimensions(it.width, it.height) },
            physicalWidthMm = physicalSize?.width,
            physicalHeightMm = physicalSize?.height,
            colorFilterArrangement = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
            ),
            blackLevelPattern = blackLevels,
            whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL),
            sensitivityRange = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            )?.toNumericRange(),
            maximumAnalogSensitivity = characteristics.get(
                CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY
            ),
            exposureTimeRangeNs = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
            )?.toNumericRange(),
            maximumFrameDurationNs = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION
            ),
            timestampSource = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE
            ),
            lensShadingApplied = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED
            ),
            referenceIlluminant1 = characteristics.get(
                CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1
            ),
            referenceIlluminant2 = characteristics.get(
                CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2
            )?.toInt(),
            calibrationTransform1 = characteristics.get(
                CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1
            )?.toMatrix3x3(),
            calibrationTransform2 = characteristics.get(
                CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2
            )?.toMatrix3x3(),
            colorTransform1 = characteristics.get(
                CameraCharacteristics.SENSOR_COLOR_TRANSFORM1
            )?.toMatrix3x3(),
            colorTransform2 = characteristics.get(
                CameraCharacteristics.SENSOR_COLOR_TRANSFORM2
            )?.toMatrix3x3(),
            forwardMatrix1 = characteristics.get(
                CameraCharacteristics.SENSOR_FORWARD_MATRIX1
            )?.toMatrix3x3(),
            forwardMatrix2 = characteristics.get(
                CameraCharacteristics.SENSOR_FORWARD_MATRIX2
            )?.toMatrix3x3()
        )
    }

    private fun lensCapability(characteristics: CameraCharacteristics): LensCapability =
        LensCapability(
            focalLengthsMm = characteristics.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            )?.toList().orEmpty(),
            apertures = characteristics.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES
            )?.toList().orEmpty(),
            minimumFocusDistanceDiopters = characteristics.get(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
            ),
            hyperfocalDistanceDiopters = characteristics.get(
                CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE
            ),
            focusDistanceCalibration = characteristics.get(
                CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION
            ),
            opticalStabilizationModes = characteristics.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
            )?.toSet().orEmpty(),
            maximumDigitalZoom = characteristics.get(
                CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
            ),
            zoomRatioRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    ?.toNumericRange()
            } else null
        )

    private fun controlCapability(
        characteristics: CameraCharacteristics,
        availableCapabilities: Set<Int>,
        sensor: SensorCapability
    ): CameraControlCapability {
        val requestKeys = characteristics.availableCaptureRequestKeys.orEmpty()
        return CameraControlCapability(
            manualSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in
                availableCapabilities,
            manualPostProcessing =
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in
                    availableCapabilities,
            raw = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in availableCapabilities,
            afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.toSet().orEmpty(),
            aeModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
                ?.toSet().orEmpty(),
            awbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                ?.toSet().orEmpty(),
            targetFpsRanges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            )?.map { it.toNumericRange() }.orEmpty(),
            maximumAfRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)
                ?: 0,
            maximumAeRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE)
                ?: 0,
            maximumAwbRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB)
                ?: 0,
            aeLockAvailable = characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)
                ?: false,
            awbLockAvailable = characteristics.get(
                CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE
            ) ?: false,
            videoStabilizationModes = characteristics.get(
                CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
            )?.toSet().orEmpty(),
            colorCorrectionModes = buildSet {
                if (CaptureRequest.COLOR_CORRECTION_MODE in requestKeys) {
                    add(CameraMetadata.COLOR_CORRECTION_MODE_FAST)
                    add(CameraMetadata.COLOR_CORRECTION_MODE_HIGH_QUALITY)
                    if (CaptureRequest.COLOR_CORRECTION_TRANSFORM in requestKeys) {
                        add(CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                    }
                }
            },
            flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
                ?: false,
            sensorRangesPresent = sensor.sensitivityRange != null &&
                sensor.exposureTimeRangeNs != null && sensor.maximumFrameDurationNs != null
        )
    }

    private fun outputCapability(characteristics: CameraCharacteristics): OutputCapability =
        OutputCapability(
            maximumRawOutputs = characteristics.get(
                CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_RAW
            ) ?: 0,
            maximumProcessedOutputs = characteristics.get(
                CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC
            ) ?: 0,
            maximumStallingOutputs = characteristics.get(
                CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC_STALLING
            ) ?: 0,
            maximumInputStreams = characteristics.get(
                CameraCharacteristics.REQUEST_MAX_NUM_INPUT_STREAMS
            ) ?: 0
        )

    private fun streamCapabilities(
        streamMap: android.hardware.camera2.params.StreamConfigurationMap?
    ): List<StreamCapability> {
        if (streamMap == null) return emptyList()
        return StreamFormat.entries.flatMap { format ->
            runCatching { streamMap.getOutputSizes(format.androidFormat) }
                .getOrNull().orEmpty().map { size ->
                    StreamCapability(
                        format = format,
                        dimensions = Dimensions(size.width, size.height),
                        minimumFrameDurationNs = runCatching {
                            streamMap.getOutputMinFrameDuration(format.androidFormat, size)
                        }.getOrDefault(0L),
                        stallDurationNs = runCatching {
                            streamMap.getOutputStallDuration(format.androidFormat, size)
                        }.getOrDefault(0L)
                    )
                }
        }
    }

    private fun encoderCapabilities(): List<EncoderCapability> =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.flatMap { codec ->
            if (!codec.isEncoder) return@flatMap emptyList()
            codec.supportedTypes.filter {
                it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) ||
                    it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
            }.mapNotNull { mimeType ->
                val capabilities = runCatching {
                    codec.getCapabilitiesForType(mimeType)
                }.getOrNull() ?: return@mapNotNull null
                val video = capabilities.videoCapabilities
                val encoder = capabilities.encoderCapabilities
                EncoderCapability(
                    name = codec.name,
                    hevcMain10 = mimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) &&
                        capabilities.profileLevels.any {
                            it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                        },
                    p010 = capabilities.colorFormats.contains(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
                    ),
                    hardwareAccelerated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        codec.isHardwareAccelerated
                    } else {
                        !codec.name.startsWith("OMX.google", ignoreCase = true) &&
                            !codec.name.startsWith("c2.android", ignoreCase = true)
                    },
                    evidence = SupportEvidence.DECLARED,
                    widthAlignment = video.widthAlignment,
                    heightAlignment = video.heightAlignment,
                    minimumBitRate = video.bitrateRange.lower,
                    maximumBitRate = video.bitrateRange.upper,
                    minimumWidth = video.supportedWidths.lower,
                    maximumWidth = video.supportedWidths.upper,
                    minimumHeight = video.supportedHeights.lower,
                    maximumHeight = video.supportedHeights.upper,
                    mimeType = mimeType,
                    profiles = capabilities.profileLevels.map {
                        CodecProfileLevel(profile = it.profile, level = it.level)
                    },
                    colorFormats = capabilities.colorFormats.toSet(),
                    maximumSupportedInstances = capabilities.maxSupportedInstances,
                    bitrateModes = buildSet {
                        if (encoder.isBitrateModeSupported(
                                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
                            )
                        ) add(BitrateMode.CQ)
                        if (encoder.isBitrateModeSupported(
                                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                            )
                        ) add(BitrateMode.VBR)
                        if (encoder.isBitrateModeSupported(
                                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                            )
                        ) add(BitrateMode.CBR)
                    },
                    supportedFrameRateRange = video.supportedFrameRates.toNumericRange(),
                    performancePoints = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        video.supportedPerformancePoints?.map { it.toString() }.orEmpty()
                    } else emptyList()
                )
            }
        }

    private fun Rect.toIntRect(): IntRect = IntRect(left, top, right, bottom)

    private fun <T> Range<T>.toNumericRange(): NumericRange where T : Number, T : Comparable<T> =
        NumericRange(lower.toDouble(), upper.toDouble())

    private fun ColorSpaceTransform.toMatrix3x3(): Matrix3x3 = Matrix3x3(
        List(9) { index ->
            val value = getElement(index % 3, index / 3)
            RationalValue(value.numerator, value.denominator)
        }
    )

    private fun facing(value: Int?): CameraCapability.Facing = when (value) {
        CameraCharacteristics.LENS_FACING_BACK -> CameraCapability.Facing.BACK
        CameraCharacteristics.LENS_FACING_FRONT -> CameraCapability.Facing.FRONT
        CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraCapability.Facing.EXTERNAL
        else -> CameraCapability.Facing.UNKNOWN
    }
}
