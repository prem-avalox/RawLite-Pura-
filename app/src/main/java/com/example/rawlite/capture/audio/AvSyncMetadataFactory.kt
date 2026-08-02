package com.example.rawlite.capture.audio

import com.example.rawlite.AudioCaptureSummary
import org.json.JSONObject

internal data class AvSyncContext(
    val cameraUsesBoottime: Boolean,
    val cameraFirstTimestampNs: Long,
    val cameraLastTimestampNs: Long,
    val lastTimelineFrame: Int,
    val savedFrames: Int,
    val cadenceGaps: Int
)

/** Defines the audio/video clock contract written to sync.json. */
internal object AvSyncMetadataFactory {
    fun create(context: AvSyncContext, audio: AudioCaptureSummary): JSONObject {
        val audioEnd = if (audio.sampleZeroBoottimeNs > 0L && audio.sampleRate > 0) {
            audio.sampleZeroBoottimeNs + audio.frames * NANOS_PER_SECOND / audio.sampleRate
        } else {
            0L
        }
        val offset = if (context.cameraUsesBoottime &&
            context.cameraFirstTimestampNs > 0L &&
            audio.sampleZeroBoottimeNs > 0L
        ) {
            context.cameraFirstTimestampNs - audio.sampleZeroBoottimeNs
        } else {
            JSONObject.NULL
        }
        return JSONObject().apply {
            put("version", 1)
            put("video_clock", if (context.cameraUsesBoottime) {
                "CLOCK_BOOTTIME"
            } else {
                "CAMERA_UNKNOWN"
            })
            put("audio_clock", "CLOCK_BOOTTIME")
            put("camera_first_timestamp_ns", context.cameraFirstTimestampNs)
            put("camera_last_timestamp_ns", context.cameraLastTimestampNs)
            put("audio_sample_zero_timestamp_ns", audio.sampleZeroBoottimeNs)
            put("audio_end_timestamp_ns", audioEnd)
            put("audio_stop_requested_timestamp_ns", audio.stopRequestedBoottimeNs)
            put("audio_stopped_timestamp_ns", audio.stoppedBoottimeNs)
            put("camera_start_minus_audio_start_ns", offset)
            put(
                "audio_end_minus_camera_last_ns",
                if (audioEnd > 0L && context.cameraLastTimestampNs > 0L) {
                    audioEnd - context.cameraLastTimestampNs
                } else {
                    JSONObject.NULL
                }
            )
            put(
                "timeline_frames",
                (context.lastTimelineFrame + 1).coerceAtLeast(context.savedFrames)
            )
            put("audio_file", audio.file.name)
            put("audio_sample_rate", audio.sampleRate)
            put("audio_channels", audio.channels)
            put("audio_bits_per_sample", audio.bitsPerSample)
            put("audio_source", audio.source)
            put("audio_frames", audio.frames)
            put("audio_data_bytes", audio.dataBytes)
            put("cadence_gaps", context.cadenceGaps)
            put("finalized", audio.finalized)
            audio.error?.let { put("audio_error", it) }
        }
    }

    private const val NANOS_PER_SECOND = 1_000_000_000L
}
