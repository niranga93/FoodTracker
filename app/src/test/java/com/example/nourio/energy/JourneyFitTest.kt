package com.example.nourio.energy

import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyFitTest {
    private val base = (-20..0).map { EstimatePoint(it, 72.0, true) }

    @Test
    fun levelAloneIsShrunkTowardTheStart() {
        val one = JourneyModel.fitLevel(base, listOf(JourneyModel.Obs(-20, 73.0, 0.6)))
        assertTrue(one in 0.2..0.6)
    }

    @Test
    fun manyWeighInsSetTheLevel() {
        val obs = (-20..0).map { JourneyModel.Obs(it, 72.8, 0.6) }
        // Close to the 0.8 kg the weigh-ins show, pulled slightly toward no shift by the prior
        assertTrue(JourneyModel.fitLevel(base, obs) in 0.65..0.8)
    }
}
