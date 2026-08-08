package com.example.rawlite

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

class ReviewActivity : Activity() {
    private enum class PlaybackKind { NONE, PURE10, RAW10, DNG }

    private lateinit var titleView: TextView
    private lateinit var backButton: TextView
    private lateinit var selectButton: TextView
    private lateinit var content: FrameLayout
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private val decodeInFlight = AtomicBoolean(false)
    private val clipRepository = ClipRepository()
    private val uiTypeface by lazy { resources.getFont(R.font.open_sans) }
    private var galleryClips: List<ClipSummary> = emptyList()
    private var selectionMode = false
    private val selectedClipPaths = linkedSetOf<String>()

    private var playbackKind = PlaybackKind.NONE
    private var activeSummary: ClipSummary? = null
    private var activeRawClip: Raw10Clip? = null
    private var activeDngClip: DngSequenceClip? = null
    private var activeFrameIndex = 0
    private var requestedFrameIndex = 0
    private var displayedBitmap: Bitmap? = null
    private val frameCache = object : LinkedHashMap<Int, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?): Boolean {
            if (size <= FRAME_CACHE_SIZE || eldest == null) return false
            if (eldest.value !== displayedBitmap) eldest.value.recycle()
            return true
        }
    }
    private val thumbnailBitmaps = mutableListOf<Bitmap>()

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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(RawLiteDesign.BLACK)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(16), dp(4))
            setBackgroundColor(RawLiteDesign.BLACK)
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(dp(8), insets.systemWindowInsetTop + dp(4), dp(16), dp(4))
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
        titleView = TextView(this).apply {
            text = "TOMAS"
            textSize = 17f
            typeface = uiTypeface
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(RawLiteDesign.TEXT)
        }
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
        toolbar.addView(backButton, LinearLayout.LayoutParams(dp(52), dp(52)))
        toolbar.addView(titleView, LinearLayout.LayoutParams(0, dp(52), 1f))
        toolbar.addView(selectButton, LinearLayout.LayoutParams(dp(112), dp(44)))
        root.addView(toolbar)
        root.addView(View(this).apply { setBackgroundColor(RawLiteDesign.DIVIDER) },
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1))

        content = FrameLayout(this).apply { setBackgroundColor(RawLiteDesign.BLACK) }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun showClipList() {
        clearPlayback()
        recycleThumbnails()
        val currentGeneration = generation.incrementAndGet()
        selectedClipPaths.removeAll { selected ->
            galleryClips.none { it.directory.absolutePath == selected }
        }
        titleView.text = "TOMAS"
        backButton.contentDescription = "Volver a la cámara"
        selectButton.visibility = View.GONE
        setContent(messageView("LEYENDO TOMAS"))
        worker.execute {
            val base = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            val clips = clipRepository.list(base)
            runOnUiThread {
                if (generation.get() == currentGeneration && !isFinishing) {
                    galleryClips = clips
                    if (galleryClips.isEmpty()) {
                        selectionMode = false
                        selectedClipPaths.clear()
                    }
                    renderClipList(clips)
                }
            }
        }
    }

    private fun renderClipList(clips: List<ClipSummary>) {
        if (clips.isEmpty()) {
            selectButton.visibility = View.GONE
            setContent(messageView("NO HAY TOMAS"))
            return
        }
        recycleThumbnails()
        updateSelectionToolbar()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(28))
        }
        if (selectionMode) {
            list.addView(selectionActionBar(), LinearLayout.LayoutParams.MATCH_PARENT, dp(60))
        }
        list.addView(TextView(this).apply {
            text = if (selectionMode) {
                "${selectedClipPaths.size}/${clips.size} SELECCIONADAS   " +
                    formatBytes(selectedClips().sumOf { it.byteCount })
            } else {
                "${clips.size} ${if (clips.size == 1) "TOMA" else "TOMAS"}   " +
                    formatBytes(clips.sumOf { it.byteCount })
            }
            textSize = 11f
            typeface = uiTypeface
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(RawLiteDesign.ACCENT)
        }, LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            clips.chunked(2).forEachIndexed { rowIndex, pair ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEachIndexed { columnIndex, summary ->
                    row.addView(clipRow(summary), LinearLayout.LayoutParams(0, dp(102), 1f))
                    if (columnIndex == 0 && pair.size > 1) {
                        row.addView(View(this).apply { setBackgroundColor(RawLiteDesign.DIVIDER) },
                            LinearLayout.LayoutParams(dp(1), dp(82)).apply {
                                gravity = Gravity.CENTER_VERTICAL
                            })
                    }
                }
                if (pair.size == 1) row.addView(space(1), LinearLayout.LayoutParams(0, 1, 1f))
                list.addView(row, LinearLayout.LayoutParams.MATCH_PARENT, dp(102))
                if (rowIndex != clips.chunked(2).lastIndex) {
                    list.addView(View(this).apply { setBackgroundColor(RawLiteDesign.DIVIDER) },
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                }
            }
        } else {
            clips.forEachIndexed { index, summary ->
                list.addView(
                    portraitClipCard(summary),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                if (index != clips.lastIndex) {
                    list.addView(
                        View(this).apply { setBackgroundColor(RawLiteDesign.DIVIDER) },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(1)
                        ).apply {
                            topMargin = dp(14)
                            bottomMargin = dp(14)
                        }
                    )
                }
            }
        }
        setContent(ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list)
        })
    }

    private fun clipRow(summary: ClipSummary): View {
        val selected = isSelected(summary)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            isFocusable = true
            background = selectableSurface(selected)
            setOnClickListener {
                if (selectionMode) toggleSelection(summary) else openClip(summary)
            }
            setOnLongClickListener {
                enterSelectionMode(summary)
                true
            }
        }
        if (selectionMode) {
            row.addView(selectionBadge(selected), LinearLayout.LayoutParams(dp(36), dp(48)))
        }
        val thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(RawLiteDesign.SURFACE_RAISED)
            clipToOutline = true
            outlineProvider = roundedOutlineProvider(dp(RawLiteDesign.RADIUS_SMALL_DP).toFloat())
            contentDescription = "Vista previa de la toma"
        }
        row.addView(thumbnail, LinearLayout.LayoutParams(dp(112), dp(78)))

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        val date = SimpleDateFormat("dd MMM  HH:mm", Locale.getDefault())
            .format(Date(summary.directory.lastModified()))
        textColumn.addView(labelText(date, 14f, RawLiteDesign.TEXT))
        textColumn.addView(labelText(
            "${summary.captureFormat}  ${summary.width}×${summary.height}  ${summary.fps} FPS",
            11f,
            RawLiteDesign.TEXT_MUTED
        ))
        textColumn.addView(labelText(
            "${formatDuration(summary.durationSeconds)}  ${formatBytes(summary.byteCount)}" +
                if (summary.audioDescription != null) "  AUDIO" else "  SIN AUDIO",
            11f,
            if (summary.finalized) RawLiteDesign.ACCENT else RawLiteDesign.RECORD
        ))
        row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!selectionMode) {
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_transport_play)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = transportControl(primary = false)
                contentDescription = "Reproducir toma"
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        loadThumbnail(summary, thumbnail)
        return row
    }

    private fun portraitClipCard(summary: ClipSummary): View {
        val selected = isSelected(summary)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            background = selectableSurface(selected)
            setOnClickListener {
                if (selectionMode) toggleSelection(summary) else openClip(summary)
            }
            setOnLongClickListener {
                enterSelectionMode(summary)
                true
            }
        }
        val imageStage = FrameLayout(this).apply {
            setBackgroundColor(RawLiteDesign.SURFACE_RAISED)
            clipToOutline = true
            outlineProvider = roundedOutlineProvider(dp(RawLiteDesign.RADIUS_SMALL_DP).toFloat())
        }
        val thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(RawLiteDesign.SURFACE_RAISED)
            contentDescription = "Vista previa de la toma"
        }
        imageStage.addView(
            thumbnail,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        imageStage.addView(TextView(this).apply {
            text = summary.captureFormat.uppercase(Locale.getDefault())
            textSize = 10f
            typeface = uiTypeface
            gravity = Gravity.CENTER
            setTextColor(RawLiteDesign.ACCENT)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = roundRect(0xD9000000.toInt(), dp(3))
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.TOP
        ).apply {
            marginStart = dp(12)
            topMargin = dp(12)
        })
        if (selectionMode) {
            imageStage.addView(selectionBadge(selected), FrameLayout.LayoutParams(
                dp(40),
                dp(40),
                Gravity.END or Gravity.TOP
            ).apply {
                marginEnd = dp(12)
                topMargin = dp(12)
            })
        } else {
            imageStage.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_transport_play)
                setColorFilter(RawLiteDesign.BLACK)
                setPadding(dp(17), dp(17), dp(17), dp(17))
                background = transportControl(primary = true)
                contentDescription = "Reproducir toma"
            }, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END or Gravity.BOTTOM).apply {
                marginEnd = dp(12)
                bottomMargin = dp(12)
            })
        }
        card.addView(imageStage, LinearLayout.LayoutParams.MATCH_PARENT, dp(188))

        val date = SimpleDateFormat("dd MMM  HH:mm", Locale.getDefault())
            .format(Date(summary.directory.lastModified()))
        val metadata = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(14))
        }
        metadata.addView(labelText(date, 15f, RawLiteDesign.TEXT))
        metadata.addView(labelText(
            "${summary.width}×${summary.height}   ${summary.fps} FPS   ${summary.camera}",
            12f,
            RawLiteDesign.TEXT_MUTED
        ))
        metadata.addView(labelText(
            "${formatDuration(summary.durationSeconds)}   ${formatBytes(summary.byteCount)}" +
                if (summary.audioDescription != null) "   AUDIO" else "   SIN AUDIO",
            12f,
            if (summary.finalized) RawLiteDesign.ACCENT else RawLiteDesign.RECORD
        ))
        card.addView(metadata)
        loadThumbnail(summary, thumbnail)
        return card
    }

    private fun loadThumbnail(summary: ClipSummary, target: ImageView) {
        val currentGeneration = generation.get()
        worker.execute {
            val bitmap = runCatching { decodeThumbnail(summary) }.getOrNull()
            runOnUiThread {
                if (bitmap == null || generation.get() != currentGeneration || isFinishing) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }
                thumbnailBitmaps += bitmap
                target.setImageBitmap(bitmap)
            }
        }
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
        activeSummary = summary
        titleView.text = CaptureStorage.displayName(summary.directory.name)
        backButton.contentDescription = "Volver a tomas"
        when (summary.captureFormat) {
            "PURE 10" -> buildPure10Player(summary)
            "CinemaDNG" -> openSequence(summary, PlaybackKind.DNG)
            else -> openSequence(summary, PlaybackKind.RAW10)
        }
    }

    private fun buildPure10Player(summary: ClipSummary) {
        playbackKind = PlaybackKind.PURE10
        val playerUi = buildPlayerShell(summary, usesFrames = false)
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
                    val playerUi = buildPlayerShell(summary, usesFrames = true)
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

    private fun buildPlayerShell(
        summary: ClipSummary,
        usesFrames: Boolean
    ): Pair<FrameLayout, LinearLayout> {
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
        transport.addView(detailView, LinearLayout.LayoutParams.MATCH_PARENT, dp(26))

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
        frameView = transportText(if (usesFrames) "F 1/1" else "10 BIT", Gravity.CENTER)
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
            updateAudioButton()
        }
        controls.addView(audioButton, LinearLayout.LayoutParams(dp(52), dp(52)))
        controls.addView(space(dp(12)))
        controls.addView(iconButton(R.drawable.ic_transport_delete, "Borrar toma") {
            confirmDelete(summary)
        }.apply { setColorFilter(RawLiteDesign.RECORD) }, LinearLayout.LayoutParams(dp(52), dp(52)))
        transport.addView(controls, LinearLayout.LayoutParams.MATCH_PARENT, dp(68))
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
                    requestSequenceFrame(0)
                }
                if (summary.frameCount <= 0) return
                sequencePositionMs(activeFrameIndex)
            }
            PlaybackKind.NONE -> return
        }

        val audio = audioPlayer
        if (audio != null) {
            val expected = audioTargetMs(audio, positionMs)
            val target = audioSeekTargetMs(audio, positionMs)
            if (abs(audio.currentPosition - expected) > AUDIO_RESUME_TOLERANCE_MS) {
                audio.seekTo(target)
            }
            audio.setVolume(if (muted) 0f else 1f)
            if (target < audio.duration - 10) {
                audio.play()
            }
        }

        startVisualPlayback(positionMs)
    }

    private fun startVisualPlayback(positionMs: Int) {
        if (playing) return
        when (playbackKind) {
            PlaybackKind.PURE10 -> {
                lastVideoProgressMs = positionMs
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
        runCatching { audioPlayer?.pause() }
        runCatching { if (videoPlayer?.isPlaying == true) videoPlayer?.pause() }
        mainHandler.removeCallbacks(progressTick)
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
        if (targetIndex != requestedFrameIndex) requestSequenceFrame(targetIndex)
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
                requestSequenceFrame((activeFrameIndex + direction).coerceIn(0, sequenceFrameCount() - 1))
            }
            PlaybackKind.NONE -> Unit
        }
    }

    private fun requestSequenceFrame(index: Int, autoStart: Boolean = false) {
        val count = sequenceFrameCount()
        if (count <= 0) return
        requestedFrameIndex = index.coerceIn(0, count - 1)
        frameCache[requestedFrameIndex]?.let {
            displaySequenceFrame(requestedFrameIndex, it)
            if (autoStart && playWhenReady) startPlayback()
            return
        }
        if (!decodeInFlight.compareAndSet(false, true)) return
        val decodeIndex = requestedFrameIndex
        val currentGeneration = generation.get()
        if (displayedBitmap == null) {
            stageStatus?.apply {
                text = if (playbackKind == PlaybackKind.DNG) "REVELANDO DNG" else "REVELANDO RAW10"
                visibility = View.VISIBLE
            }
        }
        worker.execute {
            val result = runCatching { decodeSequenceFrame(decodeIndex) }
            runOnUiThread {
                decodeInFlight.set(false)
                if (generation.get() != currentGeneration || playbackKind == PlaybackKind.NONE) {
                    result.getOrNull()?.recycle()
                    return@runOnUiThread
                }
                result.onSuccess { bitmap ->
                    frameCache[decodeIndex] = bitmap
                    if (decodeIndex == requestedFrameIndex || playing) {
                        displaySequenceFrame(decodeIndex, bitmap)
                    }
                    if (autoStart && playWhenReady) startPlayback()
                }.onFailure {
                    stageStatus?.apply {
                        text = it.message ?: "NO SE PUDO REVELAR EL FOTOGRAMA"
                        setTextColor(RawLiteDesign.RECORD)
                        visibility = View.VISIBLE
                    }
                }
                if (requestedFrameIndex != decodeIndex && frameCache[requestedFrameIndex] == null) {
                    requestSequenceFrame(requestedFrameIndex)
                }
            }
        }
    }

    private fun decodeSequenceFrame(index: Int): Bitmap {
        return when (playbackKind) {
            PlaybackKind.RAW10 -> requireNotNull(activeRawClip).decodeFrame(index)
            PlaybackKind.DNG -> requireNotNull(activeDngClip).decodeFrame(index)
            else -> error("No hay secuencia activa")
        }
    }

    private fun displaySequenceFrame(index: Int, bitmap: Bitmap) {
        activeFrameIndex = index
        displayedBitmap = bitmap
        sequenceView?.setImageBitmap(bitmap)
        stageStatus?.visibility = View.GONE
        val position = sequencePositionMs(index)
        if (!playing) {
            seekBar?.progress = position.coerceIn(0, seekBar?.max ?: position)
            updateTimeLabels(position)
        }
        val timelineFrame = sequenceTimelineAt(index) + 1
        val timelineCount = activeSummary?.timelineFrameCount ?: sequenceFrameCount()
        frameView?.text = if (timelineCount > sequenceFrameCount()) {
            "F ${index + 1}/${sequenceFrameCount()}  T $timelineFrame/$timelineCount"
        } else {
            "F ${index + 1}/${sequenceFrameCount()}"
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
        if (playbackKind == PlaybackKind.PURE10) frameView?.text = "10 BIT"
    }

    private fun updateAudioButton() {
        val button = audioButton ?: return
        val available = audioPlayer != null
        button.isEnabled = available
        button.alpha = if (available) 1f else 0.35f
        button.setImageResource(
            if (muted) R.drawable.ic_transport_mute
            else R.drawable.ic_transport_audio
        )
        button.contentDescription = if (muted) "Activar audio" else "Silenciar audio"
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
        initial?.let { selectedClipPaths += it.directory.absolutePath }
        renderClipList(galleryClips)
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedClipPaths.clear()
        renderClipList(galleryClips)
    }

    private fun toggleSelection(summary: ClipSummary) {
        val key = summary.directory.absolutePath
        if (!selectedClipPaths.add(key)) selectedClipPaths.remove(key)
        if (selectedClipPaths.isEmpty()) {
            selectionMode = false
        }
        renderClipList(galleryClips)
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
        selectButton.text = if (selectionMode) "CANCELAR" else "SEL"
        selectButton.contentDescription = if (selectionMode) "Cancelar selección" else "Seleccionar tomas"
        selectButton.setOnClickListener {
            if (selectionMode) exitSelectionMode() else enterSelectionMode()
        }
        titleView.text = if (selectionMode) "SELECCIÓN" else "TOMAS"
    }

    private fun selectionActionBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(selectionActionButton("CONVERTIR", RawLiteDesign.ACCENT) {
                showConversionWarning(selectedClips())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(space(dp(8)))
            addView(selectionActionButton("BORRAR", RawLiteDesign.RECORD) {
                confirmDeleteSelected(selectedClips())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(space(dp(8)))
            addView(selectionActionButton("CANCELAR", RawLiteDesign.TEXT) {
                exitSelectionMode()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
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
            background = roundRect(RawLiteDesign.SURFACE_RAISED, dp(RawLiteDesign.RADIUS_SMALL_DP))
            setOnClickListener { if (selectedClipPaths.isNotEmpty() || label == "CANCELAR") action() }
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
        decodeInFlight.set(false)
        sequenceView?.setImageDrawable(null)
        displayedBitmap = null
        frameCache.values.distinct().forEach { if (!it.isRecycled) it.recycle() }
        frameCache.clear()
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

    private fun setContent(view: View) {
        content.removeAllViews()
        view.alpha = 0f
        view.translationY = dp(6).toFloat()
        content.addView(view, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
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

    private fun selectableSurface(selected: Boolean = false): android.graphics.drawable.StateListDrawable {
        return android.graphics.drawable.StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                roundRect(RawLiteDesign.SURFACE_ACTIVE, dp(RawLiteDesign.RADIUS_SMALL_DP))
            )
            addState(
                intArrayOf(),
                roundRect(
                    if (selected) RawLiteDesign.ACCENT_SURFACE else RawLiteDesign.BLACK,
                    dp(RawLiteDesign.RADIUS_SMALL_DP)
                )
            )
        }
    }

    private fun roundedOutlineProvider(radius: Float): android.view.ViewOutlineProvider {
        return object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }

    private fun roundRect(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun recycleThumbnails() {
        thumbnailBitmaps.distinct().forEach { if (!it.isRecycled) it.recycle() }
        thumbnailBitmaps.clear()
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
        private const val FRAME_CACHE_SIZE = 6
        private const val PROGRESS_INTERVAL_MS = 33L
        private const val AUDIO_DRIFT_LOG_INTERVAL_MS = 1_000L
        private const val AUDIO_END_TOLERANCE_MS = 5
        private const val AUDIO_RESUME_TOLERANCE_MS = 60
        private const val PURE10_AUDIO_OUTPUT_LEAD_MS = 45
        private const val VIDEO_CLOCK_BACKWARD_TOLERANCE_MS = 250
        private const val AUDIO_SYNC_TAG = "RawLiteAudioSync"
        private const val CONTENT_TRANSITION_MS = 240L
    }
}
