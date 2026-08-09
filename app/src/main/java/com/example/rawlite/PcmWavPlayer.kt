package com.example.rawlite

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

internal data class WavPcmInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataBytes: Long
) {
    val bytesPerFrame: Int = channels * bitsPerSample / 8
    val frames: Long = if (bytesPerFrame > 0) dataBytes / bytesPerFrame else 0L
    val durationMs: Int = if (sampleRate > 0) {
        (frames * 1_000L / sampleRate).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    } else {
        0
    }
}

/** Sample-addressable PCM WAV playback without MediaPlayer seek discontinuities. */
internal class PcmWavPlayer private constructor(
    private val file: File,
    val info: WavPcmInfo,
    private val track: AudioTrack,
    private val bufferBytes: Int
) {
    private val generation = AtomicInteger()
    @Volatile private var released = false
    @Volatile private var playing = false
    private var baseFrame = 0L
    private var playbackHeadOrigin = 0L
    @Volatile private var nextFileOffset = info.dataOffset

    val duration: Int
        get() = info.durationMs

    val currentPosition: Int
        @Synchronized get() {
            val head = track.playbackHeadPosition.toLong() and UINT32_MASK
            val played = (head - playbackHeadOrigin) and UINT32_MASK
            val frame = (baseFrame + played).coerceIn(0L, info.frames)
            return (frame * 1_000L / info.sampleRate)
                .coerceAtMost(info.durationMs.toLong())
                .toInt()
        }

    val isPlaying: Boolean
        get() = playing && track.playState == AudioTrack.PLAYSTATE_PLAYING

    init {
        prepareAt(0)
    }

    @Synchronized
    fun seekTo(positionMs: Int) {
        check(!released) { "Audio player released" }
        prepareAt(positionMs.coerceIn(0, duration))
    }

    @Synchronized
    fun play() {
        check(!released) { "Audio player released" }
        if (playing || baseFrame >= info.frames) return
        val activeGeneration = generation.incrementAndGet()
        playing = true
        track.play()
        val startOffset = nextFileOffset
        Thread({ stream(activeGeneration, startOffset) }, "RawLite-WAV").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    @Synchronized
    fun pause() {
        if (released) return
        playing = false
        runCatching { track.pause() }
        generation.incrementAndGet()
    }

    @Synchronized
    fun setVolume(volume: Float) {
        if (!released) track.setVolume(volume.coerceIn(0f, 1f))
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        playing = false
        generation.incrementAndGet()
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.release() }
    }

    @Synchronized
    private fun prepareAt(positionMs: Int) {
        generation.incrementAndGet()
        playing = false
        runCatching { track.pause() }
        runCatching { track.flush() }
        baseFrame = (positionMs.toLong() * info.sampleRate / 1_000L)
            .coerceIn(0L, info.frames)
        playbackHeadOrigin = track.playbackHeadPosition.toLong() and UINT32_MASK
        val offset = info.dataOffset + baseFrame * info.bytesPerFrame
        val remaining = (info.dataOffset + info.dataBytes - offset).coerceAtLeast(0L)
        if (remaining <= 0L) {
            nextFileOffset = offset
            return
        }
        val preload = ByteArray(minOf(bufferBytes.toLong(), remaining).toInt())
        val count = RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            input.read(preload)
        }.coerceAtLeast(0)
        val written = writeAlignedPcm(preload, 0, count)
        nextFileOffset = offset + written
    }

    private fun stream(activeGeneration: Int, startOffset: Long) {
        val end = info.dataOffset + info.dataBytes
        val buffer = ByteArray(bufferBytes)
        var writtenFileOffset = startOffset
        RandomAccessFile(file, "r").use { input ->
            input.seek(startOffset.coerceAtMost(end))
            while (!released && playing && generation.get() == activeGeneration &&
                input.filePointer < end
            ) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), end - input.filePointer).toInt())
                if (count <= 0) break
                var offset = 0
                while (!released && playing && generation.get() == activeGeneration && offset < count) {
                    val written = writeAlignedPcm(buffer, offset, count - offset)
                    if (written > 0) {
                        offset += written
                        writtenFileOffset += written
                        if (generation.get() == activeGeneration) {
                            nextFileOffset = writtenFileOffset
                        }
                    } else {
                        break
                    }
                }
            }
        }
    }

    private fun writeAlignedPcm(buffer: ByteArray, offset: Int, byteCount: Int): Int {
        val frameBytes = info.bytesPerFrame.coerceAtLeast(1)
        val alignedCount = byteCount - (byteCount % frameBytes)
        var written = 0
        while (!released && written < alignedCount) {
            val request = alignedCount - written
            val result = track.write(
                buffer,
                offset + written,
                request,
                AudioTrack.WRITE_BLOCKING
            )
            if (result <= 0) break
            if (result % frameBytes != 0) break
            written += result
        }
        return written
    }

    companion object {
        private const val UINT32_MASK = 0xffff_ffffL

        fun create(file: File): PcmWavPlayer? = runCatching {
            val info = inspect(file) ?: return null
            val channelMask = if (info.channels == 2) {
                AudioFormat.CHANNEL_OUT_STEREO
            } else {
                AudioFormat.CHANNEL_OUT_MONO
            }
            val encoding = when (info.bitsPerSample) {
                16 -> AudioFormat.ENCODING_PCM_16BIT
                24 -> {
                    require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        "PCM de 24 bits requiere Android 12"
                    }
                    AudioFormat.ENCODING_PCM_24BIT_PACKED
                }
                else -> error("PCM de ${info.bitsPerSample} bits no compatible")
            }
            val format = AudioFormat.Builder()
                .setSampleRate(info.sampleRate)
                .setChannelMask(channelMask)
                .setEncoding(encoding)
                .build()
            val minimum = AudioTrack.getMinBufferSize(info.sampleRate, channelMask, encoding)
            require(minimum > 0) { "AudioTrack buffer inválido: $minimum" }
            val requestedBufferBytes = max(minimum * 2, info.sampleRate * info.bytesPerFrame / 4)
            val bufferBytes = alignDown(requestedBufferBytes, info.bytesPerFrame)
                .coerceAtLeast(info.bytesPerFrame)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            require(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack no inicializado" }
            PcmWavPlayer(file, info, track, bufferBytes)
        }.getOrNull()

        fun inspect(file: File): WavPcmInfo? = runCatching {
            if (!file.isFile || file.length() < 44L) return null
            RandomAccessFile(file, "r").use { input ->
                if (input.readFourCc() != "RIFF") return null
                input.readLittleEndianInt()
                if (input.readFourCc() != "WAVE") return null
                var sampleRate = 0
                var channels = 0
                var bits = 0
                var dataOffset = 0L
                var dataBytes = 0L
                while (input.filePointer + 8L <= input.length()) {
                    val chunk = input.readFourCc()
                    val size = input.readLittleEndianInt().toLong() and UINT32_MASK
                    val content = input.filePointer
                    when (chunk) {
                        "fmt " -> {
                            if (size < 16L) return null
                            val format = input.readLittleEndianShort()
                            if (format != 1) return null
                            channels = input.readLittleEndianShort()
                            sampleRate = input.readLittleEndianInt()
                            input.skipBytes(6)
                            bits = input.readLittleEndianShort()
                        }
                        "data" -> {
                            dataOffset = content
                            dataBytes = minOf(size, input.length() - content)
                        }
                    }
                    input.seek((content + size + (size and 1L)).coerceAtMost(input.length()))
                    if (sampleRate > 0 && dataOffset > 0L) break
                }
                if (sampleRate <= 0 || channels !in 1..2 || bits !in setOf(16, 24) ||
                    dataOffset <= 0L || dataBytes <= 0L
                ) return null
                val bytesPerFrame = channels * bits / 8
                val alignedDataBytes = alignDown(dataBytes, bytesPerFrame)
                if (bytesPerFrame <= 0 || alignedDataBytes <= 0L) return null
                WavPcmInfo(sampleRate, channels, bits, dataOffset, alignedDataBytes)
            }
        }.getOrNull()

        private fun alignDown(value: Int, quantum: Int): Int {
            if (quantum <= 0) return value
            return value - value % quantum
        }

        private fun alignDown(value: Long, quantum: Int): Long {
            if (quantum <= 0) return value
            return value - value % quantum
        }

        private fun RandomAccessFile.readFourCc(): String {
            val bytes = ByteArray(4)
            readFully(bytes)
            return bytes.toString(Charsets.US_ASCII)
        }

        private fun RandomAccessFile.readLittleEndianShort(): Int {
            val low = readUnsignedByte()
            val high = readUnsignedByte()
            return low or (high shl 8)
        }

        private fun RandomAccessFile.readLittleEndianInt(): Int {
            return readUnsignedByte() or
                (readUnsignedByte() shl 8) or
                (readUnsignedByte() shl 16) or
                (readUnsignedByte() shl 24)
        }
    }
}
