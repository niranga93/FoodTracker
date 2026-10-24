package com.example.nourio.energy

import kotlin.math.sqrt

/**
 * Combines [BodyModel] with the weigh-ins (a Kalman filter). Two things are learned:
 *
 * - offset: how far real weight sits from the model's weight, in kg.
 * - bias: the personal energy gap in kcal/day. Positive means weight moves as if you eat more, or burn less,
 *   than the log and the activity data say. Self-reported intake usually comes out 10–35% low against
 *   doubly labelled water, and wrist devices are often 15–40% off on calories burned, so this gap is the
 *   largest single source of error in a food-log forecast.
 *
 * - daytime rise: how much heavier you weigh late in the day than on waking, in kg. Weight climbs through
 *   the day with food and drink, and morning is the standard reference (Dittmar et al.). People who weigh in
 *   at different times teach the filter their own rise; until then it assumes about 0.9 kg by midnight.
 *
 * Each weigh-in counts as the model's weight plus offset plus the daytime rise for its time, plus noise.
 * Glycogen and salt water are already in the model, so the noise left is smaller than raw day-to-day swings.
 */
object WeightFilter {
    internal const val SCALE_SD = 0.6             // kg, a morning weigh-in after the water the model explains
    internal const val LATE_EXTRA_SD = 0.3        // kg more at midnight: what was eaten and drunk varies
    private const val LOGGED_SD = 250.0          // kcal, error of one logged day
    private const val UNLOGGED_SD = 600.0        // kcal, a day without a full log
    private const val BIAS_DRIFT_SD = 15.0       // kcal/day per day: habits change slowly
    internal const val BIAS_PRIOR_SD = 350.0      // kcal/day before any weigh-in
    internal const val OFFSET_PRIOR_SD = 0.4      // kg: the starting weigh-in itself carries water
    const val RISE_PRIOR_KG = 0.9                // daytime rise by midnight, before your own data says otherwise
    private const val RISE_PRIOR_SD = 0.4
    private const val RISE_DRIFT_SD = 0.01

    /**
     * A weigh-in: [kg] on the scale, [late] how far into the waking day it was, 0 on waking (6 am or earlier)
     * to 1 at midnight or later.
     */
    data class Reading(val kg: Double, val late: Double = 0.0)

    /** One day of history: what was eaten and done, and that day's weigh-ins. [input] is null for today, which isn't over yet. */
    data class Day(val input: BodyModel.DayInput?, val readings: List<Reading>) {
        companion object {
            /** Morning weigh-ins only. */
            fun mornings(input: BodyModel.DayInput?, kgs: List<Double>) = Day(input, kgs.map { Reading(it) })
        }
    }

    data class Result(
        val model: BodyModel.State,      // model state this morning (food and activity only)
        val offsetKg: Double,
        val biasKcal: Double,
        val riseKg: Double,              // learned daytime rise by midnight
        val pOffset: Double,             // variances and covariance
        val pBias: Double,
        val pCross: Double,
        val weighIns: Int,
        val track: List<Pair<Double, Double>>,  // (filtered morning weight, its sd) each morning, oldest first
    ) {
        val weightKg: Double get() = model.weightKg + offsetKg
        val biasSd: Double get() = sqrt(pBias)
        /** A reading as if taken on waking. */
        fun morning(r: Reading): Double = r.kg - riseKg * r.late
    }

    /**
     * Runs the filter over [days], oldest first, from [start] on the morning of the first day. A day's
     * weigh-ins are compared before that day's food is added; the daytime rise covers what was eaten by then.
     */
    fun run(
        start: BodyModel.State,
        days: List<Day>,
        base: BodyModel.Baseline,
        resting: BodyModel.Resting,
    ): Result {
        var s = start
        // State x = [offset kg, bias kcal/day, rise kg] and its covariance
        val x = doubleArrayOf(0.0, 0.0, RISE_PRIOR_KG)
        val p = arrayOf(
            doubleArrayOf(OFFSET_PRIOR_SD * OFFSET_PRIOR_SD, 0.0, 0.0),
            doubleArrayOf(0.0, BIAS_PRIOR_SD * BIAS_PRIOR_SD, 0.0),
            doubleArrayOf(0.0, 0.0, RISE_PRIOR_SD * RISE_PRIOR_SD),
        )
        var count = 0
        val track = ArrayList<Pair<Double, Double>>(days.size)

        for (day in days) {
            for (reading in day.readings) {
                // z = model + offset + rise * late; H = [1, 0, late]
                val h = doubleArrayOf(1.0, 0.0, reading.late)
                val sd = SCALE_SD + LATE_EXTRA_SD * reading.late
                val innovation = reading.kg - (s.weightKg + x[0] + x[2] * reading.late)
                val ph = DoubleArray(3) { i -> (0..2).sumOf { j -> p[i][j] * h[j] } }
                val sInn = (0..2).sumOf { h[it] * ph[it] } + sd * sd
                val k = DoubleArray(3) { ph[it] / sInn }
                for (i in 0..2) x[i] += k[i] * innovation
                for (i in 0..2) for (j in 0..2) p[i][j] -= k[i] * ph[j]
                count++
            }
            track += (s.weightKg + x[0]) to sqrt(p[0][0])
            val input = day.input ?: continue

            // Predict to tomorrow morning: the model takes the day's food; the bias moves the offset
            val rho = BodyModel.energyPerKg(s)
            s = BodyModel.step(s, input, base, resting).next
            // The gap is mostly food that wasn't logged, which also costs ~10% to digest
            val a = (1 - BodyModel.TEF_MIXED) / rho
            val sdE = if (input.eatenKcal != null) LOGGED_SD else UNLOGGED_SD
            x[0] += a * x[1]
            // P <- F P F' + Q with F = [[1, a, 0], [0, 1, 0], [0, 0, 1]]
            val row0 = DoubleArray(3) { j -> p[0][j] + a * p[1][j] }
            for (j in 0..2) p[0][j] = row0[j]
            val col0 = DoubleArray(3) { i -> p[i][0] + a * p[i][1] }
            for (i in 0..2) p[i][0] = col0[i]
            p[0][0] += (sdE / rho) * (sdE / rho)
            p[1][1] += BIAS_DRIFT_SD * BIAS_DRIFT_SD
            p[2][2] += RISE_DRIFT_SD * RISE_DRIFT_SD
        }
        return Result(s, x[0], x[1], x[2], p[0][0], p[1][1], p[0][1], count, track)
    }

    data class Outlook(
        val nowKg: Double,
        val in30Kg: Double,
        val low30Kg: Double,
        val high30Kg: Double,
        val tissuePerWeekKg: Double,     // fat and lean only, without water swings
        val waterNowKg: Double,          // glycogen and salt water above usual, today
    )

    /**
     * The next 30 days, if the coming days look like [typical] (the recent average) and the learned gap stays.
     * The range is a 95% interval from the filter's uncertainty, the day-to-day spread of intake, and how
     * many days the average rests on.
     */
    fun outlook(
        r: Result,
        typical: BodyModel.DayInput,
        intakeSd: Double,
        typicalDays: Int,
        base: BodyModel.Baseline,
        resting: BodyModel.Resting,
        horizon: Int = 30,
    ): Outlook {
        var s = r.model
        val rho = BodyModel.energyPerKg(s)
        // The gap goes in as food the log missed, so digestion and adaptation see the real intake
        val withGap = typical.copy(eatenKcal = (typical.eatenKcal ?: 0.0) + r.biasKcal)
        repeat(horizon) { s = BodyModel.step(s, withGap, base, resting).next }
        val now = r.weightKg
        val end = s.weightKg + r.offsetKg
        val a = horizon * (1 - BodyModel.TEF_MIXED) / rho
        val meanSd = if (typicalDays > 1) intakeSd / sqrt(typicalDays.toDouble()) else 400.0
        val variance = r.pOffset + 2 * a * r.pCross + a * a * r.pBias +
            a * a * meanSd * meanSd +
            horizon * (LOGGED_SD / rho) * (LOGGED_SD / rho)
        val half = 1.96 * sqrt(variance.coerceAtLeast(0.0))
        val water0 = r.model.glycogenKg * (1 + BodyModel.WATER_PER_GLYCOGEN) + r.model.fluidKg
        val usualWater = 0.5 * (1 + BodyModel.WATER_PER_GLYCOGEN)
        return Outlook(
            nowKg = now,
            in30Kg = end,
            low30Kg = end - half,
            high30Kg = end + half,
            tissuePerWeekKg = (s.tissueKg - r.model.tissueKg) / horizon * 7,   // the bias is already in the steps
            waterNowKg = water0 - usualWater,
        )
    }
}
