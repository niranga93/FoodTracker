package com.example.nourio

import com.example.nourio.data.Goals
import com.example.nourio.data.Totals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradingTest {
    private val goals = Goals(kcal = 2000.0, sugar = 50.0, fat = 70.0, satFat = 20.0, sodiumMg = 2300.0,
        carbs = 260.0, protein = 100.0, fiber = 30.0)

    private fun totals(kcal: Double, protein: Double = 100.0, fiber: Double = 30.0, sugar: Double = 40.0,
                       fat: Double = 60.0, satFat: Double = 15.0, sodium: Double = 2000.0) =
        Totals(kcal, protein, fat, satFat, 250.0, sugar, fiber, sodium)

    private fun inputs(t: Totals, today: Boolean = false, hour: Double = 22.0, water: Int = 2000, steps: Long? = 10_000) =
        DayInputs(t, 2000.0, goals, 75.0, water, 2000, steps, today, hour)

    @Test
    fun onTargetFinishedDayIsAnA() {
        val r = Grading.report(inputs(totals(1980.0)))
        assertEquals("A", r.grade)
        assertTrue(r.subjects.all { it.grade == "A" })
    }

    @Test
    fun nothingLoggedIsNotGraded() {
        val r = Grading.report(inputs(totals(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))
        assertNull(r.grade)
    }

    @Test
    fun earlyMorningMinimumsAreNotPunished() {
        // 8 am: a 300 kcal breakfast with 15 g protein is on track, not a failing protein grade
        val r = Grading.report(inputs(totals(300.0, protein = 15.0, fiber = 5.0), today = true, hour = 8.0, water = 300, steps = 1500))
        assertEquals("A", r.subjects.first { it.kind == SubjectKind.PROTEIN }.grade)
        assertEquals("A", r.subjects.first { it.kind == SubjectKind.CALORIES }.grade)
    }

    @Test
    fun goingOverLimitsLowersGrades() {
        val r = Grading.report(inputs(totals(2400.0, sugar = 75.0)))
        assertEquals("D", r.subjects.first { it.kind == SubjectKind.CALORIES }.grade)   // 20% over
        assertEquals("E", r.subjects.first { it.kind == SubjectKind.SUGAR }.grade)      // 50% over
        assertTrue(Grading.note(r, false).contains("Work on"))
    }
}
