package com.example.nourio

import com.example.nourio.energy.EstimatePoint
import com.example.nourio.energy.JourneySeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyPaceTest {
    private fun series(actual: List<Pair<Double, Double>>) = JourneySeries(
        -14, 73.0, (-14..0).map { EstimatePoint(it, 73.0 - 0.03 * (it + 14), true) }, actual, 0,
    )

    @Test
    fun paceFromWeighInsAndLog() {
        val s = series((-14..0 step 2).map { it.toDouble() to 73.0 })
        assertEquals(0.0, s.scaleKgPerWeek()!!, 1e-9)
        assertEquals(-0.21, s.estimatedKgPerWeek()!!, 1e-6)
        assertNull("too few weigh-ins", series(listOf(-3.0 to 73.0, 0.0 to 73.0)).scaleKgPerWeek())
    }

    @Test
    fun verdictNamesTheMissingCalories() {
        val v = verdict(0.0, -0.21, 12)!!
        assertTrue(v, v.contains("200 kcal"))
        assertTrue(verdict(-0.2, -0.25, 12)!!.contains("agree"))
        assertNull(verdict(null, -0.2, 3))
        // The filter's estimate is used when it agrees in direction
        assertTrue(verdict(0.0, -0.21, 12, gapKcal = 510.0)!!.contains("500 kcal"))
    }

    @Test
    fun targetDateOnlyWhenHeadingThere() {
        assertEquals("Not yet", etaText(73.0, 72.0, 0.1).first)
        assertEquals("Not yet", etaText(73.0, 72.0, 0.0).first)
        assertEquals("Reached", etaText(72.05, 72.0, null).first)
        assertTrue(etaText(73.0, 72.0, -0.5).second.contains("scale"))
    }
}
