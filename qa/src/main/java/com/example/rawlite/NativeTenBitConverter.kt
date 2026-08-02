package com.example.rawlite

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
        width: Int,
        height: Int,
        outputRowStrideBytes: Int,
        outputSliceHeight: Int,
        colorFilterArrangement: Int,
        blackLevel: Int,
        whiteLevel: Int,
        redGain: Float,
        blueGain: Float,
        exposureGain: Float
    ): Long
}
