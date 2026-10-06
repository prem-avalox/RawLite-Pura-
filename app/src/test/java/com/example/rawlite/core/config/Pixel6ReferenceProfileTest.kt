package com.example.rawlite.core.config

import org.junit.Assert.assertEquals
import org.junit.Test

class Pixel6ReferenceProfileTest {
    @Test
    fun `preserves all capture metrics from 0_33 rc1`() {
        val profile = Pixel6ReferenceProfile.capture

        assertEquals("pixel6-v0.33", profile.id)
        assertEquals("c2.exynos.hevc.encoder", profile.preferredHevcMain10Encoder)
        assertEquals(50_000_000, profile.pure10BitRate)
        assertEquals(3.0f, profile.pure10HlgMidtoneGain)
        assertEquals(10, profile.pure10ImageReaderCapacity)
        assertEquals(8, profile.pure10MaximumPendingWrites)
        assertEquals(8, profile.packedRawImageReaderCapacity)
        assertEquals(6, profile.packedRawMaximumPendingWrites)
        assertEquals(4, profile.dngImageReaderCapacity)
        assertEquals(6, profile.maximumPendingMetadataImages)
        assertEquals(listOf(15, 24, 25, 30), profile.frameRates)
        assertEquals(
            listOf("1920×1080", "2032×1536", "3840×2160"),
            profile.hlg10OutputProfiles.map(Hlg10OutputProfile::exactLabel)
        )
        assertEquals(
            listOf("16:9", "4:3", "16:9"),
            profile.hlg10OutputProfiles.map(Hlg10OutputProfile::aspectLabel)
        )
        assertEquals(
            listOf(
                Hlg10OutputProfile.Status.MEASURED,
                Hlg10OutputProfile.Status.VALIDATED,
                Hlg10OutputProfile.Status.EXPERIMENTAL
            ),
            profile.hlg10OutputProfiles.map(Hlg10OutputProfile::status)
        )
        assertEquals(listOf(15, 24), profile.hlg10OutputProfiles.last().frameRates)
        assertEquals(false, profile.hlg10OutputProfiles.any { 60 in it.frameRates })
        assertEquals(
            listOf(
                RawLiteCaptureProfile.Dimensions(1280, 960),
                RawLiteCaptureProfile.Dimensions(1024, 768)
            ),
            profile.previewSizes
        )
        assertEquals(512L * 1024L * 1024L, profile.minimumFreeBytes)
        assertEquals(2, profile.checkpointSeconds)
        assertEquals(3_000L, profile.backgroundDrainTimeoutMs)
    }
}
