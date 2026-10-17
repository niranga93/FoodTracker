package com.example.nourio.energy

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * How far the calibrated maintenance calories land from the truth for simulated people: a real
 * maintenance the formula gets 10% wrong, 28 days of logged food, and weigh-ins with day-to-day water
 * swings, the odd salty-dinner spike, and some days weighed more than once.
 */
class CalibrationAccuracyTest {
    private fun Random.gauss(): Double {
        val u1 = nextDouble().coerceAtLeast(1e-12); val u2 = nextDouble()
        return sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }

    /** Average error in kcal/day of the measured maintenance, over [n] people. */
    private fun meanError(n: Int = 300): Double = (1..n).map { s ->
        val r = Random(s)
        val trueMaintenance = 2300.0
        val modelBurn = trueMaintenance / (if (s % 2 == 0) 1.1 else 0.9)     // the formula is 10% off
        val eaten = 2300.0 - 250 + r.gauss() * 100                             // a modest deficit on average
        val days = (1..28).map { i -> DayEnergy(-i, eaten + r.gauss() * 300, modelBurn, BurnSource.BMR_ONLY, null, true) }
        val slope = (eaten - trueMaintenance) / EnergyModel.KCAL_PER_KG          // kg per day
        val weights = (0 until 28).filter { r.nextDouble() < 0.6 }.flatMap { d ->
            val day = -27.0 + d
            val base = 80 + slope * (day + 27) + r.gauss() * 0.4                  // water swings
            val spike = if (r.nextDouble() < 0.12) 1.2 + r.nextDouble() else 0.0  // salty dinner, big meal
            val times = if (r.nextDouble() < 0.25) 3 else 1                       // weighed again after meals
            (0 until times).map { k -> (day + k * 0.3) to base + spike + k * 0.6 }
        }
        val cal = EnergyModel.calibrate(days, weights) ?: return@map Double.NaN
        abs(cal.measuredMaintenance - trueMaintenance)
    }.filter { !it.isNaN() }.average()

    @Test
    fun report() {
        val err = meanError()
        println("CALIBRATION mean error in maintenance: ${err.toInt()} kcal/day")
        // Was 175 kcal/day with a least-squares line through every weigh-in
        assertTrue("error $err", err < 140)
    }
}
