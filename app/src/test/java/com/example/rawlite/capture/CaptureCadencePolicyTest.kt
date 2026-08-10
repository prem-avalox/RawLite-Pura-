package com.example.rawlite.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureCadencePolicyTest {
    @Test
    fun `auto RAW recording below 30 fps freezes measured exposure`() {
        assertTrue(
            CaptureCadencePolicy.shouldFreezeAutoExposureForRawRecording(
                includeRaw = true,
                fps = 24,
                manualExposure = false,
                snapshotIso = 200,
                snapshotExposureNs = 20_000_000L
            )
        )
    }

    @Test
    fun `30 fps auto RAW recording stays under camera auto exposure`() {
        assertFalse(
            CaptureCadencePolicy.shouldFreezeAutoExposureForRawRecording(
                includeRaw = true,
                fps = 30,
                manualExposure = false,
                snapshotIso = 200,
                snapshotExposureNs = 20_000_000L
            )
        )
    }

    @Test
    fun `manual exposure does not use auto cadence lock`() {
        assertFalse(
            CaptureCadencePolicy.shouldFreezeAutoExposureForRawRecording(
                includeRaw = true,
                fps = 24,
                manualExposure = true,
                snapshotIso = 200,
                snapshotExposureNs = 20_000_000L
            )
        )
    }

    @Test
    fun `24 fps frame duration is exact integer camera duration`() {
        assertEquals(41_666_666L, CaptureCadencePolicy.frameDurationNs(24))
    }

    @Test
    fun `policy name reports cadence lock separately from plain auto`() {
        assertEquals(
            "auto-cadence-lock",
            CaptureCadencePolicy.exposurePolicyName(
                manualExposure = false,
                explicitExposureLock = false,
                autoCadenceLock = true
            )
        )
    }
}
