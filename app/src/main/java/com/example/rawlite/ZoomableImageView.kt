package com.example.rawlite

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import kotlin.math.min

internal class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    companion object {
        private const val MIN_SCALE = 1.0f
        private const val MAX_SCALE = 4.0f
        private const val DOUBLE_TAP_SCALE = 2.5f
        private const val ANIMATION_DURATION_MS = 220L
    }

    private var bitmapWidth = 0
    private var bitmapHeight = 0

    // Zoom & Pan state
    private var currentScale = MIN_SCALE
    private var panX = 0f
    private var panY = 0f

    // Base geometry
    private var fitScale = 1f
    private var targetWidth = 0f
    private var targetHeight = 0f
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
        scaleType = ScaleType.MATRIX
        isClickable = true
        isFocusable = true
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

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        if (bm != null) {
            updateBaseGeometry(bm.width, bm.height)
        }
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        if (drawable != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
            updateBaseGeometry(drawable.intrinsicWidth, drawable.intrinsicHeight)
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (bitmapWidth > 0 && bitmapHeight > 0) {
            computeFit()
            clampPan()
            applyTransform()
        }
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

    private fun updateBaseGeometry(bw: Int, bh: Int) {
        val changed = (bitmapWidth != bw || bitmapHeight != bh)
        bitmapWidth = bw
        bitmapHeight = bh
        if (width > 0 && height > 0) {
            computeFit()
            if (changed && currentScale <= MIN_SCALE) {
                clampPan()
            }
            applyTransform()
        }
    }

    private fun computeFit() {
        if (width <= 0 || height <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return
        fitScale = min(
            width.toFloat() / bitmapWidth,
            height.toFloat() / bitmapHeight
        )
        targetWidth = bitmapWidth * fitScale
        targetHeight = bitmapHeight * fitScale
        centerX = width / 2f
        centerY = height / 2f
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
        if (width <= 0 || height <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return
        transformMatrix.reset()
        transformMatrix.postScale(fitScale, fitScale)
        transformMatrix.postTranslate((width - targetWidth) / 2f, (height - targetHeight) / 2f)
        transformMatrix.postScale(currentScale, currentScale, centerX, centerY)
        transformMatrix.postTranslate(panX, panY)
        imageMatrix = transformMatrix
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
