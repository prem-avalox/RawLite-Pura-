package com.example.rawlite.device.benchmark

import com.example.rawlite.device.CameraCapability
import com.example.rawlite.device.DeviceCapabilityProfile
import com.example.rawlite.device.DeviceIdentity
import com.example.rawlite.device.Dimensions
import com.example.rawlite.device.SupportEvidence
import org.junit.Assert.assertEquals
import org.junit.Test

class SensorSweepPlannerTest {
    @Test
    fun `logical multi camera is replaced by its physical sensors`() {
        val logical = camera(
            id = "0",
            facing = CameraCapability.Facing.BACK,
            physicalIds = setOf("2", "3"),
            sizes = setOf(Dimensions(2032, 1536))
        )
        val main = camera("2", CameraCapability.Facing.BACK, sizes = setOf(
            Dimensions(4080, 3072),
            Dimensions(2032, 1536)
        ))
        val ultraWide = camera("3", CameraCapability.Facing.BACK, sizes = setOf(
            Dimensions(4032, 3016),
            Dimensions(2016, 1508)
        ))
        val front = camera("1", CameraCapability.Facing.FRONT, sizes = setOf(
            Dimensions(3280, 2464)
        ))

        val targets = SensorSweepPlanner.targets(profile(logical, main, ultraWide, front))

        assertEquals(listOf("2", "3", "1"), targets.map { it.camera.cameraId })
        assertEquals(
            listOf(Dimensions(2032, 1536), Dimensions(2016, 1508), Dimensions(3280, 2464)),
            targets.map { it.dimensions }
        )
        assertEquals("2", SensorSweepPlanner.primaryBackTarget(targets)?.camera?.cameraId)
    }

    @Test
    fun `logical camera remains when no raw physical child is exposed`() {
        val logical = camera(
            id = "0",
            facing = CameraCapability.Facing.BACK,
            physicalIds = setOf("2"),
            sizes = setOf(Dimensions(2032, 1536))
        )
        val hiddenChild = camera("2", CameraCapability.Facing.BACK, sizes = emptySet())

        val targets = SensorSweepPlanner.targets(profile(logical, hiddenChild))

        assertEquals(listOf("0"), targets.map { it.camera.cameraId })
    }

    private fun profile(vararg cameras: CameraCapability): DeviceCapabilityProfile =
        DeviceCapabilityProfile(
            identity = DeviceIdentity("test", "test", "test", "test", 35),
            cameras = cameras.toList(),
            encoders = emptyList()
        )

    private fun camera(
        id: String,
        facing: CameraCapability.Facing,
        physicalIds: Set<String> = emptySet(),
        sizes: Set<Dimensions>
    ): CameraCapability = CameraCapability(
        cameraId = id,
        facing = facing,
        rawSensorSizes = sizes,
        raw10Sizes = sizes,
        maximumRawOutputs = if (sizes.isEmpty()) 0 else 1,
        evidence = if (sizes.isEmpty()) SupportEvidence.UNSUPPORTED else SupportEvidence.DECLARED,
        physicalCameraIds = physicalIds
    )
}
