package com.example.rawlite

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.TextureView
import kotlin.math.min

internal class AspectRatioTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs) {
    private var videoWidth = 0
    private var videoHeight = 0
    private var videoRotation = 0

    fun setVideoGeometry(width: Int, height: Int, rotationDegrees: Int) {
        videoWidth = width
        videoHeight = height
        videoRotation = ((rotationDegrees % 360) + 360) % 360
        rotation = 0f
        updateVideoTransform()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(availableWidth, availableHeight)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateVideoTransform()
    }

    private fun updateVideoTransform() {
        if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) return
        val quarterTurn = videoRotation % 180 != 0
        val displayWidth = if (quarterTurn) videoHeight else videoWidth
        val displayHeight = if (quarterTurn) videoWidth else videoHeight
        val fitScale = min(
            width.toFloat() / displayWidth,
            height.toFloat() / displayHeight
        )
        val targetWidth = displayWidth * fitScale
        val targetHeight = displayHeight * fitScale
        val scaleX = if (quarterTurn) targetHeight / width else targetWidth / width
        val scaleY = if (quarterTurn) targetWidth / height else targetHeight / height
        val centerX = width / 2f
        val centerY = height / 2f
        setTransform(Matrix().apply {
            setScale(scaleX, scaleY, centerX, centerY)
            postRotate(videoRotation.toFloat(), centerX, centerY)
        })
    }
}
