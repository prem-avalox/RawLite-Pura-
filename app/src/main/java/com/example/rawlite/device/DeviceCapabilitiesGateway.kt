package com.example.rawlite.device

import android.hardware.camera2.CameraManager
import kotlin.math.floor

/**
 * The only normalized capability contract that application features should consume.
 * Camera2 constants remain evidence in the profile; product decisions live here.
 */
internal class DeviceCapabilitiesGateway private constructor(
    val profile: DeviceCapabilityProfile
) {
    fun camera(openId: String, physicalId: String? = null): CameraCapability? =
        profile.cameras.firstOrNull { it.cameraId == physicalId }
            ?: profile.cameras.firstOrNull { it.cameraId == openId }

    fun feature(
        feature: CaptureFeature,
        openId: String,
        physicalId: String? = null
    ): FeatureAvailability {
        val camera = camera(openId, physicalId) ?: return FeatureAvailability(
            feature = feature,
            evidence = SupportEvidence.UNSUPPORTED,
            dimensions = emptySet(),
            reason = "Cámara no inventariada"
        )
        val dimensions = when (feature) {
            CaptureFeature.CINEMA_DNG -> camera.rawSensorSizes
            CaptureFeature.RAW10, CaptureFeature.PURE10 -> camera.raw10Sizes
        }
        val evidence = CaptureAvailabilityPolicy.support(feature, camera, profile.encoders)
        val reason = when {
            dimensions.isEmpty() -> when (feature) {
                CaptureFeature.CINEMA_DNG -> "El HAL no ofrece RAW_SENSOR"
                CaptureFeature.RAW10, CaptureFeature.PURE10 -> "El HAL no ofrece RAW10"
            }
            feature == CaptureFeature.PURE10 && evidence == SupportEvidence.UNSUPPORTED ->
                "No existe un encoder HEVC Main10 P010 acelerado por hardware"
            evidence == SupportEvidence.UNSUPPORTED -> "Función no compatible"
            else -> "Disponible con evidencia ${evidence.name}"
        }
        return FeatureAvailability(feature, evidence, dimensions, reason)
    }

    fun controls(openId: String, physicalId: String? = null): AvailableCameraControls {
        val camera = camera(openId, physicalId) ?: return AvailableCameraControls()
        val controls = camera.controls
        val lens = camera.lens
        return AvailableCameraControls(
            manualExposure = controls.manualExposureAvailable,
            manualWhiteBalance = controls.manualPostProcessing &&
                controls.colorCorrectionModes.isNotEmpty(),
            manualFocus = (lens.minimumFocusDistanceDiopters ?: 0f) > 0f,
            autofocus = controls.afModes.any { it != 0 },
            tapToFocus = controls.maximumAfRegions > 0,
            aeLock = controls.aeLockAvailable,
            awbLock = controls.awbLockAvailable,
            opticalStabilization = lens.opticalStabilizationModes.any { it != 0 },
            videoStabilization = controls.videoStabilizationModes.any { it != 0 },
            flash = controls.flashAvailable
        )
    }

    fun declaredFrameRates(
        openId: String,
        physicalId: String?,
        format: StreamFormat,
        dimensions: Dimensions,
        candidates: List<Int>
    ): List<Int> {
        val camera = camera(openId, physicalId) ?: return emptyList()
        val stream = camera.streams.firstOrNull {
            it.format == format && it.dimensions == dimensions
        } ?: return emptyList()
        val maximum = stream.maximumDeclaredFps?.let(::floor)?.toInt()
            ?: candidates.maxOrNull().orEmptyMaximum()
        val aeMaximum = camera.controls.targetFpsRanges.maxOfOrNull { it.maximum }
            ?.let(::floor)?.toInt() ?: maximum
        return candidates.distinct().sorted().filter { it <= minOf(maximum, aeMaximum) }
    }

    companion object {
        fun discover(cameraManager: CameraManager): DeviceCapabilitiesGateway =
            DeviceCapabilitiesGateway(AndroidDeviceCapabilityProbe(cameraManager).probe())

        internal fun from(profile: DeviceCapabilityProfile): DeviceCapabilitiesGateway =
            DeviceCapabilitiesGateway(profile)
    }
}

internal data class FeatureAvailability(
    val feature: CaptureFeature,
    val evidence: SupportEvidence,
    val dimensions: Set<Dimensions>,
    val reason: String
) {
    val available: Boolean
        get() = evidence != SupportEvidence.UNSUPPORTED && dimensions.isNotEmpty()
}

internal data class AvailableCameraControls(
    val manualExposure: Boolean = false,
    val manualWhiteBalance: Boolean = false,
    val manualFocus: Boolean = false,
    val autofocus: Boolean = false,
    val tapToFocus: Boolean = false,
    val aeLock: Boolean = false,
    val awbLock: Boolean = false,
    val opticalStabilization: Boolean = false,
    val videoStabilization: Boolean = false,
    val flash: Boolean = false
)

private fun Int?.orEmptyMaximum(): Int = this ?: 0
