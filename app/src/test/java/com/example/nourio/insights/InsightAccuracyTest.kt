package com.example.nourio.insights

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Measures the Insights engine on simulated people: how often it reports a link that isn't there
 * (false positives) and how often it finds one that is (power). 60 days, every measure the engine
 * looks at, realistic means and spreads.
 */
class InsightAccuracyTest {
    private val today = LocalDate.of(2026, 10, 14)
    private val goals = CurrentGoals(sodiumMg = 2300.0, sugar = 50.0, fiber = 25.0, protein = 100.0,
        waterMl = 2000.0, steps = 8000.0, sleepH = 7.0, weightKg = 75.0)

    // mean, daily spread
    private val spec = mapOf(
        Metric.KCAL to (2100.0 to 350.0), Metric.PROTEIN to (90.0 to 25.0), Metric.FIBER to (22.0 to 7.0),
        Metric.SUGAR to (35.0 to 15.0), Metric.SAT_FAT to (22.0 to 7.0), Metric.SODIUM to (2400.0 to 600.0),
        Metric.WATER to (1800.0 to 450.0), Metric.LAST_MEAL to (19.5 to 1.3), Metric.FAST to (13.0 to 1.5),
        Metric.EATING_WINDOW to (10.0 to 1.5), Metric.STEPS to (8000.0 to 2800.0), Metric.SLEEP to (7.0 to 0.8),
        Metric.BEDTIME to (23.3 to 0.8), Metric.WAKE_TIME to (6.8 to 0.6), Metric.AWAKE to (35.0 to 15.0),
        Metric.SLEEP_EFFICIENCY to (89.0 to 4.0), Metric.DEEP_SLEEP to (70.0 to 18.0), Metric.REM_SLEEP to (95.0 to 22.0),
        Metric.BEDTIME_SHIFT to (0.6 to 0.4), Metric.RESTING_HR to (60.0 to 3.0), Metric.AVG_HR to (72.0 to 5.0),
        Metric.SYSTOLIC to (122.0 to 6.0), Metric.WEIGHT_CHANGE to (0.0 to 0.35),
    )

    /**
     * One person with no real links. [ar] makes each day follow the one before (habits carry over);
     * [drift] adds a slow shared swing over the weeks (holidays, seasons), which moves many measures
     * together without one causing another.
     */
    private fun person(seed: Int, ar: Double, drift: Double): List<DayMetrics> {
        val r = Random(seed)
        val prev = HashMap<Metric, Double>()
        val phase = r.nextDouble() * 6.28
        return (0 until 60).map { i ->
            val season = kotlin.math.sin(phase + i / 60.0 * 6.28)
            val v = spec.mapValues { (m, ms) ->
                val z = r.nextGaussian()
                val e = ar * (prev[m] ?: 0.0) + sqrt(1 - ar * ar) * z
                prev[m] = e
                // each measure picks up the shared swing with its own sign
                val sign = if ((m.ordinal + seed) % 2 == 0) 1 else -1
                ms.first + ms.second * (e + drift * sign * season)
            }
            DayMetrics(today.minusDays(60L - i), v)
        }
    }

    private fun Random.nextGaussian(): Double {
        val u1 = nextDouble().coerceAtLeast(1e-12); val u2 = nextDouble()
        return sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }

    /** Share of simulated people for whom at least one link (or trend) is reported although none exists. */
    private fun falseRate(ar: Double, drift: Double, trends: Boolean = false, n: Int = 200): Double =
        (1..n).count { s ->
            val a = InsightEngine.analyse(person(s, ar, drift), goals, today)
            if (trends) a.trends.isNotEmpty() else a.relationships.isNotEmpty()
        }.toDouble() / n

    /** Share of people with a true link (less sleep, more calories) for whom it's found. */
    private fun power(kcalPerHour: Double, n: Int = 100): Double = (1..n).count { s ->
        val days = person(s, 0.3, 0.0).map { d ->
            val sleep = d[Metric.SLEEP]!!
            // 180 kcal per hour of sleep lost is a moderate link (rho about 0.35); 300 is a strong one (about 0.55)
            DayMetrics(d.date, d.values + (Metric.KCAL to d[Metric.KCAL]!! - (sleep - 7.0) * kcalPerHour))
        }
        InsightEngine.analyse(days, goals, today).relationships.any { it.id == "sleep-kcal" }
    }.toDouble() / n

    /** Share of people whose sleep really dropped by [hours] over the last two weeks who are shown that trend. */
    private fun trendPower(hours: Double, ar: Double, n: Int = 100): Double = (1..n).count { s ->
        val days = person(s, ar, 0.0).map { d ->
            if (d.date > today.minusDays(14)) DayMetrics(d.date, d.values + (Metric.SLEEP to d[Metric.SLEEP]!! - hours)) else d
        }
        InsightEngine.analyse(days, goals, today).trends.any { it.metric == Metric.SLEEP }
    }.toDouble() / n

    @Test
    fun realTrendsAreStillFound() {
        val found = trendPower(0.67, 0.3)        // 40 minutes less sleep a night, typical carry-over
        val foundHour = trendPower(1.0, 0.3)     // a full hour less
        val foundSticky = trendPower(0.67, 0.5)  // strong carry-over
        val falseTypical = falseRate(0.3, 0.0, trends = true)
        println("ACCURACY real sleep trend found: 40 min ${pct(found)}, 1 h ${pct(foundHour)}, 40 min sticky ${pct(foundSticky)}; false trends typical ${pct(falseTypical)}")
        // Two weeks of nights can only just tell a 40-minute shift from normal ups and downs
        assertTrue("1 h trend found: $foundHour", foundHour >= 0.35)
        assertTrue("40 min trend found: $found", found >= 0.15)
        assertTrue("false trends, typical habits: $falseTypical", falseTypical <= 0.17)
    }

    @Test
    fun report() {
        val independent = falseRate(0.0, 0.0)
        val habits = falseRate(0.5, 0.0)
        val drifting = falseRate(0.5, 1.0)
        val trendFalse = falseRate(0.5, 0.0, trends = true)
        val moderate = power(180.0)
        val strong = power(300.0)
        println("ACCURACY people with a false link: independent ${pct(independent)}, habits ${pct(habits)}, habits+drift ${pct(drifting)}")
        println("ACCURACY people with a false trend (habits): ${pct(trendFalse)}")
        println("ACCURACY true sleep-calorie link found: moderate ${pct(moderate)}, strong ${pct(strong)}")
        // Before these changes: 28% / 58% / 100% false links, 100% false trends, 59% moderate links found.
        // A false-discovery rate of 10% means about one person in ten with no real links still sees one.
        assertTrue("false links, independent: $independent", independent <= 0.14)
        assertTrue("false links, habits: $habits", habits <= 0.15)
        assertTrue("false links, drift: $drifting", drifting <= 0.16)
        assertTrue("false trends: $trendFalse", trendFalse <= 0.17)
        assertTrue("moderate links found: $moderate", moderate >= 0.40)
        assertTrue("strong links found: $strong", strong >= 0.85)
    }

    private fun pct(x: Double) = "${(x * 100).toInt()}%"
}

