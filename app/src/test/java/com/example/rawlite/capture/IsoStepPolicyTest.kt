package com.example.rawlite.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoStepPolicyTest {
    @Test
    fun usesConventionalThirdStopValues() {
        val values = IsoStepPolicy.steps(100, 800)
        assertEquals(
            listOf(100, 125, 160, 200, 250, 320, 400, 500, 640, 800),
            values
        )
    }

    @Test
    fun preservesCameraRangeBoundaries() {
        val values = IsoStepPolicy.steps(83, 1217)
        assertEquals(83, values.first())
        assertEquals(1217, values.last())
        assertTrue(100 in values)
        assertTrue(800 in values)
    }

    @Test
    fun snapsToNearestPhotographicIso() {
        assertEquals(400, IsoStepPolicy.nearest(417, 100, 1600))
        assertEquals(250, IsoStepPolicy.nearest(233, 100, 1600))
    }

    @Test
    fun progressRoundTripsToAStableStep() {
        val progress = IsoStepPolicy.progressFor(417, 100, 1600)
        assertEquals(400, IsoStepPolicy.valueForProgress(progress, 100, 1600))
    }
}
