package com.example.rawlite.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilitiesGatewayTest {
    @Test
    fun `formats are exposed independently`() {
        val gateway = gateway(
            camera(
                rawSensorSizes = setOf(Dimensions(4080, 3072)),
                raw10Sizes = emptySet()
            )
        )

        assertTrue(gateway.feature(CaptureFeature.CINEMA_DNG, "0").available)
        assertFalse(gateway.feature(CaptureFeature.RAW10, "0").available)
        assertFalse(gateway.feature(CaptureFeature.PURE10, "0").available)
    }

    @Test
    fun `pure10 requires raw10 and a hardware main10 p010 encoder`() {
        val camera = camera(raw10Sizes = setOf(Dimensions(2032, 1536)))
        val encoder = EncoderCapability(
            name = "hardware.hevc",
            hevcMain10 = true,
            p010 = true,
            hardwareAccelerated = true,
            evidence = SupportEvidence.DECLARED
        )
        val gateway = gateway(camera, listOf(encoder))

        assertTrue(gateway.feature(CaptureFeature.PURE10, "0").available)
    }

    @Test
    fun `manual controls are derived from sensor and lens evidence`() {
        val gateway = gateway(
            camera(
                sensor = SensorCapability(
                    sensitivityRange = NumericRange(50.0, 3200.0),
                    exposureTimeRangeNs = NumericRange(100_000.0, 1_000_000_000.0),
                    maximumFrameDurationNs = 1_000_000_000L
                ),
                lens = LensCapability(minimumFocusDistanceDiopters = 10f),
                controls = CameraControlCapability(
                    manualSensor = true,
                    manualPostProcessing = true,
                    afModes = setOf(0, 1, 4),
                    maximumAfRegions = 1,
                    colorCorrectionModes = setOf(0, 1),
                    aeLockAvailable = true,
                    awbLockAvailable = true,
                    sensorRangesPresent = true
                )
            )
        )

        val controls = gateway.controls("0")
        assertTrue(controls.manualExposure)
        assertTrue(controls.manualWhiteBalance)
        assertTrue(controls.manualFocus)
        assertTrue(controls.autofocus)
        assertTrue(controls.tapToFocus)
        assertTrue(controls.aeLock)
        assertTrue(controls.awbLock)
    }

    @Test
    fun `declared frame rates respect stream and ae limits`() {
        val size = Dimensions(2032, 1536)
        val gateway = gateway(
            camera(
                streams = listOf(
                    StreamCapability(
                        format = StreamFormat.RAW10,
                        dimensions = size,
                        minimumFrameDurationNs = 33_333_333L,
                        stallDurationNs = 0L
                    )
                ),
                controls = CameraControlCapability(
                    targetFpsRanges = listOf(NumericRange(15.0, 30.0))
                )
            )
        )

        assertEquals(
            listOf(15, 24, 25, 30),
            gateway.declaredFrameRates("0", null, StreamFormat.RAW10, size, listOf(15, 24, 25, 30, 60))
        )
    }

    private fun gateway(
        camera: CameraCapability,
        encoders: List<EncoderCapability> = emptyList()
    ): DeviceCapabilitiesGateway = DeviceCapabilitiesGateway.from(
        DeviceCapabilityProfile(
            identity = DeviceIdentity("test", "test", "test", "test", 35),
            cameras = listOf(camera),
            encoders = encoders
        )
    )

    private fun camera(
        rawSensorSizes: Set<Dimensions> = emptySet(),
        raw10Sizes: Set<Dimensions> = emptySet(),
        streams: List<StreamCapability> = emptyList(),
        sensor: SensorCapability = SensorCapability(),
        lens: LensCapability = LensCapability(),
        controls: CameraControlCapability = CameraControlCapability()
    ): CameraCapability = CameraCapability(
        cameraId = "0",
        facing = CameraCapability.Facing.BACK,
        rawSensorSizes = rawSensorSizes,
        raw10Sizes = raw10Sizes,
        maximumRawOutputs = 1,
        evidence = SupportEvidence.DECLARED,
        streams = streams,
        sensor = sensor,
        lens = lens,
        controls = controls
    )
}
