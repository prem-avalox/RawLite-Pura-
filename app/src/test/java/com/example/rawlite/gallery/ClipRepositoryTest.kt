package com.example.rawlite.gallery

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClipRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun list_inspectsCaptureMetadataInsteadOfDependingOnDirectoryPrefix() {
        val base = temporaryFolder.newFolder("Movies")
        createMinimalRaw10Clip(File(base, "RAW10_20260808_142000"))
        createMinimalRaw10Clip(File(base, "RawLite_20260808_141000"))
        createMinimalRaw10Clip(File(base, "Manual_Import"))
        base.resolve("Notes").mkdir()

        val clips = ClipRepository().list(base)

        assertEquals(
            listOf("Manual_Import", "RAW10_20260808_142000", "RawLite_20260808_141000"),
            clips.map { it.directory.name }.sorted()
        )
    }

    private fun createMinimalRaw10Clip(directory: File) {
        directory.mkdirs()
        directory.resolve("clip.json").writeText(
            """{"format":"rawlite-raw10","width":4,"height":2,"row_bytes":5,"frame_rate":30}"""
        )
        directory.resolve("clip.raw10").writeBytes(ByteArray(10))
        directory.resolve("clip.state").writeText(
            "version=1\nstatus=closed\ncompleted_frames=1\nraw_bytes=10\n"
        )
        directory.resolve("frames.csv").writeText(
            "frame,timestamp_ns,iso,exposure_ns,frame_duration_ns,focus_diopters," +
                "wb_r,wb_ge,wb_go,wb_b\n" +
                "0,1000000000,100,10000000,33333333,0.0,2.0,1.0,1.0,1.8\n"
        )
    }
}
