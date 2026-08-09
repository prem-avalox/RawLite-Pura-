package com.example.rawlite.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFormatPolicyTest {
    @Test
    fun `full raw maps to CinemaDNG`() {
        assertEquals(CaptureOutput.CINEMA_DNG, CaptureFormatPolicy.output(CaptureMode.RAW, 0))
    }

    @Test
    fun `reduced raw maps to packed RAW10`() {
        assertEquals(CaptureOutput.RAW10, CaptureFormatPolicy.output(CaptureMode.RAW, 1))
    }

    @Test
    fun `pure10 accepts full or reduced source`() {
        assertEquals(CaptureOutput.PURE10, CaptureFormatPolicy.output(CaptureMode.PURE10, 1))
        assertTrue(CaptureFormatPolicy.selectionIsValid(CaptureMode.PURE10, 0))
        assertTrue(CaptureFormatPolicy.selectionIsValid(CaptureMode.PURE10, 1))
    }
}
