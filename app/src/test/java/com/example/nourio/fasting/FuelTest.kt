package com.example.nourio.fasting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelTest {
    private val h = 3_600_000L
    private val t0 = 1_800_000_000_000L
    private fun meal(atH: Double, kcal: Double, carbs: Double = 60.0, fat: Double = 20.0) =
        MealFuel(t0 + (atH * h).toLong(), kcal, carbs, fat)

    @Test
    fun biggerFattierMealsTakeLonger() {
        assertEquals(2.5, Fuel.absorbHours(MealFuel(0, 100.0, 20.0, 2.0)), 1e-9)
        assertTrue(Fuel.absorbHours(MealFuel(0, 900.0, 80.0, 45.0)) > Fuel.absorbHours(MealFuel(0, 400.0, 50.0, 10.0)))
        assertEquals(6.0, Fuel.absorbHours(MealFuel(0, 2000.0, 200.0, 100.0)), 1e-9)
    }

    @Test
    fun phasesFollowTheClock() {
        val meals = listOf(meal(0.0, 600.0, carbs = 70.0, fat = 25.0))   // absorbs in 2 + 2.4 + 0.5 = 4.9 h
        assertEquals(FuelPhase.FED, Fuel.state(meals, t0 + 2 * h)!!.phase)
        assertEquals(FuelPhase.GLYCOGEN, Fuel.state(meals, t0 + 6 * h)!!.phase)
        assertEquals(FuelPhase.FAT, Fuel.state(meals, t0 + 12 * h)!!.phase)     // 70 g carbs: fat at 10.7 h
        assertEquals(FuelPhase.KETOSIS, Fuel.state(meals, t0 + 18 * h)!!.phase)
    }

    @Test
    fun aHighCarbDayDelaysTheSwitchToFat() {
        val low = Fuel.state(listOf(meal(0.0, 500.0, carbs = 40.0)), t0 + h)!!
        val high = Fuel.state(listOf(meal(-10.0, 900.0, carbs = 200.0), meal(0.0, 500.0, carbs = 150.0)), t0 + h)!!
        assertTrue(high.fatAt - t0 > low.fatAt - t0)
    }

    @Test
    fun theMixAddsUpAndShiftsToFat() {
        val meals = listOf(meal(0.0, 600.0))
        val early = Fuel.state(meals, t0 + h)!!
        val late = Fuel.state(meals, t0 + 14 * h)!!
        listOf(early, late).forEach { assertEquals(1.0, it.fromFood + it.fromGlycogen + it.fromFat, 1e-9) }
        assertTrue(early.fromFood > 0.5)
        assertEquals(0.0, late.fromFood, 1e-9)
        assertTrue(late.fromFat > late.fromGlycogen)
    }

    @Test
    fun halfwayThroughAMealHalfIsStillAbsorbing() {
        val m = meal(0.0, 500.0, fat = 0.0)              // 2 + 2 = 4 h
        assertEquals(250.0, Fuel.state(listOf(m), t0 + 2 * h)!!.stillAbsorbingKcal, 1.0)
    }

    @Test
    fun coffeeDoesntBreakTheFast() {
        assertNull(Fuel.state(listOf(meal(0.0, 20.0)), t0 + h))
        val s = Fuel.state(listOf(meal(0.0, 600.0), meal(8.0, 15.0)), t0 + 9 * h)!!
        assertEquals(t0, s.lastMeal)
    }
}
