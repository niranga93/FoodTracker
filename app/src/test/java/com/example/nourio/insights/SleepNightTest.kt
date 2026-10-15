package com.example.nourio.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random

class SleepNightTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, day, h, m).toInstant(ZoneOffset.UTC)

    @Test
    fun noStagesUsesTimeInBed() {
        val night = SleepNight.of(listOf(SleepSession(at(14, 23, 30), at(15, 7, 0))), zone)!!
        assertEquals(7.5, night.asleepH, 1e-9)
        assertEquals(23.5, night.bedtime, 1e-9)
        assertEquals(7.0, night.wakeTime, 1e-9)
        assertNull(night.awakeMin)
        assertNull(night.deepMin)
    }

    @Test
    fun bedtimeAfterMidnightRunsPast24() {
        val night = SleepNight.of(listOf(SleepSession(at(15, 0, 30), at(15, 7, 30))), zone)!!
        assertEquals(24.5, night.bedtime, 1e-9)
    }

    @Test
    fun stagesGiveAwakeTimeEfficiencyAndDepth() {
        val stages = listOf(
            SleepStage(at(14, 23), at(15, 0), SleepStage.Type.AWAKE),     // 60 min lying awake
            SleepStage(at(15, 0), at(15, 2), SleepStage.Type.DEEP),
            SleepStage(at(15, 2), at(15, 5), SleepStage.Type.LIGHT),
            SleepStage(at(15, 5), at(15, 6), SleepStage.Type.REM),
            SleepStage(at(15, 6), at(15, 7), SleepStage.Type.LIGHT),
        )
        val night = SleepNight.of(listOf(SleepSession(at(14, 23), at(15, 7), stages)), zone)!!
        assertEquals(7.0, night.asleepH, 1e-9)          // 8 h in bed minus 1 h awake
        assertEquals(60.0, night.awakeMin!!, 1e-9)
        assertEquals(87.5, night.efficiency!!, 1e-9)
        assertEquals(120.0, night.deepMin!!, 1e-9)
        assertEquals(60.0, night.remMin!!, 1e-9)
    }

    @Test
    fun napDoesNotMoveBedtime() {
        val night = SleepNight.of(
            listOf(SleepSession(at(14, 23), at(15, 6)), SleepSession(at(15, 14), at(15, 14, 40))), zone
        )!!
        assertEquals(23.0, night.bedtime, 1e-9)
        assertEquals(6.0, night.wakeTime, 1e-9)
        assertEquals(7.0 + 40 / 60.0, night.asleepH, 1e-9)
    }

    @Test
    fun tooShortIsNotANight() = assertNull(SleepNight.of(listOf(SleepSession(at(15, 14), at(15, 14, 30))), zone))

    @Test
    fun bedtimeShiftIsDistanceFromTheUsualTime() {
        val d = LocalDate.of(2026, 10, 10)
        val bedtimes = mapOf(d to 23.0, d.plusDays(1) to 23.0, d.plusDays(2) to 23.5, d.plusDays(3) to 25.0)
        val shifts = SleepNight.bedtimeShifts(bedtimes)
        assertEquals(2.0, shifts[d.plusDays(3)]!!, 1e-9)     // median 23.0, went to bed at 1 am
        assertNull(shifts[d.plusDays(2)])                    // only 2 nights before it
    }

    // ------------------------------------------------------------ the engine on sleep data

    private val today = LocalDate.of(2026, 10, 14)
    private val goals = CurrentGoals(sodiumMg = 2300.0, sugar = 50.0, fiber = 25.0, protein = 100.0,
        waterMl = 2000.0, steps = 10_000.0, sleepH = 7.0, weightKg = 75.0)

    private fun days(n: Int, make: (Random) -> Map<Metric, Double>): List<DayMetrics> {
        val rnd = Random(7)
        return (0 until n).map { i -> DayMetrics(today.minusDays((n - i).toLong()), make(rnd)) }
    }

    @Test
    fun lateBedtimesLeadToBiggerEatingDays() {
        val data = days(30) { r ->
            val bed = 22.0 + r.nextDouble() * 3
            mapOf(Metric.BEDTIME to bed, Metric.KCAL to 1500 + (bed - 22) * 250 + r.nextDouble() * 150)
        }
        val a = InsightEngine.analyse(data, goals, today)
        val rel = a.relationships.firstOrNull { it.id == "bedtime-kcal" }
        assertNotNull(rel)
        assertTrue(rel!!.summary.contains("kcal more"))
        assertTrue(a.tips.any { it.id == "bedtime-earlier" })
    }

    @Test
    fun lateMealsLeadToRestlessNights() {
        // Last meal on one day, minutes awake recorded the next morning
        val data = days(30) { r ->
            val last = 18.0 + r.nextDouble() * 4
            mapOf(Metric.LAST_MEAL to last, Metric.AWAKE to 10 + (last - 18) * 15 + r.nextDouble() * 10)
        }
        // Shift AWAKE back so it sits on the morning after each meal
        val shifted = data.mapIndexed { i, d ->
            val v = d.values.toMutableMap()
            v[Metric.AWAKE] = data.getOrNull(i - 1)?.let { 10 + (it[Metric.LAST_MEAL]!! - 18) * 15 } ?: 20.0
            DayMetrics(d.date, v)
        }
        assertTrue(InsightEngine.analyse(shifted, goals, today).relationships.any { it.id == "late-awake" })
    }

    @Test
    fun irregularBedtimesGiveASteadyBedtimeTip() {
        val data = days(10) { _ -> mapOf(Metric.BEDTIME_SHIFT to 1.6) }
        assertTrue(InsightEngine.analyse(data, goals, today).tips.any { it.id == "steady-bedtime" })
    }
}
