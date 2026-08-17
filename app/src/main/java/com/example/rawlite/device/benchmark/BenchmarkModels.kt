package com.example.rawlite.device.benchmark

import com.example.rawlite.device.DeviceCapabilityProfile
import com.example.rawlite.device.Dimensions
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal enum class BenchmarkStage {
    HAL,
    SENSOR_CADENCE,
    STORAGE,
    ISP,
    ENCODER
}

internal enum class CertificationState {
    UNAVAILABLE,
    DECLARED,
    DELIVERY_VERIFIED,
    COMPONENT_VERIFIED,
    MEASURED,
    REJECTED
}

/**
 * Highest real recording duration that a complete capture pipeline has survived.
 *
 * Component benchmarks deliberately cannot promote a mode beyond [ISOLATED_COMPONENTS].
 * This prevents a short HAL delivery test from being presented as a usable recording mode.
 */
internal enum class RecordingValidationTier {
    NOT_TESTED,
    ISOLATED_COMPONENTS,
    BURST_10_SECONDS,
    SHORT_1_MINUTE,
    SUSTAINED_5_MINUTES,
    REJECTED
}

internal data class BenchmarkRequest(
    val stages: Set<BenchmarkStage>,
    val frameRates: List<Int>,
    val durationSeconds: Double
) {
    init {
        require(durationSeconds in 1.0..30.0)
        require(frameRates.isNotEmpty() && frameRates.all { it in 1..120 })
    }

    companion object {
        fun engineeringQuick(): BenchmarkRequest = BenchmarkRequest(
            stages = BenchmarkStage.entries.toSet(),
            frameRates = listOf(24, 30),
            durationSeconds = 3.0
        )
    }
}

internal data class Distribution(
    val count: Int,
    val averageMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val maximumMs: Double,
    val standardDeviationMs: Double
) {
    companion object {
        fun fromNanoseconds(values: List<Long>): Distribution {
            if (values.isEmpty()) return Distribution(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            val sorted = values.sorted()
            val milliseconds = sorted.map { it / 1_000_000.0 }
            val average = milliseconds.average()
            val variance = milliseconds.sumOf { value ->
                val delta = value - average
                delta * delta
            } / milliseconds.size
            return Distribution(
                count = values.size,
                averageMs = average,
                p50Ms = percentile(milliseconds, 0.50),
                p95Ms = percentile(milliseconds, 0.95),
                p99Ms = percentile(milliseconds, 0.99),
                maximumMs = milliseconds.last(),
                standardDeviationMs = sqrt(variance)
            )
        }

        private fun percentile(sortedValues: List<Double>, percentile: Double): Double {
            if (sortedValues.size == 1) return sortedValues.first()
            val index = ceil((sortedValues.size - 1) * percentile).toInt()
                .coerceIn(0, sortedValues.lastIndex)
            return sortedValues[index]
        }
    }
}

internal data class SensorCadenceResult(
    val cameraId: String,
    val physicalCameraId: String?,
    val dimensions: Dimensions,
    val requestedFps: Int,
    val declaredMaximumFps: Double?,
    val receivedFrames: Int,
    val durationSeconds: Double,
    val achievedFps: Double,
    val inferredLostFrames: Int,
    val intervals: Distribution,
    val state: CertificationState,
    val rowStrideBytes: Int = 0,
    val error: String? = null
)

internal data class StorageBenchmarkResult(
    val dimensions: Dimensions,
    val requestedFps: Int,
    val rowStrideBytes: Int,
    val frameBytes: Long,
    val framesWritten: Int,
    val bytesWritten: Long,
    val durationSeconds: Double,
    val throughputMegabytesPerSecond: Double,
    val writeCalls: Distribution,
    val closeMilliseconds: Double,
    val ioMode: String,
    val directFallbacks: Int,
    val sustainableFpsWithMargin: Double,
    val deadlineMisses: Int,
    val maximumLagMs: Double,
    val finalLagMs: Double,
    val state: CertificationState,
    val error: String? = null
)

internal data class IspBenchmarkResult(
    val sourceDimensions: Dimensions,
    val outputDimensions: Dimensions,
    val framesProcessed: Int,
    val requestedFps: Int,
    val conversion: Distribution,
    val realTimeHeadroom: Double,
    val state: CertificationState,
    val error: String? = null
)

internal data class EncoderBenchmarkResult(
    val codecName: String,
    val dimensions: Dimensions,
    val requestedFps: Int,
    val bitRate: Int,
    val submittedFrames: Int,
    val encodedFrames: Int,
    val elapsedSeconds: Double,
    val throughputFps: Double,
    val realTimeHeadroom: Double,
    val state: CertificationState,
    val error: String? = null
)

internal data class ModeCertification(
    val id: String,
    val state: CertificationState,
    val limitingStage: BenchmarkStage?,
    val reason: String,
    val recordingTier: RecordingValidationTier = RecordingValidationTier.NOT_TESTED,
    val maximumVerifiedDurationSeconds: Double = 0.0
)

internal data class IntegratedRecordingEvidence(
    val durationSeconds: Double,
    val requestedFps: Int,
    val writtenFrames: Int,
    val droppedFrames: Int,
    val queueOverflows: Int,
    val finalBacklogFrames: Int,
    val fileFinalized: Boolean,
    val fileReadable: Boolean,
    val audioRequired: Boolean,
    val audioValid: Boolean,
    val endSyncErrorMs: Double?,
    val thermalStatusAfter: Int
)

internal data class DeviceBenchmarkReport(
    val schemaVersion: Int,
    val generatedAtEpochMs: Long,
    val profile: DeviceCapabilityProfile,
    val temperatureBeforeC: Double,
    val temperatureAfterC: Double,
    val thermalStatusBefore: Int,
    val thermalStatusAfter: Int,
    val availableStorageBeforeBytes: Long,
    val availableStorageAfterBytes: Long,
    val totalStorageBytes: Long,
    val sensorCadence: List<SensorCadenceResult>,
    val storage: List<StorageBenchmarkResult>,
    val isp: List<IspBenchmarkResult>,
    val encoders: List<EncoderBenchmarkResult>,
    val modes: List<ModeCertification>,
    val completedStages: Set<BenchmarkStage>,
    val interrupted: Boolean
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

internal object BenchmarkMath {
    fun cadence(
        timestampsNs: List<Long>,
        requestedFps: Int
    ): Triple<Double, Int, Distribution> {
        if (timestampsNs.size < 2) {
            return Triple(0.0, 0, Distribution.fromNanoseconds(emptyList()))
        }
        val ordered = timestampsNs.sorted()
        val intervals = ordered.zipWithNext { first, second -> (second - first).coerceAtLeast(0L) }
        val durationNs = (ordered.last() - ordered.first()).coerceAtLeast(1L)
        val achievedFps = (ordered.size - 1) * 1_000_000_000.0 / durationNs
        val targetNs = 1_000_000_000.0 / requestedFps
        val inferredLosses = intervals.sumOf { interval ->
            (interval / targetNs).roundToInt().coerceAtLeast(1) - 1
        }
        return Triple(achievedFps, inferredLosses, Distribution.fromNanoseconds(intervals))
    }
}

internal object ModeCertificationPolicy {
    private const val REQUIRED_HEADROOM = 1.15

    fun sensorState(
        requestedFps: Int,
        receivedFrames: Int,
        durationSeconds: Double,
        achievedFps: Double,
        inferredLostFrames: Int,
        intervalP99Ms: Double
    ): CertificationState {
        if (receivedFrames < 2 || durationSeconds <= 0.0) return CertificationState.REJECTED
        val targetIntervalMs = 1_000.0 / requestedFps
        val enoughFrames = receivedFrames >= requestedFps * durationSeconds * 0.90
        val stableRate = achievedFps >= requestedFps * 0.985
        val stableTail = intervalP99Ms <= targetIntervalMs * 1.50
        return if (enoughFrames && stableRate && stableTail && inferredLostFrames == 0) {
            CertificationState.DELIVERY_VERIFIED
        } else {
            CertificationState.REJECTED
        }
    }

    fun throughputState(realTimeHeadroom: Double): CertificationState =
        if (realTimeHeadroom >= REQUIRED_HEADROOM) {
            CertificationState.COMPONENT_VERIFIED
        } else {
            CertificationState.REJECTED
        }

    fun pacedStorageState(
        completedFrames: Int,
        expectedFrames: Int,
        finalLagMs: Double,
        frameIntervalMs: Double,
        realTimeHeadroom: Double
    ): CertificationState {
        if (completedFrames != expectedFrames || finalLagMs > frameIntervalMs) {
            return CertificationState.REJECTED
        }
        return if (realTimeHeadroom >= REQUIRED_HEADROOM) {
            CertificationState.COMPONENT_VERIFIED
        } else {
            CertificationState.MEASURED
        }
    }
}

internal object IntegratedRecordingPolicy {
    const val BURST_SECONDS = 10.0
    const val SHORT_SECONDS = 60.0
    const val SUSTAINED_SECONDS = 300.0

    private const val MINIMUM_RATE_RATIO = 0.985
    private const val THERMAL_STATUS_SEVERE = 3

    fun tier(evidence: IntegratedRecordingEvidence): RecordingValidationTier {
        if (!hasCompleteIntegrity(evidence)) return RecordingValidationTier.REJECTED
        return when {
            evidence.durationSeconds >= SUSTAINED_SECONDS ->
                RecordingValidationTier.SUSTAINED_5_MINUTES
            evidence.durationSeconds >= SHORT_SECONDS ->
                RecordingValidationTier.SHORT_1_MINUTE
            evidence.durationSeconds >= BURST_SECONDS ->
                RecordingValidationTier.BURST_10_SECONDS
            else -> RecordingValidationTier.NOT_TESTED
        }
    }

    private fun hasCompleteIntegrity(evidence: IntegratedRecordingEvidence): Boolean {
        if (evidence.durationSeconds <= 0.0 || evidence.requestedFps <= 0) return false
        val achievedFps = evidence.writtenFrames / evidence.durationSeconds
        val frameIntervalMs = 1_000.0 / evidence.requestedFps
        val audioPasses = !evidence.audioRequired || (
            evidence.audioValid &&
                evidence.endSyncErrorMs != null &&
                kotlin.math.abs(evidence.endSyncErrorMs) <= frameIntervalMs
            )
        return evidence.fileFinalized &&
            evidence.fileReadable &&
            evidence.writtenFrames > 0 &&
            achievedFps >= evidence.requestedFps * MINIMUM_RATE_RATIO &&
            evidence.droppedFrames == 0 &&
            evidence.queueOverflows == 0 &&
            evidence.finalBacklogFrames == 0 &&
            audioPasses &&
            evidence.thermalStatusAfter < THERMAL_STATUS_SEVERE
    }
}
