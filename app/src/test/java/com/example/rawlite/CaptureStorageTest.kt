package com.example.rawlite

import com.example.rawlite.core.model.CaptureOutput
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStorageTest {
    @Test
    fun captureStem_usesReadableFormatPrefix() {
        assertEquals(
            "RAW10_20260808_142000",
            CaptureStorage.captureStem(CaptureOutput.RAW10, "20260808_142000")
        )
        assertEquals(
            "PURE10_20260808_142000",
            CaptureStorage.captureStem(CaptureOutput.PURE10, "20260808_142000")
        )
        assertEquals(
            "CINEMADNG_20260808_142000",
            CaptureStorage.captureStem(CaptureOutput.CINEMA_DNG, "20260808_142000")
        )
    }

    @Test
    fun displayName_removesKnownCapturePrefixes() {
        assertEquals("20260808_142000", CaptureStorage.displayName("RAW10_20260808_142000"))
        assertEquals("20260808_142000", CaptureStorage.displayName("PURE10_20260808_142000"))
        assertEquals("20260808_142000", CaptureStorage.displayName("CINEMADNG_20260808_142000"))
        assertEquals("20260808_142000", CaptureStorage.displayName("RawLite_20260808_142000"))
        assertEquals("CustomName", CaptureStorage.displayName("CustomName"))
    }

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
