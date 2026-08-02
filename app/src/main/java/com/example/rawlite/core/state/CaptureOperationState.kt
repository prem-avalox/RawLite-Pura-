package com.example.rawlite.core.state

/**
 * Read-only operational state consumed by UI policy. The camera callbacks remain the source of
 * truth while the legacy activity is decomposed; this prevents a second, competing state machine.
 */
internal data class CaptureOperationState(
    val recording: Boolean,
    val switchingSession: Boolean,
    val finalizingOutput: Boolean,
    val finalizingAudio: Boolean
) {
    val interactionEnabled: Boolean
        get() = !switchingSession && !finalizingOutput && !finalizingAudio

    val sessionControlsEnabled: Boolean
        get() = !recording && interactionEnabled

    val status: Status
        get() = when {
            finalizingOutput || finalizingAudio -> Status.FINALIZING
            switchingSession -> Status.PREPARING
            recording -> Status.RECORDING
            else -> Status.READY
        }

    enum class Status(val label: String) {
        READY("LISTO"),
        PREPARING("PREPARANDO"),
        RECORDING("REC"),
        FINALIZING("GUARDANDO")
    }
}
