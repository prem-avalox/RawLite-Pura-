package com.example.rawlite.device

/** A serializable, Android-independent description of what a device reports and what we verified. */
internal data class DeviceCapabilityProfile(
    val identity: DeviceIdentity,
    val cameras: List<CameraCapability>,
    val encoders: List<EncoderCapability>,
    val profileSchemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
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
    val evidence: SupportEvidence
) {
    enum class Facing { BACK, FRONT, EXTERNAL, UNKNOWN }
}

internal data class EncoderCapability(
    val name: String,
    val hevcMain10: Boolean,
    val p010: Boolean,
    val hardwareAccelerated: Boolean,
    val evidence: SupportEvidence
)

internal data class Dimensions(val width: Int, val height: Int)

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
