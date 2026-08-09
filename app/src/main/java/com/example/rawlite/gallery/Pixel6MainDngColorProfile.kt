package com.example.rawlite.gallery

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
    private val XYZ_TO_REC2020 = doubleArrayOf(
        1.716651187971268, -0.355670783776393, -0.253366281373660,
        -0.666684351832489, 1.616481236634939, 0.015768545813911,
        0.017639857445311, -0.042770613257809, 0.942103121235474
    )

    /**
     * D65 camera RGB to linear Rec.2020 derived from ColorMatrix2 in the Pixel 6
     * reference DNG. Rows are normalized so a white-balanced camera neutral maps
     * to neutral Rec.2020 before the transfer function is applied.
     */
    val CAMERA_RGB_TO_REC2020_D65: FloatArray = cameraRgbToRec2020(6500)
    private val PURE10_REC2020_NEUTRAL_CORRECTION = floatArrayOf(
        1.0f,
        0.985f,
        1.035f
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

    fun cameraRgbToRec2020(kelvin: Int): FloatArray {
        val d65Weight = d65InterpolationWeight(kelvin)
        val colorMatrix = DoubleArray(9) { i ->
            COLOR_MATRIX_A[i] * (1.0 - d65Weight) + COLOR_MATRIX_D65[i] * d65Weight
        }
        val cameraToXyz = invert3x3(colorMatrix)
        val cameraToRec2020 = multiply3x3(XYZ_TO_REC2020, cameraToXyz)
        normalizeNeutral(cameraToRec2020)
        return FloatArray(9) { cameraToRec2020[it].toFloat() }
    }

    fun cameraRgbToRec2020Json(kelvin: Int): JSONArray = JSONArray().apply {
        cameraRgbToRec2020(kelvin).forEach { put(it.toDouble()) }
    }

    fun pure10CameraRgbToRec2020(): FloatArray {
        val base = CAMERA_RGB_TO_REC2020_D65.copyOf()
        repeat(3) { column -> base[column] *= PURE10_REC2020_NEUTRAL_CORRECTION[0] }
        repeat(3) { column -> base[3 + column] *= PURE10_REC2020_NEUTRAL_CORRECTION[1] }
        repeat(3) { column -> base[6 + column] *= PURE10_REC2020_NEUTRAL_CORRECTION[2] }
        return base
    }

    fun pure10CameraRgbToRec2020Json(): JSONArray = JSONArray().apply {
        pure10CameraRgbToRec2020().forEach { put(it.toDouble()) }
    }

    fun pure10NeutralCorrectionJson(): JSONArray = JSONArray().apply {
        PURE10_REC2020_NEUTRAL_CORRECTION.forEach { put(it.toDouble()) }
    }

    fun d65InterpolationWeight(kelvin: Int): Double {
        val clamped = kelvin.coerceIn(2856, 6500)
        val tungstenMired = 1_000_000.0 / 2856.0
        val d65Mired = 1_000_000.0 / 6500.0
        val currentMired = 1_000_000.0 / clamped
        return ((tungstenMired - currentMired) / (tungstenMired - d65Mired))
            .coerceIn(0.0, 1.0)
    }

    private fun matrix(vararg values: Pair<Int, Int>) = JSONArray().apply {
        values.forEach { (numerator, denominator) ->
            put(JSONArray().put(numerator).put(denominator))
        }
    }

    private fun multiply3x3(a: DoubleArray, b: DoubleArray): DoubleArray =
        DoubleArray(9) { index ->
            val row = index / 3
            val col = index % 3
            a[row * 3] * b[col] +
                a[row * 3 + 1] * b[3 + col] +
                a[row * 3 + 2] * b[6 + col]
        }

    private fun invert3x3(m: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val aCof = e * i - f * h
        val bCof = -(d * i - f * g)
        val cCof = d * h - e * g
        val determinant = a * aCof + b * bCof + c * cCof
        require(kotlin.math.abs(determinant) > 1e-9) { "Color matrix is not invertible" }
        return doubleArrayOf(
            aCof / determinant,
            -(b * i - c * h) / determinant,
            (b * f - c * e) / determinant,
            bCof / determinant,
            (a * i - c * g) / determinant,
            -(a * f - c * d) / determinant,
            cCof / determinant,
            -(a * h - b * g) / determinant,
            (a * e - b * d) / determinant
        )
    }

    private fun normalizeNeutral(matrix: DoubleArray) {
        for (row in 0 until 3) {
            val offset = row * 3
            val sum = matrix[offset] + matrix[offset + 1] + matrix[offset + 2]
            if (kotlin.math.abs(sum) > 1e-9) {
                matrix[offset] /= sum
                matrix[offset + 1] /= sum
                matrix[offset + 2] /= sum
            }
        }
    }
}
