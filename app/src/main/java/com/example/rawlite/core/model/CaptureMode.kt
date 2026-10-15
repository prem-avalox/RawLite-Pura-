package com.example.rawlite.core.model

internal enum class CaptureMode {
    RAW,
    TEN_BIT,
    EIGHT_BIT;

    companion object {
        @Deprecated("Use TEN_BIT instead", ReplaceWith("TEN_BIT"))
        @JvmField
        val PURE10: CaptureMode = TEN_BIT
    }
}
