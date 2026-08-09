package com.example.rawlite.color

import com.example.rawlite.gallery.Pixel6MainDngColorProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DngColorScienceTest {
    @Test
    fun `Pixel 6 daylight capture neutral maps to Rec2020 D65 white`() {
        assertNeutralMapsToWhite(red = 1.95391953f, blue = 1.74040711f)
    }

    @Test
    fun `Pixel 6 mixed daylight capture neutral maps to Rec2020 D65 white`() {
        assertNeutralMapsToWhite(red = 1.62539077f, blue = 2.11910534f)
    }

    @Test
    fun `different as-shot neutrals produce different color transforms`() {
        val first = Pixel6MainDngColorProfile.cameraRgbToRec2020(
            1.95391953f,
            1f,
            1f,
            1.74040711f
        )
        val second = Pixel6MainDngColorProfile.cameraRgbToRec2020(
            1.62539077f,
            1f,
            1f,
            2.11910534f
        )

        assertTrue(first.all(Float::isFinite))
        assertTrue(second.all(Float::isFinite))
        assertFalse(first.contentEquals(second))
        assertTrue(first.indices.any { abs(first[it] - second[it]) > 0.01f })
    }

    private fun assertNeutralMapsToWhite(red: Float, blue: Float) {
        val neutral = DngColorScience.asShotNeutralFromGains(red, 1f, 1f, blue)
        val transform = Pixel6MainDngColorProfile.cameraRgbToRec2020(
            red,
            1f,
            1f,
            blue
        )
        val rec2020 = DngColorScience.applyMatrix(transform, neutral)
        val reference = rec2020.average()

        assertEquals(reference, rec2020[0], 0.0005)
        assertEquals(reference, rec2020[1], 0.0005)
        assertEquals(reference, rec2020[2], 0.0005)
        assertTrue(reference > 0.95 && reference < 1.05)
    }
}
