package com.example.rawlite

import android.graphics.Bitmap
import android.graphics.Matrix
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow

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
) {
    private val rawFile = File(summary.directory, "clip.raw10")
    private val rowBytes = metadata.getInt("row_bytes")
    private val frameBytes = rowBytes.toLong() * summary.height
    private val cfa = metadata.optString("cfa", "RGGB").uppercase(Locale.US)
    private val whiteLevel = metadata.optInt("white_level", 1023).coerceAtLeast(1)
    private val blackLevels = IntArray(4) { index ->
        metadata.optJSONArray("black_level")?.optInt(index, 0) ?: 0
    }
    private val rotationDegrees = metadata.optInt(
        "rotation_degrees",
        metadata.optInt("sensor_orientation", 0)
    )
    private val mirrorPreview = metadata.optBoolean("front_facing", false)

    fun decodeFrame(index: Int, maxWidth: Int = 1280, maxHeight: Int = 900): Bitmap {
        require(index in frames.indices) { "Frame fuera de rango" }
        val packed = ByteArray(frameBytes.toInt())
        RandomAccessFile(rawFile, "r").use { stream ->
            stream.seek(index * frameBytes)
            stream.readFully(packed)
        }

        val cellWidth = summary.width / 2
        val cellHeight = summary.height / 2
        val step = max(
            1,
            ceil(max(cellWidth.toDouble() / maxWidth, cellHeight.toDouble() / maxHeight)).toInt()
        )
        val outputWidth = max(1, cellWidth / step)
        val outputHeight = max(1, cellHeight / step)
        val linear = FloatArray(outputWidth * outputHeight * 3)
        val frame = frames[index]
        var outputIndex = 0

        for (outputY in 0 until outputHeight) {
            val sourceY = (outputY * step * 2).coerceAtMost(summary.height - 2)
            for (outputX in 0 until outputWidth) {
                val sourceX = (outputX * step * 2).coerceAtMost(summary.width - 2)
                var red = 0f
                var greenEven = 0f
                var greenOdd = 0f
                var blue = 0f
                var greenCount = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val site = dy * 2 + dx
                        val normalized = normalizedSample(packed, sourceX + dx, sourceY + dy, site)
                        when (cfa.getOrNull(site)) {
                            'R' -> red = normalized * frame.wbRed
                            'B' -> blue = normalized * frame.wbBlue
                            'G' -> {
                                if (greenCount++ == 0) {
                                    greenEven = normalized * frame.wbGreenEven
                                } else {
                                    greenOdd = normalized * frame.wbGreenOdd
                                }
                            }
                        }
                    }
                }
                val green = if (greenCount > 1) {
                    (greenEven + greenOdd) * 0.5f
                } else {
                    greenEven + greenOdd
                }
                linear[outputIndex++] = red
                linear[outputIndex++] = green
                linear[outputIndex++] = blue
            }
        }

        val highlights = FloatArray((outputWidth * outputHeight + 15) / 16)
        var highlightIndex = 0
        var pixel = 0
        while (pixel < outputWidth * outputHeight) {
            val base = pixel * 3
            highlights[highlightIndex++] =
                linear[base] * 0.2126f + linear[base + 1] * 0.7152f + linear[base + 2] * 0.0722f
            pixel += 16
        }
        highlights.sort()
        val percentile = highlights[
            ((highlights.size - 1) * 0.985f).toInt().coerceIn(highlights.indices)
        ].coerceAtLeast(0.001f)
        val displayGain = (0.90f / percentile).coerceIn(0.25f, 8f)

        val colors = IntArray(outputWidth * outputHeight)
        for (colorIndex in colors.indices) {
            val base = colorIndex * 3
            var red = linear[base] * displayGain
            var green = linear[base + 1] * displayGain
            var blue = linear[base + 2] * displayGain
            val luminance = red * 0.2126f + green * 0.7152f + blue * 0.0722f
            red = luminance + (red - luminance) * 1.12f
            green = luminance + (green - luminance) * 1.12f
            blue = luminance + (blue - luminance) * 1.12f
            colors[colorIndex] =
                (0xFF shl 24) or
                    (toSrgbByte(red) shl 16) or
                    (toSrgbByte(green) shl 8) or
                    toSrgbByte(blue)
        }

        val bitmap = Bitmap.createBitmap(colors, outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
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

    private fun normalizedSample(packed: ByteArray, x: Int, y: Int, site: Int): Float {
        val groupOffset = y * rowBytes + (x / 4) * 5
        val pixelInGroup = x and 3
        val high = packed[groupOffset + pixelInGroup].toInt() and 0xFF
        val low = packed[groupOffset + 4].toInt() and 0xFF
        val value = (high shl 2) or ((low shr (pixelInGroup * 2)) and 0x03)
        val black = blackLevels[site].coerceAtMost(whiteLevel - 1)
        return ((value - black).coerceAtLeast(0).toFloat() / (whiteLevel - black))
    }

    private fun toSrgbByte(value: Float): Int {
        val linear = value.coerceIn(0f, 1f)
        val srgb = if (linear <= 0.0031308f) {
            linear * 12.92f
        } else {
            1.055f * linear.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f
        }
        return (srgb * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    companion object {
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
