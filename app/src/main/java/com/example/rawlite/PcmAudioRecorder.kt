package com.example.rawlite

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.rawlite.capture.audio.AudioInputDevice
import com.example.rawlite.capture.audio.AudioInputMonitor
import com.example.rawlite.capture.audio.AudioInputPolicy
import com.example.rawlite.capture.audio.inputDescription
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

internal data class AudioCaptureSummary(
    val file: File,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val source: String,
    val frames: Long,
    val dataBytes: Long,
    val sampleZeroBoottimeNs: Long,
    val stopRequestedBoottimeNs: Long,
    val stoppedBoottimeNs: Long,
    val finalized: Boolean,
    val error: String?
)

internal class PcmAudioRecorder private constructor(
    private val audioRecord: AudioRecord,
    private val directory: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitsPerSample: Int,
    private val sourceName: String,
    private val bufferBytes: Int,
    context: Context,
    private val routeListener: (AudioInputDevice?) -> Unit,
    private val errorListener: (String) -> Unit,
    private val levelListener: (Float) -> Unit
) {
    private val routeLock = Any()
    private var routingClosed = false
    private var preferredInput: AudioInputDevice? = null
    private var routedInput: AudioInputDevice? = null
    private var preferenceAccepted = false
    private val routeHistory = JSONArray()
    private val routingListener = AudioRouting.OnRoutingChangedListener { updateRoutedInput() }
    private val inputMonitor = AudioInputMonitor(context) { device ->
        synchronized(routeLock) {
            if (!routingClosed) {
                val externalDevice = device?.takeIf { it.inputDescription().isExternal }
                val description = externalDevice?.inputDescription()
                if (description != preferredInput || !preferenceAccepted) {
                    preferredInput = description
                    // Restore the source's default phone-mic routing on disconnect.
                    preferenceAccepted = audioRecord.setPreferredDevice(externalDevice)
                }
                updateRoutedInput()
            }
        }
    }
    private val running = AtomicBoolean(false)
    private val wavFile = File(directory, AUDIO_FILE)
    private val metadataFile = File(directory, AUDIO_METADATA_FILE)
    private var worker: Thread? = null
    @Volatile
    private var dataBytes = 0L
    @Volatile
    private var sampleZeroBoottimeNs = 0L
    @Volatile
    private var stopRequestedBoottimeNs = 0L
    @Volatile
    private var stoppedBoottimeNs = 0L
    @Volatile
    private var terminalError: String? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        return try {
            RandomAccessFile(wavFile, "rw").use { output ->
                output.setLength(0L)
                output.write(waveHeader(0L))
            }
            audioRecord.addOnRoutingChangedListener(routingListener, Handler(Looper.getMainLooper()))
            inputMonitor.start()
            writeMetadata(status = "recording", finalized = false, stoppedNs = 0L)
            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                error("AudioRecord no comenzó a grabar")
            }
            updateRoutedInput()
            worker = Thread(::captureLoop, "RawLiteAudio").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            true
        } catch (error: Throwable) {
            terminalError = error.message ?: error.javaClass.simpleName
            running.set(false)
            closeRouting()
            runCatching { audioRecord.release() }
            writeMetadata(status = "failed", finalized = false, stoppedNs = 0L)
            false
        }
    }

    fun stop(): AudioCaptureSummary {
        stopRequestedBoottimeNs = SystemClock.elapsedRealtimeNanos()
        closeRouting()
        // Keep reading until the WAV timeline reaches the stop request. This
        // covers both an in-flight block and a request arriving between reads.
        runCatching { worker?.join(GRACEFUL_DRAIN_TIMEOUT_MS) }
        running.set(false)
        runCatching {
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop()
            }
        }
        worker?.join(STOP_TIMEOUT_MS)
        if (worker?.isAlive == true) {
            terminalError = terminalError ?: "Timeout finalizando audio"
        }
        runCatching { audioRecord.release() }
        stoppedBoottimeNs = SystemClock.elapsedRealtimeNanos()
        val finalized = finalizeWaveHeader()
        writeMetadata(
            status = if (finalized) "closed" else "incomplete",
            finalized = finalized
        )
        return summary(finalized)
    }

    private fun closeRouting() {
        inputMonitor.stop()
        synchronized(routeLock) {
            routingClosed = true
            runCatching { audioRecord.removeOnRoutingChangedListener(routingListener) }
        }
    }

    private fun updateRoutedInput() {
        val listenerToNotify: ((AudioInputDevice?) -> Unit)?
        val deviceToReport: AudioInputDevice?
        synchronized(routeLock) {
            if (routingClosed || audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) return
            // A preferred device is only a request. Report the route Android actually uses.
            val actual = audioRecord.routedDevice?.inputDescription()
            if (actual != routedInput || routeHistory.length() == 0) {
                routedInput = actual
                routeHistory.put(JSONObject().apply {
                    put("observed_boottime_ns", SystemClock.elapsedRealtimeNanos())
                    put("device", actual?.toJson() ?: JSONObject.NULL)
                })
                listenerToNotify = routeListener
                deviceToReport = actual
            } else {
                listenerToNotify = null
                deviceToReport = null
            }
        }
        listenerToNotify?.invoke(deviceToReport)
    }

    private fun AudioInputDevice.toJson() = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("name", name)
        put("external", isExternal)
    }

    private fun captureLoop() {
        val buffer = ByteBuffer.allocateDirect(bufferBytes).order(ByteOrder.LITTLE_ENDIAN)
        val timestamp = AudioTimestamp()
        var lastLevelUpdateNs = 0L
        RandomAccessFile(wavFile, "rw").use { output ->
            output.seek(WAVE_HEADER_BYTES.toLong())
            val channel = output.channel
            try {
                while (running.get()) {
                    buffer.clear()
                    val read = audioRecord.read(buffer, buffer.capacity(), AudioRecord.READ_BLOCKING)
                    if (read <= 0) {
                        if (running.get()) terminalError = "AudioRecord.read=$read"
                        break
                    }
                    if (dataBytes == 0L) updateRoutedInput()
                    if (sampleZeroBoottimeNs == 0L &&
                        audioRecord.getTimestamp(
                            timestamp,
                            AudioTimestamp.TIMEBASE_BOOTTIME
                        ) == AudioRecord.SUCCESS
                    ) {
                        sampleZeroBoottimeNs = timestamp.nanoTime -
                            timestamp.framePosition * 1_000_000_000L / sampleRate
                    }
                    if (sampleZeroBoottimeNs == 0L) {
                        val capturedFrames = (dataBytes + read) / bytesPerFrame()
                        sampleZeroBoottimeNs = SystemClock.elapsedRealtimeNanos() -
                            capturedFrames * 1_000_000_000L / sampleRate
                    }
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (now - lastLevelUpdateNs >= LEVEL_INTERVAL_NS) {
                        levelListener(measurePeak(buffer, read))
                        lastLevelUpdateNs = now
                    }
                    buffer.position(0)
                    buffer.limit(read)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    dataBytes += read
                    if (stopRequestedBoottimeNs > 0L &&
                        estimatedAudioEndBoottimeNs() >= stopRequestedBoottimeNs
                    ) {
                        running.set(false)
                    }
                }
                output.fd.sync()
            } catch (error: Throwable) {
                if (running.get()) terminalError = error.message ?: error.javaClass.simpleName
            } finally {
                running.set(false)
                levelListener(0f)
                terminalError?.let(errorListener)
            }
        }
    }

    private fun measurePeak(buffer: ByteBuffer, byteCount: Int): Float {
        var peak = 0
        if (bitsPerSample == 24) {
            var offset = 0
            while (offset + 2 < byteCount) {
                var sample = (buffer.get(offset).toInt() and 0xff) or
                    ((buffer.get(offset + 1).toInt() and 0xff) shl 8) or
                    ((buffer.get(offset + 2).toInt() and 0xff) shl 16)
                if (sample and 0x800000 != 0) sample = sample or -0x1000000
                peak = max(peak, abs(sample.coerceAtLeast(-0x7fffff)))
                offset += 3
            }
            return (peak / 8_388_607f).coerceIn(0f, 1f)
        }
        var offset = 0
        while (offset + 1 < byteCount) {
            val sample = (buffer.get(offset).toInt() and 0xff) or
                (buffer.get(offset + 1).toInt() shl 8)
            peak = max(peak, abs(sample.toShort().toInt().coerceAtLeast(-32767)))
            offset += 2
        }
        return (peak / 32767f).coerceIn(0f, 1f)
    }

    private fun finalizeWaveHeader(): Boolean {
        return runCatching {
            RandomAccessFile(wavFile, "rw").use { output ->
                val recoverableBytes = (output.length() - WAVE_HEADER_BYTES)
                    .coerceAtLeast(0L)
                    .let { it - it % bytesPerFrame() }
                dataBytes = recoverableBytes
                output.seek(0L)
                output.write(waveHeader(recoverableBytes))
                output.setLength(WAVE_HEADER_BYTES + recoverableBytes)
                output.fd.sync()
            }
            true
        }.getOrElse {
            terminalError = terminalError ?: it.message ?: it.javaClass.simpleName
            false
        }
    }

    private fun waveHeader(payloadBytes: Long): ByteArray {
        val byteRate = sampleRate * bytesPerFrame()
        return ByteBuffer.allocate(WAVE_HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt((36L + payloadBytes).coerceAtMost(0xffff_ffffL).toInt())
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1)
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort(bytesPerFrame().toShort())
                putShort(bitsPerSample.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(payloadBytes.coerceAtMost(0xffff_ffffL).toInt())
            }
            .array()
    }

    private fun writeMetadata(status: String, finalized: Boolean, stoppedNs: Long = 0L) {
        if (stoppedNs > 0L) stoppedBoottimeNs = stoppedNs
        val audioEndNs = if (sampleZeroBoottimeNs > 0L && sampleRate > 0) {
            sampleZeroBoottimeNs + dataBytes / bytesPerFrame() * 1_000_000_000L / sampleRate
        } else {
            0L
        }
        val metadata = JSONObject().apply {
            put("format", "wav-pcm")
            put("status", status)
            put("file", AUDIO_FILE)
            put("sample_rate", sampleRate)
            put("channels", channels)
            put("bits_per_sample", bitsPerSample)
            put("source", sourceName)
            synchronized(routeLock) {
                put("preferred_input", preferredInput?.toJson() ?: JSONObject.NULL)
                put("preferred_input_accepted", preferenceAccepted)
                put("routed_input", routedInput?.toJson() ?: JSONObject.NULL)
                put("route_changes", JSONArray(routeHistory.toString()))
            }
            put("clock", "CLOCK_BOOTTIME")
            put("sample_zero_boottime_ns", sampleZeroBoottimeNs)
            put("audio_end_boottime_ns", audioEndNs)
            put("stop_requested_boottime_ns", stopRequestedBoottimeNs)
            put("stopped_boottime_ns", stoppedBoottimeNs)
            put("frames", dataBytes / bytesPerFrame())
            put("data_bytes", dataBytes)
            put("finalized", finalized)
            terminalError?.let { put("error", it) }
        }
        runCatching { metadataFile.writeText(metadata.toString(2)) }
    }

    private fun bytesPerFrame(): Int = channels * bitsPerSample / 8

    private fun estimatedAudioEndBoottimeNs(): Long {
        if (sampleZeroBoottimeNs <= 0L || sampleRate <= 0) return 0L
        return sampleZeroBoottimeNs + dataBytes / bytesPerFrame() * 1_000_000_000L / sampleRate
    }

    private fun summary(finalized: Boolean) = AudioCaptureSummary(
        file = wavFile,
        sampleRate = sampleRate,
        channels = channels,
        bitsPerSample = bitsPerSample,
        source = sourceName,
        frames = dataBytes / bytesPerFrame(),
        dataBytes = dataBytes,
        sampleZeroBoottimeNs = sampleZeroBoottimeNs,
        stopRequestedBoottimeNs = stopRequestedBoottimeNs,
        stoppedBoottimeNs = stoppedBoottimeNs,
        finalized = finalized,
        error = terminalError
    )

    companion object {
        const val AUDIO_FILE = "audio.wav"
        const val AUDIO_METADATA_FILE = "audio.json"
        private const val SAMPLE_RATE = 48_000
        private const val WAVE_HEADER_BYTES = 44
        private const val GRACEFUL_DRAIN_TIMEOUT_MS = 500L
        private const val STOP_TIMEOUT_MS = 2_000L
        private const val LEVEL_INTERVAL_NS = 100_000_000L

        fun create(
            context: Context,
            directory: File,
            routeListener: (AudioInputDevice?) -> Unit = {},
            errorListener: (String) -> Unit = {},
            levelListener: (Float) -> Unit
        ): PcmAudioRecorder? {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val unprocessed = manager.getProperty(
                AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED
            ) == "true"
            val external = AudioInputPolicy.select(
                manager.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.inputDescription() }
            )?.isExternal == true
            val sources = if (external) {
                listOf(MediaRecorder.AudioSource.MIC to "MIC")
            } else if (unprocessed) {
                listOf(MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED")
            } else {
                listOf(MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER")
            } + listOf(MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER")

            val formats = listOf(
                Triple(AudioFormat.ENCODING_PCM_24BIT_PACKED, 2, 24),
                Triple(AudioFormat.ENCODING_PCM_24BIT_PACKED, 1, 24),
                Triple(AudioFormat.ENCODING_PCM_16BIT, 2, 16),
                Triple(AudioFormat.ENCODING_PCM_16BIT, 1, 16)
            )
            for ((source, sourceName) in sources.distinct()) {
                for ((encoding, channels, bits) in formats) {
                    val channelMask = if (channels == 2) {
                        AudioFormat.CHANNEL_IN_STEREO
                    } else {
                        AudioFormat.CHANNEL_IN_MONO
                    }
                    val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelMask, encoding)
                    if (minimum <= 0) continue
                    val bytesPerFrame = channels * bits / 8
                    val requested = max(minimum * 4, SAMPLE_RATE * bytesPerFrame / 5)
                    val aligned = requested + (bytesPerFrame - requested % bytesPerFrame) % bytesPerFrame
                    val record = runCatching {
                        AudioRecord.Builder()
                            .setAudioSource(source)
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setSampleRate(SAMPLE_RATE)
                                    .setEncoding(encoding)
                                    .setChannelMask(channelMask)
                                    .build()
                            )
                            .setBufferSizeInBytes(aligned)
                            .build()
                    }.getOrNull() ?: continue
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        return PcmAudioRecorder(
                            record,
                            directory,
                            SAMPLE_RATE,
                            channels,
                            bits,
                            sourceName,
                            aligned,
                            context,
                            routeListener,
                            errorListener,
                            levelListener
                        )
                    }
                    record.release()
                }
            }
            return null
        }
    }
}
