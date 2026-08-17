package com.example.rawlite

import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

internal interface Raw10FrameStore : Closeable {
    val frameCount: Int
    val storedBytes: Long
    val compressed: Boolean

    fun readFrame(index: Int, output: ByteBuffer)

    companion object {
        const val LEGACY_FILE = "clip.raw10"
        const val COMPRESSED_FILE = "clip.raw10c"

        fun open(directory: File, metadata: JSONObject): Raw10FrameStore {
            val width = metadata.getInt("width")
            val height = metadata.getInt("height")
            val rowBytes = metadata.getInt("row_bytes")
            val compressedFile = File(directory, COMPRESSED_FILE)
            return if (compressedFile.isFile) {
                CompressedRaw10FrameStore(compressedFile, width, height, rowBytes)
            } else {
                val legacy = File(directory, LEGACY_FILE)
                require(legacy.isFile) { "Falta $LEGACY_FILE o $COMPRESSED_FILE" }
                LegacyRaw10FrameStore(legacy, height, rowBytes)
            }
        }
    }
}

private class LegacyRaw10FrameStore(
    private val file: File,
    height: Int,
    rowBytes: Int
) : Raw10FrameStore {
    private val stream = RandomAccessFile(file, "r")
    private val channel = stream.channel
    private val frameBytes = Math.multiplyExact(rowBytes, height)

    override val frameCount: Int = (file.length() / frameBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    override val storedBytes: Long = file.length()
    override val compressed: Boolean = false

    override fun readFrame(index: Int, output: ByteBuffer) {
        require(index in 0 until frameCount) { "Frame fuera de rango" }
        require(output.isDirect && output.capacity() >= frameBytes) { "Buffer RAW10 inválido" }
        output.clear()
        output.limit(frameBytes)
        readFully(channel, output, index.toLong() * frameBytes)
        output.flip()
    }

    override fun close() {
        channel.close()
        stream.close()
    }
}

private class CompressedRaw10FrameStore(
    private val file: File,
    private val width: Int,
    private val height: Int,
    private val rowBytes: Int
) : Raw10FrameStore {
    private data class FrameChunk(
        val payloadOffset: Long,
        val payloadSize: Int,
        val flags: Int,
        val rawCrc32: Long,
        val payloadCrc32: Long
    )

    private val stream = RandomAccessFile(file, "r")
    private val channel = stream.channel
    private val frameBytes = Math.multiplyExact(rowBytes, height)
    private val chunks: List<FrameChunk>
    private val payloadBuffer: ByteBuffer

    override val frameCount: Int
        get() = chunks.size
    override val storedBytes: Long = file.length()
    override val compressed: Boolean = true

    init {
        val header = ByteBuffer.allocate(CONTAINER_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        readFully(channel, header, 0L)
        header.flip()
        val magic = ByteArray(CONTAINER_MAGIC.size).also(header::get)
        require(magic.contentEquals(CONTAINER_MAGIC)) { "Cabecera RAW10C inválida" }
        require(header.int == CONTAINER_HEADER_BYTES) { "Tamaño de cabecera RAW10C incompatible" }
        require(header.int == CONTAINER_VERSION) { "Versión RAW10C incompatible" }
        require(header.int == width && header.int == height && header.int == rowBytes) {
            "La geometría RAW10C no coincide con clip.json"
        }
        require(header.int == frameBytes) { "Tamaño de frame RAW10C incompatible" }

        val parsed = ArrayList<FrameChunk>()
        var offset = CONTAINER_HEADER_BYTES.toLong()
        var maximumPayload = 1
        val frameHeader = ByteBuffer.allocate(FRAME_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        while (offset + FRAME_HEADER_BYTES <= channel.size()) {
            frameHeader.clear()
            readFully(channel, frameHeader, offset)
            frameHeader.flip()
            val frameMagic = ByteArray(FRAME_MAGIC.size).also(frameHeader::get)
            if (!frameMagic.contentEquals(FRAME_MAGIC)) break
            if (frameHeader.int != FRAME_HEADER_BYTES) break
            frameHeader.int // Timeline frame; frames.csv remains authoritative.
            val flags = frameHeader.int
            val rawSize = frameHeader.int
            val payloadSize = frameHeader.int
            val rawCrc = frameHeader.int.toLong() and UINT32_MASK
            val payloadCrc = frameHeader.int.toLong() and UINT32_MASK
            if (rawSize != frameBytes || payloadSize <= 0 || payloadSize > frameBytes + 65_536) break
            val payloadOffset = offset + FRAME_HEADER_BYTES
            val next = payloadOffset + payloadSize
            if (next > channel.size()) break
            parsed += FrameChunk(payloadOffset, payloadSize, flags, rawCrc, payloadCrc)
            maximumPayload = maxOf(maximumPayload, payloadSize)
            offset = next
        }
        chunks = parsed
        payloadBuffer = ByteBuffer.allocateDirect(maximumPayload)
    }

    @Synchronized
    override fun readFrame(index: Int, output: ByteBuffer) {
        require(index in chunks.indices) { "Frame fuera de rango" }
        require(output.isDirect && output.capacity() >= frameBytes) { "Buffer RAW10 inválido" }
        val chunk = chunks[index]
        payloadBuffer.clear()
        payloadBuffer.limit(chunk.payloadSize)
        readFully(channel, payloadBuffer, chunk.payloadOffset)
        payloadBuffer.flip()
        output.clear()
        output.limit(frameBytes)
        val result = NativeRawCompression.decodeFrame(
            payload = payloadBuffer,
            payloadSize = chunk.payloadSize,
            flags = chunk.flags,
            rawCrc32 = chunk.rawCrc32,
            payloadCrc32 = chunk.payloadCrc32,
            output = output,
            width = width,
            height = height,
            rowBytes = rowBytes
        )
        check(result == 0) {
            "RAW10C corrupto en frame $index ($result): ${NativeRawWriter.lastError()}"
        }
        output.position(0)
        output.limit(frameBytes)
    }

    override fun close() {
        channel.close()
        stream.close()
    }

    companion object {
        private const val CONTAINER_HEADER_BYTES = 64
        private const val FRAME_HEADER_BYTES = 32
        private const val CONTAINER_VERSION = 1
        private const val UINT32_MASK = 0xffff_ffffL
        private val CONTAINER_MAGIC = "RLR10C01".toByteArray(Charsets.US_ASCII)
        private val FRAME_MAGIC = "RLF1".toByteArray(Charsets.US_ASCII)
    }
}

private fun readFully(channel: FileChannel, output: ByteBuffer, offset: Long) {
    var position = offset
    while (output.hasRemaining()) {
        val read = channel.read(output, position)
        check(read > 0) { "Archivo RAW10 incompleto" }
        position += read
    }
}
