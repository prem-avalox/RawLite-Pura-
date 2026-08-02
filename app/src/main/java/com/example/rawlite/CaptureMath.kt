package com.example.rawlite

import kotlin.math.max

/** Pure capture and playback calculations kept independent from Android for deterministic tests. */
internal object CaptureMath {
    data class TimelineResult(
        val frame: Int,
        val cadenceGap: Int
    )

    fun rotationDegrees(
        sensorDegrees: Int,
        displayRotationDegrees: Int,
        frontFacing: Boolean
    ): Int {
        val sensor = normalizedDegrees(sensorDegrees)
        val display = normalizedDegrees(displayRotationDegrees)
        return if (frontFacing) {
            normalizedDegrees(sensor - display)
        } else {
            normalizedDegrees(sensor + display)
        }
    }

    fun timelineFrame(
        timestampNs: Long,
        firstTimestampNs: Long,
        lastTimelineFrame: Int,
        fps: Int
    ): TimelineResult {
        require(fps > 0) { "FPS must be positive" }
        if (firstTimestampNs <= 0L || timestampNs <= 0L) {
            return TimelineResult(lastTimelineFrame + 1, 0)
        }
        val nominalDuration = 1_000_000_000L / fps
        val derived = ((timestampNs - firstTimestampNs + nominalDuration / 2L) /
            nominalDuration).toInt().coerceAtLeast(0)
        val frame = max(lastTimelineFrame + 1, derived)
        val gap = if (lastTimelineFrame >= 0) {
            (frame - lastTimelineFrame - 1).coerceAtLeast(0)
        } else {
            0
        }
        return TimelineResult(frame, gap)
    }

    fun indexForTimeline(
        frameCount: Int,
        targetTimeline: Int,
        timelineAt: (Int) -> Int
    ): Int {
        if (frameCount <= 0) return 0
        var low = 0
        var high = frameCount - 1
        var result = 0
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (timelineAt(middle) <= targetTimeline) {
                result = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return result
    }

    fun sequencePositionMs(timelineFrame: Int, firstTimelineFrame: Int, fps: Int): Int {
        require(fps > 0) { "FPS must be positive" }
        return ((timelineFrame - firstTimelineFrame).coerceAtLeast(0) * 1_000L / fps).toInt()
    }

    fun audioTargetMs(
        videoPositionMs: Int,
        audioOffsetMs: Int,
        audioDurationMs: Int,
        outputLeadCompensationMs: Int = 0
    ): Int {
        return (videoPositionMs.toLong() + audioOffsetMs - outputLeadCompensationMs)
            .coerceIn(0L, audioDurationMs.coerceAtLeast(0).toLong())
            .toInt()
    }

    private fun normalizedDegrees(value: Int): Int = ((value % 360) + 360) % 360
}
