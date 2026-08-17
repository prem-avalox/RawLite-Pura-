package com.example.rawlite

internal object FrameIntegrity {
    fun lostFrameCount(
        storedFrames: Int,
        timelineFrames: Int,
        repeatedFrames: Int = 0,
        reportedDroppedFrames: Int = 0
    ): Int {
        val missingFromTimeline = (timelineFrames - storedFrames).coerceAtLeast(0)
        return maxOf(
            missingFromTimeline,
            repeatedFrames.coerceAtLeast(0),
            reportedDroppedFrames.coerceAtLeast(0)
        )
    }
}
