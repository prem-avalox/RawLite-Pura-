package com.example.rawlite

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.animation.PathInterpolator
import android.view.View
import com.example.rawlite.core.model.CameraParameter
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class CameraChromeView(context: Context) : View(context) {
    var onParameterSelected: ((Int) -> Unit)? = null
    var onRawPressed: (() -> Unit)? = null
    var onAutoPressed: (() -> Unit)? = null
    var onAutoLongPressed: (() -> Unit)? = null
    var onPreviewFocus: ((Float, Float, Boolean) -> Unit)? = null
    var onPushAutoChanged: ((Boolean) -> Unit)? = null
    var onWheelChanged: ((Int) -> Unit)? = null
    var onWheelStarted: (() -> Unit)? = null
    var onWheelReleased: (() -> Unit)? = null
    var onToolPressed: (() -> Unit)? = null
    var onStep: ((Int) -> Unit)? = null
    var onParameterStep: ((Int) -> Unit)? = null
    var onRecordPressed: (() -> Unit)? = null
    var onReviewPressed: (() -> Unit)? = null
    var onSettingsPressed: (() -> Unit)? = null
    var onRotationLockPressed: (() -> Unit)? = null
    var onLockChanged: ((LockGroup, Boolean) -> Unit)? = null

    enum class LockGroup { EXPOSURE, WHITE_BALANCE, FOCUS }

    private enum class FocusReticleState { SEARCHING, CONFIRMED, LOCKED }

    private data class FocusReticle(
        val point: PointF,
        val state: FocusReticleState
    )

    private data class DPadAction(
        val physicalDirection: Int,
        val logicalDirection: Int,
        val parameterAxis: Boolean
    )

    var selectorValues: List<String> = listOf("24mm", "15FPS", "1/60", "100ISO", "5000K", "∞", "2K")
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var autoParameterStates: List<Boolean> = listOf(false, false, true, true, true, true, false)
        set(value) {
            val normalized = List(7) { index -> value.getOrNull(index) == true }
            if (field == normalized) return
            field = normalized
            invalidate()
        }
    var selectedParameter: Int = 3
        set(value) {
            val next = value.coerceIn(0, 6)
            if (field == next) return
            val previous = field
            field = next
            animateSelectorAccent(previous, 0f)
            animateSelectorAccent(next, 1f)
            animateCenterLabel(previous, next)
            invalidate()
        }
    var rawActive: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var captureModeLabel: String = "RAW"
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var autoSupported: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var toolAlwaysOn: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var autoActive: Boolean = true
        set(value) {
            if (field == value) return
            animateAutoAccent(if (value) 1f else 0f)
            field = value
            invalidate()
        }
    var pushAutoActive: Boolean = false
        set(value) {
            if (field == value) return
            animatePushAutoAccent(if (value) 1f else 0f)
            field = value
            invalidate()
        }
    var recording: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                stabilizerWasStable = false
                displayedStabilizerRollDegrees = stabilizerRollDegrees
                clearFocusReticle()
            }
            updateRecordRingAnimation()
            updateGalleryRecordingAnimation()
            invalidate()
        }
    var saving: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) closeMenus()
            invalidate()
        }
    var leftHanded: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            stopRepeating()
            touchTarget = TARGET_NONE
            invalidate()
        }
    var selectedValueEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var pushAutoEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var rotationLocked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) rotationAnimator?.cancel()
            animateRotationAccent(if (value) 1f else 0f)
            invalidate()
        }
    var rotationControlEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var controlsEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var recordEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var stabilizerAvailable: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var stabilizerRollDegrees: Float = 0f
        set(value) {
            val next = value.coerceIn(-STABILIZER_MAX_ROLL_DEGREES, STABILIZER_MAX_ROLL_DEGREES)
            if (abs(field - next) < 0.04f) return
            val delta = normalizeDegrees(next - displayedStabilizerRollDegrees)
            val frameDelta = delta.coerceIn(
                -STABILIZER_MAX_FRAME_STEP_DEGREES,
                STABILIZER_MAX_FRAME_STEP_DEGREES
            )
            displayedStabilizerRollDegrees = normalizeDegrees(
                displayedStabilizerRollDegrees + frameDelta * STABILIZER_ROLL_FOLLOW_ALPHA
            )
            field = next
            invalidate()
        }
    var stabilizerStable: Boolean = false
        set(value) {
            if (field == value) return
            if (value && !stabilizerWasStable && stabilizerGuideVisible()) {
                levelHaptic()
            }
            stabilizerWasStable = value
            field = value
            invalidate()
        }
    var timerText: String = "00:00"
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var audioInputLabel: String = "MIC INT"
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var exposureLocked: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            animateLockGroup(0, if (value) 1f else 0f)
            animateLockAccent()
            invalidate()
        }
    var whiteBalanceLocked: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            animateLockGroup(1, if (value) 1f else 0f)
            animateLockAccent()
            invalidate()
        }
    var focusLocked: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            animateLockGroup(2, if (value) 1f else 0f)
            animateLockAccent()
            invalidate()
        }
    private var wheelProgress = 500
    private var displayWheelProgress = 500f
    private var wheelAnimationTarget = 500f
    private var majorProgressStep = 100
    private var wheelDiscrete = false
    private var wheelLiveDiscrete = false
    private var valueFormatter: (Int) -> String = { it.toString() }
    private var layoutLandscape = false
    private var contentScale = 1f
    private var contentOffsetX = 0f
    private var contentOffsetY = 0f
    private var lockMenuOpen = false
    private var lockMenuProgress = 0f
    private var touchTarget = TARGET_NONE
    private var wheelTouchOrigin = 0f
    private var wheelProgressOrigin = 500
    private var wheelDragging = false
    private var lastWheelHapticValue = ""
    private var repeatedDirection = 0
    private var repeatedPhysicalDirection = DPAD_NONE
    private var repeatedParameterAxis = false
    private var repeatedStartedAt = 0L
    private var dPadAxisLocked = false
    private var dPadLockedParameterAxis: Boolean? = null
    private val dPadTouchOrigin = PointF()
    private var dPadLastAxisCoordinate = 0f
    private var dPadLastMoveUptime = 0L
    private var dPadDragAccumulator = 0f
    private var dPadIntensity = 0f
    private var autoLongPressTriggered = false
    private var previewLongPressTriggered = false
    private val previewTouchOrigin = PointF()
    private var focusReticle: FocusReticle? = null
    private var focusReticleVisibility = 0f
    private var focusReticleScale = 1f
    private var focusReticleAnimator: ValueAnimator? = null
    private var focusReticleVisibilityAnimator: ValueAnimator? = null
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var chromeToolsVisible = true
    private var chromeReveal = 1f
    private var contentRotation = 0f
    private var chromeAnimator: ValueAnimator? = null
    private var rotationAnimator: ValueAnimator? = null
    private var wheelAnimator: ValueAnimator? = null
    private val selectorAccentProgress = FloatArray(8).apply { this[3] = 1f }
    private val selectorAnimators = arrayOfNulls<ValueAnimator>(8)
    private var autoAccentProgress = 1f
    private var pushAutoAccentProgress = 0f
    private var lockAccentProgress = 1f
    private var recordPressProgress = 0f
    private var autoAccentAnimator: ValueAnimator? = null
    private var pushAutoAccentAnimator: ValueAnimator? = null
    private var lockAccentAnimator: ValueAnimator? = null
    private var lockMenuAnimator: ValueAnimator? = null
    private var recordPressAnimator: ValueAnimator? = null
    private var rotationAccentProgress = 0f
    private var rotationAccentAnimator: ValueAnimator? = null
    private var recordRingProgress = 0f
    private var recordRingAnimator: ValueAnimator? = null
    private var galleryRecordingVisibility = 1f
    private var galleryRecordingAnimator: ValueAnimator? = null
    private var displayedStabilizerRollDegrees = 0f
    private var stabilizerWasStable = false
    private var lastLevelHapticUptime = 0L
    private var lastBlockedHapticUptime = 0L
    private val lockGroupProgress = FloatArray(3) { 1f }
    private val lockGroupAnimators = arrayOfNulls<ValueAnimator>(3)
    private val dPadAccentProgress = FloatArray(5)
    private val dPadAnimators = arrayOfNulls<ValueAnimator>(5)
    private var centerLabel = PARAMETER_NAMES[3]
    private var previousCenterLabel = centerLabel
    private var centerLabelTransition = 1f
    private var centerLabelShowsValue = false
    private var centerLabelVisibility = 0f
    private var centerLabelAnimator: ValueAnimator? = null
    private var centerLabelVisibilityAnimator: ValueAnimator? = null
    private var statusPopupText = ""
    private var statusPopupVisibility = 0f
    private var statusPopupAnimator: ValueAnimator? = null
    private val hideCenterLabelRunnable = Runnable {
        centerLabelVisibilityAnimator?.cancel()
        centerLabelVisibilityAnimator = animateFloat(
            centerLabelVisibility,
            0f,
            CENTER_LABEL_HIDE_MS
        ) {
            centerLabelVisibility = it
        }
    }
    private val hideStatusPopupRunnable = Runnable {
        statusPopupAnimator?.cancel()
        statusPopupAnimator = animateFloat(
            statusPopupVisibility,
            0f,
            STATUS_POPUP_HIDE_MS
        ) {
            statusPopupVisibility = it
        }
    }
    private val hideFocusReticleRunnable = Runnable {
        focusReticleVisibilityAnimator?.cancel()
        focusReticleVisibilityAnimator = animateFloat(
            focusReticleVisibility,
            0f,
            FOCUS_RETICLE_HIDE_MS
        ) {
            focusReticleVisibility = it
            if (it <= 0.001f) focusReticle = null
        }
    }
    private val bezierInterpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    private val openSans: Typeface = runCatching { resources.getFont(R.font.open_sans) }
        .getOrElse { Typeface.create("sans-serif", Typeface.NORMAL) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = openSans
        strokeCap = Paint.Cap.SQUARE
    }
    private val path = Path()
    private val lockIcon: Drawable? = context.getDrawable(R.drawable.ic_camera_lock)
    private val settingsIcon: Drawable? = context.getDrawable(R.drawable.ic_camera_settings)
    private val visibilityIcon: Drawable? = context.getDrawable(R.drawable.ic_camera_visibility)
    private val rotationIcon: Drawable? = context.getDrawable(R.drawable.ic_camera_rotation)
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    init {
        isFocusable = true
        isClickable = true
        contentDescription = "Controles de cámara"
    }

    fun configureWheel(
        progress: Int,
        majorStep: Int,
        discrete: Boolean,
        liveDiscrete: Boolean = false,
        formatter: (Int) -> String
    ) {
        val nextProgress = progress.coerceIn(0, MAX_PROGRESS)
        val nextMajorStep = majorStep.coerceAtLeast(1)
        val configurationChanged = wheelProgress != nextProgress ||
            majorProgressStep != nextMajorStep ||
            wheelDiscrete != discrete ||
            wheelLiveDiscrete != liveDiscrete
        if (!wheelDragging) {
            wheelProgress = nextProgress
            if (configurationChanged) animateWheelTo(wheelProgress.toFloat())
        }
        majorProgressStep = nextMajorStep
        wheelDiscrete = discrete
        wheelLiveDiscrete = liveDiscrete
        valueFormatter = formatter
        if (configurationChanged) invalidate()
    }

    private fun animateSelectorAccent(index: Int, target: Float) {
        if (index !in selectorAccentProgress.indices) return
        selectorAnimators[index]?.cancel()
        selectorAnimators[index] = animateFloat(selectorAccentProgress[index], target, COLOR_TRANSITION_MS) {
            selectorAccentProgress[index] = it
        }
    }

    private fun animateAutoAccent(target: Float) {
        autoAccentAnimator?.cancel()
        autoAccentAnimator = animateFloat(autoAccentProgress, target, COLOR_TRANSITION_MS) {
            autoAccentProgress = it
        }
    }

    private fun animatePushAutoAccent(target: Float) {
        pushAutoAccentAnimator?.cancel()
        pushAutoAccentAnimator = animateFloat(pushAutoAccentProgress, target, COLOR_TRANSITION_MS) {
            pushAutoAccentProgress = it
        }
    }

    private fun animateLockAccent() {
        val target = if (exposureLocked || whiteBalanceLocked || focusLocked) 1f else 0f
        lockAccentAnimator?.cancel()
        lockAccentAnimator = animateFloat(lockAccentProgress, target, COLOR_TRANSITION_MS) {
            lockAccentProgress = it
        }
    }

    private fun animateRotationAccent(target: Float) {
        rotationAccentAnimator?.cancel()
        rotationAccentAnimator = animateFloat(rotationAccentProgress, target, COLOR_TRANSITION_MS) {
            rotationAccentProgress = it
        }
    }

    private fun updateRecordRingAnimation() {
        recordRingAnimator?.cancel()
        recordRingAnimator = null
        if (!recording) {
            recordRingProgress = 0f
            return
        }
        recordRingAnimator = ValueAnimator.ofFloat(0.28f, 1f).apply {
            duration = RECORD_RING_HALF_CYCLE_MS
            interpolator = bezierInterpolator
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                recordRingProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun updateGalleryRecordingAnimation() {
        val target = if (recording && contentIsVertical()) 0f else 1f
        galleryRecordingAnimator?.cancel()
        galleryRecordingAnimator = animateFloat(
            galleryRecordingVisibility,
            target,
            if (target < galleryRecordingVisibility) GALLERY_HIDE_MS else GALLERY_SHOW_MS
        ) { galleryRecordingVisibility = it }
    }

    private fun animateLockGroup(index: Int, target: Float) {
        if (index !in lockGroupProgress.indices) return
        lockGroupAnimators[index]?.cancel()
        lockGroupAnimators[index] = animateFloat(lockGroupProgress[index], target, COLOR_TRANSITION_MS) {
            lockGroupProgress[index] = it
        }
    }

    private fun animateDPadAccent(index: Int, target: Float) {
        if (index !in 1..4) return
        dPadAnimators[index]?.cancel()
        dPadAnimators[index] = animateFloat(dPadAccentProgress[index], target, DPAD_COLOR_TRANSITION_MS) {
            dPadAccentProgress[index] = it
        }
    }

    private fun animateCenterLabel(previousIndex: Int, nextIndex: Int) {
        centerLabelShowsValue = false
        previousCenterLabel = PARAMETER_NAMES.getOrElse(previousIndex) { centerLabel }
        centerLabel = PARAMETER_NAMES.getOrElse(nextIndex) { centerLabel }
        centerLabelTransition = 0f
        centerLabelAnimator?.cancel()
        centerLabelAnimator = animateFloat(0f, 1f, CENTER_LABEL_TRANSITION_MS) {
            centerLabelTransition = it
        }
        revealCenterLabel()
    }

    private fun revealCurrentParameterName() {
        centerLabelShowsValue = false
        centerLabel = PARAMETER_NAMES.getOrElse(selectedParameter) { centerLabel }
        previousCenterLabel = centerLabel
        centerLabelTransition = 1f
        centerLabelAnimator?.cancel()
        revealCenterLabel()
    }

    private fun revealCenterValue() {
        centerLabelShowsValue = true
        centerLabel = PARAMETER_NAMES.getOrElse(selectedParameter) { centerLabel }
        previousCenterLabel = centerLabel
        centerLabelTransition = 1f
        centerLabelAnimator?.cancel()
        revealCenterLabel()
    }

    private fun revealCenterLabel() {
        repeatHandler.removeCallbacks(hideCenterLabelRunnable)
        if (centerLabelVisibility < 0.999f) {
            centerLabelVisibilityAnimator?.cancel()
            centerLabelVisibilityAnimator = animateFloat(
                centerLabelVisibility,
                1f,
                CENTER_LABEL_SHOW_MS
            ) {
                centerLabelVisibility = it
            }
        }
        repeatHandler.postDelayed(hideCenterLabelRunnable, CENTER_LABEL_HOLD_MS)
    }

    private fun animateWheelTo(target: Float) {
        val next = target.coerceIn(0f, MAX_PROGRESS.toFloat())
        if (abs(next - wheelAnimationTarget) < 0.25f && wheelAnimator?.isRunning == true) return
        if (abs(next - displayWheelProgress) < 0.25f) {
            displayWheelProgress = next
            wheelAnimationTarget = next
            invalidate()
            return
        }
        wheelAnimationTarget = next
        wheelAnimator?.cancel()
        wheelAnimator = animateFloat(displayWheelProgress, next, RULER_TRANSITION_MS) {
            displayWheelProgress = it
        }
    }

    private fun animateFloat(
        from: Float,
        to: Float,
        durationMs: Long,
        update: (Float) -> Unit
    ): ValueAnimator = ValueAnimator.ofFloat(from, to).apply {
        duration = durationMs
        interpolator = bezierInterpolator
        addUpdateListener {
            update(it.animatedValue as Float)
            invalidate()
        }
        start()
    }

    fun closeMenus() {
        if (!lockMenuOpen) return
        setLockMenuOpen(false)
    }

    private fun setLockMenuOpen(open: Boolean) {
        if (lockMenuOpen == open &&
            abs(lockMenuProgress - if (open) 1f else 0f) < 0.001f
        ) return
        lockMenuOpen = open
        lockMenuAnimator?.cancel()
        lockMenuAnimator = animateFloat(
            lockMenuProgress,
            if (open) 1f else 0f,
            LOCK_MENU_TRANSITION_MS
        ) { lockMenuProgress = it }
    }

    private fun animateRecordPress(pressed: Boolean) {
        recordPressAnimator?.cancel()
        recordPressAnimator = animateFloat(
            recordPressProgress,
            if (pressed) 1f else 0f,
            if (pressed) CONTROL_PRESS_IN_MS else CONTROL_PRESS_OUT_MS
        ) { recordPressProgress = it }
    }

    fun animateContentRotation(targetDegrees: Float) {
        val normalizedTarget = ((targetDegrees % 360f) + 360f) % 360f
        val normalizedCurrent = ((contentRotation % 360f) + 360f) % 360f
        var delta = normalizedTarget - normalizedCurrent
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        if (abs(delta) < 0.5f) return
        rotationAnimator?.cancel()
        rotationAnimator = ValueAnimator.ofFloat(contentRotation, contentRotation + delta).apply {
            duration = CONTENT_ROTATION_MS
            interpolator = bezierInterpolator
            addUpdateListener {
                contentRotation = it.animatedValue as Float
                if (recording) {
                    galleryRecordingVisibility = if (contentIsVertical()) 0f else 1f
                }
                invalidate()
            }
            start()
        }
    }

    fun rebaseContentRotation(degrees: Float) {
        rotationAnimator?.cancel()
        contentRotation = degrees
        invalidate()
    }

    fun currentContentRotationDegrees(): Float = contentRotation

    fun showStatusPopup(text: String) {
        statusPopupText = text
        repeatHandler.removeCallbacks(hideStatusPopupRunnable)
        statusPopupAnimator?.cancel()
        statusPopupAnimator = animateFloat(
            statusPopupVisibility,
            1f,
            STATUS_POPUP_SHOW_MS
        ) {
            statusPopupVisibility = it
        }
        repeatHandler.postDelayed(hideStatusPopupRunnable, STATUS_POPUP_HOLD_MS)
    }

    fun showFocusConfirmed() {
        if (focusReticle?.state != FocusReticleState.SEARCHING) return
        val current = focusReticle ?: return
        focusReticle = current.copy(state = FocusReticleState.CONFIRMED)
        focusReticleVisibilityAnimator?.cancel()
        focusReticleVisibility = 1f
        repeatHandler.removeCallbacks(hideFocusReticleRunnable)
        repeatHandler.postDelayed(hideFocusReticleRunnable, FOCUS_RETICLE_CONFIRMED_HOLD_MS)
        invalidate()
    }

    fun clearFocusReticle() {
        repeatHandler.removeCallbacks(hideFocusReticleRunnable)
        repeatHandler.removeCallbacks(previewLongPressRunnable)
        focusReticleAnimator?.cancel()
        focusReticleVisibilityAnimator?.cancel()
        focusReticle = null
        focusReticleVisibility = 0f
        focusReticleScale = 1f
        previewLongPressTriggered = false
        invalidate()
    }

    fun hideFocusReticle() {
        if (focusReticle == null) return
        repeatHandler.removeCallbacks(hideFocusReticleRunnable)
        hideFocusReticleRunnable.run()
    }

    private fun toggleChromeTools() {
        chromeToolsVisible = !chromeToolsVisible
        val target = if (chromeToolsVisible) 1f else 0f
        chromeAnimator?.cancel()
        chromeAnimator = ValueAnimator.ofFloat(chromeReveal, target).apply {
            duration = CHROME_TRANSITION_MS
            interpolator = bezierInterpolator
            addUpdateListener {
                chromeReveal = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutLandscape = w > h
        val canonicalWidth = if (layoutLandscape) LANDSCAPE_WIDTH else PORTRAIT_WIDTH
        val canonicalHeight = if (layoutLandscape) LANDSCAPE_HEIGHT else PORTRAIT_HEIGHT
        contentScale = min(w / canonicalWidth, h / canonicalHeight)
        contentOffsetX = (w - canonicalWidth * contentScale) / 2f
        contentOffsetY = (h - canonicalHeight * contentScale) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(contentOffsetX, contentOffsetY)
        canvas.scale(contentScale, contentScale)
        if (leftHanded) {
            canvas.translate(canonicalWidth(), 0f)
            canvas.scale(-1f, 1f)
        }
        if (layoutLandscape) drawLandscape(canvas) else drawPortrait(canvas)
        canvas.restore()
    }

    private fun drawLandscape(canvas: Canvas) {
        fill(canvas, 0f, 0f, GUTTER, LANDSCAPE_HEIGHT, Color.BLACK)
        fill(canvas, SELECTOR_START, 0f, SELECTOR_END, SELECTOR_THICKNESS,
            scaledAlpha(CHROME_OVERLAY, chromeReveal))
        fill(canvas, WHEEL_START, 0f, WHEEL_END, LANDSCAPE_HEIGHT,
            scaledAlpha(CHROME_OVERLAY, chromeReveal))
        fill(canvas, WHEEL_END, 0f, LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT, Color.BLACK)

        drawSelectors(canvas, landscape = true)
        if (chromeReveal > 0.001f) {
            drawRuler(canvas, landscape = true)
            drawAuto(canvas, autoRect(landscape = true))
            drawTool(canvas, toolRect(landscape = true))
        }
        drawParameterName(canvas, landscape = true)
        drawStabilizerGuide(canvas, landscape = true)
        drawFocusReticle(canvas)
        drawCaptureControls(canvas, landscape = true)
        drawBezelButtons(canvas, landscape = true)
        if (lockMenuProgress > 0.001f) drawLockMenu(canvas, landscape = true)
    }

    private fun drawPortrait(canvas: Canvas) {
        fill(canvas, 0f, 0f, PORTRAIT_WIDTH, GUTTER, Color.BLACK)
        fill(canvas, PORTRAIT_SELECTOR_X, GUTTER, PORTRAIT_WIDTH, PORTRAIT_SELECTOR_END,
            scaledAlpha(CHROME_OVERLAY, chromeReveal))
        fill(canvas, 0f, WHEEL_START, PORTRAIT_WIDTH, WHEEL_END,
            scaledAlpha(CHROME_OVERLAY, chromeReveal))
        fill(canvas, 0f, WHEEL_END, PORTRAIT_WIDTH, PORTRAIT_HEIGHT, Color.BLACK)

        drawSelectors(canvas, landscape = false)
        if (chromeReveal > 0.001f) {
            drawRuler(canvas, landscape = false)
            drawAuto(canvas, autoRect(landscape = false))
            drawTool(canvas, toolRect(landscape = false))
        }
        drawParameterName(canvas, landscape = false)
        drawStabilizerGuide(canvas, landscape = false)
        drawFocusReticle(canvas)
        drawCaptureControls(canvas, landscape = false)
        drawBezelButtons(canvas, landscape = false)
        if (lockMenuProgress > 0.001f) drawLockMenu(canvas, landscape = false)
    }

    private fun drawSelectors(canvas: Canvas, landscape: Boolean) {
        for (index in 0 until 8) {
            val rect = selectorRect(index, landscape)
            if (index > 0) {
                paint.color = DIVIDER
                paint.alpha = (Color.alpha(DIVIDER) * chromeReveal).roundToInt()
                paint.strokeWidth = 2f
                if (landscape) {
                    canvas.drawLine(rect.left, rect.top, rect.left, rect.bottom, paint)
                } else {
                    canvas.drawLine(rect.left, rect.top, rect.right, rect.top, paint)
                }
                paint.alpha = 255
            }
            val color = when {
                index == 7 && rawActive -> ACCENT
                index in 0..6 && autoParameterStates.getOrNull(index) == true -> AUTO
                else -> blendColor(Color.WHITE, ACCENT, selectorAccentProgress[index])
            }
            val label = if (index == 7) captureModeLabel else selectorValues.getOrElse(index) { "—" }
            withContentRotation(canvas, rect.centerX(), rect.centerY()) {
                val availableWidth = if (landscape) rect.width() else rect.height()
                drawSelectorLabel(
                    canvas,
                    label,
                    rect.centerX(),
                    rect.centerY(),
                    color,
                    availableWidth - 16f
                )
            }
        }
    }

    private fun drawSelectorLabel(
        canvas: Canvas,
        label: String,
        cx: Float,
        cy: Float,
        color: Int,
        maximumWidth: Float
    ) {
        val parts = splitValueAndUnit(label)
        val preferredValueSize = if (label.length >= 7) 42f else 50f
        val preferredUnitSize = 24f
        paint.typeface = openSans
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = preferredValueSize
        val valueWidth = paint.measureText(parts.first)
        paint.textSize = preferredUnitSize
        val unitWidth = if (parts.second.isEmpty()) 0f else paint.measureText(parts.second)
        val gap = if (parts.second.isEmpty()) 0f else 3f
        val preferredWidth = valueWidth + gap + unitWidth
        val scale = (maximumWidth / preferredWidth.coerceAtLeast(1f)).coerceIn(0.62f, 1f)
        val valueSize = preferredValueSize * scale
        val unitSize = preferredUnitSize * scale
        paint.textSize = valueSize
        val fittedValueWidth = paint.measureText(parts.first)
        paint.textSize = unitSize
        val fittedUnitWidth = if (parts.second.isEmpty()) 0f else paint.measureText(parts.second)
        val fittedGap = gap * scale
        var x = cx - (fittedValueWidth + fittedGap + fittedUnitWidth) / 2f
        val baseline = centeredBaseline(cy, valueSize)
        val textAlpha = if (controlsEnabled) 255 else 158
        drawSelectorText(canvas, parts.first, x, baseline, valueSize, color, textAlpha)
        x += fittedValueWidth + fittedGap
        if (parts.second.isNotEmpty()) {
            drawSelectorText(canvas, parts.second, x, baseline, unitSize, color, textAlpha)
        }
        paint.style = Paint.Style.FILL
        paint.alpha = 255
    }

    private fun drawSelectorText(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        textSize: Float,
        color: Int,
        fillAlpha: Int
    ) {
        paint.textSize = textSize
        val hiddenHudProgress = (1f - chromeReveal).coerceIn(0f, 1f)
        if (hiddenHudProgress > 0.001f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = HIDDEN_HUD_TEXT_OUTLINE_WIDTH
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = Color.BLACK
            paint.alpha = (fillAlpha * hiddenHudProgress).roundToInt()
            canvas.drawText(text, x, baseline, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.alpha = fillAlpha
        canvas.drawText(text, x, baseline, paint)
    }

    private fun splitValueAndUnit(label: String): Pair<String, String> {
        val compact = label.trim()
        val suffixes = listOf("FPS", "ISO", "mm", " cm", " m", "K")
        val suffix = suffixes.firstOrNull { compact.endsWith(it) } ?: return compact to ""
        return compact.removeSuffix(suffix).trimEnd() to suffix.trim()
    }

    private fun drawRuler(canvas: Canvas, landscape: Boolean) {
        val clip = rulerTouchRect(landscape)
        canvas.save()
        canvas.clipRect(clip)

        val center = if (landscape) LANDSCAPE_HEIGHT / 2f else PORTRAIT_WIDTH / 2f
        val displayedProgress = displayWheelProgress.coerceIn(0f, MAX_PROGRESS.toFloat())
        val base = floor(displayedProgress.toDouble() / majorProgressStep).toInt() * majorProgressStep
        val textOnlyDiscrete = selectedParameter == PARAMETER_LENS ||
            selectedParameter == PARAMETER_RESOLUTION
        val first = base - majorProgressStep * 7
        for (majorIndex in 0..14) {
            val sample = first + majorIndex * majorProgressStep
            if (sample !in 0..MAX_PROGRESS) continue
            val majorOffset = (sample - displayedProgress) / majorProgressStep * RULER_MAJOR_SPACING
            val majorPosition = if (landscape) center + majorOffset else center - majorOffset
            val alpha = fadeAlpha(majorPosition, landscape)
            if (!textOnlyDiscrete) {
                drawRulerTick(canvas, landscape, majorPosition, major = true, selected = false, alpha)
            }
            if (abs(majorPosition - center) > SELECTED_LABEL_CLEARANCE) {
                drawRulerLabel(canvas, landscape, majorPosition, valueFormatter(sample), Color.WHITE,
                    alpha)
            }
            if (majorIndex == 14 || textOnlyDiscrete) continue
            for (minor in 1..3) {
                val minorSample = sample + majorProgressStep * minor / 4f
                val minorOffset = (minorSample - displayedProgress) / majorProgressStep * RULER_MAJOR_SPACING
                val minorPosition = if (landscape) center + minorOffset else center - minorOffset
                drawRulerTick(canvas, landscape, minorPosition, major = false, selected = false,
                    fadeAlpha(minorPosition, landscape))
            }
        }

        val selectedAlpha = (255f * chromeReveal).roundToInt()
        if (!textOnlyDiscrete) {
            drawRulerTick(canvas, landscape, center, major = true, selected = true, alpha = selectedAlpha)
        }
        drawRulerLabel(canvas, landscape, center, valueFormatter(displayedProgress.roundToInt()),
            ACCENT, selectedAlpha, selected = true)
        canvas.restore()
    }

    private fun drawRulerTick(
        canvas: Canvas,
        landscape: Boolean,
        position: Float,
        major: Boolean,
        selected: Boolean,
        alpha: Int
    ) {
        paint.style = Paint.Style.STROKE
        paint.color = if (selected) ACCENT else Color.WHITE
        paint.alpha = alpha
        paint.strokeWidth = if (selected) 4f else 3f
        if (landscape) {
            val length = if (major) 77f else 27f
            canvas.drawLine(WHEEL_END - length, position, WHEEL_END, position, paint)
        } else {
            val length = if (major) 77f else 27f
            canvas.drawLine(position, WHEEL_END - length, position, WHEEL_END, paint)
        }
        paint.style = Paint.Style.FILL
        paint.alpha = 255
    }

    private fun drawRulerLabel(
        canvas: Canvas,
        landscape: Boolean,
        position: Float,
        text: String,
        color: Int,
        alpha: Int,
        selected: Boolean = false
    ) {
        val textSize = if (selectedParameter == PARAMETER_RESOLUTION) {
            if (selected) 30f else 19f
        } else if (selected) {
            48f
        } else {
            34f
        }
        paint.typeface = openSans
        paint.color = color
        paint.alpha = alpha
        paint.textSize = textSize
        val horizontalContent = !contentIsVertical()
        if (landscape) {
            paint.textAlign = Paint.Align.RIGHT
            val labelX = WHEEL_END - if (horizontalContent) {
                RULER_HORIZONTAL_LABEL_INSET
            } else {
                96f
            }
            withContentRotation(canvas, labelX, position) {
                canvas.drawText(text, labelX, centeredBaseline(position, textSize), paint)
            }
        } else {
            paint.textAlign = Paint.Align.CENTER
            val labelCenterY = WHEEL_END - if (horizontalContent) {
                RULER_HORIZONTAL_LABEL_INSET
            } else {
                92f
            }
            withContentRotation(canvas, position, labelCenterY) {
                val baseline = if (horizontalContent) {
                    centeredBaseline(labelCenterY, textSize)
                } else {
                    labelCenterY
                }
                canvas.drawText(text, position, baseline, paint)
            }
        }
        paint.alpha = 255
    }

    private fun fadeAlpha(position: Float, landscape: Boolean): Int {
        val bounds = rulerTouchRect(landscape)
        val start = if (landscape) bounds.top else bounds.left
        val end = if (landscape) bounds.bottom else bounds.right
        val fadeLength = (end - start) * RULER_FADE_FRACTION
        val distanceToEdge = min(position - start, end - position)
        val visible = (distanceToEdge / fadeLength).coerceIn(0f, 1f)
        return (visible * RULER_MAX_ALPHA * chromeReveal).roundToInt()
    }

    private fun drawAuto(canvas: Canvas, rect: RectF) {
        if (!autoSupported) return
        val color = blendColor(Color.WHITE, ACCENT, autoAccentProgress)
        val alpha = ((if (controlsEnabled) 255 else 158) * chromeReveal / 255f).coerceIn(0f, 1f)
        withContentRotation(canvas, rect.centerX(), rect.centerY()) {
            drawCenteredText(canvas, "AUTO", rect.centerX(), rect.centerY(), 36f, scaledAlpha(color, alpha))
        }
        paint.alpha = 255
    }

    private fun toolLabelForParameter(paramIndex: Int): String? = when (CameraParameter.entries.getOrNull(paramIndex)) {
        CameraParameter.FOCUS -> "PEAK"
        CameraParameter.SHUTTER -> "ZEBRA"
        CameraParameter.ISO -> "FALSE"
        CameraParameter.WHITE_BALANCE -> "HIST"
        else -> null
    }

    private fun drawTool(canvas: Canvas, rect: RectF) {
        val label = toolLabelForParameter(selectedParameter) ?: return
        val baseColor = if (toolAlwaysOn) ACCENT else 0x88FFFFFF.toInt()
        val alpha = ((if (controlsEnabled) 255 else 158) * chromeReveal / 255f).coerceIn(0f, 1f)
        val color = scaledAlpha(baseColor, alpha)
        paint.color = color
        withContentRotation(canvas, rect.centerX(), rect.centerY()) {
            drawCenteredText(canvas, label, rect.centerX(), rect.centerY(), 34f, paint)
        }
        paint.alpha = 255
    }

    private fun drawParameterName(canvas: Canvas, landscape: Boolean) {
        val visibility = centerLabelVisibility.coerceIn(0f, 1f)
        if (visibility <= 0.001f) return
        val center = if (landscape) PointF(1065f, 589.5f) else PointF(589.5f, 1065f)
        if (centerLabelShowsValue) {
            drawParameterValueHud(canvas, center, visibility)
            return
        }
        val transition = centerLabelTransition.coerceIn(0f, 1f)
        if (transition < 0.999f && previousCenterLabel != centerLabel) {
            drawOutlinedParameterName(
                canvas,
                previousCenterLabel,
                center.x,
                center.y - 10f * transition,
                (1f - transition) * visibility
            )
        }
        drawOutlinedParameterName(
            canvas,
            centerLabel,
            center.x,
            center.y + 10f * (1f - transition),
            transition * visibility
        )
    }

    private fun drawParameterValueHud(canvas: Canvas, center: PointF, alpha: Float) {
        withContentRotation(canvas, center.x, center.y) {
            drawOutlinedCenterText(
                canvas,
                centerLabel,
                center.x,
                center.y - 43f,
                CENTER_VALUE_NAME_TEXT_SIZE,
                Color.WHITE,
                alpha * 0.88f,
                4f
            )
            drawOutlinedCenterText(
                canvas,
                valueFormatter(wheelProgress),
                center.x,
                center.y + 31f,
                CENTER_LABEL_TEXT_SIZE,
                ACCENT,
                alpha,
                6f
            )
        }
    }

    private fun drawOutlinedParameterName(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        alpha: Float
    ) {
        if (alpha <= 0.001f) return
        withContentRotation(canvas, cx, cy) {
            drawOutlinedCenterText(
                canvas,
                text,
                cx,
                cy,
                CENTER_LABEL_TEXT_SIZE,
                ACCENT,
                alpha,
                6f
            )
        }
    }

    private fun drawOutlinedCenterText(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        textSize: Float,
        color: Int,
        alpha: Float,
        outlineWidth: Float
    ) {
        if (alpha <= 0.001f) return
        paint.typeface = openSans
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = textSize
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = outlineWidth
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = scaledAlpha(Color.BLACK, alpha * 0.82f)
        val baseline = centeredBaseline(cy, textSize)
        canvas.drawText(text, cx, baseline, paint)
        paint.style = Paint.Style.FILL
        paint.color = scaledAlpha(color, alpha)
        canvas.drawText(text, cx, baseline, paint)
    }

    private fun drawStabilizerGuide(canvas: Canvas, landscape: Boolean) {
        if (!stabilizerGuideVisible()) return
        val center = if (landscape) PointF(1065f, 589.5f) else PointF(589.5f, 1065f)
        val color = if (stabilizerStable) AUTO else Color.WHITE
        val alpha = if (stabilizerStable) STABILIZER_STABLE_ALPHA else STABILIZER_ACTIVE_ALPHA
        val roll = displayedStabilizerRollDegrees
        canvas.save()
        if (leftHanded) canvas.scale(-1f, 1f, center.x, center.y)
        canvas.rotate(-roll, center.x, center.y)
        val previousCap = paint.strokeCap
        paint.strokeCap = Paint.Cap.ROUND
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (stabilizerStable) 3f else 3.5f
        paint.color = scaledAlpha(color, alpha)
        drawStabilizerSegments(canvas, center)
        paint.style = Paint.Style.FILL
        paint.color = scaledAlpha(color, alpha)
        canvas.drawCircle(center.x, center.y, if (stabilizerStable) 4.5f else 3.5f, paint)
        paint.strokeCap = previousCap
        paint.alpha = 255
        canvas.restore()
    }

    private fun stabilizerGuideVisible(): Boolean =
        stabilizerAvailable &&
            !recording &&
            !saving &&
            centerLabelVisibility <= 0.001f

    private fun drawStabilizerSegments(canvas: Canvas, center: PointF) {
        val outer = 196f
        val inner = 90f
        val tickHalf = 14f
        canvas.drawLine(center.x - outer, center.y, center.x - inner, center.y, paint)
        canvas.drawLine(center.x + inner, center.y, center.x + outer, center.y, paint)
        canvas.drawLine(center.x, center.y - tickHalf, center.x, center.y + tickHalf, paint)
    }

    private fun drawFocusReticle(canvas: Canvas) {
        val reticle = focusReticle ?: return
        if (recording || saving || centerLabelVisibility > 0.001f) return
        val visibility = focusReticleVisibility.coerceIn(0f, 1f)
        if (visibility <= 0.001f) return
        val color = when (reticle.state) {
            FocusReticleState.SEARCHING -> Color.WHITE
            FocusReticleState.CONFIRMED -> AUTO
            FocusReticleState.LOCKED -> ACCENT
        }
        val alpha = if (reticle.state == FocusReticleState.LOCKED) {
            0.96f
        } else {
            visibility
        }
        withContentRotation(canvas, reticle.point.x, reticle.point.y) {
            canvas.save()
            val scale = focusReticleScale.coerceIn(0.88f, 1.16f)
            canvas.scale(scale, scale, reticle.point.x, reticle.point.y)
            val previousCap = paint.strokeCap
            paint.strokeCap = Paint.Cap.ROUND
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.2f
            paint.color = scaledAlpha(color, alpha)
            val cx = reticle.point.x
            val cy = reticle.point.y
            val half = 92f
            val corner = 34f
            canvas.drawLine(cx - half, cy - half, cx - half + corner, cy - half, paint)
            canvas.drawLine(cx - half, cy - half, cx - half, cy - half + corner, paint)
            canvas.drawLine(cx + half - corner, cy - half, cx + half, cy - half, paint)
            canvas.drawLine(cx + half, cy - half, cx + half, cy - half + corner, paint)
            canvas.drawLine(cx - half, cy + half - corner, cx - half, cy + half, paint)
            canvas.drawLine(cx - half, cy + half, cx - half + corner, cy + half, paint)
            canvas.drawLine(cx + half - corner, cy + half, cx + half, cy + half, paint)
            canvas.drawLine(cx + half, cy + half - corner, cx + half, cy + half, paint)
            paint.style = Paint.Style.FILL
            paint.color = scaledAlpha(color, alpha)
            canvas.drawCircle(cx, cy, if (reticle.state == FocusReticleState.LOCKED) 5.5f else 4.5f, paint)
            paint.strokeCap = previousCap
            paint.alpha = 255
            canvas.restore()
        }
    }

    private fun drawCaptureControls(canvas: Canvas, landscape: Boolean) {
        val recordCenter = recordCenter(landscape)
        val pushAutoCenter = pushAutoCenter(landscape)
        val indicatorCenter = recordingIndicatorCenter(landscape)
        val timerCenter = recordingTimerCenter(landscape)
        val dPad = precisionRect(landscape)

        drawPushAutoControl(canvas, pushAutoCenter.x, pushAutoCenter.y)
        val galleryVisibility = if (contentIsVertical()) galleryRecordingVisibility else 1f
        if (galleryVisibility > 0.001f) {
            drawGalleryButton(canvas, galleryDisplayRect(landscape), galleryVisibility)
        }

        val recordScale = 1f - recordPressProgress * 0.045f
        canvas.save()
        canvas.scale(recordScale, recordScale, recordCenter.x, recordCenter.y)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFF3F2EE.toInt()
        paint.alpha = if (recordEnabled) 255 else 140
        canvas.drawCircle(recordCenter.x, recordCenter.y, 92f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = 0xFFD2D0C9.toInt()
        canvas.drawCircle(recordCenter.x, recordCenter.y, 97f, paint)
        if (recording) {
            paint.strokeWidth = 6f
            paint.color = scaledAlpha(RawLiteDesign.RECORD, 0.20f + recordRingProgress * 0.72f)
            canvas.drawCircle(
                recordCenter.x,
                recordCenter.y,
                104f + recordRingProgress * 4f,
                paint
            )
        }
        canvas.restore()
        paint.style = Paint.Style.FILL
        paint.alpha = 255

        if (recording) {
            paint.color = RawLiteDesign.RECORD
            canvas.drawCircle(indicatorCenter.x, indicatorCenter.y, 12f, paint)
            withContentRotation(canvas, timerCenter.x, timerCenter.y) {
                paint.color = 0x80000000.toInt()
                val timerRect = RectF(timerCenter.x - 97.5f, timerCenter.y - 37.5f,
                    timerCenter.x + 97.5f, timerCenter.y + 37.5f)
                canvas.drawRoundRect(timerRect, 37.5f, 37.5f, paint)
                drawCenteredText(canvas, timerText, timerCenter.x, timerCenter.y, 48f, 0xFFFFF0F0.toInt())
            }
        } else if (saving) {
            drawSavingStatus(canvas, timerCenter)
        } else {
            drawStatusPopup(canvas, timerCenter)
        }

        withContentRotation(canvas, timerCenter.x, timerCenter.y) {
            drawOutlinedCenterText(
                canvas, audioInputLabel, timerCenter.x, timerCenter.y - 62f,
                24f, if (audioInputLabel.contains("!") || audioInputLabel == "MIC ERROR")
                    RawLiteDesign.RECORD else Color.WHITE, 1f, 4f
            )
        }

        drawDPadBackground(canvas, dPad)
        withContentRotation(canvas, dPad.centerX(), dPad.centerY()) {
            drawDPadArrows(canvas, dPad)
        }
    }

    private fun drawStatusPopup(canvas: Canvas, center: PointF) {
        val visibility = statusPopupVisibility.coerceIn(0f, 1f)
        if (visibility <= 0.001f || statusPopupText.isEmpty()) return
        withContentRotation(canvas, center.x, center.y) {
            paint.style = Paint.Style.FILL
            paint.color = scaledAlpha(0xD9000000.toInt(), visibility)
            val halfWidth = max(97.5f, 26f + textWidth(statusPopupText, 32f) / 2f)
            val popupRect = RectF(
                center.x - halfWidth,
                center.y - 37.5f,
                center.x + halfWidth,
                center.y + 37.5f
            )
            canvas.drawRoundRect(popupRect, 37.5f, 37.5f, paint)
            drawCenteredText(
                canvas,
                statusPopupText,
                center.x,
                center.y,
                32f,
                scaledAlpha(ACCENT, visibility)
            )
        }
    }

    private fun drawSavingStatus(canvas: Canvas, center: PointF) {
        withContentRotation(canvas, center.x, center.y) {
            paint.style = Paint.Style.FILL
            paint.color = 0xD9000000.toInt()
            val label = "GUARDANDO"
            val halfWidth = max(126f, 30f + textWidth(label, 30f) / 2f)
            val rect = RectF(
                center.x - halfWidth,
                center.y - 37.5f,
                center.x + halfWidth,
                center.y + 37.5f
            )
            canvas.drawRoundRect(rect, 37.5f, 37.5f, paint)
            drawCenteredText(canvas, label, center.x, center.y, 30f, ACCENT)
        }
    }

    private fun textWidth(text: String, size: Float): Float {
        paint.typeface = openSans
        paint.textSize = size
        return paint.measureText(text)
    }

    private fun drawPushAutoControl(canvas: Canvas, cx: Float, cy: Float) {
        val active = pushAutoAccentProgress.coerceIn(0f, 1f)
        paint.style = Paint.Style.FILL
        paint.color = blendColor(RawLiteDesign.SURFACE, RawLiteDesign.ACCENT, active)
        paint.alpha = if (pushAutoEnabled) 255 else 115
        canvas.drawCircle(cx, cy, 92f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = blendColor(0xBFFFFFFF.toInt(), RawLiteDesign.ACCENT, active)
        canvas.drawCircle(cx, cy, 97f, paint)
        paint.strokeWidth = 1.5f
        paint.color = blendColor(0x45FFFFFF, 0x99000000.toInt(), active)
        canvas.drawCircle(cx, cy, 79f, paint)
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        withContentRotation(canvas, cx, cy) {
            val textColor = if (!pushAutoEnabled) {
                RawLiteDesign.TEXT_DISABLED
            } else {
                blendColor(Color.WHITE, Color.BLACK, active)
            }
            drawCenteredText(canvas, "PUSH", cx, cy - 19f, 20f, textColor)
            drawCenteredText(canvas, "AUTO", cx, cy + 17f, 29f, textColor)
        }
    }

    private fun drawGalleryButton(canvas: Canvas, rect: RectF, visibility: Float) {
        withContentRotation(canvas, rect.centerX(), rect.centerY()) {
            paint.style = Paint.Style.FILL
            paint.color = scaledAlpha(if (touchTarget == TARGET_REVIEW) {
                RawLiteDesign.SURFACE_ACTIVE
            } else {
                RawLiteDesign.SURFACE
            }, visibility)
            canvas.drawRoundRect(rect, 6f, 6f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = scaledAlpha(RawLiteDesign.OUTLINE, visibility)
            canvas.drawRoundRect(rect, 6f, 6f, paint)
            paint.style = Paint.Style.FILL
            paint.color = scaledAlpha(RawLiteDesign.ACCENT, visibility)
            canvas.drawRect(rect.left, rect.top, rect.left + 5f, rect.bottom, paint)
            drawCenteredText(
                canvas,
                "TOMAS",
                rect.centerX() + 3f,
                rect.centerY(),
                24f,
                scaledAlpha(Color.WHITE, visibility)
            )
        }
    }

    private fun drawDPadBackground(canvas: Canvas, rect: RectF) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val arm = DPAD_ARM_HALF_WIDTH
        path.reset()
        path.moveTo(cx - arm, rect.top)
        path.lineTo(cx + arm, rect.top)
        path.lineTo(cx + arm, cy - arm)
        path.lineTo(rect.right, cy - arm)
        path.lineTo(rect.right, cy + arm)
        path.lineTo(cx + arm, cy + arm)
        path.lineTo(cx + arm, rect.bottom)
        path.lineTo(cx - arm, rect.bottom)
        path.lineTo(cx - arm, cy + arm)
        path.lineTo(rect.left, cy + arm)
        path.lineTo(rect.left, cy - arm)
        path.lineTo(cx - arm, cy - arm)
        path.close()
        paint.pathEffect = CornerPathEffect(14f)
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = 0x99FFFFFF.toInt()
        canvas.drawPath(path, paint)
        paint.pathEffect = null
        paint.strokeJoin = Paint.Join.MITER
        paint.style = Paint.Style.FILL
    }

    private fun drawDPadArrows(canvas: Canvas, rect: RectF) {
        val offset = DPAD_ARROW_OFFSET
        drawChevron(canvas, rect.centerX(), rect.centerY() - offset, up = true,
            color = blendColor(Color.WHITE, ACCENT, dPadAccentProgress[DPAD_UP]))
        drawChevron(canvas, rect.centerX(), rect.centerY() + offset, up = false,
            color = blendColor(Color.WHITE, ACCENT, dPadAccentProgress[DPAD_DOWN]))
        drawChevronHorizontal(canvas, rect.centerX() - offset, rect.centerY(), left = true,
            color = blendColor(Color.WHITE, ACCENT, dPadAccentProgress[DPAD_LEFT]))
        drawChevronHorizontal(canvas, rect.centerX() + offset, rect.centerY(), left = false,
            color = blendColor(Color.WHITE, ACCENT, dPadAccentProgress[DPAD_RIGHT]))
    }

    private fun drawChevron(canvas: Canvas, cx: Float, cy: Float, up: Boolean, color: Int) {
        val sign = if (up) -1f else 1f
        path.reset()
        path.moveTo(cx - 24f, cy - 14f * sign)
        path.lineTo(cx, cy + 16f * sign)
        path.lineTo(cx + 24f, cy - 14f * sign)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 7f
        paint.strokeCap = Paint.Cap.SQUARE
        paint.color = color
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawChevronHorizontal(canvas: Canvas, cx: Float, cy: Float, left: Boolean, color: Int) {
        val sign = if (left) -1f else 1f
        path.reset()
        path.moveTo(cx - 14f * sign, cy - 24f)
        path.lineTo(cx + 16f * sign, cy)
        path.lineTo(cx - 14f * sign, cy + 24f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 7f
        paint.strokeCap = Paint.Cap.SQUARE
        paint.color = color
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawBezelButtons(canvas: Canvas, landscape: Boolean) {
        val lockCenter = if (landscape) PointF(106.5f, 1081f) else PointF(98.5f, 106.5f)
        val settingsCenter = if (landscape) PointF(106.5f, 884f) else PointF(295.5f, 106.5f)
        val visibilityCenter = visibilityCenter(landscape)
        val rotationCenter = rotationCenter(landscape)
        drawToolButton(canvas, lockCenter.x, lockCenter.y, lockIcon,
            activeProgress = lockAccentProgress)
        drawToolButton(canvas, settingsCenter.x, settingsCenter.y, settingsIcon, activeProgress = 0f)
        drawToolButton(canvas, visibilityCenter.x, visibilityCenter.y, visibilityIcon,
            activeProgress = chromeReveal)
        drawToolButton(canvas, rotationCenter.x, rotationCenter.y, rotationIcon,
            activeProgress = rotationAccentProgress, enabled = rotationControlEnabled)
    }

    private fun drawToolButton(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        icon: Drawable?,
        activeProgress: Float,
        enabled: Boolean = true
    ) {
        val color = scaledAlpha(
            blendColor(Color.WHITE, ACCENT, activeProgress),
            if (enabled) 1f else 0.48f
        )
        paint.style = Paint.Style.FILL
        paint.color = RawLiteDesign.SURFACE
        canvas.drawCircle(cx, cy, 33f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = color
        canvas.drawCircle(cx, cy, 35f, paint)
        icon?.let {
            val half = 17
            it.setBounds((cx - half).roundToInt(), (cy - half).roundToInt(),
                (cx + half).roundToInt(), (cy + half).roundToInt())
            it.setTint(color)
            withContentRotation(canvas, cx, cy) { it.draw(canvas) }
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawLockMenu(canvas: Canvas, landscape: Boolean) {
        val visibility = lockMenuProgress.coerceIn(0f, 1f)
        val first = lockMenuRect(0, landscape)
        val last = lockMenuRect(2, landscape)
        val panelRect = RectF(
            min(first.left, last.left),
            min(first.top, last.top),
            max(first.right, last.right),
            max(first.bottom, last.bottom)
        )
        val slide = (1f - visibility) * 16f
        canvas.save()
        if (landscape) canvas.translate(-slide, 0f) else canvas.translate(0f, -slide)
        paint.style = Paint.Style.FILL
        paint.color = scaledAlpha(0xF20A0A0A.toInt(), visibility)
        canvas.drawRoundRect(panelRect, 6f, 6f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = scaledAlpha(RawLiteDesign.OUTLINE, visibility)
        canvas.drawRoundRect(panelRect, 6f, 6f, paint)
        paint.style = Paint.Style.FILL
        for (index in 0..2) {
            val rect = lockMenuRect(index, landscape)
            if (index > 0) {
                paint.color = scaledAlpha(DIVIDER, visibility)
                if (landscape) {
                    canvas.drawRect(rect.left, rect.bottom - 2f, rect.right, rect.bottom, paint)
                } else {
                    canvas.drawRect(rect.left, rect.top, rect.left + 2f, rect.bottom, paint)
                }
            }
            val label = when (index) {
                0 -> "EXP"
                1 -> "WB"
                else -> "FOCUS"
            }
            val activeProgress = lockGroupProgress[index]
            withContentRotation(canvas, rect.centerX(), rect.centerY()) {
                if (landscape) {
                    drawCenteredText(canvas, label, rect.centerX(), rect.top + 54f, 23f,
                        scaledAlpha(Color.WHITE, visibility))
                    drawSwitch(canvas, rect.centerX(), rect.top + 136f, activeProgress)
                } else {
                    drawCenteredText(canvas, label, rect.centerX(), rect.top + 52f, 23f,
                        scaledAlpha(Color.WHITE, visibility))
                    drawSwitch(canvas, rect.centerX(), rect.top + 133f, activeProgress)
                }
            }
        }
        canvas.restore()
    }

    private fun drawSwitch(canvas: Canvas, cx: Float, cy: Float, activeProgress: Float) {
        val progress = activeProgress.coerceIn(0f, 1f)
        val rect = RectF(cx - 45f, cy - 17f, cx + 45f, cy + 17f)
        paint.style = Paint.Style.FILL
        paint.color = blendColor(RawLiteDesign.SURFACE_ACTIVE, ACCENT, progress)
        canvas.drawRoundRect(rect, 17f, 17f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = blendColor(RawLiteDesign.OUTLINE, ACCENT, progress)
        canvas.drawRoundRect(rect, 17f, 17f, paint)
        paint.style = Paint.Style.FILL
        paint.color = blendColor(RawLiteDesign.TEXT, RawLiteDesign.BLACK, progress)
        canvas.drawCircle(cx - 25f + 50f * progress, cy, 12f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (contentScale <= 0f) return false
        val point = toCanonical(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return handleDown(point)
            MotionEvent.ACTION_MOVE -> return handleMove(point)
            MotionEvent.ACTION_UP -> {
                val handled = handleUp(point, cancelled = false)
                performClick()
                return handled
            }
            MotionEvent.ACTION_CANCEL -> return handleUp(point, cancelled = true)
        }
        return true
    }

    private fun handleDown(point: PointF): Boolean {
        if (lockMenuOpen) {
            for (index in 0..2) {
                if (lockMenuRect(index, layoutLandscape).contains(point.x, point.y)) {
                    touchTarget = TARGET_LOCK_ITEM_BASE + index
                    return true
                }
            }
        }
        if (insideToolButton(point, lock = true)) {
            touchTarget = TARGET_LOCK_BUTTON
            return true
        }
        if (insideToolButton(point, lock = false)) {
            touchTarget = TARGET_SETTINGS
            return true
        }
        if (insideCircle(point, visibilityCenter(layoutLandscape), 48f)) {
            touchTarget = TARGET_VISIBILITY
            return true
        }
        if (insideCircle(point, rotationCenter(layoutLandscape), 48f)) {
            touchTarget = if (rotationControlEnabled) TARGET_ROTATION else TARGET_NONE
            return true
        }
        if (lockMenuOpen) {
            setLockMenuOpen(false)
        }
        for (index in 0 until 8) {
            if (selectorRect(index, layoutLandscape).contains(point.x, point.y)) {
                touchTarget = TARGET_SELECTOR_BASE + index
                return true
            }
        }
        if (chromeToolsVisible && autoSupported && selectedValueEnabled &&
            autoRect(layoutLandscape).contains(point.x, point.y)
        ) {
            touchTarget = TARGET_AUTO
            autoLongPressTriggered = false
            repeatHandler.postDelayed(autoLongPressRunnable, AUTO_LONG_PRESS_MS)
            return true
        }
        if (chromeToolsVisible && selectedValueEnabled &&
            toolRect(layoutLandscape).contains(point.x, point.y)
        ) {
            touchTarget = TARGET_TOOL
            return true
        }
        if (insideCircle(point, pushAutoCenter(layoutLandscape), 108f) && pushAutoEnabled) {
            touchTarget = TARGET_PUSH_AUTO
            pushAutoActive = true
            onPushAutoChanged?.invoke(true)
            return true
        }
        if (insideCircle(point, recordCenter(layoutLandscape), 108f)) {
            touchTarget = TARGET_RECORD
            animateRecordPress(true)
            return true
        }
        if (galleryRecordingVisibility > 0.5f &&
            (!recording || !contentIsVertical()) &&
            galleryDisplayRect(layoutLandscape).contains(point.x, point.y)
        ) {
            touchTarget = TARGET_REVIEW
            invalidate()
            return true
        }
        val precision = precisionRect(layoutLandscape)
        if (precision.contains(point.x, point.y)) {
            touchTarget = TARGET_PRECISION
            dPadTouchOrigin.set(point.x, point.y)
            dPadAxisLocked = false
            dPadLockedParameterAxis = null
            dPadDragAccumulator = 0f
            parent?.requestDisallowInterceptTouchEvent(true)
            dPadAction(point, precision)?.let { action ->
                lockDPadAxis(action, point, precision)
            }
            return true
        }
        if (chromeToolsVisible && selectedValueEnabled &&
            rulerTouchRect(layoutLandscape).contains(point.x, point.y)
        ) {
            touchTarget = TARGET_WHEEL
            wheelDragging = true
            revealCenterValue()
            wheelTouchOrigin = if (layoutLandscape) point.y else point.x
            wheelProgressOrigin = wheelProgress
            lastWheelHapticValue = valueFormatter(wheelProgress)
            parent?.requestDisallowInterceptTouchEvent(true)
            onWheelStarted?.invoke()
            return true
        }
        if (!recording && !saving && controlsEnabled &&
            previewHitRect(layoutLandscape).contains(point.x, point.y)
        ) {
            touchTarget = TARGET_PREVIEW_FOCUS
            previewLongPressTriggered = false
            previewTouchOrigin.set(point.x, point.y)
            repeatHandler.postDelayed(previewLongPressRunnable, PREVIEW_FOCUS_LONG_PRESS_MS)
            return true
        }
        touchTarget = TARGET_NONE
        return true
    }

    private fun handleMove(point: PointF): Boolean {
        if (touchTarget == TARGET_PRECISION) {
            if (!dPadAxisLocked) {
                val dx = point.x - dPadTouchOrigin.x
                val dy = point.y - dPadTouchOrigin.y
                if (hypot(dx, dy) >= DPAD_AXIS_LOCK_THRESHOLD) {
                    val screenDirection = directionForDelta(dx, dy)
                    lockDPadAxis(dPadAction(screenDirection), point, precisionRect(layoutLandscape))
                }
            } else {
                handleDPadDrag(point, precisionRect(layoutLandscape))
            }
            return true
        }
        if (touchTarget == TARGET_PREVIEW_FOCUS) {
            val dx = point.x - previewTouchOrigin.x
            val dy = point.y - previewTouchOrigin.y
            if (hypot(dx, dy) > PREVIEW_FOCUS_TAP_SLOP ||
                !previewHitRect(layoutLandscape).contains(point.x, point.y)
            ) {
                repeatHandler.removeCallbacks(previewLongPressRunnable)
            }
            return true
        }
        if (touchTarget != TARGET_WHEEL || !wheelDragging || !controlsEnabled ||
            !selectedValueEnabled
        ) return true
        val coordinate = if (layoutLandscape) point.y else point.x
        val delta = coordinate - wheelTouchOrigin
        val progressDelta = if (layoutLandscape) {
            -delta / RULER_MAJOR_SPACING * majorProgressStep
        } else {
            delta / RULER_MAJOR_SPACING * majorProgressStep
        }
        wheelProgress = (wheelProgressOrigin + progressDelta).roundToInt().coerceIn(0, MAX_PROGRESS)
        revealCenterValue()
        performWheelHapticIfNeeded()
        wheelAnimator?.cancel()
        wheelAnimationTarget = wheelProgress.toFloat()
        displayWheelProgress = wheelProgress.toFloat()
        if (!wheelDiscrete) {
            onWheelChanged?.invoke(wheelProgress)
        } else if (wheelLiveDiscrete) {
            val snapped = ((wheelProgress.toFloat() / majorProgressStep).roundToInt() *
                majorProgressStep).coerceIn(0, MAX_PROGRESS)
            onWheelChanged?.invoke(snapped)
        }
        invalidate()
        return true
    }

    private fun handleUp(point: PointF, cancelled: Boolean): Boolean {
        stopRepeating()
        val target = touchTarget
        touchTarget = TARGET_NONE
        if (target == TARGET_AUTO) repeatHandler.removeCallbacks(autoLongPressRunnable)
        if (target == TARGET_PREVIEW_FOCUS) repeatHandler.removeCallbacks(previewLongPressRunnable)
        if (target == TARGET_RECORD) animateRecordPress(false)
        if (target == TARGET_REVIEW) invalidate()
        if (target == TARGET_PRECISION) {
            dPadAxisLocked = false
            dPadLockedParameterAxis = null
            dPadDragAccumulator = 0f
            parent?.requestDisallowInterceptTouchEvent(false)
            return true
        }
        if (target == TARGET_PUSH_AUTO) {
            pushAutoActive = false
            onPushAutoChanged?.invoke(false)
            return true
        }
        if (target == TARGET_PREVIEW_FOCUS) {
            if (!cancelled && !previewLongPressTriggered &&
                previewHitRect(layoutLandscape).contains(point.x, point.y)
            ) {
                showFocusReticle(point, locked = false)
                previewFocusCoordinates(point)?.let { (x, y) ->
                    onPreviewFocus?.invoke(x, y, false)
                }
            }
            return true
        }
        if (target == TARGET_WHEEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
            if (!cancelled) {
                val step = if (wheelDiscrete) majorProgressStep else max(1, majorProgressStep / 4)
                wheelProgress = ((wheelProgress.toFloat() / step).roundToInt() * step)
                    .coerceIn(0, MAX_PROGRESS)
                revealCenterValue()
                performWheelHapticIfNeeded()
                animateWheelTo(wheelProgress.toFloat())
                onWheelChanged?.invoke(wheelProgress)
            }
            onWheelReleased?.invoke()
            wheelDragging = false
            invalidate()
            return true
        }
        if (cancelled) return true
        if (target == TARGET_TOOL && controlsEnabled && selectedValueEnabled) {
            pulseHaptic()
            onToolPressed?.invoke()
            return true
        }
        when {
            target in TARGET_SELECTOR_BASE until TARGET_SELECTOR_BASE + 7 && controlsEnabled -> {
                val nextParameter = target - TARGET_SELECTOR_BASE
                if (nextParameter == selectedParameter) revealCurrentParameterName()
                pulseHaptic()
                onParameterSelected?.invoke(nextParameter)
            }
            target == TARGET_SELECTOR_BASE + 7 && controlsEnabled -> onRawPressed?.invoke()
            target == TARGET_AUTO && controlsEnabled && selectedValueEnabled &&
                !autoLongPressTriggered -> onAutoPressed?.invoke()
            target == TARGET_RECORD && recordEnabled && insideCircle(point, recordCenter(layoutLandscape), 115f) -> {
                onRecordPressed?.invoke()
            }
            target == TARGET_REVIEW && controlsEnabled &&
                galleryDisplayRect(layoutLandscape).contains(point.x, point.y) -> {
                onReviewPressed?.invoke()
            }
            target == TARGET_LOCK_BUTTON -> {
                setLockMenuOpen(!lockMenuOpen)
            }
            target == TARGET_SETTINGS && controlsEnabled -> onSettingsPressed?.invoke()
            target == TARGET_VISIBILITY -> toggleChromeTools()
            target == TARGET_ROTATION && rotationControlEnabled -> onRotationLockPressed?.invoke()
            target in TARGET_LOCK_ITEM_BASE..TARGET_LOCK_ITEM_BASE + 2 && controlsEnabled && !recording -> {
                val index = target - TARGET_LOCK_ITEM_BASE
                val next = !lockValue(index)
                when (index) {
                    0 -> exposureLocked = next
                    1 -> whiteBalanceLocked = next
                    else -> focusLocked = next
                }
                onLockChanged?.invoke(LockGroup.entries[index], next)
            }
        }
        return true
    }

    private val autoLongPressRunnable = Runnable {
        if (touchTarget != TARGET_AUTO || !controlsEnabled || !selectedValueEnabled) return@Runnable
        autoLongPressTriggered = true
        pulseHaptic()
        onAutoLongPressed?.invoke()
    }

    private val previewLongPressRunnable = Runnable {
        if (touchTarget != TARGET_PREVIEW_FOCUS || !controlsEnabled || recording || saving) return@Runnable
        previewLongPressTriggered = true
        showFocusReticle(previewTouchOrigin, locked = true)
        pulseHaptic()
        previewFocusCoordinates(previewTouchOrigin)?.let { (x, y) ->
            onPreviewFocus?.invoke(x, y, true)
        }
    }

    private fun showFocusReticle(point: PointF, locked: Boolean) {
        repeatHandler.removeCallbacks(hideFocusReticleRunnable)
        focusReticleVisibilityAnimator?.cancel()
        focusReticleAnimator?.cancel()
        focusReticle = FocusReticle(
            PointF(point.x, point.y),
            if (locked) FocusReticleState.LOCKED else FocusReticleState.SEARCHING
        )
        focusReticleVisibility = 1f
        focusReticleScale = if (locked) 0.98f else 1.12f
        focusReticleAnimator = animateFloat(
            focusReticleScale,
            1f,
            FOCUS_RETICLE_SETTLE_MS
        ) { focusReticleScale = it }
        if (!locked) pulseHaptic()
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun lockDPadAxis(action: DPadAction, point: PointF, rect: RectF) {
        dPadAxisLocked = true
        dPadLockedParameterAxis = action.parameterAxis
        dPadLastAxisCoordinate = dPadAxisCoordinate(point, action.parameterAxis)
        dPadLastMoveUptime = android.os.SystemClock.uptimeMillis()
        dPadDragAccumulator = 0f
        startRepeating(action, dPadIntensity(point, rect, action.parameterAxis), immediate = true)
    }

    private fun handleDPadDrag(point: PointF, rect: RectF) {
        val parameterAxis = dPadLockedParameterAxis ?: return
        val now = android.os.SystemClock.uptimeMillis()
        val coordinate = dPadAxisCoordinate(point, parameterAxis)
        val delta = coordinate - dPadLastAxisCoordinate
        val elapsed = (now - dPadLastMoveUptime).coerceAtLeast(1L)
        dPadLastAxisCoordinate = coordinate
        dPadLastMoveUptime = now

        if (abs(delta) > 0.25f) {
            val horizontal = dPadAxisIsHorizontal(parameterAxis)
            val screenDirection = when {
                horizontal && delta < 0f -> DPAD_LEFT
                horizontal -> DPAD_RIGHT
                delta < 0f -> DPAD_UP
                else -> DPAD_DOWN
            }
            val dragAction = dPadAction(screenDirection)
            val speed = abs(delta) / elapsed
            val gain = 1f + (speed / DPAD_DRAG_FULL_SPEED).coerceIn(0f, 1f) *
                DPAD_DRAG_MAX_EXTRA_GAIN
            dPadDragAccumulator += abs(delta) * gain * dragAction.logicalDirection
            val threshold = if (parameterAxis) {
                DPAD_PARAMETER_DRAG_STEP_DISTANCE
            } else {
                DPAD_VALUE_DRAG_STEP_DISTANCE
            }
            var steps = (abs(dPadDragAccumulator) / threshold).toInt()
                .coerceAtMost(if (parameterAxis) 1 else DPAD_VALUE_MAX_STEPS_PER_MOVE)
            if (steps > 0) {
                val logicalDirection = if (dPadDragAccumulator < 0f) -1 else 1
                while (steps-- > 0) {
                    if (!invokeDPadStep(logicalDirection, parameterAxis)) break
                    dPadDragAccumulator -= logicalDirection * threshold
                }
            }
        }

        val component = dPadAxisComponent(point, rect, parameterAxis)
        if (abs(component) < DPAD_CENTER_DEAD_ZONE) {
            stopRepeating()
            return
        }
        val horizontal = dPadAxisIsHorizontal(parameterAxis)
        val screenDirection = when {
            horizontal && component < 0f -> DPAD_LEFT
            horizontal -> DPAD_RIGHT
            component < 0f -> DPAD_UP
            else -> DPAD_DOWN
        }
        startRepeating(
            dPadAction(screenDirection),
            dPadIntensity(point, rect, parameterAxis),
            immediate = false
        )
    }

    private fun startRepeating(action: DPadAction, intensity: Float, immediate: Boolean) {
        if (!controlsEnabled || (!action.parameterAxis && !selectedValueEnabled)) return
        val directionChanged = repeatedPhysicalDirection != action.physicalDirection ||
            repeatedDirection == 0
        if (repeatedPhysicalDirection != DPAD_NONE &&
            repeatedPhysicalDirection != action.physicalDirection
        ) {
            animateDPadAccent(repeatedPhysicalDirection, 0f)
        }
        repeatedDirection = action.logicalDirection
        repeatedPhysicalDirection = action.physicalDirection
        repeatedParameterAxis = action.parameterAxis
        dPadIntensity = intensity.coerceIn(0f, 1f)
        animateDPadAccent(action.physicalDirection, 1f)
        if (directionChanged) {
            repeatedStartedAt = android.os.SystemClock.uptimeMillis()
            repeatHandler.removeCallbacks(repeatRunnable)
            if (immediate) invokeRepeatedAction()
            if (repeatedDirection != 0) {
                repeatHandler.postDelayed(
                    repeatRunnable,
                    if (action.parameterAxis) PARAMETER_HOLD_DELAY_MS else VALUE_HOLD_DELAY_MS
                )
            }
        }
        invalidate()
    }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (repeatedDirection == 0 || !controlsEnabled) return
            invokeRepeatedAction()
            if (repeatedDirection == 0) return
            val interval = dPadRepeatInterval()
            repeatHandler.postDelayed(this, interval)
        }
    }

    private fun dPadRepeatInterval(): Long {
        val heldFor = android.os.SystemClock.uptimeMillis() - repeatedStartedAt
        val base = if (repeatedParameterAxis) {
            lerp(PARAMETER_REPEAT_SLOW_MS, PARAMETER_REPEAT_FAST_MS, dPadIntensity)
        } else {
            lerp(VALUE_REPEAT_SLOW_MS, VALUE_REPEAT_FAST_MS, dPadIntensity)
        }
        val temporalFactor = when {
            heldFor >= HOLD_TURBO_2_MS -> 0.82f
            heldFor >= HOLD_TURBO_1_MS -> 0.90f
            else -> 1f
        }
        val minimum = if (repeatedParameterAxis) PARAMETER_REPEAT_MIN_MS else VALUE_REPEAT_MIN_MS
        return (base * temporalFactor).roundToInt().toLong().coerceAtLeast(minimum)
    }

    private fun stopRepeating() {
        val releasedDirection = repeatedPhysicalDirection
        repeatedDirection = 0
        repeatedPhysicalDirection = DPAD_NONE
        repeatedParameterAxis = false
        repeatHandler.removeCallbacks(repeatRunnable)
        if (releasedDirection != DPAD_NONE) animateDPadAccent(releasedDirection, 0f)
        invalidate()
    }

    private fun invokeRepeatedAction() {
        if (!invokeDPadStep(repeatedDirection, repeatedParameterAxis)) {
            blockedHaptic()
            stopRepeating()
        }
    }

    private fun invokeDPadStep(direction: Int, parameterAxis: Boolean): Boolean {
        if (!parameterAxis) {
            if (!selectedValueEnabled) return false
            val previousProgress = wheelProgress
            onStep?.invoke(direction)
            val changed = wheelProgress != previousProgress
            if (changed) {
                revealCenterValue()
                pulseHaptic()
            }
            return changed
        }
        val previousParameter = selectedParameter
        onParameterStep?.invoke(direction)
        val changed = selectedParameter != previousParameter
        if (changed) pulseHaptic()
        return changed
    }

    private fun performWheelHapticIfNeeded() {
        val value = valueFormatter(wheelProgress)
        if (value == lastWheelHapticValue) return
        lastWheelHapticValue = value
        pulseHaptic()
    }

    private fun blockedHaptic() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBlockedHapticUptime < BLOCKED_HAPTIC_COOLDOWN_MS) return
        lastBlockedHapticUptime = now
        if (performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        ) {
            return
        }
        val motor = vibrator ?: return
        if (!motor.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                motor.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0L, 24L, 32L, 34L),
                        intArrayOf(0, 78, 0, 142),
                        -1
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                motor.vibrate(55L)
            }
        }
    }

    private fun levelHaptic() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastLevelHapticUptime < LEVEL_HAPTIC_COOLDOWN_MS) return
        lastLevelHapticUptime = now
        if (performHapticFeedback(
                HapticFeedbackConstants.CLOCK_TICK,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        ) {
            return
        }
        val motor = vibrator ?: return
        if (!motor.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                motor.vibrate(VibrationEffect.createOneShot(LEVEL_HAPTIC_DURATION_MS, LEVEL_HAPTIC_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                motor.vibrate(LEVEL_HAPTIC_DURATION_MS)
            }
        }
    }

    private fun pulseHaptic() {
        if (performHapticFeedback(
                HapticFeedbackConstants.CLOCK_TICK,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        ) {
            return
        }
        val motor = vibrator ?: return
        if (!motor.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                motor.vibrate(VibrationEffect.createOneShot(HAPTIC_DURATION_MS, HAPTIC_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                motor.vibrate(HAPTIC_DURATION_MS)
            }
        }
    }

    private fun dPadAxisIsHorizontal(parameterAxis: Boolean): Boolean =
        if (layoutLandscape) parameterAxis else !parameterAxis

    private fun dPadAxisCoordinate(point: PointF, parameterAxis: Boolean): Float =
        if (dPadAxisIsHorizontal(parameterAxis)) point.x else point.y

    private fun dPadAxisComponent(point: PointF, rect: RectF, parameterAxis: Boolean): Float =
        if (dPadAxisIsHorizontal(parameterAxis)) point.x - rect.centerX() else point.y - rect.centerY()

    private fun dPadIntensity(point: PointF, rect: RectF, parameterAxis: Boolean): Float {
        val component = abs(dPadAxisComponent(point, rect, parameterAxis))
        val radius = if (dPadAxisIsHorizontal(parameterAxis)) rect.width() / 2f else rect.height() / 2f
        return ((component - DPAD_CENTER_DEAD_ZONE) /
            (radius - DPAD_CENTER_DEAD_ZONE).coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    private fun lerp(slow: Long, fast: Long, amount: Float): Float =
        slow + (fast - slow) * amount.coerceIn(0f, 1f)

    private fun dPadAction(point: PointF, rect: RectF): DPadAction? {
        if (!rect.contains(point.x, point.y)) return null
        val dx = point.x - rect.centerX()
        val dy = point.y - rect.centerY()
        if (abs(dx) > DPAD_ARM_HALF_WIDTH && abs(dy) > DPAD_ARM_HALF_WIDTH) return null
        if (hypot(dx, dy) < DPAD_CENTER_DEAD_ZONE) return null
        return dPadAction(directionForDelta(dx, dy))
    }

    private fun directionForDelta(dx: Float, dy: Float): Int {
        val horizontal = abs(dx) > abs(dy)
        return when {
            horizontal && dx < 0f -> DPAD_LEFT
            horizontal -> DPAD_RIGHT
            dy < 0f -> DPAD_UP
            else -> DPAD_DOWN
        }
    }

    private fun dPadAction(screenDirection: Int): DPadAction {
        val horizontal = screenDirection == DPAD_LEFT || screenDirection == DPAD_RIGHT
        val parameterAxis = if (layoutLandscape) horizontal else !horizontal
        val logicalDirection = when {
            !layoutLandscape && horizontal -> if (screenDirection == DPAD_LEFT) 1 else -1
            screenDirection == DPAD_LEFT || screenDirection == DPAD_UP -> -1
            else -> 1
        }
        val rotationSteps = ((contentRotation / 90f).roundToInt() % 4 + 4) % 4
        val accentDirection = ((screenDirection - 1 - rotationSteps + 4) % 4) + 1
        return DPadAction(accentDirection, logicalDirection, parameterAxis)
    }

    override fun onDetachedFromWindow() {
        stopRepeating()
        chromeAnimator?.cancel()
        rotationAnimator?.cancel()
        rotationAccentAnimator?.cancel()
        recordRingAnimator?.cancel()
        galleryRecordingAnimator?.cancel()
        wheelAnimator?.cancel()
        centerLabelAnimator?.cancel()
        centerLabelVisibilityAnimator?.cancel()
        repeatHandler.removeCallbacks(hideCenterLabelRunnable)
        repeatHandler.removeCallbacks(autoLongPressRunnable)
        statusPopupAnimator?.cancel()
        repeatHandler.removeCallbacks(hideStatusPopupRunnable)
        autoAccentAnimator?.cancel()
        pushAutoAccentAnimator?.cancel()
        lockAccentAnimator?.cancel()
        lockMenuAnimator?.cancel()
        recordPressAnimator?.cancel()
        lockGroupAnimators.forEach { it?.cancel() }
        selectorAnimators.forEach { it?.cancel() }
        dPadAnimators.forEach { it?.cancel() }
        if (pushAutoActive) {
            pushAutoActive = false
            onPushAutoChanged?.invoke(false)
        }
        super.onDetachedFromWindow()
    }

    private fun lockValue(index: Int): Boolean = when (index) {
        0 -> exposureLocked
        1 -> whiteBalanceLocked
        else -> focusLocked
    }

    private fun selectorRect(index: Int, landscape: Boolean): RectF {
        return if (landscape) {
            RectF(SELECTOR_START + CELL * index, 0f, SELECTOR_START + CELL * (index + 1), SELECTOR_THICKNESS)
        } else {
            RectF(PORTRAIT_SELECTOR_X, GUTTER + CELL * index, PORTRAIT_WIDTH, GUTTER + CELL * (index + 1))
        }
    }

    private fun autoRect(landscape: Boolean): RectF {
        return if (landscape) {
            RectF(WHEEL_START, LANDSCAPE_HEIGHT - SELECTOR_THICKNESS, WHEEL_END, LANDSCAPE_HEIGHT)
        } else {
            RectF(0f, WHEEL_START, SELECTOR_THICKNESS, WHEEL_END)
        }
    }

    private fun toolRect(landscape: Boolean): RectF {
        return if (landscape) {
            RectF(WHEEL_START, 0f, WHEEL_END, SELECTOR_THICKNESS)
        } else {
            RectF(PORTRAIT_WIDTH - SELECTOR_THICKNESS, WHEEL_START, PORTRAIT_WIDTH, WHEEL_END)
        }
    }

    private fun precisionRect(landscape: Boolean): RectF {
        val rect = if (landscape) {
            RectF(2188f, 71.5f, 2498f, 381.5f)
        } else {
            RectF(797.5f, 2188f, 1107.5f, 2498f)
        }
        return rect
    }

    private fun rulerTouchRect(landscape: Boolean): RectF {
        return if (landscape) {
            RectF(WHEEL_START, SELECTOR_THICKNESS, WHEEL_END, LANDSCAPE_HEIGHT - SELECTOR_THICKNESS)
        } else {
            RectF(SELECTOR_THICKNESS, WHEEL_START, PORTRAIT_WIDTH - SELECTOR_THICKNESS, WHEEL_END)
        }
    }

    private fun lockMenuRect(index: Int, landscape: Boolean): RectF {
        return if (landscape) {
            val bottom = LANDSCAPE_HEIGHT - CELL * index
            RectF(GUTTER, bottom - CELL, GUTTER + SELECTOR_THICKNESS, bottom)
        } else {
            RectF(CELL * index, GUTTER, CELL * (index + 1), GUTTER + SELECTOR_THICKNESS)
        }
    }

    private fun recordCenter(landscape: Boolean): PointF {
        val center = if (landscape) PointF(2343f, 589.5f) else PointF(589.5f, 2343f)
        return center
    }

    private fun recordingIndicatorCenter(landscape: Boolean): PointF {
        val center = if (landscape) PointF(2343f, 440f) else PointF(589.5f, 2183f)
        return center
    }

    private fun recordingTimerCenter(landscape: Boolean): PointF {
        if (landscape) return PointF(1065f, 1138f)
        return if (contentIsVertical()) {
            PointF(589.5f, 1810.5f)
        } else {
            // The whole canvas is mirrored for left-handed controls, so this
            // single canonical anchor naturally moves to the opposite safe edge.
            PointF(105f, 1065f)
        }
    }

    private fun pushAutoCenter(landscape: Boolean): PointF = when {
        landscape -> {
            val center = PointF(2343f, 982.5f)
            center
        }
        else -> {
            val center = PointF(196.5f, 2343f)
            center
        }
    }

    private fun galleryRect(landscape: Boolean): RectF = if (landscape) {
        val raw = selectorRect(7, landscape)
        val auto = autoRect(landscape)
        val width = 156f
        val height = 58f
        RectF(
            raw.centerX() - width / 2f,
            auto.centerY() - height / 2f,
            raw.centerX() + width / 2f,
            auto.centerY() + height / 2f
        )
    } else {
        RectF(20.25f, 1781.5f, 176.25f, 1839.5f)
    }

    private fun galleryDisplayRect(landscape: Boolean): RectF {
        val normal = galleryRect(landscape)
        return RectF(normal)
    }

    private fun previewTouchRect(landscape: Boolean): RectF =
        if (landscape) {
            RectF(279f, 0f, 1851f, LANDSCAPE_HEIGHT)
        } else {
            RectF(0f, 279f, PORTRAIT_WIDTH, 1851f)
        }

    private fun previewHitRect(landscape: Boolean): RectF {
        val normal = previewTouchRect(landscape)
        if (!leftHanded) return normal
        val width = canonicalWidth()
        return RectF(width - normal.right, normal.top, width - normal.left, normal.bottom)
    }

    private fun previewFocusCoordinates(point: PointF): Pair<Float, Float>? {
        val rect = previewTouchRect(layoutLandscape)
        val sceneX = if (leftHanded) canonicalWidth() - point.x else point.x
        if (!rect.contains(sceneX, point.y)) return null
        return ((sceneX - rect.left) / rect.width()).coerceIn(0f, 1f) to
            ((point.y - rect.top) / rect.height()).coerceIn(0f, 1f)
    }

    private fun contentIsVertical(): Boolean {
        val quarterTurns = ((contentRotation / 90f).roundToInt() % 4 + 4) % 4
        return quarterTurns % 2 == 0
    }

    private fun visibilityCenter(landscape: Boolean): PointF =
        if (landscape) PointF(106.5f, 295.5f) else PointF(1080.5f, 106.5f)

    private fun rotationCenter(landscape: Boolean): PointF =
        if (landscape) PointF(106.5f, 98.5f) else PointF(883.5f, 106.5f)

    private fun insideToolButton(point: PointF, lock: Boolean): Boolean {
        val center = if (layoutLandscape) {
            if (lock) PointF(106.5f, 1081f) else PointF(106.5f, 884f)
        } else {
            if (lock) PointF(98.5f, 106.5f) else PointF(295.5f, 106.5f)
        }
        return insideCircle(point, center, 48f)
    }

    private fun insideCircle(point: PointF, center: PointF, radius: Float): Boolean =
        hypot(point.x - center.x, point.y - center.y) <= radius

    private fun toCanonical(x: Float, y: Float): PointF {
        val canonicalX = (x - contentOffsetX) / contentScale
        return PointF(
            if (leftHanded) canonicalWidth() - canonicalX else canonicalX,
            (y - contentOffsetY) / contentScale
        )
    }

    private fun canonicalWidth(): Float =
        if (layoutLandscape) LANDSCAPE_WIDTH else PORTRAIT_WIDTH

    private fun normalizeDegrees(degrees: Float): Float {
        var value = degrees
        while (value > 180f) value -= 360f
        while (value < -180f) value += 360f
        return value
    }

    private fun drawCenteredText(canvas: Canvas, text: String, cx: Float, cy: Float, size: Float, color: Int) {
        paint.typeface = openSans
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = size
        paint.color = color
        canvas.drawText(text, cx, centeredBaseline(cy, size), paint)
    }

    private fun drawCenteredText(canvas: Canvas, text: String, cx: Float, cy: Float, size: Float, paint: Paint) {
        paint.typeface = openSans
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = size
        canvas.drawText(text, cx, centeredBaseline(cy, size), paint)
    }

    private inline fun withContentRotation(canvas: Canvas, cx: Float, cy: Float, draw: () -> Unit) {
        canvas.save()
        if (leftHanded) canvas.scale(-1f, 1f, cx, cy)
        canvas.rotate(contentRotation, cx, cy)
        draw()
        canvas.restore()
    }


    private fun centeredBaseline(centerY: Float, textSize: Float): Float {
        paint.textSize = textSize
        return centerY - (paint.ascent() + paint.descent()) / 2f
    }

    private fun fill(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.alpha = Color.alpha(color)
        canvas.drawRect(left, top, right, bottom, paint)
        paint.alpha = 255
    }

    private fun scaledAlpha(color: Int, multiplier: Float): Int =
        Color.argb(
            (Color.alpha(color) * multiplier.coerceIn(0f, 1f)).roundToInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )

    private fun blendColor(from: Int, to: Int, progress: Float): Int {
        val amount = progress.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * amount).roundToInt(),
            (Color.red(from) + (Color.red(to) - Color.red(from)) * amount).roundToInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * amount).roundToInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount).roundToInt()
        )
    }

    companion object {
        private const val PORTRAIT_WIDTH = 1179f
        private const val PORTRAIT_HEIGHT = 2556f
        private const val LANDSCAPE_WIDTH = 2556f
        private const val LANDSCAPE_HEIGHT = 1179f
        private const val GUTTER = 213f
        private const val CELL = 213f
        private const val SELECTOR_THICKNESS = 196.5f
        private const val HIDDEN_HUD_TEXT_OUTLINE_WIDTH = 3f
        private const val SELECTOR_START = 213f
        private const val SELECTOR_END = 1917f
        private const val PORTRAIT_SELECTOR_X = 982.5f
        private const val PORTRAIT_SELECTOR_END = 1917f
        private const val WHEEL_START = 1917f
        private const val WHEEL_END = 2130f
        private const val RULER_MAJOR_SPACING = 133.25f
        private const val RULER_HORIZONTAL_LABEL_INSET = 126f
        private const val SELECTED_LABEL_CLEARANCE = 108f
        private const val MAX_PROGRESS = 1000
        private const val PARAMETER_HOLD_DELAY_MS = 620L
        private const val PARAMETER_REPEAT_SLOW_MS = 620L
        private const val PARAMETER_REPEAT_FAST_MS = 420L
        private const val PARAMETER_REPEAT_MIN_MS = 380L
        private const val VALUE_HOLD_DELAY_MS = 320L
        private const val VALUE_REPEAT_SLOW_MS = 260L
        private const val VALUE_REPEAT_FAST_MS = 110L
        private const val VALUE_REPEAT_MIN_MS = 90L
        private const val HOLD_TURBO_1_MS = 1_100L
        private const val HOLD_TURBO_2_MS = 2_400L
        private const val RULER_FADE_FRACTION = 0.25f
        private const val RULER_MAX_ALPHA = 220f
        private const val CHROME_TRANSITION_MS = 240L
        private const val LOCK_MENU_TRANSITION_MS = 220L
        private const val CONTENT_ROTATION_MS = 280L
        private const val COLOR_TRANSITION_MS = 180L
        private const val DPAD_COLOR_TRANSITION_MS = 120L
        private const val CONTROL_PRESS_IN_MS = 90L
        private const val CONTROL_PRESS_OUT_MS = 160L
        private const val RULER_TRANSITION_MS = 220L
        private const val CENTER_LABEL_TRANSITION_MS = 220L
        private const val CENTER_LABEL_SHOW_MS = 140L
        private const val CENTER_LABEL_HOLD_MS = 850L
        private const val CENTER_LABEL_HIDE_MS = 260L
        private const val STATUS_POPUP_SHOW_MS = 160L
        private const val STATUS_POPUP_HOLD_MS = 900L
        private const val STATUS_POPUP_HIDE_MS = 240L
        private const val FOCUS_RETICLE_SETTLE_MS = 180L
        private const val FOCUS_RETICLE_CONFIRMED_HOLD_MS = 720L
        private const val FOCUS_RETICLE_HIDE_MS = 260L
        private const val RECORD_RING_HALF_CYCLE_MS = 720L
        private const val GALLERY_HIDE_MS = 240L
        private const val GALLERY_SHOW_MS = 280L
        private const val STABILIZER_ROLL_FOLLOW_ALPHA = 0.42f
        private const val STABILIZER_MAX_FRAME_STEP_DEGREES = 8f
        private const val STABILIZER_ACTIVE_ALPHA = 0.92f
        private const val STABILIZER_STABLE_ALPHA = 0.42f
        private const val CENTER_LABEL_TEXT_SIZE = 64f
        private const val CENTER_VALUE_NAME_TEXT_SIZE = 30f
        private const val HAPTIC_DURATION_MS = 14L
        private const val BLOCKED_HAPTIC_COOLDOWN_MS = 180L
        private const val LEVEL_HAPTIC_COOLDOWN_MS = 420L
        private const val LEVEL_HAPTIC_DURATION_MS = 18L
        private const val AUTO_LONG_PRESS_MS = 550L
        private const val PREVIEW_FOCUS_LONG_PRESS_MS = 520L
        private const val HAPTIC_AMPLITUDE = 110
        private const val LEVEL_HAPTIC_AMPLITUDE = 150
        private const val STABILIZER_MAX_ROLL_DEGREES = 180f
        private const val PARAMETER_LENS = 0
        private const val PARAMETER_RESOLUTION = 6
        private const val DPAD_ARM_HALF_WIDTH = 54f
        private const val DPAD_ARROW_OFFSET = 96f
        private const val DPAD_CENTER_DEAD_ZONE = 30f
        private const val DPAD_AXIS_LOCK_THRESHOLD = 18f
        private const val PREVIEW_FOCUS_TAP_SLOP = 22f
        private const val DPAD_PARAMETER_DRAG_STEP_DISTANCE = 70f
        private const val DPAD_VALUE_DRAG_STEP_DISTANCE = 26f
        private const val DPAD_DRAG_FULL_SPEED = 1.35f
        private const val DPAD_DRAG_MAX_EXTRA_GAIN = 0.65f
        private const val DPAD_VALUE_MAX_STEPS_PER_MOVE = 2
        private const val DPAD_NONE = 0
        private const val DPAD_UP = 1
        private const val DPAD_RIGHT = 2
        private const val DPAD_DOWN = 3
        private const val DPAD_LEFT = 4

        private const val TARGET_NONE = 0
        private const val TARGET_SELECTOR_BASE = 100
        private const val TARGET_AUTO = 200
        private const val TARGET_TOOL = 201
        private const val TARGET_RECORD = 202
        private const val TARGET_REVIEW = 203
        private const val TARGET_PRECISION = 204
        private const val TARGET_WHEEL = 205
        private const val TARGET_LOCK_BUTTON = 206
        private const val TARGET_SETTINGS = 207
        private const val TARGET_PUSH_AUTO = 208
        private const val TARGET_VISIBILITY = 209
        private const val TARGET_ROTATION = 210
        private const val TARGET_PREVIEW_FOCUS = 211
        private const val TARGET_LOCK_ITEM_BASE = 300

        private const val ACCENT = RawLiteDesign.ACCENT
        private const val AUTO = RawLiteDesign.AUTO
        private const val DIVIDER = RawLiteDesign.DIVIDER
        private const val CHROME_OVERLAY = RawLiteDesign.OVERLAY
        private val PARAMETER_NAMES = listOf(
            "LENTE",
            "FPS",
            "OBTURACIÓN",
            "ISO",
            "BALANCE DE BLANCOS",
            "ENFOQUE",
            "RESOLUCIÓN"
        )
    }
}
