package com.example.nourio.fasting

import com.example.nourio.data.FoodItem
import com.example.nourio.data.Meal
import com.example.nourio.insights.CurrentGoals
import com.example.nourio.insights.DayMetrics
import com.example.nourio.insights.InsightEngine
import com.example.nourio.insights.Metric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random

class FastingTest {
    private val zone = ZoneOffset.UTC
    private val d1 = LocalDate.of(2026, 10, 10)

    private fun at(date: LocalDate, hour: Double) =
        date.atStartOfDay(zone).toInstant().toEpochMilli() + (hour * 3_600_000).toLong()

    private fun meal(ts: Long, kcal: Double) =
        Meal(ts, ts, "m", listOf(FoodItem("food", null, 100.0, kcal, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))

    @Test
    fun smallDrinksDontBreakAFast() {
        val meals = listOf(meal(at(d1, 19.0), 600.0), meal(at(d1.plusDays(1), 7.0), 5.0), meal(at(d1.plusDays(1), 11.0), 500.0))
        val times = Fasting.eatingTimes(meals)
        val day = Fasting.days(times, d1.plusDays(1), d1.plusDays(1), zone).single()
        assertEquals(16.0, day.fastH!!, 1e-9)             // 7 pm to 11 am; the 5 kcal coffee doesn't count
    }

    @Test
    fun eatingWindowAndLongGaps() {
        val times = listOf(at(d1, 8.0), at(d1, 13.0), at(d1, 20.0), at(d1.plusDays(2), 12.0))
        val days = Fasting.days(times, d1, d1.plusDays(2), zone)
        assertEquals(12.0, days[0].windowH!!, 1e-9)
        assertEquals(3, days[0].meals)
        assertNull(days[1].fastH)                          // no meal that day
        assertNull(days[2].fastH)                          // 40 h gap: a day without logging, not a fast
    }

    @Test
    fun statsCountGoalDaysAndStreak() {
        val times = listOf(
            at(d1, 20.0), at(d1.plusDays(1), 12.0),        // 16 h
            at(d1.plusDays(1), 20.0), at(d1.plusDays(2), 10.0),   // 14 h
            at(d1.plusDays(2), 19.0), at(d1.plusDays(3), 11.0),   // 16 h
            at(d1.plusDays(3), 19.0), at(d1.plusDays(4), 11.5),   // 16.5 h
        )
        val days = Fasting.days(times, d1.plusDays(1), d1.plusDays(4), zone)
        val s = Fasting.stats(days, 16.0, d1.plusDays(5))
        assertEquals(3, s.daysAtGoal)
        assertEquals(4, s.daysWithFast)
        assertEquals(2, s.streak)
        assertEquals(16.5, s.longestH!!, 1e-9)
    }

    @Test
    fun stagesFollowTheHours() {
        assertEquals("Digesting", Fasting.stage(2.0).title)
        assertEquals("Switching to fat", Fasting.stage(14.0).title)
        assertEquals("Long fast", Fasting.stage(30.0).title)
    }

    @Test
    fun impactComparesGoalDaysWithOthers() {
        val days = (0 until 10).map { i ->
            val long = i % 2 == 0
            DayMetrics(d1.minusDays(i.toLong()), mapOf(
                Metric.FAST to if (long) 16.5 else 12.0,
                Metric.KCAL to if (long) 1800.0 else 2100.0,
                Metric.WEIGHT_CHANGE to if (long) -0.1 else 0.05,
            ))
        }
        val r = Fasting.impact(days, 16.0)
        assertEquals(5, r.atGoal.days)
        assertEquals(1800.0, r.atGoal.kcal!!, 1e-9)
        assertEquals(0.05, r.below.weightChangeKg!!, 1e-9)
    }

    @Test
    fun engineFindsLongerFastsLeadingToLessFood() {
        val rnd = Random(7)
        val today = LocalDate.of(2026, 10, 14)
        val days = (0 until 30).map { i ->
            val fast = 11.0 + rnd.nextDouble() * 6
            DayMetrics(today.minusDays((30 - i).toLong()), mapOf(
                Metric.FAST to fast,
                Metric.KCAL to 2600 - fast * 50 + rnd.nextDouble() * 150,
            ))
        }
        val goals = CurrentGoals(2300.0, 25.0, 25.0, 100.0, 2000.0, 10_000.0, 7.0, 75.0, fastH = 12.0)
        val a = InsightEngine.analyse(days, goals, today)
        val link = a.relationships.firstOrNull { it.id == "fast-kcal" }
        assertNotNull(link)
        assertTrue(link!!.summary.contains("less"))
        val tip = a.tips.first { it.id == "fast-kcal" }
        assertTrue(tip.change!!.to > 12.0)
    }
}
