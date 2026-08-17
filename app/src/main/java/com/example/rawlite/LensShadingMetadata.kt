package com.example.rawlite

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

internal data class LensShadingSample(
    val rows: Int,
    val columns: Int,
    val gains: FloatArray
) {
    init {
        require(rows >= 2 && columns >= 2)
        require(gains.size == rows * columns * CHANNELS)
        require(gains.all { it.isFinite() && it >= 1f })
    }

    companion object {
        const val CHANNELS = 4
    }
}

/**
 * Per-frame Camera2 lens-shading maps. The RAW mosaic stays untouched; this sidecar lets
 * preview, PURE 10 and later CinemaDNG conversion apply the HAL-provided optical correction.
 */
internal class LensShadingMetadataWriter(file: File) : Closeable {
    private val fileStream = FileOutputStream(file)
    private val output = BufferedOutputStream(fileStream, BUFFER_BYTES)
    private var closed = false

    init {
        output.write(FILE_MAGIC)
        output.writeLeInt(VERSION)
        output.writeLeInt(FILE_HEADER_BYTES)
        repeat(FILE_HEADER_BYTES - FILE_MAGIC.size - 8) { output.write(0) }
    }

    fun write(frame: Int, sample: LensShadingSample) {
        check(!closed)
        val payload = ByteBuffer.allocate(sample.gains.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        sample.gains.forEach(payload::putFloat)
        val bytes = payload.array()
        val crc = CRC32().apply { update(bytes) }.value.toInt()
        output.write(RECORD_MAGIC)
        output.writeLeInt(frame)
        output.writeLeInt(sample.rows)
        output.writeLeInt(sample.columns)
        output.writeLeInt(sample.gains.size)
        output.writeLeInt(crc)
        output.write(bytes)
    }

    override fun close() {
        if (closed) return
        closed = true
        output.flush()
        fileStream.fd.sync()
        output.close()
    }

    companion object {
        const val FILE_NAME = "lens_shading.bin"
        private val FILE_MAGIC = "RLLSM001".encodeToByteArray()
        private val RECORD_MAGIC = "LSM1".encodeToByteArray()
        private const val VERSION = 1
        private const val FILE_HEADER_BYTES = 32
        private const val RECORD_HEADER_BYTES = 24
        private const val BUFFER_BYTES = 64 * 1024

        internal fun isFileMagic(value: ByteArray): Boolean = value.contentEquals(FILE_MAGIC)
        internal fun isRecordMagic(value: ByteArray): Boolean = value.contentEquals(RECORD_MAGIC)
        internal val fileMagicSize: Int get() = FILE_MAGIC.size
        internal val recordMagicSize: Int get() = RECORD_MAGIC.size
        internal val fileHeaderBytes: Int get() = FILE_HEADER_BYTES
        internal val recordHeaderBytes: Int get() = RECORD_HEADER_BYTES
        internal val version: Int get() = VERSION
    }
}

internal class LensShadingMetadataReader private constructor(
    private val input: RandomAccessFile,
    private val records: Map<Int, Record>
) : Closeable {
    private data class Record(
        val payloadOffset: Long,
        val rows: Int,
        val columns: Int,
        val count: Int,
        val crc: Int
    )

    private var cachedFrame = Int.MIN_VALUE
    private var cachedSample: LensShadingSample? = null

    @Synchronized
    fun sampleForFrame(frame: Int): LensShadingSample? {
        if (frame == cachedFrame) return cachedSample
        val record = records[frame] ?: return null
        val bytes = ByteArray(record.count * Float.SIZE_BYTES)
        input.seek(record.payloadOffset)
        input.readFully(bytes)
        if (CRC32().apply { update(bytes) }.value.toInt() != record.crc) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val gains = FloatArray(record.count) { buffer.float }
        return runCatching { LensShadingSample(record.rows, record.columns, gains) }
            .getOrNull()
            .also {
                cachedFrame = frame
                cachedSample = it
            }
    }

    override fun close() = input.close()

    companion object {
        fun openOrNull(directory: File): LensShadingMetadataReader? {
            val file = File(directory, LensShadingMetadataWriter.FILE_NAME)
            if (!file.isFile) return null
            val input = RandomAccessFile(file, "r")
            return runCatching {
                val magic = ByteArray(LensShadingMetadataWriter.fileMagicSize)
                input.readFully(magic)
                require(LensShadingMetadataWriter.isFileMagic(magic))
                require(input.readLeInt() == LensShadingMetadataWriter.version)
                val headerBytes = input.readLeInt()
                require(headerBytes >= LensShadingMetadataWriter.fileHeaderBytes)
                input.seek(headerBytes.toLong())
                val records = linkedMapOf<Int, Record>()
                while (input.filePointer + LensShadingMetadataWriter.recordHeaderBytes <= input.length()) {
                    val recordMagic = ByteArray(LensShadingMetadataWriter.recordMagicSize)
                    input.readFully(recordMagic)
                    if (!LensShadingMetadataWriter.isRecordMagic(recordMagic)) break
                    val frame = input.readLeInt()
                    val rows = input.readLeInt()
                    val columns = input.readLeInt()
                    val count = input.readLeInt()
                    val crc = input.readLeInt()
                    val payloadBytes = count.toLong() * Float.SIZE_BYTES
                    if (rows < 2 || columns < 2 || count != rows * columns * LensShadingSample.CHANNELS ||
                        payloadBytes < 0 || input.filePointer + payloadBytes > input.length()
                    ) break
                    records[frame] = Record(input.filePointer, rows, columns, count, crc)
                    input.seek(input.filePointer + payloadBytes)
                }
                LensShadingMetadataReader(input, records)
            }.getOrElse {
                input.close()
                null
            }
        }
    }
}

private fun BufferedOutputStream.writeLeInt(value: Int) {
    write(value and 0xff)
    write((value ushr 8) and 0xff)
    write((value ushr 16) and 0xff)
    write((value ushr 24) and 0xff)
}

private fun RandomAccessFile.readLeInt(): Int {
    val b0 = read()
    val b1 = read()
    val b2 = read()
    val b3 = read()
    if (b0 or b1 or b2 or b3 < 0) throw EOFException()
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}
