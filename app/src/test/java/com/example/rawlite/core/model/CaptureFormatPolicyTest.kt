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
    fun `ten-bit maps to TEN_BIT output and accepts full or reduced source`() {
        assertEquals(CaptureOutput.TEN_BIT, CaptureFormatPolicy.output(CaptureMode.TEN_BIT, 1))
        assertTrue(CaptureFormatPolicy.selectionIsValid(CaptureMode.TEN_BIT, 0))
        assertTrue(CaptureFormatPolicy.selectionIsValid(CaptureMode.TEN_BIT, 1))
    }

    @Test
    fun `eight-bit maps to EIGHT_BIT output and accepts full or reduced source`() {
        assertEquals(CaptureOutput.EIGHT_BIT, CaptureFormatPolicy.output(CaptureMode.EIGHT_BIT, 1))
        assertTrue(CaptureFormatPolicy.selectionIsValid(CaptureMode.EIGHT_BIT, 0))
        assertTrue(CaptureFormatPolicy.selectionIsValid(CaptureMode.EIGHT_BIT, 1))
    }
}
