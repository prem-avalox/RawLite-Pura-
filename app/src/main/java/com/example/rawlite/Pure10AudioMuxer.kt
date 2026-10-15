package com.example.rawlite

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

internal data class Pure10AudioMuxResult(
    val outputFile: File,
    val videoBytes: Long,
    val audioFrames: Long,
    val audioOffsetNs: Long,
    val sourceVideoDeleted: Boolean,
    val finalized: Boolean,
    val error: String?
)

/** Creates the user-facing PURE 10 MP4 by muxing HEVC Main10 video with AAC audio. */
internal object Pure10AudioMuxer {
    const val FINAL_VIDEO_FILE = "pure10_final.mp4"
    const val FINAL_VIDEO_HAS_AUDIO = "final_video_has_audio"
    const val MUX_IN_PROGRESS_FILE = ".pure10_muxing"

    fun mux(directory: File): Pure10AudioMuxResult {
        val descriptionFile = File(directory, "clip.json")
        val clipFormat = runCatching {
            JSONObject(descriptionFile.readText()).optString("format")
        }.getOrDefault("")
        val isEightBit = clipFormat == "rawlite-8bit"
        val sourceVideoName = if (isEightBit) {
            if (File(directory, EightBitRec709Encoder.VIDEO_FILE).isFile) {
                EightBitRec709Encoder.VIDEO_FILE
            } else {
                Pure10Encoder.VIDEO_FILE
            }
        } else {
            Pure10Encoder.VIDEO_FILE
        }
        val sourceVideo = File(directory, sourceVideoName)
        val wavFile = File(directory, PcmAudioRecorder.AUDIO_FILE)
        val finalVideoName = if (isEightBit) "eight_bit_final.mp4" else FINAL_VIDEO_FILE
        val output = File(directory, finalVideoName)
        val temporary = File(directory, "$finalVideoName.tmp")
        return try {
            require(sourceVideo.isFile && sourceVideo.length() > 0L) { "Video origen faltante" }
            require(wavFile.isFile && wavFile.length() > 0L) { "WAV faltante para integrar" }
            val wav = requireNotNull(PcmWavPlayer.inspect(wavFile)) { "WAV PCM inválido" }
            val sync = readSync(directory)
            val rotation = readRotation(directory)
            if (temporary.exists()) temporary.delete()
            muxInternal(
                sourceVideo = sourceVideo,
                wavFile = wavFile,
                wav = wav,
                audioOffsetNs = sync,
                rotationDegrees = rotation,
                outputFile = temporary,
                isEightBit = isEightBit
            )
            require(temporary.isFile && temporary.length() > 0L) { "MP4 final vacío" }
            if (output.exists() && !output.delete()) {
                throw IllegalStateException("No se pudo reemplazar MP4 final previo")
            }
            require(temporary.renameTo(output)) { "No se pudo cerrar MP4 final" }
            val sourceDeleted = if (sourceVideo.canonicalPath == output.canonicalPath) {
                false
            } else {
                sourceVideo.delete().also { deleted ->
                    if (!deleted && sourceVideo.exists()) {
                        Log.w(TAG, "Could not delete intermediate video ${sourceVideo.name}")
                    }
                }
            }
            if (wavFile.exists() && output.isFile && output.length() > 0L) {
                wavFile.delete().also { deleted ->
                    if (!deleted && wavFile.exists()) {
                        Log.w(TAG, "Could not delete intermediate audio ${wavFile.name}")
                    }
                }
            }
            val result = Pure10AudioMuxResult(
                outputFile = output,
                videoBytes = output.length(),
                audioFrames = wav.frames,
                audioOffsetNs = sync,
                sourceVideoDeleted = sourceDeleted,
                finalized = true,
                error = null
            )
            updateClipMetadata(directory, result, sourceVideoName)
            result
        } catch (error: Throwable) {
            runCatching { temporary.delete() }
            val result = Pure10AudioMuxResult(
                outputFile = output,
                videoBytes = output.takeIf { it.isFile }?.length() ?: 0L,
                audioFrames = 0L,
                audioOffsetNs = readSync(directory),
                sourceVideoDeleted = false,
                finalized = false,
                error = error.message ?: error.javaClass.simpleName
            )
            updateClipMetadata(directory, result, sourceVideoName)
            Log.e(TAG, "Could not mux video audio", error)
            result
        }
    }

    private fun muxInternal(
        sourceVideo: File,
        wavFile: File,
        wav: WavPcmInfo,
        audioOffsetNs: Long,
        rotationDegrees: Int,
        outputFile: File,
        isEightBit: Boolean
    ) {
        val videoInfo = inspectVideoTrack(sourceVideo, isEightBit)
        val muxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        var muxerStarted = false
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            muxer.setOrientationHint(rotationDegrees)
            val videoTrack = muxer.addTrack(videoInfo.format)
            val inputFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                wav.sampleRate,
                wav.channels
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, if (wav.channels == 2) 192_000 else 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_INPUT_FRAMES * wav.channels * 2)
            }
            codec.configure(inputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val audioTrack = encodeAudioUntilFormat(
                codec = codec,
                wavFile = wavFile,
                wav = wav,
                audioOffsetNs = audioOffsetNs,
                muxer = muxer,
                onFormat = { outputFormat ->
                    val track = muxer.addTrack(outputFormat)
                    muxer.start()
                    muxerStarted = true
                    copyVideoTrack(sourceVideo, videoInfo.trackIndex, muxer, videoTrack)
                    track
                }
            )
            require(audioTrack >= 0) { "El encoder AAC no entregó formato de salida" }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun encodeAudioUntilFormat(
        codec: MediaCodec,
        wavFile: File,
        wav: WavPcmInfo,
        audioOffsetNs: Long,
        muxer: MediaMuxer,
        onFormat: (MediaFormat) -> Int
    ): Int {
        val bufferInfo = MediaCodec.BufferInfo()
        var audioTrack = -1
        var submittedFrames = 0L
        var sawInputEos = false
        val trimStartFrames = if (audioOffsetNs > 0L) {
            (audioOffsetNs * wav.sampleRate / NANOS_PER_SECOND).coerceAtMost(wav.frames)
        } else {
            0L
        }
        val initialPresentationUs = if (audioOffsetNs < 0L) {
            (-audioOffsetNs / 1_000L).coerceAtLeast(0L)
        } else {
            0L
        }

        RandomAccessFile(wavFile, "r").use { input ->
            input.seek(wav.dataOffset + trimStartFrames * wav.bytesPerFrame)
            while (true) {
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = requireNotNull(codec.getInputBuffer(inputIndex))
                        inputBuffer.clear()
                        val framesRead = readPcm16(input, wav, inputBuffer, AUDIO_INPUT_FRAMES)
                        if (framesRead <= 0) {
                            val ptsUs = initialPresentationUs +
                                submittedFrames * MICROS_PER_SECOND / wav.sampleRate
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                ptsUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEos = true
                        } else {
                            val byteCount = framesRead * wav.channels * 2
                            val ptsUs = initialPresentationUs +
                                submittedFrames * MICROS_PER_SECOND / wav.sampleRate
                            codec.queueInputBuffer(inputIndex, 0, byteCount, ptsUs, 0)
                            submittedFrames += framesRead
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        require(audioTrack < 0) { "Formato AAC cambió más de una vez" }
                        audioTrack = onFormat(codec.outputFormat)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (sawInputEos) continue
                    }
                    else -> if (outputIndex >= 0) {
                        val encoded = requireNotNull(codec.getOutputBuffer(outputIndex))
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && audioTrack >= 0) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(audioTrack, encoded, bufferInfo)
                        }
                        val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (eos) return audioTrack
                    }
                }
            }
        }
    }

    private fun readPcm16(
        input: RandomAccessFile,
        wav: WavPcmInfo,
        output: ByteBuffer,
        maxFrames: Int
    ): Int {
        val sourceBytesPerFrame = wav.bytesPerFrame
        val requested = maxFrames * sourceBytesPerFrame
        val source = ByteArray(requested)
        val count = input.read(source)
        if (count <= 0) return 0
        val frames = count / sourceBytesPerFrame
        var sourceOffset = 0
        repeat(frames) {
            repeat(wav.channels) {
                val sample16 = if (wav.bitsPerSample == 24) {
                    var sample = (source[sourceOffset].toInt() and 0xff) or
                        ((source[sourceOffset + 1].toInt() and 0xff) shl 8) or
                        ((source[sourceOffset + 2].toInt() and 0xff) shl 16)
                    if (sample and 0x800000 != 0) sample = sample or -0x1000000
                    sourceOffset += 3
                    (sample shr 8).toShort()
                } else {
                    val sample = (source[sourceOffset].toInt() and 0xff) or
                        (source[sourceOffset + 1].toInt() shl 8)
                    sourceOffset += 2
                    sample.toShort()
                }
                output.put((sample16.toInt() and 0xff).toByte())
                output.put(((sample16.toInt() ushr 8) and 0xff).toByte())
            }
        }
        return frames
    }

    private fun copyVideoTrack(
        sourceVideo: File,
        sourceTrackIndex: Int,
        muxer: MediaMuxer,
        muxerTrackIndex: Int
    ) {
        val extractor = MediaExtractor()
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            extractor.setDataSource(sourceVideo.absolutePath)
            extractor.selectTrack(sourceTrackIndex)
            val maxInput = extractor.getTrackFormat(sourceTrackIndex)
                .takeIf { it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) }
                ?.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                ?: DEFAULT_VIDEO_SAMPLE_BYTES
            val buffer = ByteBuffer.allocateDirect(max(DEFAULT_VIDEO_SAMPLE_BYTES, maxInput))
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                bufferInfo.set(
                    0,
                    size,
                    extractor.sampleTime.coerceAtLeast(0L),
                    mediaCodecFlags(extractor.sampleFlags)
                )
                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                extractor.advance()
            }
        } finally {
            extractor.release()
        }
    }

    private data class VideoTrackInfo(val trackIndex: Int, val format: MediaFormat)

    private fun mediaCodecFlags(extractorFlags: Int): Int {
        var flags = 0
        if (extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if (extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return flags
    }

    private fun inspectVideoTrack(sourceVideo: File, isEightBit: Boolean): VideoTrackInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(sourceVideo.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) return VideoTrackInfo(
                    index,
                    if (isEightBit) format.applyEightBitColorMetadata() else format.applyPure10ColorMetadata()
                )
            }
            error("MP4 sin pista de video")
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.applyPure10ColorMetadata(): MediaFormat = apply {
        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
    }

    private fun MediaFormat.applyEightBitColorMetadata(): MediaFormat = apply {
        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
    }

    private fun readSync(directory: File): Long {
        return runCatching {
            val sync = JSONObject(File(directory, "sync.json").readText())
            if (sync.isNull("camera_start_minus_audio_start_ns")) {
                0L
            } else {
                sync.optLong("camera_start_minus_audio_start_ns", 0L)
            }
        }.getOrDefault(0L)
    }

    private fun readRotation(directory: File): Int {
        return runCatching {
            JSONObject(File(directory, "clip.json").readText()).optInt("rotation_degrees", 0)
        }.getOrDefault(0)
    }

    private fun updateClipMetadata(
        directory: File,
        result: Pure10AudioMuxResult,
        sourceVideoName: String
    ) {
        val descriptionFile = File(directory, "clip.json")
        val description = runCatching { JSONObject(descriptionFile.readText()) }
            .getOrElse { JSONObject() }
        description.put("final_video_file", result.outputFile.name)
        description.put(FINAL_VIDEO_HAS_AUDIO, result.finalized)
        description.put("final_video_bytes", result.videoBytes)
        description.put("audio_mux_finalized", result.finalized)
        description.put("audio_mux_offset_ns", result.audioOffsetNs)
        description.put("audio_codec", "AAC-LC")
        if (result.finalized) {
            description.put("video_file", result.outputFile.name)
            description.put("intermediate_video_file", sourceVideoName)
            description.put("intermediate_video_deleted", result.sourceVideoDeleted)
            description.remove("audio_mux_error")
        } else {
            description.put("video_file", sourceVideoName)
            description.put("intermediate_video_deleted", false)
            result.error?.let { description.put("audio_mux_error", it) }
        }
        runCatching { descriptionFile.writeText(description.toString(2)) }
    }

    private const val TAG = "RawLitePure10Mux"
    private const val AUDIO_INPUT_FRAMES = 2048
    private const val DEFAULT_VIDEO_SAMPLE_BYTES = 4 * 1024 * 1024
    private const val TIMEOUT_US = 10_000L
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val MICROS_PER_SECOND = 1_000_000L
}
