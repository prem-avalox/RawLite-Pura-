package com.example.rawlite.core.model

internal enum class CaptureOutput {
    CINEMA_DNG,
    RAW10,
    TEN_BIT,
    EIGHT_BIT;

    companion object {
        @Deprecated("Use TEN_BIT instead", ReplaceWith("TEN_BIT"))
        @JvmField
        val PURE10: CaptureOutput = TEN_BIT
    }
}

internal object CaptureFormatPolicy {
    fun output(mode: CaptureMode, selectedRawIndex: Int): CaptureOutput = when (mode) {
        CaptureMode.TEN_BIT -> CaptureOutput.TEN_BIT
        CaptureMode.EIGHT_BIT -> CaptureOutput.EIGHT_BIT
        CaptureMode.RAW -> if (selectedRawIndex > 0) {
            CaptureOutput.RAW10
        } else {
            CaptureOutput.CINEMA_DNG
        }
    }

    fun selectionIsValid(mode: CaptureMode, selectedRawIndex: Int): Boolean =
        (mode != CaptureMode.TEN_BIT && mode != CaptureMode.EIGHT_BIT) || selectedRawIndex >= 0
}
