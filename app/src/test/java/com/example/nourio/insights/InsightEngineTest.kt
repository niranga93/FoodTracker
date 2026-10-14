package com.example.nourio.insights

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class InsightEngineTest {
    private val today = LocalDate.of(2026, 10, 14)
    private val goals = CurrentGoals(sodiumMg = 2300.0, sugar = 50.0, fiber = 25.0, protein = 100.0,
        waterMl = 2000.0, steps = 10_000.0, sleepH = 7.0, weightKg = 75.0)

    private fun days(n: Int, make: (Int, Random) -> Map<Metric, Double>): List<DayMetrics> {
        val rnd = Random(42)
        return (0 until n).map { i -> DayMetrics(today.minusDays((n - i).toLong()), make(i, rnd)) }
    }

    @Test
    fun ranksShareTies() {
        assertArrayEquals(doubleArrayOf(1.0, 2.5, 2.5, 4.0), InsightEngine.ranks(listOf(1.0, 5.0, 5.0, 9.0)), 1e-9)
    }

    @Test
    fun findsShortSleepLeadingToOvereating() {
        // Less sleep -> more calories, with some noise
        val data = days(30) { _, r ->
            val sleep = 5.0 + r.nextDouble() * 3.5
            mapOf(Metric.SLEEP to sleep, Metric.KCAL to 2600 - sleep * 120 + r.nextDouble() * 150)
        }
        val a = InsightEngine.analyse(data, goals, today)
        val rel = a.relationships.firstOrNull { it.id == "sleep-kcal" }
        assertNotNull(rel)
        assertTrue(rel!!.rho < -0.5)
        assertTrue(rel.summary.contains("kcal more"))
        assertTrue(a.tips.any { it.id == "sleep-kcal" })
    }

    @Test
    fun noiseFindsNothing() {
        val data = days(40) { _, r ->
            mapOf(
                Metric.SLEEP to 5 + r.nextDouble() * 4, Metric.KCAL to 1500 + r.nextDouble() * 1000,
                Metric.SODIUM to 1500 + r.nextDouble() * 2000, Metric.STEPS to 3000 + r.nextDouble() * 9000,
                Metric.SUGAR to 20 + r.nextDouble() * 60
            )
        }
        assertEquals(emptyList<Relationship>(), InsightEngine.analyse(data, goals, today).relationships)
    }

    @Test
    fun linkPointingTheUnexpectedWayIsIgnored() {
        // More sleep -> MORE calories: not a plausible cause, so it isn't reported
        val data = days(30) { _, r ->
            val sleep = 5.0 + r.nextDouble() * 3.5
            mapOf(Metric.SLEEP to sleep, Metric.KCAL to 1200 + sleep * 150)
        }
        assertTrue(InsightEngine.analyse(data, goals, today).relationships.none { it.id == "sleep-kcal" })
    }

    @Test
    fun hittingStepGoalDailySuggestsAHigherGoal() {
        val data = days(10) { _, _ -> mapOf(Metric.STEPS to 11_500.0) }
        val tip = InsightEngine.analyse(data, goals, today).tips.first { it.change?.field == GoalField.STEPS }
        assertEquals(11_000.0, tip.change!!.to, 0.0)
    }

    @Test
    fun easierStepGoalIsAlwaysLower() {
        // Close to the goal but never reaching it: the suggestion must still be below 10,000
        val data = days(10) { _, _ -> mapOf(Metric.STEPS to 9_600.0) }
        val change = InsightEngine.analyse(data, goals, today).tips.first { it.change?.field == GoalField.STEPS }.change!!
        assertTrue(change.to < change.from)
    }

    @Test
    fun risingSodiumIsATrendWithAGoalChange() {
        val data = days(28) { i, _ -> mapOf(Metric.SODIUM to if (i < 14) 2000.0 else 2800.0) }
        val a = InsightEngine.analyse(data, goals, today)
        assertTrue(a.trends.any { it.metric == Metric.SODIUM && it.good == false })
        assertEquals(2000.0, a.tips.first { it.change?.field == GoalField.SODIUM }.change!!.to, 0.0)
    }
}
