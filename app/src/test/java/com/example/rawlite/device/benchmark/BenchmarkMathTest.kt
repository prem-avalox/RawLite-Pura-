package com.example.rawlite.device.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkMathTest {
    @Test
    fun `perfect 24 fps cadence has no inferred losses`() {
        val frameDuration = 1_000_000_000L / 24L
        val timestamps = (0..48).map { it * frameDuration }

        val (fps, losses, distribution) = BenchmarkMath.cadence(timestamps, 24)

        assertEquals(0, losses)
        assertTrue(fps in 23.99..24.01)
        assertTrue(distribution.p99Ms in 41.6..41.8)
    }

    @Test
    fun `cadence gap reports the missing timeline frames`() {
        val frameDuration = 1_000_000_000L / 24L
        val timestamps = listOf(0L, frameDuration, frameDuration * 4, frameDuration * 5)

        val (_, losses, distribution) = BenchmarkMath.cadence(timestamps, 24)

        assertEquals(2, losses)
        assertTrue(distribution.maximumMs > 120.0)
    }

    @Test
    fun `mode remains measured until integrated pipeline validation exists`() {
        assertEquals(
            CertificationState.COMPONENT_VERIFIED,
            ModeCertificationPolicy.throughputState(1.20)
        )
        assertEquals(
            CertificationState.REJECTED,
            ModeCertificationPolicy.throughputState(1.10)
        )
    }

    @Test
    fun `sensor certification requires zero loss and stable tail`() {
        assertEquals(
            CertificationState.DELIVERY_VERIFIED,
            ModeCertificationPolicy.sensorState(
                requestedFps = 30,
                receivedFrames = 91,
                durationSeconds = 3.0,
                achievedFps = 30.0,
                inferredLostFrames = 0,
                intervalP99Ms = 34.0
            )
        )
        assertEquals(
            CertificationState.REJECTED,
            ModeCertificationPolicy.sensorState(
                requestedFps = 30,
                receivedFrames = 89,
                durationSeconds = 3.0,
                achievedFps = 29.4,
                inferredLostFrames = 1,
                intervalP99Ms = 66.0
            )
        )
    }

    @Test
    fun `paced storage distinguishes sustainable from margin certified`() {
        assertEquals(
            CertificationState.MEASURED,
            ModeCertificationPolicy.pacedStorageState(
                completedFrames = 72,
                expectedFrames = 72,
                finalLagMs = 0.0,
                frameIntervalMs = 41.67,
                realTimeHeadroom = 1.05
            )
        )
        assertEquals(
            CertificationState.REJECTED,
            ModeCertificationPolicy.pacedStorageState(
                completedFrames = 72,
                expectedFrames = 72,
                finalLagMs = 60.0,
                frameIntervalMs = 41.67,
                realTimeHeadroom = 0.90
            )
        )
    }

    @Test
    fun `ten second integrated capture is only burst verified`() {
        assertEquals(
            RecordingValidationTier.BURST_10_SECONDS,
            IntegratedRecordingPolicy.tier(validIntegratedEvidence(durationSeconds = 10.0))
        )
    }

    @Test
    fun `one minute and five minute captures have distinct tiers`() {
        assertEquals(
            RecordingValidationTier.SHORT_1_MINUTE,
            IntegratedRecordingPolicy.tier(validIntegratedEvidence(durationSeconds = 60.0))
        )
        assertEquals(
            RecordingValidationTier.SUSTAINED_5_MINUTES,
            IntegratedRecordingPolicy.tier(validIntegratedEvidence(durationSeconds = 300.0))
        )
    }

    @Test
    fun `any drop or invalid audio rejects integrated capture`() {
        assertEquals(
            RecordingValidationTier.REJECTED,
            IntegratedRecordingPolicy.tier(
                validIntegratedEvidence(durationSeconds = 300.0).copy(droppedFrames = 1)
            )
        )
        assertEquals(
            RecordingValidationTier.REJECTED,
            IntegratedRecordingPolicy.tier(
                validIntegratedEvidence(durationSeconds = 300.0).copy(audioValid = false)
            )
        )
    }

    private fun validIntegratedEvidence(durationSeconds: Double): IntegratedRecordingEvidence =
        IntegratedRecordingEvidence(
            durationSeconds = durationSeconds,
            requestedFps = 24,
            writtenFrames = (durationSeconds * 24).toInt(),
            droppedFrames = 0,
            queueOverflows = 0,
            finalBacklogFrames = 0,
            fileFinalized = true,
            fileReadable = true,
            audioRequired = true,
            audioValid = true,
            endSyncErrorMs = 10.0,
            thermalStatusAfter = 1
        )
}
