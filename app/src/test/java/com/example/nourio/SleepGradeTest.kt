package com.example.nourio

import com.example.nourio.insights.SleepNight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepGradeTest {
    private fun night(h: Double, efficiency: Double? = null) =
        SleepNight(h, 23.0, 7.0, null, efficiency, null, null)

    @Test
    fun goalReachedIsAnA() {
        val s = Grading.sleepSubject(night(7.5, 92.0), 0.2, 7.0)
        assertEquals("A", s.grade)
        assertEquals("Well rested.", s.comment)
    }

    @Test
    fun anHourShortIsAC() = assertEquals("C", Grading.sleepSubject(night(6.0), null, 7.0).grade)

    @Test
    fun fiveHoursFails() = assertEquals("E", Grading.sleepSubject(night(5.0), null, 7.0).grade)

    @Test
    fun restlessNightLosesMarks() {
        val s = Grading.sleepSubject(night(7.5, 65.0), null, 7.0)
        assertTrue(s.score < 1.0)
        assertTrue(s.comment.startsWith("Restless"))
    }

    @Test
    fun irregularBedtimeIsMentioned() {
        val s = Grading.sleepSubject(night(7.5), 2.0, 7.0)
        assertTrue(s.comment.contains("off your usual"))
    }

    @Test
    fun sleepJoinsTheReportCard() {
        val goals = com.example.nourio.data.Goals(kcal = 2000.0, addedSugar = 50.0, fat = 70.0, satFat = 20.0, sodiumMg = 2300.0,
            carbs = 260.0, protein = 100.0, fiber = 30.0)
        val t = com.example.nourio.data.Totals(1980.0, 100.0, 60.0, 15.0, 250.0, 30.0, 30.0, 2000.0, addedSugar = 30.0)
        val r = Grading.report(DayInputs(t, 2000.0, goals, 75.0, 2000, 2000, 10_000, false, 22.0, sleep = night(5.0)))
        assertEquals("E", r.subjects.first { it.kind == SubjectKind.SLEEP }.grade)
        assertTrue(r.score!! < 1.0)
    }
}
