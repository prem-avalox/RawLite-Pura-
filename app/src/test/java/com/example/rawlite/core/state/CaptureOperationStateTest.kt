package com.example.rawlite.core.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureOperationStateTest {
    @Test
    fun `ready permits interaction and session changes`() {
        val state = CaptureOperationState(false, false, false, false)

        assertEquals(CaptureOperationState.Status.READY, state.status)
        assertTrue(state.interactionEnabled)
        assertTrue(state.sessionControlsEnabled)
    }

    @Test
    fun `recording preserves parameter interaction but freezes session controls`() {
        val state = CaptureOperationState(true, false, false, false)

        assertEquals(CaptureOperationState.Status.RECORDING, state.status)
        assertTrue(state.interactionEnabled)
        assertFalse(state.sessionControlsEnabled)
    }

    @Test
    fun `finalization takes precedence over preparation`() {
        val state = CaptureOperationState(false, true, true, false)

        assertEquals(CaptureOperationState.Status.FINALIZING, state.status)
        assertFalse(state.interactionEnabled)
        assertFalse(state.sessionControlsEnabled)
    }
}
