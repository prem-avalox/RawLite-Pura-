package com.example.rawlite

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pure capture and playback calculations kept independent from Android for deterministic tests. */
internal object CaptureMath {
    data class TimelineResult(
        val frame: Int,
        val cadenceGap: Int
    )

    data class CropRegionResult(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int
    ) {
        val right: Int get() = left + width
        val bottom: Int get() = top + height
    }

    data class FitResult(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val scale: Float
    ) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
    }

    data class PreviewTransformResult(
        val scaleX: Float,
        val scaleY: Float,
        val centerX: Float,
        val centerY: Float,
        val rotationDegrees: Float,
        val visibleWidth: Float,
        val visibleHeight: Float,
        val left: Float,
        val top: Float
    ) {
        val right: Float get() = left + visibleWidth
        val bottom: Float get() = top + visibleHeight
    }

    fun calculateCropRegion(
        activeWidth: Int,
        activeHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        activeLeft: Int = 0,
        activeTop: Int = 0
    ): CropRegionResult {
        if (activeWidth <= 0 || activeHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return CropRegionResult(activeLeft and -2, activeTop and -2, activeWidth and -2, activeHeight and -2)
        }
        val targetRatio = targetWidth.toDouble() / targetHeight
        val activeRatio = activeWidth.toDouble() / activeHeight
        val cropWidth: Int
        val cropHeight: Int
        if (activeRatio > targetRatio) {
            cropHeight = activeHeight and -2
            cropWidth = (cropHeight * targetRatio).roundToInt().coerceAtMost(activeWidth) and -2
        } else {
            cropWidth = activeWidth and -2
            cropHeight = (cropWidth / targetRatio).roundToInt().coerceAtMost(activeHeight) and -2
        }
        val left = activeLeft + (((activeWidth - cropWidth) / 2) and -2)
        val top = activeTop + (((activeHeight - cropHeight) / 2) and -2)
        return CropRegionResult(left and -2, top and -2, cropWidth, cropHeight)
    }

    fun fitWithin(
        containerWidth: Float,
        containerHeight: Float,
        contentWidth: Float,
        contentHeight: Float
    ): FitResult {
        if (containerWidth <= 0f || containerHeight <= 0f || contentWidth <= 0f || contentHeight <= 0f) {
            return FitResult(0f, 0f, 0f, 0f, 1f)
        }
        val scale = min(containerWidth / contentWidth, containerHeight / contentHeight)
        val fittedWidth = contentWidth * scale
        val fittedHeight = contentHeight * scale
        val left = (containerWidth - fittedWidth) / 2f
        val top = (containerHeight - fittedHeight) / 2f
        return FitResult(left, top, fittedWidth, fittedHeight, scale)
    }

    fun calculatePreviewTransform(
        viewWidth: Int,
        viewHeight: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        relativeDegrees: Int
    ): PreviewTransformResult {
        if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0) {
            return PreviewTransformResult(1f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
        val normalizedRotation = normalizedDegrees(relativeDegrees)
        val quarterTurn = normalizedRotation % 180 != 0
        val displayContentWidth = if (quarterTurn) bufferHeight.toFloat() else bufferWidth.toFloat()
        val displayContentHeight = if (quarterTurn) bufferWidth.toFloat() else bufferHeight.toFloat()

        val fitScale = min(
            viewWidth.toFloat() / displayContentWidth,
            viewHeight.toFloat() / displayContentHeight
        )
        val visibleWidth = displayContentWidth * fitScale
        val visibleHeight = displayContentHeight * fitScale

        val scaleX = if (quarterTurn) visibleHeight / viewWidth else visibleWidth / viewWidth
        val scaleY = if (quarterTurn) visibleWidth / viewHeight else visibleHeight / viewHeight
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f

        val left = (viewWidth - visibleWidth) / 2f
        val top = (viewHeight - visibleHeight) / 2f

        return PreviewTransformResult(
            scaleX = scaleX,
            scaleY = scaleY,
            centerX = centerX,
            centerY = centerY,
            rotationDegrees = normalizedRotation.toFloat(),
            visibleWidth = visibleWidth,
            visibleHeight = visibleHeight,
            left = left,
            top = top
        )
    }

    fun rotationDegrees(
        sensorDegrees: Int,
        displayRotationDegrees: Int,
        frontFacing: Boolean
    ): Int {
        val sensor = normalizedDegrees(sensorDegrees)
        val display = normalizedDegrees(displayRotationDegrees)
        return if (frontFacing) {
            normalizedDegrees(sensor + display)
        } else {
            normalizedDegrees(sensor - display)
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
