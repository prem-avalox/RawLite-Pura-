package com.example.rawlite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceFrameBufferTest {
    @Test
    fun buffer_reportsMissingAndContiguousFrames() {
        val released = mutableListOf<String>()
        val buffer = SequenceFrameBuffer<String>(4, released::add)
        buffer.put(3, "3")
        buffer.put(4, "4")
        buffer.put(6, "6")

        assertEquals(listOf(5), buffer.missingWindow(3, 4, 10))
        assertEquals(2, buffer.contiguousReadyCount(3, 10))
    }

    @Test
    fun buffer_isBoundedAndReleasesOldFrames() {
        val released = mutableListOf<String>()
        val buffer = SequenceFrameBuffer<String>(2, released::add)
        buffer.put(0, "0")
        buffer.put(1, "1")
        buffer.put(2, "2")

        assertFalse(buffer.contains(0))
        assertTrue(buffer.contains(1))
        assertTrue(buffer.contains(2))
        assertEquals(listOf("0"), released)
    }

    @Test
    fun readingOldFrame_doesNotEvictPrefetchedFuture() {
        val released = mutableListOf<String>()
        val buffer = SequenceFrameBuffer<String>(3, released::add)
        buffer.put(0, "0")
        buffer.put(1, "1")
        buffer.put(2, "2")

        assertEquals("0", buffer[0])
        buffer.put(3, "3")

        assertFalse(buffer.contains(0))
        assertTrue(buffer.contains(1))
        assertTrue(buffer.contains(2))
        assertTrue(buffer.contains(3))
        assertEquals(listOf("0"), released)
    }
}
