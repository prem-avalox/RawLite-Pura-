package com.example.rawlite.device

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/**
 * Passive capability discovery. It never opens a camera, configures a session or writes footage.
 */
internal class AndroidDeviceCapabilityProbe(
    private val cameraManager: CameraManager
) {
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
        cameraManager.cameraIdList.forEach { logicalId ->
            val logical = runCatching {
                cameraManager.getCameraCharacteristics(logicalId)
            }.getOrNull() ?: return@forEach
            characteristicsById[logicalId] = logical
            logical.physicalCameraIds.forEach { physicalId ->
                runCatching { cameraManager.getCameraCharacteristics(physicalId) }
                    .getOrNull()
                    ?.let { characteristicsById.putIfAbsent(physicalId, it) }
            }
        }
        return characteristicsById.map { (cameraId, characteristics) ->
            val streamMap = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )
            CameraCapability(
                cameraId = cameraId,
                facing = facing(characteristics.get(CameraCharacteristics.LENS_FACING)),
                rawSensorSizes = streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)
                    ?.map { Dimensions(it.width, it.height) }
                    ?.toSet()
                    .orEmpty(),
                raw10Sizes = streamMap?.getOutputSizes(ImageFormat.RAW10)
                    ?.map { Dimensions(it.width, it.height) }
                    ?.toSet()
                    .orEmpty(),
                maximumRawOutputs = characteristics.get(
                    CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_RAW
                ) ?: 0,
                evidence = SupportEvidence.DECLARED
            )
        }
    }

    private fun encoderCapabilities(): List<EncoderCapability> =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.mapNotNull { codec ->
            if (!codec.isEncoder || codec.supportedTypes.none {
                    it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
                }
            ) {
                return@mapNotNull null
            }
            val capabilities = runCatching {
                codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            }.getOrNull() ?: return@mapNotNull null
            EncoderCapability(
                name = codec.name,
                hevcMain10 = capabilities.profileLevels.any {
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
                evidence = SupportEvidence.DECLARED
            )
        }

    private fun facing(value: Int?): CameraCapability.Facing = when (value) {
        CameraCharacteristics.LENS_FACING_BACK -> CameraCapability.Facing.BACK
        CameraCharacteristics.LENS_FACING_FRONT -> CameraCapability.Facing.FRONT
        CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraCapability.Facing.EXTERNAL
        else -> CameraCapability.Facing.UNKNOWN
    }
}
