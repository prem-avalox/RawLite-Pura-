package com.example.rawlite

import android.graphics.Bitmap
import java.nio.ByteBuffer

internal object NativeTenBitConverter {
    init {
        System.loadLibrary("rawlite_writer")
    }

    external fun raw10ToP010Hlg(
        raw: ByteBuffer,
        rawOffset: Int,
        rawLimit: Int,
        rawRowStride: Int,
        output: ByteBuffer,
        sourceWidth: Int,
        sourceHeight: Int,
        cropX: Int,
        cropY: Int,
        outputWidth: Int,
        outputHeight: Int,
        outputRowStrideBytes: Int,
        outputSliceHeight: Int,
        colorFilterArrangement: Int,
        blackLevel: Int,
        whiteLevel: Int,
        redGain: Float,
        blueGain: Float,
        exposureGain: Float
    ): Long

    external fun raw10ToArgbPreview(
        raw: ByteBuffer,
        rawOffset: Int,
        rawLimit: Int,
        rawRowStride: Int,
        output: Bitmap,
        sourceWidth: Int,
        sourceHeight: Int,
        sampleStep: Int,
        colorFilterArrangement: Int,
        blackLevel0: Int,
        blackLevel1: Int,
        blackLevel2: Int,
        blackLevel3: Int,
        whiteLevel: Int,
        redGain: Float,
        greenEvenGain: Float,
        greenOddGain: Float,
        blueGain: Float
    ): Long
}
