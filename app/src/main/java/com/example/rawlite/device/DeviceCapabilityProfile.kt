package com.example.rawlite.device

/** A serializable, Android-independent description of what a device reports and what we verified. */
internal data class DeviceCapabilityProfile(
    val identity: DeviceIdentity,
    val cameras: List<CameraCapability>,
    val encoders: List<EncoderCapability>,
    val profileSchemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 3
    }
}

internal data class DeviceIdentity(
    val manufacturer: String,
    val model: String,
    val device: String,
    val buildFingerprint: String,
    val sdk: Int
)

internal data class CameraCapability(
    val cameraId: String,
    val facing: Facing,
    val rawSensorSizes: Set<Dimensions>,
    val raw10Sizes: Set<Dimensions>,
    val maximumRawOutputs: Int,
    val evidence: SupportEvidence,
    val hardwareLevel: Int? = null,
    val sensorOrientationDegrees: Int? = null,
    val availableCapabilities: Set<Int> = emptySet(),
    val streams: List<StreamCapability> = emptyList(),
    val croppedRawDeclared: Boolean = false,
    val hlg10Declared: Boolean = false,
    val logicalCameraId: String? = null,
    val physicalCameraIds: Set<String> = emptySet(),
    val sensor: SensorCapability = SensorCapability(),
    val lens: LensCapability = LensCapability(),
    val controls: CameraControlCapability = CameraControlCapability(),
    val outputs: OutputCapability = OutputCapability()
) {
    enum class Facing { BACK, FRONT, EXTERNAL, UNKNOWN }
}

internal data class EncoderCapability(
    val name: String,
    val hevcMain10: Boolean,
    val p010: Boolean,
    val hardwareAccelerated: Boolean,
    val evidence: SupportEvidence,
    val widthAlignment: Int = 1,
    val heightAlignment: Int = 1,
    val minimumBitRate: Int? = null,
    val maximumBitRate: Int? = null,
    val minimumWidth: Int? = null,
    val maximumWidth: Int? = null,
    val minimumHeight: Int? = null,
    val maximumHeight: Int? = null,
    val mimeType: String = "video/hevc",
    val profiles: List<CodecProfileLevel> = emptyList(),
    val colorFormats: Set<Int> = emptySet(),
    val maximumSupportedInstances: Int = 1,
    val bitrateModes: Set<BitrateMode> = emptySet(),
    val supportedFrameRateRange: NumericRange? = null,
    val performancePoints: List<String> = emptyList()
)

internal data class Dimensions(val width: Int, val height: Int)

internal data class IntRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

internal data class NumericRange(val minimum: Double, val maximum: Double)

internal data class RationalValue(val numerator: Int, val denominator: Int)

internal data class Matrix3x3(val values: List<RationalValue>) {
    init {
        require(values.size == 9)
    }
}

internal data class SensorCapability(
    val activeArray: IntRect? = null,
    val preCorrectionActiveArray: IntRect? = null,
    val pixelArray: Dimensions? = null,
    val physicalWidthMm: Float? = null,
    val physicalHeightMm: Float? = null,
    val colorFilterArrangement: Int? = null,
    val blackLevelPattern: List<Int> = emptyList(),
    val whiteLevel: Int? = null,
    val sensitivityRange: NumericRange? = null,
    val maximumAnalogSensitivity: Int? = null,
    val exposureTimeRangeNs: NumericRange? = null,
    val maximumFrameDurationNs: Long? = null,
    val timestampSource: Int? = null,
    val lensShadingApplied: Boolean? = null,
    val referenceIlluminant1: Int? = null,
    val referenceIlluminant2: Int? = null,
    val calibrationTransform1: Matrix3x3? = null,
    val calibrationTransform2: Matrix3x3? = null,
    val colorTransform1: Matrix3x3? = null,
    val colorTransform2: Matrix3x3? = null,
    val forwardMatrix1: Matrix3x3? = null,
    val forwardMatrix2: Matrix3x3? = null
)

internal data class LensCapability(
    val focalLengthsMm: List<Float> = emptyList(),
    val apertures: List<Float> = emptyList(),
    val minimumFocusDistanceDiopters: Float? = null,
    val hyperfocalDistanceDiopters: Float? = null,
    val focusDistanceCalibration: Int? = null,
    val opticalStabilizationModes: Set<Int> = emptySet(),
    val maximumDigitalZoom: Float? = null,
    val zoomRatioRange: NumericRange? = null
)

internal data class CameraControlCapability(
    val manualSensor: Boolean = false,
    val manualPostProcessing: Boolean = false,
    val raw: Boolean = false,
    val afModes: Set<Int> = emptySet(),
    val aeModes: Set<Int> = emptySet(),
    val awbModes: Set<Int> = emptySet(),
    val targetFpsRanges: List<NumericRange> = emptyList(),
    val maximumAfRegions: Int = 0,
    val maximumAeRegions: Int = 0,
    val maximumAwbRegions: Int = 0,
    val aeLockAvailable: Boolean = false,
    val awbLockAvailable: Boolean = false,
    val videoStabilizationModes: Set<Int> = emptySet(),
    val colorCorrectionModes: Set<Int> = emptySet(),
    val flashAvailable: Boolean = false,
    val sensorRangesPresent: Boolean = false
) {
    val manualExposureAvailable: Boolean
        get() = manualSensor && sensorRangesPresent
}

internal data class OutputCapability(
    val maximumRawOutputs: Int = 0,
    val maximumProcessedOutputs: Int = 0,
    val maximumStallingOutputs: Int = 0,
    val maximumInputStreams: Int = 0
)

internal data class CodecProfileLevel(val profile: Int, val level: Int)

internal enum class BitrateMode { CQ, VBR, CBR }

internal data class StreamCapability(
    val format: StreamFormat,
    val dimensions: Dimensions,
    val minimumFrameDurationNs: Long,
    val stallDurationNs: Long
) {
    val maximumDeclaredFps: Double?
        get() = minimumFrameDurationNs.takeIf { it > 0L }
            ?.let { 1_000_000_000.0 / it }
}

internal enum class StreamFormat(val androidFormat: Int) {
    RAW_SENSOR(32),
    RAW10(37),
    YUV_420_888(35),
    PRIVATE(34)
}

internal enum class SupportEvidence {
    UNSUPPORTED,
    DECLARED,
    SESSION_VERIFIED,
    SUSTAINED_VERIFIED
}

internal enum class CaptureFeature {
    CINEMA_DNG,
    RAW10,
    PURE10
}

internal object CaptureAvailabilityPolicy {
    fun support(
        feature: CaptureFeature,
        camera: CameraCapability,
        encoders: List<EncoderCapability>
    ): SupportEvidence = when (feature) {
        CaptureFeature.CINEMA_DNG -> evidenceForSizes(camera.rawSensorSizes, camera.evidence)
        CaptureFeature.RAW10 -> evidenceForSizes(camera.raw10Sizes, camera.evidence)
        CaptureFeature.PURE10 -> {
            val rawEvidence = evidenceForSizes(camera.raw10Sizes, camera.evidence)
            val encoderEvidence = encoders
                .filter { it.hevcMain10 && it.p010 && it.hardwareAccelerated }
                .maxByOrNull { it.evidence.ordinal }
                ?.evidence
                ?: SupportEvidence.UNSUPPORTED
            if (rawEvidence == SupportEvidence.UNSUPPORTED ||
                encoderEvidence == SupportEvidence.UNSUPPORTED
            ) {
                SupportEvidence.UNSUPPORTED
            } else {
                minOf(rawEvidence, encoderEvidence, compareBy { it.ordinal })
            }
        }
    }

    private fun evidenceForSizes(
        sizes: Set<Dimensions>,
        evidence: SupportEvidence
    ): SupportEvidence = if (sizes.isEmpty()) SupportEvidence.UNSUPPORTED else evidence
}
