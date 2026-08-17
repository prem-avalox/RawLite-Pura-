package com.example.rawlite.device.benchmark

import com.example.rawlite.NativeRawWriter
import com.example.rawlite.device.Dimensions
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.locks.LockSupport
import kotlin.math.ceil
import kotlin.math.max

internal class StorageBenchmark(private val directory: File) {
    fun run(
        dimensions: Dimensions,
        targetFps: Int,
        durationSeconds: Double,
        inputRowStrideBytes: Int? = null
    ): StorageBenchmarkResult {
        val packedRowBytes = (dimensions.width * 10 + 7) / 8
        val rowStride = inputRowStrideBytes?.takeIf { it >= packedRowBytes }
            ?: align(packedRowBytes, 64)
        val frameBytes = packedRowBytes.toLong() * dimensions.height
        val frameCount = ceil(targetFps * durationSeconds).toInt().coerceAtLeast(2)
        val raw = File(directory, "storage-${targetFps}p.raw10")
        val metadata = File(directory, "storage-${targetFps}p.csv")
        val state = File(directory, "storage-${targetFps}p.state")
        val buffer = ByteBuffer.allocateDirect(rowStride * dimensions.height)
        fillPattern(buffer)
        var handle = 0L
        val writeDurations = ArrayList<Long>(frameCount)
        var completed = 0
        var closeNs = 0L
        var deadlineMisses = 0
        var maximumLagNs = 0L
        var finalLagNs = 0L
        return try {
            handle = NativeRawWriter.open(
                raw.absolutePath,
                metadata.absolutePath,
                state.absolutePath,
                dimensions.width,
                dimensions.height,
                packedRowBytes,
                checkpointFrames = targetFps * 2
            )
            check(handle != 0L) { NativeRawWriter.lastError() }
            val startedNs = System.nanoTime()
            val frameIntervalNs = 1_000_000_000L / targetFps
            repeat(frameCount) { frame ->
                val scheduledNs = startedNs + frame * frameIntervalNs
                var beforeWriteNs = System.nanoTime()
                if (beforeWriteNs < scheduledNs) {
                    LockSupport.parkNanos(scheduledNs - beforeWriteNs)
                    beforeWriteNs = System.nanoTime()
                }
                val startLagNs = (beforeWriteNs - scheduledNs).coerceAtLeast(0L)
                maximumLagNs = max(maximumLagNs, startLagNs)
                if (startLagNs > frameIntervalNs) deadlineMisses += 1
                val callStartedNs = System.nanoTime()
                val result = NativeRawWriter.writeFrame(
                    handle = handle,
                    buffer = buffer,
                    bufferOffset = 0,
                    bufferLimit = buffer.capacity(),
                    rowStride = rowStride,
                    frame = frame,
                    timestampNs = frame * (1_000_000_000L / targetFps),
                    iso = 100,
                    exposureNs = 1_000_000_000L / (targetFps * 2L),
                    frameDurationNs = 1_000_000_000L / targetFps,
                    focusDiopters = 0f,
                    wbRed = 1f,
                    wbGreenEven = 1f,
                    wbGreenOdd = 1f,
                    wbBlue = 1f
                )
                writeDurations += System.nanoTime() - callStartedNs
                check(result == 0) { NativeRawWriter.lastError() }
                completed += 1
            }
            val writingFinishedNs = System.nanoTime()
            finalLagNs = (
                writingFinishedNs - (startedNs + frameCount * frameIntervalNs)
                ).coerceAtLeast(0L)
            val closeStartedNs = System.nanoTime()
            val closeResult = NativeRawWriter.close(handle)
            handle = 0L
            closeNs = System.nanoTime() - closeStartedNs
            check(closeResult == 0) { NativeRawWriter.lastError() }
            val elapsedNs = (writingFinishedNs - startedNs).coerceAtLeast(1L)
            val bytes = frameBytes * completed
            val throughput = bytes / 1_000_000.0 / (elapsedNs / 1_000_000_000.0)
            val activeWriteNs = writeDurations.sum().coerceAtLeast(1L)
            val processingCapacityFps = completed * 1_000_000_000.0 / activeWriteNs
            val sustainableFps = processingCapacityFps / 1.15
            val realTimeHeadroom = processingCapacityFps / targetFps
            val stateValues = parseState(state)
            StorageBenchmarkResult(
                dimensions = dimensions,
                requestedFps = targetFps,
                rowStrideBytes = rowStride,
                frameBytes = frameBytes,
                framesWritten = completed,
                bytesWritten = bytes,
                durationSeconds = elapsedNs / 1_000_000_000.0,
                throughputMegabytesPerSecond = throughput,
                writeCalls = Distribution.fromNanoseconds(writeDurations),
                closeMilliseconds = closeNs / 1_000_000.0,
                ioMode = stateValues["io_mode"].orEmpty().ifBlank { "unknown" },
                directFallbacks = stateValues["direct_fallbacks"]?.toIntOrNull() ?: 0,
                sustainableFpsWithMargin = sustainableFps,
                deadlineMisses = deadlineMisses,
                maximumLagMs = maximumLagNs / 1_000_000.0,
                finalLagMs = finalLagNs / 1_000_000.0,
                state = ModeCertificationPolicy.pacedStorageState(
                    completedFrames = completed,
                    expectedFrames = frameCount,
                    finalLagMs = finalLagNs / 1_000_000.0,
                    frameIntervalMs = frameIntervalNs / 1_000_000.0,
                    realTimeHeadroom = realTimeHeadroom
                )
            )
        } catch (error: Throwable) {
            if (handle != 0L) runCatching { NativeRawWriter.close(handle) }
            StorageBenchmarkResult(
                dimensions = dimensions,
                requestedFps = targetFps,
                rowStrideBytes = rowStride,
                frameBytes = frameBytes,
                framesWritten = completed,
                bytesWritten = frameBytes * completed,
                durationSeconds = 0.0,
                throughputMegabytesPerSecond = 0.0,
                writeCalls = Distribution.fromNanoseconds(writeDurations),
                closeMilliseconds = closeNs / 1_000_000.0,
                ioMode = "failed",
                directFallbacks = 0,
                sustainableFpsWithMargin = 0.0,
                deadlineMisses = deadlineMisses,
                maximumLagMs = maximumLagNs / 1_000_000.0,
                finalLagMs = finalLagNs / 1_000_000.0,
                state = CertificationState.REJECTED,
                error = error.message ?: error.javaClass.simpleName
            )
        } finally {
            raw.delete()
            metadata.delete()
            state.delete()
        }
    }

    private fun fillPattern(buffer: ByteBuffer) {
        buffer.clear()
        while (buffer.remaining() >= Long.SIZE_BYTES) buffer.putLong(0x8020080200802008uL.toLong())
        while (buffer.hasRemaining()) buffer.put(0x80.toByte())
        buffer.clear()
    }

    private fun parseState(file: File): Map<String, String> = runCatching {
        file.readLines().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun align(value: Int, alignment: Int): Int =
        ((value + alignment - 1) / alignment) * alignment
}
