package com.example.rawlite.gallery

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pixel 6 main-camera DNG profile extracted from
 * PXL_20260805_192832722.RAW-02.ORIGINAL.dng.
 */
internal object Pixel6MainDngColorProfile {
    /**
     * D65 camera RGB to linear Rec.2020 derived from ColorMatrix2 in the Pixel 6
     * reference DNG. Rows are normalized so a white-balanced camera neutral maps
     * to neutral Rec.2020 before the transfer function is applied.
     */
    val CAMERA_RGB_TO_REC2020_D65: FloatArray = floatArrayOf(
        0.9352855f, 0.22501442f, -0.16029993f,
        -0.32346301f, 1.8871065f, -0.56364349f,
        -0.05047278f, -0.25077162f, 1.3012444f
    )

    fun matches(metadata: JSONObject): Boolean {
        val cfa = metadata.optString("cfa", "")
        return metadata.optString("camera_id") in setOf("0", "2") &&
            metadata.optString("camera").startsWith("Principal") &&
            (cfa.isBlank() || cfa == "GBRG") &&
            metadata.optInt("width") in 1900..4080 &&
            metadata.optInt("height") in 1000..3072
    }

    fun apply(metadata: JSONObject) {
        if (!matches(metadata)) return
        metadata.put("color_matrix_1", matrix(
            12453 to 10000, -6954 to 10000, 134 to 10000,
            -2949 to 10000, 11718 to 10000, 1364 to 10000,
            71 to 10000, 1626 to 10000, 6290 to 10000
        ))
        metadata.put("color_matrix_2", matrix(
            10537 to 10000, -4544 to 10000, -906 to 10000,
            -3178 to 10000, 11784 to 10000, 1552 to 10000,
            -109 to 10000, 1865 to 10000, 5324 to 10000
        ))
        metadata.put("reference_illuminant_1", 17)
        metadata.put("reference_illuminant_2", 21)
        metadata.put("baseline_exposure", 0.59)
        metadata.put("unique_camera_model", "Google Pixel 6")
        metadata.put("color_profile", "Google Pixel 6 main DNG")
        metadata.put("color_profile_source", "PXL_20260805_192832722.RAW-02.ORIGINAL.dng")

        // The official Pixel 6 DNG does not expose these as normal tags. Avoid
        // injecting legacy guesses into Resolve-facing CinemaDNG exports.
        metadata.remove("calibration_matrix_1")
        metadata.remove("calibration_matrix_2")
        metadata.remove("forward_matrix_1")
        metadata.remove("forward_matrix_2")
    }

    fun cameraRgbToRec2020D65Json(): JSONArray = JSONArray().apply {
        CAMERA_RGB_TO_REC2020_D65.forEach { put(it.toDouble()) }
    }

    private fun matrix(vararg values: Pair<Int, Int>) = JSONArray().apply {
        values.forEach { (numerator, denominator) ->
            put(JSONArray().put(numerator).put(denominator))
        }
    }
}
