package com.example.nourio.reminders

import com.example.nourio.data.Goals
import com.example.nourio.data.Totals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionNudgesTest {
    private val goals = Goals(kcal = 2000.0, addedSugar = 30.0, fat = 70.0, satFat = 20.0, sodiumMg = 2300.0,
        carbs = 250.0, protein = 120.0, fiber = 30.0)

    private fun day(kcal: Double = 1100.0, protein: Double = 70.0, fiber: Double = 15.0, sodium: Double = 1200.0, sugar: Double = 10.0) =
        Totals(kcal = kcal, protein = protein, fiber = fiber, sodium = sodium, addedSugar = sugar, sugar = sugar)

    // ---- dinner planner

    @Test fun nothingLoggedOrStillFastingSaysNothing() = assertNull(NutritionNudges.dinnerPlan(day(kcal = 150.0), goals))

    @Test fun aBalancedDaySaysNothing() = assertNull(NutritionNudges.dinnerPlan(day(), goals))

    @Test fun sodiumNearTheLimitComesFirst() {
        val m = NutritionNudges.dinnerPlan(day(sodium = 2100.0, protein = 20.0), goals)!!
        assertEquals("Make dinner a low-salt one", m.title)
    }

    @Test fun addedSugarNearTheLimit() =
        assertEquals("Keep tonight's sweet fruit-based", NutritionNudges.dinnerPlan(day(sugar = 26.0), goals)!!.title)

    @Test fun lowProteinAsksForAPortionOneMealCanCarry() {
        val m = NutritionNudges.dinnerPlan(day(protein = 30.0), goals)!!
        assertEquals("Build dinner around protein", m.title)
        assertTrue(m.body, m.body.contains("about 40 g at dinner"))       // not the full 90 g still missing
    }

    @Test fun lowFibre() =
        assertEquals("Add vegetables or beans tonight", NutritionNudges.dinnerPlan(day(fiber = 8.0), goals)!!.title)

    @Test fun caloriesNearlyUsedNeverSaysSkipDinner() {
        val m = NutritionNudges.dinnerPlan(day(kcal = 1850.0), goals)!!
        assertEquals("About 150 kcal left for dinner", m.title)
        assertTrue(m.body.contains("If you're hungry, eat"))
        assertEquals("Today's calories are used up", NutritionNudges.dinnerPlan(day(kcal = 2300.0), goals)!!.title)
    }

    // ---- weekly review

    @Test fun tooFewLoggedDaysSkipsTheReview() =
        assertNull(NutritionNudges.weeklyReview(List(3) { day(kcal = 1900.0) } + List(4) { Totals() }, goals))

    @Test fun reviewNamesWhatWentWellAndTheBiggestGap() {
        val week = List(6) { day(kcal = 1950.0, protein = 125.0, fiber = 12.0, sodium = 2000.0, sugar = 20.0) } + Totals()
        val m = NutritionNudges.weeklyReview(week, goals)!!
        assertTrue(m.body, m.body.startsWith("6 of 7 days logged"))
        assertTrue(m.body, m.body.contains("Going well: protein averaged 125 g"))
        assertTrue(m.body, m.body.contains("Focus for next week: fibre averaged 12 of 30 g"))
    }

    @Test fun anAllRoundGoodWeekIsPraised() {
        val week = List(7) { day(kcal = 2000.0, protein = 120.0, fiber = 30.0, sodium = 2000.0, sugar = 20.0) }
        assertTrue(NutritionNudges.weeklyReview(week, goals)!!.body.contains("Keep it up"))
    }

    // ---- weigh-in

    private val dayMs = 86_400_000L

    @Test fun weighInOnlyForPeopleWhoWeighIn() {
        val now = 100 * dayMs
        assertNull(NutritionNudges.weighIn(listOf(80 * dayMs), now))                         // one reading ever
        assertNull(NutritionNudges.weighIn(listOf(80 * dayMs, 96 * dayMs), now))             // 4 days ago
        assertTrue(NutritionNudges.weighIn(listOf(80 * dayMs, 91 * dayMs), now)!!.body.contains("9 days ago"))
    }
}
