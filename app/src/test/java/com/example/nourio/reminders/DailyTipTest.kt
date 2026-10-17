package com.example.nourio.reminders

import com.example.nourio.data.Goals
import com.example.nourio.data.Totals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyTipTest {
    private val goals = Goals(kcal = 2000.0, addedSugar = 30.0, fat = 70.0, satFat = 20.0, sodiumMg = 2300.0,
        carbs = 250.0, protein = 120.0, fiber = 30.0)

    private fun day(kcal: Double = 2000.0, protein: Double = 120.0, fiber: Double = 30.0, sodium: Double = 2000.0,
                    sugar: Double = 20.0, satFat: Double = 15.0, late: Double = 200.0, water: Int = 2000) =
        TipDay(Totals(kcal = kcal, protein = protein, fiber = fiber, sodium = sodium, addedSugar = sugar, sugar = sugar, satFat = satFat), late, water)

    private val good = List(7) { day() }

    @Test fun theBiggestGapIsTheTip() {
        val week = List(7) { day(protein = 60.0) }                 // half the protein goal
        val tip = DailyTip.pick(week, goals, 2000, dayOfYear = 0)
        assertEquals("week_protein", tip.id)
        assertTrue(tip.body, tip.body.contains("60 of 120 g") && tip.body.contains("strength training"))
    }

    @Test fun severalGapsRotateDayToDayWithoutRepeating() {
        val week = List(7) { day(protein = 60.0, fiber = 10.0, sodium = 3200.0) }
        val ids = (0..2).map { DailyTip.pick(week, goals, 2000, dayOfYear = it).id }.toSet()
        assertEquals(setOf("week_protein", "week_fibre", "week_sodium"), ids)
        val first = DailyTip.pick(week, goals, 2000, dayOfYear = 0)
        assertNotEquals(first.id, DailyTip.pick(week, goals, 2000, dayOfYear = 0, lastId = first.id).id)
    }

    @Test fun lateEatingIsNoticed() {
        val week = List(7) { day(late = 900.0) }                   // 45% of the day after 8 pm
        assertEquals("week_late", DailyTip.pick(week, goals, 2000, 0).id)
    }

    @Test fun bigSwingsAreNoticed() {
        val week = List(7) { i -> day(kcal = if (i % 2 == 0) 1200.0 else 2900.0, protein = 120.0 * (if (i % 2 == 0) 0.6 else 1.45),
            fiber = 30.0, late = 100.0) }
        assertTrue(DailyTip.pick(week, goals, 2000, 0).let { it.id == "week_swings" || it.id == "week_protein" })
    }

    @Test fun waterOnlyCountsDaysItWasLogged() {
        assertNotEquals("week_water", DailyTip.pick(List(7) { day(water = 0) }, goals, 2000, 0).id)
        assertEquals("week_water", DailyTip.pick(List(7) { day(water = 900) }, goals, 2000, 0).id)
    }

    @Test fun aGoodWeekGetsAHabitToBuildOnAndItRotates() {
        val a = DailyTip.pick(good, goals, 2000, 0)
        val b = DailyTip.pick(good, goals, 2000, 1)
        assertTrue(a.id.startsWith("tip_") && b.id.startsWith("tip_"))
        assertNotEquals(a.id, b.id)
        assertTrue(a.title.contains("on track"))
    }

    @Test fun anEmptyWeekAsksForAFullerLog() {
        val tip = DailyTip.pick(List(7) { TipDay(Totals()) }, goals, 2000, 3)
        assertEquals("week_log", tip.id)
        assertTrue(tip.body.startsWith("No complete days"))
    }
}
