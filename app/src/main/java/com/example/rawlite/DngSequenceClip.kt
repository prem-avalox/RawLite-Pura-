package com.example.rawlite

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

internal class DngSequenceClip private constructor(
    val summary: ClipSummary,
    val files: List<File>,
    val timelineFrames: List<Int>,
    private val rotationDegrees: Int,
    private val mirrorPreview: Boolean
) {
    fun decodeFrame(index: Int, maxWidth: Int = 1280, maxHeight: Int = 900): Bitmap {
        val file = files[index.coerceIn(files.indices)]
        val sample = max(
            1,
            ceil(
                max(
                    summary.width.toDouble() / maxWidth,
                    summary.height.toDouble() / maxHeight
                )
            ).toInt()
        )
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                decoder.setTargetSampleSize(sample)
            }
        } else {
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: error("Android no pudo revelar ${file.name}")
        }
        if (rotationDegrees % 360 == 0 && !mirrorPreview) return bitmap
        val transform = Matrix().apply {
            postRotate((rotationDegrees % 360).toFloat())
            if (mirrorPreview) postScale(-1f, 1f)
        }
        val oriented = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            transform,
            true
        )
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }

    companion object {
        fun inspect(directory: File): ClipSummary? = openInternal(directory, summaryOnly = true)?.summary

        fun open(directory: File): DngSequenceClip {
            return openInternal(directory, summaryOnly = false)
                ?: error("La secuencia CinemaDNG no es válida")
        }

        private fun openInternal(directory: File, summaryOnly: Boolean): DngSequenceClip? {
            val files = directory.listFiles { file ->
                file.isFile && file.name.startsWith("frame_") && file.extension.equals("dng", true)
            }?.sortedBy(::timelineFrame).orEmpty()
            if (files.isEmpty()) return null
            return runCatching {
                val metadataFile = File(directory, "clip.json")
                val metadata = if (metadataFile.isFile) {
                    JSONObject(metadataFile.readText())
                } else {
                    JSONObject()
                }
                val timeline = files.map(::timelineFrame)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(files.first().absolutePath, bounds)
                val width = metadata.optInt("width", bounds.outWidth.takeIf { it > 0 } ?: 4080)
                val height = metadata.optInt("height", bounds.outHeight.takeIf { it > 0 } ?: 3072)
                val audioMetadata = readAudioMetadata(directory)
                val audioDuration = audioMetadata?.let {
                    val frames = it.optLong("frames", 0L)
                    val sampleRate = it.optInt("sample_rate", 0)
                    if (frames > 0L && sampleRate > 0) frames.toDouble() / sampleRate else 0.0
                } ?: 0.0
                val timelineCount = metadata.optInt(
                    "timeline_frames",
                    timeline.last() - timeline.first() + 1
                ).coerceAtLeast(files.size)
                val inferredFps = if (audioDuration > 0.2) {
                    val measured = timelineCount / audioDuration
                    listOf(15, 24, 25, 30).minByOrNull { kotlin.math.abs(it - measured) } ?: 15
                } else {
                    15
                }
                val summary = ClipSummary(
                    directory = directory,
                    camera = metadata.optString("camera", "Cámara"),
                    width = width,
                    height = height,
                    fps = metadata.optInt("frame_rate", inferredFps).coerceAtLeast(1),
                    frameCount = files.size,
                    timelineFrameCount = timelineCount,
                    byteCount = directory.listFiles()?.sumOf { it.length() } ?: 0L,
                    finalized = metadata.optBoolean("finalized", true),
                    audioDescription = audioDescription(directory, audioMetadata),
                    captureFormat = "CinemaDNG"
                )
                DngSequenceClip(
                    summary = summary,
                    files = if (summaryOnly) emptyList() else files,
                    timelineFrames = if (summaryOnly) emptyList() else timeline,
                    rotationDegrees = metadata.optInt(
                        "rotation_degrees",
                        metadata.optInt("sensor_orientation", 0)
                    ),
                    mirrorPreview = metadata.optBoolean("front_facing", false)
                )
            }.getOrNull()
        }

        private fun timelineFrame(file: File): Int {
            return file.nameWithoutExtension.substringAfter("frame_").toIntOrNull() ?: 0
        }

        private fun readAudioMetadata(directory: File): JSONObject? {
            return runCatching { JSONObject(File(directory, "audio.json").readText()) }.getOrNull()
        }

        private fun audioDescription(directory: File, metadata: JSONObject?): String? {
            metadata ?: return null
            val audioFile = File(directory, metadata.optString("file", "audio.wav"))
            if (!audioFile.isFile) return null
            val bits = metadata.optInt("bits_per_sample", 0)
            val sampleRate = metadata.optInt("sample_rate", 0) / 1000
            val channels = metadata.optInt("channels", 0)
            return "AUDIO ${bits}-bit ${sampleRate}k ${if (channels == 2) "ST" else "MONO"}"
        }
    }
}
