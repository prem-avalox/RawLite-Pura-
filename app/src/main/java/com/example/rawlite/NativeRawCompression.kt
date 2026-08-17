package com.example.rawlite

import java.nio.ByteBuffer

internal object NativeRawCompression {
    init {
        System.loadLibrary("rawlite_writer")
    }

    external fun decodeFrame(
        payload: ByteBuffer,
        payloadSize: Int,
        flags: Int,
        rawCrc32: Long,
        payloadCrc32: Long,
        output: ByteBuffer,
        width: Int,
        height: Int,
        rowBytes: Int
    ): Int
}
