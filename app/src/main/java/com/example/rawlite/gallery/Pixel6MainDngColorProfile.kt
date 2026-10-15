package com.example.rawlite.gallery

import com.example.rawlite.color.DngColorScience
import com.example.rawlite.color.DualIlluminantColorProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pixel 6 main-camera DNG profile extracted from
 * PXL_20260805_192832722.RAW-02.ORIGINAL.dng.
 */
internal object Pixel6MainDngColorProfile {
    private val COLOR_MATRIX_A = doubleArrayOf(
        1.2453, -0.6954, 0.0134,
        -0.2949, 1.1718, 0.1364,
        0.0071, 0.1626, 0.6290
    )
    private val COLOR_MATRIX_D65 = doubleArrayOf(
        1.0537, -0.4544, -0.0906,
        -0.3178, 1.1784, 0.1552,
        -0.0109, 0.1865, 0.5324
    )
    private val PROFILE = DualIlluminantColorProfile(
        colorMatrix1 = COLOR_MATRIX_A,
        colorMatrix2 = COLOR_MATRIX_D65,
        illuminant1Kelvin = 2856.0,
        illuminant2Kelvin = 6500.0
    )

    /** D65 camera RGB to linear Rec.2020 using the DNG white-point model. */
    val CAMERA_RGB_TO_REC2020_D65: FloatArray = cameraRgbToRec2020(6500)
    /** D65 camera RGB to linear Rec.709 using the DNG white-point model. */
    val CAMERA_RGB_TO_REC709_D65: FloatArray = cameraRgbToRec709(6500)
    const val BASELINE_EXPOSURE_EV = 0.59
    const val DEFAULT_RENDER_GAIN = 1.505247f

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
        metadata.put("baseline_exposure", BASELINE_EXPOSURE_EV)
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

    fun cameraRgbToRec2020(kelvin: Int): FloatArray {
        val neutral = DngColorScience.neutralForTemperature(PROFILE, kelvin.toDouble())
        return DngColorScience.cameraToLinearRec2020(PROFILE, neutral)
    }

    fun cameraRgbToRec2020(
        redGain: Float,
        greenEvenGain: Float,
        greenOddGain: Float,
        blueGain: Float
    ): FloatArray {
        val neutral = DngColorScience.asShotNeutralFromGains(
            redGain,
            greenEvenGain,
            greenOddGain,
            blueGain
        )
        return DngColorScience.cameraToLinearRec2020(PROFILE, neutral)
    }

    fun whiteBalancedCameraRgbToRec2020(
        redGain: Float,
        greenEvenGain: Float,
        greenOddGain: Float,
        blueGain: Float
    ): FloatArray {
        val neutral = DngColorScience.asShotNeutralFromGains(
            redGain,
            greenEvenGain,
            greenOddGain,
            blueGain
        )
        return DngColorScience.whiteBalancedCameraToLinearRec2020(PROFILE, neutral)
    }

    fun cameraRgbToRec709(kelvin: Int): FloatArray {
        val neutral = DngColorScience.neutralForTemperature(PROFILE, kelvin.toDouble())
        return DngColorScience.cameraToLinearRec709(PROFILE, neutral)
    }

    fun cameraRgbToRec709(
        redGain: Float,
        greenEvenGain: Float,
        greenOddGain: Float,
        blueGain: Float
    ): FloatArray {
        val neutral = DngColorScience.asShotNeutralFromGains(
            redGain,
            greenEvenGain,
            greenOddGain,
            blueGain
        )
        return DngColorScience.cameraToLinearRec709(PROFILE, neutral)
    }

    fun whiteBalancedCameraRgbToRec709(
        redGain: Float,
        greenEvenGain: Float,
        greenOddGain: Float,
        blueGain: Float
    ): FloatArray {
        val neutral = DngColorScience.asShotNeutralFromGains(
            redGain,
            greenEvenGain,
            greenOddGain,
            blueGain
        )
        return DngColorScience.whiteBalancedCameraToLinearRec709(PROFILE, neutral)
    }

    fun cameraRgbToRec2020Json(kelvin: Int): JSONArray = JSONArray().apply {
        cameraRgbToRec2020(kelvin).forEach { put(it.toDouble()) }
    }

    fun whiteBalancedCameraRgbToRec2020Json(kelvin: Int): JSONArray = JSONArray().apply {
        val gains = gainsForKelvin(kelvin)
        whiteBalancedCameraRgbToRec2020(
            redGain = gains[0],
            greenEvenGain = 1f,
            greenOddGain = 1f,
            blueGain = gains[1]
        ).forEach { put(it.toDouble()) }
    }

    private fun gainsForKelvin(kelvin: Int): FloatArray {
        val k = kelvin.coerceIn(2000, 10000).toFloat()
        val red: Float
        val blue: Float
        if (k <= 6500f) {
            val t = (k - 2000f) / 4500f
            red = 1.05f + 1.15f * t
            blue = 3.65f - 2.25f * t
        } else {
            val t = (k - 6500f) / 3500f
            red = 2.20f + 0.85f * t
            blue = 1.40f - 0.35f * t
        }
        return floatArrayOf(red, blue)
    }

    private fun matrix(vararg values: Pair<Int, Int>) = JSONArray().apply {
        values.forEach { (numerator, denominator) ->
            put(JSONArray().put(numerator).put(denominator))
        }
    }
}
