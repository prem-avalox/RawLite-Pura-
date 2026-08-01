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
import android.os.SystemClock
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
    private lateinit var content: FrameLayout
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private val decodeInFlight = AtomicBoolean(false)
    private val uiTypeface by lazy { resources.getFont(R.font.open_sans) }

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
    private var audioPlayer: MediaPlayer? = null
    private var videoPrepared = false
    private var audioPrepared = false
    private var playWhenReady = false
    private var playing = false
    private var muted = false
    private var audioOffsetMs = 0
    private var audioSeekInFlight = false
    private var audioStartAfterSeek = false
    private var lastAudioResyncElapsedMs = 0L
    private var restorePositionMs: Int? = null
    private var restoreShouldPlay = false

    private var rawPlaybackStartElapsedMs = 0L
    private var rawPlaybackStartTimeline = 0

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
        if (playbackKind != PlaybackKind.NONE) {
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
        toolbar.addView(backButton, LinearLayout.LayoutParams(dp(52), dp(52)))
        toolbar.addView(titleView, LinearLayout.LayoutParams(0, dp(52), 1f))
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
        titleView.text = "TOMAS"
        backButton.contentDescription = "Volver a la cámara"
        setContent(messageView("LEYENDO TOMAS"))
        worker.execute {
            val base = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            val clips = base
                ?.listFiles { file -> file.isDirectory && file.name.startsWith("RawLite_") }
                ?.mapNotNull { directory ->
                    Pure10Clip.inspect(directory)
                        ?: Raw10Clip.inspect(directory)
                        ?: DngSequenceClip.inspect(directory)
                }
                ?.sortedByDescending { it.directory.lastModified() }
                .orEmpty()
            runOnUiThread {
                if (generation.get() == currentGeneration && !isFinishing) renderClipList(clips)
            }
        }
    }

    private fun renderClipList(clips: List<ClipSummary>) {
        if (clips.isEmpty()) {
            setContent(messageView("NO HAY TOMAS"))
            return
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(28))
        }
        list.addView(TextView(this).apply {
            text = "${clips.size} ${if (clips.size == 1) "TOMA" else "TOMAS"}   " +
                formatBytes(clips.sumOf { it.byteCount })
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
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            isFocusable = true
            background = selectableSurface()
            setOnClickListener { openClip(summary) }
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
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_transport_play)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = transportControl(primary = false)
            contentDescription = "Reproducir toma"
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        loadThumbnail(summary, thumbnail)
        return row
    }

    private fun portraitClipCard(summary: ClipSummary): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            background = selectableSurface()
            setOnClickListener { openClip(summary) }
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
                        ?.let { orientBitmap(it, summary.directory) }
                } finally {
                    retriever.release()
                }
            }
            "CinemaDNG" -> DngSequenceClip.open(summary.directory).decodeFrame(0, 320, 240)
            else -> Raw10Clip.open(summary.directory).decodeFrame(0, 320, 240)
        }
    }

    private fun openClip(summary: ClipSummary) {
        clearPlayback()
        activeSummary = summary
        titleView.text = summary.directory.name.removePrefix("RawLite_")
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
            setVideoGeometry(summary.width, summary.height, readRotation(summary.directory))
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
                textureView?.setVideoGeometry(width, height, readRotation(summary.directory))
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
                prepareAudio(summary.directory)
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
            audioPlayer?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
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
        if (playing) pausePlayback() else startPlayback()
    }

    private fun startPlayback() {
        when (playbackKind) {
            PlaybackKind.PURE10 -> {
                if (!videoPrepared) {
                    playWhenReady = true
                    return
                }
                val player = videoPlayer ?: return
                if (player.currentPosition >= player.duration - 20) seekToPosition(0)
                player.start()
                syncAndStartAudio(player.currentPosition)
            }
            PlaybackKind.RAW10, PlaybackKind.DNG -> {
                val summary = activeSummary ?: return
                val timeline = sequenceTimelineAt(activeFrameIndex)
                rawPlaybackStartTimeline = timeline
                rawPlaybackStartElapsedMs = SystemClock.elapsedRealtime()
                syncAndStartAudio(sequencePositionMs(activeFrameIndex))
                if (activeFrameIndex >= sequenceFrameCount() - 1) {
                    activeFrameIndex = 0
                    requestedFrameIndex = 0
                    rawPlaybackStartTimeline = sequenceTimelineAt(0)
                    requestSequenceFrame(0)
                }
                if (summary.frameCount <= 0) return
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
        if (!playing) return
        playing = false
        audioStartAfterSeek = false
        runCatching { if (videoPlayer?.isPlaying == true) videoPlayer?.pause() }
        runCatching { if (audioPlayer?.isPlaying == true) audioPlayer?.pause() }
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
        seekBar?.progress = position.coerceAtMost(seekBar?.max ?: position)
        updateTimeLabels(position)
        keepAudioSynchronized(position)
    }

    private fun updateSequenceProgress() {
        val summary = activeSummary ?: return
        val elapsed = SystemClock.elapsedRealtime() - rawPlaybackStartElapsedMs
        val targetTimeline = rawPlaybackStartTimeline +
            (elapsed * summary.fps / 1000L).toInt()
        val lastTimeline = sequenceTimelineAt(sequenceFrameCount() - 1)
        if (targetTimeline >= lastTimeline) {
            requestSequenceFrame(sequenceFrameCount() - 1)
            pausePlayback()
            return
        }
        val targetIndex = sequenceIndexForTimeline(targetTimeline)
        if (targetIndex != requestedFrameIndex) requestSequenceFrame(targetIndex)
        val position = ((targetTimeline - sequenceTimelineAt(0)).coerceAtLeast(0) * 1000L /
            summary.fps).toInt()
        seekBar?.progress = position.coerceIn(0, seekBar?.max ?: position)
        updateTimeLabels(position)
        keepAudioSynchronized(position)
    }

    private fun seekToPosition(positionMs: Int) {
        val bounded = positionMs.coerceIn(0, seekBar?.max ?: positionMs)
        when (playbackKind) {
            PlaybackKind.PURE10 -> {
                videoPlayer?.seekTo(bounded.toLong(), MediaPlayer.SEEK_CLOSEST)
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
        var low = 0
        var high = sequenceFrameCount() - 1
        var result = 0
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (sequenceTimelineAt(middle) <= timeline) {
                result = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return result
    }

    private fun sequencePositionMs(index: Int): Int {
        val fps = activeSummary?.fps?.coerceAtLeast(1) ?: 1
        return ((sequenceTimelineAt(index) - sequenceTimelineAt(0)).coerceAtLeast(0) * 1000L /
            fps).toInt()
    }

    private fun prepareAudio(directory: File) {
        val file = File(directory, "audio.wav")
        audioOffsetMs = readAudioOffsetMs(directory)
        if (!file.isFile) {
            audioPrepared = false
            updateAudioButton()
            return
        }
        val player = MediaPlayer()
        audioPlayer = player
        runCatching {
            player.setDataSource(file.absolutePath)
            player.setOnSeekCompleteListener { completedPlayer ->
                if (completedPlayer !== audioPlayer) return@setOnSeekCompleteListener
                audioSeekInFlight = false
                if (audioStartAfterSeek) {
                    audioStartAfterSeek = false
                    if (playing && completedPlayer.currentPosition < completedPlayer.duration - 10) {
                        completedPlayer.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
                        completedPlayer.start()
                    }
                }
            }
            player.setOnPreparedListener {
                audioPrepared = true
                updateAudioButton()
                if (playing) syncAndStartAudio(currentPositionMs())
            }
            player.setOnErrorListener { _, _, _ ->
                audioPrepared = false
                updateAudioButton()
                true
            }
            player.prepareAsync()
        }.onFailure {
            audioPrepared = false
            updateAudioButton()
        }
    }

    private fun syncAndStartAudio(videoPositionMs: Int) {
        val player = audioPlayer ?: return
        if (!audioPrepared) return
        player.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
        val target = audioTargetMs(player, videoPositionMs)
        if (!audioSeekInFlight && abs(player.currentPosition - target) <= AUDIO_START_TOLERANCE_MS) {
            if (target < player.duration - 10) player.start()
            return
        }
        audioStartAfterSeek = true
        requestAudioSeek(player, target)
    }

    private fun seekAudio(videoPositionMs: Int) {
        val player = audioPlayer ?: return
        if (!audioPrepared) return
        audioStartAfterSeek = false
        requestAudioSeek(player, audioTargetMs(player, videoPositionMs))
    }

    private fun keepAudioSynchronized(videoPositionMs: Int) {
        val player = audioPlayer ?: return
        if (!audioPrepared || !playing) return
        val expected = audioTargetMs(player, videoPositionMs)
        val now = SystemClock.elapsedRealtime()
        if (!audioSeekInFlight &&
            now - lastAudioResyncElapsedMs >= AUDIO_RESYNC_MIN_INTERVAL_MS &&
            abs(player.currentPosition - expected) > AUDIO_RESYNC_THRESHOLD_MS
        ) {
            lastAudioResyncElapsedMs = now
            audioStartAfterSeek = true
            requestAudioSeek(player, expected)
            return
        }
        if (!audioSeekInFlight && !player.isPlaying && expected < player.duration - 10) player.start()
    }

    private fun audioTargetMs(player: MediaPlayer, videoPositionMs: Int): Int {
        return (videoPositionMs + audioOffsetMs).coerceIn(0, player.duration.coerceAtLeast(0))
    }

    private fun requestAudioSeek(player: MediaPlayer, targetMs: Int) {
        if (audioSeekInFlight) return
        audioSeekInFlight = true
        runCatching { player.seekTo(targetMs.toLong(), MediaPlayer.SEEK_CLOSEST) }
            .onFailure {
                audioSeekInFlight = false
                audioStartAfterSeek = false
            }
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

    private fun orientBitmap(bitmap: Bitmap, directory: File): Bitmap {
        val metadata = runCatching {
            JSONObject(File(directory, "clip.json").readText())
        }.getOrNull()
        val rotation = metadata?.optInt("rotation_degrees", 0) ?: 0
        val mirrored = metadata?.optBoolean("front_facing", false) == true
        if (rotation % 360 == 0 && !mirrored) return bitmap
        val matrix = Matrix().apply {
            postRotate((((rotation % 360) + 360) % 360).toFloat())
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
                    val deleted = summary.directory.deleteRecursively()
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
        audioPrepared = false
        audioSeekInFlight = false
        audioStartAfterSeek = false
        lastAudioResyncElapsedMs = 0L
        playWhenReady = false
        playbackKind = PlaybackKind.NONE
        activeSummary = null
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
        private const val AUDIO_START_TOLERANCE_MS = 24
        private const val AUDIO_RESYNC_THRESHOLD_MS = 240
        private const val AUDIO_RESYNC_MIN_INTERVAL_MS = 1_000L
        private const val CONTENT_TRANSITION_MS = 240L
    }
}
