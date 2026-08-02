package com.example.rawlite

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStorageTest {
    @Test
    fun createUniqueDirectory_neverReusesAnExistingTake() {
        val base = Files.createTempDirectory("rawlite-storage").toFile()
        try {
            val first = CaptureStorage.createUniqueDirectory(base, "RawLite_20260802_120000")
            val second = CaptureStorage.createUniqueDirectory(base, "RawLite_20260802_120000")
            val third = CaptureStorage.createUniqueDirectory(base, "RawLite_20260802_120000")

            assertEquals("RawLite_20260802_120000", first.name)
            assertEquals("RawLite_20260802_120000_01", second.name)
            assertEquals("RawLite_20260802_120000_02", third.name)
            assertTrue(first.isDirectory)
            assertTrue(second.isDirectory)
            assertTrue(third.isDirectory)
        } finally {
            base.walkBottomUp().forEach { it.delete() }
        }
    }
}
