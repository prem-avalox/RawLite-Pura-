package com.example.rawlite

import android.media.MediaMetadataRetriever
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

internal object Pure10Clip {
    fun inspect(directory: File): ClipSummary? {
        val descriptionFile = File(directory, "clip.json")
        if (!descriptionFile.isFile) return null
        return runCatching {
            val metadata = JSONObject(descriptionFile.readText())
            if (metadata.optString("format") != "rawlite-pure10") return null
            val finalVideoName = metadata.optString("final_video_file", "")
            val finalVideo = if (metadata.optBoolean(Pure10AudioMuxer.FINAL_VIDEO_HAS_AUDIO, false) &&
                finalVideoName.isNotBlank()
            ) {
                File(directory, finalVideoName)
            } else {
                null
            }
            val videoName = if (finalVideo?.isFile == true && finalVideo.length() > 0L) {
                finalVideo.name
            } else {
                metadata.optString("video_file", Pure10Encoder.VIDEO_FILE)
            }
            val video = File(directory, videoName)
            if (!video.isFile || video.length() <= 0L) return null
            val integratedAudio = videoName == finalVideo?.name
            val muxInProgress = File(directory, Pure10AudioMuxer.MUX_IN_PROGRESS_FILE).isFile &&
                !integratedAudio
            val fps = metadata.optInt("frame_rate", 0).coerceAtLeast(1)
            var frameCount = maxOf(
                metadata.optInt("encoded_frames", 0),
                metadata.optInt("saved_frames", 0)
            )
            var durationSeconds = 0.0
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(video.absolutePath)
                    val containerFrameCount = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT
                    )?.toIntOrNull() ?: 0
                    if (containerFrameCount > 0) frameCount = containerFrameCount
                    durationSeconds = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull()?.div(1_000.0) ?: 0.0
                } finally {
                    retriever.release()
                }
            }
            val reportedDroppedFrames = maxOf(
                metadata.optInt("dropped_frames", 0),
                metadata.optInt("encoder_drops", 0),
                metadata.optInt("capture_failure_drops", 0)
            ).coerceAtLeast(0)
            val firstTimestampNs = metadata.optLong("encoder_first_timestamp_ns", 0L)
            val lastTimestampNs = metadata.optLong("encoder_last_timestamp_ns", 0L)
            val timestampTimelineFrames = if (
                firstTimestampNs > 0L && lastTimestampNs >= firstTimestampNs
            ) {
                (((lastTimestampNs - firstTimestampNs).toDouble() * fps / 1_000_000_000.0)
                    .roundToInt() + 1).coerceAtLeast(frameCount)
            } else {
                (durationSeconds * fps).roundToInt().coerceAtLeast(frameCount)
            }
            val timelineFrames = maxOf(
                frameCount,
                timestampTimelineFrames,
                frameCount + reportedDroppedFrames
            )
            val audio = runCatching {
                val audioMetadata = JSONObject(File(directory, "audio.json").readText())
                val bits = audioMetadata.optInt("bits_per_sample", 0)
                val sampleRate = audioMetadata.optInt("sample_rate", 0) / 1000
                val channels = audioMetadata.optInt("channels", 0)
                if (bits > 0 && sampleRate > 0) {
                    "AUDIO ${bits}-bit ${sampleRate}k ${if (channels == 2) "ST" else "MONO"}"
                } else if (integratedAudio) {
                    "AUDIO AAC 48k ST"
                } else {
                    null
                }
            }.getOrNull() ?: if (integratedAudio) "AUDIO AAC 48k ST" else null
            ClipSummary(
                directory = directory,
                camera = metadata.optString("camera", "Cámara"),
                width = metadata.optInt("width", 0),
                height = metadata.optInt("height", 0),
                fps = fps,
                frameCount = frameCount,
                timelineFrameCount = timelineFrames,
                lostFrameCount = FrameIntegrity.lostFrameCount(
                    storedFrames = frameCount,
                    timelineFrames = timelineFrames,
                    reportedDroppedFrames = reportedDroppedFrames
                ),
                byteCount = directory.listFiles()?.sumOf { it.length() } ?: video.length(),
                finalized = metadata.optBoolean("finalized", false),
                audioDescription = audio,
                captureFormat = "PURE 10",
                videoFileName = videoName,
                integratedAudio = integratedAudio,
                postProcessing = muxInProgress
            )
        }.getOrNull()
    }
}
