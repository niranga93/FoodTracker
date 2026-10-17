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
    SLEEP("Sleep", "h", +1),                          // time asleep; time in bed when no stages were recorded
    BEDTIME("Bedtime", "h", 0),                       // hour the night began; after midnight counts on past 24
    WAKE_TIME("Wake-up time", "h", 0),
    AWAKE("Awake during the night", "min", -1),
    SLEEP_EFFICIENCY("Sleep efficiency", "%", +1),    // share of time in bed spent asleep
    DEEP_SLEEP("Deep sleep", "min", +1),
    REM_SLEEP("REM sleep", "min", +1),
    BEDTIME_SHIFT("Bedtime off your usual", "h", -1), // from the median of the 7 nights before
    RESTING_HR("Resting heart rate", "bpm", -1),
    AVG_HR("Average heart rate", "bpm", 0),
    SYSTOLIC("Blood pressure (systolic)", "mmHg", -1),
    DIASTOLIC("Blood pressure (diastolic)", "mmHg", -1),
    WEIGHT("Weight", "kg", 0),
    WEIGHT_CHANGE("Weight change to the next day", "kg", 0);

    fun format(v: Double): String = when (this) {
        LAST_MEAL, BEDTIME, WAKE_TIME -> clock(v % 24)
        SLEEP, FAST, EATING_WINDOW, BEDTIME_SHIFT -> "${(v).let { "%.1f".format(it) }} h"
        AWAKE, DEEP_SLEEP, REM_SLEEP -> "${v.roundToLong()} min"
        SLEEP_EFFICIENCY -> "${v.roundToLong()}%"
        WEIGHT, WEIGHT_CHANGE -> "%.1f kg".format(v)
        STEPS -> "%,d steps".format(v.roundToLong())
        else -> "%,d %s".format(v.roundToLong(), unit)
    }

    companion object {
        /** Intake metrics only mean something on days with a full food log. */
        val INTAKE = setOf(KCAL, PROTEIN, FIBER, SUGAR, SAT_FAT, SODIUM, LAST_MEAL, FAST, EATING_WINDOW)

        /** Measured once a night rather than once a day. */
        val NIGHTLY = setOf(SLEEP, BEDTIME, WAKE_TIME, AWAKE, SLEEP_EFFICIENCY, DEEP_SLEEP, REM_SLEEP, BEDTIME_SHIFT)

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

/** A measure that moved between the last two weeks and the up-to-six weeks before ("your usual"). */
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
 * Only a curated list of pairs is tested, each with a direction that makes physiological sense. Each
 * pair uses a Spearman rank correlation (robust to outliers and odd days) on drift-free values, needs
 * at least [MIN_PAIRS] days, |rho| ≥ 0.2, an effect big enough to matter, a p-value that survives the
 * false-discovery-rate correction across all pairs (on an effective sample size that allows for habits
 * carrying over), the same direction in both halves of the data, and the expected direction.
 * Measured on simulated people in InsightAccuracyTest.
 */
object InsightEngine {
    const val MIN_PAIRS = 14
    // A floor on the correlation itself; significance and a meaningful size in real units do the rest
    private const val MIN_RHO = 0.2
    /** Expected share of false links among those reported. */
    private const val FDR = 0.10
    /** How far back "your usual" goes for trends: the weeks before the last two. */
    private const val BASELINE_DAYS = 42L

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

        // Sleep timing and quality. Each pair has a known mechanism: late or broken sleep raises hunger
        // hormones (ghrelin) and cravings, late meals disturb sleep, and activity deepens it.
        Hypothesis("bedtime-kcal", Metric.BEDTIME, Metric.KCAL, false, +1,
            { _, _, yLow, yHigh, split -> "On days after going to bed after ${clk(split)} you ate about ${n0(yHigh - yLow)} kcal more." },
            { _, split -> bedtimeTip(split, "Late nights are followed by bigger eating days for you.") }),
        Hypothesis("bedtime-sugar", Metric.BEDTIME, Metric.SUGAR, false, +1,
            { _, _, yLow, yHigh, split -> "After bedtimes later than ${clk(split)} you ate about ${n0(yHigh - yLow)} g more added sugar." },
            { _, split -> bedtimeTip(split, "Late nights bring sugar cravings for you.") }),
        Hypothesis("shift-kcal", Metric.BEDTIME_SHIFT, Metric.KCAL, false, +1,
            { _, _, yLow, yHigh, split -> "When your bedtime was more than ${h1(split)} off your usual, you ate about ${n0(yHigh - yLow)} kcal more the next day." },
            { _, _ -> steadyBedtimeTip("An irregular bedtime goes with bigger eating days for you.") }),
        Hypothesis("awake-kcal", Metric.AWAKE, Metric.KCAL, false, +1,
            { _, _, yLow, yHigh, split -> "After restless nights (more than ${n0(split)} min awake) you ate about ${n0(yHigh - yLow)} kcal more." },
            { _, _ -> restlessTip("Broken nights are followed by bigger eating days for you.") }),
        Hypothesis("awake-sugar", Metric.AWAKE, Metric.SUGAR, false, +1,
            { _, _, yLow, yHigh, split -> "After nights with more than ${n0(split)} min awake you ate about ${n0(yHigh - yLow)} g more added sugar." },
            { _, _ -> restlessTip("Broken nights bring sugar cravings for you.") }),
        Hypothesis("efficiency-kcal", Metric.SLEEP_EFFICIENCY, Metric.KCAL, false, -1,
            { _, _, yLow, yHigh, split -> "After nights where you slept less than ${n0(split)}% of your time in bed, you ate about ${n0(yLow - yHigh)} kcal more." },
            { _, _ -> restlessTip("Nights of poor sleep quality lead to bigger eating days for you.") }),
        Hypothesis("late-awake", Metric.LAST_MEAL, Metric.AWAKE, true, +1,
            { _, _, yLow, yHigh, split -> "When your last meal was after ${Metric.clock(split)} you lay awake about ${n0(yHigh - yLow)} minutes longer that night." },
            { _, split -> lateMealTip(split, "Late meals make your nights more restless.") }),
        Hypothesis("late-deep", Metric.LAST_MEAL, Metric.DEEP_SLEEP, true, -1,
            { _, _, yLow, yHigh, split -> "When your last meal was after ${Metric.clock(split)} you got about ${n0(yLow - yHigh)} minutes less deep sleep." },
            { _, split -> lateMealTip(split, "Late meals cut into your deep sleep.") }),
        Hypothesis("steps-deep", Metric.STEPS, Metric.DEEP_SLEEP, true, +1,
            { _, _, yLow, yHigh, split -> "On days with more than ${n0(split)} steps you got about ${n0(yHigh - yLow)} minutes more deep sleep that night." },
            { g, _ -> stepTip("steps-deep", g, "Active days give you more deep sleep.") }),
        Hypothesis("deep-rhr", Metric.DEEP_SLEEP, Metric.RESTING_HR, false, -1,
            { _, _, yLow, yHigh, split -> "After nights with more than ${n0(split)} min of deep sleep your resting heart rate was about ${n0(yLow - yHigh)} bpm lower." },
            { _, _ -> null }),

        Hypothesis("sodium-weight",Metric.SODIUM, Metric.WEIGHT_CHANGE, false, +1,
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

    /** A clock time for an hour that may run past 24 (bedtimes after midnight). */
    private fun clk(h: Double) = Metric.clock(h % 24)

    // The sleep tips share ids, so several links behind the same advice give one tip
    private fun bedtimeTip(split: Double, why: String) = Tip(
        "bedtime-earlier", "Get to bed a little earlier",
        "$why Aim to be in bed by ${clk(min(split, 23.5) - 0.5)}, and keep screens out of the last half hour.", null
    )

    private fun steadyBedtimeTip(why: String) = Tip(
        "steady-bedtime", "Keep a steady bedtime",
        "$why Going to bed within the same half hour every night, weekends too, is one of the strongest sleep habits.", null
    )

    private fun restlessTip(why: String) = Tip(
        "restless-night", "Calmer nights, calmer appetite",
        "$why A cool, dark room, no caffeine after noon, little alcohol and 3 hours between dinner and bed all help you stay asleep.", null
    )

    private fun lateMealTip(split: Double, why: String) = Tip(
        "late-meal-night", "Leave 3 hours between dinner and bed",
        "$why Try to finish eating before ${Metric.clock(min(split, 20.0))}.", null
    )

    private fun stepTip(id: String,g: CurrentGoals, why: String): Tip =
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

    // ------------------------------------------------------------ significance
    //
    // Daily health data breaks the textbook assumptions in three ways, each a source of false links:
    //  1. Slow drift (holidays, seasons, a new job) moves many measures together for weeks. Each value is
    //     compared with its own local two-week average instead, so only day-to-day swings count.
    //  2. Habits carry over (today looks like yesterday), so 60 days hold fewer than 60 independent
    //     observations. The test uses an effective sample size from the lag-1 autocorrelation.
    //  3. Many pairs are tested at once. Benjamini-Hochberg keeps the expected share of false links
    //     among those reported at [FDR] or below.

    /** Two-sided p-value of a t statistic with [df] degrees of freedom (regularized incomplete beta). */
    internal fun pValue(t: Double, df: Double): Double {
        if (df <= 0 || t.isNaN()) return 1.0
        if (t.isInfinite()) return 0.0
        return incompleteBeta(df / 2, 0.5, df / (df + t * t)).coerceIn(0.0, 1.0)
    }

    private fun lnGamma(x: Double): Double {
        val c = doubleArrayOf(76.18009172947146, -86.50532032941677, 24.01409824083091,
            -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5)
        var y = x; val tmp = x + 5.5 - (x + 0.5) * kotlin.math.ln(x + 5.5)
        var ser = 1.000000000190015
        for (cj in c) { y += 1; ser += cj / y }
        return -tmp + kotlin.math.ln(2.5066282746310005 * ser / x)
    }

    private fun incompleteBeta(a: Double, b: Double, x: Double): Double {
        if (x <= 0) return 0.0
        if (x >= 1) return 1.0
        val front = kotlin.math.exp(lnGamma(a + b) - lnGamma(a) - lnGamma(b) + a * kotlin.math.ln(x) + b * kotlin.math.ln(1 - x))
        return if (x < (a + 1) / (a + b + 2)) front * betaCf(a, b, x) / a else 1 - front * betaCf(b, a, 1 - x) / b
    }

    private fun betaCf(a: Double, b: Double, x: Double): Double {
        val tiny = 1e-30
        var c = 1.0; var d = 1 - (a + b) * x / (a + 1)
        if (abs(d) < tiny) d = tiny
        d = 1 / d; var h = d
        for (m in 1..200) {
            val m2 = 2.0 * m
            var aa = m * (b - m) * x / ((a - 1 + m2) * (a + m2))
            d = 1 + aa * d; if (abs(d) < tiny) d = tiny
            c = 1 + aa / c; if (abs(c) < tiny) c = tiny
            d = 1 / d; h *= d * c
            aa = -(a + m) * (a + b + m) * x / ((a + m2) * (a + 1 + m2))
            d = 1 + aa * d; if (abs(d) < tiny) d = tiny
            c = 1 + aa / c; if (abs(c) < tiny) c = tiny
            d = 1 / d; val del = d * c; h *= del
            if (abs(del - 1) < 1e-10) break
        }
        return h
    }

    /** Each value minus the average of the same measure within a week either side (drift removed). */
    internal fun localDeviations(series: Map<LocalDate, Double>): Map<LocalDate, Double> {
        val dates = series.keys.sorted()
        return dates.associateWith { d ->
            val near = dates.filter { abs(java.time.temporal.ChronoUnit.DAYS.between(it, d)) <= 7 }.map { series.getValue(it) }
            series.getValue(d) - if (near.size >= 5) near.average() else series.values.average()
        }
    }

    /**
     * Lag-1 autocorrelation of a daily series in date order, kept between 0 and 0.9. Short series
     * underestimate it, which would overstate how many independent days there are, so the usual
     * small-sample correction (Marriott & Pope) is added.
     */
    internal fun lag1(series: Map<LocalDate, Double>): Double {
        val dates = series.keys.sorted()
        val pairs = dates.zipWithNext().filter { (a, b) -> b == a.plusDays(1) }.map { (a, b) -> series.getValue(a) to series.getValue(b) }
        if (pairs.size < 5) return 0.0
        val r = pearson(pairs.map { it.first }.toDoubleArray(), pairs.map { it.second }.toDoubleArray())
        return (r + (1 + 4 * r.coerceAtLeast(0.0)) / pairs.size).coerceIn(0.0, 0.9)
    }

    /** Effective sample size of [n] paired days when both series carry over day to day. */
    internal fun effectiveN(n: Int, r1x: Double, r1y: Double): Double {
        val k = (r1x * r1y).coerceIn(0.0, 0.81)
        return n * (1 - k) / (1 + k)
    }

    /** The result of testing one link on one person's data. */
    data class LinkTest(
        val rho: Double,                       // on drift-free values
        val n: Int,
        val effectiveN: Double,
        val p: Double,                         // two-sided, before any multiple-testing correction
        val halvesAgree: Boolean,              // the sign holds in the earlier and the later half
        val raw: List<Pair<Double, Double>>,   // the paired raw values, for charts and effect sizes
    )

    /**
     * Tests whether x goes with y on the same day, or y the next day when [nextDay]. Null when fewer
     * than [MIN_PAIRS] days have both. Used by Insights and by the assistant's "does X go with Y".
     */
    fun testLink(xs: Map<LocalDate, Double>, ys: Map<LocalDate, Double>, nextDay: Boolean): LinkTest? {
        val shift = if (nextDay) 1L else 0L
        val dates = xs.keys.filter { ys.containsKey(it.plusDays(shift)) }.sorted()
        if (dates.size < MIN_PAIRS) return null
        val dx = localDeviations(dates.associateWith { xs.getValue(it) })
        val dy = localDeviations(dates.associateWith { ys.getValue(it.plusDays(shift)) })
        val rho = spearman(dates.map { dx.getValue(it) }, dates.map { dy.getValue(it) })
        val nEff = effectiveN(dates.size, lag1(dx), lag1(dy))
        val t = if (abs(rho) >= 1.0) Double.POSITIVE_INFINITY else abs(rho) * sqrt((nEff - 2).coerceAtLeast(0.0) / (1 - rho * rho))
        val p = pValue(t, nEff - 2)
        // Replication: with enough days, the earlier and later halves must point the same way
        val halvesAgree = if (dates.size < 20) true else {
            val (a, b) = dates.chunked((dates.size + 1) / 2).let { it[0] to it[1] }
            fun rhoOf(ds: List<LocalDate>) = spearman(ds.map { dx.getValue(it) }, ds.map { dy.getValue(it) })
            rhoOf(a) * rho > 0 && rhoOf(b) * rho > 0
        }
        return LinkTest(rho, dates.size, nEff, p, halvesAgree, dates.map { xs.getValue(it) to ys.getValue(it.plusDays(shift)) })
    }

    /** Benjamini-Hochberg: which of these p-values survive at false discovery rate [q]. */
    internal fun benjaminiHochberg(ps: List<Double>, q: Double): List<Boolean> {
        val m = ps.size
        if (m == 0) return emptyList()
        val order = ps.indices.sortedBy { ps[it] }
        var cutoff = -1
        order.forEachIndexed { rank, i -> if (ps[i] <= q * (rank + 1) / m) cutoff = rank }
        val keep = BooleanArray(m)
        for (rank in 0..cutoff) keep[order[rank]] = true
        return keep.toList()
    }

    /** The smallest difference worth telling someone about, in each measure's own unit. */
    private val MIN_EFFECT = mapOf(
        Metric.KCAL to 100.0, Metric.SUGAR to 5.0, Metric.RESTING_HR to 1.5, Metric.SYSTOLIC to 3.0,
        Metric.WEIGHT_CHANGE to 0.15, Metric.SLEEP to 0.25, Metric.AWAKE to 5.0, Metric.DEEP_SLEEP to 5.0,
        Metric.REM_SLEEP to 5.0,
    )

    // ------------------------------------------------------------ analysis

    private fun series(days: List<DayMetrics>, m: Metric): Map<LocalDate, Double> =
        days.mapNotNull { d -> d[m]?.let { d.date to it } }.toMap()

    fun relationships(days: List<DayMetrics>, goals: CurrentGoals): List<Pair<Relationship, Tip?>> {
        // Test every pair that has the data, then correct for testing them all at once. A pair whose
        // direction was fixed in advance is tested one-sided: only that direction can count.
        val tested = HYPOTHESES.mapNotNull { h -> testLink(series(days, h.x), series(days, h.y), h.nextDay)?.let { h to it } }
        val ps = tested.map { (h, t) ->
            when {
                h.expectedSign == 0 -> t.p
                t.rho * h.expectedSign > 0 -> t.p / 2
                else -> 1.0
            }
        }
        val survives = benjaminiHochberg(ps, FDR)
        return tested.filterIndexed { i, _ -> survives[i] }.mapNotNull { (h, t) ->
            if (abs(t.rho) < MIN_RHO || !t.halvesAgree) return@mapNotNull null
            if (h.expectedSign != 0 && t.rho * h.expectedSign <= 0) return@mapNotNull null
            val p = t.raw
            // Effect size: average y in the lower half of x versus the upper half
            val split = p.map { it.first }.sorted()[p.size / 2]
            val low = p.filter { it.first < split }.map { it.second }
            val high = p.filter { it.first >= split }.map { it.second }
            if (low.size < 3 || high.size < 3) return@mapNotNull null
            if (abs(high.average() - low.average()) < (MIN_EFFECT[h.y] ?: 0.0)) return@mapNotNull null
            val lx = p.filter { it.first < split }.map { it.first }.average()
            val hx = p.filter { it.first >= split }.map { it.first }.average()
            val rel = Relationship(
                h.id, h.x, h.y, h.nextDay, t.rho, p.size,
                h.describe(lx, hx, low.average(), high.average(), split), p
            )
            rel to (h.signedTip?.invoke(goals, split, low.average(), high.average()) ?: h.tip(goals, split))
        }.sortedByDescending { abs(it.first.rho) }
    }

    private val TREND_THRESHOLD = mapOf(
        Metric.SLEEP to 0.33, Metric.RESTING_HR to 2.0, Metric.STEPS to 1000.0, Metric.SYSTOLIC to 4.0,
        Metric.KCAL to 150.0, Metric.SODIUM to 300.0, Metric.SUGAR to 8.0, Metric.FIBER to 4.0,
        Metric.PROTEIN to 10.0, Metric.WATER to 300.0, Metric.WEIGHT to 0.5,
        Metric.FAST to 1.0, Metric.EATING_WINDOW to 1.0,
        Metric.BEDTIME to 0.5, Metric.WAKE_TIME to 0.5, Metric.AWAKE to 10.0, Metric.SLEEP_EFFICIENCY to 3.0,
        Metric.DEEP_SLEEP to 10.0, Metric.REM_SLEEP to 10.0, Metric.BEDTIME_SHIFT to 0.33
    )

    /**
     * Last 14 days against the 14 before, for each measure with at least 7 days in both halves. A
     * change must be big enough to matter ([TREND_THRESHOLD]) and unlikely to be ordinary ups and downs:
     * a Welch t-test on effective sample sizes (habits carry over day to day), corrected for testing
     * every measure at once.
     */
    fun trends(days: List<DayMetrics>, today: LocalDate): List<Trend> {
        // Every measure with enough data is tested; the size threshold is applied only after the
        // correction, since filtering first would hand the correction just the noisiest measures
        val candidates = TREND_THRESHOLD.mapNotNull { (m, threshold) ->
            val recentDays = days.filter { it.date > today.minusDays(14) }
            val beforeDays = days.filter { it.date <= today.minusDays(14) && it.date > today.minusDays(14L + BASELINE_DAYS) }
            val recent = recentDays.mapNotNull { it[m] }
            val before = beforeDays.mapNotNull { it[m] }
            if (recent.size < 7 || before.size < 7) return@mapNotNull null
            val r = recent.average(); val b = before.average()
            // Day-to-day carry-over, estimated from the whole history (up to 60 days) since four weeks
            // alone give an unstable estimate, around each half's own level within the four weeks
            val within = series(recentDays, m).mapValues { it.value - r } + series(beforeDays, m).mapValues { it.value - b }
            val k = lag1(within)
            val n1 = recent.size * (1 - k) / (1 + k); val n2 = before.size * (1 - k) / (1 + k)
            fun variance(v: List<Double>, mean: Double) = v.sumOf { (it - mean) * (it - mean) } / (v.size - 1).coerceAtLeast(1)
            val v1 = variance(recent, r) / n1; val v2 = variance(before, b) / n2
            val se = sqrt(v1 + v2)
            // Welch-Satterthwaite degrees of freedom, on the effective sample sizes
            val df = ((v1 + v2) * (v1 + v2) / (v1 * v1 / (n1 - 1).coerceAtLeast(1.0) + v2 * v2 / (n2 - 1).coerceAtLeast(1.0))).coerceAtLeast(1.0)
            val p = if (se == 0.0) 0.0 else pValue(abs(r - b) / se, df)
            val good = if (m.better == 0) null else (r - b) * m.better > 0
            Trend(m, r, b, good, days.sortedBy { it.date }.mapNotNull { it[m] }.takeLast(28)) to p
        }
        val keep = benjaminiHochberg(candidates.map { it.second }, FDR)
        return candidates.filterIndexed { i, (t, _) -> keep[i] && abs(t.change) >= (TREND_THRESHOLD[t.metric] ?: 0.0) }.map { it.first }
            .sortedByDescending { abs(it.change) / (TREND_THRESHOLD[it.metric] ?: 1.0) }
    }

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
        val shifts = week.mapNotNull { it[Metric.BEDTIME_SHIFT] }
        if (shifts.size >= 4 && shifts.average() > 1.0) out += steadyBedtimeTip(
            "This week your bedtime was about ${h1(shifts.average())} off your usual time on an average night."
        )
        val efficiency = week.mapNotNull { it[Metric.SLEEP_EFFICIENCY] }
        if (efficiency.size >= 4 && efficiency.average() < 85) out += Tip(
            "sleep-efficiency", "Make your time in bed count",
            "You were asleep for ${n0(efficiency.average())}% of your time in bed this week; 85% or more is healthy. " +
                "Go to bed when you're sleepy, and get up if you lie awake for more than 20 minutes.", null
        )
        val bedtimes = week.mapNotNull { it[Metric.BEDTIME] }
        if (bedtimes.size >= 4 && bedtimes.average() >= 24.5 && sleep.isNotEmpty() && sleep.average() < g.sleepH) out += bedtimeTip(
            bedtimes.average(), "You've been going to bed around ${clk(bedtimes.average())} and sleeping less than your goal."
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
            t.metric == Metric.SODIUM && t.good == false -> sodiumTip("sodium-trend", g, "Your sodium is up ${n0(t.change)} mg a day compared with the weeks before.")
            t.metric == Metric.SUGAR && t.good == false && g.sugar > 0 -> Tip("sugar-trend", "Added sugar is creeping up",
                "You're eating ${n0(t.change)} g more added sugar a day than in the weeks before.", GoalChange(GoalField.SUGAR, g.sugar, roundTo(g.sugar * 0.9, 5.0)))
            t.metric == Metric.EATING_WINDOW && t.change >= 1.5 -> Tip("window-trend", "Your eating window is getting longer",
                "You're eating over ${h1(t.recent)} a day, up from ${h1(t.before)} in the weeks before. Late snacks are the usual cause.", null)
            t.metric == Metric.BEDTIME && t.change >= 0.75 -> Tip("bedtime-trend", "Your bedtime is drifting later",
                "You've been going to bed around ${clk(t.recent)}, up from ${clk(t.before)} in the weeks before. " +
                    "Later nights usually mean shorter sleep and a bigger appetite the next day.", null)
            t.metric == Metric.AWAKE && t.good == false -> restlessTip(
                "You've been awake ${n0(t.change)} minutes longer a night than in the weeks before.")
            t.metric == Metric.DEEP_SLEEP && t.good == false -> Tip("deep-trend", "Less deep sleep lately",
                "You're getting ${n0(-t.change)} minutes less deep sleep a night than in the weeks before. Daytime activity, " +
                    "a cooler room and an earlier dinner all help it come back.", null)
            t.metric == Metric.BEDTIME_SHIFT && t.good == false -> steadyBedtimeTip(
                "Your bedtime has become less regular over the last two weeks.")
            t.metric == Metric.RESTING_HR && t.good == true -> Tip("rhr-improving", "Your fitness is improving",
                "Your resting heart rate dropped ${n0(-t.change)} bpm compared with the weeks before. Whatever you're doing, keep going!", null)
            t.metric == Metric.RESTING_HR && t.good == false -> Tip("rhr-rising", "Your resting heart rate is up",
                "It rose ${n0(t.change)} bpm compared with the weeks before. Short sleep, stress, illness, alcohol or less " +
                    "activity can all raise it. Protect your sleep and keep up an easy daily walk.", null)
            t.metric == Metric.SYSTOLIC && t.good == false -> Tip("bp-rising", "Keep an eye on blood pressure",
                "Your average systolic pressure is up ${n0(t.change)} mmHg on the weeks before. Less salt, regular walks and good sleep all help. " +
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
