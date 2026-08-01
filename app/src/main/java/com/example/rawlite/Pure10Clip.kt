package com.example.rawlite

import android.media.MediaMetadataRetriever
import org.json.JSONObject
import java.io.File

internal object Pure10Clip {
    fun inspect(directory: File): ClipSummary? {
        val descriptionFile = File(directory, "clip.json")
        if (!descriptionFile.isFile) return null
        return runCatching {
            val metadata = JSONObject(descriptionFile.readText())
            if (metadata.optString("format") != "rawlite-pure10") return null
            val videoName = metadata.optString("video_file", Pure10Encoder.VIDEO_FILE)
            val video = File(directory, videoName)
            if (!video.isFile || video.length() <= 0L) return null
            val fps = metadata.optInt("frame_rate", 0).coerceAtLeast(1)
            var frameCount = metadata.optInt("encoded_frames", 0)
            var durationSeconds = 0.0
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(video.absolutePath)
                    if (frameCount <= 0) {
                        frameCount = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT
                        )?.toIntOrNull() ?: 0
                    }
                    durationSeconds = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull()?.div(1_000.0) ?: 0.0
                } finally {
                    retriever.release()
                }
            }
            val timelineFrames = maxOf(frameCount, (durationSeconds * fps).toInt())
            val audio = runCatching {
                val audioMetadata = JSONObject(File(directory, "audio.json").readText())
                val audioFile = File(directory, audioMetadata.optString("file", "audio.wav"))
                if (!audioFile.isFile) {
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
                camera = metadata.optString("camera", "Cámara"),
                width = metadata.optInt("width", 0),
                height = metadata.optInt("height", 0),
                fps = fps,
                frameCount = frameCount,
                timelineFrameCount = timelineFrames,
                byteCount = directory.listFiles()?.sumOf { it.length() } ?: video.length(),
                finalized = metadata.optBoolean("finalized", false),
                audioDescription = audio,
                captureFormat = "PURE 10",
                videoFileName = videoName
            )
        }.getOrNull()
    }
}
