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
import android.os.Build
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
    private val histogramBounds = RectF()
    private val histogramPath = android.graphics.Path()
    @Volatile
    private var thermalStatus = PowerManager.THERMAL_STATUS_NONE
    private var rotationDegrees = 0
    private var videoWidth = 0
    private var videoHeight = 0
    private val frameRect = RectF()

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

    fun setGeometry(
        rotationDegrees: Int,
        videoWidth: Int = 0,
        videoHeight: Int = 0,
        frameLeft: Float = -1f,
        frameTop: Float = -1f,
        frameRight: Float = -1f,
        frameBottom: Float = -1f
    ) {
        synchronized(lock) {
            this.rotationDegrees = ((rotationDegrees % 360) + 360) % 360
            if (videoWidth > 0 && videoHeight > 0) {
                this.videoWidth = videoWidth
                this.videoHeight = videoHeight
            }
            if (frameLeft >= 0f && frameRight > frameLeft && frameTop >= 0f && frameBottom > frameTop) {
                frameRect.set(frameLeft, frameTop, frameRight, frameBottom)
            } else {
                updateFrameRectLocked()
            }
        }
        invalidate()
    }

    fun getVisibleFrameRect(): RectF = synchronized(lock) { RectF(frameRect) }

    private fun updateFrameRectLocked() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            frameRect.set(0f, 0f, 0f, 0f)
            return
        }
        val rotated = rotationDegrees == 90 || rotationDegrees == 270
        val vWidth = if (videoWidth > 0) videoWidth else analysisWidth
        val vHeight = if (videoHeight > 0) videoHeight else analysisHeight
        if (vWidth <= 0 || vHeight <= 0) {
            frameRect.set(0f, 0f, w, h)
            return
        }
        val orientedWidth = if (rotated) vHeight.toFloat() else vWidth.toFloat()
        val orientedHeight = if (rotated) vWidth.toFloat() else vHeight.toFloat()
        val fit = CaptureMath.fitWithin(w, h, orientedWidth, orientedHeight)
        frameRect.set(fit.left, fit.top, fit.right, fit.bottom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        synchronized(lock) {
            updateFrameRectLocked()
        }
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
            val sizeChanged = analysisWidth != outputWidth || analysisHeight != outputHeight
            analysisWidth = outputWidth
            analysisHeight = outputHeight
            luma = nextLuma
            previewHistogram = nextHistogram
            if (sizeChanged && (videoWidth <= 0 || videoHeight <= 0)) {
                updateFrameRectLocked()
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (mode != TechnicalOverlayMode.HISTOGRAM) renderOverlayLocked()
            }
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
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
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
        val left = frameRect.left
        val top = frameRect.top
        val right = frameRect.right
        val bottom = frameRect.bottom
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return

        val x1 = left + w / 3f
        val x2 = left + w * 2f / 3f
        val y1 = top + h / 3f
        val y2 = top + h * 2f / 3f
        canvas.drawLine(x1, top, x1, bottom, gridPaint)
        canvas.drawLine(x2, top, x2, bottom, gridPaint)
        canvas.drawLine(left, y1, right, y1, gridPaint)
        canvas.drawLine(left, y2, right, y2, gridPaint)
    }

    private fun buildDisplayMatrixLocked(bitmapWidth: Int, bitmapHeight: Int) {
        val rotated = rotationDegrees == 90 || rotationDegrees == 270
        val orientedWidth = if (rotated) bitmapHeight.toFloat() else bitmapWidth.toFloat()
        val orientedHeight = if (rotated) bitmapWidth.toFloat() else bitmapHeight.toFloat()
        val scale = min(width / orientedWidth, height / orientedHeight)
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
        histogramBounds.set(left, top, left + panelWidth, top + panelHeight)
        canvas.drawRoundRect(histogramBounds, 4f * density, 4f * density, panelPaint)
        val histogram = rawHistogram ?: previewHistogram
        val peak = histogram.maxOrNull()?.coerceAtLeast(1) ?: 1
        val baseline = histogramBounds.bottom - 10f * density
        val graphHeight = histogramBounds.height() - 18f * density
        val step = histogramBounds.width() / histogram.size
        linePaint.style = Paint.Style.STROKE
        linePaint.color = 0xff7ccec6.toInt()
        histogramPath.reset()
        histogram.forEachIndexed { index, value ->
            val x = histogramBounds.left + index * step
            val normalized = kotlin.math.ln(1.0 + value) / kotlin.math.ln(1.0 + peak)
            val y = baseline - graphHeight * normalized.toFloat()
            if (index == 0) histogramPath.moveTo(x, y) else histogramPath.lineTo(x, y)
        }
        canvas.drawPath(histogramPath, linePaint)
        canvas.drawText("HIST $histogramSource", histogramBounds.left + 6f * density, histogramBounds.bottom - 3f * density, textPaint)
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
