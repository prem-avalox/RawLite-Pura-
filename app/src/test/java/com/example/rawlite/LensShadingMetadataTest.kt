package com.example.rawlite

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.io.path.createTempDirectory

class LensShadingMetadataTest {
    @Test
    fun `sidecar preserves per-frame maps and ignores a truncated tail`() {
        val directory = createTempDirectory().toFile()
        val file = File(directory, LensShadingMetadataWriter.FILE_NAME)
        val first = sample(1f)
        val second = sample(2f)
        LensShadingMetadataWriter(file).use {
            it.write(3, first)
            it.write(7, second)
        }
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - 5) }

        LensShadingMetadataReader.openOrNull(directory).use { reader ->
            requireNotNull(reader)
            val restored = requireNotNull(reader.sampleForFrame(3))
            assertEquals(2, restored.rows)
            assertEquals(2, restored.columns)
            assertArrayEquals(first.gains, restored.gains, 0f)
            assertNull(reader.sampleForFrame(7))
        }
    }

    private fun sample(base: Float) = LensShadingSample(
        rows = 2,
        columns = 2,
        gains = FloatArray(16) { base + it / 100f }
    )
}
