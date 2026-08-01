package com.example.rawlite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

class ParameterWheelView(context: Context) : View(context) {
    var vertical = true
        set(value) {
            field = value
            invalidate()
        }

    var valueFormatter: (Int) -> String = { it.toString() }
        set(value) {
            field = value
            invalidate()
        }

    var onProgressChanged: ((Int) -> Unit)? = null

    var valueInterval = 100
        set(value) {
            field = value.coerceAtLeast(1)
            invalidate()
        }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xA6FFFFFF.toInt()
        strokeWidth = density(1f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB8FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = density(13f)
        typeface = Typeface.create("Arial", Typeface.NORMAL)
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        strokeWidth = density(2f)
    }
    private val selectedTextPaint = Paint(textPaint).apply {
        color = ACCENT
        typeface = Typeface.create("Arial", Typeface.BOLD)
    }

    private var progress = 500
    private var touchOrigin = 0f
    private var progressOrigin = 500

    init {
        setBackgroundColor(RawLiteDesign.OVERLAY)
        isFocusable = true
        contentDescription = "Rueda de ajuste"
    }

    fun setProgressSilently(value: Int) {
        progress = value.coerceIn(0, MAX_PROGRESS)
        invalidate()
    }

    fun currentProgress(): Int = progress

    fun step(delta: Int) {
        setProgressFromUser(progress + delta)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        if (vertical) drawVertical(canvas) else drawHorizontal(canvas)
    }

    private fun drawVertical(canvas: Canvas) {
        val center = height / 2f
        val spacing = height / 8f
        val right = width.toFloat()
        for (index in -12..12) {
            val y = center + index * spacing / 2f
            if (y < 0f || y > height) continue
            val major = index % 2 == 0
            val selected = index == 0
            val length = if (major) width * 0.27f else width * 0.14f
            canvas.drawLine(
                right - length,
                y,
                right,
                y,
                if (selected) selectedPaint else tickPaint
            )
            if (major) {
                val sample = progress + index / 2 * valueInterval
                if (sample !in 0..MAX_PROGRESS) continue
                val paint = if (selected) selectedTextPaint else textPaint
                paint.textAlign = Paint.Align.RIGHT
                canvas.drawText(valueFormatter(sample), right - length - density(8f), y + textBaseline(paint), paint)
            }
        }
    }

    private fun drawHorizontal(canvas: Canvas) {
        val center = width / 2f
        val spacing = width / 8f
        val bottom = height.toFloat()
        for (index in -12..12) {
            val x = center + index * spacing / 2f
            if (x < 0f || x > width) continue
            val major = index % 2 == 0
            val selected = index == 0
            val length = if (major) height * 0.34f else height * 0.18f
            canvas.drawLine(
                x,
                bottom - length,
                x,
                bottom,
                if (selected) selectedPaint else tickPaint
            )
            if (major) {
                val sample = progress + index / 2 * valueInterval
                if (sample !in 0..MAX_PROGRESS) continue
                val paint = if (selected) selectedTextPaint else textPaint
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(valueFormatter(sample), x, bottom - length - density(7f), paint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val coordinate = if (vertical) event.y else event.x
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touchOrigin = coordinate
                progressOrigin = progress
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val extent = if (vertical) height else width
                if (extent > 0) {
                    val delta = ((coordinate - touchOrigin) / extent * 850f).roundToInt()
                    setProgressFromUser(progressOrigin + delta)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (abs(coordinate - touchOrigin) < density(3f)) performClick()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun setProgressFromUser(value: Int) {
        val next = value.coerceIn(0, MAX_PROGRESS)
        if (next == progress) return
        progress = next
        invalidate()
        onProgressChanged?.invoke(progress)
    }

    private fun textBaseline(paint: Paint): Float {
        return -(paint.ascent() + paint.descent()) / 2f
    }

    private fun density(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val MAX_PROGRESS = 1000
        private const val ACCENT = RawLiteDesign.ACCENT
    }
}
