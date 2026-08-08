package com.example.rawlite

import android.graphics.Bitmap
import android.graphics.Matrix
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

internal data class RawFrameInfo(
    val frame: Int,
    val timestampNs: Long,
    val iso: Int,
    val exposureNs: Long,
    val frameDurationNs: Long,
    val focusDiopters: Float,
    val wbRed: Float,
    val wbGreenEven: Float,
    val wbGreenOdd: Float,
    val wbBlue: Float
)

internal data class ClipSummary(
    val directory: File,
    val camera: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val frameCount: Int,
    val timelineFrameCount: Int,
    val byteCount: Long,
    val finalized: Boolean,
    val audioDescription: String?,
    val captureFormat: String = "RAW10",
    val videoFileName: String? = null
) {
    val durationSeconds: Double
        get() = if (fps > 0) timelineFrameCount.toDouble() / fps else 0.0
}

internal class Raw10Clip private constructor(
    val summary: ClipSummary,
    private val metadata: JSONObject,
    val frames: List<RawFrameInfo>
) : Closeable {
    private val rawFile = File(summary.directory, "clip.raw10")
    private val rowBytes = metadata.getInt("row_bytes")
    private val frameBytes = rowBytes.toLong() * summary.height
    private val cfa = metadata.optString("cfa", "RGGB").uppercase(Locale.US)
    private val whiteLevel = metadata.optInt("white_level", 1023).coerceAtLeast(1)
    private val blackLevels = IntArray(4) { index ->
        metadata.optJSONArray("black_level")?.optInt(index, 0) ?: 0
    }
    private val rotationDegrees = VideoOrientation.rawSequenceRotationDegrees(
        metadataRotationDegrees = metadata.optInt(
            "rotation_degrees",
            metadata.optInt("sensor_orientation", 0)
        ),
        sensorOrientationDegrees = metadata.optInt("sensor_orientation", 0),
        uiContentRotationDegrees = if (metadata.has("ui_content_rotation_degrees")) {
            metadata.optInt("ui_content_rotation_degrees", 0)
        } else {
            null
        },
        frontFacing = metadata.optBoolean("front_facing", false)
    )
    private val mirrorPreview = metadata.optBoolean("front_facing", false)
    private val rawStream = RandomAccessFile(rawFile, "r")
    private val rawChannel = rawStream.channel
    private val packedFrame = ByteBuffer.allocateDirect(frameBytes.toInt())
    private var closed = false

    @Synchronized
    fun decodeFrame(index: Int, maxWidth: Int = 1280, maxHeight: Int = 900): Bitmap {
        require(index in frames.indices) { "Frame fuera de rango" }
        check(!closed) { "La toma RAW10 está cerrada" }

        val cellWidth = summary.width / 2
        val cellHeight = summary.height / 2
        val step = max(
            1,
            ceil(max(cellWidth.toDouble() / maxWidth, cellHeight.toDouble() / maxHeight)).toInt()
        )
        val outputWidth = max(1, cellWidth / step)
        val outputHeight = max(1, cellHeight / step)
        packedFrame.clear()
        var bytesRead = 0L
        val frameOffset = index * frameBytes
        while (packedFrame.hasRemaining()) {
            val read = rawChannel.read(packedFrame, frameOffset + bytesRead)
            check(read > 0) { "RAW10 incompleto en el fotograma $index" }
            bytesRead += read
        }
        packedFrame.flip()

        val frame = frames[index]
        val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val decodeNs = NativeTenBitConverter.raw10ToArgbPreview(
            raw = packedFrame,
            rawOffset = 0,
            rawLimit = packedFrame.limit(),
            rawRowStride = rowBytes,
            output = bitmap,
            sourceWidth = summary.width,
            sourceHeight = summary.height,
            sampleStep = step,
            colorFilterArrangement = cfaCode(cfa),
            blackLevel0 = blackLevels[0],
            blackLevel1 = blackLevels[1],
            blackLevel2 = blackLevels[2],
            blackLevel3 = blackLevels[3],
            whiteLevel = whiteLevel,
            redGain = frame.wbRed,
            greenEvenGain = frame.wbGreenEven,
            greenOddGain = frame.wbGreenOdd,
            blueGain = frame.wbBlue
        )
        if (decodeNs < 0L) {
            bitmap.recycle()
            error("No se pudo revelar RAW10 ($decodeNs)")
        }

        if (rotationDegrees % 360 == 0 && !mirrorPreview) return bitmap
        val matrix = Matrix().apply {
            postRotate((rotationDegrees % 360).toFloat())
            if (mirrorPreview) postScale(-1f, 1f)
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

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        rawChannel.close()
        rawStream.close()
    }

    companion object {
        private fun cfaCode(value: String): Int = when (value) {
            "GRBG" -> 1
            "GBRG" -> 2
            "BGGR" -> 3
            else -> 0
        }

        fun inspect(directory: File): ClipSummary? {
            val description = File(directory, "clip.json")
            val raw = File(directory, "clip.raw10")
            if (!description.isFile || !raw.isFile) return null
            return runCatching {
                val metadata = JSONObject(description.readText())
                if (metadata.optString("format") != "rawlite-raw10") return null
                val width = metadata.getInt("width")
                val height = metadata.getInt("height")
                val rowBytes = metadata.getInt("row_bytes")
                val frameBytes = rowBytes.toLong() * height
                val streamFrames = if (frameBytes > 0) (raw.length() / frameBytes).toInt() else 0
                val state = readState(File(directory, "clip.state"))
                val completed = state["completed_frames"]?.toIntOrNull()
                val sourceFrames = minOf(streamFrames, completed ?: streamFrames)
                val frameMetadata = readFrames(File(directory, "frames.csv")).take(sourceFrames)
                val timelineFrames = if (frameMetadata.isNotEmpty()) {
                    (frameMetadata.last().frame - frameMetadata.first().frame + 1)
                        .coerceAtLeast(frameMetadata.size)
                } else {
                    sourceFrames
                }
                val audio = runCatching {
                    val audioMetadata = JSONObject(File(directory, "audio.json").readText())
                    if (!File(directory, audioMetadata.optString("file", "audio.wav")).isFile) {
                        null
                    } else {
                        val bits = audioMetadata.optInt("bits_per_sample", 0)
                        val sampleRate = audioMetadata.optInt("sample_rate", 0) / 1000
                        val channels = audioMetadata.optInt("channels", 0)
                        "AUDIO ${bits}-bit ${sampleRate}k ${if (channels == 2) "ST" else "MONO"}"
                    }
                }.getOrNull()
                ClipSummary(
                    directory = directory,
                    camera = metadata.optString("camera", "Cámara RAW"),
                    width = width,
                    height = height,
                    fps = metadata.optInt("frame_rate", 0),
                    frameCount = sourceFrames,
                    timelineFrameCount = timelineFrames,
                    byteCount = directory.listFiles()?.sumOf { it.length() } ?: raw.length(),
                    finalized = state["status"] == "closed",
                    audioDescription = audio
                )
            }.getOrNull()
        }

        fun open(directory: File): Raw10Clip {
            val summary = inspect(directory) ?: error("La toma RAW10 no es válida")
            val metadata = JSONObject(File(directory, "clip.json").readText())
            val frames = readFrames(File(directory, "frames.csv")).take(summary.frameCount)
            require(frames.isNotEmpty()) { "La toma no contiene frames completos" }
            return Raw10Clip(
                summary.copy(frameCount = minOf(summary.frameCount, frames.size)),
                metadata,
                frames
            )
        }

        private fun readFrames(file: File): List<RawFrameInfo> {
            if (!file.isFile) return emptyList()
            return file.bufferedReader(Charsets.US_ASCII).useLines { lines ->
                val iterator = lines.iterator()
                if (!iterator.hasNext()) return@useLines emptyList()
                val headers = iterator.next().split(',')
                val indices = headers.withIndex().associate { it.value to it.index }
                val output = ArrayList<RawFrameInfo>()
                while (iterator.hasNext()) {
                    val values = iterator.next().split(',')
                    runCatching {
                        fun value(name: String): String = values[indices.getValue(name)]
                        output += RawFrameInfo(
                            frame = value("frame").toInt(),
                            timestampNs = value("timestamp_ns").toLong(),
                            iso = value("iso").toInt(),
                            exposureNs = value("exposure_ns").toLong(),
                            frameDurationNs = value("frame_duration_ns").toLong(),
                            focusDiopters = value("focus_diopters").toFloat(),
                            wbRed = value("wb_r").toFloat(),
                            wbGreenEven = value("wb_ge").toFloat(),
                            wbGreenOdd = value("wb_go").toFloat(),
                            wbBlue = value("wb_b").toFloat()
                        )
                    }.onFailure { return@useLines output }
                }
                output
            }
        }

        private fun readState(file: File): Map<String, String> {
            if (!file.isFile) return emptyMap()
            return file.readLines(Charsets.US_ASCII).mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }.toMap()
        }
    }
}
