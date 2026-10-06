package com.example.rawlite

import kotlin.math.roundToInt

/**
 * Format-independent frame accounting used by every gallery player.
 *
 * Stored frames are actual images or encoded samples. Timeline frames include capture gaps, so
 * callers never have to reinterpret a missing frame differently for RAW10, CinemaDNG or PURE 10.
 */
internal data class PlaybackFrameStatus(
    val storedFrames: Int,
    val timelineFrames: Int,
    val lostFrames: Int
) {
    val hasLosses: Boolean
        get() = lostFrames > 0
}

internal object PlaybackDiagnostics {
    fun from(summary: ClipSummary): PlaybackFrameStatus = PlaybackFrameStatus(
        storedFrames = summary.frameCount.coerceAtLeast(0),
        timelineFrames = summary.timelineFrameCount.coerceAtLeast(summary.frameCount),
        lostFrames = summary.lostFrameCount.coerceAtLeast(0)
    )

    fun pure10TimelineFrame(positionMs: Int, fps: Int, timelineFrames: Int): Int {
        if (timelineFrames <= 0) return 0
        val zeroBased = (positionMs.coerceAtLeast(0) * fps.coerceAtLeast(1) / 1_000.0)
            .roundToInt()
        return (zeroBased + 1).coerceIn(1, timelineFrames)
    }

    fun progressLabel(
        status: PlaybackFrameStatus,
        storedFrame: Int? = null,
        timelineFrame: Int? = null
    ): String {
        val stored = storedFrame?.coerceIn(1, status.storedFrames.coerceAtLeast(1))
        val timeline = timelineFrame?.coerceIn(1, status.timelineFrames.coerceAtLeast(1))
        return when {
            stored != null && timeline != null && status.timelineFrames > status.storedFrames ->
                "FOT. $stored/${status.storedFrames}  TL $timeline/${status.timelineFrames}"
            stored != null -> "FOT. $stored/${status.storedFrames}"
            timeline != null -> "TL $timeline/${status.timelineFrames}  ENC. ${status.storedFrames}"
            else -> "FOT. ${status.storedFrames}  TL ${status.timelineFrames}"
        }
    }

    fun lossLabel(status: PlaybackFrameStatus): String = "PERD. ${status.lostFrames}"

    fun lossDescription(status: PlaybackFrameStatus): String = when (status.lostFrames) {
        0 -> "Sin fotogramas perdidos en la captura"
        1 -> "1 fotograma perdido en la captura"
        else -> "${status.lostFrames} fotogramas perdidos en la captura"
    }
}
