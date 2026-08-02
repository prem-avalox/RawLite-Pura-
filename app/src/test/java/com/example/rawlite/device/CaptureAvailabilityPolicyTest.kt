package com.example.rawlite.device

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureAvailabilityPolicyTest {
    private val rawCamera = CameraCapability(
        cameraId = "0",
        facing = CameraCapability.Facing.BACK,
        rawSensorSizes = setOf(Dimensions(4080, 3072)),
        raw10Sizes = setOf(Dimensions(2032, 1536)),
        maximumRawOutputs = 1,
        evidence = SupportEvidence.SESSION_VERIFIED
    )

    @Test
    fun `pure10 is limited by the least verified required component`() {
        val encoder = EncoderCapability(
            name = "hardware.hevc",
            hevcMain10 = true,
            p010 = true,
            hardwareAccelerated = true,
            evidence = SupportEvidence.DECLARED
        )

        assertEquals(
            SupportEvidence.DECLARED,
            CaptureAvailabilityPolicy.support(CaptureFeature.PURE10, rawCamera, listOf(encoder))
        )
    }

    @Test
    fun `pure10 is unavailable without a hardware main10 p010 path`() {
        val eightBitEncoder = EncoderCapability(
            name = "hardware.hevc",
            hevcMain10 = false,
            p010 = false,
            hardwareAccelerated = true,
            evidence = SupportEvidence.SESSION_VERIFIED
        )

        assertEquals(
            SupportEvidence.UNSUPPORTED,
            CaptureAvailabilityPolicy.support(
                CaptureFeature.PURE10,
                rawCamera,
                listOf(eightBitEncoder)
            )
        )
    }

    @Test
    fun `raw formats remain independently detectable`() {
        assertEquals(
            SupportEvidence.SESSION_VERIFIED,
            CaptureAvailabilityPolicy.support(CaptureFeature.CINEMA_DNG, rawCamera, emptyList())
        )
        assertEquals(
            SupportEvidence.SESSION_VERIFIED,
            CaptureAvailabilityPolicy.support(CaptureFeature.RAW10, rawCamera, emptyList())
        )
    }
}
