package com.example.nourio.energy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastRangeTest {
    private fun days(vararg balances: Double) = balances.mapIndexed { i, b ->
        DayEnergy(-(i + 1), 2000.0 + b, 2000.0, BurnSource.BMR_ONLY, null, true)
    }

    @Test
    fun steadyDaysGiveANarrowerRangeThanErraticOnes() {
        val steady = EnergyModel.predict(days(-500.0, -480.0, -520.0, -510.0, -490.0, -500.0, -500.0))!!
        val erratic = EnergyModel.predict(days(-1500.0, 600.0, -1200.0, 400.0, -900.0, 200.0, -1100.0))!!
        assertEquals(steady.change30Kg, erratic.change30Kg, 0.05)            // same average
        assertTrue((erratic.high30Kg - erratic.low30Kg) > 1.5 * (steady.high30Kg - steady.low30Kg))
    }

    @Test
    fun aRangeExistsEvenAtMaintenance() {
        // The old ±30% of the change collapsed to nothing when the change was zero
        val p = EnergyModel.predict(days(0.0, 0.0, 0.0, 0.0, 0.0))!!
        assertTrue(p.high30Kg - p.low30Kg > 0.5)
    }

    @Test
    fun calibratedBurnNarrowsTheRange() {
        val d = days(-400.0, -300.0, -500.0, -450.0, -350.0, -420.0, -380.0)
        val raw = EnergyModel.predict(d, 0.10)!!; val cal = EnergyModel.predict(d, 0.05)!!
        assertTrue(cal.high30Kg - cal.low30Kg < raw.high30Kg - raw.low30Kg)
    }

    @Test
    fun theilSenIgnoresAWaterSpike() {
        val pts = (0..14).map { it.toDouble() to 80.0 - 0.05 * it }.toMutableList()
        pts[7] = 7.0 to 82.0                                                   // salty dinner
        assertEquals(-0.05, EnergyModel.theilSen(pts)!!, 0.005)
    }
}
