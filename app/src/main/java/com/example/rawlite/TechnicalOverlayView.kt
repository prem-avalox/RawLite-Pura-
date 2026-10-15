package com.example.rawlite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.Image
import android.os.PowerManager
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal class TechnicalOverlayView(context: Context) : View(context) {
    private val openSans: Typeface = runCatching { resources.getFont(R.font.open_sans) }
        .getOrElse { Typeface.create("sans-serif", Typeface.NORMAL) }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = RawLiteDesign.SURFACE and 0x00FFFFFF or 0xCC000000.toInt()
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * resources.displayMetrics.scaledDensity
        typeface = openSans
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xA6FFFFFF.toInt()
        strokeWidth = resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val displayMatrix = Matrix()
    private val lock = Any()
    private var overlayBitmap: Bitmap? = null
    private var overlayPixels = IntArray(0)
    private var luma = ByteArray(0)
    private var analysisWidth = 0
    private var analysisHeight = 0
    private var previewHistogram = IntArray(HISTOGRAM_BINS)
    private var rawHistogram: IntArray? = null
    private var histogramSource = "PREV"
    @Volatile
    private var thermalStatus = PowerManager.THERMAL_STATUS_NONE
    private var rotationDegrees = 0

    @Volatile
    var mode: TechnicalOverlayMode = TechnicalOverlayMode.OFF
        set(value) {
            field = value
            updateVisibility()
            invalidate()
        }

    @Volatile
    var activeOverlay: ActiveOverlay = ActiveOverlay.NONE
        set(value) {
            field = value
            mode = when (value) {
                ActiveOverlay.NONE -> TechnicalOverlayMode.OFF
                ActiveOverlay.FOCUS_PEAKING -> TechnicalOverlayMode.PEAKING
                ActiveOverlay.ZEBRAS -> TechnicalOverlayMode.ZEBRA
                ActiveOverlay.FALSE_COLOR -> TechnicalOverlayMode.FALSE_COLOR
            }
        }

    var thirdsGridEnabled: Boolean = false
        set(value) {
            field = value
            updateVisibility()
            invalidate()
        }

    var histogramEnabled: Boolean = false
        set(value) {
            field = value
            updateVisibility()
            invalidate()
        }

    private fun updateVisibility() {
        visibility = if (mode == TechnicalOverlayMode.OFF && !thirdsGridEnabled && !histogramEnabled) GONE else VISIBLE
    }

    fun setGeometry(rotationDegrees: Int) {
        synchronized(lock) { this.rotationDegrees = ((rotationDegrees % 360) + 360) % 360 }
        invalidate()
    }

    fun setThermalStatus(status: Int) {
        thermalStatus = status
        invalidate()
    }

    fun submitPreview(image: Image) {
        if ((mode == TechnicalOverlayMode.OFF && !histogramEnabled) || thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            return
        }
        val plane = image.planes.firstOrNull() ?: return
        val source = plane.buffer
        val sourceWidth = image.width
        val sourceHeight = image.height
        val scale = max(1, sourceWidth / MAX_ANALYSIS_WIDTH)
        val outputWidth = max(1, sourceWidth / scale)
        val outputHeight = max(1, sourceHeight / scale)
        val count = outputWidth * outputHeight
        val nextLuma = ByteArray(count)
        val nextHistogram = IntArray(HISTOGRAM_BINS)
        var output = 0
        for (y in 0 until outputHeight) {
            val sourceY = min(sourceHeight - 1, y * scale)
            val row = sourceY * plane.rowStride
            for (x in 0 until outputWidth) {
                val sourceX = min(sourceWidth - 1, x * scale)
                val value = source.get(row + sourceX * plane.pixelStride).toInt() and 0xff
                nextLuma[output++] = value.toByte()
                nextHistogram[value * HISTOGRAM_BINS / 256]++
            }
        }
        synchronized(lock) {
            analysisWidth = outputWidth
            analysisHeight = outputHeight
            luma = nextLuma
            previewHistogram = nextHistogram
            if (mode != TechnicalOverlayMode.HISTOGRAM) renderOverlayLocked()
        }
        postInvalidateOnAnimation()
    }

    fun submitRawHistogram(histogram: IntArray) {
        synchronized(lock) {
            rawHistogram = histogram.copyOf()
            histogramSource = "RAW10"
        }
        postInvalidateOnAnimation()
    }

    fun clearRawHistogram() {
        synchronized(lock) {
            rawHistogram = null
            histogramSource = "PREV"
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mode != TechnicalOverlayMode.OFF) {
            if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                drawThermalSuppressed(canvas)
            } else {
                synchronized(lock) {
                    overlayBitmap?.let { bitmap ->
                        buildDisplayMatrixLocked(bitmap.width, bitmap.height)
                        canvas.drawBitmap(bitmap, displayMatrix, bitmapPaint)
                    }
                }
            }
        }
        if (histogramEnabled) {
            synchronized(lock) {
                drawHistogramLocked(canvas)
            }
        }
        if (thirdsGridEnabled) drawThirdsGrid(canvas)
    }

    private fun drawThirdsGrid(canvas: Canvas) {
        val x1 = width / 3f
        val x2 = width * 2f / 3f
        val y1 = height / 3f
        val y2 = height * 2f / 3f
        canvas.drawLine(x1, 0f, x1, height.toFloat(), gridPaint)
        canvas.drawLine(x2, 0f, x2, height.toFloat(), gridPaint)
        canvas.drawLine(0f, y1, width.toFloat(), y1, gridPaint)
        canvas.drawLine(0f, y2, width.toFloat(), y2, gridPaint)
    }

    private fun buildDisplayMatrixLocked(bitmapWidth: Int, bitmapHeight: Int) {
        val rotated = rotationDegrees == 90 || rotationDegrees == 270
        val orientedWidth = if (rotated) bitmapHeight.toFloat() else bitmapWidth.toFloat()
        val orientedHeight = if (rotated) bitmapWidth.toFloat() else bitmapHeight.toFloat()
        val scale = max(width / orientedWidth, height / orientedHeight)
        displayMatrix.reset()
        displayMatrix.postTranslate(-bitmapWidth / 2f, -bitmapHeight / 2f)
        displayMatrix.postRotate(rotationDegrees.toFloat())
        displayMatrix.postScale(scale, scale)
        displayMatrix.postTranslate(width / 2f, height / 2f)
    }

    private fun renderOverlayLocked() {
        val count = analysisWidth * analysisHeight
        if (count <= 0 || luma.size < count) return
        if (overlayPixels.size != count) overlayPixels = IntArray(count)
        when (mode) {
            TechnicalOverlayMode.ZEBRA -> renderZebra(count)
            TechnicalOverlayMode.FALSE_COLOR -> renderFalseColor(count)
            TechnicalOverlayMode.PEAKING -> renderPeaking()
            else -> overlayPixels.fill(Color.TRANSPARENT)
        }
        val bitmap = overlayBitmap
        if (bitmap == null || bitmap.width != analysisWidth || bitmap.height != analysisHeight) {
            bitmap?.recycle()
            overlayBitmap = Bitmap.createBitmap(
                analysisWidth,
                analysisHeight,
                Bitmap.Config.ARGB_8888
            )
        }
        overlayBitmap?.setPixels(
            overlayPixels,
            0,
            analysisWidth,
            0,
            0,
            analysisWidth,
            analysisHeight
        )
    }

    private fun renderZebra(count: Int) {
        for (index in 0 until count) {
            val value = luma[index].toInt() and 0xff
            if (limitedRangePercent(value) >= ZEBRA_THRESHOLD) {
                val x = index % analysisWidth
                val y = index / analysisWidth
                overlayPixels[index] = if ((x + y) / 4 % 2 == 0) {
                    0xddffffff.toInt()
                } else {
                    0xdd101010.toInt()
                }
            } else {
                overlayPixels[index] = Color.TRANSPARENT
            }
        }
    }

    private fun renderFalseColor(count: Int) {
        for (index in 0 until count) {
            val percent = limitedRangePercent(luma[index].toInt() and 0xff)
            overlayPixels[index] = falseColor(percent)
        }
    }

    private fun renderPeaking() {
        overlayPixels.fill(Color.TRANSPARENT)
        if (analysisWidth < 3 || analysisHeight < 3) return
        for (y in 1 until analysisHeight - 1) {
            val row = y * analysisWidth
            for (x in 1 until analysisWidth - 1) {
                fun sample(dx: Int, dy: Int): Int =
                    luma[row + dy * analysisWidth + x + dx].toInt() and 0xff
                val gx = -sample(-1, -1) + sample(1, -1) - 2 * sample(-1, 0) +
                    2 * sample(1, 0) - sample(-1, 1) + sample(1, 1)
                val gy = -sample(-1, -1) - 2 * sample(0, -1) - sample(1, -1) +
                    sample(-1, 1) + 2 * sample(0, 1) + sample(1, 1)
                val magnitude = sqrt((gx * gx + gy * gy).toDouble()).toInt()
                if (magnitude >= PEAK_THRESHOLD) overlayPixels[row + x] = PEAK_COLOR
            }
        }
    }

    private fun drawHistogramLocked(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val panelWidth = 120f * density
        val panelHeight = 60f * density
        val left = 16f * density
        val top = 12f * density
        val bounds = RectF(left, top, left + panelWidth, top + panelHeight)
        canvas.drawRoundRect(bounds, 4f * density, 4f * density, panelPaint)
        val histogram = rawHistogram ?: previewHistogram
        val peak = histogram.maxOrNull()?.coerceAtLeast(1) ?: 1
        val baseline = bounds.bottom - 10f * density
        val graphHeight = bounds.height() - 18f * density
        val step = bounds.width() / histogram.size
        linePaint.style = Paint.Style.STROKE
        linePaint.color = 0xff7ccec6.toInt()
        val path = android.graphics.Path()
        histogram.forEachIndexed { index, value ->
            val x = bounds.left + index * step
            val normalized = kotlin.math.ln(1.0 + value) / kotlin.math.ln(1.0 + peak)
            val y = baseline - graphHeight * normalized.toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
        canvas.drawText("HIST $histogramSource", bounds.left + 6f * density, bounds.bottom - 3f * density, textPaint)
    }

    private fun drawThermalSuppressed(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val message = "TOOLS PAUSADAS · TÉRMICO"
        val width = textPaint.measureText(message) + 24f * density
        val bounds = RectF(12f * density, 88f * density, 12f * density + width, 122f * density)
        canvas.drawRoundRect(bounds, 4f * density, 4f * density, panelPaint)
        canvas.drawText(message, bounds.left + 12f * density, bounds.centerY() + 4f * density, textPaint)
    }

    private fun limitedRangePercent(value: Int): Float =
        ((value - 16f) / 219f).coerceIn(0f, 1f)

    private fun falseColor(value: Float): Int {
        return when {
            value < 0.025f -> 0xeeb000ff.toInt()
            value < 0.08f -> 0xee204cff.toInt()
            value < 0.16f -> 0xee00a8ff.toInt()
            value < 0.30f -> 0xee707070.toInt()
            value < 0.45f -> 0xee18c97a.toInt()
            value < 0.58f -> 0xeeff70a8.toInt()
            value < 0.72f -> 0xeea8a8a8.toInt()
            value < 0.88f -> 0xeeffe044.toInt()
            value < 0.97f -> 0xeeff7a18.toInt()
            else -> 0xeeff2020.toInt()
        }
    }

    companion object {
        private const val MAX_ANALYSIS_WIDTH = 320
        private const val HISTOGRAM_BINS = 64
        private const val ZEBRA_THRESHOLD = 0.95f
        private const val PEAK_THRESHOLD = 180
        private const val PEAK_COLOR = 0xff00f0d0.toInt()
    }
}
