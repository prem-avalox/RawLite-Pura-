package com.example.rawlite

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.text.TextUtils
import com.example.rawlite.gallery.ClipRepository
import com.example.rawlite.gallery.GalleryConversionEstimator
import com.example.rawlite.gallery.Raw10CinemaDngConverter
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

class ReviewActivity : Activity() {
    private enum class PlaybackKind { NONE, PURE10, RAW10, DNG }

    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var backButton: TextView
    private lateinit var selectButton: TextView
    private lateinit var moreButton: TextView
    private lateinit var content: FrameLayout
    private lateinit var selectionBar: LinearLayout
    private lateinit var conversionSelectionButton: TextView
    private lateinit var deleteSelectionButton: TextView
    private lateinit var ambientBackground: ImageView
    private val worker = Executors.newSingleThreadExecutor()
    private val sequenceWorker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private val clipRepository = ClipRepository()
    private val uiTypeface by lazy { resources.getFont(R.font.open_sans) }
    private var galleryClips: List<ClipSummary> = emptyList()
    private var selectionMode = false
    private val selectedClipPaths = linkedSetOf<String>()
    private var galleryScrollView: ScrollView? = null
    private var selectionAnchorPath: String? = null
    private var ambientBitmap: Bitmap? = null
    private val galleryBindings = linkedMapOf<String, GalleryCardBinding>()
    private var dragSelectionActive = false
    private var dragSelectionValue = true
    private var dragLastPath: String? = null
    private var selectionActionsAvailableAfterMs = 0L
    private val thumbnailCache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            if (size <= THUMBNAIL_CACHE_SIZE || eldest == null) return false
            if (eldest.value !== displayedBitmap && !eldest.value.isRecycled) eldest.value.recycle()
            return true
        }
    }

    private var playbackKind = PlaybackKind.NONE
    private var activeSummary: ClipSummary? = null
    private var activeRawClip: Raw10Clip? = null
    private var activeDngClip: DngSequenceClip? = null
    private var activeFrameIndex = 0
    private var requestedFrameIndex = 0
    private var displayedBitmap: Bitmap? = null
    private val frameBuffer = SequenceFrameBuffer<Bitmap>(FRAME_CACHE_SIZE) { bitmap ->
        if (bitmap !== displayedBitmap && !bitmap.isRecycled) bitmap.recycle()
    }
    private val pendingSequenceFrames = mutableSetOf<Int>()
    @Volatile
    private var sequenceDecodeEpoch = 0
    private var sequenceBuffering = false
    private var bufferingAnchorIndex = 0
    private var bufferingTargetFrames = 0
    private var bufferingResumePositionMs = 0
    private var videoPlayer: MediaPlayer? = null
    private var audioPlayer: PcmWavPlayer? = null
    private var videoPrepared = false
    private var playWhenReady = false
    private var playing = false
    private var muted = false
    private var audioOffsetMs = 0
    private var lastAudioDriftLogElapsedMs = 0L
    private var restorePositionMs: Int? = null
    private var restoreShouldPlay = false

    private var rawPlaybackStartElapsedMs = 0L
    private var rawPlaybackStartTimeline = 0
    private var lastVideoProgressMs = 0

    private var stage: FrameLayout? = null
    private var textureView: AspectRatioTextureView? = null
    private var sequenceView: ImageView? = null
    private var stageStatus: TextView? = null
    private var detailView: TextView? = null
    private var positionView: TextView? = null
    private var durationView: TextView? = null
    private var frameView: TextView? = null
    private var seekBar: SeekBar? = null
    private var playButton: ImageButton? = null
    private var audioButton: ImageButton? = null

    private data class GalleryCardBinding(
        val summary: ClipSummary,
        val root: View,
        val badge: TextView,
        val selectionRail: View
    )

    private val progressTick = object : Runnable {
        override fun run() {
            if (!playing) return
            when (playbackKind) {
                PlaybackKind.PURE10 -> updateVideoProgress()
                PlaybackKind.RAW10, PlaybackKind.DNG -> updateSequenceProgress()
                PlaybackKind.NONE -> Unit
            }
            mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = RawLiteDesign.BLACK
        window.navigationBarColor = RawLiteDesign.BLACK
        buildShell()
        showClipList()
    }

    override fun onStop() {
        pausePlayback()
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val summary = activeSummary
        if (summary == null) {
            showClipList()
            return
        }
        restorePositionMs = currentPositionMs()
        restoreShouldPlay = playing
        openClip(summary)
    }

    override fun onDestroy() {
        clearPlayback()
        worker.shutdownNow()
        sequenceWorker.shutdownNow()
        recycleThumbnails()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selectionMode) {
            exitSelectionMode()
        } else if (playbackKind != PlaybackKind.NONE) {
            showClipList()
        } else {
            super.onBackPressed()
        }
    }

    private fun buildShell() {
        val root = FrameLayout(this).apply { setBackgroundColor(RawLiteDesign.BLACK) }
        ambientBackground = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.18f
            setBackgroundColor(RawLiteDesign.BLACK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRenderEffect(
                    RenderEffect.createBlurEffect(54f, 54f, Shader.TileMode.CLAMP)
                )
            }
        }
        root.addView(
            ambientBackground,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val chrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xD9000000.toInt()) }
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(0xE6000000.toInt())
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(dp(8), insets.systemWindowInsetTop + dp(6), dp(8), dp(6))
                insets
            }
        }
        backButton = TextView(this).apply {
            text = "‹"
            textSize = 38f
            typeface = uiTypeface
            gravity = Gravity.CENTER
            setTextColor(RawLiteDesign.TEXT)
            contentDescription = "Volver"
            tooltipText = "Volver"
            setOnClickListener { onBackPressed() }
        }
        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        titleView = TextView(this).apply {
            text = "TOMAS"
            textSize = 16f
            typeface = uiTypeface
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(RawLiteDesign.TEXT)
            letterSpacing = 0.075f
        }
        subtitleView = TextView(this).apply {
            textSize = 9f
            typeface = uiTypeface
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(RawLiteDesign.TEXT_MUTED)
            letterSpacing = 0.08f
        }
        titleBlock.addView(titleView)
        titleBlock.addView(subtitleView)
        selectButton = TextView(this).apply {
            text = "SEL"
            textSize = 12f
            typeface = uiTypeface
            gravity = Gravity.CENTER
            setTextColor(RawLiteDesign.ACCENT)
            isClickable = true
            isFocusable = true
            background = selectableSurface()
            contentDescription = "Seleccionar tomas"
            tooltipText = "Seleccionar"
            setOnClickListener {
                if (galleryClips.isNotEmpty()) enterSelectionMode()
            }
        }
        moreButton = TextView(this).apply {
            text = "•••"
            textSize = 13f
            typeface = uiTypeface
            gravity = Gravity.CENTER
            setTextColor(RawLiteDesign.TEXT)
            isClickable = true
            isFocusable = true
            background = selectableSurface()
            contentDescription = "Más opciones"
            tooltipText = "Más opciones"
            setOnClickListener { showGalleryMenu() }
        }
        toolbar.addView(backButton, LinearLayout.LayoutParams(dp(52), dp(52)))
        toolbar.addView(titleBlock, LinearLayout.LayoutParams(0, dp(52), 1f))
        toolbar.addView(selectButton, LinearLayout.LayoutParams(dp(72), dp(44)))
        toolbar.addView(moreButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        chrome.addView(toolbar)
        chrome.addView(View(this).apply { setBackgroundColor(RawLiteDesign.DIVIDER) },
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1))

        selectionBar = buildSelectionBar().apply {
            visibility = View.GONE
            alpha = 0f
        }
        chrome.addView(selectionBar, LinearLayout.LayoutParams.MATCH_PARENT, dp(64))

        content = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        chrome.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(
            chrome,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
    }

    private fun showClipList() {
        clearPlayback()
        val currentGeneration = generation.incrementAndGet()
        selectedClipPaths.removeAll { selected ->
            galleryClips.none { it.directory.absolutePath == selected }
        }
        titleView.text = "TOMAS"
        subtitleView.text = "LEYENDO BIBLIOTECA"
        backButton.contentDescription = "Volver a la cámara"
        selectButton.visibility = View.GONE
        moreButton.visibility = View.GONE
        selectionBar.visibility = View.GONE
        selectionBar.alpha = 0f
        setContent(messageView("LEYENDO TOMAS"))
        worker.execute {
            val base = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            val clips = clipRepository.list(base)
            runOnUiThread {
                if (generation.get() == currentGeneration && !isFinishing) {
                    galleryClips = clips
                    pruneThumbnailCache(clips)
                    if (galleryClips.isEmpty()) {
                        selectionMode = false
                        selectedClipPaths.clear()
                        selectionAnchorPath = null
                    }
                    renderClipList(clips)
                }
            }
        }
    }

    private fun renderClipList(clips: List<ClipSummary>) {
        if (clips.isEmpty()) {
            selectButton.visibility = View.GONE
            moreButton.visibility = View.GONE
            subtitleView.text = "0 TOMAS"
            galleryScrollView = null
            galleryBindings.clear()
            setContent(messageView("NO HAY TOMAS"))
            return
        }
        val restoreScrollY = galleryScrollView?.scrollY ?: 0
        galleryBindings.clear()
        updateSelectionToolbar()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(1), 0, dp(1), dp(40))
            setBackgroundColor(RawLiteDesign.DIVIDER)
        }
        val intro = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            setBackgroundColor(0xE6000000.toInt())
        }
        intro.addView(TextView(this).apply {
            text = "CONTACTO / RECIENTES"
            textSize = 9f
            typeface = uiTypeface
            gravity = Gravity.CENTER_VERTICAL
            letterSpacing = 0.1f
            setTextColor(RawLiteDesign.TEXT_MUTED)
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        intro.addView(TextView(this).apply {
            text = "MANTÉN Y ARRASTRA PARA SELECCIONAR"
            textSize = 9f
            typeface = uiTypeface
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            letterSpacing = 0.08f
            setTextColor(RawLiteDesign.TEXT_DISABLED)
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        list.addView(intro, LinearLayout.LayoutParams.MATCH_PARENT, dp(42))
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            clips.chunked(2).forEachIndexed { rowIndex, pair ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setBackgroundColor(RawLiteDesign.DIVIDER)
                }
                pair.forEachIndexed { columnIndex, summary ->
                    row.addView(contactClipCard(summary, compact = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (columnIndex == 0 && pair.size > 1) {
                        row.addView(View(this).apply { setBackgroundColor(RawLiteDesign.DIVIDER) },
                            LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                }
                if (pair.size == 1) row.addView(space(1), LinearLayout.LayoutParams(0, 1, 1f))
                list.addView(row, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                if (rowIndex != clips.chunked(2).lastIndex) {
                    list.addView(View(this).apply { setBackgroundColor(RawLiteDesign.DIVIDER) },
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                }
            }
        } else {
            clips.forEachIndexed { index, summary ->
                list.addView(
                    contactClipCard(summary, compact = false),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                if (index != clips.lastIndex) {
                    list.addView(
                        View(this).apply { setBackgroundColor(RawLiteDesign.DIVIDER) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                    )
                }
            }
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list)
        }
        val animate = content.getChildAt(0) !is ScrollView
        galleryScrollView = scroll
        setContent(scroll, animate = animate)
        scroll.post { scroll.scrollTo(0, restoreScrollY) }
    }

    private fun contactClipCard(summary: ClipSummary, compact: Boolean): View {
        val key = summary.directory.absolutePath
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setBackgroundColor(0xED020202.toInt())
            setOnClickListener {
                if (selectionMode) toggleSelection(summary) else openClip(summary)
            }
            setOnLongClickListener {
                if (selectionMode) selectRangeTo(summary) else enterSelectionMode(summary)
                true
            }
        }
        val imageStage = FrameLayout(this).apply { setBackgroundColor(RawLiteDesign.SURFACE_RAISED) }
        val thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(RawLiteDesign.SURFACE_RAISED)
            contentDescription = "Vista previa de ${galleryClipTitle(summary)}"
        }
        imageStage.addView(thumbnail, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        imageStage.addView(formatFlag(summary), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(28),
            Gravity.START or Gravity.TOP
        ).apply {
            marginStart = dp(12)
            topMargin = dp(12)
        })
        imageStage.addView(TextView(this).apply {
            text = formatDuration(summary.durationSeconds).substringBeforeLast('.')
            textSize = 10f
            typeface = uiTypeface
            gravity = Gravity.CENTER
            setTextColor(RawLiteDesign.TEXT)
            setPadding(dp(8), 0, dp(8), 0)
            background = roundRect(0xD9000000.toInt(), dp(3))
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(26),
            Gravity.END or Gravity.BOTTOM
        ).apply {
            marginEnd = dp(12)
            bottomMargin = dp(12)
        })
        val selectionRail = View(this).apply {
            setBackgroundColor(RawLiteDesign.ACCENT)
            visibility = if (isSelected(summary)) View.VISIBLE else View.INVISIBLE
        }
        imageStage.addView(selectionRail, FrameLayout.LayoutParams(dp(4), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START))
        val badge = selectionBadge(isSelected(summary)).apply {
            visibility = if (selectionMode) View.VISIBLE else View.INVISIBLE
        }
        imageStage.addView(badge, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.END or Gravity.TOP).apply {
            marginEnd = dp(12)
            topMargin = dp(12)
        })
        card.addView(imageStage, LinearLayout.LayoutParams.MATCH_PARENT, dp(if (compact) 174 else 214))

        val metadata = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            setBackgroundColor(0xE60A0A0A.toInt())
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(labelText(galleryClipTitle(summary), 14f, RawLiteDesign.TEXT), LinearLayout.LayoutParams(0, dp(26), 1f))
        titleRow.addView(labelText(String.format(Locale.US, "%02d", galleryClips.indexOf(summary) + 1), 10f, RawLiteDesign.TEXT_DISABLED), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)))
        metadata.addView(titleRow)
        metadata.addView(labelText(
            "${summary.width}×${summary.height} · ${summary.fps} FPS · ${summary.camera} · ${formatLongName(summary)}",
            10f,
            RawLiteDesign.TEXT_MUTED
        ))
        val status = labelText(contactStatusLine(summary), 10f, clipStatusColor(summary)).apply {
            setPadding(0, dp(7), 0, 0)
        }
        metadata.addView(status)
        card.addView(metadata)
        galleryBindings[key] = GalleryCardBinding(summary, card, badge, selectionRail)
        attachDragSelection(card, summary)
        loadThumbnail(summary, thumbnail)
        return card
    }

    private fun formatFlag(summary: ClipSummary): TextView = TextView(this).apply {
        text = summary.captureFormat.uppercase(Locale.getDefault())
        textSize = 9f
        typeface = uiTypeface
        gravity = Gravity.CENTER
        letterSpacing = 0.08f
        setTextColor(RawLiteDesign.ACCENT)
        setPadding(dp(11), 0, dp(11), 0)
        background = GradientDrawable().apply {
            setColor(0xE6000000.toInt())
            setStroke(dp(1), RawLiteDesign.ACCENT_DIM)
        }
    }

    private fun formatLongName(summary: ClipSummary): String = when (summary.captureFormat) {
        "PURE 10" -> "HEVC MAIN10 HLG"
        "CinemaDNG" -> "SECUENCIA DNG"
        else -> "BAYER RAW10C"
    }

    private fun contactStatusLine(summary: ClipSummary): String {
        val integrity = when {
            !summary.finalized -> "INCOMPLETA"
            summary.lostFrameCount > 0 -> lostFramesLabel(summary.lostFrameCount)
            summary.captureFormat == "CinemaDNG" -> "${summary.frameCount} DNG"
            else -> "ÍNTEGRA"
        }
        val audio = when {
            summary.postProcessing -> "PROCESANDO AUDIO"
            summary.audioDescription != null || summary.integratedAudio -> "AUDIO"
            else -> "SIN AUDIO"
        }
        return "$integrity   ${formatBytes(summary.byteCount)}   $audio"
    }

    private fun galleryClipTitle(summary: ClipSummary): String {
        val format = if (summary.captureFormat == "PURE 10") "PURE10" else summary.captureFormat.uppercase(Locale.getDefault())
        val captured = SimpleDateFormat("dd MMM · HH:mm", Locale("es", "EC"))
            .format(Date(CaptureStorage.captureTimeMillis(summary.directory)))
            .uppercase(Locale("es", "EC"))
        return "$format · $captured"
    }

    private fun lostFramesLabel(count: Int): String =
        if (count == 1) "1 PERDIDO" else "$count PERDIDOS"

    private fun clipStatusColor(summary: ClipSummary): Int = when {
        summary.postProcessing -> RawLiteDesign.ACCENT
        summary.finalized -> RawLiteDesign.ACCENT
        else -> RawLiteDesign.RECORD
    }

    private fun loadThumbnail(summary: ClipSummary, target: ImageView) {
        val currentGeneration = generation.get()
        val key = summary.directory.absolutePath
        thumbnailCache[key]?.takeIf { !it.isRecycled }?.let {
            target.setImageBitmap(it)
            if (galleryClips.firstOrNull()?.directory?.absolutePath == key) {
                updateAmbientBackground(it)
            }
            return
        }
        worker.execute {
            val bitmap = runCatching { decodeThumbnail(summary) }.getOrNull()
            runOnUiThread {
                if (bitmap == null || generation.get() != currentGeneration || isFinishing) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }
                thumbnailCache[key]?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
                thumbnailCache[key] = bitmap
                if (target.windowToken != null) target.setImageBitmap(bitmap)
                if (galleryClips.firstOrNull()?.directory?.absolutePath == key) {
                    updateAmbientBackground(bitmap)
                }
            }
        }
    }

    private fun updateAmbientBackground(source: Bitmap) {
        if (!::ambientBackground.isInitialized || source.isRecycled) return
        val scaled = runCatching { Bitmap.createScaledBitmap(source, 96, 96, true) }.getOrNull() ?: return
        ambientBitmap?.takeIf { it !== scaled && !it.isRecycled }?.recycle()
        ambientBitmap = scaled
        ambientBackground.setImageBitmap(scaled)
        ambientBackground.animate().alpha(0.18f).setDuration(220L).start()
    }

    private fun decodeThumbnail(summary: ClipSummary): Bitmap? {
        return when (summary.captureFormat) {
            "PURE 10" -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(
                        File(summary.directory, summary.videoFileName ?: Pure10Encoder.VIDEO_FILE)
                            .absolutePath
                    )
                    retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?.let { orientPure10Bitmap(it, summary) }
                } finally {
                    retriever.release()
                }
            }
            "CinemaDNG" -> DngSequenceClip.open(summary.directory).decodeFrame(0, 320, 240)
            else -> Raw10Clip.open(summary.directory).use { it.decodeFrame(0, 320, 240) }
        }
    }

    private fun openClip(summary: ClipSummary) {
        clearPlayback()
        selectButton.visibility = View.GONE
        moreButton.visibility = View.VISIBLE
        moreButton.setOnClickListener { showClipMenu(summary) }
        activeSummary = summary
        titleView.text = galleryClipTitle(summary)
        subtitleView.text = "${formatLongName(summary)} · ${summary.width}×${summary.height} · ${summary.fps} FPS"
        backButton.contentDescription = "Volver a tomas"
        when (summary.captureFormat) {
            "PURE 10" -> buildPure10Player(summary)
            "CinemaDNG" -> openSequence(summary, PlaybackKind.DNG)
            else -> openSequence(summary, PlaybackKind.RAW10)
        }
    }

    private fun buildPure10Player(summary: ClipSummary) {
        playbackKind = PlaybackKind.PURE10
        val playerUi = buildPlayerShell(summary)
        val video = AspectRatioTextureView(this).apply {
            val rotation = readRotation(summary.directory)
            setVideoGeometry(
                summary.width,
                summary.height,
                VideoOrientation.textureRotationDegrees(
                    metadataRotationDegrees = rotation,
                    encodedWidth = summary.width,
                    encodedHeight = summary.height,
                    reportedWidth = summary.width,
                    reportedHeight = summary.height
                )
            )
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    prepareVideo(summary, texture)
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    videoPlayer?.setSurface(null)
                    return true
                }
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            }
        }
        textureView = video
        playerUi.first.addView(
            video,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        playWhenReady = restorePositionMs == null || restoreShouldPlay
    }

    private fun prepareVideo(summary: ClipSummary, texture: SurfaceTexture) {
        val file = File(summary.directory, summary.videoFileName ?: Pure10Encoder.VIDEO_FILE)
        val player = MediaPlayer()
        videoPlayer = player
        val surface = Surface(texture)
        try {
            player.setDataSource(file.absolutePath)
            player.setSurface(surface)
            player.setOnVideoSizeChangedListener { _, width, height ->
                val rotation = readRotation(summary.directory)
                textureView?.setVideoGeometry(
                    width,
                    height,
                    VideoOrientation.textureRotationDegrees(
                        metadataRotationDegrees = rotation,
                        encodedWidth = summary.width,
                        encodedHeight = summary.height,
                        reportedWidth = width,
                        reportedHeight = height
                    )
                )
            }
            player.setOnPreparedListener {
                videoPrepared = true
                configureDuration(it.duration.coerceAtLeast(1))
                stageStatus?.visibility = View.GONE
                val initialPosition = restorePositionMs ?: 1
                it.seekTo(initialPosition.toLong(), MediaPlayer.SEEK_CLOSEST)
                seekBar?.progress = initialPosition
                updateTimeLabels(initialPosition)
                restorePositionMs = null
                restoreShouldPlay = false
                if (!summary.integratedAudio) prepareAudio(summary.directory) else {
                    audioPlayer = null
                    audioOffsetMs = 0
                    applyIntegratedVideoAudioVolume()
                    updateAudioButton()
                }
                if (playWhenReady) startPlayback()
            }
            player.setOnCompletionListener {
                pausePlayback()
                seekToPosition(0)
            }
            player.setOnErrorListener { _, what, extra ->
                pausePlayback()
                stageStatus?.apply {
                    text = "NO SE PUDO DECODIFICAR MAIN10  $what/$extra"
                    visibility = View.VISIBLE
                    setTextColor(RawLiteDesign.RECORD)
                }
                true
            }
            player.prepareAsync()
        } catch (error: Throwable) {
            stageStatus?.text = error.message ?: "NO SE PUDO ABRIR PURE 10"
        } finally {
            surface.release()
        }
    }

    private fun openSequence(summary: ClipSummary, kind: PlaybackKind) {
        playbackKind = kind
        stageStatus?.text = "ABRIENDO SECUENCIA"
        setContent(messageView("ABRIENDO SECUENCIA"))
        val currentGeneration = generation.incrementAndGet()
        worker.execute {
            val result = runCatching {
                if (kind == PlaybackKind.DNG) {
                    activeDngClip = DngSequenceClip.open(summary.directory)
                } else {
                    activeRawClip = Raw10Clip.open(summary.directory)
                }
            }
            runOnUiThread {
                if (generation.get() != currentGeneration || isFinishing) return@runOnUiThread
                result.onSuccess {
                    val playerUi = buildPlayerShell(summary)
                    val image = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setBackgroundColor(RawLiteDesign.BLACK)
                        contentDescription = "Secuencia ${summary.captureFormat}"
                    }
                    sequenceView = image
                    playerUi.first.addView(
                        image,
                        0,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    configureDuration((summary.durationSeconds * 1000.0).roundToInt().coerceAtLeast(1))
                    prepareAudio(summary.directory)
                    val restoredPosition = restorePositionMs
                    playWhenReady = restoredPosition == null || restoreShouldPlay
                    val startIndex = if (restoredPosition == null) {
                        0
                    } else {
                        val targetTimeline = sequenceTimelineAt(0) +
                            (restoredPosition * summary.fps / 1000L).toInt()
                        sequenceIndexForTimeline(targetTimeline)
                    }
                    restorePositionMs = null
                    restoreShouldPlay = false
                    requestSequenceFrame(startIndex, autoStart = true)
                }.onFailure {
                    setContent(messageView(it.message ?: "NO SE PUDO ABRIR LA SECUENCIA"))
                }
            }
        }
    }

    private fun buildPlayerShell(summary: ClipSummary): Pair<FrameLayout, LinearLayout> {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val root = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(RawLiteDesign.BLACK)
        }
        val mediaStage = FrameLayout(this).apply {
            setBackgroundColor(RawLiteDesign.BLACK)
            setOnClickListener { togglePlayback() }
        }
        stage = mediaStage
        stageStatus = TextView(this).apply {
            text = "PREPARANDO"
            textSize = 12f
            typeface = uiTypeface
            gravity = Gravity.CENTER
            setTextColor(RawLiteDesign.TEXT_MUTED)
        }
        mediaStage.addView(stageStatus, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(
            mediaStage,
            if (landscape) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
        )

        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (landscape) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(RawLiteDesign.SURFACE)
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(
                    dp(16) + if (landscape) insets.systemWindowInsetLeft else 0,
                    dp(12),
                    dp(16) + if (landscape) insets.systemWindowInsetRight else 0,
                    dp(12) + if (landscape) 0 else insets.systemWindowInsetBottom
                )
                insets
            }
        }
        val metricRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        metricRow.addView(
            transportMetric("FORMATO", summary.captureFormat.uppercase(Locale.getDefault()), RawLiteDesign.ACCENT),
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )
        metricRow.addView(
            transportMetric("CADENCIA", "${summary.fps} FPS", RawLiteDesign.TEXT),
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )
        metricRow.addView(
            transportMetric(
                "AUDIO",
                if (summary.audioDescription != null || summary.integratedAudio) "DISPONIBLE" else "SIN AUDIO",
                if (summary.audioDescription != null || summary.integratedAudio) RawLiteDesign.AUTO else RawLiteDesign.TEXT_MUTED
            ),
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )
        transport.addView(metricRow, LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
        transport.addView(View(this).apply { setBackgroundColor(RawLiteDesign.DIVIDER) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        val detailRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        detailView = TextView(this).apply {
            text = "${summary.captureFormat.uppercase(Locale.getDefault())}   " +
                "${summary.width}×${summary.height}   ${summary.fps} FPS" +
                (if (summary.captureFormat == "PURE 10") "   HEVC MAIN10 · HLG" else "")
            textSize = 10f
            typeface = uiTypeface
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(RawLiteDesign.ACCENT)
        }
        detailRow.addView(detailView, LinearLayout.LayoutParams(0, dp(26), 1f))
        val diagnostics = PlaybackDiagnostics.from(summary)
        detailRow.addView(TextView(this).apply {
            text = PlaybackDiagnostics.lossLabel(diagnostics)
            textSize = 10f
            typeface = uiTypeface
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            isSingleLine = true
            setTextColor(
                if (diagnostics.hasLosses) RawLiteDesign.RECORD
                else RawLiteDesign.TEXT_MUTED
            )
            contentDescription = PlaybackDiagnostics.lossDescription(diagnostics)
            tooltipText = contentDescription
        }, LinearLayout.LayoutParams(dp(76), dp(26)))
        transport.addView(detailRow, LinearLayout.LayoutParams.MATCH_PARENT, dp(26))

        seekBar = SeekBar(this).apply {
            max = 1
            progress = 0
            splitTrack = false
            configureTransportSeekBar(this)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateTimeLabels(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { pausePlayback() }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    seekToPosition(seekBar?.progress ?: 0)
                }
            })
        }
        transport.addView(seekBar, LinearLayout.LayoutParams.MATCH_PARENT, dp(36))

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        positionView = transportText("00:00.000", Gravity.START)
        frameView = transportText(PlaybackDiagnostics.progressLabel(diagnostics), Gravity.CENTER)
        durationView = transportText("00:00.000", Gravity.END)
        timeRow.addView(positionView, LinearLayout.LayoutParams(0, dp(24), 1f))
        timeRow.addView(frameView, LinearLayout.LayoutParams(0, dp(24), 1f))
        timeRow.addView(durationView, LinearLayout.LayoutParams(0, dp(24), 1f))
        transport.addView(timeRow)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(iconButton(R.drawable.ic_transport_previous, "Fotograma anterior") {
            stepFrame(-1)
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        controls.addView(space(dp(12)))
        playButton = iconButton(R.drawable.ic_transport_play, "Reproducir") {
            togglePlayback()
        }.apply {
            background = transportControl(primary = true)
            setColorFilter(RawLiteDesign.BLACK)
        }
        controls.addView(playButton, LinearLayout.LayoutParams(dp(62), dp(62)))
        controls.addView(space(dp(12)))
        controls.addView(iconButton(R.drawable.ic_transport_next, "Fotograma siguiente") {
            stepFrame(1)
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        controls.addView(space(dp(22)))
        audioButton = iconButton(R.drawable.ic_transport_audio, "Silenciar audio") {
            muted = !muted
            audioPlayer?.setVolume(if (muted) 0f else 1f)
            applyIntegratedVideoAudioVolume()
            updateAudioButton()
        }
        controls.addView(audioButton, LinearLayout.LayoutParams(dp(52), dp(52)))
        controls.addView(space(dp(12)))
        controls.addView(iconButton(R.drawable.ic_transport_delete, "Borrar toma") {
            confirmDelete(summary)
        }.apply { setColorFilter(RawLiteDesign.RECORD) }, LinearLayout.LayoutParams(dp(52), dp(52)))
        transport.addView(controls, LinearLayout.LayoutParams.MATCH_PARENT, dp(68))
        if (landscape) {
            transport.addView(View(this).apply { setBackgroundColor(RawLiteDesign.DIVIDER) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            transport.addView(labelText("HERRAMIENTAS DE FORMATO", 9f, RawLiteDesign.TEXT_MUTED).apply {
                letterSpacing = 0.08f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, 0, 0)
            }, LinearLayout.LayoutParams.MATCH_PARENT, dp(34))
            if (summary.captureFormat == "RAW10") {
                transport.addView(playerDeckAction(
                    "RAW10 → CINEMADNG",
                    "CREAR COPIA EDITABLE",
                    RawLiteDesign.ACCENT
                ) { showConversionWarning(listOf(summary)) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(54))
            }
            transport.addView(playerDeckAction(
                "VERIFICAR INTEGRIDAD",
                "FRAMES · AUDIO · TIMESTAMPS",
                RawLiteDesign.TEXT
            ) { showClipIntegrity(summary) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(54))
        }
        root.addView(
            transport,
            if (landscape) {
                LinearLayout.LayoutParams(dp(370), ViewGroup.LayoutParams.MATCH_PARENT)
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        )
        setContent(root)
        updateAudioButton()
        return mediaStage to transport
    }

    private fun transportMetric(label: String, value: String, color: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            addView(labelText(label, 8f, RawLiteDesign.TEXT_MUTED).apply { letterSpacing = 0.08f })
            addView(labelText(value, 10f, color).apply { letterSpacing = 0.04f })
        }
    }

    private fun playerDeckAction(
        title: String,
        detail: String,
        color: Int,
        action: () -> Unit
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), 0)
        isClickable = true
        isFocusable = true
        background = selectableSurface()
        addView(labelText(title, 10f, color).apply { letterSpacing = 0.05f })
        addView(labelText(detail, 8f, RawLiteDesign.TEXT_MUTED).apply { letterSpacing = 0.04f })
        setOnClickListener { action() }
    }

    private fun togglePlayback() {
        if (playing || playWhenReady) pausePlayback() else startPlayback()
    }

    private fun startPlayback() {
        val positionMs = when (playbackKind) {
            PlaybackKind.PURE10 -> {
                if (!videoPrepared) {
                    playWhenReady = true
                    return
                }
                val player = videoPlayer ?: return
                if (player.currentPosition >= player.duration - 20) seekToPosition(0)
                player.currentPosition.coerceAtLeast(0)
            }
            PlaybackKind.RAW10, PlaybackKind.DNG -> {
                val summary = activeSummary ?: return
                if (activeFrameIndex >= sequenceFrameCount() - 1) {
                    activeFrameIndex = 0
                    requestedFrameIndex = 0
                }
                if (summary.frameCount <= 0) return
                val sequencePosition = sequencePositionMs(activeFrameIndex)
                if (!isSequenceBufferReady(activeFrameIndex)) {
                    beginSequenceBuffering(
                        anchorIndex = activeFrameIndex,
                        resumePositionMs = sequencePosition,
                        autoStart = true
                    )
                    return
                }
                sequencePosition
            }
            PlaybackKind.NONE -> return
        }

        prepareAudioForPlayback(positionMs)
        startVisualPlayback(positionMs)
    }

    private fun prepareAudioForPlayback(positionMs: Int) {
        val audio = audioPlayer ?: return
        val expected = audioTargetMs(audio, positionMs)
        val target = audioSeekTargetMs(audio, positionMs)
        if (abs(audio.currentPosition - expected) > AUDIO_RESUME_TOLERANCE_MS) {
            audio.seekTo(target)
        }
        audio.setVolume(if (muted) 0f else 1f)
        if (target < audio.duration - 10) audio.play()
    }

    private fun startVisualPlayback(positionMs: Int) {
        if (playing) return
        when (playbackKind) {
            PlaybackKind.PURE10 -> {
                lastVideoProgressMs = positionMs
                applyIntegratedVideoAudioVolume()
                videoPlayer?.start()
            }
            PlaybackKind.RAW10, PlaybackKind.DNG -> {
                rawPlaybackStartTimeline = sequenceTimelineAt(activeFrameIndex)
                rawPlaybackStartElapsedMs = SystemClock.elapsedRealtime()
            }
            PlaybackKind.NONE -> return
        }
        playing = true
        playWhenReady = false
        playButton?.setImageResource(R.drawable.ic_transport_pause)
        playButton?.contentDescription = "Pausar"
        mainHandler.removeCallbacks(progressTick)
        mainHandler.post(progressTick)
    }

    private fun pausePlayback() {
        playWhenReady = false
        playing = false
        sequenceBuffering = false
        runCatching { audioPlayer?.pause() }
        runCatching { if (videoPlayer?.isPlaying == true) videoPlayer?.pause() }
        mainHandler.removeCallbacks(progressTick)
        if (displayedBitmap != null) stageStatus?.visibility = View.GONE
        playButton?.setImageResource(R.drawable.ic_transport_play)
        playButton?.contentDescription = "Reproducir"
    }

    private fun updateVideoProgress() {
        val player = videoPlayer ?: return
        if (!player.isPlaying) {
            pausePlayback()
            return
        }
        val position = player.currentPosition.coerceAtLeast(0)
        if (position + VIDEO_CLOCK_BACKWARD_TOLERANCE_MS < lastVideoProgressMs) {
            Log.d(
                AUDIO_SYNC_TAG,
                "video_clock_reset previous_ms=$lastVideoProgressMs current_ms=$position"
            )
            pausePlayback()
            return
        }
        lastVideoProgressMs = position
        seekBar?.progress = position.coerceAtMost(seekBar?.max ?: position)
        updateTimeLabels(position)
        keepAudioSynchronized(position)
    }

    private fun updateSequenceProgress() {
        val summary = activeSummary ?: return
        val audio = audioPlayer
        if (audio != null && audio.currentPosition >= audio.duration - AUDIO_END_TOLERANCE_MS) {
            requestSequenceFrame(sequenceFrameCount() - 1)
            seekBar?.progress = seekBar?.max ?: sequencePositionMs(sequenceFrameCount() - 1)
            updateTimeLabels(seekBar?.progress ?: 0)
            pausePlayback()
            return
        }
        val audioPosition = audio
            ?.takeIf { it.isPlaying }
            ?.let { (it.currentPosition - audioOffsetMs).coerceAtLeast(0) }
        val position = audioPosition ?: run {
            val elapsed = SystemClock.elapsedRealtime() - rawPlaybackStartElapsedMs
            ((rawPlaybackStartTimeline - sequenceTimelineAt(0)).coerceAtLeast(0) * 1_000L /
                summary.fps + elapsed).toInt()
        }
        val targetTimeline = sequenceTimelineAt(0) +
            (position * summary.fps / 1_000L).toInt()
        val lastTimeline = sequenceTimelineAt(sequenceFrameCount() - 1)
        if (targetTimeline >= lastTimeline) {
            requestSequenceFrame(sequenceFrameCount() - 1)
            pausePlayback()
            return
        }
        val targetIndex = sequenceIndexForTimeline(targetTimeline)
        if (frameBuffer[targetIndex] == null) {
            suspendSequenceForBuffering(targetIndex, sequencePositionMs(targetIndex))
            return
        }
        if (targetIndex != requestedFrameIndex) requestSequenceFrame(targetIndex)
        prefetchSequenceFrames(targetIndex)
        seekBar?.progress = position.coerceIn(0, seekBar?.max ?: position)
        updateTimeLabels(position)
        keepAudioSynchronized(position)
    }

    private fun seekToPosition(positionMs: Int) {
        val bounded = positionMs.coerceIn(0, seekBar?.max ?: positionMs)
        when (playbackKind) {
            PlaybackKind.PURE10 -> {
                videoPlayer?.seekTo(bounded.toLong(), MediaPlayer.SEEK_CLOSEST)
                lastVideoProgressMs = bounded
                seekAudio(bounded)
            }
            PlaybackKind.RAW10, PlaybackKind.DNG -> {
                val fps = activeSummary?.fps ?: return
                val targetTimeline = sequenceTimelineAt(0) + (bounded * fps / 1000L).toInt()
                resetSequenceDecodeQueue()
                requestSequenceFrame(sequenceIndexForTimeline(targetTimeline))
                seekAudio(bounded)
            }
            PlaybackKind.NONE -> Unit
        }
        seekBar?.progress = bounded
        updateTimeLabels(bounded)
    }

    private fun stepFrame(direction: Int) {
        pausePlayback()
        when (playbackKind) {
            PlaybackKind.PURE10 -> {
                val frameMs = 1000 / (activeSummary?.fps ?: 30).coerceAtLeast(1)
                seekToPosition((videoPlayer?.currentPosition ?: 0) + direction * frameMs)
            }
            PlaybackKind.RAW10, PlaybackKind.DNG -> {
                resetSequenceDecodeQueue()
                requestSequenceFrame((activeFrameIndex + direction).coerceIn(0, sequenceFrameCount() - 1))
            }
            PlaybackKind.NONE -> Unit
        }
    }

    private fun requestSequenceFrame(index: Int, autoStart: Boolean = false) {
        val count = sequenceFrameCount()
        if (count <= 0) return
        requestedFrameIndex = index.coerceIn(0, count - 1)
        if (autoStart) {
            beginSequenceBuffering(
                anchorIndex = requestedFrameIndex,
                resumePositionMs = sequencePositionMs(requestedFrameIndex),
                autoStart = true
            )
            return
        }
        frameBuffer[requestedFrameIndex]?.let {
            displaySequenceFrame(requestedFrameIndex, it)
            prefetchSequenceFrames(requestedFrameIndex)
            return
        }
        stageStatus?.apply {
            text = if (playbackKind == PlaybackKind.DNG) "REVELANDO DNG" else "REVELANDO RAW10"
            setTextColor(RawLiteDesign.TEXT_MUTED)
            visibility = View.VISIBLE
        }
        enqueueSequenceFrame(requestedFrameIndex)
    }

    private fun beginSequenceBuffering(
        anchorIndex: Int,
        resumePositionMs: Int,
        autoStart: Boolean
    ) {
        val count = sequenceFrameCount()
        if (count <= 0) return
        bufferingAnchorIndex = anchorIndex.coerceIn(0, count - 1)
        requestedFrameIndex = bufferingAnchorIndex
        bufferingResumePositionMs = resumePositionMs.coerceAtLeast(0)
        bufferingTargetFrames = sequenceBufferTarget(bufferingAnchorIndex)
        sequenceBuffering = true
        if (autoStart) {
            playWhenReady = true
            playButton?.setImageResource(R.drawable.ic_transport_pause)
            playButton?.contentDescription = "Cancelar reproducción"
        }
        updateSequenceBufferingState()
        prefetchSequenceFrames(bufferingAnchorIndex, bufferingTargetFrames)
    }

    private fun suspendSequenceForBuffering(index: Int, positionMs: Int) {
        playing = false
        playWhenReady = true
        runCatching { audioPlayer?.pause() }
        mainHandler.removeCallbacks(progressTick)
        resetSequenceDecodeQueue(keepBufferingState = true)
        beginSequenceBuffering(index, positionMs, autoStart = true)
    }

    private fun prefetchSequenceFrames(
        anchorIndex: Int,
        targetFrames: Int = sequenceBufferTarget(anchorIndex)
    ) {
        frameBuffer.missingWindow(anchorIndex, targetFrames, sequenceFrameCount())
            .forEach(::enqueueSequenceFrame)
    }

    private fun enqueueSequenceFrame(index: Int) {
        val count = sequenceFrameCount()
        if (index !in 0 until count || frameBuffer.contains(index) || !pendingSequenceFrames.add(index)) {
            return
        }
        val currentGeneration = generation.get()
        val currentEpoch = sequenceDecodeEpoch
        sequenceWorker.execute {
            if (generation.get() != currentGeneration || sequenceDecodeEpoch != currentEpoch) return@execute
            val result = runCatching { decodeSequenceFrame(index) }
            runOnUiThread {
                if (currentEpoch == sequenceDecodeEpoch) pendingSequenceFrames.remove(index)
                if (generation.get() != currentGeneration ||
                    sequenceDecodeEpoch != currentEpoch ||
                    playbackKind == PlaybackKind.NONE
                ) {
                    result.getOrNull()?.recycle()
                    return@runOnUiThread
                }
                result.onSuccess { bitmap ->
                    frameBuffer.put(index, bitmap, displayedBitmap)
                    if (index == requestedFrameIndex && (!playing || sequenceBuffering)) {
                        displaySequenceFrame(index, bitmap)
                    }
                    if (sequenceBuffering) updateSequenceBufferingState()
                }.onFailure {
                    sequenceBuffering = false
                    playWhenReady = false
                    stageStatus?.apply {
                        text = it.message ?: "NO SE PUDO REVELAR EL FOTOGRAMA"
                        setTextColor(RawLiteDesign.RECORD)
                        visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun updateSequenceBufferingState() {
        if (!sequenceBuffering) return
        val ready = frameBuffer.contiguousReadyCount(bufferingAnchorIndex, sequenceFrameCount())
        stageStatus?.apply {
            text = "CARGANDO $ready/$bufferingTargetFrames"
            setTextColor(RawLiteDesign.TEXT_MUTED)
            visibility = View.VISIBLE
        }
        frameBuffer[bufferingAnchorIndex]?.let { displaySequenceFrame(bufferingAnchorIndex, it) }
        if (ready < bufferingTargetFrames) return
        val shouldResume = playWhenReady
        sequenceBuffering = false
        stageStatus?.visibility = View.GONE
        if (shouldResume) {
            if (bufferingAnchorIndex >= sequenceFrameCount() - 1) {
                pausePlayback()
                return
            }
            prepareAudioForPlayback(bufferingResumePositionMs)
            startVisualPlayback(bufferingResumePositionMs)
        }
    }

    private fun isSequenceBufferReady(anchorIndex: Int): Boolean {
        val target = sequenceBufferTarget(anchorIndex)
        return frameBuffer.contiguousReadyCount(anchorIndex, sequenceFrameCount()) >= target
    }

    private fun sequenceBufferTarget(anchorIndex: Int): Int {
        val count = sequenceFrameCount()
        if (count <= 0) return 0
        val remaining = count - anchorIndex.coerceIn(0, count - 1)
        val fps = activeSummary?.fps?.coerceAtLeast(1) ?: 1
        val desired = ((fps * SEQUENCE_BUFFER_TARGET_MS + 999L) / 1_000L)
            .toInt()
            .coerceIn(1, FRAME_CACHE_SIZE)
        return minOf(remaining, desired)
    }

    private fun resetSequenceDecodeQueue(keepBufferingState: Boolean = false) {
        sequenceDecodeEpoch++
        pendingSequenceFrames.clear()
        if (!keepBufferingState) sequenceBuffering = false
    }

    private fun decodeSequenceFrame(index: Int): Bitmap {
        return when (playbackKind) {
            PlaybackKind.RAW10 -> requireNotNull(activeRawClip).decodeFrame(index)
            PlaybackKind.DNG -> requireNotNull(activeDngClip).decodeFrame(
                index,
                DNG_PREVIEW_MAX_WIDTH,
                DNG_PREVIEW_MAX_HEIGHT
            )
            else -> error("No hay secuencia activa")
        }
    }

    private fun displaySequenceFrame(index: Int, bitmap: Bitmap) {
        activeFrameIndex = index
        displayedBitmap = bitmap
        sequenceView?.setImageBitmap(bitmap)
        if (!sequenceBuffering) stageStatus?.visibility = View.GONE
        val position = sequencePositionMs(index)
        if (!playing) {
            seekBar?.progress = position.coerceIn(0, seekBar?.max ?: position)
            updateTimeLabels(position)
        }
        val timelineFrame = sequenceTimelineAt(index) + 1
        val summary = activeSummary ?: return
        val diagnostics = PlaybackDiagnostics.from(summary)
        frameView?.text = PlaybackDiagnostics.progressLabel(
            diagnostics,
            storedFrame = index + 1,
            timelineFrame = timelineFrame
        )
        frameView?.contentDescription = buildString {
            append("Fotograma almacenado ${index + 1} de ${sequenceFrameCount()}")
            if (diagnostics.timelineFrames > diagnostics.storedFrames) {
                append(", posición temporal $timelineFrame de ${diagnostics.timelineFrames}")
            }
            append(", ${PlaybackDiagnostics.lossDescription(diagnostics).lowercase(Locale.getDefault())}")
        }
    }

    private fun sequenceFrameCount(): Int = when (playbackKind) {
        PlaybackKind.RAW10 -> activeRawClip?.frames?.size ?: 0
        PlaybackKind.DNG -> activeDngClip?.files?.size ?: 0
        else -> 0
    }

    private fun sequenceTimelineAt(index: Int): Int {
        val bounded = index.coerceIn(0, (sequenceFrameCount() - 1).coerceAtLeast(0))
        return when (playbackKind) {
            PlaybackKind.RAW10 -> activeRawClip?.frames?.getOrNull(bounded)?.frame ?: bounded
            PlaybackKind.DNG -> activeDngClip?.timelineFrames?.getOrNull(bounded) ?: bounded
            else -> bounded
        }
    }

    private fun sequenceIndexForTimeline(timeline: Int): Int {
        return CaptureMath.indexForTimeline(sequenceFrameCount(), timeline, ::sequenceTimelineAt)
    }

    private fun sequencePositionMs(index: Int): Int {
        val fps = activeSummary?.fps?.coerceAtLeast(1) ?: 1
        return CaptureMath.sequencePositionMs(
            sequenceTimelineAt(index),
            sequenceTimelineAt(0),
            fps
        )
    }

    private fun prepareAudio(directory: File) {
        val file = File(directory, "audio.wav")
        audioOffsetMs = readAudioOffsetMs(directory)
        if (!file.isFile) {
            updateAudioButton()
            return
        }
        audioPlayer = PcmWavPlayer.create(file)
        audioPlayer?.setVolume(if (muted) 0f else 1f)
        updateAudioButton()
        if (audioPlayer == null) {
            Log.e(AUDIO_SYNC_TAG, "No se pudo abrir ${file.name} como PCM WAV")
        }
    }

    private fun seekAudio(videoPositionMs: Int) {
        val player = audioPlayer ?: return
        player.seekTo(audioSeekTargetMs(player, videoPositionMs))
    }

    private fun keepAudioSynchronized(videoPositionMs: Int) {
        val player = audioPlayer ?: return
        if (!playing) return
        val expected = audioTargetMs(player, videoPositionMs)
        val now = SystemClock.elapsedRealtime()
        val driftMs = player.currentPosition - expected
        if (now - lastAudioDriftLogElapsedMs >= AUDIO_DRIFT_LOG_INTERVAL_MS) {
            lastAudioDriftLogElapsedMs = now
            Log.d(
                AUDIO_SYNC_TAG,
                "drift_ms=$driftMs video_ms=$videoPositionMs audio_ms=${player.currentPosition} " +
                    "target_ms=$expected"
            )
        }
    }

    private fun audioTargetMs(player: PcmWavPlayer, videoPositionMs: Int): Int {
        return CaptureMath.audioTargetMs(videoPositionMs, audioOffsetMs, player.duration)
    }

    private fun audioSeekTargetMs(player: PcmWavPlayer, videoPositionMs: Int): Int {
        val outputLead = if (playbackKind == PlaybackKind.PURE10) {
            PURE10_AUDIO_OUTPUT_LEAD_MS
        } else {
            0
        }
        return CaptureMath.audioTargetMs(
            videoPositionMs,
            audioOffsetMs,
            player.duration,
            outputLead
        )
    }

    private fun readAudioOffsetMs(directory: File): Int {
        return runCatching {
            val sync = JSONObject(File(directory, "sync.json").readText())
            sync.optLong("camera_start_minus_audio_start_ns", 0L)
                .div(1_000_000L)
                .toInt()
        }.getOrDefault(0)
    }

    private fun readRotation(directory: File): Int {
        return runCatching {
            JSONObject(File(directory, "clip.json").readText()).optInt("rotation_degrees", 0)
        }.getOrDefault(0)
    }

    private fun orientPure10Bitmap(bitmap: Bitmap, summary: ClipSummary): Bitmap {
        val directory = summary.directory
        val metadata = runCatching {
            JSONObject(File(directory, "clip.json").readText())
        }.getOrNull()
        val rotation = VideoOrientation.bitmapRotationDegrees(
            metadataRotationDegrees = metadata?.optInt("rotation_degrees", 0) ?: 0,
            encodedWidth = summary.width,
            encodedHeight = summary.height,
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height
        )
        val mirrored = metadata?.optBoolean("front_facing", false) == true
        if (rotation % 360 == 0 && !mirrored) return bitmap
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            if (mirrored) postScale(-1f, 1f)
        }
        val oriented = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }

    private fun currentPositionMs(): Int = when (playbackKind) {
        PlaybackKind.PURE10 -> videoPlayer?.currentPosition ?: 0
        PlaybackKind.RAW10, PlaybackKind.DNG -> sequencePositionMs(activeFrameIndex)
        PlaybackKind.NONE -> 0
    }

    private fun configureDuration(durationMs: Int) {
        seekBar?.max = durationMs.coerceAtLeast(1)
        durationView?.text = formatTimecode(durationMs)
        updateTimeLabels(0)
    }

    private fun updateTimeLabels(positionMs: Int) {
        positionView?.text = formatTimecode(positionMs)
        if (playbackKind == PlaybackKind.PURE10) {
            val summary = activeSummary ?: return
            val diagnostics = PlaybackDiagnostics.from(summary)
            val timelineFrame = PlaybackDiagnostics.pure10TimelineFrame(
                positionMs = positionMs,
                fps = summary.fps,
                timelineFrames = diagnostics.timelineFrames
            )
            frameView?.text = PlaybackDiagnostics.progressLabel(
                diagnostics,
                timelineFrame = timelineFrame
            )
            frameView?.contentDescription = buildString {
                append("Posición temporal $timelineFrame de ${diagnostics.timelineFrames}")
                append(", ${diagnostics.storedFrames} fotogramas codificados")
                append(", ${PlaybackDiagnostics.lossDescription(diagnostics).lowercase(Locale.getDefault())}")
            }
        }
    }

    private fun updateAudioButton() {
        val button = audioButton ?: return
        val available = audioPlayer != null || hasIntegratedVideoAudio()
        button.isEnabled = available
        button.alpha = if (available) 1f else 0.35f
        button.setImageResource(
            if (muted) R.drawable.ic_transport_mute
            else R.drawable.ic_transport_audio
        )
        button.contentDescription = if (muted) "Activar audio" else "Silenciar audio"
    }

    private fun hasIntegratedVideoAudio(): Boolean {
        return playbackKind == PlaybackKind.PURE10 && activeSummary?.integratedAudio == true
    }

    private fun applyIntegratedVideoAudioVolume() {
        if (!hasIntegratedVideoAudio()) return
        val volume = if (muted) 0f else 1f
        runCatching { videoPlayer?.setVolume(volume, volume) }
    }

    private fun confirmDelete(summary: ClipSummary) {
        pausePlayback()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Borrar toma")
            .setMessage("Se eliminarán permanentemente ${formatBytes(summary.byteCount)}.")
            .setNegativeButton("CANCELAR", null)
            .setPositiveButton("BORRAR") { _, _ ->
                clearPlayback()
                setContent(messageView("BORRANDO"))
                worker.execute {
                    val deleted = clipRepository.delete(summary)
                    runOnUiThread {
                        if (deleted) showClipList() else setContent(messageView("NO SE PUDO BORRAR"))
                    }
                }
            }
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(roundRect(RawLiteDesign.SURFACE_RAISED, dp(4)))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(RawLiteDesign.RECORD)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(RawLiteDesign.TEXT)
        }
        dialog.show()
    }

    private fun enterSelectionMode(initial: ClipSummary? = null) {
        selectionMode = true
        disarmSelectionActions()
        initial?.let {
            val key = it.directory.absolutePath
            selectedClipPaths += key
            selectionAnchorPath = key
        }
        updateSelectionUiInPlace()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedClipPaths.clear()
        selectionAnchorPath = null
        dragSelectionActive = false
        dragLastPath = null
        updateSelectionUiInPlace()
    }

    private fun toggleSelection(summary: ClipSummary) {
        disarmSelectionActions()
        val key = summary.directory.absolutePath
        if (!selectedClipPaths.add(key)) selectedClipPaths.remove(key)
        selectionAnchorPath = key
        if (selectedClipPaths.isEmpty()) {
            selectionMode = false
            selectionAnchorPath = null
        }
        updateSelectionUiInPlace()
    }

    private fun selectRangeTo(summary: ClipSummary) {
        disarmSelectionActions()
        val target = summary.directory.absolutePath
        val anchor = selectionAnchorPath ?: selectedClipPaths.lastOrNull() ?: target
        val start = galleryClips.indexOfFirst { it.directory.absolutePath == anchor }
        val end = galleryClips.indexOfFirst { it.directory.absolutePath == target }
        if (start < 0 || end < 0) {
            selectedClipPaths += target
            selectionAnchorPath = target
            updateSelectionUiInPlace()
            return
        }
        val range = if (start <= end) start..end else end..start
        range.forEach { index -> selectedClipPaths += galleryClips[index].directory.absolutePath }
        selectionAnchorPath = target
        selectionMode = selectedClipPaths.isNotEmpty()
        updateSelectionUiInPlace()
    }

    private fun selectedClips(): List<ClipSummary> =
        galleryClips.filter { selectedClipPaths.contains(it.directory.absolutePath) }

    private fun isSelected(summary: ClipSummary): Boolean =
        selectedClipPaths.contains(summary.directory.absolutePath)

    private fun updateSelectionToolbar() {
        selectButton.visibility = if (playbackKind == PlaybackKind.NONE && galleryClips.isNotEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        moreButton.visibility = if (playbackKind == PlaybackKind.NONE && galleryClips.isNotEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        moreButton.setOnClickListener { showGalleryMenu() }
        selectButton.text = if (selectionMode) "CERRAR" else "SEL"
        selectButton.contentDescription = if (selectionMode) "Cancelar selección" else "Seleccionar tomas"
        selectButton.setOnClickListener {
            if (selectionMode) exitSelectionMode() else enterSelectionMode()
        }
        titleView.text = if (selectionMode) "SELECCIÓN" else "TOMAS"
        subtitleView.text = if (selectionMode) {
            "${selectedClipPaths.size} SELECCIONADAS · ${formatBytes(selectedClips().sumOf { it.byteCount })}"
        } else {
            "${galleryClips.size} ${if (galleryClips.size == 1) "TOMA" else "TOMAS"} · ${formatBytes(galleryClips.sumOf { it.byteCount })}"
        }
    }

    private fun buildSelectionBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(0xF20A0A0A.toInt())
            addView(selectionActionButton("TODO", RawLiteDesign.TEXT) {
                selectAllClips()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.9f))
            addView(space(dp(1)).apply { setBackgroundColor(RawLiteDesign.DIVIDER) }, LinearLayout.LayoutParams(dp(1), dp(36)))
            conversionSelectionButton = selectionActionButton("CONVERTIR", RawLiteDesign.ACCENT) {
                showConversionWarning(selectedClips())
            }
            addView(conversionSelectionButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.35f))
            addView(space(dp(1)).apply { setBackgroundColor(RawLiteDesign.DIVIDER) }, LinearLayout.LayoutParams(dp(1), dp(36)))
            deleteSelectionButton = selectionActionButton("BORRAR", RawLiteDesign.RECORD) {
                confirmDeleteSelected(selectedClips())
            }
            addView(deleteSelectionButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.9f))
        }
    }

    private fun selectionActionButton(label: String, color: Int, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 11f
            typeface = uiTypeface
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(8), 0, dp(8), 0)
            setTextColor(color)
            isClickable = true
            isFocusable = true
            background = selectableSurface()
            setOnClickListener {
                if (SystemClock.uptimeMillis() >= selectionActionsAvailableAfterMs) action()
            }
        }

    private fun selectAllClips() {
        disarmSelectionActions()
        selectionMode = true
        selectedClipPaths.clear()
        galleryClips.forEach { selectedClipPaths += it.directory.absolutePath }
        selectionAnchorPath = galleryClips.lastOrNull()?.directory?.absolutePath
        updateSelectionUiInPlace()
    }

    private fun updateSelectionUiInPlace() {
        updateSelectionToolbar()
        if (selectionMode) {
            if (selectionBar.visibility != View.VISIBLE) {
                selectionBar.visibility = View.VISIBLE
                selectionBar.translationY = -dp(12).toFloat()
                selectionBar.animate().alpha(1f).translationY(0f).setDuration(180L).start()
            }
        } else if (selectionBar.visibility == View.VISIBLE) {
            selectionBar.animate().alpha(0f).translationY(-dp(8).toFloat()).setDuration(140L)
                .withEndAction { selectionBar.visibility = View.GONE }
                .start()
        }
        galleryBindings.forEach { (path, binding) ->
            val selected = selectedClipPaths.contains(path)
            binding.badge.visibility = if (selectionMode) View.VISIBLE else View.INVISIBLE
            binding.badge.text = if (selected) "✓" else "○"
            binding.badge.setTextColor(if (selected) RawLiteDesign.BLACK else RawLiteDesign.TEXT)
            binding.badge.background = roundRect(
                if (selected) RawLiteDesign.ACCENT else 0xB3000000.toInt(),
                dp(18)
            )
            binding.badge.contentDescription = if (selected) "Seleccionada" else "No seleccionada"
            binding.selectionRail.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            binding.root.setBackgroundColor(
                if (selected) 0xF21A1608.toInt() else 0xED020202.toInt()
            )
        }
        if (::conversionSelectionButton.isInitialized) {
            val count = selectedClipPaths.size
            val rawCount = selectedClips().count { it.captureFormat == "RAW10" }
            conversionSelectionButton.text = if (rawCount > 0) "CONVERTIR $rawCount" else "SIN RAW10"
            conversionSelectionButton.isEnabled = rawCount > 0
            conversionSelectionButton.alpha = if (rawCount > 0) 1f else 0.42f
            deleteSelectionButton.isEnabled = count > 0
            deleteSelectionButton.alpha = if (count > 0) 1f else 0.42f
        }
    }

    private fun showGalleryMenu() {
        val options = if (selectionMode) {
            arrayOf("SELECCIONAR TODO", "CANCELAR SELECCIÓN")
        } else {
            arrayOf("SELECCIONAR TODO")
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("TOMAS")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "SELECCIONAR TODO" -> selectAllClips()
                    else -> exitSelectionMode()
                }
            }
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(roundRect(RawLiteDesign.SURFACE_RAISED, dp(4)))
        }
        dialog.show()
    }

    private fun showClipMenu(summary: ClipSummary) {
        val options = buildList {
            if (summary.captureFormat == "RAW10") add("CONVERTIR A CINEMADNG")
            add("VERIFICAR INTEGRIDAD")
            add("BORRAR TOMA")
        }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle(galleryClipTitle(summary))
            .setItems(options) { _, which ->
                when (options[which]) {
                    "CONVERTIR A CINEMADNG" -> showConversionWarning(listOf(summary))
                    "VERIFICAR INTEGRIDAD" -> showClipIntegrity(summary)
                    "BORRAR TOMA" -> confirmDelete(summary)
                }
            }
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(roundRect(RawLiteDesign.SURFACE_RAISED, dp(4)))
        }
        dialog.show()
    }

    private fun showClipIntegrity(summary: ClipSummary) {
        val status = when {
            !summary.finalized -> "INCOMPLETA"
            summary.lostFrameCount > 0 -> "CON PÉRDIDAS"
            else -> "ÍNTEGRA"
        }
        val message = buildString {
            append("Estado: $status\n")
            append("Guardados: ${summary.frameCount} fotogramas\n")
            append("Timeline: ${summary.timelineFrameCount} fotogramas\n")
            append("Perdidos: ${summary.lostFrameCount}\n")
            append("Cadencia: ${summary.fps} FPS\n")
            append("Audio: ${if (summary.audioDescription != null || summary.integratedAudio) "DISPONIBLE" else "NO DISPONIBLE"}\n")
            append("Tamaño: ${formatBytes(summary.byteCount)}")
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("INTEGRIDAD DE TOMA")
            .setMessage(message)
            .setPositiveButton("ENTENDIDO", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(roundRect(RawLiteDesign.SURFACE_RAISED, dp(4)))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(RawLiteDesign.ACCENT)
        }
        dialog.show()
    }

    private fun attachDragSelection(view: View, summary: ClipSummary) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        view.setOnTouchListener { _, event ->
            if (!selectionMode) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    dragSelectionActive = false
                    dragSelectionValue = !isSelected(summary)
                    dragLastPath = null
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragSelectionActive &&
                        (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop)
                    ) {
                        dragSelectionActive = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        applyDragSelectionAt(downX, downY)
                    }
                    if (dragSelectionActive) {
                        applyDragSelectionAt(event.rawX, event.rawY)
                        autoScrollGallery(event.rawY)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragSelectionActive) {
                        applyDragSelectionAt(event.rawX, event.rawY)
                    } else {
                        setSelectionState(summary, dragSelectionValue)
                    }
                    dragSelectionActive = false
                    dragLastPath = null
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragSelectionActive = false
                    dragLastPath = null
                    true
                }
                else -> true
            }
        }
    }

    private fun applyDragSelectionAt(rawX: Float, rawY: Float) {
        val hit = Rect()
        val binding = galleryBindings.values.firstOrNull {
            it.root.getGlobalVisibleRect(hit) && hit.contains(rawX.toInt(), rawY.toInt())
        } ?: return
        val path = binding.summary.directory.absolutePath
        if (dragLastPath == path) return
        dragLastPath = path
        setSelectionState(binding.summary, dragSelectionValue)
    }

    private fun setSelectionState(summary: ClipSummary, selected: Boolean) {
        disarmSelectionActions()
        val path = summary.directory.absolutePath
        if (selected) selectedClipPaths += path else selectedClipPaths.remove(path)
        selectionAnchorPath = path
        selectionMode = true
        updateSelectionUiInPlace()
    }

    private fun disarmSelectionActions() {
        selectionActionsAvailableAfterMs = SystemClock.uptimeMillis() + SELECTION_ACTION_ARM_DELAY_MS
    }

    private fun autoScrollGallery(rawY: Float) {
        val scroll = galleryScrollView ?: return
        val location = IntArray(2)
        scroll.getLocationOnScreen(location)
        val top = location[1].toFloat()
        val bottom = top + scroll.height
        val edge = dp(56)
        when {
            rawY < top + edge -> scroll.scrollBy(0, -dp(18))
            rawY > bottom - edge -> scroll.scrollBy(0, dp(18))
        }
    }

    private fun showConversionWarning(clips: List<ClipSummary>) {
        if (clips.isEmpty()) return
        val estimate = GalleryConversionEstimator.estimateRaw10ToCinemaDng(clips)
        if (estimate.raw10ClipCount == 0) {
            showNoRaw10ConversionDialog()
            return
        }
        val available = availableMovieBytes()
        val pressure = if (available > 0L && estimate.estimatedCinemaDngBytes > available) {
            "\n\nESPACIO INSUFICIENTE PROBABLE: disponible ${formatBytes(available)}."
        } else {
            "\n\nDisponible aproximado: ${formatBytes(available)}."
        }
        val message = buildString {
            append("Conversión: RAW10 → CinemaDNG 2K.\n")
            append("${estimate.raw10ClipCount} toma(s) RAW10 seleccionada(s).")
            if (estimate.ignoredClipCount > 0) {
                append("\n${estimate.ignoredClipCount} toma(s) no RAW10 se ignorarán.")
            }
            append("\n\nRAW10 original: ${formatBytes(estimate.raw10SourceBytes)}.\n")
            append("CinemaDNG estimado adicional: ${formatBytes(estimate.estimatedCinemaDngBytes)}.\n")
            append("Uso temporal total: ${formatBytes(estimate.temporaryWorkingBytes)}.")
            append(pressure)
            append("\n\nSe creará una toma CinemaDNG nueva junto al RAW10 original. ")
            append("El RAW10 original no se modifica.")
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Convertir RAW10")
            .setMessage(message)
            .setNegativeButton("CANCELAR", null)
            .setPositiveButton("CONVERTIR") { _, _ ->
                startRaw10Conversion(clips.filter { it.captureFormat == "RAW10" })
            }
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(roundRect(RawLiteDesign.SURFACE_RAISED, dp(4)))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(RawLiteDesign.ACCENT)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(RawLiteDesign.TEXT)
        }
        dialog.show()
    }

    private fun startRaw10Conversion(clips: List<ClipSummary>) {
        if (clips.isEmpty()) return
        val currentGeneration = generation.incrementAndGet()
        selectionMode = false
        selectedClipPaths.clear()
        val progressView = messageView("CONVIRTIENDO RAW10")
        setContent(progressView)
        worker.execute {
            val results = Raw10CinemaDngConverter.convertBatch(clips.map { it.directory }) { progress ->
                runOnUiThread {
                    if (generation.get() == currentGeneration && !isFinishing) {
                        (progressView as? TextView)?.text =
                            "CONVIRTIENDO ${progress.clipName}\n" +
                            "${progress.doneFrames}/${progress.totalFrames} FRAMES"
                    }
                }
            }
            runOnUiThread {
                if (generation.get() != currentGeneration || isFinishing) return@runOnUiThread
                val failed = results.count { it.error != null }
                val converted = results.count { it.error == null && !it.skipped }
                val skipped = results.count { it.skipped }
                val frames = results.sumOf { it.convertedFrames }
                showClipList()
                val message = buildString {
                    append("Convertidas: $converted toma(s).\n")
                    if (skipped > 0) append("Ya existían: $skipped.\n")
                    append("Frames CinemaDNG: $frames.")
                    if (failed > 0) {
                        append("\n\nFallaron: $failed.\n")
                        results.filter { it.error != null }.take(3).forEach {
                            append("\n${it.source.name}: ${it.error}")
                        }
                    }
                }
                showConversionResultDialog(failed == 0, message)
            }
        }
    }

    private fun showConversionResultDialog(success: Boolean, message: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (success) "Conversión completa" else "Conversión incompleta")
            .setMessage(message)
            .setPositiveButton("ENTENDIDO", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(roundRect(RawLiteDesign.SURFACE_RAISED, dp(4)))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(
                if (success) RawLiteDesign.ACCENT else RawLiteDesign.RECORD
            )
        }
        dialog.show()
    }

    private fun showNoRaw10ConversionDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Sin RAW10")
            .setMessage(
                "La conversión disponible para este flujo es RAW10 → CinemaDNG 2K.\n\n" +
                    "PURE 10 ya es video MP4 y CinemaDNG ya es una secuencia DNG; no se convierten aquí."
            )
            .setPositiveButton("ENTENDIDO", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(roundRect(RawLiteDesign.SURFACE_RAISED, dp(4)))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(RawLiteDesign.ACCENT)
        }
        dialog.show()
    }

    private fun confirmDeleteSelected(clips: List<ClipSummary>) {
        if (clips.isEmpty()) return
        val dialog = AlertDialog.Builder(this)
            .setTitle("Borrar tomas")
            .setMessage(
                "Se eliminarán permanentemente ${clips.size} toma(s), " +
                    "${formatBytes(clips.sumOf { it.byteCount })}."
            )
            .setNegativeButton("CANCELAR", null)
            .setPositiveButton("BORRAR") { _, _ ->
                setContent(messageView("BORRANDO"))
                worker.execute {
                    val deleted = clips.count { clipRepository.delete(it) }
                    runOnUiThread {
                        selectionMode = false
                        selectedClipPaths.clear()
                        selectionAnchorPath = null
                        showClipList()
                        if (deleted != clips.size) {
                            setContent(messageView("NO SE PUDIERON BORRAR TODAS LAS TOMAS"))
                        }
                    }
                }
            }
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(roundRect(RawLiteDesign.SURFACE_RAISED, dp(4)))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(RawLiteDesign.RECORD)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(RawLiteDesign.TEXT)
        }
        dialog.show()
    }

    private fun availableMovieBytes(): Long {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        return runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(0L)
    }

    private fun selectionBadge(selected: Boolean): TextView = TextView(this).apply {
        text = if (selected) "✓" else "○"
        textSize = 18f
        typeface = uiTypeface
        gravity = Gravity.CENTER
        setTextColor(if (selected) RawLiteDesign.BLACK else RawLiteDesign.TEXT)
        background = roundRect(
            if (selected) RawLiteDesign.ACCENT else 0x99000000.toInt(),
            dp(3)
        )
        contentDescription = if (selected) "Seleccionada" else "No seleccionada"
    }

    private fun clearPlayback() {
        generation.incrementAndGet()
        pausePlayback()
        mainHandler.removeCallbacks(progressTick)
        runCatching { videoPlayer?.setSurface(null) }
        runCatching { videoPlayer?.release() }
        runCatching { audioPlayer?.release() }
        videoPlayer = null
        audioPlayer = null
        videoPrepared = false
        lastAudioDriftLogElapsedMs = 0L
        lastVideoProgressMs = 0
        playWhenReady = false
        playbackKind = PlaybackKind.NONE
        if (::selectButton.isInitialized) updateSelectionToolbar()
        activeSummary = null
        activeRawClip?.close()
        activeRawClip = null
        activeDngClip = null
        activeFrameIndex = 0
        requestedFrameIndex = 0
        resetSequenceDecodeQueue()
        bufferingAnchorIndex = 0
        bufferingTargetFrames = 0
        bufferingResumePositionMs = 0
        sequenceView?.setImageDrawable(null)
        displayedBitmap = null
        frameBuffer.clear()
        textureView = null
        sequenceView = null
        stage = null
        stageStatus = null
        detailView = null
        positionView = null
        durationView = null
        frameView = null
        seekBar = null
        playButton = null
        audioButton = null
    }

    private fun setContent(view: View, animate: Boolean = true) {
        content.removeAllViews()
        view.alpha = if (animate) 0f else 1f
        view.translationY = if (animate) dp(6).toFloat() else 0f
        content.addView(view, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        if (!animate) return
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(CONTENT_TRANSITION_MS)
            .setInterpolator(PathInterpolator(0.4f, 0f, 0.2f, 1f))
            .start()
    }

    private fun messageView(message: String): View = TextView(this).apply {
        text = message
        textSize = 12f
        typeface = uiTypeface
        gravity = Gravity.CENTER
        setTextColor(RawLiteDesign.TEXT_MUTED)
    }

    private fun labelText(value: String, size: Float, color: Int): TextView = TextView(this).apply {
        text = value
        textSize = size
        typeface = uiTypeface
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(color)
    }

    private fun transportText(value: String, alignment: Int): TextView = TextView(this).apply {
        text = value
        textSize = 10f
        typeface = uiTypeface
        gravity = alignment or Gravity.CENTER_VERTICAL
        setTextColor(RawLiteDesign.TEXT_MUTED)
    }

    private fun iconButton(icon: Int, description: String, action: () -> Unit): ImageButton {
        return ImageButton(this).apply {
            setImageResource(icon)
            setColorFilter(RawLiteDesign.TEXT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = transportControl(primary = false)
            contentDescription = description
            tooltipText = description
            setOnClickListener { action() }
        }
    }

    private fun transportControl(primary: Boolean): StateListDrawable = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_pressed),
            transportControlShape(primary, pressed = true)
        )
        addState(intArrayOf(), transportControlShape(primary, pressed = false))
    }

    private fun transportControlShape(primary: Boolean, pressed: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = if (primary) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            cornerRadius = if (primary) 0f else dp(RawLiteDesign.RADIUS_SMALL_DP).toFloat()
            setColor(
                when {
                    primary && pressed -> RawLiteDesign.ACCENT
                    primary -> 0xFFF3F2EE.toInt()
                    pressed -> RawLiteDesign.SURFACE_ACTIVE
                    else -> Color.TRANSPARENT
                }
            )
            if (primary) {
                setStroke(
                    dp(1),
                    if (pressed) RawLiteDesign.ACCENT else RawLiteDesign.OUTLINE
                )
            }
        }

    private fun configureTransportSeekBar(bar: SeekBar) {
        val track = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(1).toFloat()
            setColor(0x70FFFFFF)
            setSize(dp(1), dp(2))
        }
        val progress = ClipDrawable(
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(1).toFloat()
                setColor(RawLiteDesign.ACCENT)
                setSize(dp(1), dp(2))
            },
            Gravity.START,
            ClipDrawable.HORIZONTAL
        )
        bar.progressDrawable = LayerDrawable(arrayOf(track, progress)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
            setLayerInset(0, 0, dp(16), 0, dp(16))
            setLayerInset(1, 0, dp(16), 0, dp(16))
        }
        bar.progressBackgroundTintList = ColorStateList.valueOf(0x70FFFFFF)
        bar.progressTintList = ColorStateList.valueOf(RawLiteDesign.ACCENT)
        bar.thumb = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(1).toFloat()
            setColor(RawLiteDesign.ACCENT)
            setStroke(dp(1), RawLiteDesign.BLACK)
            setSize(dp(8), dp(18))
        }
        bar.thumbOffset = 0
    }

    private fun selectableSurface(): android.graphics.drawable.StateListDrawable {
        return android.graphics.drawable.StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                roundRect(RawLiteDesign.SURFACE_ACTIVE, dp(RawLiteDesign.RADIUS_SMALL_DP))
            )
            addState(
                intArrayOf(),
                roundRect(RawLiteDesign.BLACK, dp(RawLiteDesign.RADIUS_SMALL_DP))
            )
        }
    }

    private fun roundRect(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun recycleThumbnails() {
        ambientBackground.setImageDrawable(null)
        ambientBitmap?.takeIf { !it.isRecycled }?.recycle()
        ambientBitmap = null
        thumbnailCache.values.distinct().forEach { if (!it.isRecycled) it.recycle() }
        thumbnailCache.clear()
    }

    private fun pruneThumbnailCache(clips: List<ClipSummary>) {
        val livePaths = clips.mapTo(mutableSetOf()) { it.directory.absolutePath }
        val iterator = thumbnailCache.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!livePaths.contains(entry.key)) {
                if (!entry.value.isRecycled) entry.value.recycle()
                iterator.remove()
            }
        }
    }

    private fun formatDuration(seconds: Double): String = formatTimecode((seconds * 1000).toInt())

    private fun formatTimecode(milliseconds: Int): String {
        val total = milliseconds.coerceAtLeast(0)
        val minutes = total / 60_000
        val seconds = total / 1_000 % 60
        val millis = total % 1_000
        return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
        bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
        else -> String.format(Locale.US, "%.1f KB", bytes / 1e3)
    }

    private fun space(width: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(width, 1)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val FRAME_CACHE_SIZE = 20
        private const val SEQUENCE_BUFFER_TARGET_MS = 750L
        private const val THUMBNAIL_CACHE_SIZE = 32
        private const val DNG_PREVIEW_MAX_WIDTH = 960
        private const val DNG_PREVIEW_MAX_HEIGHT = 540
        private const val PROGRESS_INTERVAL_MS = 33L
        private const val AUDIO_DRIFT_LOG_INTERVAL_MS = 1_000L
        private const val AUDIO_END_TOLERANCE_MS = 5
        private const val AUDIO_RESUME_TOLERANCE_MS = 60
        private const val PURE10_AUDIO_OUTPUT_LEAD_MS = 45
        private const val VIDEO_CLOCK_BACKWARD_TOLERANCE_MS = 250
        private const val AUDIO_SYNC_TAG = "RawLiteAudioSync"
        private const val CONTENT_TRANSITION_MS = 240L
        private const val SELECTION_ACTION_ARM_DELAY_MS = 650L
    }
}
