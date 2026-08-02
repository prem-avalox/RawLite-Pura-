package com.example.rawlite.capture.metadata

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.ColorSpaceTransform
import android.util.Size
import com.example.rawlite.PcmAudioRecorder
import com.example.rawlite.Pure10Encoder
import org.json.JSONArray
import org.json.JSONObject

internal data class CaptureMetadataContext(
    val cameraLabel: String,
    val cameraId: String,
    val characteristics: CameraCharacteristics,
    val captureSize: Size,
    val previewSize: Size,
    val frameRate: Int,
    val audioEnabled: Boolean,
    val displayRotationDegrees: Int,
    val detectedDeviceOrientationDegrees: Int,
    val uiContentRotationDegrees: Int,
    val captureRotationDegrees: Int
)

/** Owns the on-disk clip contract independently from Activity and UI lifecycle code. */
internal object CaptureMetadataFactory {
    fun raw10(
        context: CaptureMetadataContext,
        technicalOverlay: String,
        checkpointFrames: Int
    ): JSONObject {
        val chars = context.characteristics
        val black = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val blackLevels = JSONArray()
        for (row in 0..1) {
            for (column in 0..1) {
                blackLevels.put(black?.getOffsetForIndex(column, row) ?: 0)
            }
        }
        return common(context).apply {
            put("format", "rawlite-raw10")
            put("version", 3)
            put("bits_per_sample", 10)
            put("row_bytes", (context.captureSize.width * 10 + 7) / 8)
            put("writer", "native-writev")
            put("state_file", "clip.state")
            put("technical_overlay", technicalOverlay)
            put("checkpoint_frames", checkpointFrames)
            put(
                "camera_timestamp_source",
                if (chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                ) "REALTIME" else "UNKNOWN"
            )
            put("cfa", cfaName(chars.get(
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
            )))
            put("black_level", blackLevels)
            put("white_level", chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023)
            put("binning", JSONArray().put(2).put(2))
            put("color_matrix_1", colorTransformJson(chars.get(
                CameraCharacteristics.SENSOR_COLOR_TRANSFORM1
            )))
            put("color_matrix_2", colorTransformJson(chars.get(
                CameraCharacteristics.SENSOR_COLOR_TRANSFORM2
            )))
            put("calibration_matrix_1", colorTransformJson(chars.get(
                CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1
            )))
            put("calibration_matrix_2", colorTransformJson(chars.get(
                CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2
            )))
            put("forward_matrix_1", colorTransformJson(chars.get(
                CameraCharacteristics.SENSOR_FORWARD_MATRIX1
            )))
            put("forward_matrix_2", colorTransformJson(chars.get(
                CameraCharacteristics.SENSOR_FORWARD_MATRIX2
            )))
            put("reference_illuminant_1", chars.get(
                CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1
            ) ?: 21)
            put("reference_illuminant_2", chars.get(
                CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2
            ) ?: 17)
        }
    }

    fun cinemaDng(context: CaptureMetadataContext): JSONObject = common(context).apply {
        put("format", "rawlite-dng")
        put("version", 1)
        put("mode", "RAW")
        put("container", "CinemaDNG sequence")
        put("bits_per_sample", 16)
        put("finalized", false)
    }

    fun pure10(context: CaptureMetadataContext, postRawGain: Int): JSONObject =
        common(context).apply {
            put("format", "rawlite-pure10")
            put("version", 1)
            put("mode", "PURE 10")
            put("container", "MP4")
            put("video_file", Pure10Encoder.VIDEO_FILE)
            put("codec", "HEVC")
            put("codec_profile", "Main10")
            put("encoder", Pure10Encoder.CODEC_NAME)
            put("bits_per_sample", 10)
            put("pixel_format", "P010")
            put("color_primaries", "BT.2020")
            put("transfer", "HLG (ARIB STD-B67)")
            put("range", "limited")
            put("hlg_midtone_gain", Pure10Encoder.HLG_MIDTONE_GAIN * postRawGain / 100f)
            put("bit_rate", Pure10Encoder.BIT_RATE)
            put("source", "Camera2 RAW10")
            put("demosaic", "native 2x2")
            put("finalized", false)
        }

    private fun common(context: CaptureMetadataContext): JSONObject {
        val chars = context.characteristics
        val sensorDegrees = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val frontFacing = chars.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        return JSONObject().apply {
            put("camera", context.cameraLabel)
            put("camera_id", context.cameraId)
            put("width", context.captureSize.width)
            put("height", context.captureSize.height)
            put("frame_rate", context.frameRate)
            put("telemetry_file", "telemetry.csv")
            put("audio_enabled", context.audioEnabled)
            put("audio_file", PcmAudioRecorder.AUDIO_FILE)
            put("audio_metadata_file", PcmAudioRecorder.AUDIO_METADATA_FILE)
            put("sync_file", "sync.json")
            put("sensor_orientation", sensorDegrees)
            put("display_rotation_degrees", context.displayRotationDegrees)
            put("detected_device_orientation_degrees", context.detectedDeviceOrientationDegrees)
            put("ui_content_rotation_degrees", context.uiContentRotationDegrees)
            put("front_facing", frontFacing)
            put("rotation_degrees", context.captureRotationDegrees)
            put("preview_width", context.previewSize.width)
            put("preview_height", context.previewSize.height)
        }
    }

    private fun colorTransformJson(transform: ColorSpaceTransform?): JSONArray {
        val output = JSONArray()
        if (transform == null) return output
        for (row in 0..2) {
            for (column in 0..2) {
                val value = transform.getElement(column, row)
                output.put(JSONArray().put(value.numerator).put(value.denominator))
            }
        }
        return output
    }

    private fun cfaName(value: Int?): String = when (value) {
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
        else -> "UNKNOWN"
    }
}
