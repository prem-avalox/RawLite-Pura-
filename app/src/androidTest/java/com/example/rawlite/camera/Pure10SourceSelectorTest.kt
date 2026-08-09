package com.example.rawlite.camera

import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Pure10SourceSelectorTest {
    @Test
    fun selectsSmallestRaw10SourceThatContainsUhd() {
        val selected = Pure10SourceSelector.select(
            raw10Sizes = listOf(
                Size(4080, 3072),
                Size(4080, 2288),
                Size(2032, 1536)
            ),
            outputSize = Size(3840, 2160),
            fallback = Size(4080, 3072)
        )

        assertEquals(Size(4080, 2288), selected)
    }

    @Test
    fun keepsFallbackWhenNoRaw10SourceContainsOutput() {
        val fallback = Size(2032, 1536)

        val selected = Pure10SourceSelector.select(
            raw10Sizes = listOf(Size(1920, 1080)),
            outputSize = fallback,
            fallback = fallback
        )

        assertEquals(fallback, selected)
    }
}
