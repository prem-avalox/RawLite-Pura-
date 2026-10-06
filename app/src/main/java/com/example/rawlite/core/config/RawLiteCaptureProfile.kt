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
    val hlg10OutputProfiles: List<Hlg10OutputProfile>,
    val frameRates: List<Int>,
    val minimumFreeBytes: Long,
    val checkpointSeconds: Int,
    val backgroundDrainTimeoutMs: Long
) {
    data class Dimensions(val width: Int, val height: Int)
}

/**
 * A real encoded HLG 10 output, independent from the RAW10 stream used as its source.
 *
 * The source remains a HAL-advertised RAW10 size. This profile describes only the frame that
 * RawLite crops, processes and sends to HEVC Main10, so the UI never has to present a marketing
 * label such as "2K" without also knowing its exact dimensions and aspect ratio.
 */
internal data class Hlg10OutputProfile(
    val id: String,
    val width: Int,
    val height: Int,
    val aspectWidth: Int,
    val aspectHeight: Int,
    val frameRates: List<Int>,
    val status: Status
) {
    enum class Status {
        VALIDATED,
        MEASURED,
        EXPERIMENTAL
    }

    val dimensions: RawLiteCaptureProfile.Dimensions
        get() = RawLiteCaptureProfile.Dimensions(width, height)

    val exactLabel: String
        get() = "${width}×${height}"

    val aspectLabel: String
        get() = "${aspectWidth}:${aspectHeight}"
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
        hlg10OutputProfiles = listOf(
            Hlg10OutputProfile(
                id = "fhd-16x9",
                width = 1920,
                height = 1080,
                aspectWidth = 16,
                aspectHeight = 9,
                frameRates = listOf(15, 24, 25, 30),
                status = Hlg10OutputProfile.Status.MEASURED
            ),
            Hlg10OutputProfile(
                id = "sensor-2k-4x3",
                width = 2032,
                height = 1536,
                aspectWidth = 4,
                aspectHeight = 3,
                frameRates = listOf(15, 24, 25, 30),
                status = Hlg10OutputProfile.Status.VALIDATED
            ),
            Hlg10OutputProfile(
                id = "uhd-16x9",
                width = 3840,
                height = 2160,
                aspectWidth = 16,
                aspectHeight = 9,
                frameRates = listOf(15, 24),
                status = Hlg10OutputProfile.Status.EXPERIMENTAL
            )
        ),
        frameRates = listOf(15, 24, 25, 30),
        minimumFreeBytes = 512L * 1024L * 1024L,
        checkpointSeconds = 2,
        backgroundDrainTimeoutMs = 3_000L
    )
}
