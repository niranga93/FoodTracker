package com.example.nourio.insights

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** What is measured each day. [better] says which direction is healthier, when there is one. */
enum class Metric(val label: String, val unit: String, val better: Int) {
    KCAL("Calories eaten", "kcal", 0),
    PROTEIN("Protein", "g", +1),
    FIBER("Fiber", "g", +1),
    SUGAR("Added sugar", "g", -1),
    SAT_FAT("Saturated fat", "g", -1),
    SODIUM("Sodium", "mg", -1),
    WATER("Water", "ml", +1),
    LAST_MEAL("Last meal of the day", "h", -1),       // hour of day, e.g. 21.5
    FAST("Overnight fast", "h", 0),                   // hours from the last meal to the first meal of the day
    EATING_WINDOW("Eating window", "h", 0),           // first to last meal
    STEPS("Steps", "", +1),
    SLEEP("Sleep", "h", +1),
    RESTING_HR("Resting heart rate", "bpm", -1),
    AVG_HR("Average heart rate", "bpm", 0),
    SYSTOLIC("Blood pressure (systolic)", "mmHg", -1),
    DIASTOLIC("Blood pressure (diastolic)", "mmHg", -1),
    WEIGHT("Weight", "kg", 0),
    WEIGHT_CHANGE("Weight change to the next day", "kg", 0);

    fun format(v: Double): String = when (this) {
        LAST_MEAL -> clock(v)
        SLEEP, FAST, EATING_WINDOW -> "${(v).let { "%.1f".format(it) }} h"
        WEIGHT, WEIGHT_CHANGE -> "%.1f kg".format(v)
        STEPS -> "%,d steps".format(v.roundToLong())
        else -> "%,d %s".format(v.roundToLong(), unit)
    }

    companion object {
        /** Intake metrics only mean something on days with a full food log. */
        val INTAKE = setOf(KCAL, PROTEIN, FIBER, SUGAR, SAT_FAT, SODIUM, LAST_MEAL, FAST, EATING_WINDOW)

        fun clock(h: Double): String {
            val hh = h.toInt().coerceIn(0, 23)
            val mm = ((h - hh) * 60).roundToInt().coerceIn(0, 59)
            val ampm = if (hh < 12) "am" else "pm"
            val h12 = if (hh % 12 == 0) 12 else hh % 12
            return "%d:%02d %s".format(h12, mm, ampm)
        }
    }
}

/** One day of data. Missing values are simply absent from [values]. */
data class DayMetrics(val date: LocalDate, val values: Map<Metric, Double>) {
    operator fun get(m: Metric): Double? = values[m]
}

/** Goals a tip can change. */
enum class GoalField(val label: String, val unit: String) {
    SODIUM("Sodium limit", "mg"),
    SUGAR("Added sugar limit", "g"),
    FIBER("Fiber goal", "g"),
    PROTEIN("Protein goal", "g"),
    WATER("Water goal", "ml"),
    STEPS("Step goal", "steps"),
    SLEEP("Sleep goal", "h"),
    FAST("Fasting goal", "h");

    fun format(v: Double) = if (this == SLEEP || this == FAST) "%.0f h".format(v) else "%,d %s".format(v.roundToLong(), unit)
}

data class GoalChange(val field: GoalField, val from: Double, val to: Double) {
    val text get() = "${field.label}: ${field.format(from)} → ${field.format(to)}"
}

/** A recommendation. [id] is stable, so an accepted or dismissed tip isn't offered again. */
data class Tip(val id: String, val title: String, val body: String, val change: GoalChange?)

/** A link found between two measures, with the paired values for the chart. */
data class Relationship(
    val id: String,
    val x: Metric,
    val y: Metric,
    val lagged: Boolean,                 // y is the following day
    val rho: Double,
    val n: Int,
    val summary: String,
    val points: List<Pair<Double, Double>>
) {
    val strength: String get() = when {
        abs(rho) >= 0.6 -> "Strong link"
        abs(rho) >= 0.4 -> "Clear link"
        else -> "Possible link"
    }
}

/** A measure that moved between the last two weeks and the two before. */
data class Trend(
    val metric: Metric,
    val recent: Double,
    val before: Double,
    val good: Boolean?,
    val series: List<Double>
) {
    val change get() = recent - before
}

/** The current goals, as the engine needs them. 0 = not set. */
data class CurrentGoals(
    val sodiumMg: Double, val sugar: Double, val fiber: Double, val protein: Double,
    val waterMl: Double, val steps: Double, val sleepH: Double, val weightKg: Double,
    val fastH: Double = 14.0
)

data class Analysis(
    val days: Int,
    val coverage: Map<Metric, Int>,
    val relationships: List<Relationship>,
    val trends: List<Trend>,
    val tips: List<Tip>
)

/**
 * Finds relationships, trends and tips in daily data.
 *
 * Only a curated list of pairs is tested, each with a direction that makes physiological sense, so
 * that the many-comparisons problem doesn't produce nonsense links. Each pair uses a Spearman rank
 * correlation (robust to outliers and odd days), needs at least [MIN_PAIRS] days, |rho| ≥ 0.3 and a
 * t statistic ≥ 2 (about p < 0.05), and is only reported when it points the expected way.
 */
object InsightEngine {
    const val MIN_PAIRS = 10
    private const val MIN_RHO = 0.3
    private const val MIN_T = 2.0

    private data class Hypothesis(
        val id: String,
        val x: Metric,
        val y: Metric,
        val nextDay: Boolean,
        val expectedSign: Int,               // 0: either way is worth knowing (fasting)
        val describe: (lowX: Double, highX: Double, yLow: Double, yHigh: Double, splitAt: Double) -> String,
        val tip: (CurrentGoals, Double) -> Tip?,
        val signedTip: ((g: CurrentGoals, split: Double, yLow: Double, yHigh: Double) -> Tip?)? = null
    )

    private fun n0(v: Double) = "%,d".format(v.roundToLong())

    private val HYPOTHESES = listOf(
        Hypothesis("sleep-kcal", Metric.SLEEP, Metric.KCAL, false, -1,
            { _, _, yLow, yHigh, split -> "On days after less than ${"%.1f".format(split)} h of sleep you ate about ${n0(yLow - yHigh)} kcal more." },
            { g, split -> sleepTip("sleep-kcal", g, split, "Short nights are followed by bigger eating days for you. More sleep makes the calorie budget easier.") }),
        Hypothesis("sleep-sugar", Metric.SLEEP, Metric.SUGAR, false, -1,
            { _, _, yLow, yHigh, split -> "After nights under ${"%.1f".format(split)} h you ate about ${n0(yLow - yHigh)} g more added sugar." },
            { g, split -> sleepTip("sleep-sugar", g, split, "Tired days bring sugar cravings for you. Sleep is your best defence.") }),
        Hypothesis("sleep-rhr", Metric.SLEEP, Metric.RESTING_HR, false, -1,
            { _, _, yLow, yHigh, split -> "After short nights (under ${"%.1f".format(split)} h) your resting heart rate is about ${n0(yLow - yHigh)} bpm higher." },
            { g, split -> sleepTip("sleep-rhr", g, split, "Your heart works harder after short nights.") }),
        Hypothesis("steps-sleep", Metric.STEPS, Metric.SLEEP, true, +1,
            { _, _, yLow, yHigh, split -> "On days with more than ${n0(split)} steps you slept about ${n0((yHigh - yLow) * 60)} minutes longer that night." },
            { g, _ -> stepTip("steps-sleep", g, "Active days give you longer sleep.") }),
        Hypothesis("steps-rhr", Metric.STEPS, Metric.RESTING_HR, true, -1,
            { _, _, yLow, yHigh, split -> "After days with more than ${n0(split)} steps your resting heart rate is about ${n0(yLow - yHigh)} bpm lower the next day." },
            { g, _ -> stepTip("steps-rhr", g, "Moving more is linked with a calmer heart for you.") }),
        Hypothesis("late-sleep", Metric.LAST_MEAL, Metric.SLEEP, true, -1,
            { _, _, yLow, yHigh, split -> "When your last meal was after ${Metric.clock(split)} you slept about ${n0((yLow - yHigh) * 60)} minutes less." },
            { _, split -> Tip("late-sleep", "Finish dinner earlier", "You sleep less after late meals. Try to finish eating before ${Metric.clock(min(split, 20.0))}.", null) }),
        Hypothesis("sodium-weight", Metric.SODIUM, Metric.WEIGHT_CHANGE, false, +1,
            { _, _, yLow, yHigh, split -> "After days over ${n0(split)} mg of sodium your weight was up about ${"%.1f".format(yHigh - yLow)} kg the next morning (mostly water)." },
            { g, _ -> sodiumTip("sodium-weight", g, "Salty days show up on your scale the next morning as water weight.") }),
        Hypothesis("sodium-bp", Metric.SODIUM, Metric.SYSTOLIC, true, +1,
            { _, _, yLow, yHigh, split -> "After days over ${n0(split)} mg of sodium your blood pressure was about ${n0(yHigh - yLow)} mmHg higher the next day." },
            { g, _ -> sodiumTip("sodium-bp", g, "Your blood pressure rises after salty days.") }),
        Hypothesis("sugar-kcal", Metric.SUGAR, Metric.KCAL, true, +1,
            { _, _, yLow, yHigh, split -> "After days with more than ${n0(split)} g of added sugar you ate about ${n0(yHigh - yLow)} kcal more the next day." },
            { g, _ -> if (g.sugar <= 0) null else Tip("sugar-kcal", "Lower your added sugar limit",
                "Days with a lot of added sugar lead to bigger eating days for you.", GoalChange(GoalField.SUGAR, g.sugar, roundTo(g.sugar * 0.85, 5.0)))
            }),
        Hypothesis("water-kcal", Metric.WATER, Metric.KCAL, false, -1,
            { _, _, yLow, yHigh, split -> "On days you drank more than ${n0(split)} ml of water you ate about ${n0(yLow - yHigh)} kcal less." },
            { g, _ -> Tip("water-kcal", "Drink a little more water",
                "Well-hydrated days are lighter eating days for you.", GoalChange(GoalField.WATER, g.waterMl, g.waterMl + 250)) }),
        Hypothesis("protein-kcal", Metric.PROTEIN, Metric.KCAL, true, -1,
            { _, _, yLow, yHigh, split -> "After days with more than ${n0(split)} g of protein you ate about ${n0(yLow - yHigh)} kcal less the next day." },
            { g, _ -> if (g.protein <= 0) null else Tip("protein-kcal", "Raise your protein goal",
                "Protein-rich days keep you fuller the next day.", GoalChange(GoalField.PROTEIN, g.protein, roundTo(g.protein * 1.1, 5.0))) }),
        Hypothesis("kcal-weight", Metric.KCAL, Metric.WEIGHT_CHANGE, false, +1,
            { _, _, yLow, yHigh, split -> "After days over ${n0(split)} kcal your weight was about ${"%.1f".format(yHigh - yLow)} kg higher the next morning." },
            { _, _ -> null }),

        // Fasting. Either direction is reported: some people eat less after a long fast, others make up for it.
        Hypothesis("fast-kcal", Metric.FAST, Metric.KCAL, false, 0,
            { _, _, yLow, yHigh, split ->
                if (yHigh < yLow) "On days after an overnight fast of ${h1(split)} or more you ate about ${n0(yLow - yHigh)} kcal less."
                else "On days after an overnight fast of ${h1(split)} or more you ate about ${n0(yHigh - yLow)} kcal more. Long fasts may be leading to bigger meals."
            },
            { _, _ -> null },
            { g, split, yLow, yHigh -> fastKcalTip(g, split, yHigh < yLow) }),
        Hypothesis("fast-weight", Metric.FAST, Metric.WEIGHT_CHANGE, false, 0,
            { _, _, yLow, yHigh, split ->
                if (yHigh < yLow) "After an overnight fast of ${h1(split)} or more, your weight the next morning was about ${kg(yLow - yHigh)} lower than after shorter fasts."
                else "After an overnight fast of ${h1(split)} or more, your weight the next morning was about ${kg(yHigh - yLow)} higher than after shorter fasts."
            },
            { _, _ -> null },
            { g, split, yLow, yHigh -> if (yHigh < yLow) fastGoalTip("fast-weight", g, split,
                "Your longer fasts go with lower weight the next morning.") else null }),
        Hypothesis("window-kcal", Metric.EATING_WINDOW, Metric.KCAL, false, 0,
            { _, _, yLow, yHigh, split ->
                if (yHigh > yLow) "On days you ate over more than ${h1(split)} you ate about ${n0(yHigh - yLow)} kcal more."
                else "Your calories hardly rise with a longer eating window: on longer days you ate about ${n0(yLow - yHigh)} kcal less."
            },
            { _, _ -> null },
            { g, split, yLow, yHigh -> if (yHigh > yLow) Tip("window-kcal", "Eat within a shorter window",
                "Long eating days are bigger eating days for you. Try to fit your meals into ${h1(split)} or less.",
                if (g.fastH < 24 - split) GoalChange(GoalField.FAST, g.fastH, fastGoalFor(24 - split)) else null) else null }),
        Hypothesis("window-weight", Metric.EATING_WINDOW, Metric.WEIGHT_CHANGE, false, 0,
            { _, _, yLow, yHigh, split ->
                if (yHigh > yLow) "On days you ate over more than ${h1(split)} your weight was about ${kg(yHigh - yLow)} higher the next morning."
                else "A longer eating window doesn't push your weight up: the next morning it was about ${kg(yLow - yHigh)} lower."
            },
            { _, _ -> null })
    )

    private fun h1(v: Double) = "%.1f h".format(v)
    private fun kg(v: Double) = "%.1f kg".format(v)

    /** The nearest fasting goal (12 to 20 h) at or above [h]. */
    private fun fastGoalFor(h: Double) = listOf(12.0, 14.0, 16.0, 18.0, 20.0).firstOrNull { it >= h - 0.25 } ?: 20.0

    private fun fastGoalTip(id: String, g: CurrentGoals, split: Double, why: String): Tip {
        val target = fastGoalFor(split)
        return Tip(id, "Longer overnight fasts suit you", "$why Aim for about ${h1(target)} between dinner and your first meal.",
            if (target > g.fastH) GoalChange(GoalField.FAST, g.fastH, target) else null)
    }

    private fun fastKcalTip(g: CurrentGoals, split: Double, lessAfterFast: Boolean): Tip =
        if (lessAfterFast) fastGoalTip("fast-kcal", g, split, "You eat less on days after a longer fast.")
        else Tip("fast-overeat", "Don't let long fasts turn into big meals",
            "You tend to eat more after long fasts. Break the fast with protein and fiber (eggs, yogurt, beans, " +
                "vegetables), or try a shorter fast.",
            if (g.fastH > 12) GoalChange(GoalField.FAST, g.fastH, g.fastH - 2) else null)

    private fun roundTo(v: Double, step: Double) = (v / step).roundToInt() * step

    private fun sleepTip(id: String, g: CurrentGoals, split: Double, why: String): Tip {
        val target = max(7.0, (split * 2).roundToInt() / 2.0 + 0.5).coerceAtMost(9.0)
        return Tip(id, "Aim for ${"%.1f".format(target)} hours of sleep", "$why A regular bedtime helps most.",
            if (g.sleepH < target) GoalChange(GoalField.SLEEP, g.sleepH, target) else null)
    }

    private fun stepTip(id: String, g: CurrentGoals, why: String): Tip =
        Tip(id, "Step it up a little", "$why Add 1,000 steps a day, about a 10-minute walk.",
            GoalChange(GoalField.STEPS, g.steps, g.steps + 1000))

    private fun sodiumTip(id: String, g: CurrentGoals, why: String): Tip {
        val from = if (g.sodiumMg > 0) g.sodiumMg else 2300.0
        val to = max(1500.0, from - 300)
        return Tip(id, "Cut back on salt", "$why Go easy on sauces, salty snacks and packaged food.",
            if (to < from) GoalChange(GoalField.SODIUM, from, to) else null)
    }

    // ------------------------------------------------------------ statistics

    /** Ranks with ties sharing their average rank. */
    internal fun ranks(v: List<Double>): DoubleArray {
        val idx = v.indices.sortedBy { v[it] }
        val r = DoubleArray(v.size)
        var i = 0
        while (i < idx.size) {
            var j = i
            while (j + 1 < idx.size && v[idx[j + 1]] == v[idx[i]]) j++
            val avg = (i + j) / 2.0 + 1
            for (k in i..j) r[idx[k]] = avg
            i = j + 1
        }
        return r
    }

    internal fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average()
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in a.indices) {
            num += (a[i] - ma) * (b[i] - mb); da += (a[i] - ma) * (a[i] - ma); db += (b[i] - mb) * (b[i] - mb)
        }
        return if (da == 0.0 || db == 0.0) 0.0 else num / sqrt(da * db)
    }

    internal fun spearman(x: List<Double>, y: List<Double>) = pearson(ranks(x), ranks(y))

    private fun tStat(r: Double, n: Int) = if (abs(r) >= 1.0) Double.MAX_VALUE else abs(r) * sqrt((n - 2) / (1 - r * r))

    // ------------------------------------------------------------ analysis

    private fun usable(d: DayMetrics, m: Metric) = d[m]

    private fun pairs(days: List<DayMetrics>, x: Metric, y: Metric, nextDay: Boolean): List<Pair<Double, Double>> {
        val byDate = days.associateBy { it.date }
        return days.mapNotNull { d ->
            val xv = usable(d, x) ?: return@mapNotNull null
            val other = if (nextDay) byDate[d.date.plusDays(1)] ?: return@mapNotNull null else d
            val yv = usable(other, y) ?: return@mapNotNull null
            xv to yv
        }
    }

    fun relationships(days: List<DayMetrics>, goals: CurrentGoals): List<Pair<Relationship, Tip?>> =
        HYPOTHESES.mapNotNull { h ->
            val p = pairs(days, h.x, h.y, h.nextDay)
            if (p.size < MIN_PAIRS) return@mapNotNull null
            val rho = spearman(p.map { it.first }, p.map { it.second })
            if (abs(rho) < MIN_RHO || tStat(rho, p.size) < MIN_T) return@mapNotNull null
            if (h.expectedSign != 0 && rho * h.expectedSign <= 0) return@mapNotNull null
            // Effect size: average y in the lower half of x versus the upper half
            val split = p.map { it.first }.sorted()[p.size / 2]
            val low = p.filter { it.first < split }.map { it.second }
            val high = p.filter { it.first >= split }.map { it.second }
            if (low.size < 3 || high.size < 3) return@mapNotNull null
            val lx = p.filter { it.first < split }.map { it.first }.average()
            val hx = p.filter { it.first >= split }.map { it.first }.average()
            val rel = Relationship(
                h.id, h.x, h.y, h.nextDay, rho, p.size,
                h.describe(lx, hx, low.average(), high.average(), split), p
            )
            rel to (h.signedTip?.invoke(goals, split, low.average(), high.average()) ?: h.tip(goals, split))
        }.sortedByDescending { abs(it.first.rho) }

    private val TREND_THRESHOLD = mapOf(
        Metric.SLEEP to 0.33, Metric.RESTING_HR to 2.0, Metric.STEPS to 1000.0, Metric.SYSTOLIC to 4.0,
        Metric.KCAL to 150.0, Metric.SODIUM to 300.0, Metric.SUGAR to 8.0, Metric.FIBER to 4.0,
        Metric.PROTEIN to 10.0, Metric.WATER to 300.0, Metric.WEIGHT to 0.5,
        Metric.FAST to 1.0, Metric.EATING_WINDOW to 1.0
    )

    /** Last 14 days against the 14 before, for each measure with at least 5 days in both halves. */
    fun trends(days: List<DayMetrics>, today: LocalDate): List<Trend> =
        TREND_THRESHOLD.mapNotNull { (m, threshold) ->
            val recent = days.filter { it.date > today.minusDays(14) }.mapNotNull { it[m] }
            val before = days.filter { it.date <= today.minusDays(14) && it.date > today.minusDays(28) }.mapNotNull { it[m] }
            if (recent.size < 5 || before.size < 5) return@mapNotNull null
            val r = recent.average(); val b = before.average()
            if (abs(r - b) < threshold) return@mapNotNull null
            val good = if (m.better == 0) null else (r - b) * m.better > 0
            Trend(m, r, b, good, days.sortedBy { it.date }.mapNotNull { it[m] }.takeLast(28))
        }.sortedByDescending { abs(it.change) / (TREND_THRESHOLD[it.metric] ?: 1.0) }

    /** Rule-based tips from how often goals are met in the last 7 days. */
    private fun goalTips(days: List<DayMetrics>, today: LocalDate, g: CurrentGoals): List<Tip> {
        val week = days.filter { it.date > today.minusDays(7) && it.date < today }   // finished days only
        val out = mutableListOf<Tip>()
        val steps = week.mapNotNull { it[Metric.STEPS] }
        if (steps.size >= 5 && steps.count { it >= g.steps } >= 5) out += Tip(
            "steps-up-${g.steps.roundToLong()}", "You're ready for a bigger step goal",
            "You reached ${n0(g.steps)} steps on ${steps.count { it >= g.steps }} of the last ${steps.size} days. Time for a new challenge.",
            GoalChange(GoalField.STEPS, g.steps, g.steps + 1000)
        )
        // A bit above this week's average, but always below the current goal (no step data: no tip)
        val easier = if (steps.isEmpty()) 0.0 else min(roundTo(steps.average() + 1000, 500.0), g.steps - 500)
        if (steps.size >= 5 && steps.count { it >= g.steps } == 0 && g.steps > 6000 && easier >= 4000) out += Tip(
            "steps-down-${g.steps.roundToLong()}", "Set a step goal you can win",
            "You haven't reached ${n0(g.steps)} steps this week. A goal you can hit keeps you motivated; raise it again later.",
            GoalChange(GoalField.STEPS, g.steps, easier)
        )
        val fiber = week.mapNotNull { it[Metric.FIBER] }
        if (g.fiber > 0 && fiber.size >= 4 && fiber.count { it < g.fiber * 0.6 } >= 4) out += Tip(
            "fiber-low", "Add fiber to every meal",
            "You've been well under your fiber goal most days. Beans, lentils, oats, berries and vegetables are the easiest wins.", null
        )
        val protein = week.mapNotNull { it[Metric.PROTEIN] }
        if (g.protein > 0 && protein.size >= 4 && protein.count { it < g.protein * 0.7 } >= 4) out += Tip(
            "protein-low", "Spread protein through the day",
            "Most days you've been short of protein. Eggs, yogurt, fish, chicken, tofu or beans at each meal close the gap.", null
        )
        val water = week.mapNotNull { it[Metric.WATER] }
        if (water.size >= 5 && water.count { it >= g.waterMl } >= 5 && g.waterMl < 3000) out += Tip(
            "water-up-${g.waterMl.roundToLong()}", "Great hydration streak",
            "You met your water goal on ${water.count { it >= g.waterMl }} of the last ${water.size} days. Keep it up, or raise it a little.",
            GoalChange(GoalField.WATER, g.waterMl, g.waterMl + 250)
        )
        val sleep = week.mapNotNull { it[Metric.SLEEP] }
        if (sleep.size >= 4 && sleep.average() < 6.5) out += Tip(
            "sleep-short", "Protect your sleep",
            "You averaged ${"%.1f".format(sleep.average())} h of sleep this week. Under 7 h makes cravings and overeating more likely.",
            if (g.sleepH < 7.0) GoalChange(GoalField.SLEEP, g.sleepH, 7.0) else null
        )
        val fasts = week.mapNotNull { it[Metric.FAST] }
        if (fasts.size >= 5 && fasts.count { it >= g.fastH } >= 5) out += Tip(
            "fast-week-${g.fastH.roundToLong()}", "Steady fasting rhythm",
            "You reached your ${h1(g.fastH)} overnight fast on ${fasts.count { it >= g.fastH }} of the last ${fasts.size} days. " +
                "A regular rhythm matters more than a longer fast.", null
        )
        return out
    }

    private fun trendTips(trends: List<Trend>, g: CurrentGoals): List<Tip> = trends.mapNotNull { t ->
        when {
            t.metric == Metric.SODIUM && t.good == false -> sodiumTip("sodium-trend", g, "Your sodium is up ${n0(t.change)} mg a day compared with two weeks before.")
            t.metric == Metric.SUGAR && t.good == false && g.sugar > 0 -> Tip("sugar-trend", "Added sugar is creeping up",
                "You're eating ${n0(t.change)} g more added sugar a day than two weeks ago.", GoalChange(GoalField.SUGAR, g.sugar, roundTo(g.sugar * 0.9, 5.0)))
            t.metric == Metric.EATING_WINDOW && t.change >= 1.5 -> Tip("window-trend", "Your eating window is getting longer",
                "You're eating over ${h1(t.recent)} a day, up from ${h1(t.before)} two weeks ago. Late snacks are the usual cause.", null)
            t.metric == Metric.RESTING_HR && t.good == true -> Tip("rhr-improving", "Your fitness is improving",
                "Your resting heart rate dropped ${n0(-t.change)} bpm in two weeks. Whatever you're doing, keep going!", null)
            t.metric == Metric.RESTING_HR && t.good == false -> Tip("rhr-rising", "Your resting heart rate is up",
                "It rose ${n0(t.change)} bpm compared with two weeks before. Short sleep, stress, illness, alcohol or less " +
                    "activity can all raise it. Protect your sleep and keep up an easy daily walk.", null)
            t.metric == Metric.SYSTOLIC && t.good == false -> Tip("bp-rising", "Keep an eye on blood pressure",
                "Your average systolic pressure is up ${n0(t.change)} mmHg on two weeks ago. Less salt, regular walks and good sleep all help. " +
                    "If it stays high, talk to a doctor.", null)
            else -> null
        }
    }

    fun analyse(days: List<DayMetrics>, goals: CurrentGoals, today: LocalDate): Analysis {
        val rels = relationships(days, goals)
        val trends = trends(days, today)
        val tips = (rels.mapNotNull { it.second } + trendTips(trends, goals) + goalTips(days, today, goals))
            .distinctBy { it.id }
            // Tips that would change the same goal: keep the first (strongest) one
            .let { list -> list.filter { t -> t.change == null || list.first { it.change?.field == t.change.field } == t } }
        val coverage = Metric.entries.associateWith { m -> days.count { it[m] != null } }
        return Analysis(days.size, coverage, rels.map { it.first }, trends, tips)
    }
}
