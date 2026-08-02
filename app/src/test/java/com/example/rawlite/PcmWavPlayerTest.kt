package com.example.rawlite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmWavPlayerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun inspect_readsStereo24BitDurationAndDataBounds() {
        val file = temporaryFolder.newFile("audio.wav")
        val frames = 48_000
        file.writeBytes(wav(sampleRate = 48_000, channels = 2, bits = 24, frames = frames))

        val info = requireNotNull(PcmWavPlayer.inspect(file))

        assertEquals(48_000, info.sampleRate)
        assertEquals(2, info.channels)
        assertEquals(24, info.bitsPerSample)
        assertEquals(44L, info.dataOffset)
        assertEquals(frames.toLong(), info.frames)
        assertEquals(1_000, info.durationMs)
    }

    @Test
    fun inspect_readsMono16Bit() {
        val file = temporaryFolder.newFile("mono.wav")
        file.writeBytes(wav(sampleRate = 48_000, channels = 1, bits = 16, frames = 24_000))

        val info = requireNotNull(PcmWavPlayer.inspect(file))

        assertEquals(1, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(500, info.durationMs)
    }

    @Test
    fun inspect_rejectsTruncatedFile() {
        val file = temporaryFolder.newFile("broken.wav")
        file.writeBytes("RIFF".toByteArray())

        assertNull(PcmWavPlayer.inspect(file))
    }

    private fun wav(sampleRate: Int, channels: Int, bits: Int, frames: Int): ByteArray {
        val bytesPerFrame = channels * bits / 8
        val dataBytes = frames * bytesPerFrame
        return ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataBytes)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * bytesPerFrame)
            putShort(bytesPerFrame.toShort())
            putShort(bits.toShort())
            put("data".toByteArray())
            putInt(dataBytes)
            put(ByteArray(dataBytes))
        }.array()
    }
}
