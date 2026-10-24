package com.example.nourio.energy

import kotlin.math.sqrt

enum class BurnSource(val label: String) {
    DEVICE_TOTAL("Health Connect total calories"),
    DEVICE_RESTING_PLUS_STEPS("Health Connect total + estimate from steps"),
    BMR_PLUS_ACTIVITY("resting + Health Connect active calories"),
    BMR_PLUS_STEPS("resting + estimate from steps"),
    BMR_ONLY("resting estimate only (no activity data)")
}

data class Body(
    val weightKg: Double,
    val heightCm: Double,
    val age: Int,
    val sex: String
)

data class DayEnergy(
    val offset: Int,            // 0 = today, -1 = yesterday ...
    val eaten: Double,
    val burned: Double,
    val source: BurnSource,
    val steps: Long?,
    val logComplete: Boolean,   // finished day with a plausible amount of food logged
    val resting: Double = 0.0,  // the resting part of `burned`
    val shownActivity: Double? = null,  // activity to display, when it isn't simply burned - resting
) {
    val balance: Double get() = eaten - burned
    /** Calories burned by movement, as shown to the person. */
    val activityKcal: Double get() = (shownActivity ?: (burned - resting)).coerceIn(0.0, burned)
}

/**
 * [shownActivity] is for display only: a device daily total holds the device's resting rate plus movement, so
 * movement is the total above resting, while [resting] (which the body model uses) also carries digestion.
 */
data class BurnEstimate(val burned: Double, val source: BurnSource, val resting: Double, val shownActivity: Double? = null)

data class Prediction(
    val usedDays: Int,
    val avgBalance: Double,     // kcal/day
    val weeklyKg: Double,
    val change30Kg: Double,
    val low30Kg: Double,
    val high30Kg: Double,
    val activityDays: Int,
    val largeDeficit: Boolean,
    val avgEaten: Double = 0.0,
    val avgBurned: Double = 0.0,
    // From the body model and weigh-ins (null with the simple model)
    val nowKg: Double? = null,           // today's weight, with day-to-day water smoothed out
    val gapKcal: Double? = null,         // learned personal energy gap, kcal/day
    val gapSd: Double? = null,
    val waterKg: Double? = null,         // glycogen and salt water above usual today
    val weighIns: Int = 0,
)

data class Calibration(
    val factor: Double,               // multiplies the model's burn estimate
    val measuredMaintenance: Double,  // kcal/day implied by your weigh-ins
    val modelMaintenance: Double,     // kcal/day the formula predicted for the same days
    val slopeKgPerWeek: Double
)

object EnergyModel {
    const val KCAL_PER_KG = 7700.0          // the old rule; the body model ([BodyModel.energyPerKg]) replaces it
    const val MIN_LOGGED_KCAL = 800.0       // below this a day is treated as an incomplete log
    private const val RESTING_FACTOR = 1.1  // BMR + digestion, for [restingBurn] only
    /**
     * Everyday movement that step counts miss: standing, posture, chores, fidgeting. Standing alone burns about
     * 10% above rest and light chores about 15% (Levine). Measured daily burn of sedentary adults is 1.4–1.7×
     * BMR (FAO/WHO/UNU 2004), while BMR + 10% digestion + a few thousand steps only reaches about 1.2×. Phone
     * step counts also miss the steps taken while the phone is put down. Weigh-ins calibrate the rest.
     */
    const val NEAT_SHARE = 0.20
    /** With no activity data at all: unmeasured movement plus a little walking, about 1.4× BMR with digestion. */
    const val NO_DATA_ACTIVITY_SHARE = 0.25
    /** Digestion: about 10% of the energy eaten (protein ~25%, carbohydrate ~7%, fat ~2%). */
    const val TEF_SHARE = 0.10
    /**
     * Wrist devices tend to overestimate exercise energy; validation studies put typical errors at 15–40%
     * (Shcherbina et al., J Pers Med 2017). Active energy is trusted at 90% until weigh-ins calibrate it.
     */
    const val DEVICE_ACTIVE_TRUST = 0.90
    private const val WALK_KCAL_PER_KG_KM = 0.5
    private const val STEP_LENGTH_FACTOR = 0.415

    fun restingBurn(b: Body): Double = bmr(b) * RESTING_FACTOR

    /** Net kcal for one walking step. */
    fun kcalPerStep(b: Body): Double = stepKcal(1000L, b) / 1000.0

    /** Step length in km. */
    fun stepKm(b: Body): Double = b.heightCm * STEP_LENGTH_FACTOR / 100_000.0

    /** Mifflin-St Jeor resting energy expenditure, kcal/day. */
    fun formulaBmr(b: Body): Double {
        val s = when (b.sex) {
            "Male" -> 5.0
            "Female" -> -161.0
            else -> -78.0                   // unset or other: midpoint
        }
        return (10.0 * b.weightKg + 6.25 * b.heightCm - 5.0 * b.age + s).coerceAtLeast(800.0)
    }

    /** Resting energy expenditure, kcal/day. */
    fun bmr(b: Body): Double = formulaBmr(b)

    /** Net walking energy for a number of steps. */
    fun stepKcal(steps: Long, b: Body): Double {
        val stepKm = b.heightCm * STEP_LENGTH_FACTOR / 100_000.0
        return steps * stepKm * b.weightKg * WALK_KCAL_PER_KG_KM
    }

    /** Walking energy from the distance a device measured, more accurate than a step-length guess. */
    private fun walkKcal(steps: Long?, distanceKm: Double?, b: Body): Double = when {
        distanceKm != null && distanceKm > 0 -> distanceKm * b.weightKg * WALK_KCAL_PER_KG_KM
        steps != null && steps > 0 -> stepKcal(steps, b)
        else -> 0.0
    }

    /**
     * Calories burned in a day. "Resting" is BMR plus digestion ([TEF_SHARE] of [eaten], or of a typical day when
     * the log isn't complete); the rest is movement. Without a device total, movement is the steps or device
     * active energy plus everyday movement that steps miss ([NEAT_SHARE]).
     */
    fun estimateBurn(
        body: Body,
        steps: Long?,
        activeKcal: Double?,
        deviceTotal: Double?,
        distanceKm: Double? = null,
        eaten: Double? = null,
    ): BurnEstimate {
        val bmr = bmr(body)
        val tef = TEF_SHARE * (eaten?.takeIf { it >= MIN_LOGGED_KCAL } ?: (1.4 * bmr))
        val resting = bmr + tef
        val neat = NEAT_SHARE * bmr
        val active = activeKcal?.takeIf { it > 0 }?.let { it * DEVICE_ACTIVE_TRUST }

        // A plausible Health Connect total is trusted as the base figure
        val total = deviceTotal?.takeIf { it in (0.9 * bmr)..(3.5 * bmr) }
            // A total only from part of the day (or a watch not worn) still counts, with no separate active figure
            ?: deviceTotal?.takeIf { it > 0 && it < 0.9 * bmr && (activeKcal == null || activeKcal <= 0) }
        if (total != null) {
            if (activeKcal != null && activeKcal > 0) {
                val trimmed = total - (activeKcal - active!!)          // the device's own resting part stays
                return BurnEstimate(trimmed, BurnSource.DEVICE_TOTAL, (trimmed - active).coerceAtLeast(0.0))
            }
            // No active calories reported (Samsung Health sends only a daily total: its resting rate for the
            // day plus movement so far). Counting the steps on top of that total counted an 8,000-step day
            // twice (~600-800 kcal too high). Early in the day the device may not have added the walking yet,
            // so the burn is whichever is larger: the device total, or resting plus the steps. That keeps it
            // rising smoothly through the day (it used to add a typical day's movement early on, then drop
            // once the device total took over).
            val walk = walkKcal(steps, distanceKm, body)
            if (total < bmr + walk) {
                val burned = bmr + walk
                return BurnEstimate(burned, BurnSource.DEVICE_RESTING_PLUS_STEPS, minOf(resting, burned), shownActivity = walk)
            }
            return BurnEstimate(total, BurnSource.DEVICE_TOTAL, minOf(resting, total), shownActivity = total - minOf(bmr, total))
        }

        if (active != null) {
            return BurnEstimate(resting + neat + active, BurnSource.BMR_PLUS_ACTIVITY, resting)
        }
        val walk = walkKcal(steps, distanceKm, body)
        if (walk > 0) {
            return BurnEstimate(resting + neat + walk, BurnSource.BMR_PLUS_STEPS, resting)
        }
        return BurnEstimate(resting + NO_DATA_ACTIVITY_SHARE * bmr, BurnSource.BMR_ONLY, resting)
    }

    /**
     * Uses completed days only. Returns null when there isn't enough reliable data. The 30-day range is a
     * 95% interval from two real sources of doubt: how much the daily balance varies (so the average is
     * uncertain), and how far the burn estimate can be off ([burnUncertainty], smaller once weigh-ins
     * have calibrated it).
     */
    fun predict(days: List<DayEnergy>, burnUncertainty: Double = 0.10): Prediction? {
        val usable = days.filter { it.logComplete }
        if (usable.size < 3) return null
        val balances = usable.map { it.balance }
        val avg = balances.average()
        val c30 = avg * 30.0 / KCAL_PER_KG
        val sd = sqrt(balances.sumOf { (it - avg) * (it - avg) } / (balances.size - 1))
        val avgBurned = usable.map { it.burned }.average()
        // [burnUncertainty] is itself a 95% bound (the estimate is within 10% of the truth), so it adds as is
        val perDay = sqrt((1.96 * sd / sqrt(balances.size.toDouble())).let { it * it } + (burnUncertainty * avgBurned).let { it * it })
        val half = perDay * 30.0 / KCAL_PER_KG
        return Prediction(
            usedDays = usable.size,
            avgBalance = avg,
            weeklyKg = avg * 7.0 / KCAL_PER_KG,
            change30Kg = c30,
            low30Kg = c30 - half,
            high30Kg = c30 + half,
            activityDays = usable.count { it.source != BurnSource.BMR_ONLY },
            largeDeficit = avg < -1000.0,
            avgEaten = usable.map { it.eaten }.average(),
            avgBurned = usable.map { it.burned }.average()
        )
    }

    /** Exponentially smoothed ("trend") weights, oldest first. */
    fun trend(weights: List<Double>, alpha: Double = 0.3): List<Double> {
        val out = ArrayList<Double>(weights.size)
        var t = 0.0
        weights.forEachIndexed { i, w ->
            t = if (i == 0) w else t + alpha * (w - t)
            out.add(t)
        }
        return out
    }

    /**
     * Theil-Sen slope: the median of the slopes between every pair of readings at least 3 days apart.
     * A water spike after a salty dinner or a missed reading barely moves it, unlike a least-squares line.
     */
    internal fun theilSen(points: List<Pair<Double, Double>>): Double? {
        val slopes = ArrayList<Double>()
        for (i in points.indices) for (j in i + 1 until points.size) {
            val dx = points[j].first - points[i].first
            if (kotlin.math.abs(dx) >= 3.0) slopes += (points[j].second - points[i].second) / dx
        }
        if (slopes.isEmpty()) return null
        slopes.sort()
        val m = slopes.size / 2
        return if (slopes.size % 2 == 1) slopes[m] else (slopes[m - 1] + slopes[m]) / 2
    }

    /**
     * rawDays: up to 28 completed days with the formula's (uncalibrated) burn.
     * weights: (days relative to now, negative = past, kg).
     * Compares actual weight change with calories eaten to infer real maintenance calories.
     */
    fun calibrate(rawDays: List<DayEnergy>, weights: List<Pair<Double, Double>>): Calibration? {
        val usable = rawDays.filter { it.logComplete }
        if (usable.size < 10) return null
        // One reading per day, the first: a day weighed three times would otherwise count three times,
        // and later readings carry that day's food and drink
        val w = weights.filter { it.first >= -28.0 }
            .groupBy { kotlin.math.floor(it.first) }.values.map { day -> day.minBy { it.first } }
        if (w.size < 5) return null
        val span = w.maxOf { it.first } - w.minOf { it.first }
        if (span < 14.0) return null

        val slope = theilSen(w) ?: return null   // kg per day

        val avgEaten = usable.map { it.eaten }.average()
        val avgModelBurn = usable.map { it.burned }.average()
        val measured = avgEaten - slope * KCAL_PER_KG
        val factor = (measured / avgModelBurn).coerceIn(0.8, 1.2)
        return Calibration(factor, measured, avgModelBurn, slope * 7.0)
    }
}
