package com.example.rawlite.gallery

import com.example.rawlite.CaptureStorage
import com.example.rawlite.DngSequenceClip
import com.example.rawlite.PcmAudioRecorder
import com.example.rawlite.RawFrameInfo
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.roundToLong

internal data class Raw10ConversionProgress(
    val clipName: String,
    val doneFrames: Int,
    val totalFrames: Int
)

internal data class Raw10ConversionResult(
    val source: File,
    val output: File?,
    val convertedFrames: Int,
    val repeatedFrames: Int,
    val audioAvailable: Boolean,
    val skipped: Boolean,
    val error: String?
)

internal object Raw10CinemaDngConverter {
    fun convertBatch(
        clips: List<File>,
        progress: (Raw10ConversionProgress) -> Unit
    ): List<Raw10ConversionResult> = clips.map { source ->
        runCatching { convert(source, progress) }.getOrElse { error ->
            Raw10ConversionResult(
                source = source,
                output = null,
                convertedFrames = 0,
                repeatedFrames = 0,
                audioAvailable = false,
                skipped = false,
                error = error.message ?: error.javaClass.simpleName
            )
        }
    }

    fun convert(
        source: File,
        progress: (Raw10ConversionProgress) -> Unit
    ): Raw10ConversionResult {
        val metadata = JSONObject(File(source, "clip.json").readText())
        require(metadata.optString("format") == "rawlite-raw10") {
            "La toma no es RAW10"
        }
        addLegacyFactoryProfile(metadata)
        val width = metadata.getInt("width")
        val height = metadata.getInt("height")
        val rowBytes = metadata.getInt("row_bytes")
        val frameBytes = rowBytes * height
        require(width > 0 && height > 0 && rowBytes > 0 && frameBytes > 0) {
            "Geometría RAW10 inválida"
        }
        val rawFile = File(source, "clip.raw10")
        require(rawFile.isFile) { "Falta clip.raw10" }
        val rawFrames = (rawFile.length() / frameBytes).toInt()
        val rows = readFrames(File(source, "frames.csv"))
        val state = readState(File(source, "clip.state"))
        val completed = state["completed_frames"]?.toIntOrNull()
        val available = minOf(rawFrames, rows.size, completed ?: rawFrames)
        require(available > 0) { "No hay frames RAW10 completos" }

        val parent = source.parentFile ?: error("La toma no tiene directorio padre")
        val outputStem = "CINEMADNG_${CaptureStorage.displayName(source.name)}"
        val existing = File(parent, outputStem)
        if (isValidConvertedClip(existing)) {
            return Raw10ConversionResult(
                source = source,
                output = existing,
                convertedFrames = DngSequenceClip.inspect(existing)?.frameCount ?: 0,
                repeatedFrames = 0,
                audioAvailable = File(existing, PcmAudioRecorder.AUDIO_FILE).isFile,
                skipped = true,
                error = null
            )
        }
        val output = CaptureStorage.createUniqueDirectory(parent, outputStem)
        val dngDir = File(output, DngSequenceClip.DNG_DIRECTORY)
        require(dngDir.mkdirs()) { "No se pudo crear carpeta DNG" }

        var repeated = 0
        var firstTimeline = rows.first().frame
        var lastTimeline = firstTimeline - 1
        var previousDng: File? = null
        val packed = ByteArray(frameBytes)
        val mosaic = ByteArray(width * height * 2)
        RandomAccessFile(rawFile, "r").use { raw ->
            for (streamIndex in 0 until available) {
                val row = rows[streamIndex]
                var frameId = row.frame
                if (streamIndex == 0) firstTimeline = frameId
                if (frameId <= lastTimeline) frameId = lastTimeline + 1
                val previous = previousDng
                if (previous != null) {
                    for (missing in (lastTimeline + 1) until frameId) {
                        val repeatedFile = File(dngDir, "frame_%06d.dng".format(Locale.US, missing))
                        previous.copyTo(repeatedFile, overwrite = false)
                        previousDng = repeatedFile
                        repeated++
                    }
                }
                raw.seek(streamIndex.toLong() * frameBytes)
                raw.readFully(packed)
                unpackRaw10ToLittleEndian16(packed, rowBytes, width, height, mosaic)
                val destination = File(dngDir, "frame_%06d.dng".format(Locale.US, frameId))
                CinemaDngWriter.write(destination, mosaic, metadata, row)
                previousDng = destination
                lastTimeline = frameId
                progress(
                    Raw10ConversionProgress(
                        clipName = source.name,
                        doneFrames = streamIndex + 1,
                        totalFrames = available
                    )
                )
            }
        }
        val timelineFrames = lastTimeline - firstTimeline + 1
        val audioReport = alignAudio(source, output, firstTimeline, timelineFrames, metadata.optInt("frame_rate", 0))
        writePlaybackMetadata(
            output = output,
            source = source,
            metadata = metadata,
            timelineFrames = timelineFrames,
            audioReport = audioReport
        )
        writeConversionReport(
            output = output,
            source = source,
            sourceFrames = available,
            timelineFrames = timelineFrames,
            firstTimeline = firstTimeline,
            lastTimeline = lastTimeline,
            repeated = repeated,
            audioReport = audioReport
        )
        return Raw10ConversionResult(
            source = source,
            output = output,
            convertedFrames = timelineFrames,
            repeatedFrames = repeated,
            audioAvailable = audioReport.available,
            skipped = false,
            error = null
        )
    }

    fun unpackRaw10ToLittleEndian16(
        packed: ByteArray,
        rowBytes: Int,
        width: Int,
        height: Int,
        output: ByteArray
    ) {
        require(output.size >= width * height * 2)
        var outputOffset = 0
        for (y in 0 until height) {
            var inputOffset = y * rowBytes
            val rowEnd = inputOffset + (width / 4) * 5
            while (inputOffset + 4 < rowEnd) {
                val b0 = packed[inputOffset].toInt() and 0xff
                val b1 = packed[inputOffset + 1].toInt() and 0xff
                val b2 = packed[inputOffset + 2].toInt() and 0xff
                val b3 = packed[inputOffset + 3].toInt() and 0xff
                val low = packed[inputOffset + 4].toInt() and 0xff
                outputOffset = putU16Le(output, outputOffset, (b0 shl 2) or (low and 0x03))
                outputOffset = putU16Le(output, outputOffset, (b1 shl 2) or ((low ushr 2) and 0x03))
                outputOffset = putU16Le(output, outputOffset, (b2 shl 2) or ((low ushr 4) and 0x03))
                outputOffset = putU16Le(output, outputOffset, (b3 shl 2) or ((low ushr 6) and 0x03))
                inputOffset += 5
            }
        }
    }

    private fun putU16Le(output: ByteArray, offset: Int, value: Int): Int {
        output[offset] = (value and 0xff).toByte()
        output[offset + 1] = ((value ushr 8) and 0xff).toByte()
        return offset + 2
    }

    private data class AudioReport(
        val available: Boolean,
        val exactSync: Boolean = false,
        val sourceOffsetNs: Long? = null,
        val sampleRate: Int = 0,
        val channels: Int = 0,
        val bitsPerSample: Int = 0,
        val frames: Long = 0
    )

    private fun alignAudio(
        source: File,
        output: File,
        startTimelineFrame: Int,
        outputFrames: Int,
        fps: Int
    ): AudioReport {
        val audioFile = File(source, PcmAudioRecorder.AUDIO_FILE)
        val metadata = runCatching {
            JSONObject(File(source, PcmAudioRecorder.AUDIO_METADATA_FILE).readText())
        }.getOrNull() ?: return AudioReport(false)
        if (!audioFile.isFile || audioFile.length() < WAVE_HEADER_BYTES ||
            outputFrames <= 0 || fps <= 0
        ) {
            return AudioReport(false)
        }
        val channels = metadata.optInt("channels", 0)
        val sampleRate = metadata.optInt("sample_rate", 0)
        val bits = metadata.optInt("bits_per_sample", 0)
        val sampleWidth = bits / 8
        val frameBytes = channels * sampleWidth
        if (channels !in 1..2 || sampleRate <= 0 || sampleWidth !in 2..3 || frameBytes <= 0) {
            return AudioReport(false)
        }
        val sync = runCatching { JSONObject(File(source, "sync.json").readText()) }.getOrNull()
        val offsetNs = if (sync != null && !sync.isNull("camera_start_minus_audio_start_ns")) {
            sync.optLong("camera_start_minus_audio_start_ns", 0L)
        } else {
            null
        }
        val sourceFrames = ((audioFile.length() - WAVE_HEADER_BYTES).coerceAtLeast(0L)) / frameBytes
        val destination = File(output, PcmAudioRecorder.AUDIO_FILE)
        val timelineOffset = (startTimelineFrame.toLong() * sampleRate / fps)
        val cameraOffset = offsetNs?.let { (it * sampleRate / 1_000_000_000.0).roundToLong() } ?: 0L
        var sourceStart = cameraOffset + timelineOffset
        val targetFrames = (outputFrames.toLong() * sampleRate / fps.toLong())
        val silenceBefore = (-sourceStart).coerceAtLeast(0L)
        sourceStart = sourceStart.coerceAtLeast(0L)
        RandomAccessFile(audioFile, "r").use { input ->
            RandomAccessFile(destination, "rw").use { writer ->
                writer.setLength(0L)
                writer.write(waveHeader(0L, sampleRate, channels, bits))
                var remaining = targetFrames
                var payload = 0L
                var silence = minOf(silenceBefore, remaining)
                val silenceBuffer = ByteArray(minOf(sampleRate * frameBytes, 64 * 1024))
                while (silence > 0) {
                    val frames = minOf(silence, (silenceBuffer.size / frameBytes).toLong())
                    val bytes = (frames * frameBytes).toInt()
                    writer.write(silenceBuffer, 0, bytes)
                    payload += bytes
                    remaining -= frames
                    silence -= frames
                }
                if (sourceStart < sourceFrames && remaining > 0) {
                    input.seek(WAVE_HEADER_BYTES + sourceStart * frameBytes)
                    var available = minOf(remaining, sourceFrames - sourceStart)
                    val copyBuffer = ByteArray(64 * 1024)
                    while (available > 0) {
                        val bytes = minOf(copyBuffer.size.toLong(), available * frameBytes).toInt()
                        val read = input.read(copyBuffer, 0, bytes)
                        if (read <= 0) break
                        val aligned = read - read % frameBytes
                        writer.write(copyBuffer, 0, aligned)
                        val copiedFrames = aligned / frameBytes
                        payload += aligned
                        remaining -= copiedFrames.toLong()
                        available -= copiedFrames.toLong()
                    }
                }
                while (remaining > 0) {
                    val frames = minOf(remaining, (silenceBuffer.size / frameBytes).toLong())
                    val bytes = (frames * frameBytes).toInt()
                    writer.write(silenceBuffer, 0, bytes)
                    payload += bytes
                    remaining -= frames
                }
                writer.seek(0L)
                writer.write(waveHeader(payload, sampleRate, channels, bits))
            }
        }
        return AudioReport(
            available = true,
            exactSync = offsetNs != null,
            sourceOffsetNs = offsetNs,
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bits,
            frames = targetFrames
        )
    }

    private fun waveHeader(payloadBytes: Long, sampleRate: Int, channels: Int, bits: Int): ByteArray {
        val frameBytes = channels * bits / 8
        return ByteBuffer.allocate(WAVE_HEADER_BYTES.toInt())
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
                putInt(sampleRate * frameBytes)
                putShort(frameBytes.toShort())
                putShort(bits.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(payloadBytes.coerceAtMost(0xffff_ffffL).toInt())
            }
            .array()
    }

    private fun writePlaybackMetadata(
        output: File,
        source: File,
        metadata: JSONObject,
        timelineFrames: Int,
        audioReport: AudioReport
    ) {
        val clip = JSONObject(metadata.toString()).apply {
            put("format", "rawlite-dng")
            put("version", 1)
            put("mode", "RAW")
            put("container", "CinemaDNG sequence")
            put("dng_directory", DngSequenceClip.DNG_DIRECTORY)
            put("bits_per_sample", 16)
            put("saved_frames", timelineFrames)
            put("timeline_frames", timelineFrames)
            put("finalized", true)
            put("source_format", metadata.optString("format"))
            put("source_directory", source.name)
        }
        File(output, "clip.json").writeText(clip.toString(2))
        if (!audioReport.available) return
        val audio = JSONObject().apply {
            put("format", "wav-pcm")
            put("status", "closed")
            put("file", PcmAudioRecorder.AUDIO_FILE)
            put("sample_rate", audioReport.sampleRate)
            put("channels", audioReport.channels)
            put("bits_per_sample", audioReport.bitsPerSample)
            put("frames", audioReport.frames)
            put(
                "data_bytes",
                audioReport.frames * audioReport.channels * audioReport.bitsPerSample / 8
            )
            put("finalized", true)
        }
        File(output, PcmAudioRecorder.AUDIO_METADATA_FILE).writeText(audio.toString(2))
        val sync = JSONObject().apply {
            put("version", 1)
            put("video_clock", "CINEMADNG_TIMELINE")
            put("audio_clock", "CINEMADNG_TIMELINE")
            put("camera_start_minus_audio_start_ns", 0)
            audioReport.sourceOffsetNs?.let { put("source_camera_start_minus_audio_start_ns", it) }
            put("audio_file", PcmAudioRecorder.AUDIO_FILE)
            put("audio_sample_rate", audioReport.sampleRate)
            put("audio_channels", audioReport.channels)
            put("audio_bits_per_sample", audioReport.bitsPerSample)
            put("audio_frames", audioReport.frames)
            put("finalized", true)
        }
        File(output, "sync.json").writeText(sync.toString(2))
    }

    private fun writeConversionReport(
        output: File,
        source: File,
        sourceFrames: Int,
        timelineFrames: Int,
        firstTimeline: Int,
        lastTimeline: Int,
        repeated: Int,
        audioReport: AudioReport
    ) {
        val report = JSONObject().apply {
            put("source", source.absolutePath)
            put("output", output.absolutePath)
            put("source_frames", sourceFrames)
            put("timeline_frames", timelineFrames)
            put("first_timeline_frame", firstTimeline)
            put("last_timeline_frame", lastTimeline)
            put("repeated_frame_count", repeated)
            put("audio_available", audioReport.available)
            put("audio_exact_sync", audioReport.exactSync)
        }
        File(output, "conversion_report.json").writeText(report.toString(2))
    }

    private fun isValidConvertedClip(directory: File): Boolean {
        if (!directory.isDirectory) return false
        return DngSequenceClip.inspect(directory)?.captureFormat == "CinemaDNG"
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
                val parsed = runCatching {
                    fun value(name: String): String = values[indices.getValue(name)]
                    RawFrameInfo(
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
                }.getOrNull() ?: break
                output += parsed
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

    private fun addLegacyFactoryProfile(metadata: JSONObject) {
        val required = listOf(
            "calibration_matrix_1",
            "calibration_matrix_2",
            "forward_matrix_1",
            "forward_matrix_2"
        )
        if (required.all { (metadata.optJSONArray(it)?.length() ?: 0) == 9 }) return
        val isPixel6Main = metadata.optString("camera_id") == "2" &&
            metadata.optString("camera").startsWith("Principal") &&
            metadata.optString("cfa") == "GBRG" &&
            metadata.optInt("width") in setOf(2032, 4080)
        if (!isPixel6Main) return
        PIXEL_6_MAIN_FACTORY_PROFILE.forEach { (key, values) ->
            if ((metadata.optJSONArray(key)?.length() ?: 0) != 9) {
                metadata.put(key, values)
            }
        }
    }

    private fun matrix(vararg values: Pair<Int, Int>) = org.json.JSONArray().apply {
        values.forEach { (numerator, denominator) ->
            put(org.json.JSONArray().put(numerator).put(denominator))
        }
    }

    private const val WAVE_HEADER_BYTES = 44L

    private val PIXEL_6_MAIN_FACTORY_PROFILE = mapOf(
        "calibration_matrix_1" to matrix(
            6611 to 6709, 0 to 1, 0 to 1,
            0 to 1, 1 to 1, 0 to 1,
            0 to 1, 0 to 1, 4131 to 4131
        ),
        "calibration_matrix_2" to matrix(
            4629 to 4736, 0 to 1, 0 to 1,
            0 to 1, 1 to 1, 0 to 1,
            0 to 1, 0 to 1, 7217 to 7246
        ),
        "forward_matrix_1" to matrix(
            4362 to 10000, 3850 to 10000, 1431 to 10000,
            2226 to 10000, 7168 to 10000, 606 to 10000,
            139 to 10000, 971 to 10000, 7141 to 10000
        ),
        "forward_matrix_2" to matrix(
            4361 to 10000, 3851 to 10000, 1431 to 10000,
            2225 to 10000, 7169 to 10000, 606 to 10000,
            139 to 10000, 971 to 10000, 7141 to 10000
        )
    )
}
