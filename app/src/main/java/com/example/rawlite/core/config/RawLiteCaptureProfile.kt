package com.example.rawlite.core.config

/**
 * Stable capture values that have already been validated on real hardware.
 *
 * A reference profile is deliberately separate from capability discovery: probing another
 * device must never silently rewrite the settings that make the Pixel 6 path reliable.
 */
internal data class RawLiteCaptureProfile(
    val id: String,
    val preferredHevcMain10Encoder: String,
    val pure10BitRate: Int,
    val pure10HlgMidtoneGain: Float,
    val pure10MaximumWidth: Int,
    val pure10MaximumHeight: Int,
    val pure10ImageReaderCapacity: Int,
    val pure10MaximumPendingWrites: Int,
    val packedRawImageReaderCapacity: Int,
    val packedRawMaximumPendingWrites: Int,
    val dngImageReaderCapacity: Int,
    val maximumPendingMetadataImages: Int,
    val previewSizes: List<Dimensions>,
    val frameRates: List<Int>,
    val minimumFreeBytes: Long,
    val checkpointSeconds: Int,
    val backgroundDrainTimeoutMs: Long
) {
    data class Dimensions(val width: Int, val height: Int)
}

internal object Pixel6ReferenceProfile {
    /** Values preserved from RawLite 0.33-rc1 (af0d209). */
    val capture = RawLiteCaptureProfile(
        id = "pixel6-v0.33",
        preferredHevcMain10Encoder = "c2.exynos.hevc.encoder",
        pure10BitRate = 50_000_000,
        pure10HlgMidtoneGain = 3.0f,
        pure10MaximumWidth = 3840,
        pure10MaximumHeight = 2176,
        pure10ImageReaderCapacity = 10,
        pure10MaximumPendingWrites = 8,
        packedRawImageReaderCapacity = 8,
        packedRawMaximumPendingWrites = 6,
        dngImageReaderCapacity = 4,
        maximumPendingMetadataImages = 6,
        previewSizes = listOf(
            RawLiteCaptureProfile.Dimensions(1280, 960),
            RawLiteCaptureProfile.Dimensions(1024, 768)
        ),
        frameRates = listOf(15, 24, 25, 30),
        minimumFreeBytes = 512L * 1024L * 1024L,
        checkpointSeconds = 2,
        backgroundDrainTimeoutMs = 3_000L
    )
}
