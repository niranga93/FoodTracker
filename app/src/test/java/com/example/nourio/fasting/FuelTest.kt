package com.example.nourio.fasting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelTest {
    private val h = 3_600_000L
    private val t0 = 1_800_000_000_000L
    private fun meal(atH: Double, kcal: Double, carbs: Double = 60.0, fat: Double = 20.0, fiber: Double = 0.0) =
        MealFuel(t0 + (atH * h).toLong(), kcal, carbs, fat, fiber)
    private fun hoursAfter(t: Long, from: Long = t0) = (t - from).toDouble() / h

    /** A normal day: breakfast, lunch and dinner, 250 g carbs in all; dinner at t0. */
    private val day = listOf(
        meal(-12.0, 500.0, carbs = 70.0, fat = 15.0),
        meal(-6.0, 700.0, carbs = 90.0, fat = 25.0),
        meal(0.0, 700.0, carbs = 90.0, fat = 25.0),
    )

    @Test fun biggerFattierHigherFibreMealsTakeLonger() {
        assertTrue(Fuel.absorbHours(MealFuel(0, 900.0, 80.0, 45.0)) > Fuel.absorbHours(MealFuel(0, 400.0, 50.0, 10.0)))
        assertTrue(Fuel.absorbHours(MealFuel(0, 500.0, 60.0, 25.0)) > Fuel.absorbHours(MealFuel(0, 500.0, 60.0, 5.0)))
        assertTrue(Fuel.absorbHours(MealFuel(0, 500.0, 60.0, 15.0, fiber = 15.0)) > Fuel.absorbHours(MealFuel(0, 500.0, 60.0, 15.0)))
        assertEquals(8.0, Fuel.absorbHours(MealFuel(0, 2000.0, 200.0, 100.0)), 1e-9)
        assertEquals(1.5, Fuel.absorbHours(MealFuel(0, 60.0, 10.0, 0.0)), 1e-9)
    }

    @Test fun aSnackWaitsBehindTheMealStillInTheStomach() {
        val lunch = meal(0.0, 800.0, fat = 30.0)
        val snack = meal(1.0, 200.0, fat = 5.0)
        val both = Fuel.state(listOf(lunch, snack), t0 + 2 * h)!!
        val lunchAlone = Fuel.state(listOf(lunch), t0 + 2 * h)!!
        val snackAlone = Fuel.state(listOf(snack), t0 + 2 * h)!!
        // Before: the snack ran alongside, so "used up" was simply the later of the two
        assertTrue(both.absorbedAt > lunchAlone.absorbedAt)
        assertTrue(both.absorbedAt > snackAlone.absorbedAt)
        // The queue: the snack's emptying starts when lunch's ends
        val q = Fuel.queue(listOf(lunch, snack))
        assertEquals(q[0].second, q[1].first)
    }

    @Test fun aNormalDayFollowsThePublishedTimeline() {
        val s = Fuel.state(day, t0 + 2 * h)!!
        assertEquals(FuelPhase.FED, s.phase)
        // Fat takes over about 12-16 h after dinner, ketones about 18-26 h
        assertTrue("fat at ${hoursAfter(s.fatAt)} h", hoursAfter(s.fatAt) in 12.0..16.0)
        assertTrue("ketones at ${hoursAfter(s.ketosisAt)} h", hoursAfter(s.ketosisAt) in 18.0..26.0)
        assertEquals(FuelPhase.GLYCOGEN, Fuel.state(day, t0 + 8 * h)!!.phase)
        assertEquals(FuelPhase.FAT, Fuel.state(day, t0 + 17 * h)!!.phase)
        assertEquals(FuelPhase.KETOSIS, Fuel.state(day, t0 + 27 * h)!!.phase)
    }

    @Test fun aHighCarbDayDelaysTheSwitchAndALowCarbDayBringsItForward() {
        val low = Fuel.state(listOf(meal(0.0, 600.0, carbs = 30.0, fat = 40.0)), t0 + h)!!
        val high = Fuel.state(listOf(meal(-10.0, 900.0, carbs = 200.0), meal(0.0, 600.0, carbs = 150.0)), t0 + h)!!
        assertTrue(hoursAfter(high.fatAt) > hoursAfter(low.fatAt) + 4)
        assertEquals(low.absorbedAt, low.fatAt)          // little glycogen to use: fat right after digestion
    }

    @Test fun aHeavierBodyDrainsGlycogenSooner() {
        val light = Fuel.state(day, t0 + h, weightKg = 55.0)!!
        val heavy = Fuel.state(day, t0 + h, weightKg = 100.0)!!
        assertTrue(heavy.fatAt < light.fatAt)
        assertEquals(light.absorbedAt, heavy.absorbedAt)  // digestion doesn't depend on weight
    }

    @Test fun glycogenFillsWithCarbsUpToTheLiversCapacity() {
        assertEquals(30.0, Fuel.glycogenFed(0.0), 1e-9)
        assertEquals(92.5, Fuel.glycogenFed(250.0), 1e-9)
        assertEquals(100.0, Fuel.glycogenFed(600.0), 1e-9)
        val s = Fuel.state(day, t0 + 10 * h)!!
        assertTrue(s.glycogenG < 92.5 && s.glycogenG > 65.0)
    }

    @Test fun theMixAddsUpAndStoredGlucoseLeadsUntilFatTakesOver() {
        val early = Fuel.state(day, t0 + h)!!
        val glyco = Fuel.state(day, t0 + 8 * h)!!
        val fat = Fuel.state(day, t0 + 17 * h)!!
        listOf(early, glyco, fat).forEach { assertEquals(1.0, it.fromFood + it.fromGlycogen + it.fromFat, 1e-9) }
        assertTrue(early.fromFood > 0.5)
        assertTrue(glyco.fromGlycogen > glyco.fromFat)    // the label matches the mix
        assertTrue(fat.fromFat > fat.fromGlycogen)
    }

    @Test fun foodReachesTheBloodAsTheStomachEmpties() {
        val m = meal(0.0, 500.0, fat = 0.0)          // empties 0.33 h to 3.11 h; reaches the blood 45 min later
        val mid = t0 + ((0.33 + 0.75 + 500.0 / 180.0 / 2) * h).toLong()
        assertEquals(250.0, Fuel.state(listOf(m), mid)!!.stillAbsorbingKcal, 2.0)
        assertEquals(500.0, Fuel.state(listOf(m), t0 + h)!!.stillAbsorbingKcal, 1e-9)   // nothing absorbed yet
    }

    // ---- activity from Health Connect

    /** A 400 kcal workout in the hour starting [atH] hours after dinner. */
    private fun workout(atH: Int, kcal: Double = 400.0) = listOf((t0 + atH * h) to kcal)

    @Test fun aWorkoutAfterDigestionBringsFatForward() {
        val rest = Fuel.state(day, t0 + 9 * h)!!
        val moved = Fuel.state(day, t0 + 9 * h, activity = workout(7))!!
        // 400 kcal uses about 12.5 g of liver glycogen, close to 4 hours of rest at 70 kg
        assertEquals(400.0 * Fuel.LIVER_G_PER_ACTIVE_KCAL, rest.glycogenG - moved.glycogenG, 0.01)
        val earlier = hoursAfter(moved.fatAt, rest.fatAt)
        assertTrue("fat ${-earlier} h earlier", earlier in -4.5..-3.0)
        assertEquals(400.0, moved.activeKcal, 1e-9)
    }

    @Test fun activityWhileDigestingDoesntCount() {
        val s = Fuel.state(day, t0 + 9 * h, activity = workout(1))!!       // still digesting dinner at 1 h
        assertEquals(0.0, s.activeKcal, 1e-9)
        assertEquals(Fuel.state(day, t0 + 9 * h)!!.fatAt, s.fatAt)
    }

    @Test fun futureActivityIsntKnownYet() {
        // At 7.5 h, only half of the 7-8 h workout has happened
        val s = Fuel.state(day, t0 + (7.5 * h).toLong(), activity = workout(7))!!
        assertEquals(200.0, s.activeKcal, 1e-6)
    }

    @Test fun partHoursCountProRata() {
        assertEquals(150.0, Fuel.activeBetween(listOf(t0 to 300.0), t0 + h / 2, t0 + 2 * h), 1e-9)
        assertEquals(0.0, Fuel.activeBetween(listOf(t0 to 300.0), t0 + h, t0 + 2 * h), 1e-9)
    }

    @Test fun coffeeDoesntBreakTheFast() {
        assertNull(Fuel.state(listOf(meal(0.0, 20.0)), t0 + h))
        val s = Fuel.state(listOf(meal(0.0, 600.0), meal(8.0, 15.0)), t0 + 9 * h)!!
        assertEquals(t0, s.lastMeal)
    }
}
