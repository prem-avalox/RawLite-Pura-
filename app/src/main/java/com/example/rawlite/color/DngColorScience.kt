package com.example.rawlite.color

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

internal class DualIlluminantColorProfile(
    val colorMatrix1: DoubleArray,
    val colorMatrix2: DoubleArray,
    val illuminant1Kelvin: Double,
    val illuminant2Kelvin: Double
) {
    init {
        require(colorMatrix1.size == MATRIX_SIZE && colorMatrix2.size == MATRIX_SIZE)
        require(illuminant1Kelvin > 0.0 && illuminant2Kelvin > 0.0)
    }

    private companion object {
        const val MATRIX_SIZE = 9
    }
}

/**
 * Minimal three-channel implementation of the Adobe DNG dual-illuminant color model.
 *
 * ColorMatrix maps CIE XYZ under the scene illuminant to camera RGB. The resulting
 * transform maps black-subtracted, white-level-normalized camera RGB directly to
 * linear Rec.2020 D65; white balance must not be applied a second time.
 */
internal object DngColorScience {
    private data class Xy(val x: Double, val y: Double)
    private data class TemperaturePoint(
        val reciprocalMegakelvin: Double,
        val u: Double,
        val v: Double,
        val slope: Double
    )

    fun cameraToLinearRec2020(
        profile: DualIlluminantColorProfile,
        asShotNeutral: DoubleArray
    ): FloatArray {
        require(asShotNeutral.size == 3)
        val neutral = normalizeMaximum(asShotNeutral)
        require(neutral.all { it.isFinite() && it > 0.0 })

        val sceneWhite = neutralToXy(profile, neutral)
        val xyzToCamera = interpolatedColorMatrix(profile, sceneWhite)
        var pcsToCamera = multiply3x3(
            xyzToCamera,
            mapWhiteMatrix(D50, sceneWhite)
        )
        val cameraWhite = multiply3x3Vector(pcsToCamera, xyToXyz(D50))
        val scale = cameraWhite.maxOrNull()?.takeIf { it > EPSILON }
            ?: error("Invalid DNG camera white")
        pcsToCamera = DoubleArray(MATRIX_SIZE) { pcsToCamera[it] / scale }

        val cameraToPcsD50 = invert3x3(pcsToCamera)
        val cameraToXyzD65 = multiply3x3(
            mapWhiteMatrix(D50, D65),
            cameraToPcsD50
        )
        val cameraToRec2020 = multiply3x3(XYZ_D65_TO_REC2020, cameraToXyzD65)
        return FloatArray(MATRIX_SIZE) { cameraToRec2020[it].toFloat() }
    }

    fun asShotNeutralFromGains(
        redGain: Float,
        greenEvenGain: Float,
        greenOddGain: Float,
        blueGain: Float
    ): DoubleArray {
        val green = ((greenEvenGain + greenOddGain) * 0.5f).coerceAtLeast(MIN_GAIN)
        return normalizeMaximum(
            doubleArrayOf(
                (green / redGain.coerceAtLeast(MIN_GAIN)).toDouble(),
                1.0,
                (green / blueGain.coerceAtLeast(MIN_GAIN)).toDouble()
            )
        )
    }

    fun neutralForTemperature(
        profile: DualIlluminantColorProfile,
        kelvin: Double
    ): DoubleArray {
        val white = xyForTemperature(kelvin.coerceIn(1667.0, 100000.0))
        return normalizeMaximum(
            multiply3x3Vector(interpolatedColorMatrix(profile, white), xyToXyz(white))
        )
    }

    internal fun applyMatrix(matrix: FloatArray, value: DoubleArray): DoubleArray {
        require(matrix.size == MATRIX_SIZE && value.size == 3)
        return DoubleArray(3) { row ->
            matrix[row * 3] * value[0] +
                matrix[row * 3 + 1] * value[1] +
                matrix[row * 3 + 2] * value[2]
        }
    }

    private fun neutralToXy(
        profile: DualIlluminantColorProfile,
        neutral: DoubleArray
    ): Xy {
        var last = D50
        repeat(MAX_NEUTRAL_PASSES) { pass ->
            val xyzToCamera = interpolatedColorMatrix(profile, last)
            val next = xyzToXy(multiply3x3Vector(invert3x3(xyzToCamera), neutral))
            if (abs(next.x - last.x) + abs(next.y - last.y) < WHITE_CONVERGENCE) {
                return next
            }
            if (pass == MAX_NEUTRAL_PASSES - 1) {
                return Xy((last.x + next.x) * 0.5, (last.y + next.y) * 0.5)
            }
            last = next
        }
        return last
    }

    private fun interpolatedColorMatrix(
        profile: DualIlluminantColorProfile,
        white: Xy
    ): DoubleArray {
        val temperature = temperatureForXy(white)
        val first = profile.illuminant1Kelvin
        val second = profile.illuminant2Kelvin
        val firstWeight = when {
            temperature <= minOf(first, second) -> if (first <= second) 1.0 else 0.0
            temperature >= maxOf(first, second) -> if (first >= second) 1.0 else 0.0
            else -> {
                val reciprocal = 1.0 / temperature
                (reciprocal - 1.0 / second) / (1.0 / first - 1.0 / second)
            }
        }.coerceIn(0.0, 1.0)
        return DoubleArray(MATRIX_SIZE) { index ->
            firstWeight * profile.colorMatrix1[index] +
                (1.0 - firstWeight) * profile.colorMatrix2[index]
        }
    }

    /** Linearized Bradford chromatic adaptation, matching the DNG SDK. */
    private fun mapWhiteMatrix(from: Xy, to: Xy): DoubleArray {
        val fromCone = multiply3x3Vector(BRADFORD, xyToXyz(from))
        val toCone = multiply3x3Vector(BRADFORD, xyToXyz(to))
        val scale = doubleArrayOf(
            (toCone[0] / fromCone[0]).coerceIn(0.1, 10.0),
            (toCone[1] / fromCone[1]).coerceIn(0.1, 10.0),
            (toCone[2] / fromCone[2]).coerceIn(0.1, 10.0)
        )
        return multiply3x3(
            multiply3x3(invert3x3(BRADFORD), diagonal3x3(scale)),
            BRADFORD
        )
    }

    private fun temperatureForXy(xy: Xy): Double {
        val denominator = 1.5 - xy.x + 6.0 * xy.y
        val u = 2.0 * xy.x / denominator
        val v = 3.0 * xy.y / denominator
        var previousDistance = 0.0

        for (index in 1 until TEMPERATURE_TABLE.size) {
            val point = TEMPERATURE_TABLE[index]
            val length = sqrt(1.0 + point.slope * point.slope)
            val du = 1.0 / length
            val dv = point.slope / length
            val distance = -(u - point.u) * dv + (v - point.v) * du
            if (distance <= 0.0 || index == TEMPERATURE_TABLE.lastIndex) {
                val positiveDistance = max(-distance, 0.0)
                val fraction = if (index == 1) 0.0 else {
                    positiveDistance / (previousDistance + positiveDistance)
                }
                val reciprocal =
                    TEMPERATURE_TABLE[index - 1].reciprocalMegakelvin * fraction +
                        point.reciprocalMegakelvin * (1.0 - fraction)
                return if (reciprocal > EPSILON) 1_000_000.0 / reciprocal else 100_000.0
            }
            previousDistance = distance
        }
        return 6500.0
    }

    private fun xyForTemperature(kelvin: Double): Xy {
        val reciprocal = 1_000_000.0 / kelvin
        for (index in 0 until TEMPERATURE_TABLE.lastIndex) {
            val first = TEMPERATURE_TABLE[index]
            val second = TEMPERATURE_TABLE[index + 1]
            if (reciprocal < second.reciprocalMegakelvin || index == TEMPERATURE_TABLE.lastIndex - 1) {
                val fraction = (
                    second.reciprocalMegakelvin - reciprocal
                    ) / (second.reciprocalMegakelvin - first.reciprocalMegakelvin)
                val u = first.u * fraction + second.u * (1.0 - fraction)
                val v = first.v * fraction + second.v * (1.0 - fraction)
                val denominator = u - 4.0 * v + 2.0
                return Xy(1.5 * u / denominator, v / denominator)
            }
        }
        return D65
    }

    private fun xyToXyz(xy: Xy): DoubleArray = doubleArrayOf(
        xy.x / xy.y,
        1.0,
        (1.0 - xy.x - xy.y) / xy.y
    )

    private fun xyzToXy(xyz: DoubleArray): Xy {
        val sum = xyz.sum()
        require(sum.isFinite() && abs(sum) > EPSILON) { "Invalid XYZ white" }
        return Xy(xyz[0] / sum, xyz[1] / sum)
    }

    private fun normalizeMaximum(values: DoubleArray): DoubleArray {
        val maximum = values.maxOrNull()?.coerceAtLeast(EPSILON) ?: 1.0
        return DoubleArray(values.size) { values[it] / maximum }
    }

    private fun diagonal3x3(values: DoubleArray): DoubleArray = doubleArrayOf(
        values[0], 0.0, 0.0,
        0.0, values[1], 0.0,
        0.0, 0.0, values[2]
    )

    private fun multiply3x3(a: DoubleArray, b: DoubleArray): DoubleArray =
        DoubleArray(MATRIX_SIZE) { index ->
            val row = index / 3
            val column = index % 3
            a[row * 3] * b[column] +
                a[row * 3 + 1] * b[3 + column] +
                a[row * 3 + 2] * b[6 + column]
        }

    private fun multiply3x3Vector(matrix: DoubleArray, vector: DoubleArray): DoubleArray =
        DoubleArray(3) { row ->
            matrix[row * 3] * vector[0] +
                matrix[row * 3 + 1] * vector[1] +
                matrix[row * 3 + 2] * vector[2]
        }

    private fun invert3x3(matrix: DoubleArray): DoubleArray {
        val a = matrix[0]; val b = matrix[1]; val c = matrix[2]
        val d = matrix[3]; val e = matrix[4]; val f = matrix[5]
        val g = matrix[6]; val h = matrix[7]; val i = matrix[8]
        val aCofactor = e * i - f * h
        val bCofactor = -(d * i - f * g)
        val cCofactor = d * h - e * g
        val determinant = a * aCofactor + b * bCofactor + c * cCofactor
        require(abs(determinant) > EPSILON) { "Color matrix is not invertible" }
        return doubleArrayOf(
            aCofactor / determinant,
            -(b * i - c * h) / determinant,
            (b * f - c * e) / determinant,
            bCofactor / determinant,
            (a * i - c * g) / determinant,
            -(a * f - c * d) / determinant,
            cCofactor / determinant,
            -(a * h - b * g) / determinant,
            (a * e - b * d) / determinant
        )
    }

    private val D50 = Xy(0.3457, 0.3585)
    private val D65 = Xy(0.3127, 0.3290)
    private val BRADFORD = doubleArrayOf(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296
    )
    private val XYZ_D65_TO_REC2020 = doubleArrayOf(
        1.716651187971268, -0.355670783776393, -0.253366281373660,
        -0.666684351832489, 1.616481236634939, 0.015768545813911,
        0.017639857445311, -0.042770613257809, 0.942103121235474
    )
    // Wyszecki & Stiles reciprocal-temperature table used by the Adobe DNG SDK.
    private val TEMPERATURE_TABLE = arrayOf(
        TemperaturePoint(0.0, 0.18006, 0.26352, -0.24341),
        TemperaturePoint(10.0, 0.18066, 0.26589, -0.25479),
        TemperaturePoint(20.0, 0.18133, 0.26846, -0.26876),
        TemperaturePoint(30.0, 0.18208, 0.27119, -0.28539),
        TemperaturePoint(40.0, 0.18293, 0.27407, -0.30470),
        TemperaturePoint(50.0, 0.18388, 0.27709, -0.32675),
        TemperaturePoint(60.0, 0.18494, 0.28021, -0.35156),
        TemperaturePoint(70.0, 0.18611, 0.28342, -0.37915),
        TemperaturePoint(80.0, 0.18740, 0.28668, -0.40955),
        TemperaturePoint(90.0, 0.18880, 0.28997, -0.44278),
        TemperaturePoint(100.0, 0.19032, 0.29326, -0.47888),
        TemperaturePoint(125.0, 0.19462, 0.30141, -0.58204),
        TemperaturePoint(150.0, 0.19962, 0.30921, -0.70471),
        TemperaturePoint(175.0, 0.20525, 0.31647, -0.84901),
        TemperaturePoint(200.0, 0.21142, 0.32312, -1.0182),
        TemperaturePoint(225.0, 0.21807, 0.32909, -1.2168),
        TemperaturePoint(250.0, 0.22511, 0.33439, -1.4512),
        TemperaturePoint(275.0, 0.23247, 0.33904, -1.7298),
        TemperaturePoint(300.0, 0.24010, 0.34308, -2.0637),
        TemperaturePoint(325.0, 0.24702, 0.34655, -2.4681),
        TemperaturePoint(350.0, 0.25591, 0.34951, -2.9641),
        TemperaturePoint(375.0, 0.26400, 0.35200, -3.5814),
        TemperaturePoint(400.0, 0.27218, 0.35407, -4.3633),
        TemperaturePoint(425.0, 0.28039, 0.35577, -5.3762),
        TemperaturePoint(450.0, 0.28863, 0.35714, -6.7262),
        TemperaturePoint(475.0, 0.29685, 0.35823, -8.5955),
        TemperaturePoint(500.0, 0.30505, 0.35907, -11.324),
        TemperaturePoint(525.0, 0.31320, 0.35968, -15.628),
        TemperaturePoint(550.0, 0.32129, 0.36011, -23.325),
        TemperaturePoint(575.0, 0.32931, 0.36038, -40.770),
        TemperaturePoint(600.0, 0.33724, 0.36051, -116.45)
    )

    private const val MATRIX_SIZE = 9
    private const val MAX_NEUTRAL_PASSES = 30
    private const val WHITE_CONVERGENCE = 1e-7
    private const val EPSILON = 1e-12
    private const val MIN_GAIN = 0.0001f
}
