package com.example.rawlite

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

internal class AspectRatioTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs) {

    companion object {
        private const val MIN_SCALE = 1.0f
        private const val MAX_SCALE = 4.0f
        private const val DOUBLE_TAP_SCALE = 2.5f
        private const val ANIMATION_DURATION_MS = 220L
    }

    private var videoWidth = 0
    private var videoHeight = 0
    private var videoRotation = 0

    // Zoom & Pan state
    private var currentScale = MIN_SCALE
    private var panX = 0f
    private var panY = 0f

    // Base geometry
    private var targetWidth = 0f
    private var targetHeight = 0f
    private var scaleX = 1f
    private var scaleY = 1f
    private var centerX = 0f
    private var centerY = 0f

    // Touch gesture tracking
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var prevFocusX = 0f
    private var prevFocusY = 0f
    private var zoomAnimator: ValueAnimator? = null
    private val transformMatrix = Matrix()

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                prevFocusX = detector.focusX
                prevFocusY = detector.focusY
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val focusX = detector.focusX
                val focusY = detector.focusY
                val deltaFocusX = focusX - prevFocusX
                val deltaFocusY = focusY - prevFocusY
                prevFocusX = focusX
                prevFocusY = focusY

                val previousScale = currentScale
                currentScale = (currentScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)

                if (currentScale != previousScale) {
                    val factor = currentScale / previousScale
                    panX -= (focusX - centerX - panX) * (factor - 1f)
                    panY -= (focusY - centerY - panY) * (factor - 1f)
                }

                // Sumar deltaFocus para que el paneo con dos dedos continúe funcionando con fluidez
                // incluso cuando currentScale alcanza MAX_SCALE (4.0f)
                panX += deltaFocusX
                panY += deltaFocusY

                clampPan()
                applyTransform()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                prevFocusX = 0f
                prevFocusY = 0f
                if (currentScale <= MIN_SCALE) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
    ).apply {
        isQuickScaleEnabled = false
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                performClick()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val targetScale = if (currentScale > 1.05f) MIN_SCALE else DOUBLE_TAP_SCALE
                animateZoomTo(targetScale, e.x, e.y)
                return true
            }
        }
    )

    init {
        isClickable = true
        isFocusable = true
    }

    fun setVideoGeometry(width: Int, height: Int, rotationDegrees: Int) {
        videoWidth = width
        videoHeight = height
        videoRotation = ((rotationDegrees % 360) + 360) % 360
        rotation = 0f
        resetZoom(animate = false)
        updateVideoTransform()
    }

    fun resetZoom(animate: Boolean = false) {
        zoomAnimator?.cancel()
        if (animate && currentScale > MIN_SCALE) {
            animateZoomTo(MIN_SCALE, centerX, centerY)
        } else {
            currentScale = MIN_SCALE
            panX = 0f
            panY = 0f
            clampPan()
            applyTransform()
            parent?.requestDisallowInterceptTouchEvent(false)
        }
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

    override fun onDetachedFromWindow() {
        zoomAnimator?.cancel()
        zoomAnimator = null
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        (parent as? View)?.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return super.onTouchEvent(event)

        var handled = scaleDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                zoomAnimator?.cancel()
                activePointerId = event.getPointerId(0)
                lastTouchX = event.x
                lastTouchY = event.y
                if (currentScale > MIN_SCALE) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val actionIndex = event.actionIndex
                lastTouchX = event.getX(actionIndex)
                lastTouchY = event.getY(actionIndex)
                activePointerId = event.getPointerId(actionIndex)
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex != -1) {
                    val x = event.getX(pointerIndex)
                    val y = event.getY(pointerIndex)

                    if (!scaleDetector.isInProgress && currentScale > MIN_SCALE) {
                        val dx = x - lastTouchX
                        val dy = y - lastTouchY
                        panX += dx
                        panY += dy
                        clampPan()
                        applyTransform()
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }

                    lastTouchX = x
                    lastTouchY = y
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val actionIndex = event.actionIndex
                val pointerId = event.getPointerId(actionIndex)
                if (pointerId == activePointerId) {
                    val newIndex = if (actionIndex == 0) 1 else 0
                    lastTouchX = event.getX(newIndex)
                    lastTouchY = event.getY(newIndex)
                    activePointerId = event.getPointerId(newIndex)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                if (currentScale <= MIN_SCALE) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }

        return true
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
        targetWidth = displayWidth * fitScale
        targetHeight = displayHeight * fitScale
        scaleX = if (quarterTurn) targetHeight / width else targetWidth / width
        scaleY = if (quarterTurn) targetWidth / height else targetHeight / height
        centerX = width / 2f
        centerY = height / 2f

        clampPan()
        applyTransform()
    }

    private fun clampPan() {
        if (currentScale <= MIN_SCALE || width <= 0 || height <= 0 || targetWidth <= 0f || targetHeight <= 0f) {
            panX = 0f
            panY = 0f
            return
        }
        val zoomedWidth = targetWidth * currentScale
        val zoomedHeight = targetHeight * currentScale
        val maxPanX = if (zoomedWidth > width) {
            (zoomedWidth - width) / 2f
        } else {
            ((zoomedWidth - targetWidth) / 2f).coerceAtLeast(0f)
        }
        val maxPanY = if (zoomedHeight > height) {
            (zoomedHeight - height) / 2f
        } else {
            ((zoomedHeight - targetHeight) / 2f).coerceAtLeast(0f)
        }
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
    }

    private fun applyTransform() {
        if (width <= 0 || height <= 0) return
        transformMatrix.reset()
        transformMatrix.setScale(scaleX, scaleY, centerX, centerY)
        transformMatrix.postRotate(videoRotation.toFloat(), centerX, centerY)
        transformMatrix.postScale(currentScale, currentScale, centerX, centerY)
        transformMatrix.postTranslate(panX, panY)
        setTransform(transformMatrix)
        invalidate()
    }

    private fun animateZoomTo(targetScale: Float, focalX: Float, focalY: Float) {
        zoomAnimator?.cancel()

        val startScale = currentScale
        val startPanX = panX
        val startPanY = panY

        val clampedTargetScale = targetScale.coerceIn(MIN_SCALE, MAX_SCALE)
        val targetPanX: Float
        val targetPanY: Float

        if (clampedTargetScale <= MIN_SCALE) {
            targetPanX = 0f
            targetPanY = 0f
        } else {
            val factor = clampedTargetScale / startScale
            val rawPanX = startPanX - (focalX - centerX - startPanX) * (factor - 1f)
            val rawPanY = startPanY - (focalY - centerY - startPanY) * (factor - 1f)

            val targetZoomedWidth = targetWidth * clampedTargetScale
            val targetZoomedHeight = targetHeight * clampedTargetScale
            val maxPanX = if (targetZoomedWidth > width) {
                (targetZoomedWidth - width) / 2f
            } else {
                ((targetZoomedWidth - targetWidth) / 2f).coerceAtLeast(0f)
            }
            val maxPanY = if (targetZoomedHeight > height) {
                (targetZoomedHeight - height) / 2f
            } else {
                ((targetZoomedHeight - targetHeight) / 2f).coerceAtLeast(0f)
            }

            targetPanX = rawPanX.coerceIn(-maxPanX, maxPanX)
            targetPanY = rawPanY.coerceIn(-maxPanY, maxPanY)
        }

        if (clampedTargetScale > MIN_SCALE) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }

        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                currentScale = startScale + (clampedTargetScale - startScale) * fraction
                panX = startPanX + (targetPanX - startPanX) * fraction
                panY = startPanY + (targetPanY - startPanY) * fraction
                clampPan()
                applyTransform()
                if (fraction >= 1f && currentScale <= MIN_SCALE) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            start()
        }
    }
}
