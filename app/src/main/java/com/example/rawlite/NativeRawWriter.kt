package com.example.rawlite

import java.nio.ByteBuffer

internal object NativeRawWriter {
    init {
        System.loadLibrary("rawlite_writer")
    }

    external fun open(
        rawPath: String,
        metadataPath: String,
        statePath: String,
        width: Int,
        height: Int,
        rowBytes: Int,
        checkpointFrames: Int
    ): Long

    external fun writeFrame(
        handle: Long,
        buffer: ByteBuffer,
        bufferOffset: Int,
        bufferLimit: Int,
        rowStride: Int,
        frame: Int,
        timestampNs: Long,
        iso: Int,
        exposureNs: Long,
        frameDurationNs: Long,
        focusDiopters: Float,
        wbRed: Float,
        wbGreenEven: Float,
        wbGreenOdd: Float,
        wbBlue: Float
    ): Int

    external fun close(handle: Long): Int

    external fun lastError(): String
}
