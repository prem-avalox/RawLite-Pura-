package com.example.rawlite

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.example.rawlite.camera.CameraCatalog
import com.example.rawlite.camera.CameraOption
import com.example.rawlite.camera.Pure10SourceSelector
import com.example.rawlite.capture.CaptureCadencePolicy
import com.example.rawlite.capture.IsoStepPolicy
import com.example.rawlite.capture.audio.AvSyncContext
import com.example.rawlite.capture.audio.AvSyncMetadataFactory
import com.example.rawlite.capture.audio.AudioInputDevice
import com.example.rawlite.capture.audio.AudioInputMonitor
import com.example.rawlite.capture.audio.inputDescription
import com.example.rawlite.capture.metadata.CaptureMetadataContext
import com.example.rawlite.capture.metadata.CaptureMetadataFactory
import com.example.rawlite.core.config.Hlg10OutputProfile
import com.example.rawlite.core.config.Pixel6ReferenceProfile
import com.example.rawlite.core.model.CameraParameter
import com.example.rawlite.core.model.CaptureFormatPolicy
import com.example.rawlite.core.model.CaptureMode
import com.example.rawlite.core.model.CaptureOutput
import com.example.rawlite.core.state.CaptureOperationState
import com.example.rawlite.device.CaptureFeature
import com.example.rawlite.device.AvailableCameraControls
import com.example.rawlite.device.DeviceCapabilitiesGateway
import com.example.rawlite.gallery.Pixel6MainDngColorProfile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private class RotatingDialogFrame(context: Context) : FrameLayout(context) {
        var contentRotationDegrees: Float = 0f
            set(value) {
                if (abs(field - value) < 0.5f) return
                field = value
                requestLayout()
            }

        init {
            clipChildren = false
            clipToPadding = false
        }

        private fun isQuarterTurn(): Boolean {
            val turns = ((contentRotationDegrees / 90f).roundToInt() % 4 + 4) % 4
            return turns % 2 == 1
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (!isQuarterTurn() || childCount == 0) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            val child = getChildAt(0)
            val display = resources.displayMetrics
            val widthLimit = View.MeasureSpec.getSize(widthMeasureSpec)
                .takeIf { it > 0 } ?: display.widthPixels
            val heightLimit = View.MeasureSpec.getSize(heightMeasureSpec)
                .takeIf { it > 0 } ?: display.heightPixels
            val childWidth = (heightLimit * SETTINGS_LANDSCAPE_WIDTH_FRACTION)
                .roundToInt()
                .coerceAtLeast(1)
            child.measure(
                View.MeasureSpec.makeMeasureSpec(childWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(widthLimit, View.MeasureSpec.AT_MOST)
            )
            setMeasuredDimension(
                resolveSize(child.measuredHeight, widthMeasureSpec),
                resolveSize(child.measuredWidth, heightMeasureSpec)
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            if (!isQuarterTurn() || childCount == 0) {
                super.onLayout(changed, left, top, right, bottom)
                if (childCount > 0) getChildAt(0).rotation = contentRotationDegrees
                return
            }
            val child = getChildAt(0)
            val childLeft = (measuredWidth - child.measuredWidth) / 2
            val childTop = (measuredHeight - child.measuredHeight) / 2
            child.layout(
                childLeft,
                childTop,
                childLeft + child.measuredWidth,
                childTop + child.measuredHeight
            )
            child.pivotX = child.measuredWidth / 2f
            child.pivotY = child.measuredHeight / 2f
            child.rotation = contentRotationDegrees
        }

        companion object {
            private const val SETTINGS_LANDSCAPE_WIDTH_FRACTION = 0.84f
        }
    }

    private data class LockSnapshot(
        val iso: Int?,
        val exposureNs: Long?,
        val gains: RggbChannelVector?,
        val transform: ColorSpaceTransform?,
        val focusDiopters: Float?
    )

    private data class FrameMetadata(
        val timestampNs: Long,
        val iso: Int,
        val exposureNs: Long,
        val frameDurationNs: Long,
        val focusDiopters: Float,
        val wbRed: Float,
        val wbGreenEven: Float,
        val wbGreenOdd: Float,
        val wbBlue: Float,
        val lensShading: LensShadingSample?
    )

    private data class SessionRequest(
        val includeRaw: Boolean,
        val allowAnalysis: Boolean
    )

    private data class ControlWidgets(
        val value: TextView,
        val seek: SeekBar,
        val minus: Button,
        val plus: Button,
        val mode: Button?
    )

    private enum class CaptureUiState {
        READY,
        PREPARING,
        RECORDING,
        SAVING
    }

    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var cameraSpinner: Spinner
    private lateinit var resolutionSpinner: Spinner
    private lateinit var previewSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var apertureValue: TextView
    private lateinit var recordButton: TextView
    private lateinit var reviewButton: ImageButton
    private lateinit var formatHint: TextView
    private lateinit var lockSwitch: Switch
    private lateinit var controlsPanel: LinearLayout
    private lateinit var parametersPanel: LinearLayout
    private lateinit var panelToggle: TextView
    private lateinit var technicalButton: TextView
    private lateinit var microphoneButton: TextView
    private lateinit var audioMeter: TextView
    private lateinit var technicalOverlay: TechnicalOverlayView
    private lateinit var chromeLayer: FrameLayout
    private lateinit var cameraChrome: CameraChromeView
    private var settingsDialogFrame: RotatingDialogFrame? = null
    private lateinit var selectorStrip: LinearLayout
    private lateinit var wheelPanel: FrameLayout
    private lateinit var captureRail: FrameLayout
    private lateinit var wheelView: ParameterWheelView
    private lateinit var wheelAutoButton: TextView
    private lateinit var precisionButtons: LinearLayout
    private lateinit var decreaseButton: TextView
    private lateinit var increaseButton: TextView
    private lateinit var recordIndicator: View
    private lateinit var rawModeButton: TextView
    private val selectorButtons = linkedMapOf<CameraParameter, TextView>()
    private lateinit var exposureControls: ControlWidgets
    private lateinit var isoControls: ControlWidgets
    private lateinit var evControls: ControlWidgets
    private lateinit var gainControls: ControlWidgets
    private lateinit var wbControls: ControlWidgets
    private lateinit var focusControls: ControlWidgets

    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private lateinit var powerManager: PowerManager
    private val cameraExecutor by lazy { Executor { command -> cameraHandler.post(command) } }
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val telemetryExecutor = Executors.newSingleThreadExecutor()
    private val audioExecutor = Executors.newSingleThreadExecutor()
    private val postProcessExecutor = Executors.newSingleThreadExecutor()
    private val telemetryHandler = Handler(Looper.getMainLooper())
    private val chromeHandler = Handler(Looper.getMainLooper())
    private lateinit var contentOrientationListener: OrientationEventListener
    private lateinit var sensorManager: SensorManager
    private var stabilizerSensor: Sensor? = null
    private var baseContentOrientation: Int? = null
    private var rotationLockedByUser = false
    private var lastAllowedContentRotation = 0f
    private var appliedContentRotation = 0f
    private var detectedDeviceOrientationDegrees = 0
    private var recordingDisplayRotationDegrees = 0
    private var rotationPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private val stabilizerGravity = FloatArray(3)
    private var stabilizerGravityInitialized = false
    private val stabilizerListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!::cameraChrome.isInitialized) return
            val alpha = if (event.sensor.type == Sensor.TYPE_GRAVITY) 1f else 0.22f
            if (!stabilizerGravityInitialized || alpha >= 1f) {
                stabilizerGravity[0] = event.values[0]
                stabilizerGravity[1] = event.values[1]
                stabilizerGravity[2] = event.values[2]
                stabilizerGravityInitialized = true
            } else {
                stabilizerGravity[0] += (event.values[0] - stabilizerGravity[0]) * alpha
                stabilizerGravity[1] += (event.values[1] - stabilizerGravity[1]) * alpha
                stabilizerGravity[2] += (event.values[2] - stabilizerGravity[2]) * alpha
            }
            updateStabilizerGuide()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private var cameraOptions: List<CameraOption> = emptyList()
    private lateinit var deviceCapabilities: DeviceCapabilitiesGateway
    private var selectedCameraIndex = 0
    private var selectedRawIndex = 1
    private var selectedHlg10Index = 1
    private var selectedPreviewIndex = 1
    private var selectedFps = 15
    private var captureMode = CaptureMode.RAW
    private var activeCaptureMode = CaptureMode.RAW
    private var suppressSpinnerEvents = false
    private var parametersExpanded = false
    private var selectedParameter = CameraParameter.ISO
    private var lockExposure = true
    private var lockWhiteBalance = true
    private var lockFocus = true

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var closingSession: CameraCaptureSession? = null
    private var pendingSessionRequest: SessionRequest? = null
    private var pendingCameraTexture: SurfaceTexture? = null
    private var closeCameraAfterSession = false
    private var finalizeCaptureAfterSessionClose = false
    private var rawReader: ImageReader? = null
    private var analysisReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var sessionGeneration = 0

    private val resultsByTimestamp = ConcurrentHashMap<Long, CaptureResult>()
    private val pendingRawImages = LinkedHashMap<Long, android.media.Image>()
    private var latestResult: CaptureResult? = null
    private var lockedValues: LockSnapshot? = null
    private var previewTimingValues: LockSnapshot? = null
    private var previewMeteringFramesRemaining = 0
    private val pendingWrites = AtomicInteger(0)
    private val peakPendingWrites = AtomicInteger(0)
    private var sessionDir: File? = null
    private var nativeWriterHandle = 0L
    private var lensShadingWriter: LensShadingMetadataWriter? = null
    private var pure10Encoder: Pure10Encoder? = null
    private var pure10LensShading: LensShadingSample? = null
    private var telemetryStream: FileOutputStream? = null
    private var sessionStartElapsedMs = 0L
    private var packedRawRecording = false
    private var pure10Recording = false
    private var dngRecording = false
    private var lastTimelineFrame = -1
    private var cadenceGaps = 0
    private var captureIntervalCount = 0
    private var captureIntervalTotalNs = 0L
    private var captureIntervalMinNs = Long.MAX_VALUE
    private var captureIntervalMaxNs = 0L
    private var captureLongIntervals = 0
    private var previousCaptureTimestampNs = 0L
    private var sessionAutoCadenceLock = false
    private var sessionExposurePolicy = "auto"
    private var sessionRequestedFrameDurationNs = 0L
    private var droppedFrames = 0
    private var noMetadataDrops = 0
    private var writerBusyDrops = 0
    private var writeErrorDrops = 0
    private var captureFailureDrops = 0
    private var savedFrames = 0
    private var recordTimerText = "00:00"
    private var recording = false
    private var switchingSession = false
    private var captureStopPending = false
    private var recordingStartToken = 0
    private var stopMeasureId = 0
    private var stopMeasureStartMs = 0L
    private var stopMeasureMode = ""
    private var stopMeasureDir = ""
    private var stopMeasureReadyLoggedFor = 0
    @Volatile
    private var finalizingOutput = false
    @Volatile
    private var finalizingAudio = false
    private val pure10MuxQueuedDirectories = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var firstSensorTimestampNs = 0L
    @Volatile
    private var lastSensorTimestampNs = 0L
    @Volatile
    private var audioRecorder: PcmAudioRecorder? = null
    private lateinit var audioInputMonitor: AudioInputMonitor
    private var availableAudioInput: AudioInputDevice? = null
    private var activeAudioInput: AudioInputDevice? = null
    @Volatile private var audioCaptureToken: Any? = null
    private var audioCaptureError = false
    private var audioInputStatusText: TextView? = null
    private var audioEnabled = true
    private var audioPeak = 0f
    private var analysisFrameCounter = 0
    private var technicalMode = TechnicalOverlayMode.OFF
    private var activeOverlay = ActiveOverlay.NONE
    private var activeOverlayAlwaysOn = false
    private var activeOverlayMomentary = false
    private var preFocusRestoreOverlay: ActiveOverlay? = null
    private var toolAlwaysOn = false
    private var toolMomentaryActive = false
    private var thirdsGridEnabled = false
    private var histogramEnabled = false
    private var leftHandedMode = false
    private var lastStatusUpdateMs = 0L
    private var telemetryMonitoring = false
    private var thermalStatus = PowerManager.THERMAL_STATUS_NONE
    private var thermalHeadroom: Float? = null
    private var batteryTemperatureC: Float? = null
    private var storageAvailableBytes = 0L

    private var manualExposure = false
    private var manualWb = false
    private var manualFocus = false
    private var pushAutoRunning = false
    private var pushAutoPreviousExposure = false
    private var pushAutoPreviousWb = false
    private var pushAutoPreviousFocus = false
    private var pushAutoCapturesExposure = false
    private var pushAutoCapturesWb = false
    private var pushAutoCapturesFocus = false
    private var manualWbGains: RggbChannelVector? = null
    private var manualWbTransform: ColorSpaceTransform? = null
    private var previewMeteringRegion: MeteringRectangle? = null
    private var previewMeteringLocked = false
    private var previewFocusConfirmationPending = false
    private var isoValue = 100
    private var exposureNs = 16_666_667L
    private var evSteps = 0
    private var postRawGain = 100
    private var kelvin = 5000
    private var focusDiopters = 0f

    private val chromeRefresh = object : Runnable {
        override fun run() {
            if (::cameraChrome.isInitialized) updateChromeValues()
            chromeHandler.postDelayed(this, CHROME_REFRESH_MS)
        }
    }
    private val savePreferencesRunnable = Runnable { saveUserPreferencesNow() }
    private val clearTemporaryPreviewFocusRunnable = Runnable {
        if (!previewMeteringLocked && previewMeteringRegion != null) {
            clearPreviewFocusMetering(updateCamera = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val windowAttributes = window.attributes
        windowAttributes.rotationAnimation =
            WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS
        window.attributes = windowAttributes
        loadUserPreferences()
        buildUi()
        audioInputMonitor = AudioInputMonitor(this) { device ->
            val previous = availableAudioInput
            availableAudioInput = device?.inputDescription()
            if (previous != availableAudioInput && !recording) {
                if (availableAudioInput?.isExternal == true) {
                    cameraChrome.showStatusPopup("MIC EXTERNO")
                } else if (previous?.isExternal == true) {
                    cameraChrome.showStatusPopup("MIC DESCONECTADO")
                }
            }
            updateMicrophoneUi()
        }
        baseContentOrientation = currentDisplayRotationDegrees()
        contentOrientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN || !::cameraChrome.isInitialized) return
                val compensation = orientationCompensationWithHysteresis(orientation) ?: return
                detectedDeviceOrientationDegrees = compensation
            }
        }
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stabilizerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        requestRequiredPermissions()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        audioEnabled = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED &&
            getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_AUDIO_ENABLED, true)
        scheduleUserPreferencesSave()
        updateMicrophoneUi()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (!::cameraThread.isInitialized) startCameraSystem()
        } else {
            setStatusText("Se necesita permiso de cámara")
        }
    }

    override fun onDestroy() {
        if (::audioInputMonitor.isInitialized) audioInputMonitor.stop()
        removeRotationRenderBarrier()
        chromeHandler.removeCallbacks(chromeRefresh)
        chromeHandler.removeCallbacks(savePreferencesRunnable)
        saveUserPreferencesNow()
        if (::contentOrientationListener.isInitialized) contentOrientationListener.disable()
        recording = false
        closeCamera()
        finishPackedOutputs()
        finishAudioCapture()
        finishTelemetry()
        stopTelemetryMonitor()
        if (::cameraThread.isInitialized) cameraThread.quitSafely()
        ioExecutor.shutdown()
        telemetryExecutor.shutdown()
        audioExecutor.shutdown()
        postProcessExecutor.shutdown()
        super.onDestroy()
    }

    override fun onStop() {
        if (::audioInputMonitor.isInitialized) audioInputMonitor.stop()
        removeRotationRenderBarrier()
        chromeHandler.removeCallbacks(chromeRefresh)
        if (::contentOrientationListener.isInitialized) contentOrientationListener.disable()
        unregisterStabilizerSensor()
        stopTelemetryMonitor()
        if (!isChangingConfigurations) {
            closeCameraForBackground(
                finalizeCapture = recording || switchingSession ||
                    nativeWriterHandle != 0L || telemetryStream != null || audioRecorder != null
            )
        }
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (::audioInputMonitor.isInitialized) audioInputMonitor.start()
        if (::contentOrientationListener.isInitialized && contentOrientationListener.canDetectOrientation()) {
            contentOrientationListener.enable()
        }
        registerStabilizerSensor()
        chromeHandler.removeCallbacks(chromeRefresh)
        chromeHandler.post(chromeRefresh)
        startTelemetryMonitor()
        if (::cameraThread.isInitialized &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            preview.isAvailable &&
            cameraDevice == null
        ) {
            openSelectedCamera(preview.surfaceTexture!!)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A display quarter-turn updates the window dimensions before every child has
        // completed its new layout. Without a barrier Android can submit that single,
        // internally inconsistent frame: the preview, selector strip and chrome briefly
        // occupy positions from different orientations. Keep the last complete frame on
        // screen until both the root and the preview have their final geometry.
        installRotationRenderBarrier(newConfig.orientation)
        val previousDisplayRotation = baseContentOrientation ?: currentDisplayRotationDegrees()
        val newDisplayRotation = currentDisplayRotationDegrees()
        baseContentOrientation = newDisplayRotation
        val displayDelta = shortestRotationDelta(
            previousDisplayRotation.toFloat(),
            newDisplayRotation.toFloat()
        )
        // Rebase each label against the display quarter-turn, then let the existing
        // Bézier animation bring it upright. The window can rotate seamlessly while
        // controls retain the progressive motion used by PURA.
        lastAllowedContentRotation = 0f
        appliedContentRotation = 0f
        cameraChrome.rebaseContentRotation(-displayDelta)
        cameraChrome.animateContentRotation(0f)
        settingsDialogFrame?.contentRotationDegrees = 0f
        chromeLayer.post { updatePanelLayout(newConfig.orientation) }
        updateStabilizerGuide()
    }

    fun toggleRecording() {
        if (recordButton.isEnabled) {
            if (recording || switchingSession) stopRecording() else startRecording()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.repeatCount == 0) {
                toggleRecording()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun installRotationRenderBarrier(expectedOrientation: Int) {
        if (!::chromeLayer.isInitialized || !::preview.isInitialized) return
        removeRotationRenderBarrier()

        var layoutUpdateRequested = false
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val expectedLandscape = expectedOrientation == Configuration.ORIENTATION_LANDSCAPE
                val rootReady = chromeLayer.width > 0 && chromeLayer.height > 0 &&
                    (chromeLayer.width > chromeLayer.height) == expectedLandscape
                val previewReady = preview.width > 0 && preview.height > 0 &&
                    (preview.width > preview.height) == expectedLandscape

                if (!rootReady || !previewReady) {
                    if (!layoutUpdateRequested) {
                        layoutUpdateRequested = true
                        chromeLayer.post {
                            updatePanelLayout(expectedOrientation)
                            chromeLayer.requestLayout()
                        }
                    }
                    return false
                }

                // Apply the TextureView matrix in the same traversal that releases the
                // complete layout. No stale preview transform can become visible between
                // the window rotation and PURA's final chrome geometry.
                configurePreviewTransform(preview.width, preview.height)
                removeRotationRenderBarrier()
                return true
            }
        }
        rotationPreDrawListener = listener
        chromeLayer.viewTreeObserver.addOnPreDrawListener(listener)
        chromeLayer.requestLayout()
    }

    private fun removeRotationRenderBarrier() {
        val listener = rotationPreDrawListener ?: return
        if (::chromeLayer.isInitialized) {
            val observer = chromeLayer.viewTreeObserver
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
        }
        rotationPreDrawListener = null
    }

    private fun shortestRotationDelta(fromDegrees: Float, toDegrees: Float): Float {
        var delta = toDegrees - fromDegrees
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }

    private fun requestRequiredPermissions() {
        val missing = buildList {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.CAMERA)
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
        }
        if (missing.isEmpty()) {
            audioEnabled = audioEnabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            updateMicrophoneUi()
            startCameraSystem()
        } else {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun loadUserPreferences() {
        val prefs = getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
        thirdsGridEnabled = prefs.getBoolean(PREF_THIRDS_GRID, false)
        histogramEnabled = prefs.getBoolean(PREF_HISTOGRAM_ENABLED, false)
        val savedAlwaysOn = prefs.getBoolean(PREF_ACTIVE_OVERLAY_ALWAYS_ON, false)
        val savedOverlay = runCatching {
            ActiveOverlay.valueOf(
                prefs.getString(PREF_ACTIVE_OVERLAY, ActiveOverlay.NONE.name) ?: ActiveOverlay.NONE.name
            )
        }.getOrDefault(ActiveOverlay.NONE)
        if (savedAlwaysOn && savedOverlay != ActiveOverlay.NONE) {
            activeOverlay = savedOverlay
            activeOverlayAlwaysOn = true
            activeOverlayMomentary = false
            toolAlwaysOn = true
            toolMomentaryActive = false
        } else {
            activeOverlay = ActiveOverlay.NONE
            activeOverlayAlwaysOn = false
            activeOverlayMomentary = false
            toolAlwaysOn = false
            toolMomentaryActive = false
        }
        leftHandedMode = prefs.getBoolean(PREF_LEFT_HANDED, false)
        selectedCameraIndex = prefs.getInt(PREF_CAMERA_INDEX, selectedCameraIndex).coerceAtLeast(0)
        selectedRawIndex = prefs.getInt(PREF_RAW_INDEX, selectedRawIndex).coerceAtLeast(0)
        selectedHlg10Index = prefs.getInt(PREF_HLG10_INDEX, selectedHlg10Index).coerceAtLeast(0)
        selectedPreviewIndex = prefs.getInt(PREF_PREVIEW_INDEX, selectedPreviewIndex)
            .coerceIn(PREVIEW_SIZES.indices)
        selectedFps = prefs.getInt(PREF_FPS, selectedFps)
            .takeIf { FPS_OPTIONS.contains(it) } ?: selectedFps
        captureMode = runCatching {
            CaptureMode.valueOf(prefs.getString(PREF_CAPTURE_MODE, captureMode.name) ?: captureMode.name)
        }.getOrDefault(captureMode)
        selectedParameter = runCatching {
            CameraParameter.valueOf(
                prefs.getString(PREF_SELECTED_PARAMETER, selectedParameter.name)
                    ?: selectedParameter.name
            )
        }.getOrDefault(selectedParameter)
        lockExposure = prefs.getBoolean(PREF_LOCK_EXPOSURE, lockExposure)
        lockWhiteBalance = prefs.getBoolean(PREF_LOCK_WB, lockWhiteBalance)
        lockFocus = prefs.getBoolean(PREF_LOCK_FOCUS, lockFocus)
        manualExposure = prefs.getBoolean(PREF_MANUAL_EXPOSURE, manualExposure)
        manualWb = prefs.getBoolean(PREF_MANUAL_WB, manualWb)
        manualFocus = prefs.getBoolean(PREF_MANUAL_FOCUS, manualFocus)
        isoValue = prefs.getInt(PREF_ISO, isoValue)
        exposureNs = prefs.getLong(PREF_EXPOSURE_NS, exposureNs)
        evSteps = prefs.getInt(PREF_EV_STEPS, evSteps)
        postRawGain = prefs.getInt(PREF_POST_RAW_GAIN, postRawGain)
        kelvin = prefs.getInt(PREF_KELVIN, kelvin)
        focusDiopters = prefs.getFloat(PREF_FOCUS_DIOPTERS, focusDiopters)
        audioEnabled = prefs.getBoolean(PREF_AUDIO_ENABLED, audioEnabled)
    }

    private fun scheduleUserPreferencesSave() {
        chromeHandler.removeCallbacks(savePreferencesRunnable)
        chromeHandler.postDelayed(savePreferencesRunnable, PREF_SAVE_DELAY_MS)
    }

    private fun saveUserPreferencesNow() {
        getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_THIRDS_GRID, thirdsGridEnabled)
            .putBoolean(PREF_HISTOGRAM_ENABLED, histogramEnabled)
            .putBoolean(PREF_ACTIVE_OVERLAY_ALWAYS_ON, activeOverlayAlwaysOn)
            .putString(PREF_ACTIVE_OVERLAY, if (activeOverlayAlwaysOn) activeOverlay.name else ActiveOverlay.NONE.name)
            .putBoolean(PREF_LEFT_HANDED, leftHandedMode)
            .putInt(PREF_CAMERA_INDEX, selectedCameraIndex)
            .putInt(PREF_RAW_INDEX, selectedRawIndex)
            .putInt(PREF_HLG10_INDEX, selectedHlg10Index)
            .putInt(PREF_PREVIEW_INDEX, selectedPreviewIndex)
            .putInt(PREF_FPS, selectedFps)
            .putString(PREF_CAPTURE_MODE, captureMode.name)
            .putString(PREF_SELECTED_PARAMETER, selectedParameter.name)
            .putBoolean(PREF_LOCK_EXPOSURE, lockExposure)
            .putBoolean(PREF_LOCK_WB, lockWhiteBalance)
            .putBoolean(PREF_LOCK_FOCUS, lockFocus)
            .putBoolean(PREF_MANUAL_EXPOSURE, manualExposure)
            .putBoolean(PREF_MANUAL_WB, manualWb)
            .putBoolean(PREF_MANUAL_FOCUS, manualFocus)
            .putInt(PREF_ISO, isoValue)
            .putLong(PREF_EXPOSURE_NS, exposureNs)
            .putInt(PREF_EV_STEPS, evSteps)
            .putInt(PREF_POST_RAW_GAIN, postRawGain)
            .putInt(PREF_KELVIN, kelvin)
            .putFloat(PREF_FOCUS_DIOPTERS, focusDiopters)
            .putBoolean(PREF_AUDIO_ENABLED, audioEnabled)
            .apply()
    }

    private fun buildUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = TextureView(this)
        root.addView(preview, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        technicalMode = TechnicalOverlayMode.OFF
        technicalOverlay = TechnicalOverlayView(this).apply {
            mode = TechnicalOverlayMode.OFF
            thirdsGridEnabled = this@MainActivity.thirdsGridEnabled
            histogramEnabled = this@MainActivity.histogramEnabled
            isClickable = false
        }
        root.addView(technicalOverlay, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xB8000000.toInt())
            textSize = 11f
            typeface = cameraTypeface(Typeface.NORMAL)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            text = "Iniciando cámara..."
            visibility = View.GONE
        }
        root.addView(status, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.START or Gravity.TOP })

        chromeLayer = FrameLayout(this).apply {
            addOnLayoutChangeListener { _, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom ->
                val sizeChanged = right - left != oldRight - oldLeft ||
                    bottom - top != oldBottom - oldTop
                if (sizeChanged && right > left && bottom > top) {
                    post { updatePanelLayout(resources.configuration.orientation) }
                }
            }
        }
        root.addView(chromeLayer, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        selectorStrip = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(0x6F2A2A2F) }
        }
        CameraParameter.entries.forEach { parameter ->
            val button = createSelectorButton(parameter)
            selectorButtons[parameter] = button
            selectorStrip.addView(button)
        }
        rawModeButton = selectorText("RAW").apply {
            setTextColor(ACCENT)
            contentDescription = "Cambiar modo de captura"
            setOnClickListener { toggleCaptureMode() }
        }
        selectorStrip.addView(rawModeButton)
        chromeLayer.addView(selectorStrip)

        wheelPanel = FrameLayout(this).apply {
            setBackgroundColor(0x69000000)
        }
        wheelView = ParameterWheelView(this).apply {
            onProgressChanged = { progress -> applyWheelProgress(progress) }
            onTouchStart = { handleManualAdjustmentStarted() }
            onTouchRelease = { handleManualAdjustmentReleased() }
        }
        wheelPanel.addView(wheelView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        wheelAutoButton = selectorText("AUTO").apply {
            textSize = 13f
            background = null
            contentDescription = "Alternar ajuste automático"
            setOnClickListener { toggleSelectedParameterAuto() }
            setOnLongClickListener {
                setAllAdjustableParametersAuto()
                true
            }
        }
        wheelPanel.addView(wheelAutoButton)
        chromeLayer.addView(wheelPanel)

        captureRail = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        recordIndicator = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(RawLiteDesign.RECORD)
            }
            visibility = View.GONE
        }
        captureRail.addView(recordIndicator)

        recordButton = TextView(this).apply {
            gravity = Gravity.CENTER
            isEnabled = false
            isClickable = false
            contentDescription = "Grabar"
            background = recordButtonBackground(active = false)
        }
        captureRail.addView(recordButton)

        reviewButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_gallery)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF252525.toInt())
                setStroke(dp(1), 0xFFBEBEBE.toInt())
            }
            contentDescription = "Revisar tomas"
            setOnClickListener {
                if (!recording && !switchingSession && !finalizingOutput &&
                    !finalizingAudio
                ) {
                    startActivity(Intent(this@MainActivity, ReviewActivity::class.java))
                }
            }
        }
        captureRail.addView(reviewButton)

        precisionButtons = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), 0xFFBEBEBE.toInt())
                cornerRadius = dp(24).toFloat()
            }
        }
        decreaseButton = precisionButton("−") { stepSelectedParameter(-1) }
        increaseButton = precisionButton("+") { stepSelectedParameter(1) }
        precisionButtons.addView(decreaseButton)
        precisionButtons.addView(increaseButton)
        captureRail.addView(precisionButtons)

        audioMeter = TextView(this).apply {
            text = "----"
            setTextColor(MUTED)
            textSize = 9f
            typeface = cameraTypeface(Typeface.BOLD)
            gravity = Gravity.CENTER
            contentDescription = "Nivel de audio"
        }
        captureRail.addView(audioMeter)
        microphoneButton = TextView(this).apply {
            text = "MIC"
            setTextColor(Color.WHITE)
            textSize = 10f
            typeface = cameraTypeface(Typeface.BOLD)
            gravity = Gravity.CENTER
            contentDescription = "Activar o desactivar micrófono"
            setOnClickListener { toggleMicrophone() }
        }
        captureRail.addView(microphoneButton)
        chromeLayer.addView(captureRail)

        selectorStrip.visibility = View.GONE
        wheelPanel.visibility = View.GONE
        captureRail.visibility = View.GONE

        cameraChrome = CameraChromeView(this).apply {
            leftHanded = leftHandedMode
            onParameterSelected = { index ->
                CameraParameter.entries.getOrNull(index)?.let(::selectParameter)
            }
            onRawPressed = {
                toggleCaptureMode()
            }
            onAutoPressed = { toggleSelectedParameterAuto() }
            onAutoLongPressed = { setAllAdjustableParametersAuto() }
            onPreviewFocus = { x, y, locked ->
                applyPreviewFocusMetering(x, y, locked)
            }
            onPushAutoChanged = { pressed ->
                if (pressed) beginPushAuto() else endPushAuto()
            }
            onToolPressed = {
                val tool = overlayForParameter(this@MainActivity.selectedParameter)
                if (tool != null) {
                    if (activeOverlay == tool && activeOverlayAlwaysOn) {
                        setActiveOverlay(ActiveOverlay.NONE, alwaysOn = false, momentary = false)
                    } else {
                        setActiveOverlay(tool, alwaysOn = true, momentary = false)
                    }
                    scheduleUserPreferencesSave()
                }
            }
            onParameterLongPressed = { index ->
                CameraParameter.entries.getOrNull(index)?.let { param ->
                    if (this@MainActivity.selectedParameter != param) {
                        selectParameter(param)
                    }
                    val tool = overlayForParameter(param)
                    if (tool != null) {
                        if (activeOverlay == tool && activeOverlayAlwaysOn) {
                            setActiveOverlay(ActiveOverlay.NONE, alwaysOn = false, momentary = false)
                        } else {
                            setActiveOverlay(tool, alwaysOn = true, momentary = false)
                        }
                        scheduleUserPreferencesSave()
                    }
                }
            }
            onWheelStarted = { handleManualAdjustmentStarted() }
            onWheelChanged = { progress -> applyWheelProgress(progress) }
            onWheelReleased = { handleManualAdjustmentReleased() }
            onPrecisionStarted = { handleManualAdjustmentStarted() }
            onPrecisionReleased = { handleManualAdjustmentReleased() }
            onStep = { direction -> stepSelectedParameter(direction) }
            onParameterStep = { direction -> stepParameterSelection(direction) }
            onRecordPressed = {
                toggleRecording()
            }
            onReviewPressed = { openReview() }
            onSettingsPressed = { showSettingsDialog() }
            onRotationLockPressed = { toggleRotationLock() }
            onLockChanged = { group, enabled ->
                when (group) {
                    CameraChromeView.LockGroup.EXPOSURE -> lockExposure = enabled
                    CameraChromeView.LockGroup.WHITE_BALANCE -> lockWhiteBalance = enabled
                    CameraChromeView.LockGroup.FOCUS -> lockFocus = enabled
                }
                lockSwitch.isChecked = lockExposure || lockWhiteBalance || lockFocus
                scheduleUserPreferencesSave()
                updateChromeValues()
            }
        }
        chromeLayer.addView(
            cameraChrome,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        controlsPanel = LinearLayout(this).apply { visibility = View.GONE }
        cameraSpinner = darkSpinner()
        resolutionSpinner = darkSpinner()
        previewSpinner = darkSpinner().apply {
            adapter = spinnerAdapter(PREVIEW_SIZES.map { "${it.width}×${it.height}" })
            setSelection(selectedPreviewIndex)
        }
        fpsSpinner = darkSpinner().apply {
            adapter = spinnerAdapter(FPS_OPTIONS.map(Int::toString))
            setSelection(FPS_OPTIONS.indexOf(selectedFps))
        }
        panelToggle = TextView(this)
        technicalButton = TextView(this).apply { text = TechnicalOverlayMode.OFF.label }
        parametersPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        apertureValue = valueText("—")
        lockSwitch = Switch(this).apply { isChecked = true }
        exposureControls = addControlRow(parametersPanel, "OBTUR.", "1/60", "AUTO")
        isoControls = addControlRow(parametersPanel, "ISO", "100", null)
        evControls = addControlRow(parametersPanel, "EV", "0.0", null)
        gainControls = addControlRow(parametersPanel, "GANANCIA", "1.00×", null)
        wbControls = addControlRow(parametersPanel, "TEMP.", "5000 K", "AUTO")
        focusControls = addControlRow(parametersPanel, "FOCO", "AF", "AUTO")
        formatHint = TextView(this).apply { text = "RAW10 secuencial" }
        controlsPanel.addView(cameraSpinner)
        controlsPanel.addView(resolutionSpinner)
        controlsPanel.addView(previewSpinner)
        controlsPanel.addView(fpsSpinner)
        controlsPanel.addView(parametersPanel)
        root.addView(controlsPanel, FrameLayout.LayoutParams(1, 1))

        setContentView(root)
        wireUiEvents()
        selectParameter(selectedParameter)
        updateMicrophoneUi()
        root.post { updatePanelLayout(resources.configuration.orientation) }
    }

    private fun openReview() {
        if (recording || switchingSession || finalizingOutput ||
            finalizingAudio
        ) return
        startActivity(Intent(this, ReviewActivity::class.java))
    }

    private fun showSettingsDialog() {
        if (recording || switchingSession || finalizingOutput ||
            finalizingAudio || pure10MuxInProgress()
        ) return
        cameraChrome.closeMenus()
        val padding = dp(22)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(18), padding, dp(18))
            setBackgroundColor(RawLiteDesign.SURFACE)
        }
        panel.addView(TextView(this).apply {
            text = "CONFIGURACIÓN"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = cameraTypeface(Typeface.NORMAL)
            letterSpacing = 0.075f
        })
        panel.addView(TextView(this).apply {
            text = "RAWLITE · ${Build.MODEL.uppercase(Locale.US)}"
            setTextColor(RawLiteDesign.TEXT_MUTED)
            textSize = 9f
            typeface = cameraTypeface(Typeface.NORMAL)
            letterSpacing = 0.08f
            setPadding(0, dp(3), 0, dp(18))
        })
        panel.addView(settingsSectionHeading("CAPTURA", "PREVISUALIZACIÓN Y SONIDO"))
        panel.addView(TextView(this).apply {
            text = "PREVIEW"
            setTextColor(RawLiteDesign.TEXT_MUTED)
            textSize = 9f
            typeface = cameraTypeface(Typeface.NORMAL)
            letterSpacing = 0.08f
        })
        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(10))
        }
        PREVIEW_SIZES.forEachIndexed { index, size ->
            previewRow.addView(Button(this).apply {
                text = "${size.width}×${size.height}"
                isAllCaps = false
                textSize = 13f
                typeface = cameraTypeface(Typeface.NORMAL)
                setTextColor(if (index == selectedPreviewIndex) ACCENT else Color.WHITE)
                background = settingsChoiceBackground(index == selectedPreviewIndex)
                setOnClickListener {
                    if (index != selectedPreviewIndex) {
                        selectedPreviewIndex = index
                        scheduleUserPreferencesSave()
                        suppressSpinnerEvents = true
                        previewSpinner.setSelection(index)
                        suppressSpinnerEvents = false
                        reopenSelectedCamera()
                    }
                    (parent as? ViewGroup)?.let { row ->
                        for (childIndex in 0 until row.childCount) {
                            (row.getChildAt(childIndex) as? Button)?.apply {
                                val active = childIndex == selectedPreviewIndex
                                setTextColor(if (active) ACCENT else Color.WHITE)
                                background = settingsChoiceBackground(active)
                            }
                        }
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
        panel.addView(previewRow)
        panel.addView(settingsSwitch("AUDIO", "Grabar y sincronizar audio con cada toma", audioEnabled) { control, checked ->
                if (checked && checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_PERMISSIONS)
                    control.isChecked = false
                } else {
                    audioEnabled = checked
                    scheduleUserPreferencesSave()
                    updateMicrophoneUi()
                }
            })
        audioInputStatusText = TextView(this).apply {
            textSize = 12f
            setTextColor(MUTED)
            setPadding(dp(4), dp(8), dp(4), dp(12))
        }
        panel.addView(audioInputStatusText)
        updateMicrophoneUi()
        panel.addView(settingsDivider())
        panel.addView(settingsSectionHeading("INTERFAZ", "GEOMETRÍA Y GUÍAS"))
        panel.addView(settingsSwitch("CUADRÍCULA DE TERCIOS", "Guía blanca sobre la previsualización", thirdsGridEnabled) { _, checked ->
                thirdsGridEnabled = checked
                technicalOverlay.thirdsGridEnabled = checked
                scheduleUserPreferencesSave()
            })
        panel.addView(settingsDivider())
        panel.addView(settingsSwitch("HISTOGRAMA GLOBAL", "Instrumentación técnica en esquina superior", histogramEnabled) { _, checked ->
                histogramEnabled = checked
                technicalOverlay.histogramEnabled = checked
                scheduleUserPreferencesSave()
            })
        panel.addView(settingsDivider())
        panel.addView(settingsSwitch("CONTROLES PARA ZURDOS", "Invierte la geometría de operación con una mano", leftHandedMode) { _, checked ->
                leftHandedMode = checked
                cameraChrome.leftHanded = checked
                scheduleUserPreferencesSave()
            })
        lateinit var dialog: AlertDialog
        panel.addView(settingsDivider())
        panel.addView(settingsSectionHeading("ALMACENAMIENTO", "ESTADO ACTUAL"))
        val movieDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val availableBytes = runCatching { StatFs(movieDir.absolutePath).availableBytes }.getOrDefault(0L)
        panel.addView(settingsInformationRow("UBICACIÓN", "DCIM / RawLite"))
        panel.addView(settingsDivider())
        panel.addView(settingsInformationRow("DISPONIBLE", formatStorageBytes(availableBytes)))
        panel.addView(settingsDivider())
        panel.addView(TextView(this).apply {
            text = "CERRAR"
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(ACCENT)
            textSize = 14f
            typeface = cameraTypeface(Typeface.NORMAL)
            setPadding(dp(12), dp(14), dp(4), dp(4))
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        val rotatingFrame = RotatingDialogFrame(this).apply {
            contentRotationDegrees = appliedContentRotation
            addView(
                ScrollView(this@MainActivity).apply {
                    isFillViewport = true
                    overScrollMode = View.OVER_SCROLL_NEVER
                    addView(
                        panel,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        settingsDialogFrame = rotatingFrame
        dialog = AlertDialog.Builder(this)
            .setView(rotatingFrame)
            .create()
        dialog.setOnDismissListener {
            if (settingsDialogFrame === rotatingFrame) {
                settingsDialogFrame = null
                audioInputStatusText = null
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun createSelectorButton(parameter: CameraParameter): TextView {
        return selectorText("").apply {
            contentDescription = parameter.name.lowercase(Locale.US)
            setOnClickListener { selectParameter(parameter) }
        }
    }

    private fun selectorText(initialText: String): TextView {
        return TextView(this).apply {
            text = initialText
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = cameraTypeface(Typeface.BOLD)
            gravity = Gravity.CENTER
            isSingleLine = true
            setPadding(dp(5), 0, dp(5), 0)
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), 0x3AFFFFFF)
            }
        }
    }

    private fun precisionButton(label: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 21f
            typeface = cameraTypeface(Typeface.BOLD)
            gravity = Gravity.CENTER
            setOnClickListener { action() }
        }
    }

    private fun cameraTypeface(style: Int): Typeface {
        return runCatching { Typeface.create(resources.getFont(R.font.open_sans), style) }
            .getOrElse { Typeface.create("sans-serif", style) }
    }

    private fun recordButtonBackground(active: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = if (active) GradientDrawable.RECTANGLE else GradientDrawable.OVAL
            setColor(if (active) RECORD else 0xFFF6F6F6.toInt())
            setStroke(dp(2), if (active) RECORD else 0xFFD7D7D7.toInt())
            cornerRadius = if (active) dp(7).toFloat() else 0f
        }
    }

    private fun selectParameter(parameter: CameraParameter) {
        if (preFocusRestoreOverlay != null) {
            val restore = preFocusRestoreOverlay!!
            preFocusRestoreOverlay = null
            setActiveOverlay(restore, alwaysOn = true, momentary = false)
        }
        selectedParameter = parameter

        if (::cameraChrome.isInitialized) cameraChrome.selectedParameter = parameter.ordinal
        selectorButtons.forEach { (candidate, button) ->
            button.setTextColor(parameterTextColor(candidate))
        }
        scheduleUserPreferencesSave()
        syncWheelToSelectedParameter()
        updateChromeValues()
        updateTechnicalOverlayState()
    }

    private fun parameterTextColor(parameter: CameraParameter): Int = when {
        parameter == selectedParameter && !parameterIsAuto(parameter) -> ACCENT
        parameterIsAuto(parameter) -> RawLiteDesign.AUTO
        else -> Color.WHITE
    }

    private fun parameterIsAuto(parameter: CameraParameter): Boolean = when (parameter) {
        CameraParameter.SHUTTER, CameraParameter.ISO -> !manualExposure
        CameraParameter.WHITE_BALANCE -> !manualWb
        CameraParameter.FOCUS -> !manualFocus
        else -> false
    }

    private fun syncWheelToSelectedParameter() {
        if (!::wheelView.isInitialized) return
        wheelView.valueFormatter = { progress -> formatWheelValue(selectedParameter, progress) }
        val fpsOptions = currentFpsOptions()
        val majorStep = when (selectedParameter) {
            CameraParameter.LENS -> discreteStep(cameraOptions.size)
            CameraParameter.FPS -> discreteStep(fpsOptions.size)
            CameraParameter.SHUTTER -> discreteStep(shutterValuesNs().size)
            CameraParameter.ISO -> isoProgressStep()
            CameraParameter.RESOLUTION -> discreteStep(currentResolutionCount())
            else -> 100
        }
        wheelView.valueInterval = majorStep
        val progress = progressForParameter(selectedParameter)
        wheelView.setProgressSilently(progress)
        if (::cameraChrome.isInitialized) {
            cameraChrome.configureWheel(
                progress = progress,
                majorStep = majorStep,
                discrete = selectedParameter == CameraParameter.LENS ||
                    selectedParameter == CameraParameter.FPS ||
                    selectedParameter == CameraParameter.SHUTTER ||
                    selectedParameter == CameraParameter.RESOLUTION,
                liveDiscrete = selectedParameter == CameraParameter.SHUTTER,
                formatter = { value -> formatWheelValue(selectedParameter, value) }
            )
        }
        updateWheelAutoButton()
    }

    private fun progressForParameter(parameter: CameraParameter): Int {
        return when (parameter) {
            CameraParameter.LENS -> discreteProgress(selectedCameraIndex, cameraOptions.size)
            CameraParameter.FPS -> currentFpsOptions().let { options ->
                discreteProgress(options.indexOf(selectedFps).coerceAtLeast(0), options.size)
            }
            CameraParameter.SHUTTER -> shutterProgressFor(
                if (manualExposure) exposureNs else {
                    latestResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: exposureNs
                }
            )
            CameraParameter.ISO -> if (manualExposure) {
                isoProgressFor(isoValue)
            } else {
                isoProgressFor(latestResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: isoValue)
            }
            CameraParameter.WHITE_BALANCE -> wbControls.seek.progress
            CameraParameter.FOCUS -> focusControls.seek.progress
            CameraParameter.RESOLUTION -> discreteProgress(
                currentResolutionIndex(),
                currentResolutionCount()
            )
        }
    }

    private fun formatWheelValue(parameter: CameraParameter, progress: Int): String {
        return when (parameter) {
            CameraParameter.LENS -> cameraOptions.getOrNull(
                discreteIndex(progress, cameraOptions.size)
            )?.let(::lensDisplayLabel) ?: "24"
            CameraParameter.FPS -> currentFpsOptions().let { options ->
                options.getOrNull(discreteIndex(progress, options.size))
                    ?.toString() ?: selectedFps.toString()
            }
            CameraParameter.SHUTTER -> {
                formatExposure(shutterValueForProgress(progress))
            }
            CameraParameter.ISO -> {
                isoValueForProgress(progress).toString()
            }
            CameraParameter.WHITE_BALANCE -> {
                val raw = progressToLinear(progress, MIN_KELVIN.toDouble(), MAX_KELVIN.toDouble())
                "${((raw / 50.0).roundToInt() * 50).coerceIn(MIN_KELVIN, MAX_KELVIN)}"
            }
            CameraParameter.FOCUS -> {
                val maxFocus = maximumFocusDistanceDiopters()
                formatFocus(progressToFocusDiopters(progress, maxFocus))
            }
            CameraParameter.RESOLUTION -> {
                val count = currentResolutionCount()
                resolutionLabelAt(discreteIndex(progress, count)) ?: "—"
            }
        }
    }

    private fun applyWheelProgress(progress: Int) {
        if (switchingSession || finalizingOutput || finalizingAudio || pure10MuxInProgress() ||
            !selectedParameterCanChangeNow()
        ) return
        wheelView.setProgressSilently(progress)
        when (selectedParameter) {
            CameraParameter.LENS -> {
                val index = discreteIndex(progress, cameraOptions.size)
                if (index != selectedCameraIndex) {
                    selectedCameraIndex = index
                    selectedRawIndex = 1
                    scheduleUserPreferencesSave()
                    cameraSpinner.setSelection(index)
                    configureUiForCamera()
                    reopenSelectedCamera()
                }
            }
            CameraParameter.FPS -> {
                val options = currentFpsOptions()
                val index = discreteIndex(progress, options.size)
                val fps = options.getOrNull(index) ?: return
                if (fps != selectedFps) {
                    selectedFps = fps
                    scheduleUserPreferencesSave()
                    constrainExposureToVideoRange()
                    fpsSpinner.setSelection(index)
                    beginPreviewCadenceMetering()
                    updateFormatHint()
                    updateControlModes()
                    restartRepeating()
                }
            }
            CameraParameter.SHUTTER -> {
                manualExposure = true
                scheduleUserPreferencesSave()
                val snappedProgress = shutterProgressFor(shutterValueForProgress(progress))
                wheelView.setProgressSilently(snappedProgress)
                exposureControls.seek.progress = snappedProgress
                updateExposureFromProgress(snappedProgress)
                scheduleControlApply()
            }
            CameraParameter.ISO -> {
                manualExposure = true
                scheduleUserPreferencesSave()
                val snappedProgress = isoProgressFor(isoValueForProgress(progress))
                wheelView.setProgressSilently(snappedProgress)
                isoControls.seek.progress = snappedProgress
                updateIsoFromProgress(snappedProgress)
                scheduleControlApply()
            }
            CameraParameter.WHITE_BALANCE -> {
                manualWb = true
                manualWbGains = null
                manualWbTransform = null
                scheduleUserPreferencesSave()
                wbControls.seek.progress = progress
                updateKelvinFromProgress(progress)
                scheduleControlApply()
            }
            CameraParameter.FOCUS -> {
                val maxFocus = maximumFocusDistanceDiopters()
                if (maxFocus > 0f) {
                    manualFocus = true
                    scheduleUserPreferencesSave()
                    focusControls.seek.progress = progress
                    updateFocusFromProgress(progress)
                    scheduleControlApply()
                }
            }
            CameraParameter.RESOLUTION -> {
                val count = currentResolutionCount()
                val index = discreteIndex(progress, count)
                if (index != currentResolutionIndex()) {
                    setCurrentResolutionIndex(index)
                    constrainFpsToCurrentResolution()
                    scheduleUserPreferencesSave()
                    resolutionSpinner.setSelection(index)
                    updateFormatHint()
                    updateChromeValues()
                    showCurrentResolutionStatus()
                    reopenSelectedCamera()
                }
            }
        }
        updateControlModes()
        updateChromeValues()
    }

    private fun stepSelectedParameter(direction: Int) {
        if (!selectedParameterCanChangeNow()) return
        if (selectedParameter == CameraParameter.SHUTTER) {
            val values = shutterValuesNs()
            val currentExposure = if (manualExposure) exposureNs else {
                latestResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: exposureNs
            }
            val current = nearestShutterIndex(currentExposure, values)
            val next = (current + direction).coerceIn(values.indices)
            if (next != current) applyWheelProgress(discreteProgress(next, values.size))
            return
        }
        val step = when (selectedParameter) {
            CameraParameter.LENS -> discreteStep(cameraOptions.size)
            CameraParameter.FPS -> discreteStep(currentFpsOptions().size)
            CameraParameter.RESOLUTION -> discreteStep(currentResolutionCount())
            CameraParameter.WHITE_BALANCE -> 6
            CameraParameter.ISO -> isoProgressStep()
            CameraParameter.SHUTTER, CameraParameter.FOCUS -> 4
        }
        wheelView.step(direction * step)
    }

    private fun stepParameterSelection(direction: Int) {
        if (switchingSession || finalizingOutput || finalizingAudio || pure10MuxInProgress()) return
        val nextIndex = (selectedParameter.ordinal + direction).coerceIn(
            CameraParameter.LENS.ordinal,
            CameraParameter.RESOLUTION.ordinal
        )
        if (nextIndex != selectedParameter.ordinal) {
            selectParameter(CameraParameter.entries[nextIndex])
        }
    }

    private fun toggleSelectedParameterAuto() {
        if (switchingSession || finalizingOutput || finalizingAudio || pure10MuxInProgress()) return
        if (!recording && previewMeteringRegion != null) {
            clearPreviewFocusMetering(updateCamera = true)
            if (::cameraChrome.isInitialized) cameraChrome.showStatusPopup("AF AUTO")
            return
        }
        if (!selectedParameterCanChangeNow()) return
        when (selectedParameter) {
            CameraParameter.SHUTTER, CameraParameter.ISO -> manualExposure = !manualExposure
            CameraParameter.WHITE_BALANCE -> {
                manualWb = !manualWb
                manualWbGains = null
                manualWbTransform = null
            }
            CameraParameter.FOCUS -> {
                val maxFocus = maximumFocusDistanceDiopters()
                if (maxFocus > 0f) manualFocus = !manualFocus
            }
            else -> return
        }
        scheduleUserPreferencesSave()
        beginPreviewCadenceMetering()
        updateControlModes()
        restartRepeating()
    }

    private fun setAllAdjustableParametersAuto() {
        if (switchingSession || finalizingOutput || finalizingAudio || pure10MuxInProgress()) return
        var changed = false
        if (!recording || !lockExposure) {
            changed = changed || manualExposure
            manualExposure = false
        }
        if (!recording || !lockWhiteBalance) {
            changed = changed || manualWb || manualWbGains != null || manualWbTransform != null
            manualWb = false
            manualWbGains = null
            manualWbTransform = null
        }
        val canFocus = currentAvailableControls().manualFocus
        if (canFocus && (!recording || !lockFocus)) {
            changed = changed || manualFocus
            manualFocus = false
        }
        changed = changed || previewMeteringRegion != null || previewMeteringLocked
        clearPreviewFocusMetering(updateCamera = false)
        if (!changed) return
        beginPreviewCadenceMetering()
        updateControlModes()
        syncWheelToSelectedParameter()
        scheduleUserPreferencesSave()
        restartRepeating()
        if (::cameraChrome.isInitialized) cameraChrome.showStatusPopup("AUTO")
    }

    private fun selectedParameterCanChangeNow(): Boolean {
        if (switchingSession || finalizingOutput || finalizingAudio || pure10MuxInProgress()) return false
        val controls = currentAvailableControls()
        val supported = when (selectedParameter) {
            CameraParameter.SHUTTER, CameraParameter.ISO -> controls.manualExposure
            CameraParameter.WHITE_BALANCE -> controls.manualWhiteBalance
            CameraParameter.FOCUS -> controls.manualFocus
            CameraParameter.LENS -> cameraOptions.size > 1
            CameraParameter.FPS -> true
            CameraParameter.RESOLUTION -> currentResolutionCount() > 1
        }
        if (!supported) return false
        if (!recording) return true
        return when (selectedParameter) {
            CameraParameter.SHUTTER, CameraParameter.ISO -> !lockExposure
            CameraParameter.WHITE_BALANCE -> !lockWhiteBalance
            CameraParameter.FOCUS -> !lockFocus
            CameraParameter.LENS, CameraParameter.FPS, CameraParameter.RESOLUTION -> false
        }
    }

    private fun applyPreviewFocusMetering(x: Float, y: Float, locked: Boolean) {
        if (recording || switchingSession || finalizingOutput || finalizingAudio ||
            pure10MuxInProgress()
        ) return
        val characteristics = currentCharacteristics() ?: return
        val controls = currentAvailableControls()
        if (!controls.tapToFocus || !controls.manualFocus) {
            cameraChrome.showStatusPopup("SIN AF")
            return
        }
        val region = previewMeteringRectangle(x, y, characteristics) ?: return
        previewMeteringRegion = region
        previewMeteringLocked = locked
        previewFocusConfirmationPending = true
        chromeHandler.removeCallbacks(clearTemporaryPreviewFocusRunnable)
        if (!locked) {
            chromeHandler.postDelayed(
                clearTemporaryPreviewFocusRunnable,
                PREVIEW_FOCUS_TEMPORARY_HOLD_MS
            )
        }
        manualFocus = false
        updateControlModes()
        updateChromeValues()
        cameraChrome.showStatusPopup(if (locked) "AF FIJO" else "AF")
        cameraHandler.post { submitPreviewFocusRegion() }
    }

    private fun clearPreviewFocusMetering(updateCamera: Boolean = true) {
        chromeHandler.removeCallbacks(clearTemporaryPreviewFocusRunnable)
        previewMeteringRegion = null
        previewMeteringLocked = false
        previewFocusConfirmationPending = false
        if (::cameraChrome.isInitialized) cameraChrome.clearFocusReticle()
        if (updateCamera) restartRepeating()
    }

    private fun previewMeteringRectangle(
        x: Float,
        y: Float,
        characteristics: CameraCharacteristics
    ): MeteringRectangle? {
        val crop = previewCropRegion()
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: return null
        val centerX = crop.left + crop.width() * x.coerceIn(0f, 1f)
        val centerY = crop.top + crop.height() * y.coerceIn(0f, 1f)
        val side = (min(crop.width(), crop.height()) * PREVIEW_METERING_REGION_FRACTION)
            .roundToInt()
            .coerceAtLeast(PREVIEW_METERING_REGION_MIN_PX)
        val half = side / 2
        val left = (centerX.roundToInt() - half).coerceIn(crop.left, crop.right - side)
        val top = (centerY.roundToInt() - half).coerceIn(crop.top, crop.bottom - side)
        val rect = Rect(left, top, left + side, top + side)
        return MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)
    }

    private fun submitPreviewFocusRegion() {
        if (Looper.myLooper() != cameraHandler.looper) {
            cameraHandler.post { submitPreviewFocusRegion() }
            return
        }
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val previewTarget = previewSurface ?: return
        val builder = try {
            camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewTarget)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not create focus request", error)
            return
        }
        applyControls(builder, includeRaw = false)
        put(builder, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        try {
            session.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        storeCaptureResult(result)
                        cameraHandler.post {
                            if (!recording && captureSession === session) startRepeating(includeRaw = false)
                        }
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        cameraHandler.post {
                            if (!recording && captureSession === session) startRepeating(includeRaw = false)
                        }
                    }
                },
                cameraHandler
            )
        } catch (error: Exception) {
            Log.w(TAG, "Could not submit focus request", error)
            if (!recording && captureSession === session) startRepeating(includeRaw = false)
        }
    }

    private fun pushAutoCanRunNow(): Boolean {
        if (pushAutoRunning || switchingSession || finalizingOutput ||
            finalizingAudio || pure10MuxInProgress()
        ) return false
        if (!recording) return true
        val canFocus = currentAvailableControls().manualFocus
        return !lockExposure || !lockWhiteBalance || (!lockFocus && canFocus)
    }

    private fun beginPushAuto() {
        if (pushAutoRunning || switchingSession || finalizingOutput ||
            finalizingAudio || pure10MuxInProgress()
        ) return
        pushAutoCapturesExposure = !recording || !lockExposure
        pushAutoCapturesWb = !recording || !lockWhiteBalance
        pushAutoCapturesFocus = (!recording || !lockFocus) &&
            currentAvailableControls().manualFocus
        if (!pushAutoCapturesExposure && !pushAutoCapturesWb && !pushAutoCapturesFocus) return
        pushAutoRunning = true
        pushAutoPreviousExposure = manualExposure
        pushAutoPreviousWb = manualWb
        pushAutoPreviousFocus = manualFocus
        if (pushAutoCapturesExposure) manualExposure = false
        if (pushAutoCapturesWb) manualWb = false
        if (pushAutoCapturesFocus) manualFocus = false
        previewTimingValues = null
        cameraChrome.pushAutoActive = true
        updateControlModes()
        restartRepeating()
    }

    private fun endPushAuto() {
        if (!pushAutoRunning) return
        val measured = snapshotCurrentValues()
        if (pushAutoCapturesExposure) {
            measured.iso?.let {
                isoValue = IsoStepPolicy.nearest(it, isoRange().lower, isoRange().upper)
            }
            measured.exposureNs?.let { exposureNs = nearestShutterValue(it) }
            isoControls.seek.progress = isoProgressFor(isoValue)
            exposureControls.seek.progress = shutterProgressFor(exposureNs)
            manualExposure = true
        } else {
            manualExposure = pushAutoPreviousExposure
        }
        if (pushAutoCapturesWb) {
            manualWbGains = measured.gains
            manualWbTransform = measured.transform
            measured.gains?.let { kelvin = estimateKelvin(it) }
            wbControls.seek.progress = linearToProgress(
                kelvin.toDouble(), MIN_KELVIN.toDouble(), MAX_KELVIN.toDouble()
            )
            manualWb = true
        } else {
            manualWb = pushAutoPreviousWb
        }
        if (pushAutoCapturesFocus) {
            measured.focusDiopters?.let { focusDiopters = it }
            val maxFocus = maximumFocusDistanceDiopters()
            if (maxFocus > 0f) {
                focusControls.seek.progress = focusDioptersToProgress(focusDiopters, maxFocus)
            }
            manualFocus = true
        } else {
            manualFocus = pushAutoPreviousFocus
        }
        pushAutoRunning = false
        pushAutoCapturesExposure = false
        pushAutoCapturesWb = false
        pushAutoCapturesFocus = false
        cameraChrome.pushAutoActive = false
        scheduleUserPreferencesSave()
        updateAllControlValues()
        syncWheelToSelectedParameter()
        updateControlModes()
        restartRepeating()
    }

    private fun updateWheelAutoButton() {
        if (!::wheelAutoButton.isInitialized) return
        val supportsAuto = when (selectedParameter) {
            CameraParameter.SHUTTER, CameraParameter.ISO -> currentAvailableControls().manualExposure
            CameraParameter.WHITE_BALANCE -> currentAvailableControls().manualWhiteBalance
            CameraParameter.FOCUS -> currentAvailableControls().manualFocus
            else -> false
        }
        val automatic = when (selectedParameter) {
            CameraParameter.SHUTTER, CameraParameter.ISO -> !manualExposure
            CameraParameter.WHITE_BALANCE -> !manualWb
            CameraParameter.FOCUS -> !manualFocus
            else -> false
        }
        wheelAutoButton.visibility = if (supportsAuto) View.VISIBLE else View.INVISIBLE
        wheelAutoButton.setTextColor(if (automatic) RawLiteDesign.AUTO else Color.WHITE)
        wheelAutoButton.text = "AUTO"
        if (::cameraChrome.isInitialized) {
            cameraChrome.autoSupported = supportsAuto
            cameraChrome.autoActive = automatic
        }
    }

    private fun toggleCaptureMode() {
        if (recording || switchingSession || finalizingOutput ||
            finalizingAudio || pure10MuxInProgress()
        ) return
        val next = if (captureMode == CaptureMode.RAW) CaptureMode.PURE10 else CaptureMode.RAW
        if (next == CaptureMode.PURE10 && !currentPure10Availability()) {
            cameraChrome.showStatusPopup("HLG 10 NO DISPONIBLE")
            return
        }
        captureMode = next
        refreshModeDependentSelectors()
        updateFormatHint()
        updateChromeValues()
        syncWheelToSelectedParameter()
        scheduleUserPreferencesSave()
        val label = if (captureMode == CaptureMode.PURE10) {
            "HLG 10 · HEVC MAIN10"
        } else {
            "RAW"
        }
        cameraChrome.showStatusPopup(label)
        rawModeButton.announceForAccessibility(label)
        reconfigurePreviewForCaptureMode()
    }

    private fun reconfigurePreviewForCaptureMode() {
        if (cameraDevice == null || captureSession == null || switchingSession || recording) return
        createSession(includeRaw = false)
    }

    private fun updateChromeValues() {
        if (!::selectorStrip.isInitialized) return
        val displayedExposure = if (manualExposure) exposureNs else {
            latestResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: exposureNs
        }
        val displayedIso = if (manualExposure) isoValue else {
            latestResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: isoValue
        }
        val displayedFocus = if (manualFocus) focusDiopters else {
            latestResult?.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: focusDiopters
        }
        selectorButtons[CameraParameter.LENS]?.text = currentOption()?.let(::lensDisplayLabel) ?: "24mm"
        selectorButtons[CameraParameter.FPS]?.text = "${selectedFps}FPS"
        selectorButtons[CameraParameter.SHUTTER]?.text = formatExposure(displayedExposure)
        selectorButtons[CameraParameter.ISO]?.text = "${displayedIso}ISO"
        selectorButtons[CameraParameter.WHITE_BALANCE]?.text = "${kelvin}K"
        selectorButtons[CameraParameter.FOCUS]?.text = formatFocus(displayedFocus)
        selectorButtons[CameraParameter.RESOLUTION]?.text = currentResolutionLabel()
        selectorButtons.forEach { (parameter, button) ->
            button.setTextColor(parameterTextColor(parameter))
        }
        val modeLabel = if (captureMode == CaptureMode.RAW) "RAW" else "HLG 10"
        rawModeButton.text = modeLabel
        rawModeButton.setTextColor(ACCENT)
        if (::cameraChrome.isInitialized) {
            cameraChrome.selectorValues = listOf(
                currentOption()?.let(::lensDisplayLabel) ?: "24mm",
                "${selectedFps}FPS",
                formatExposure(displayedExposure),
                "${displayedIso}ISO",
                "${kelvin}K",
                formatFocus(displayedFocus),
                currentResolutionLabel()
            )
            cameraChrome.autoParameterStates = CameraParameter.entries.map(::parameterIsAuto)
            cameraChrome.selectedParameter = selectedParameter.ordinal
            cameraChrome.rawActive = true
            cameraChrome.captureModeLabel = modeLabel
            cameraChrome.exposureLocked = lockExposure
            cameraChrome.whiteBalanceLocked = lockWhiteBalance
            cameraChrome.focusLocked = lockFocus
            cameraChrome.selectedValueEnabled = selectedParameterCanChangeNow()
            cameraChrome.pushAutoEnabled = pushAutoCanRunNow()
            val majorStep = when (selectedParameter) {
                CameraParameter.LENS -> discreteStep(cameraOptions.size)
                CameraParameter.FPS -> discreteStep(currentFpsOptions().size)
                CameraParameter.SHUTTER -> discreteStep(shutterValuesNs().size)
                CameraParameter.ISO -> isoProgressStep()
                CameraParameter.RESOLUTION -> discreteStep(currentResolutionCount())
                else -> 100
            }
            cameraChrome.configureWheel(
                progressForParameter(selectedParameter),
                majorStep,
                selectedParameter == CameraParameter.LENS ||
                    selectedParameter == CameraParameter.FPS ||
                    selectedParameter == CameraParameter.SHUTTER ||
                    selectedParameter == CameraParameter.RESOLUTION,
                selectedParameter == CameraParameter.SHUTTER
            ) { progress -> formatWheelValue(selectedParameter, progress) }
        }
        updateWheelAutoButton()
        if (::wheelView.isInitialized) {
            val followsAutomaticValue = when (selectedParameter) {
                CameraParameter.SHUTTER, CameraParameter.ISO -> !manualExposure
                CameraParameter.FOCUS -> !manualFocus
                else -> false
            }
            if (followsAutomaticValue) {
                wheelView.setProgressSilently(progressForParameter(selectedParameter))
            } else {
                wheelView.invalidate()
            }
        }
    }

    private fun lensDisplayLabel(option: CameraOption): String {
        val facing = option.characteristics.get(CameraCharacteristics.LENS_FACING)
        val focal = option.characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull() ?: 0f
        return when {
            facing == CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
            focal >= 5f -> "24mm"
            focal > 0f -> "16mm"
            else -> "LENS"
        }
    }

    private fun resolutionDisplayLabel(size: Size): String {
        return "${size.width}×${size.height}"
    }

    private fun discreteIndex(progress: Int, count: Int): Int {
        if (count <= 1) return 0
        return ((progress.coerceIn(0, 1000) / 1000f) * (count - 1)).roundToInt()
            .coerceIn(0, count - 1)
    }

    private fun discreteProgress(index: Int, count: Int): Int {
        if (count <= 1) return 0
        return ((index.coerceIn(0, count - 1) * 1000f) / (count - 1)).roundToInt()
    }

    private fun discreteStep(count: Int): Int {
        return if (count <= 1) 1000 else (1000f / (count - 1)).roundToInt()
    }

    private var lastControlApplyTimeMs = 0L
    private val controlApplyThrottleIntervalMs = 33L
    private var trailingControlApplyScheduled = false
    private var activeRepeatingBuilder: CaptureRequest.Builder? = null

    private val controlApplyRunnable = Runnable {
        trailingControlApplyScheduled = false
        lastControlApplyTimeMs = SystemClock.elapsedRealtime()
        applyCurrentControlsToCamera()
    }

    private fun scheduleControlApply(forceImmediate: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastControlApplyTimeMs

        if (forceImmediate || elapsed >= controlApplyThrottleIntervalMs) {
            telemetryHandler.removeCallbacks(controlApplyRunnable)
            trailingControlApplyScheduled = false
            lastControlApplyTimeMs = now
            applyCurrentControlsToCamera()
        } else if (!trailingControlApplyScheduled) {
            trailingControlApplyScheduled = true
            val delay = (controlApplyThrottleIntervalMs - elapsed).coerceAtLeast(1L)
            telemetryHandler.postDelayed(controlApplyRunnable, delay)
        }
    }

    private fun applyCurrentControlsToCamera() {
        if (switchingSession || captureSession == null) return
        if (selectedParameter == CameraParameter.FOCUS && canFastUpdateFocus()) {
            fastUpdateFocus()
        } else {
            restartRepeating()
        }
    }

    private fun canFastUpdateFocus(): Boolean {
        return activeRepeatingBuilder != null && captureSession != null && !switchingSession
    }

    private fun fastUpdateFocus() {
        val session = captureSession ?: return
        val builder = activeRepeatingBuilder ?: return
        val maxFocus = maximumFocusDistanceDiopters()
        val lockedFocus = recording && lockFocus && snapshotCurrentValues()?.focusDiopters != null
        when {
            maxFocus <= 0f -> {
                put(builder, CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                put(builder, CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            }
            manualFocus || lockedFocus -> {
                put(builder, CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                val target = (if (lockedFocus) snapshotCurrentValues()?.focusDiopters else focusDiopters)
                    ?.coerceIn(0f, maxFocus) ?: focusDiopters.coerceIn(0f, maxFocus)
                put(builder, CaptureRequest.LENS_FOCUS_DISTANCE, target)
            }
            else -> {
                put(builder, CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
        }
        cameraHandler.post {
            try {
                session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
            } catch (e: Exception) {
                Log.w(TAG, "Fast focus update fallback to full restart", e)
                startRepeating(recording)
            }
        }
    }
    private val hideStatusRunnable = Runnable {
        if (::status.isInitialized) status.visibility = View.GONE
    }

    private fun addControlRow(
        parent: LinearLayout,
        title: String,
        initialValue: String,
        modeText: String?
    ): ControlWidgets {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(label(title), LinearLayout.LayoutParams(dp(68), dp(40)))

        val mode = modeText?.let {
            smallButton(it).also { button ->
                row.addView(button, LinearLayout.LayoutParams(dp(54), dp(32)))
            }
        }
        if (mode == null) row.addView(space(dp(4)))

        val minus = smallButton("−")
        row.addView(minus, LinearLayout.LayoutParams(dp(32), dp(32)))

        val seek = SeekBar(this).apply {
            max = 1000
            progress = 500
            splitTrack = false
        }
        row.addView(seek, LinearLayout.LayoutParams(0, dp(40), 1f))

        val plus = smallButton("+")
        row.addView(plus, LinearLayout.LayoutParams(dp(32), dp(32)))

        val value = valueText(initialValue).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        row.addView(value, LinearLayout.LayoutParams(dp(72), dp(40)))
        parent.addView(row, LinearLayout.LayoutParams.MATCH_PARENT, dp(40))
        return ControlWidgets(value, seek, minus, plus, mode)
    }

    private fun wireUiEvents() {
        cameraSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSpinnerEvents || position == selectedCameraIndex) return
                selectedCameraIndex = position
                selectedRawIndex = 1
                scheduleUserPreferencesSave()
                configureUiForCamera()
                reopenSelectedCamera()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        resolutionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSpinnerEvents || position == currentResolutionIndex()) return
                setCurrentResolutionIndex(position)
                constrainFpsToCurrentResolution()
                scheduleUserPreferencesSave()
                updateFormatHint()
                updateChromeValues()
                syncWheelToSelectedParameter()
                showCurrentResolutionStatus()
                reopenSelectedCamera()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        previewSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSpinnerEvents || position == selectedPreviewIndex) return
                selectedPreviewIndex = position
                scheduleUserPreferencesSave()
                reopenSelectedCamera()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        fpsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val fps = currentFpsOptions().getOrNull(position) ?: return
                if (suppressSpinnerEvents || fps == selectedFps) return
                selectedFps = fps
                scheduleUserPreferencesSave()
                constrainExposureToVideoRange()
                beginPreviewCadenceMetering()
                updateFormatHint()
                updateControlModes()
                restartRepeating()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        exposureControls.mode?.setOnClickListener {
            manualExposure = !manualExposure
            scheduleUserPreferencesSave()
            beginPreviewCadenceMetering()
            updateControlModes()
            restartRepeating()
        }
        wbControls.mode?.setOnClickListener {
            manualWb = !manualWb
            manualWbGains = null
            manualWbTransform = null
            scheduleUserPreferencesSave()
            updateControlModes()
            restartRepeating()
        }
        focusControls.mode?.setOnClickListener {
            val maxFocus = maximumFocusDistanceDiopters()
            if (maxFocus <= 0f) return@setOnClickListener
            manualFocus = !manualFocus
            scheduleUserPreferencesSave()
            updateControlModes()
            restartRepeating()
        }

        bindSeek(exposureControls, { updateExposureFromProgress(it) }, { -1 }, { 1 })
        bindSeek(isoControls, { updateIsoFromProgress(it) }, { -isoProgressStep() }, { isoProgressStep() })
        bindSeek(
            evControls,
            {
                updateEvFromProgress(it)
                beginPreviewCadenceMetering()
                updateControlModes()
            },
            { -1 },
            { 1 }
        )
        bindSeek(gainControls, { updateGainFromProgress(it) }, { -1 }, { 1 })
        bindSeek(wbControls, { updateKelvinFromProgress(it) }, { -10 }, { 10 })
        bindSeek(focusControls, { updateFocusFromProgress(it) }, { -1 }, { 1 })
    }

    private fun bindSeek(
        widgets: ControlWidgets,
        onProgress: (Int) -> Unit,
        minusDelta: () -> Int,
        plusDelta: () -> Int
    ) {
        widgets.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    onProgress(progress)
                    scheduleUserPreferencesSave()
                    scheduleControlApply()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                scheduleControlApply(forceImmediate = true)
            }
        })
        widgets.minus.setOnClickListener {
            widgets.seek.progress = (widgets.seek.progress + minusDelta()).coerceIn(0, widgets.seek.max)
            onProgress(widgets.seek.progress)
            scheduleUserPreferencesSave()
            scheduleControlApply(forceImmediate = true)
        }
        widgets.plus.setOnClickListener {
            widgets.seek.progress = (widgets.seek.progress + plusDelta()).coerceIn(0, widgets.seek.max)
            onProgress(widgets.seek.progress)
            scheduleUserPreferencesSave()
            scheduleControlApply(forceImmediate = true)
        }
    }

    private fun startCameraSystem() {
        if (!::cameraThread.isInitialized) {
            cameraThread = HandlerThread("RawLiteCamera").also { it.start() }
            cameraHandler = Handler(cameraThread.looper)
        }

        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        deviceCapabilities = DeviceCapabilitiesGateway.discover(manager)
        cameraOptions = CameraCatalog(manager, deviceCapabilities).discover()
        if (cameraOptions.isEmpty()) {
            setStatusText("No se encontró ninguna cámara RAW compatible")
            return
        }

        suppressSpinnerEvents = true
        cameraSpinner.adapter = spinnerAdapter(cameraOptions.map { it.label })
        selectedCameraIndex = selectedCameraIndex.coerceIn(cameraOptions.indices)
        cameraSpinner.setSelection(selectedCameraIndex)
        configureUiForCamera()
        fpsSpinner.setSelection(currentFpsOptions().indexOf(selectedFps).coerceAtLeast(0))
        suppressSpinnerEvents = false

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                openSelectedCamera(texture)
            }

            override fun onSurfaceTextureSizeChanged(
                texture: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                configurePreviewTransform(width, height)
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                closeCamera()
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
        if (preview.isAvailable) openSelectedCamera(preview.surfaceTexture!!)
    }

    private fun configureUiForCamera() {
        val option = currentOption() ?: return
        val availableControls = deviceCapabilities.controls(option.openId, option.physicalId)
        if (!availableControls.manualExposure) manualExposure = false
        if (!availableControls.manualWhiteBalance) manualWb = false
        if (!availableControls.manualFocus) manualFocus = false
        if (captureMode == CaptureMode.PURE10 && !currentPure10Availability()) {
            captureMode = CaptureMode.RAW
        }
        manualWbGains = null
        manualWbTransform = null
        suppressSpinnerEvents = true
        selectedRawIndex = selectedRawIndex.coerceIn(option.rawSizes.indices)
        val hlgProfiles = availableHlg10Profiles()
        if (captureMode == CaptureMode.PURE10 && hlgProfiles.isEmpty()) {
            captureMode = CaptureMode.RAW
        }
        selectedHlg10Index = selectedHlg10Index.coerceIn(
            0,
            (hlgProfiles.size - 1).coerceAtLeast(0)
        )
        refreshModeDependentSelectors(keepSuppressed = true)
        suppressSpinnerEvents = false
        updateFormatHint()

        val apertures = option.capability.lens.apertures
        apertureValue.text = apertures.takeIf { it.isNotEmpty() }
            ?.joinToString(" / ") { "f/${formatOne(it)}" }
            ?: "No disponible"

        val isoRange = isoRange()
        isoValue = IsoStepPolicy.nearest(isoValue, isoRange.lower, isoRange.upper)
        isoControls.seek.progress = isoProgressFor(isoValue)

        constrainExposureToVideoRange()

        val evRange = evRange()
        evSteps = evSteps.coerceIn(evRange.lower, evRange.upper)
        evControls.seek.progress = linearToProgress(evSteps.toDouble(), evRange.lower.toDouble(), evRange.upper.toDouble())

        val gainRange = gainRange()
        postRawGain = postRawGain.coerceIn(gainRange.lower, gainRange.upper)
        gainControls.seek.progress = linearToProgress(
            postRawGain.toDouble(),
            gainRange.lower.toDouble(),
            gainRange.upper.toDouble()
        )

        val maxFocus = maximumFocusDistanceDiopters()
        focusDiopters = focusDiopters.coerceIn(0f, maxFocus)
        focusControls.seek.progress = if (maxFocus > 0f) {
            focusDioptersToProgress(focusDiopters, maxFocus)
        } else {
            0
        }
        if (maxFocus <= 0f) manualFocus = false

        kelvin = kelvin.coerceIn(MIN_KELVIN, MAX_KELVIN)
        wbControls.seek.progress = linearToProgress(
            kelvin.toDouble(),
            MIN_KELVIN.toDouble(),
            MAX_KELVIN.toDouble()
        )
        updateAllControlValues()
        updateControlModes()
        updateChromeValues()
        syncWheelToSelectedParameter()
    }

    private fun reopenSelectedCamera() {
        if (!preview.isAvailable || recording || switchingSession) return
        openSelectedCamera(preview.surfaceTexture!!)
    }

    private fun openSelectedCamera(texture: SurfaceTexture) {
        pendingCameraTexture = texture
        closeCamera(preservePendingOpen = true)
        switchingSession = true
        recordButton.isEnabled = false
        updateRecordingChrome()
        openPendingCameraIfReady()
    }

    private fun openPendingCameraIfReady() {
        if (closingSession != null) return
        val texture = pendingCameraTexture ?: return
        pendingCameraTexture = null
        val option = currentOption() ?: return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        switchingSession = true
        recordButton.isEnabled = false
        status.text = "Abriendo ${option.label}..."

        val previewSize = currentPreviewSize()
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        configurePreviewTransform(preview.width, preview.height)
        previewSurface = Surface(texture)
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createSession(includeRaw = false)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
                recording = false
                switchingSession = false
                finishPackedOutputs()
                finishAudioCapture()
                finishTelemetry()
                setStatusText("Cámara desconectada")
                runOnUiThread {
                    recordButton.isEnabled = false
                    updateRecordingChrome()
                }
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
                recording = false
                switchingSession = false
                finishPackedOutputs()
                finishAudioCapture()
                finishTelemetry()
                setStatusText(cameraErrorLabel(error))
                runOnUiThread {
                    recordButton.isEnabled = false
                    updateRecordingChrome()
                }
            }
        }
        try {
            manager.openCamera(option.openId, callback, cameraHandler)
        } catch (error: Exception) {
            switchingSession = false
            pendingCameraTexture = texture
            Log.w(TAG, "Camera open deferred until foreground", error)
            setStatusText("Cámara pausada; se reabrirá al volver a la aplicación")
            runOnUiThread { updateRecordingChrome() }
        }
    }

    private fun createSession(includeRaw: Boolean, allowAnalysis: Boolean = true) {
        if (Looper.myLooper() != cameraHandler.looper) {
            cameraHandler.post { createSession(includeRaw, allowAnalysis) }
            return
        }
        pendingSessionRequest = SessionRequest(includeRaw, allowAnalysis)
        switchingSession = true
        runOnUiThread {
            recordButton.isEnabled = false
            updateRecordingChrome()
        }
        val activeSession = captureSession
        if (activeSession != null) {
            captureSession = null
            activeRepeatingBuilder = null
            closingSession = activeSession
            activeSession.close()
            return
        }
        configurePendingSession()
    }

    private fun configurePendingSession() {
        if (closingSession != null) return
        val request = pendingSessionRequest ?: return
        pendingSessionRequest = null
        configureSession(request.includeRaw, request.allowAnalysis)
    }

    private fun configureSession(includeRaw: Boolean, allowAnalysis: Boolean) {
        val camera = cameraDevice ?: return
        val option = currentOption() ?: return
        val previewTarget = previewSurface ?: return
        val generation = ++sessionGeneration
        switchingSession = true
        runOnUiThread {
            recordButton.isEnabled = false
            updateRecordingChrome()
        }

        clearPendingRawImages()
        rawReader?.close()
        rawReader = null
        analysisReader?.close()
        analysisReader = null
        resultsByTimestamp.clear()

        val outputs = mutableListOf<OutputConfiguration>()
        val previewOutput = OutputConfiguration(previewTarget)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            previewOutput.setDynamicRangeProfile(DynamicRangeProfiles.STANDARD)
        }
        option.physicalId?.let { previewOutput.setPhysicalCameraId(it) }
        outputs += previewOutput

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            allowAnalysis && technicalMode != TechnicalOverlayMode.OFF
        ) {
            val analysisSize = currentAnalysisSize()
            Log.d(TAG, "Technical analysis ${analysisSize.width}x${analysisSize.height}")
            analysisReader = createAnalysisReader(analysisSize)
            val analysisOutput = OutputConfiguration(analysisReader!!.surface)
            option.physicalId?.let { analysisOutput.setPhysicalCameraId(it) }
            outputs += analysisOutput
        }

        // Keep the selected capture surface in the preview session. The Pixel 6
        // otherwise switches sensor geometry when RAW is added at REC time,
        // which changes the visible framing and makes the preview appear stretched.
        if (rawVideoSupportedForCurrentCamera()) {
            val size = currentCaptureRawSize() ?: return
            rawReader = createRawReader(size)
            val rawOutput = OutputConfiguration(rawReader!!.surface)
            option.physicalId?.let { rawOutput.setPhysicalCameraId(it) }
            outputs += rawOutput
        }

        val configuration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (generation != sessionGeneration || cameraDevice == null) {
                        closingSession = session
                        session.close()
                        return
                    }
                    captureSession = session
                    recording = includeRaw
                    switchingSession = false
                    if (!includeRaw && previewTimingValues == null) beginPreviewCadenceMetering()
                    if (includeRaw) startAudioCapture()
                    startRepeating(includeRaw)
                    runOnUiThread {
                        applyLatestAutomaticRotation()
                        recordButton.isEnabled = rawVideoSupportedForCurrentCamera() &&
                            !finalizingOutput && !finalizingAudio
                        updateControlModes()
                        updateRecordingChrome()
                        updateTechnicalOverlayState()
                        preview.post {
                            configurePreviewTransform(preview.width, preview.height)
                        }
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (generation != sessionGeneration) {
                        closingSession = session
                        session.close()
                        return
                    }
                    if (allowAnalysis && technicalMode != TechnicalOverlayMode.OFF) {
                        technicalMode = TechnicalOverlayMode.OFF
                        runOnUiThread {
                            technicalOverlay.mode = TechnicalOverlayMode.OFF
                            technicalButton.text = TechnicalOverlayMode.OFF.label
                            technicalButton.setTextColor(MUTED)
                            setStatusText("Tools no compatibles con esta combinación; continuando sin ellas")
                        }
                        pendingSessionRequest = SessionRequest(includeRaw, allowAnalysis = false)
                        closingSession = session
                        session.close()
                        return
                    }
                    recording = false
                    switchingSession = false
                    closingSession = session
                    session.close()
                    if (includeRaw) {
                        finishPackedOutputs()
                        finishAudioCapture()
                        finishTelemetry()
                    }
                    setStatusText("No se pudo configurar la sesión RAW")
                    runOnUiThread {
                        recordButton.isEnabled = false
                        updateRecordingChrome()
                    }
                }

                override fun onClosed(session: CameraCaptureSession) {
                    Log.d(TAG, "CameraCaptureSession.onClosed")
                    if (closingSession === session) closingSession = null
                    if (closeCameraAfterSession) {
                        closeCameraAfterSession = false
                        finishCameraClose()
                    }
                    if (finalizeCaptureAfterSessionClose) {
                        finalizeCaptureAfterSessionClose = false
                        clearPendingRawImages()
                        resultsByTimestamp.clear()
                        finishPackedOutputs()
                        finishTelemetry()
                    }
                    if (pendingCameraTexture != null) {
                        // CameraCaptureSession callbacks run on RawLiteCamera, while
                        // reopening also updates the TextureView and status controls.
                        runOnUiThread { openPendingCameraIfReady() }
                    } else {
                        configurePendingSession()
                    }
                }
            }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val profiles = option.characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES
            )
            if (profiles?.getSupportedColorSpaces(ImageFormat.PRIVATE)
                    ?.contains(ColorSpace.Named.BT709) == true
            ) {
                configuration.setColorSpace(ColorSpace.Named.BT709)
            }
        }
        camera.createCaptureSession(configuration)
    }

    private fun createAnalysisReader(size: Size): ImageReader {
        analysisFrameCounter = 0
        return ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3).apply {
            setOnImageAvailableListener({ reader ->
                val image = runCatching { reader.acquireLatestImage() }.getOrNull()
                    ?: return@setOnImageAvailableListener
                try {
                    val targetHz = if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) 4 else 8
                    val interval = max(1, selectedFps / targetHz)
                    if (analysisFrameCounter++ % interval == 0) {
                        technicalOverlay.submitPreview(image)
                    }
                } finally {
                    image.close()
                }
            }, cameraHandler)
        }
    }

    private fun currentAnalysisSize(): Size {
        val target = currentPreviewSize()
        val sizes = currentCharacteristics()
            ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.filter { it.width <= target.width && it.height <= target.height }
            .orEmpty()
        if (sizes.isEmpty()) return target
        val targetRatio = target.width.toDouble() / target.height
        val targetPixels = 640L * 360L
        return sizes.minWithOrNull(
            compareBy<Size> {
                kotlin.math.abs(it.width.toDouble() / it.height - targetRatio)
            }.thenBy {
                kotlin.math.abs(it.width.toLong() * it.height - targetPixels)
            }
        ) ?: target
    }

    private fun createRawReader(size: Size): ImageReader {
        val configuredPure10 = captureMode == CaptureMode.PURE10
        val configuredPackedRaw = captureMode == CaptureMode.RAW && selectedRawIndex > 0
        val imageFormat = if (configuredPackedRaw || configuredPure10) {
            ImageFormat.RAW10
        } else {
            ImageFormat.RAW_SENSOR
        }
        val maxImages = when {
            configuredPure10 -> PURE10_MAX_IMAGES
            configuredPackedRaw -> CAPTURE_PROFILE.packedRawImageReaderCapacity
            else -> CAPTURE_PROFILE.dngImageReaderCapacity
        }
        return ImageReader.newInstance(size.width, size.height, imageFormat, maxImages).apply {
            setOnImageAvailableListener({ reader ->
                val image = try {
                    reader.acquireNextImage()
                } catch (error: IllegalStateException) {
                    Log.e(TAG, "ImageReader buffer limit reached", error)
                    writerBusyDrops++
                    droppedFrames++
                    updateStatus()
                    return@setOnImageAvailableListener
                } ?: return@setOnImageAvailableListener
                if (!recording) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                if (pendingWrites.get() + pendingRawImages.size >= maxImages - 1) {
                    writerBusyDrops++
                    droppedFrames++
                    image.close()
                    updateStatus()
                    return@setOnImageAvailableListener
                }

                val result = resultsByTimestamp.remove(image.timestamp)
                if (result != null) {
                    enqueueRawWrite(image, result)
                } else {
                    while (pendingRawImages.size >= MAX_PENDING_METADATA_IMAGES) {
                        val oldest = pendingRawImages.entries.firstOrNull() ?: break
                        pendingRawImages.remove(oldest.key)
                        oldest.value.close()
                        noMetadataDrops++
                        droppedFrames++
                    }
                    pendingRawImages[image.timestamp] = image
                }
            }, cameraHandler)
        }
    }

    private fun enqueueRawWrite(image: android.media.Image, result: CaptureResult) {
        if (!recording) {
            image.close()
            return
        }
        val option = currentOption()
        val dir = sessionDir
        if (option == null || dir == null) {
            noMetadataDrops++
            droppedFrames++
            image.close()
            updateStatus()
            return
        }

        val maxPending = when {
            pure10Recording -> PURE10_MAX_PENDING_WRITES
            packedRawRecording -> CAPTURE_PROFILE.packedRawMaximumPendingWrites
            else -> 1
        }
        if (pendingWrites.get() >= maxPending) {
            writerBusyDrops++
            droppedFrames++
            image.close()
            updateStatus()
            return
        }

        val metadata = frameMetadata(result)
        updateCaptureCadenceStats(metadata.timestampNs)
        if (firstSensorTimestampNs == 0L && metadata.timestampNs > 0L) {
            firstSensorTimestampNs = metadata.timestampNs
        }
        if (metadata.timestampNs > 0L) lastSensorTimestampNs = metadata.timestampNs
        val pendingCount = pendingWrites.incrementAndGet()
        peakPendingWrites.updateAndGet { previous -> max(previous, pendingCount) }
        val currentFrame = timelineFrameForTimestamp(metadata.timestampNs)
        if (pure10Recording) {
            val encoder = pure10Encoder
            if (encoder == null) {
                pendingWrites.decrementAndGet()
                writeErrorDrops++
                droppedFrames++
                image.close()
                updateStatus()
                return
            }
            val queuedAtNs = System.nanoTime()
            ioExecutor.execute {
                try {
                    maybeUpdateRawHistogram(image, currentFrame)
                    val accepted = encoder.submit(
                        image = image,
                        timestampNs = metadata.timestampNs,
                        redGain = metadata.wbRed,
                        greenEvenGain = metadata.wbGreenEven,
                        greenOddGain = metadata.wbGreenOdd,
                        blueGain = metadata.wbBlue,
                        lensShading = metadata.lensShading,
                        exposureGain = Pixel6MainDngColorProfile.DEFAULT_RENDER_GAIN *
                            postRawGain / 100f,
                        queuedAtNs = queuedAtNs
                    )
                    if (accepted) {
                        savedFrames++
                    } else {
                        writerBusyDrops++
                        droppedFrames++
                    }
                } catch (error: Throwable) {
                    Log.e(TAG, "PURE 10 write failed", error)
                    writeErrorDrops++
                    droppedFrames++
                } finally {
                    image.close()
                    pendingWrites.decrementAndGet()
                    updateStatus()
                }
            }
        } else if (packedRawRecording) {
            val handle = nativeWriterHandle
            if (handle == 0L) {
                pendingWrites.decrementAndGet()
                writeErrorDrops++
                droppedFrames++
                image.close()
                updateStatus()
                return
            }
            val plane = image.planes[0]
            val buffer = plane.buffer
            ioExecutor.execute {
                try {
                    maybeUpdateRawHistogram(image, currentFrame)
                    val writeResult = NativeRawWriter.writeFrame(
                        handle = handle,
                        buffer = buffer,
                        bufferOffset = buffer.position(),
                        bufferLimit = buffer.limit(),
                        rowStride = plane.rowStride,
                        frame = currentFrame,
                        timestampNs = metadata.timestampNs,
                        iso = metadata.iso,
                        exposureNs = metadata.exposureNs,
                        frameDurationNs = metadata.frameDurationNs,
                        focusDiopters = metadata.focusDiopters,
                        wbRed = metadata.wbRed,
                        wbGreenEven = metadata.wbGreenEven,
                        wbGreenOdd = metadata.wbGreenOdd,
                        wbBlue = metadata.wbBlue
                    )
                    if (writeResult != 0) {
                        throw IOException(
                            "Native RAW write failed ($writeResult): ${NativeRawWriter.lastError()}"
                        )
                    }
                    metadata.lensShading?.let { lensShadingWriter?.write(currentFrame, it) }
                    savedFrames++
                } catch (error: Throwable) {
                    Log.e(TAG, "RAW write failed", error)
                    writeErrorDrops++
                    droppedFrames++
                } finally {
                    image.close()
                    pendingWrites.decrementAndGet()
                    updateStatus()
                }
            }
        } else {
            ioExecutor.execute {
                try {
                    maybeUpdateRawHistogram(image, currentFrame)
                    val output = File(
                        DngSequenceClip.dngFramesDirectory(dir),
                        "frame_%06d.dng".format(currentFrame)
                    )
                    FileOutputStream(output).use { stream ->
                        DngCreator(option.characteristics, result).use { dng ->
                            dng.writeImage(stream, image)
                        }
                    }
                    savedFrames++
                } catch (error: Throwable) {
                    Log.e(TAG, "DNG write failed", error)
                    writeErrorDrops++
                    droppedFrames++
                } finally {
                    image.close()
                    pendingWrites.decrementAndGet()
                    updateStatus()
                }
            }
        }
    }

    private fun clearPendingRawImages() {
        pendingRawImages.values.forEach { it.close() }
        pendingRawImages.clear()
    }

    private fun frameMetadata(result: CaptureResult): FrameMetadata {
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val lensShading = if (pure10Recording) {
            pure10LensShading ?: readLensShadingSample(result)?.also {
                pure10LensShading = it
            }
        } else {
            readLensShadingSample(result)
        }
        return FrameMetadata(
            timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L,
            iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
            exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
            frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L,
            focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
            wbRed = gains?.red ?: 1f,
            wbGreenEven = gains?.greenEven ?: 1f,
            wbGreenOdd = gains?.greenOdd ?: 1f,
            wbBlue = gains?.blue ?: 1f,
            lensShading = lensShading
        )
    }

    private fun readLensShadingSample(result: CaptureResult): LensShadingSample? {
        return result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
            ?.let { map ->
                val factors = FloatArray(map.gainFactorCount)
                map.copyGainFactors(factors, 0)
                runCatching {
                    LensShadingSample(map.rowCount, map.columnCount, factors)
                }.getOrNull()
            }
    }

    private fun timelineFrameForTimestamp(timestampNs: Long): Int {
        val result = CaptureMath.timelineFrame(
            timestampNs = timestampNs,
            firstTimestampNs = firstSensorTimestampNs,
            lastTimelineFrame = lastTimelineFrame,
            fps = selectedFps
        )
        cadenceGaps += result.cadenceGap
        lastTimelineFrame = result.frame
        return result.frame
    }

    private fun resetCaptureCadenceStats() {
        captureIntervalCount = 0
        captureIntervalTotalNs = 0L
        captureIntervalMinNs = Long.MAX_VALUE
        captureIntervalMaxNs = 0L
        captureLongIntervals = 0
        previousCaptureTimestampNs = 0L
    }

    private fun updateCaptureCadenceStats(timestampNs: Long) {
        if (timestampNs <= 0L) return
        val previous = previousCaptureTimestampNs
        previousCaptureTimestampNs = timestampNs
        if (previous <= 0L || timestampNs <= previous) return

        val interval = timestampNs - previous
        captureIntervalCount++
        captureIntervalTotalNs += interval
        captureIntervalMinNs = min(captureIntervalMinNs, interval)
        captureIntervalMaxNs = max(captureIntervalMaxNs, interval)

        val nominal = CaptureCadencePolicy.frameDurationNs(selectedFps)
        if (interval > nominal + nominal / 2) {
            captureLongIntervals++
        }
    }

    private fun maybeUpdateRawHistogram(image: android.media.Image, frame: Int) {
        if (pure10Recording && isPure10UhdSelected()) return
        if (!histogramEnabled) return
        val interval = max(1, selectedFps / 4)
        if (frame % interval != 0) return
        val plane = image.planes.firstOrNull() ?: return
        val buffer = plane.buffer
        val whiteLevel = currentCharacteristics()
            ?.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?.coerceAtLeast(1) ?: 1023
        val histogram = IntArray(64)
        val step = 8
        if (image.format == ImageFormat.RAW10) {
            val offset = buffer.position()
            for (y in 0 until image.height step step) {
                val row = offset + y * plane.rowStride
                for (x in 0 until image.width step step) {
                    val group = row + (x / 4) * 5
                    if (group + 4 >= buffer.limit()) continue
                    val pixel = x and 3
                    val high = buffer.get(group + pixel).toInt() and 0xff
                    val low = buffer.get(group + 4).toInt() and 0xff
                    val value = (high shl 2) or ((low shr (pixel * 2)) and 0x03)
                    histogram[(value * histogram.size / (whiteLevel + 1)).coerceIn(0, 63)]++
                }
            }
        } else {
            val offset = buffer.position()
            for (y in 0 until image.height step step) {
                val row = offset + y * plane.rowStride
                for (x in 0 until image.width step step) {
                    val sample = row + x * plane.pixelStride
                    if (sample + 1 >= buffer.limit()) continue
                    val value = (buffer.get(sample).toInt() and 0xff) or
                        ((buffer.get(sample + 1).toInt() and 0xff) shl 8)
                    histogram[(value * histogram.size / (whiteLevel + 1)).coerceIn(0, 63)]++
                }
            }
        }
        technicalOverlay.submitRawHistogram(histogram)
    }

    private fun startRepeating(includeRaw: Boolean) {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val previewTarget = previewSurface ?: return
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewTarget)
            if (!includeRaw || !isPure10UhdSelected()) {
                analysisReader?.surface?.let { addTarget(it) }
            }
            if (includeRaw) rawReader?.surface?.let { addTarget(it) }
        }
        applyControls(builder, includeRaw)
        activeRepeatingBuilder = builder

        try {
            session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
        } catch (error: IllegalArgumentException) {
            if (manualExposure && fallbackToFasterShutter()) {
                Log.w(TAG, "Rejected shutter; retrying with ${formatExposure(exposureNs)}", error)
                runOnUiThread {
                    updateAllControlValues()
                    syncWheelToSelectedParameter()
                }
                cameraHandler.post { startRepeating(includeRaw) }
                return
            }
            Log.e(TAG, "Could not start repeating request", error)
            setStatusText("Obturación rechazada por la cámara")
            stopAfterRepeatingFailure(includeRaw)
        } catch (error: Exception) {
            Log.e(TAG, "Could not start repeating request", error)
            setStatusText("No se pudo iniciar la captura")
            stopAfterRepeatingFailure(includeRaw)
        }
        updateStatus(force = true)
    }

    private fun stopAfterRepeatingFailure(includeRaw: Boolean) {
        if (!includeRaw) return
        runOnUiThread {
            if (recording) {
                setStatusText("DETENIENDO · la cámara rechazó la captura")
                stopRecording()
            }
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            storeCaptureResult(result)
            updatePreviewCadenceMetering()
            updateStatus()
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            if (!recording) return
            captureFailureDrops++
            droppedFrames++
            Log.e(
                TAG,
                "Camera capture failed reason=${failure.reason} frame=${failure.frameNumber}"
            )
            updateStatus()
        }
    }

    private fun storeCaptureResult(result: TotalCaptureResult) {
        val effective = currentOption()?.physicalId
            ?.let { result.physicalCameraResults[it] }
            ?: result
        latestResult = effective
        updatePreviewFocusConfirmation(effective)
        effective.get(CaptureResult.SENSOR_TIMESTAMP)?.let { timestamp ->
            val pendingImage = pendingRawImages.remove(timestamp)
            if (pendingImage != null) {
                enqueueRawWrite(pendingImage, effective)
            } else {
                resultsByTimestamp[timestamp] = effective
            }
            if (resultsByTimestamp.size > 64) resultsByTimestamp.clear()
        }
    }

    private fun updatePreviewFocusConfirmation(result: CaptureResult) {
        if (!previewFocusConfirmationPending) return
        val state = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
        when (state) {
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> {
                previewFocusConfirmationPending = false
                if (previewMeteringLocked) {
                    lockFocusAtResultDistance(result)
                } else {
                    runOnUiThread {
                        if (::cameraChrome.isInitialized) cameraChrome.showFocusConfirmed()
                    }
                }
            }
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
            CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> {
                previewFocusConfirmationPending = false
                val wasLocked = previewMeteringLocked
                runOnUiThread {
                    clearPreviewFocusMetering(updateCamera = true)
                    if (wasLocked && ::cameraChrome.isInitialized) {
                        cameraChrome.showStatusPopup("AF NO FIJADO")
                    }
                }
            }
        }
    }

    private fun lockFocusAtResultDistance(result: CaptureResult) {
        val maxFocus = maximumFocusDistanceDiopters()
        if (maxFocus <= 0f) return
        val measuredFocus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            ?.coerceIn(0f, maxFocus)
            ?: return
        runOnUiThread {
            focusDiopters = measuredFocus
            manualFocus = true
            previewMeteringRegion = null
            previewMeteringLocked = false
            previewFocusConfirmationPending = false
            focusControls.seek.progress = focusDioptersToProgress(focusDiopters, maxFocus)
            scheduleUserPreferencesSave()
            updateControlModes()
            updateChromeValues()
            if (::cameraChrome.isInitialized) {
                cameraChrome.showStatusPopup("AF-L")
                cameraChrome.clearFocusReticle()
            }
            restartRepeating()
        }
    }

    private fun applyControls(builder: CaptureRequest.Builder, includeRaw: Boolean) {
        val option = currentOption() ?: return
        val snapshot = when {
            includeRaw -> lockedValues
            !manualExposure -> previewTimingValues
            else -> null
        }
        put(builder, CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        put(builder, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, chooseCoolerFpsRange())
        put(builder, CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, postRawGain)
        put(builder, CaptureRequest.SCALER_CROP_REGION, previewCropRegion())
        put(
            builder,
            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
        )
        put(builder, CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_FAST)
        applyRec709PreviewProcessing(builder)

        val cadenceExposure = !includeRaw &&
            snapshot?.iso != null && snapshot.exposureNs != null
        val lockedExposure = includeRaw && lockExposure &&
            snapshot?.iso != null && snapshot.exposureNs != null
        val autoCadenceExposure = CaptureCadencePolicy.shouldFreezeAutoExposureForRawRecording(
            includeRaw = includeRaw,
            fps = selectedFps,
            manualExposure = manualExposure,
            snapshotIso = snapshot?.iso,
            snapshotExposureNs = snapshot?.exposureNs
        )
        if (manualExposure || lockedExposure || autoCadenceExposure) {
            val frameDurationNs = CaptureCadencePolicy.frameDurationNs(selectedFps)
            val requestedExposureNs = if (lockedExposure || autoCadenceExposure) {
                snapshot?.exposureNs ?: exposureNs
            } else {
                exposureNs
            }
            val requestedIso = if (lockedExposure || autoCadenceExposure) {
                snapshot?.iso ?: isoValue
            } else {
                isoValue
            }
            put(builder, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            put(builder, CaptureRequest.SENSOR_SENSITIVITY, requestedIso.coerceIn(
                isoRange().lower,
                isoRange().upper
            ))
            put(
                builder,
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                requestedExposureNs.coerceIn(exposureRange().lower, exposureRange().upper)
            )
            put(builder, CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)
        } else if (cadenceExposure) {
            val frameDurationNs = CaptureCadencePolicy.frameDurationNs(selectedFps)
            put(builder, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            put(builder, CaptureRequest.SENSOR_SENSITIVITY, snapshot.iso)
            put(
                builder,
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                snapshot.exposureNs.coerceIn(exposureRange().lower, exposureRange().upper)
            )
            put(builder, CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)
        } else {
            put(builder, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            put(builder, CaptureRequest.CONTROL_AE_LOCK, false)
            put(builder, CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evSteps)
        }
        val lockedWb = includeRaw && lockWhiteBalance && snapshot?.gains != null
        if (manualWb) {
            put(builder, CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            put(
                builder,
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            put(
                builder,
                CaptureRequest.COLOR_CORRECTION_GAINS,
                manualWbGains ?: gainsForKelvin(kelvin)
            )
            put(
                builder,
                CaptureRequest.COLOR_CORRECTION_TRANSFORM,
                manualWbTransform ?: latestColorTransform() ?: IDENTITY_TRANSFORM
            )
        } else {
            put(builder, CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            put(builder, CaptureRequest.CONTROL_AWB_LOCK, lockedWb)
        }

        val maxFocus = maximumFocusDistanceDiopters()
        val lockedFocus = includeRaw && lockFocus && snapshot?.focusDiopters != null
        val meteringRegion = previewMeteringRegion
        val canApplyAfRegion = meteringRegion != null && currentAvailableControls().tapToFocus
        when {
            maxFocus <= 0f -> {
                put(builder, CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                put(builder, CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            }
            manualFocus || lockedFocus -> {
                put(builder, CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                put(
                    builder,
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    (if (lockedFocus) snapshot?.focusDiopters else focusDiopters)
                        ?.coerceIn(0f, maxFocus) ?: focusDiopters.coerceIn(0f, maxFocus)
                )
            }
            canApplyAfRegion -> {
                put(builder, CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRegion))
                put(
                    builder,
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_AUTO
                )
            }
            else -> {
                put(
                    builder,
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            }
        }
    }

    private fun <T> put(
        builder: CaptureRequest.Builder,
        key: CaptureRequest.Key<T>,
        value: T?
    ) {
        if (value == null) return
        try {
            builder.set(key, value)
        } catch (error: IllegalArgumentException) {
            Log.v(TAG, "Logical key unavailable: ${key.name}")
        }
    }

    private fun applyRec709PreviewProcessing(builder: CaptureRequest.Builder) {
        put(
            builder,
            CaptureRequest.NOISE_REDUCTION_MODE,
            CameraCharacteristics.NOISE_REDUCTION_MODE_FAST
        )
        put(
            builder,
            CaptureRequest.EDGE_MODE,
            CameraCharacteristics.EDGE_MODE_FAST
        )
        put(builder, CaptureRequest.TONEMAP_MODE, CameraCharacteristics.TONEMAP_MODE_FAST)
        put(
            builder,
            CaptureRequest.COLOR_CORRECTION_MODE,
            CameraCharacteristics.COLOR_CORRECTION_MODE_FAST
        )
    }

    private fun startRecording() {
        if (cameraDevice == null || captureSession == null || switchingSession ||
            finalizingOutput || finalizingAudio
        ) {
            return
        }
        captureStopPending = false
        if (!rawVideoSupportedForCurrentCamera()) {
            setStatusText("La cámara frontal queda disponible solo como preview; RAW no sostiene 15 FPS")
            if (::cameraChrome.isInitialized) {
                cameraChrome.showStatusPopup("RAW FRONTAL NO DISPONIBLE")
            }
            return
        }
        recordingDisplayRotationDegrees = if (rotationLockedByUser) {
            visualDisplayRotationDegrees()
        } else {
            detectedDeviceOrientationDegrees
        }
        lastTimelineFrame = -1
        cadenceGaps = 0
        resetCaptureCadenceStats()
        firstSensorTimestampNs = 0L
        lastSensorTimestampNs = 0L
        technicalOverlay.clearRawHistogram()
        droppedFrames = 0
        peakPendingWrites.set(0)
        noMetadataDrops = 0
        writerBusyDrops = 0
        writeErrorDrops = 0
        captureFailureDrops = 0
        savedFrames = 0
        sessionStartElapsedMs = SystemClock.elapsedRealtime()
        activeCaptureMode = captureMode
        val output = CaptureFormatPolicy.output(activeCaptureMode, selectedRawIndex)
        pure10Recording = output == CaptureOutput.PURE10
        pure10LensShading = null
        packedRawRecording = output == CaptureOutput.RAW10
        dngRecording = output == CaptureOutput.CINEMA_DNG
        if (!CaptureFormatPolicy.selectionIsValid(activeCaptureMode, selectedRawIndex)) {
            setStatusText("Formato de captura no disponible")
            pure10Recording = false
            return
        }
        val base = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val stem = CaptureStorage.captureStem(output, stamp)
        sessionDir = runCatching {
            CaptureStorage.createUniqueDirectory(base, stem)
        }.getOrElse { error ->
            Log.e(TAG, "Could not reserve capture directory", error)
            setStatusText("No se pudo crear una carpeta segura para la toma")
            return
        }
        lockedValues = if (lockExposure || lockWhiteBalance || lockFocus || selectedFps < 30) {
            snapshotCurrentValues()
        } else {
            null
        }
        sessionAutoCadenceLock = autoCadenceLockActive()
        sessionExposurePolicy = recordingExposurePolicyName()
        sessionRequestedFrameDurationNs = CaptureCadencePolicy.frameDurationNs(selectedFps)
        setParametersExpanded(false)
        switchingSession = true
        recordButton.isEnabled = false
        updateControlModes()
        updateRecordingChrome()
        updateStatus(force = true)
        val startToken = ++recordingStartToken
        val setupStartedNs = SystemClock.elapsedRealtimeNanos()
        ioExecutor.execute {
            val outputOpened = when {
                pure10Recording -> openPure10Outputs()
                packedRawRecording -> openPackedOutputs()
                dngRecording -> openDngOutputs()
                else -> false
            }
            val telemetryOpened = outputOpened && openTelemetry()
            val setupMs = (SystemClock.elapsedRealtimeNanos() - setupStartedNs) / 1_000_000.0
            runOnUiThread {
                if (startToken != recordingStartToken || cameraDevice == null || isDestroyed) {
                    Log.w(TAG, "Capture setup cancelled after ${formatOne(setupMs.toFloat())} ms")
                    if (outputOpened) finishPackedOutputs()
                    if (telemetryOpened) finishTelemetry()
                    switchingSession = false
                    updateControlModes()
                    updateRecordingChrome()
                    return@runOnUiThread
                }
                if (!outputOpened || !telemetryOpened) {
                    Log.e(TAG, "Capture setup failed output=$outputOpened telemetry=$telemetryOpened")
                    if (outputOpened) finishPackedOutputs()
                    if (telemetryOpened) finishTelemetry()
                    recording = false
                    switchingSession = false
                    pure10Recording = false
                    packedRawRecording = false
                    dngRecording = false
                    recordButton.isEnabled = cameraDevice != null &&
                        !finalizingOutput && !finalizingAudio &&
                        rawVideoSupportedForCurrentCamera()
                    updateControlModes()
                    updateRecordingChrome()
                    return@runOnUiThread
                }
                Log.i(
                    TAG,
                    "Capture setup ready mode=$activeCaptureMode in ${formatOne(setupMs.toFloat())} ms " +
                        "rotation=$recordingDisplayRotationDegrees output=${captureRotationDegrees(currentOption()!!.characteristics)}"
                )
                sessionStartElapsedMs = SystemClock.elapsedRealtime()
                cameraHandler.post { createSession(includeRaw = true) }
                updateControlModes()
                updateRecordingChrome()
                updateStatus(force = true)
            }
        }
    }

    private fun stopRecording() {
        if (!recording && !switchingSession) return
        beginStopMeasurement()
        ++recordingStartToken
        previewTimingValues = snapshotCurrentValues()
        recording = false
        switchingSession = true
        captureStopPending = true
        recordButton.isEnabled = false
        updateRecordingChrome()
        logStopMeasurement("AUDIO_FINISH_REQUEST")
        finishAudioCapture()
        lockedValues = null
        cameraHandler.post {
            logStopMeasurement("PREVIEW_RESTART_START")
            startRepeating(includeRaw = false)
            logStopMeasurement("PREVIEW_REPEATING_REQUESTED")
            clearPendingRawImages()
            resultsByTimestamp.clear()
            logStopMeasurement("OUTPUT_FINISH_REQUEST")
            finishPackedOutputs()
            logStopMeasurement("TELEMETRY_FINISH_REQUEST")
            finishTelemetry()
            runOnUiThread {
                switchingSession = false
                logStopMeasurement("SWITCHING_DONE")
                updateControlModes()
                updateRecordingChrome()
                updateStatus(force = true)
            }
        }
    }

    private fun startAudioCapture() {
        if (!audioEnabled ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            updateMicrophoneUi()
            return
        }
        val dir = sessionDir ?: return
        if (audioRecorder != null) return
        val token = Any()
        audioCaptureToken = token
        runOnUiThread {
            activeAudioInput = null
            audioCaptureError = false
            updateMicrophoneUi()
        }
        val recorder = PcmAudioRecorder.create(
            this, dir,
            routeListener = { input -> runOnUiThread {
                if (audioCaptureToken === token) {
                    activeAudioInput = input
                    updateMicrophoneUi()
                }
            } },
            errorListener = { error -> runOnUiThread {
                if (audioCaptureToken === token) {
                    audioCaptureError = true
                    updateMicrophoneUi()
                    setStatusText("Audio interrumpido · $error")
                }
            } },
            levelListener = ::updateAudioLevel
        )
        if (recorder == null || !recorder.start()) {
            runOnUiThread {
                audioCaptureError = true
                updateMicrophoneUi()
            }
            setStatusText("Grabación activa · no se pudo iniciar el micrófono")
            return
        }
        audioRecorder = recorder
        runOnUiThread { updateMicrophoneUi() }
    }

    private fun finishAudioCapture() {
        val recorder = audioRecorder ?: return
        audioRecorder = null
        audioCaptureToken = null
        val dir = sessionDir ?: return
        finalizingAudio = true
        logStopMeasurement("AUDIO_STOP_START")
        runOnUiThread {
            updateMicrophoneUi()
            updateControlModes()
            updateStatus(force = true)
        }
        audioExecutor.execute {
            val summary = recorder.stop()
            logStopMeasurement(
                "AUDIO_STOP_DONE",
                "frames=${summary.frames} bytes=${summary.dataBytes} finalized=${summary.finalized}"
            )
            writeSyncDescription(dir, summary)
            logStopMeasurement("SYNC_WRITE_DONE")
            schedulePure10AudioMuxIfReady(dir)
            finalizingAudio = false
            runOnUiThread {
                updateMicrophoneUi()
                recordButton.isEnabled = cameraDevice != null && !switchingSession &&
                    !recording && !finalizingOutput &&
                    rawVideoSupportedForCurrentCamera()
                updateControlModes()
                updateStatus(force = true)
            }
        }
    }

    private fun writeSyncDescription(directory: File, audio: AudioCaptureSummary) {
        val timestampSource = currentCharacteristics()
            ?.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            ?: CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN
        val description = AvSyncMetadataFactory.create(
            context = AvSyncContext(
                cameraUsesBoottime = timestampSource ==
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
                cameraFirstTimestampNs = firstSensorTimestampNs,
                cameraLastTimestampNs = lastSensorTimestampNs,
                lastTimelineFrame = lastTimelineFrame,
                savedFrames = savedFrames,
                cadenceGaps = cadenceGaps
            ),
            audio = audio
        )
        runCatching { File(directory, "sync.json").writeText(description.toString(2)) }
            .onFailure { Log.e(TAG, "Could not write A/V sync metadata", it) }
    }

    private fun openPackedOutputs(): Boolean {
        val dir = sessionDir ?: return false
        val size = currentRawSize() ?: return false
        return try {
            File(dir, "clip.json").writeText(buildPackedDescription(size).toString(2))
            nativeWriterHandle = NativeRawWriter.open(
                rawPath = File(dir, Raw10FrameStore.COMPRESSED_FILE).absolutePath,
                metadataPath = File(dir, "frames.csv").absolutePath,
                statePath = File(dir, "clip.state").absolutePath,
                width = size.width,
                height = size.height,
                rowBytes = (size.width * 10 + 7) / 8,
                checkpointFrames = max(selectedFps * CHECKPOINT_SECONDS, 1)
            )
            if (nativeWriterHandle == 0L) {
                throw IOException("Native writer open failed: ${NativeRawWriter.lastError()}")
            }
            lensShadingWriter = runCatching {
                LensShadingMetadataWriter(File(dir, LensShadingMetadataWriter.FILE_NAME))
            }.onFailure {
                Log.w(TAG, "Could not create lens-shading sidecar", it)
            }.getOrNull()
            true
        } catch (error: Exception) {
            Log.e(TAG, "Could not create RAW10 outputs", error)
            nativeWriterHandle = 0L
            runCatching { lensShadingWriter?.close() }
            lensShadingWriter = null
            setStatusText("No se pudo crear el archivo RAW10")
            false
        }
    }

    private fun openDngOutputs(): Boolean {
        val dir = sessionDir ?: return false
        val size = currentRawSize() ?: return false
        return runCatching {
            val dngDir = File(dir, DngSequenceClip.DNG_DIRECTORY)
            if ((!dngDir.exists() && !dngDir.mkdirs()) || !dngDir.isDirectory) {
                throw IOException("No se pudo crear la carpeta DNG")
            }
            File(dir, "clip.json").writeText(buildDngDescription(size).toString(2))
            true
        }.getOrElse { error ->
            Log.e(TAG, "Could not create CinemaDNG metadata", error)
            setStatusText("No se pudo crear la secuencia CinemaDNG")
            false
        }
    }

    private fun openPure10Outputs(): Boolean {
        val dir = sessionDir ?: return false
        val fallbackSize = currentRawSize() ?: return false
        val outputSize = currentResolutionSize() ?: return false
        val sourceSize = pure10SourceSize(outputSize, fallbackSize)
        val option = currentOption() ?: return false
        var encoder: Pure10Encoder? = null
        return try {
            encoder = Pure10Encoder(
                outputFile = File(dir, Pure10Encoder.VIDEO_FILE),
                sourceSize = sourceSize,
                outputSize = outputSize,
                fps = selectedFps,
                bitRate = Pure10Encoder.BIT_RATE,
                rotationDegrees = PURE10_CONTAINER_ROTATION_DEGREES,
                characteristics = option.characteristics
            )
            encoder.start()
            pure10Encoder = encoder
            File(dir, "clip.json").writeText(
                buildPure10Description(sourceSize, outputSize).toString(2)
            )
            true
        } catch (error: Throwable) {
            Log.e(TAG, "Could not create PURE 10 outputs", error)
            runCatching { encoder?.abort() }
            pure10Encoder = null
            setStatusText("HLG 10 no pudo iniciar el encoder Main10")
            false
        }
    }

    private fun finishPackedOutputs() {
        val pureEncoder = pure10Encoder
        if (pureEncoder != null) {
            pure10Encoder = null
            finalizingOutput = true
            logStopMeasurement("PURE10_ENCODER_FINISH_START")
            runOnUiThread {
                recordButton.isEnabled = false
                updateControlModes()
                updateStatus(force = true)
            }
            ioExecutor.execute {
                val summary = runCatching { pureEncoder.finish() }
                    .onFailure { Log.e(TAG, "Could not finalize PURE 10 output", it) }
                    .getOrNull()
                logStopMeasurement(
                    "PURE10_ENCODER_FINISH_DONE",
                    "encoded=${summary?.encodedFrames ?: -1} submitted=${summary?.submittedFrames ?: -1} " +
                        "video_bytes=${sessionDir?.let { File(it, Pure10Encoder.VIDEO_FILE).length() } ?: -1L}"
                )
                val directory = sessionDir
                if (directory != null) {
                    updatePure10Description(directory, summary)
                    logStopMeasurement("PURE10_DESCRIPTION_DONE")
                    schedulePure10AudioMuxIfReady(directory)
                }
                pure10Recording = false
                finalizingOutput = false
                runOnUiThread {
                    recordButton.isEnabled = cameraDevice != null && !switchingSession &&
                        !recording && !finalizingAudio &&
                        rawVideoSupportedForCurrentCamera()
                    updateControlModes()
                    updateStatus(force = true)
                }
            }
            return
        }
        if (dngRecording) {
            dngRecording = false
            finalizingOutput = true
            logStopMeasurement("DNG_DESCRIPTION_START")
            runOnUiThread {
                recordButton.isEnabled = false
                updateControlModes()
                updateStatus(force = true)
            }
            ioExecutor.execute {
                sessionDir?.let(::updateDngDescription)
                logStopMeasurement("DNG_DESCRIPTION_DONE")
                finalizingOutput = false
                runOnUiThread {
                    recordButton.isEnabled = cameraDevice != null && !switchingSession &&
                        !recording && !finalizingAudio &&
                        rawVideoSupportedForCurrentCamera()
                    updateControlModes()
                    updateStatus(force = true)
                }
            }
            return
        }
        val handle = nativeWriterHandle
        if (handle == 0L) return
        nativeWriterHandle = 0L
        finalizingOutput = true
        logStopMeasurement("RAW10_CLOSE_START")
        runOnUiThread {
            recordButton.isEnabled = false
            updateControlModes()
            updateStatus(force = true)
        }
        ioExecutor.execute {
            runCatching { lensShadingWriter?.close() }
                .onFailure { Log.e(TAG, "Could not finalize lens-shading metadata", it) }
            lensShadingWriter = null
            val closeResult = NativeRawWriter.close(handle)
            logStopMeasurement(
                "RAW10_CLOSE_DONE",
                "result=$closeResult raw_bytes=${sessionDir?.let {
                    File(it, Raw10FrameStore.COMPRESSED_FILE).length()
                } ?: -1L}"
            )
            if (closeResult != 0) {
                Log.e(
                    TAG,
                    "Could not finalize RAW10 output ($closeResult): ${NativeRawWriter.lastError()}"
                )
            }
            finalizingOutput = false
            runOnUiThread {
                recordButton.isEnabled = cameraDevice != null && !switchingSession && !recording &&
                    !finalizingAudio && rawVideoSupportedForCurrentCamera()
                updateControlModes()
                updateStatus(force = true)
            }
        }
    }

    private fun openTelemetry(): Boolean {
        val dir = sessionDir ?: return false
        return try {
            telemetryStream = FileOutputStream(File(dir, "telemetry.csv")).apply {
                write(
                    (
                        "elapsed_ms,battery_c,thermal_status,thermal_headroom," +
                            "available_bytes,saved_frames,dropped_frames,pending_writes," +
                            "no_metadata_drops,writer_busy_drops,write_error_drops," +
                            "cadence_gaps,capture_failure_drops,audio_peak\n"
                        ).toByteArray(Charsets.US_ASCII)
                )
            }
            refreshDeviceTelemetry(writeSample = true)
            true
        } catch (error: IOException) {
            Log.e(TAG, "Could not create telemetry output", error)
            telemetryStream = null
            setStatusText("No se pudo crear la telemetría")
            false
        }
    }

    private fun finishTelemetry() {
        val stream = telemetryStream ?: return
        telemetryStream = null
        logStopMeasurement("TELEMETRY_CLOSE_START")
        telemetryExecutor.execute {
            try {
                stream.fd.sync()
                stream.close()
                logStopMeasurement("TELEMETRY_CLOSE_DONE")
            } catch (error: IOException) {
                Log.e(TAG, "Could not finalize telemetry", error)
                logStopMeasurement("TELEMETRY_CLOSE_ERROR", error.javaClass.simpleName)
            }
        }
    }

    private val telemetryRunnable = object : Runnable {
        override fun run() {
            if (!telemetryMonitoring) return
            refreshDeviceTelemetry(writeSample = recording)
            when {
                recording && storageAvailableBytes < MIN_FREE_BYTES -> {
                    setStatusText("DETENIENDO · almacenamiento insuficiente")
                    stopRecording()
                }
                recording && thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> {
                    setStatusText("DETENIENDO · límite térmico")
                    stopRecording()
                }
                else -> updateStatus(force = true)
            }
            telemetryHandler.postDelayed(this, TELEMETRY_INTERVAL_MS)
        }
    }

    private val thermalListener: PowerManager.OnThermalStatusChangedListener by lazy {
        PowerManager.OnThermalStatusChangedListener { statusValue ->
            thermalStatus = statusValue
            if (::technicalOverlay.isInitialized) technicalOverlay.setThermalStatus(statusValue)
            updateStatus(force = true)
        }
    }

    private fun startTelemetryMonitor() {
        if (telemetryMonitoring || !::powerManager.isInitialized) return
        telemetryMonitoring = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalStatus = powerManager.currentThermalStatus
            if (::technicalOverlay.isInitialized) technicalOverlay.setThermalStatus(thermalStatus)
            powerManager.addThermalStatusListener(mainExecutor, thermalListener)
        }
        refreshDeviceTelemetry(writeSample = false)
        telemetryHandler.post(telemetryRunnable)
    }

    private fun stopTelemetryMonitor() {
        if (!telemetryMonitoring || !::powerManager.isInitialized) return
        telemetryMonitoring = false
        telemetryHandler.removeCallbacks(telemetryRunnable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.removeThermalStatusListener(thermalListener)
        }
    }

    private fun refreshDeviceTelemetry(writeSample: Boolean) {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryTemperatureC = battery
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.div(10f)
        thermalHeadroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            powerManager.getThermalHeadroom(10).takeUnless { it.isNaN() }
        } else {
            null
        }
        val storageDir = sessionDir?.takeIf { it.exists() }
            ?: getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        storageAvailableBytes = try {
            storageDir?.let { StatFs(it.absolutePath).availableBytes } ?: 0L
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Could not read available storage", error)
            0L
        }

        val stream = telemetryStream
        if (writeSample && stream != null) {
            val elapsed = SystemClock.elapsedRealtime() - sessionStartElapsedMs
            val line = String.format(
                Locale.US,
                "%d,%s,%d,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.3f\n",
                elapsed,
                batteryTemperatureC?.let { "%.1f".format(Locale.US, it) } ?: "",
                thermalStatus,
                thermalHeadroom?.let { "%.3f".format(Locale.US, it) } ?: "",
                storageAvailableBytes,
                savedFrames,
                droppedFrames,
                pendingWrites.get(),
                noMetadataDrops,
                writerBusyDrops,
                writeErrorDrops,
                cadenceGaps,
                captureFailureDrops,
                audioPeak
            )
            telemetryExecutor.execute {
                try {
                    stream.write(line.toByteArray(Charsets.US_ASCII))
                } catch (error: IOException) {
                    Log.e(TAG, "Could not write telemetry", error)
                }
            }
        }
    }

    private fun buildPackedDescription(size: Size): JSONObject {
        return CaptureMetadataFactory.raw10(
            context = captureMetadataContext(size),
            technicalOverlay = technicalMode.name,
            checkpointFrames = max(selectedFps * CHECKPOINT_SECONDS, 1)
        ).put("raw_container", "rawlite-raw10c")
            .put("raw_file", Raw10FrameStore.COMPRESSED_FILE)
            .put("raw_compression", "lossless-cfa-delta-deflate")
            .put("raw_compression_version", 1)
            .put("lens_shading_map_file", LensShadingMetadataWriter.FILE_NAME)
            .put("lens_shading_correction", "camera2-per-frame")
    }

    private fun buildDngDescription(size: Size): JSONObject {
        return CaptureMetadataFactory.cinemaDng(captureMetadataContext(size))
            .put("lens_shading_correction", "camera2-dngcreator")
    }

    private fun updateDngDescription(directory: File) {
        val descriptionFile = File(directory, "clip.json")
        val description = runCatching { JSONObject(descriptionFile.readText()) }
            .getOrElse { JSONObject() }
        description.put("finalized", true)
        description.put("saved_frames", savedFrames)
        description.put("dropped_frames", droppedFrames)
        description.put("cadence_gaps", cadenceGaps)
        description.put("timeline_frames", (lastTimelineFrame + 1).coerceAtLeast(savedFrames))
        runCatching { descriptionFile.writeText(description.toString(2)) }
            .onFailure { Log.e(TAG, "Could not update CinemaDNG metadata", it) }
    }

    private fun buildPure10Description(sourceSize: Size, outputSize: Size): JSONObject {
        val profile = currentHlg10Profile()
        return CaptureMetadataFactory.pure10(
            captureMetadataContext(outputSize),
            postRawGain,
            kelvin
        )
            .put("raw_source_width", sourceSize.width)
            .put("raw_source_height", sourceSize.height)
            .put("pure10_crop_x", ((sourceSize.width - outputSize.width) / 2) and -2)
            .put("pure10_crop_y", ((sourceSize.height - outputSize.height) / 2) and -2)
            .put("pure10_experimental_4k", profile?.status == Hlg10OutputProfile.Status.EXPERIMENTAL)
            .put("hlg10_profile_id", profile?.id ?: "unknown")
            .put("hlg10_output_width", outputSize.width)
            .put("hlg10_output_height", outputSize.height)
            .put("hlg10_aspect_ratio", profile?.aspectLabel ?: aspectRatioLabel(outputSize))
            .put("hlg10_profile_status", profile?.status?.name ?: "UNKNOWN")
            .put("sensor_capture_rotation_degrees", captureRotationDegrees(currentOption()!!.characteristics))
            .put("rotation_degrees", PURE10_CONTAINER_ROTATION_DEGREES)
            .put("requested_frame_duration_ns", sessionRequestedFrameDurationNs)
            .put("recording_exposure_policy", sessionExposurePolicy)
            .put("auto_cadence_lock", sessionAutoCadenceLock)
            .put("lens_shading_correction", "camera2-capture-start-cached")
    }

    private fun captureMetadataContext(size: Size): CaptureMetadataContext {
        val option = currentOption() ?: error("No selected camera")
        return CaptureMetadataContext(
            cameraLabel = option.label,
            cameraId = option.physicalId ?: option.openId,
            characteristics = option.characteristics,
            captureSize = size,
            previewSize = currentPreviewSize(),
            frameRate = selectedFps,
            audioEnabled = audioEnabled,
            displayRotationDegrees = recordingDisplayRotationDegrees,
            detectedDeviceOrientationDegrees = detectedDeviceOrientationDegrees,
            uiContentRotationDegrees = appliedContentRotation.roundToInt(),
            captureRotationDegrees = captureRotationDegrees(option.characteristics)
        )
    }

    private fun autoCadenceLockActive(): Boolean {
        val snapshot = lockedValues
        return CaptureCadencePolicy.shouldFreezeAutoExposureForRawRecording(
            includeRaw = true,
            fps = selectedFps,
            manualExposure = manualExposure,
            snapshotIso = snapshot?.iso,
            snapshotExposureNs = snapshot?.exposureNs
        )
    }

    private fun recordingExposurePolicyName(): String {
        return CaptureCadencePolicy.exposurePolicyName(
            manualExposure = manualExposure,
            explicitExposureLock = lockExposure && lockedValues?.iso != null &&
                lockedValues?.exposureNs != null,
            autoCadenceLock = autoCadenceLockActive()
        )
    }

    private fun updatePure10Description(directory: File, summary: Pure10Summary?) {
        val descriptionFile = File(directory, "clip.json")
        val description = runCatching { JSONObject(descriptionFile.readText()) }
            .getOrElse { JSONObject() }
        description.put("finalized", summary?.finalized == true)
        description.put("video_bytes", File(directory, Pure10Encoder.VIDEO_FILE).length())
        description.put("saved_frames", savedFrames)
        description.put("dropped_frames", droppedFrames)
        description.put("cadence_gaps", cadenceGaps)
        description.put("peak_pending_writes", peakPendingWrites.get())
        description.put("no_metadata_drops", noMetadataDrops)
        description.put("writer_busy_drops", writerBusyDrops)
        description.put("write_error_drops", writeErrorDrops)
        description.put("requested_frame_duration_ns", sessionRequestedFrameDurationNs)
        description.put("recording_exposure_policy", sessionExposurePolicy)
        description.put("auto_cadence_lock", sessionAutoCadenceLock)
        description.put("capture_interval_count", captureIntervalCount)
        description.put("capture_long_intervals", captureLongIntervals)
        if (captureIntervalCount > 0) {
            description.put("capture_interval_average_ns", captureIntervalTotalNs / captureIntervalCount)
            description.put("capture_interval_min_ns", captureIntervalMinNs)
            description.put("capture_interval_max_ns", captureIntervalMaxNs)
            description.put(
                "capture_effective_fps",
                1_000_000_000.0 / (captureIntervalTotalNs.toDouble() / captureIntervalCount)
            )
        } else {
            description.put("capture_interval_average_ns", JSONObject.NULL)
            description.put("capture_interval_min_ns", JSONObject.NULL)
            description.put("capture_interval_max_ns", JSONObject.NULL)
            description.put("capture_effective_fps", JSONObject.NULL)
        }
        if (summary != null) {
            description.put("submitted_frames", summary.submittedFrames)
            description.put("encoded_frames", summary.encodedFrames)
            description.put("encoder_drops", summary.encoderDrops)
            description.put("queue_average_ms", summary.queueAverageMs)
            description.put("queue_max_ms", summary.queueMaxMs)
            description.put("conversion_average_ms", summary.conversionAverageMs)
            description.put("conversion_max_ms", summary.conversionMaxMs)
            description.put("processing_average_ms", summary.processingAverageMs)
            description.put("processing_max_ms", summary.processingMaxMs)
            description.put("encoder_first_timestamp_ns", summary.firstTimestampNs)
            description.put("encoder_last_timestamp_ns", summary.lastTimestampNs)
            description.put("capture_failure_drops", captureFailureDrops)
            summary.error?.let { description.put("encoder_error", it) }
        }
        runCatching { descriptionFile.writeText(description.toString(2)) }
            .onFailure { Log.e(TAG, "Could not update PURE 10 metadata", it) }
    }

    private fun schedulePure10AudioMuxIfReady(directory: File) {
        if (!isPure10AudioMuxReady(directory)) return
        val directoryKey = directory.absolutePath
        if (!pure10MuxQueuedDirectories.add(directoryKey)) return
        val inProgressFile = File(directory, Pure10AudioMuxer.MUX_IN_PROGRESS_FILE)
        runCatching { inProgressFile.writeText(System.currentTimeMillis().toString()) }
        logStopMeasurement("PURE10_AUDIO_MUX_START")
        runOnUiThread {
            updateControlModes()
            updateRecordingChrome()
            updateStatus(force = true)
        }
        postProcessExecutor.execute {
            val result = Pure10AudioMuxer.mux(directory)
            logStopMeasurement(
                "PURE10_AUDIO_MUX_DONE",
                "finalized=${result.finalized} final_bytes=${File(directory, "pure10_final.mp4").length()}"
            )
            if (!result.finalized) {
                Log.e(TAG, "PURE 10 audio mux failed: ${result.error}")
            }
            runCatching { inProgressFile.delete() }
            pure10MuxQueuedDirectories.remove(directoryKey)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                recordButton.isEnabled = cameraDevice != null && !switchingSession &&
                    !recording && !finalizingOutput && !finalizingAudio &&
                    rawVideoSupportedForCurrentCamera()
                updateControlModes()
                updateRecordingChrome()
                updateStatus(force = true)
            }
        }
    }

    private fun pure10MuxInProgress(): Boolean = pure10MuxQueuedDirectories.isNotEmpty()

    private fun isPure10AudioMuxReady(directory: File): Boolean {
        if (!audioEnabled) return false
        val clipFile = File(directory, "clip.json")
        val audioFile = File(directory, PcmAudioRecorder.AUDIO_FILE)
        val audioMetadataFile = File(directory, PcmAudioRecorder.AUDIO_METADATA_FILE)
        val syncFile = File(directory, "sync.json")
        val videoFile = File(directory, Pure10Encoder.VIDEO_FILE)
        if (!clipFile.isFile || !audioFile.isFile || !audioMetadataFile.isFile ||
            !syncFile.isFile || !videoFile.isFile || videoFile.length() <= 0L
        ) {
            return false
        }
        val clip = runCatching { JSONObject(clipFile.readText()) }.getOrNull() ?: return false
        if (clip.optString("format") != "rawlite-pure10") return false
        if (!clip.optBoolean("finalized", false)) return false
        if (clip.optBoolean(Pure10AudioMuxer.FINAL_VIDEO_HAS_AUDIO, false) &&
            File(directory, clip.optString("final_video_file", "")).isFile
        ) {
            return false
        }
        val audio = runCatching { JSONObject(audioMetadataFile.readText()) }.getOrNull() ?: return false
        return audio.optBoolean("finalized", false)
    }

    private fun captureRotationDegrees(chars: CameraCharacteristics): Int {
        val sensorDegrees = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val frontFacing = chars.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        return CaptureMath.rotationDegrees(
            sensorDegrees,
            recordingDisplayRotationDegrees,
            frontFacing
        )
    }

    private fun snapshotCurrentValues(): LockSnapshot {
        val result = latestResult
        return LockSnapshot(
            iso = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: isoValue,
            exposureNs = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: exposureNs,
            gains = result?.get(CaptureResult.COLOR_CORRECTION_GAINS),
            transform = result?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM),
            focusDiopters = result?.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: focusDiopters
        )
    }

    private fun beginPreviewCadenceMetering() {
        previewTimingValues = null
        previewMeteringFramesRemaining =
            if (!recording && selectedFps < 30 && !manualExposure) PREVIEW_METERING_FRAMES else 0
    }

    private fun updatePreviewCadenceMetering() {
        if (recording || selectedFps >= 30 || manualExposure || previewTimingValues != null) return
        if (previewMeteringFramesRemaining <= 0) return
        previewMeteringFramesRemaining--
        if (previewMeteringFramesRemaining == 0) {
            previewTimingValues = snapshotCurrentValues()
            runOnUiThread { updateControlModes() }
            cameraHandler.post { startRepeating(includeRaw = false) }
        }
    }

    private fun restartRepeating() {
        if (switchingSession || captureSession == null) return
        cameraHandler.post { startRepeating(recording) }
    }

    private fun closeCamera(preservePendingOpen: Boolean = false) {
        ++recordingStartToken
        sessionGeneration++
        pendingSessionRequest = null
        activeRepeatingBuilder = null
        if (!preservePendingOpen) pendingCameraTexture = null
        switchingSession = false
        if (::preview.isInitialized) {
            TechnicalShaderManager.clear(preview)
        }
        recording = false
        captureStopPending = false
        closeCameraAfterSession = true
        val activeSession = captureSession
        captureSession = null
        if (activeSession != null) {
            if (closingSession == null) closingSession = activeSession
            activeSession.close()
            return
        }
        if (closingSession != null) return
        closeCameraAfterSession = false
        finishCameraClose()
    }

    private fun finishCameraClose() {
        cameraDevice?.close()
        cameraDevice = null
        clearPendingRawImages()
        val readerToClose = rawReader
        rawReader = null
        analysisReader?.close()
        analysisReader = null
        previewSurface?.release()
        previewSurface = null
        resultsByTimestamp.clear()
        latestResult = null

        // Acquired RAW Images are deliberately processed on ioExecutor. Closing their
        // ImageReader here invalidates the direct ByteBuffers while the native Bayer ->
        // P010 conversion may still be reading them (for example when the app goes to the
        // background during a take). Queue the close behind every submitted frame instead.
        // The field is cleared immediately so a resumed camera can create a fresh reader.
        if (readerToClose != null) {
            runCatching {
                ioExecutor.execute { readerToClose.close() }
            }.onFailure {
                // onDestroy can shut the executor down before the asynchronous camera
                // onClosed callback reaches this method. Never invalidate an acquired Image
                // in that race: wait for the accepted work to drain on a disposable thread.
                Thread({
                    val drained = runCatching {
                        ioExecutor.awaitTermination(READER_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    }.getOrDefault(false)
                    if (drained) {
                        readerToClose.close()
                    } else {
                        Log.w(TAG, "Leaving RAW reader to process teardown after I/O drain timeout")
                    }
                }, "RawLiteReaderClose").start()
            }
        }
    }

    private fun closeCameraForBackground(finalizeCapture: Boolean) {
        recording = false
        switchingSession = false
        if (!finalizeCapture) captureStopPending = false
        if (finalizeCapture) finishAudioCapture()
        lockedValues = null
        resultsByTimestamp.clear()

        val waitsForSessionClose = captureSession != null || closingSession != null
        if (finalizeCapture && waitsForSessionClose) {
            finalizeCaptureAfterSessionClose = true
        }
        closeCamera()

        val cameraBarrier = CountDownLatch(1)
        if (::cameraThread.isInitialized) {
            cameraHandler.post {
                clearPendingRawImages()
                cameraBarrier.countDown()
            }
            cameraBarrier.await(BACKGROUND_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }

        try {
            ioExecutor.submit {}.get(BACKGROUND_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            Log.w(TAG, "Timed out while draining camera writes", error)
        }
        if (finalizeCapture && !waitsForSessionClose) {
            finishPackedOutputs()
            finishTelemetry()
        }
    }

    private fun chooseCoolerFpsRange(): Range<Int>? {
        val ranges = currentOption()?.capability?.controls?.targetFpsRanges
            ?.map { Range(it.minimum.roundToInt(), it.maximum.roundToInt()) }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val target = selectedFps
        if (target < 30) {
            ranges.firstOrNull { it.lower == 15 && it.upper == 30 }?.let { return it }
        }
        return ranges
            .filter { it.lower <= target && it.upper >= target }
            .minWithOrNull(compareBy<Range<Int>> { it.upper - it.lower }.thenBy { it.upper })
            ?: ranges.minByOrNull { kotlin.math.abs(it.upper - target) }
    }

    private fun updateControlModes() {
        val deviceControls = currentAvailableControls()
        exposureControls.mode?.text = when {
            manualExposure -> "MAN"
            !recording && previewTimingValues != null -> "A-L"
            else -> "AUTO"
        }
        wbControls.mode?.text = if (manualWb) "MAN" else "AUTO"
        focusControls.mode?.text = if (manualFocus) "MAN" else "AUTO"
        exposureControls.mode?.setTextColor(if (!manualExposure) RawLiteDesign.AUTO else Color.WHITE)
        wbControls.mode?.setTextColor(if (!manualWb) RawLiteDesign.AUTO else Color.WHITE)
        focusControls.mode?.setTextColor(if (!manualFocus) RawLiteDesign.AUTO else Color.WHITE)

        val operation = currentOperationState()
        val interactionEnabled = operation.interactionEnabled
        val exposureAdjustable = interactionEnabled && (!recording || !lockExposure)
        val wbAdjustable = interactionEnabled && (!recording || !lockWhiteBalance)
        val focusAdjustable = interactionEnabled && (!recording || !lockFocus)
        exposureControls.seek.isEnabled = deviceControls.manualExposure && manualExposure && exposureAdjustable
        exposureControls.minus.isEnabled = deviceControls.manualExposure && manualExposure && exposureAdjustable
        exposureControls.plus.isEnabled = deviceControls.manualExposure && manualExposure && exposureAdjustable
        isoControls.seek.isEnabled = deviceControls.manualExposure && manualExposure && exposureAdjustable
        isoControls.minus.isEnabled = deviceControls.manualExposure && manualExposure && exposureAdjustable
        isoControls.plus.isEnabled = deviceControls.manualExposure && manualExposure && exposureAdjustable

        val autoExposure = !manualExposure
        evControls.seek.isEnabled = autoExposure && exposureAdjustable
        evControls.minus.isEnabled = autoExposure && exposureAdjustable
        evControls.plus.isEnabled = autoExposure && exposureAdjustable

        gainControls.seek.isEnabled = exposureAdjustable
        gainControls.minus.isEnabled = exposureAdjustable
        gainControls.plus.isEnabled = exposureAdjustable

        wbControls.seek.isEnabled = deviceControls.manualWhiteBalance && manualWb && wbAdjustable
        wbControls.minus.isEnabled = deviceControls.manualWhiteBalance && manualWb && wbAdjustable
        wbControls.plus.isEnabled = deviceControls.manualWhiteBalance && manualWb && wbAdjustable

        val canFocus = deviceControls.manualFocus
        focusControls.mode?.isEnabled = canFocus
        focusControls.seek.isEnabled = canFocus && manualFocus && focusAdjustable
        focusControls.minus.isEnabled = canFocus && manualFocus && focusAdjustable
        focusControls.plus.isEnabled = canFocus && manualFocus && focusAdjustable
        if (!canFocus) focusControls.mode?.text = "FIJO"

        val sessionControlsEnabled = operation.sessionControlsEnabled
        cameraSpinner.isEnabled = sessionControlsEnabled
        resolutionSpinner.isEnabled = sessionControlsEnabled
        previewSpinner.isEnabled = sessionControlsEnabled
        fpsSpinner.isEnabled = sessionControlsEnabled
        fpsSpinner.alpha = if (sessionControlsEnabled) 1f else 0.45f
        reviewButton.isEnabled = sessionControlsEnabled
        reviewButton.alpha = if (reviewButton.isEnabled) 1f else 0.45f
        technicalButton.isEnabled = sessionControlsEnabled
        technicalButton.alpha = if (sessionControlsEnabled) 1f else 0.45f
        microphoneButton.isEnabled = sessionControlsEnabled
        lockSwitch.isEnabled = !recording && !finalizingOutput &&
            !finalizingAudio && !pure10MuxInProgress()
        exposureControls.mode?.isEnabled = deviceControls.manualExposure && exposureAdjustable
        wbControls.mode?.isEnabled = deviceControls.manualWhiteBalance && wbAdjustable
        if (canFocus) focusControls.mode?.isEnabled = focusAdjustable
        wheelView.isEnabled = interactionEnabled && selectedParameterCanChangeNow()
        decreaseButton.isEnabled = interactionEnabled && selectedParameterCanChangeNow()
        increaseButton.isEnabled = interactionEnabled && selectedParameterCanChangeNow()
        selectorStrip.isEnabled = interactionEnabled
        selectorButtons.values.forEach {
            it.isEnabled = interactionEnabled
            it.alpha = if (interactionEnabled) 1f else 0.62f
        }
        if (::cameraChrome.isInitialized) {
            cameraChrome.controlsEnabled = interactionEnabled
            cameraChrome.selectedValueEnabled = selectedParameterCanChangeNow()
            cameraChrome.pushAutoEnabled = pushAutoCanRunNow()
        }
        updateChromeValues()
        updateRecordingChrome()
    }

    private fun updateAllControlValues() {
        exposureControls.value.text = formatExposure(exposureNs)
        isoControls.value.text = isoValue.toString()
        evControls.value.text = formatEv()
        gainControls.value.text = String.format(Locale.US, "%.2f×", postRawGain / 100f)
        wbControls.value.text = "$kelvin K"
        focusControls.value.text = formatFocus(focusDiopters)
        updateChromeValues()
    }

    private fun updateExposureFromProgress(progress: Int) {
        exposureNs = shutterValueForProgress(progress)
        exposureControls.value.text = formatExposure(exposureNs)
    }

    private fun updateIsoFromProgress(progress: Int) {
        isoValue = isoValueForProgress(progress)
        val snappedProgress = isoProgressFor(isoValue)
        if (::isoControls.isInitialized && isoControls.seek.progress != snappedProgress) {
            isoControls.seek.progress = snappedProgress
        }
        isoControls.value.text = isoValue.toString()
    }

    private fun updateEvFromProgress(progress: Int) {
        val range = evRange()
        evSteps = progressToLinear(progress, range.lower.toDouble(), range.upper.toDouble())
            .roundToInt()
            .coerceIn(range.lower, range.upper)
        evControls.value.text = formatEv()
    }

    private fun updateGainFromProgress(progress: Int) {
        val range = gainRange()
        postRawGain = progressToLinear(progress, range.lower.toDouble(), range.upper.toDouble())
            .roundToInt()
            .coerceIn(range.lower, range.upper)
        gainControls.value.text = String.format(Locale.US, "%.2f×", postRawGain / 100f)
    }

    private fun updateKelvinFromProgress(progress: Int) {
        manualWbGains = null
        manualWbTransform = null
        val raw = progressToLinear(progress, MIN_KELVIN.toDouble(), MAX_KELVIN.toDouble())
        kelvin = ((raw / 50.0).roundToInt() * 50).coerceIn(MIN_KELVIN, MAX_KELVIN)
        wbControls.value.text = "$kelvin K"
    }

    private fun updateFocusFromProgress(progress: Int) {
        val maxFocus = maximumFocusDistanceDiopters()
        focusDiopters = progressToFocusDiopters(progress, maxFocus)
        focusControls.value.text = formatFocus(focusDiopters)
    }

    private fun updateStatus(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastStatusUpdateMs < 400L) return
        lastStatusUpdateMs = now
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { updateStatus(force = true) }
            return
        }

        val option = currentOption() ?: return
        val size = currentResolutionSize()
        val measuredIso = latestResult?.get(CaptureResult.SENSOR_SENSITIVITY)
        val measuredExposure = latestResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val measuredFrameDuration = latestResult?.get(CaptureResult.SENSOR_FRAME_DURATION)
        val state = when (currentCaptureUiState()) {
            CaptureUiState.READY -> "LISTO"
            CaptureUiState.PREPARING -> "PREPARANDO"
            CaptureUiState.RECORDING -> "REC"
            CaptureUiState.SAVING -> "GUARDANDO"
        }
        if (recording) {
            val elapsed = ((SystemClock.elapsedRealtime() - sessionStartElapsedMs) / 1000L)
                .coerceAtLeast(0L)
            recordTimerText = String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60)
            if (::cameraChrome.isInitialized) cameraChrome.timerText = recordTimerText
        }
        updateChromeValues()
        updateRecordingChrome()
        val captureInfo = if (recording) {
            val elapsed = ((SystemClock.elapsedRealtime() - sessionStartElapsedMs) / 1000L)
                .coerceAtLeast(0L)
            " · ${String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60)}" +
                " · $savedFrames guardados · $droppedFrames omitidos" +
                (if (cadenceGaps > 0) " · $cadenceGaps huecos" else "") +
                (if (audioRecorder != null) " · AUDIO" else " · SIN AUDIO")
        } else {
            ""
        }
        val measured = if (measuredIso != null && measuredExposure != null) {
            val actualFps = measuredFrameDuration
                ?.takeIf { it > 0L }
                ?.let { String.format(Locale.US, "%.1f", 1_000_000_000.0 / it) }
            "\nISO $measuredIso · ${formatExposure(measuredExposure)}" +
                (actualFps?.let { " · $it real" } ?: "") +
                " · ${if (manualWb) "$kelvin K" else "WB auto"}"
        } else {
            ""
        }
        val estimatedRate = estimatedRecordingBytesPerSecond()
        val remainingSeconds = if (estimatedRate > 0L) {
            (storageAvailableBytes - MIN_FREE_BYTES).coerceAtLeast(0L) / estimatedRate
        } else {
            0L
        }
        val deviceInfo = buildString {
            append("\n")
            append(batteryTemperatureC?.let { String.format(Locale.US, "%.1f°C", it) } ?: "—°C")
            append(" · T")
            append(thermalStatus)
            thermalHeadroom?.let {
                append(" H")
                append(String.format(Locale.US, "%.2f", it))
            }
            append(" · ")
            append(formatStorage(storageAvailableBytes))
            if (remainingSeconds > 0L) {
                append(" · ")
                append(formatRemainingTime(remainingSeconds))
            }
        }
        val previewSize = currentPreviewSize()
        val captureLabel = if (captureMode == CaptureMode.PURE10) "HLG 10" else "RAW"
        val rawAvailability = if (rawVideoSupportedForCurrentCamera()) {
            captureLabel
        } else {
            "PREVIEW · CAPTURA NO VIABLE"
        }
        status.text = "$state · ${option.label} · $rawAvailability ${size?.width}×${size?.height}" +
            " · $selectedFps FPS" +
            " · PREV ${previewSize.width}×${previewSize.height}$captureInfo$measured$deviceInfo"
    }

    private fun estimatedRecordingBytesPerSecond(): Long {
        if (captureMode == CaptureMode.PURE10) {
            val audioBytesPerSecond = if (audioEnabled) 48_000L * 2L * 3L else 0L
            return Pure10Encoder.BIT_RATE / 8L + audioBytesPerSecond
        }
        val size = currentRawSize() ?: return 0L
        val bytesPerFrame = if (selectedRawIndex > 0) {
            ((size.width.toLong() * 10L + 7L) / 8L) * size.height
        } else {
            size.width.toLong() * size.height * 2L
        }
        val audioBytesPerSecond = if (audioEnabled) 48_000L * 2L * 3L else 0L
        return bytesPerFrame * selectedFps + audioBytesPerSecond
    }

    private fun currentOperationState(): CaptureOperationState = CaptureOperationState(
        recording = recording,
        switchingSession = switchingSession,
        finalizingOutput = finalizingOutput,
        finalizingAudio = finalizingAudio
    )

    private fun currentCaptureUiState(): CaptureUiState = when {
        recording -> CaptureUiState.RECORDING
        captureStopPending || finalizingOutput || finalizingAudio -> {
            CaptureUiState.SAVING
        }
        switchingSession -> CaptureUiState.PREPARING
        else -> CaptureUiState.READY
    }

    private fun formatStorage(bytes: Long): String {
        if (bytes <= 0L) return "— GB"
        return String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    }

    private fun formatRemainingTime(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d h", hours, minutes)
        } else {
            String.format(Locale.US, "%d min", minutes)
        }
    }

    private fun setStatusText(message: String) {
        val showMessage = {
            status.text = message
            status.visibility = View.VISIBLE
            telemetryHandler.removeCallbacks(hideStatusRunnable)
            telemetryHandler.postDelayed(hideStatusRunnable, 3_500L)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) showMessage() else runOnUiThread { showMessage() }
    }

    private fun gainsForKelvin(value: Int): RggbChannelVector {
        val k = value.coerceIn(MIN_KELVIN, MAX_KELVIN).toFloat()
        val red: Float
        val blue: Float
        if (k <= 6500f) {
            val t = (k - 2000f) / 4500f
            red = 1.05f + 1.15f * t
            blue = 3.65f - 2.25f * t
        } else {
            val t = (k - 6500f) / 3500f
            red = 2.20f + 0.85f * t
            blue = 1.40f - 0.35f * t
        }
        return RggbChannelVector(red, 1f, 1f, blue)
    }

    private fun estimateKelvin(gains: RggbChannelVector): Int {
        val green = ((gains.greenEven + gains.greenOdd) / 2f).coerceAtLeast(0.01f)
        val measuredRed = gains.red / green
        val measuredBlue = gains.blue / green
        var bestKelvin = kelvin
        var bestError = Float.MAX_VALUE
        for (candidate in MIN_KELVIN..MAX_KELVIN step 50) {
            val expected = gainsForKelvin(candidate)
            val error = abs(expected.red - measuredRed) + abs(expected.blue - measuredBlue)
            if (error < bestError) {
                bestError = error
                bestKelvin = candidate
            }
        }
        return bestKelvin
    }

    private fun latestColorTransform(): ColorSpaceTransform? {
        return latestResult?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
    }

    private fun currentOption(): CameraOption? = cameraOptions.getOrNull(selectedCameraIndex)
    private fun currentCharacteristics(): CameraCharacteristics? = currentOption()?.characteristics
    private fun maximumFocusDistanceDiopters(): Float =
        currentOption()?.capability?.lens?.minimumFocusDistanceDiopters ?: 0f

    private fun currentAvailableControls(): AvailableCameraControls {
        val option = currentOption() ?: return AvailableCameraControls()
        if (!::deviceCapabilities.isInitialized) return AvailableCameraControls()
        return deviceCapabilities.controls(option.openId, option.physicalId)
    }

    private fun currentPure10Availability(): Boolean {
        val option = currentOption() ?: return false
        if (!::deviceCapabilities.isInitialized) return false
        return deviceCapabilities.feature(
            CaptureFeature.PURE10,
            option.openId,
            option.physicalId
        ).available && availableHlg10Profiles().isNotEmpty()
    }

    private fun currentDisplayRotationDegrees(): Int {
        @Suppress("DEPRECATION")
        return when (preview.display?.rotation ?: windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun rawVideoSupportedForCurrentCamera(): Boolean =
        currentCharacteristics()?.get(CameraCharacteristics.LENS_FACING) !=
            CameraCharacteristics.LENS_FACING_FRONT

    private fun visualDisplayRotationDegrees(): Int {
        val base = baseContentOrientation ?: currentDisplayRotationDegrees()
        return (base - appliedContentRotation.roundToInt() + 360) % 360
    }

    private fun orientationCompensationWithHysteresis(orientation: Int): Int? {
        val allowedLandscape = allowedLandscapeOrientationDegrees()
        val oppositeLandscape = if (allowedLandscape == 90) 270 else 90
        val upright = orientation <= 30 || orientation >= 330
        val allowed = if (allowedLandscape == 90) {
            orientation in 60..120
        } else {
            orientation in 240..300
        }
        val opposite = if (oppositeLandscape == 90) {
            orientation in 60..120
        } else {
            orientation in 240..300
        }
        val upsideDown = orientation in 120..240
        return when (detectedDeviceOrientationDegrees) {
            0 -> when {
                allowed -> allowedLandscape
                opposite || upsideDown -> null
                else -> 0
            }
            allowedLandscape -> when {
                upright -> 0
                allowed -> allowedLandscape
                opposite || upsideDown -> null
                else -> allowedLandscape
            }
            else -> when {
                upright -> 0
                allowed -> allowedLandscape
                else -> null
            }
        }
    }

    private fun allowedLandscapeOrientationDegrees(): Int =
        if (leftHandedMode) 90 else 270

    private fun applyLatestAutomaticRotation() {
        if (rotationLockedByUser || recording || switchingSession ||
            !::cameraChrome.isInitialized
        ) {
            return
        }
        appliedContentRotation = lastAllowedContentRotation
        cameraChrome.animateContentRotation(lastAllowedContentRotation)
        settingsDialogFrame?.contentRotationDegrees = lastAllowedContentRotation
    }

    private fun registerStabilizerSensor() {
        if (!::sensorManager.isInitialized) return
        val sensor = stabilizerSensor
        if (sensor == null) {
            if (::cameraChrome.isInitialized) cameraChrome.stabilizerAvailable = false
            return
        }
        sensorManager.registerListener(
            stabilizerListener,
            sensor,
            SensorManager.SENSOR_DELAY_UI
        )
        if (::cameraChrome.isInitialized) cameraChrome.stabilizerAvailable = true
    }

    private fun unregisterStabilizerSensor() {
        if (!::sensorManager.isInitialized) return
        sensorManager.unregisterListener(stabilizerListener)
        stabilizerGravityInitialized = false
        if (::cameraChrome.isInitialized) {
            cameraChrome.stabilizerAvailable = false
            cameraChrome.stabilizerStable = false
        }
    }

    private fun updateStabilizerGuide() {
        if (!stabilizerGravityInitialized || !::cameraChrome.isInitialized) return
        val x = stabilizerGravity[0]
        val y = stabilizerGravity[1]
        val screenGravity = when (@Suppress("DEPRECATION") windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> -y to x
            Surface.ROTATION_180 -> -x to -y
            Surface.ROTATION_270 -> y to -x
            else -> x to y
        }
        val screenX = screenGravity.first
        val screenY = screenGravity.second
        val projectedGravity = hypot(screenX, screenY)
        if (projectedGravity < STABILIZER_MIN_PROJECTED_GRAVITY) {
            cameraChrome.stabilizerAvailable = false
            cameraChrome.stabilizerStable = false
            return
        }
        val rollDegrees = Math.toDegrees(
            atan2(screenX.toDouble(), (-screenY).toDouble())
        ).toFloat().normalizeDegrees()
        val leveledRollDegrees = rollDegrees.normalizeQuarterTurnDeviation()
        val stable = abs(leveledRollDegrees) <= STABILIZER_LEVEL_TOLERANCE_DEGREES
        cameraChrome.stabilizerAvailable = true
        cameraChrome.stabilizerRollDegrees = rollDegrees
        cameraChrome.stabilizerStable = stable
    }

    private fun Float.normalizeDegrees(): Float {
        var value = this
        while (value > 180f) value -= 360f
        while (value < -180f) value += 360f
        return value
    }

    private fun Float.normalizeQuarterTurnDeviation(): Float {
        var value = normalizeDegrees()
        while (value > 45f) value -= 90f
        while (value < -45f) value += 90f
        return value
    }

    private fun availableHlg10Profiles(): List<Hlg10OutputProfile> {
        val raw10Sizes = currentCharacteristics()
            ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW10)
            ?.toList()
            .orEmpty()
        if (raw10Sizes.isEmpty()) return emptyList()
        return CAPTURE_PROFILE.hlg10OutputProfiles.filter { profile ->
            profile.width <= CAPTURE_PROFILE.pure10MaximumWidth &&
                profile.height <= CAPTURE_PROFILE.pure10MaximumHeight &&
                raw10Sizes.any { source ->
                    source.width >= profile.width && source.height >= profile.height
                }
        }
    }

    private fun currentHlg10Profile(): Hlg10OutputProfile? =
        availableHlg10Profiles().getOrNull(selectedHlg10Index)

    private fun currentResolutionIndex(): Int = if (captureMode == CaptureMode.PURE10) {
        selectedHlg10Index
    } else {
        selectedRawIndex
    }

    private fun currentResolutionCount(): Int = if (captureMode == CaptureMode.PURE10) {
        availableHlg10Profiles().size
    } else {
        currentOption()?.rawSizes?.size ?: 0
    }

    private fun setCurrentResolutionIndex(index: Int) {
        if (captureMode == CaptureMode.PURE10) {
            selectedHlg10Index = index.coerceIn(0, (availableHlg10Profiles().size - 1).coerceAtLeast(0))
        } else {
            selectedRawIndex = index.coerceIn(0, ((currentOption()?.rawSizes?.size ?: 1) - 1))
        }
    }

    private fun resolutionLabelAt(index: Int): String? = if (captureMode == CaptureMode.PURE10) {
        availableHlg10Profiles().getOrNull(index)?.exactLabel
    } else {
        currentOption()?.rawSizes?.getOrNull(index)?.let(::resolutionDisplayLabel)
    }

    private fun currentResolutionLabel(): String =
        resolutionLabelAt(currentResolutionIndex()) ?: "—"

    private fun currentResolutionSize(): Size? = if (captureMode == CaptureMode.PURE10) {
        currentHlg10Profile()?.let { Size(it.width, it.height) }
    } else {
        currentRawSize()
    }

    private fun currentFpsOptions(): List<Int> = if (captureMode == CaptureMode.PURE10) {
        currentHlg10Profile()?.frameRates.orEmpty().ifEmpty { FPS_OPTIONS }
    } else {
        FPS_OPTIONS
    }

    private fun constrainFpsToCurrentResolution() {
        val options = currentFpsOptions()
        if (options.isEmpty()) return
        if (selectedFps !in options) {
            selectedFps = options.minBy { option -> abs(option - selectedFps) }
        }
        if (::fpsSpinner.isInitialized) {
            val previousSuppression = suppressSpinnerEvents
            suppressSpinnerEvents = true
            fpsSpinner.adapter = spinnerAdapter(options.map(Int::toString))
            fpsSpinner.setSelection(options.indexOf(selectedFps).coerceAtLeast(0))
            suppressSpinnerEvents = previousSuppression
        }
    }

    private fun refreshModeDependentSelectors(keepSuppressed: Boolean = false) {
        if (!::resolutionSpinner.isInitialized || !::fpsSpinner.isInitialized) return
        val previousSuppression = suppressSpinnerEvents
        suppressSpinnerEvents = true
        val labels = if (captureMode == CaptureMode.PURE10) {
            availableHlg10Profiles().map { profile ->
                "HLG 10 · ${profile.exactLabel} · ${profile.aspectLabel} · " +
                    hlg10StatusLabel(profile.status)
            }
        } else {
            currentOption()?.rawSizes.orEmpty().mapIndexed { index, size ->
                val format = if (index == 0) "CinemaDNG FULL" else "RAW10"
                "$format · ${resolutionDisplayLabel(size)} · ${aspectRatioLabel(size)}"
            }
        }
        resolutionSpinner.adapter = spinnerAdapter(labels)
        resolutionSpinner.setSelection(currentResolutionIndex().coerceAtLeast(0))
        constrainFpsToCurrentResolution()
        suppressSpinnerEvents = if (keepSuppressed) true else previousSuppression
    }

    private fun aspectRatioLabel(size: Size): String {
        val ratio = size.width.toDouble() / size.height.coerceAtLeast(1)
        return if (abs(ratio - 16.0 / 9.0) < 0.03) "16:9" else if (
            abs(ratio - 4.0 / 3.0) < 0.03
        ) {
            "4:3"
        } else {
            String.format(Locale.US, "%.2f:1", ratio)
        }
    }

    private fun hlg10StatusLabel(status: Hlg10OutputProfile.Status): String = when (status) {
        Hlg10OutputProfile.Status.VALIDATED -> "VALIDADO"
        Hlg10OutputProfile.Status.MEASURED -> "MEDIDO"
        Hlg10OutputProfile.Status.EXPERIMENTAL -> "EXPERIMENTAL"
    }

    private fun showCurrentResolutionStatus() {
        if (captureMode != CaptureMode.PURE10 || !::cameraChrome.isInitialized) return
        val profile = currentHlg10Profile() ?: return
        cameraChrome.showStatusPopup(
            "${profile.exactLabel} · ${profile.aspectLabel} · ${hlg10StatusLabel(profile.status)}"
        )
    }

    private fun currentRawSize(): Size? = currentOption()?.rawSizes?.getOrNull(selectedRawIndex)
    private fun currentCaptureRawSize(): Size? {
        val fallback = currentRawSize() ?: return null
        if (captureMode != CaptureMode.PURE10) return fallback
        val outputSize = currentResolutionSize() ?: return null
        return pure10SourceSize(outputSize, fallback)
    }

    private fun pure10SourceSize(outputSize: Size, fallback: Size): Size {
        val raw10Sizes = currentCharacteristics()
            ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW10)
            ?.toList()
            .orEmpty()
        return Pure10SourceSelector.select(raw10Sizes, outputSize, fallback)
    }

    private fun isPure10UhdSelected(): Boolean {
        if (captureMode != CaptureMode.PURE10) return false
        val profile = currentHlg10Profile() ?: return false
        return profile.width == 3840 && profile.height == 2160
    }

    private fun currentPreviewSize(): Size {
        return PREVIEW_SIZES.getOrElse(selectedPreviewIndex) { PREVIEW_SIZES.last() }
    }

    private fun previewCropRegion(): Rect? {
        val active = currentCharacteristics()
            ?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val previewSize = currentPreviewSize()
        val targetRatio = previewSize.width.toDouble() / previewSize.height
        val activeRatio = active.width().toDouble() / active.height()
        val cropWidth: Int
        val cropHeight: Int
        if (activeRatio > targetRatio) {
            cropHeight = active.height() and -2
            cropWidth = (cropHeight * targetRatio).roundToInt().coerceAtMost(active.width()) and -2
        } else {
            cropWidth = active.width() and -2
            cropHeight = (cropWidth / targetRatio).roundToInt().coerceAtMost(active.height()) and -2
        }
        val left = active.left + ((active.width() - cropWidth) / 2 and -2)
        val top = active.top + ((active.height() - cropHeight) / 2 and -2)
        return Rect(left, top, left + cropWidth, top + cropHeight)
    }

    private fun updateFormatHint() {
        if (!::formatHint.isInitialized) return
        formatHint.text = if (captureMode == CaptureMode.PURE10) {
            val profile = currentHlg10Profile()
            if (profile != null) {
                "HLG 10 · ${profile.exactLabel} · ${profile.aspectLabel} · " +
                    "${selectedFps} FPS · HEVC MAIN10 · " +
                    "${Pure10Encoder.BIT_RATE / 1_000_000} Mb/s · " +
                    hlg10StatusLabel(profile.status)
            } else {
                "HLG 10 no disponible"
            }
        } else if (selectedRawIndex > 0) {
            val size = currentRawSize()
            val megabytesPerSecond = size?.let {
                ((it.width.toLong() * it.height * 10L + 7L) / 8L) * selectedFps / 1_000_000.0
            }
            if (megabytesPerSecond != null) {
                String.format(
                    Locale.US,
                    "RAW10 · %s · %s · %.1f MB/s",
                    size?.let(::resolutionDisplayLabel) ?: "—",
                    size?.let(::aspectRatioLabel) ?: "—",
                    megabytesPerSecond
                )
            } else {
                "RAW10 secuencial"
            }
        } else {
            val size = currentRawSize()
            "CinemaDNG · ${size?.let(::resolutionDisplayLabel) ?: "—"} · " +
                "${size?.let(::aspectRatioLabel) ?: "—"}"
        }
    }

    private fun updateTechnicalOverlayState() {
        if (activeOverlayAlwaysOn || activeOverlayMomentary) {
            applyActiveOverlay(activeOverlay)
        } else {
            applyActiveOverlay(ActiveOverlay.NONE)
        }
    }

    private fun overlayForParameter(parameter: CameraParameter): ActiveOverlay? = when (parameter) {
        CameraParameter.FOCUS -> ActiveOverlay.FOCUS_PEAKING
        CameraParameter.SHUTTER -> ActiveOverlay.ZEBRAS
        CameraParameter.ISO -> ActiveOverlay.FALSE_COLOR
        else -> null
    }

    private fun parameterForOverlay(overlay: ActiveOverlay): CameraParameter? = when (overlay) {
        ActiveOverlay.FOCUS_PEAKING -> CameraParameter.FOCUS
        ActiveOverlay.ZEBRAS -> CameraParameter.SHUTTER
        ActiveOverlay.FALSE_COLOR -> CameraParameter.ISO
        ActiveOverlay.NONE -> null
    }

    private fun applyActiveOverlay(overlay: ActiveOverlay) {
        technicalMode = when (overlay) {
            ActiveOverlay.NONE -> TechnicalOverlayMode.OFF
            ActiveOverlay.FOCUS_PEAKING -> TechnicalOverlayMode.PEAKING
            ActiveOverlay.ZEBRAS -> TechnicalOverlayMode.ZEBRA
            ActiveOverlay.FALSE_COLOR -> TechnicalOverlayMode.FALSE_COLOR
        }
        if (::technicalOverlay.isInitialized) {
            technicalOverlay.activeOverlay = overlay
            if (overlay != ActiveOverlay.NONE) {
                technicalOverlay.clearRawHistogram()
            }
        }
        if (::preview.isInitialized) {
            if (overlay == ActiveOverlay.NONE) {
                TechnicalShaderManager.clear(preview)
            } else {
                TechnicalShaderManager.apply(preview, overlay)
            }
        }
        if (::cameraChrome.isInitialized) {
            val selectedParamOverlay = overlayForParameter(selectedParameter)
            cameraChrome.toolAlwaysOn = activeOverlayAlwaysOn && selectedParamOverlay == overlay
            cameraChrome.alwaysOnParameter = if (activeOverlayAlwaysOn && overlay != ActiveOverlay.NONE) {
                parameterForOverlay(overlay)
            } else {
                null
            }
            cameraChrome.invalidate()
        }
        if (::technicalButton.isInitialized) {
            technicalButton.text = when (overlay) {
                ActiveOverlay.NONE -> "TOOLS OFF"
                ActiveOverlay.FOCUS_PEAKING -> "PEAK"
                ActiveOverlay.ZEBRAS -> "ZEBRA"
                ActiveOverlay.FALSE_COLOR -> "FALSE"
            }
            technicalButton.setTextColor(
                if (overlay == ActiveOverlay.NONE) MUTED else 0xFF7CCEC6.toInt()
            )
        }
    }

    private fun handleManualAdjustmentStarted() {
        if (selectedParameter == CameraParameter.FOCUS) {
            if (activeOverlayAlwaysOn && activeOverlay != ActiveOverlay.FOCUS_PEAKING) {
                preFocusRestoreOverlay = activeOverlay
                setActiveOverlay(ActiveOverlay.FOCUS_PEAKING, alwaysOn = false, momentary = true)
            } else if (!activeOverlayAlwaysOn) {
                preFocusRestoreOverlay = null
                setActiveOverlay(ActiveOverlay.FOCUS_PEAKING, alwaysOn = false, momentary = true)
            }
        } else {
            if (!activeOverlayAlwaysOn) {
                preFocusRestoreOverlay = null
                val tool = overlayForParameter(selectedParameter)
                if (tool != null) {
                    setActiveOverlay(tool, alwaysOn = false, momentary = true)
                }
            }
        }
    }

    private fun handleManualAdjustmentReleased() {
        if (activeOverlayMomentary) {
            val restore = preFocusRestoreOverlay
            if (restore != null) {
                preFocusRestoreOverlay = null
                setActiveOverlay(restore, alwaysOn = true, momentary = false)
            } else {
                setActiveOverlay(ActiveOverlay.NONE, alwaysOn = false, momentary = false)
            }
        }
        scheduleControlApply(forceImmediate = true)
    }

    private fun setActiveOverlay(
        overlay: ActiveOverlay,
        alwaysOn: Boolean,
        momentary: Boolean
    ) {
        activeOverlay = overlay
        activeOverlayAlwaysOn = alwaysOn && overlay != ActiveOverlay.NONE
        activeOverlayMomentary = momentary && overlay != ActiveOverlay.NONE
        toolAlwaysOn = activeOverlayAlwaysOn
        toolMomentaryActive = activeOverlayMomentary
        applyActiveOverlay(activeOverlay)
    }

    private fun cycleTechnicalMode() {
        val nextOverlay = when (activeOverlay) {
            ActiveOverlay.NONE -> ActiveOverlay.FOCUS_PEAKING
            ActiveOverlay.FOCUS_PEAKING -> ActiveOverlay.ZEBRAS
            ActiveOverlay.ZEBRAS -> ActiveOverlay.FALSE_COLOR
            ActiveOverlay.FALSE_COLOR -> ActiveOverlay.NONE
        }
        setActiveOverlay(nextOverlay, alwaysOn = nextOverlay != ActiveOverlay.NONE, momentary = false)
    }

    private fun toggleMicrophone() {
        if (recording || switchingSession || finalizingAudio) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_PERMISSIONS)
            return
        }
        audioEnabled = !audioEnabled
        scheduleUserPreferencesSave()
        updateMicrophoneUi()
    }

    private fun updateMicrophoneUi() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { updateMicrophoneUi() }
            return
        }
        if (!::microphoneButton.isInitialized) return
        val permitted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val input = if (recording) activeAudioInput else availableAudioInput
        val label = when {
            !audioEnabled || !permitted -> "MUTE"
            recording && audioCaptureError -> "MIC ERROR"
            recording && input == null -> "MIC ?"
            input?.isExternal == true -> "MIC EXT"
            recording && availableAudioInput?.isExternal == true -> "MIC INT !"
            input?.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "MIC INT"
            else -> "MIC AUTO"
        }
        microphoneButton.text = label
        if (::cameraChrome.isInitialized) cameraChrome.audioInputLabel = label
        audioInputStatusText?.text = when {
            !audioEnabled || !permitted -> "Audio desactivado"
            recording && audioCaptureError -> "Audio interrumpido. Revisa la conexión del micrófono."
            recording -> "Entrada activa: ${input?.label ?: "verificando micrófono…"}" +
                if (availableAudioInput?.isExternal == true && input?.isExternal != true)
                    "\nExterno conectado: ${availableAudioInput?.label}; uso aún no confirmado." else ""
            else -> "Entrada automática: ${input?.label ?: "Micrófono del sistema"}"
        }
        microphoneButton.setTextColor(if (audioEnabled && permitted) Color.WHITE else MUTED)
        microphoneButton.alpha = if (recording || finalizingAudio || pure10MuxInProgress()) 0.55f else 1f
        if (!recording) {
            audioMeter.text = "----"
            audioMeter.setTextColor(MUTED)
        }
    }

    private fun updateRecordingChrome() {
        if (!::recordButton.isInitialized) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { updateRecordingChrome() }
            return
        }
        val readyForNextCapture = recordButton.isEnabled &&
            !recording && !switchingSession && !finalizingOutput &&
            !finalizingAudio
        if (readyForNextCapture) captureStopPending = false
        val uiState = currentCaptureUiState()
        recordButton.background = recordButtonBackground(recording)
        recordButton.contentDescription = when (uiState) {
            CaptureUiState.RECORDING -> "Detener grabación"
            CaptureUiState.SAVING -> "Guardando toma"
            CaptureUiState.PREPARING -> "Preparando cámara"
            CaptureUiState.READY -> "Grabar"
        }
        recordButton.alpha = if (!recordButton.isEnabled || switchingSession ||
            finalizingOutput || finalizingAudio
        ) 0.55f else 1f
        recordIndicator.visibility = if (recording) View.VISIBLE else View.GONE
        if (!recording) recordTimerText = "00:00"
        updateSystemRotationPolicy()
        if (::cameraChrome.isInitialized) {
            cameraChrome.recording = recording
            cameraChrome.saving = uiState == CaptureUiState.SAVING
            cameraChrome.rotationLocked = rotationLockedByUser || recording || switchingSession
            cameraChrome.rotationControlEnabled = !recording && !switchingSession
            cameraChrome.recordEnabled = recordButton.isEnabled &&
                !finalizingOutput && !finalizingAudio
            cameraChrome.selectedValueEnabled = selectedParameterCanChangeNow()
            cameraChrome.pushAutoEnabled = pushAutoCanRunNow()
            cameraChrome.timerText = if (recording) recordTimerText else "00:00"
            if (recording) cameraChrome.closeMenus()
        }
        if (readyForNextCapture && stopMeasureId > 0 && stopMeasureReadyLoggedFor != stopMeasureId) {
            stopMeasureReadyLoggedFor = stopMeasureId
            logStopMeasurement("REC_READY")
        }
    }

    private fun toggleRotationLock() {
        if (recording || switchingSession) return
        rotationLockedByUser = !rotationLockedByUser
        cameraChrome.rotationLocked = rotationLockedByUser
        cameraChrome.rotationControlEnabled = true
        cameraChrome.showStatusPopup(
            if (rotationLockedByUser) "GIRO FIJO" else "GIRO AUTO"
        )
        updateSystemRotationPolicy()
        applyLatestAutomaticRotation()
    }

    private fun updateSystemRotationPolicy() {
        val target = if (rotationLockedByUser || recording || switchingSession) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_USER
        }
        if (requestedOrientation != target) requestedOrientation = target
    }

    private fun updateAudioLevel(peak: Float) {
        audioPeak = peak.coerceIn(0f, 1f)
        runOnUiThread {
            if (!::audioMeter.isInitialized || !recording) return@runOnUiThread
            val bars = (audioPeak * 4f).roundToInt().coerceIn(0, 4)
            audioMeter.text = "#".repeat(bars).padEnd(4, '-')
            audioMeter.setTextColor(
                if (audioPeak >= 0.97f) RECORD else 0xFF7CCEC6.toInt()
            )
        }
    }

    private fun setParametersExpanded(expanded: Boolean) {
        parametersExpanded = expanded
        if (!::parametersPanel.isInitialized) return
        parametersPanel.visibility = if (expanded) View.VISIBLE else View.GONE
        panelToggle.text = if (expanded) "⌄" else "⌃"
        panelToggle.contentDescription = if (expanded) {
            "Ocultar parámetros"
        } else {
            "Mostrar parámetros"
        }
    }

    private fun updatePanelLayout(orientation: Int) {
        if (!::chromeLayer.isInitialized) return
        val width = chromeLayer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val height = chromeLayer.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        if (width <= 0 || height <= 0) return

        val landscape = width > height
        wheelView.vertical = landscape
        selectorStrip.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        precisionButtons.orientation = if (landscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        decreaseButton.text = if (landscape) "▲" else "‹"
        increaseButton.text = if (landscape) "▼" else "›"

        val canonicalWidth = if (landscape) 2556f else 1179f
        val canonicalHeight = if (landscape) 1179f else 2556f
        val scale = min(width / canonicalWidth, height / canonicalHeight)
        val offsetX = (width - canonicalWidth * scale) / 2f
        val offsetY = (height - canonicalHeight * scale) / 2f
        val previewCanonical = if (landscape) {
            // Full 4:3 sensor fitted under the top selector strip.
            RectF(279f, 0f, 1851f, 1179f)
        } else {
            // Full-width 3:4 sensor preview continues behind the translucent selector strip.
            RectF(0f, 279f, 1179f, 1851f)
        }
        val previewX = (offsetX + previewCanonical.left * scale).roundToInt()
        val previewY = (offsetY + previewCanonical.top * scale).roundToInt()
        val previewWidth = (previewCanonical.width() * scale).roundToInt()
        val previewHeight = (previewCanonical.height() * scale).roundToInt()
        placeChrome(preview, previewX, previewY, previewWidth, previewHeight)
        placeChrome(technicalOverlay, previewX, previewY, previewWidth, previewHeight)
        placeChrome(cameraChrome, 0, 0, width, height)
        syncWheelToSelectedParameter()
        preview.post { configurePreviewTransform(preview.width, preview.height) }
    }

    private fun placeChrome(view: View, x: Int, y: Int, width: Int, height: Int) {
        view.layoutParams = FrameLayout.LayoutParams(width.coerceAtLeast(1), height.coerceAtLeast(1))
            .apply {
                leftMargin = x
                topMargin = y
            }
    }

    private fun placeChild(parent: FrameLayout, view: View, x: Int, y: Int, width: Int, height: Int) {
        view.layoutParams = FrameLayout.LayoutParams(width.coerceAtLeast(1), height.coerceAtLeast(1))
            .apply {
                leftMargin = x
                topMargin = y
            }
        if (view.parent !== parent) {
            (view.parent as? ViewGroup)?.removeView(view)
            parent.addView(view)
        }
    }

    private fun setEqualSelectorSizes(horizontal: Boolean) {
        for (index in 0 until selectorStrip.childCount) {
            selectorStrip.getChildAt(index).layoutParams = if (horizontal) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
        }
    }

    private fun setEqualPrecisionSizes(horizontal: Boolean) {
        for (index in 0 until precisionButtons.childCount) {
            precisionButtons.getChildAt(index).layoutParams = if (horizontal) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
        }
    }

    private fun configurePreviewTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0 || !::preview.isInitialized) return
        val bufferSize = currentPreviewSize()
        @Suppress("DEPRECATION")
        val displayRotation = preview.display?.rotation ?: windowManager.defaultDisplay.rotation
        val displayDegrees = currentDisplayRotationDegrees()
        Log.d(
            TAG,
            "Preview transform display=${preview.display?.displayId} rotation=$displayDegrees " +
                "view=${viewWidth}x$viewHeight buffer=${bufferSize.width}x${bufferSize.height}"
        )
        val sensorDegrees = currentCharacteristics()
            ?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val frontFacing = currentCharacteristics()?.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        val sign = if (frontFacing) 1 else -1
        val relativeDegrees = (sensorDegrees - displayDegrees * sign + 360) % 360
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val halfWidth = viewRect.centerX()
        val halfHeight = viewRect.centerY()
        if (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270) {
            val bufferRect = RectF(0f, 0f, bufferSize.height.toFloat(), bufferSize.width.toFloat())
            bufferRect.offset(halfWidth - bufferRect.centerX(), halfHeight - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(
                viewHeight.toFloat() / bufferSize.height,
                viewWidth.toFloat() / bufferSize.width
            )
            matrix.postScale(scale, scale, halfWidth, halfHeight)
            val quarterTurn = if (displayRotation == Surface.ROTATION_90) -90f else 90f
            matrix.postRotate(quarterTurn, halfWidth, halfHeight)
        } else if (displayRotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, halfWidth, halfHeight)
        }
        preview.setTransform(matrix)
        if (::technicalOverlay.isInitialized) technicalOverlay.setGeometry(relativeDegrees)
    }

    private fun cameraErrorLabel(error: Int): String {
        return when (error) {
            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "Cámara ocupada por otra aplicación"
            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Límite de cámaras activas"
            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "Cámara deshabilitada por Android"
            CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "Fallo del dispositivo de cámara"
            CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "Fallo del servicio de cámara"
            else -> "Error de cámara: $error"
        }
    }

    private fun isoRange(): Range<Int> {
        val range = currentOption()?.capability?.sensor?.sensitivityRange
        return if (range != null) {
            Range(range.minimum.roundToInt(), range.maximum.roundToInt())
        } else {
            Range(50, 3200)
        }
    }

    private fun isoValueForProgress(progress: Int): Int {
        val range = isoRange()
        return IsoStepPolicy.valueForProgress(progress, range.lower, range.upper)
    }

    private fun isoProgressFor(value: Int): Int {
        val range = isoRange()
        return IsoStepPolicy.progressFor(value, range.lower, range.upper)
    }

    private fun isoProgressStep(): Int {
        val range = isoRange()
        return IsoStepPolicy.stepProgress(range.lower, range.upper)
    }

    private fun exposureRange(): Range<Long> {
        val declared = currentOption()?.capability?.sensor?.exposureTimeRangeNs
        val hardware = if (declared != null) {
            Range(declared.minimum.toLong(), declared.maximum.toLong())
        } else {
            Range(100_000L, 1_000_000_000L)
        }
        val fastestVideoExposure = min(
            hardware.upper,
            max(hardware.lower, FASTEST_VIDEO_EXPOSURE_NS)
        )
        val frameDuration = 1_000_000_000L / selectedFps.coerceAtLeast(1)
        val slowestVideoExposure = min(
            hardware.upper,
            frameDuration * VIDEO_EXPOSURE_DUTY_PERCENT / 100L
        ).coerceAtLeast(fastestVideoExposure)
        return Range(fastestVideoExposure, slowestVideoExposure)
    }

    private fun shutterValuesNs(): List<Long> {
        val range = exposureRange()
        return buildList {
            add(range.lower)
            STANDARD_VIDEO_SHUTTER_DENOMINATORS.forEach { denominator ->
                val value = 1_000_000_000L / denominator
                if (value in range.lower..range.upper) add(value)
            }
            add(range.upper)
        }
            .sorted()
            .distinctBy(::formatExposure)
    }

    private fun nearestShutterIndex(value: Long, values: List<Long> = shutterValuesNs()): Int {
        if (values.isEmpty()) return 0
        return values.indices.minByOrNull { abs(values[it] - value) } ?: 0
    }

    private fun nearestShutterValue(value: Long): Long {
        val values = shutterValuesNs()
        return values[nearestShutterIndex(value, values)]
    }

    private fun shutterProgressFor(value: Long): Int {
        val values = shutterValuesNs()
        return discreteProgress(nearestShutterIndex(value, values), values.size)
    }

    private fun shutterValueForProgress(progress: Int): Long {
        val values = shutterValuesNs()
        return values[discreteIndex(progress, values.size)]
    }

    private fun constrainExposureToVideoRange() {
        exposureNs = nearestShutterValue(exposureNs)
        if (::exposureControls.isInitialized) {
            exposureControls.seek.progress = shutterProgressFor(exposureNs)
            exposureControls.value.text = formatExposure(exposureNs)
        }
    }

    private fun fallbackToFasterShutter(): Boolean {
        val values = shutterValuesNs()
        val current = nearestShutterIndex(exposureNs, values)
        if (current <= 0) return false
        exposureNs = values[current - 1]
        return true
    }

    private fun evRange(): Range<Int> {
        return currentCharacteristics()
            ?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            ?: Range(0, 0)
    }

    private fun gainRange(): Range<Int> {
        return currentCharacteristics()
            ?.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)
            ?: Range(100, 100)
    }

    private fun formatExposure(nanoseconds: Long): String {
        if (nanoseconds >= 500_000_000L) {
            return String.format(Locale.US, "%.2f s", nanoseconds / 1_000_000_000.0)
        }
        val denominator = (1_000_000_000.0 / nanoseconds).roundToInt().coerceAtLeast(1)
        return "1/$denominator"
    }

    private fun formatEv(): String {
        val step = currentCharacteristics()
            ?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            ?.toDouble() ?: 0.0
        val ev = evSteps * step
        return String.format(Locale.US, "%+.1f", ev)
    }

    private fun formatFocus(diopters: Float): String {
        if (diopters <= 0.001f) return "∞"
        val centimeters = 100f / diopters
        return if (centimeters >= 100f) {
            String.format(Locale.US, "%.1f m", centimeters / 100f)
        } else {
            "${centimeters.roundToInt()} cm"
        }
    }

    private fun progressToLog(progress: Int, min: Double, max: Double): Double {
        if (min <= 0.0 || max <= min) return min
        val t = progress.coerceIn(0, 1000) / 1000.0
        return exp(ln(min) + t * (ln(max) - ln(min)))
    }

    private fun logToProgress(value: Double, min: Double, max: Double): Int {
        if (min <= 0.0 || max <= min) return 0
        val t = (ln(value.coerceIn(min, max)) - ln(min)) / (ln(max) - ln(min))
        return (t * 1000).roundToInt().coerceIn(0, 1000)
    }

    private fun progressToLinear(progress: Int, min: Double, max: Double): Double {
        if (max <= min) return min
        return min + (progress.coerceIn(0, 1000) / 1000.0) * (max - min)
    }

    private fun linearToProgress(value: Double, min: Double, max: Double): Int {
        if (max <= min) return 0
        return (((value.coerceIn(min, max) - min) / (max - min)) * 1000)
            .roundToInt()
            .coerceIn(0, 1000)
    }

    private fun progressToFocusDiopters(progress: Int, maxFocus: Float): Float {
        if (maxFocus <= 0f) return 0f
        val t = progress.coerceIn(0, 1000) / 1000.0
        val t2 = t * t
        val t4 = t2 * t2
        return (maxFocus * t4).toFloat().coerceIn(0f, maxFocus)
    }

    private fun focusDioptersToProgress(diopters: Float, maxFocus: Float): Int {
        if (maxFocus <= 0f || diopters <= 0f) return 0
        val norm = (diopters / maxFocus).coerceIn(0f, 1f)
        val p = kotlin.math.sqrt(kotlin.math.sqrt(norm.toDouble()))
        return (p * 1000.0).roundToInt().coerceIn(0, 1000)
    }

    private fun darkSpinner(): Spinner {
        return Spinner(this).apply {
            setBackgroundColor(PANEL_FIELD)
            setPopupBackgroundDrawable(
                GradientDrawable().apply { setColor(0xFF20252B.toInt()) }
            )
        }
    }

    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    setPadding(dp(10), 0, dp(8), 0)
                }
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(0xFF20252B.toInt())
                    textSize = 13f
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                }
            }
        }
    }

    private fun label(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(MUTED)
            textSize = 10f
            typeface = cameraTypeface(Typeface.BOLD)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
    }

    private fun valueText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = cameraTypeface(Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
        }
    }

    private fun smallButton(text: String): Button {
        return Button(this).apply {
            this.text = text
            textSize = 10f
            setTextColor(Color.WHITE)
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            background = roundRect(0xFF252B31.toInt(), dp(4))
        }
    }

    private fun settingsSwitch(
        label: String,
        detail: String,
        checked: Boolean,
        onChanged: (Switch, Boolean) -> Unit
    ): View {
        val checkedState = intArrayOf(android.R.attr.state_checked)
        val defaultState = intArrayOf()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(0, dp(7), 0, dp(7))
        }
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        copy.addView(TextView(this).apply {
            text = label
            setTextColor(RawLiteDesign.TEXT)
            textSize = 12f
            typeface = cameraTypeface(Typeface.NORMAL)
            letterSpacing = 0.05f
        })
        copy.addView(TextView(this).apply {
            text = detail
            setTextColor(RawLiteDesign.TEXT_MUTED)
            textSize = 9f
            typeface = cameraTypeface(Typeface.NORMAL)
        })
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val control = Switch(this).apply {
            isChecked = checked
            showText = false
            text = ""
            minWidth = dp(56)
            thumbTintList = ColorStateList(
                arrayOf(checkedState, defaultState),
                intArrayOf(RawLiteDesign.BLACK, RawLiteDesign.TEXT)
            )
            trackTintList = ColorStateList(
                arrayOf(checkedState, defaultState),
                intArrayOf(RawLiteDesign.ACCENT, RawLiteDesign.SURFACE_ACTIVE)
            )
            setOnCheckedChangeListener { button, value ->
                onChanged(button as Switch, value)
            }
        }
        row.addView(control, LinearLayout.LayoutParams(dp(64), dp(48)))
        row.setOnClickListener { control.isChecked = !control.isChecked }
        return row
    }

    private fun settingsSectionHeading(label: String, detail: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(8))
            addView(TextView(this@MainActivity).apply {
                text = label
                setTextColor(RawLiteDesign.ACCENT)
                textSize = 10f
                typeface = cameraTypeface(Typeface.NORMAL)
                letterSpacing = 0.08f
            }, LinearLayout.LayoutParams(0, dp(28), 1f))
            addView(TextView(this@MainActivity).apply {
                text = detail
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setTextColor(RawLiteDesign.TEXT_DISABLED)
                textSize = 8f
                typeface = cameraTypeface(Typeface.NORMAL)
                letterSpacing = 0.06f
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)))
        }
    }

    private fun settingsInformationRow(label: String, value: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(TextView(this@MainActivity).apply {
                text = label
                setTextColor(RawLiteDesign.TEXT_MUTED)
                textSize = 10f
                typeface = cameraTypeface(Typeface.NORMAL)
                letterSpacing = 0.06f
            }, LinearLayout.LayoutParams(0, dp(32), 1f))
            addView(TextView(this@MainActivity).apply {
                text = value
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setTextColor(RawLiteDesign.TEXT)
                textSize = 10f
                typeface = cameraTypeface(Typeface.NORMAL)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)))
        }
    }

    private fun formatStorageBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
        bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
        else -> String.format(Locale.US, "%.1f KB", bytes / 1e3)
    }

    private fun settingsDivider(): View = View(this).apply {
        setBackgroundColor(RawLiteDesign.DIVIDER)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun settingsChoiceBackground(active: Boolean): GradientDrawable =
        GradientDrawable().apply {
            setColor(if (active) RawLiteDesign.SURFACE_ACTIVE else RawLiteDesign.SURFACE)
            setStroke(dp(1), if (active) RawLiteDesign.ACCENT else RawLiteDesign.OUTLINE)
            cornerRadius = dp(RawLiteDesign.RADIUS_SMALL_DP).toFloat()
        }

    private fun roundRect(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }
    }

    private fun space(width: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(width, 1)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun formatOne(value: Float): String = String.format(Locale.US, "%.1f", value)

    private fun beginStopMeasurement() {
        stopMeasureId += 1
        stopMeasureStartMs = SystemClock.elapsedRealtime()
        stopMeasureMode = when {
            pure10Recording -> "PURE10"
            packedRawRecording -> "RAW10"
            dngRecording -> "DNG"
            else -> activeCaptureMode.name
        }
        stopMeasureDir = sessionDir?.name.orEmpty()
        stopMeasureReadyLoggedFor = 0
        logStopMeasurement(
            "STOP_PRESSED",
            "frames=$savedFrames dropped=$droppedFrames pending=${pendingWrites.get()}"
        )
    }

    private fun logStopMeasurement(stage: String, detail: String = "") {
        if (stopMeasureId <= 0 || stopMeasureStartMs <= 0L) return
        val now = SystemClock.elapsedRealtime()
        val suffix = if (detail.isBlank()) "" else " $detail"
        Log.i(
            TAG,
            "RL_MEASURE id=$stopMeasureId mode=$stopMeasureMode dir=$stopMeasureDir " +
                "stage=$stage t=$now dt_ms=${now - stopMeasureStartMs}$suffix"
        )
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 10
        private const val TAG = "RawLite"
        private const val MIN_KELVIN = 2000
        private const val MAX_KELVIN = 10000
        private const val PREVIEW_METERING_FRAMES = 8
        private const val TELEMETRY_INTERVAL_MS = 2_000L
        private const val STABILIZER_MIN_PROJECTED_GRAVITY = 1.8f
        private const val STABILIZER_LEVEL_TOLERANCE_DEGREES = 1.8f
        private const val PREVIEW_METERING_REGION_FRACTION = 0.13f
        private const val PREVIEW_METERING_REGION_MIN_PX = 160
        private const val PREVIEW_FOCUS_TEMPORARY_HOLD_MS = 1_800L
        private val CAPTURE_PROFILE = Pixel6ReferenceProfile.capture
        private val CHECKPOINT_SECONDS = CAPTURE_PROFILE.checkpointSeconds
        private val MAX_PENDING_METADATA_IMAGES = CAPTURE_PROFILE.maximumPendingMetadataImages
        // These queue depths are part of the measured Pixel 6 reference profile. They are not
        // capability guesses and must remain stable until a different device profile is selected.
        private val PURE10_MAX_IMAGES = CAPTURE_PROFILE.pure10ImageReaderCapacity
        private val PURE10_MAX_PENDING_WRITES = CAPTURE_PROFILE.pure10MaximumPendingWrites
        private const val PURE10_CONTAINER_ROTATION_DEGREES = 0
        private val MIN_FREE_BYTES = CAPTURE_PROFILE.minimumFreeBytes
        private val BACKGROUND_DRAIN_TIMEOUT_MS = CAPTURE_PROFILE.backgroundDrainTimeoutMs
        private const val READER_CLOSE_TIMEOUT_SECONDS = 30L
        private const val FASTEST_VIDEO_EXPOSURE_NS = 125_000L
        private const val VIDEO_EXPOSURE_DUTY_PERCENT = 95L
        private const val BG = RawLiteDesign.BLACK
        private const val PANEL_FIELD = RawLiteDesign.SURFACE_RAISED
        private const val MUTED = RawLiteDesign.TEXT_MUTED
        private const val RECORD = RawLiteDesign.RECORD
        private const val ACCENT = RawLiteDesign.ACCENT
        private const val CHROME_REFRESH_MS = 100L
        private const val PREF_SAVE_DELAY_MS = 250L
        private const val UI_PREFS = "ui_preferences"
        private const val PREF_THIRDS_GRID = "thirds_grid"
        private const val PREF_HISTOGRAM_ENABLED = "histogram_enabled"
        private const val PREF_ACTIVE_OVERLAY = "active_overlay"
        private const val PREF_ACTIVE_OVERLAY_ALWAYS_ON = "active_overlay_always_on"
        private const val PREF_LEFT_HANDED = "left_handed"
        private const val PREF_CAMERA_INDEX = "camera_index"
        private const val PREF_RAW_INDEX = "raw_index"
        private const val PREF_HLG10_INDEX = "hlg10_index"
        private const val PREF_PREVIEW_INDEX = "preview_index"
        private const val PREF_FPS = "fps"
        private const val PREF_CAPTURE_MODE = "capture_mode"
        private const val PREF_SELECTED_PARAMETER = "selected_parameter"
        private const val PREF_LOCK_EXPOSURE = "lock_exposure"
        private const val PREF_LOCK_WB = "lock_white_balance"
        private const val PREF_LOCK_FOCUS = "lock_focus"
        private const val PREF_MANUAL_EXPOSURE = "manual_exposure"
        private const val PREF_MANUAL_WB = "manual_white_balance"
        private const val PREF_MANUAL_FOCUS = "manual_focus"
        private const val PREF_ISO = "iso"
        private const val PREF_EXPOSURE_NS = "exposure_ns"
        private const val PREF_EV_STEPS = "ev_steps"
        private const val PREF_POST_RAW_GAIN = "post_raw_gain"
        private const val PREF_KELVIN = "kelvin"
        private const val PREF_FOCUS_DIOPTERS = "focus_diopters"
        private const val PREF_AUDIO_ENABLED = "audio_enabled"
        private val FPS_OPTIONS = CAPTURE_PROFILE.frameRates
        private val PREVIEW_SIZES = CAPTURE_PROFILE.previewSizes.map { Size(it.width, it.height) }
        private val STANDARD_VIDEO_SHUTTER_DENOMINATORS = listOf(
            8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000,
            800, 640, 500, 400, 320, 250, 240, 200, 160, 125, 120, 100,
            96, 80, 60, 50, 48, 40, 32, 30, 25, 24, 20, 16, 15
        )

        private val IDENTITY_TRANSFORM = ColorSpaceTransform(
            arrayOf(
                Rational(1, 1), Rational(0, 1), Rational(0, 1),
                Rational(0, 1), Rational(1, 1), Rational(0, 1),
                Rational(0, 1), Rational(0, 1), Rational(1, 1)
            )
        )
    }
}
