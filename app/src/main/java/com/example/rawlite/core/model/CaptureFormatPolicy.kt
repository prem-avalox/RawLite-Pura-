package com.example.rawlite.core.model

internal enum class CaptureOutput {
    CINEMA_DNG,
    RAW10,
    PURE10
}

internal object CaptureFormatPolicy {
    fun output(mode: CaptureMode, selectedRawIndex: Int): CaptureOutput = when (mode) {
        CaptureMode.PURE10 -> CaptureOutput.PURE10
        CaptureMode.RAW -> if (selectedRawIndex > 0) {
            CaptureOutput.RAW10
        } else {
            CaptureOutput.CINEMA_DNG
        }
    }

    fun selectionIsValid(mode: CaptureMode, selectedRawIndex: Int): Boolean =
        mode != CaptureMode.PURE10 || selectedRawIndex >= 0
}
