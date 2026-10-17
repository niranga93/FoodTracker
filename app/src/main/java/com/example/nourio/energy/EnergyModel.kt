package com.example.nourio.energy

import kotlin.math.sqrt

enum class BurnSource(val label: String) {
    DEVICE_TOTAL("Health Connect total calories"),
    DEVICE_RESTING_PLUS_STEPS("Health Connect total + estimate from steps"),
    BMR_PLUS_ACTIVITY("resting + Health Connect active calories"),
    BMR_PLUS_STEPS("resting + estimate from steps"),
    BMR_ONLY("resting estimate only (no activity data)")
}

/** deviceBmr: basal metabolic rate from a watch via Health Connect, kcal/day, used instead of the formula. */
data class Body(
    val weightKg: Double,
    val heightCm: Double,
    val age: Int,
    val sex: String,
    val deviceBmr: Double? = null
)

data class DayEnergy(
    val offset: Int,            // 0 = today, -1 = yesterday ...
    val eaten: Double,
    val burned: Double,
    val source: BurnSource,
    val steps: Long?,
    val logComplete: Boolean,   // finished day with a plausible amount of food logged
    val resting: Double = 0.0   // the resting part of `burned`
) {
    val balance: Double get() = eaten - burned
}

data class BurnEstimate(val burned: Double, val source: BurnSource, val resting: Double)

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
    val avgBurned: Double = 0.0
)

data class Calibration(
    val factor: Double,               // multiplies the model's burn estimate
    val measuredMaintenance: Double,  // kcal/day implied by your weigh-ins
    val modelMaintenance: Double,     // kcal/day the formula predicted for the same days
    val slopeKgPerWeek: Double
)

object EnergyModel {
    const val KCAL_PER_KG = 7700.0          // common rule of thumb for body-weight change
    const val MIN_LOGGED_KCAL = 800.0       // below this a day is treated as an incomplete log
    private const val RESTING_FACTOR = 1.1  // BMR + digestion and tiny everyday movement
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

    /** True when the device's BMR is used: it must be within 25% of the formula to rule out bad readings. */
    fun usesDeviceBmr(b: Body): Boolean {
        val d = b.deviceBmr ?: return false
        val f = formulaBmr(b)
        return d in (0.75 * f)..(1.25 * f)
    }

    /** Resting energy expenditure, kcal/day: the device's measured BMR when plausible, otherwise the formula. */
    fun bmr(b: Body): Double = if (usesDeviceBmr(b)) b.deviceBmr!! else formulaBmr(b)

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

    fun estimateBurn(
        body: Body,
        steps: Long?,
        activeKcal: Double?,
        deviceTotal: Double?,
        distanceKm: Double? = null
    ): BurnEstimate {
        val bmr = bmr(body)
        val resting = bmr * RESTING_FACTOR

        // A plausible Health Connect total is trusted as the base figure
        val total = deviceTotal?.takeIf { it in (0.9 * bmr)..(3.5 * bmr) }
        if (total != null) {
            if (activeKcal != null && activeKcal > 0) {
                return BurnEstimate(total, BurnSource.DEVICE_TOTAL, (total - activeKcal).coerceAtLeast(0.0))
            }
            // No active calories reported. A total this close to resting looks like a resting-only
            // figure, so add the steps estimate on top.
            if (total < 1.2 * bmr) {
                val extra = walkKcal(steps, distanceKm, body)
                val source = if (extra > 0) BurnSource.DEVICE_RESTING_PLUS_STEPS else BurnSource.DEVICE_TOTAL
                return BurnEstimate(total + extra, source, total)
            }
            return BurnEstimate(total, BurnSource.DEVICE_TOTAL, minOf(resting, total))
        }

        if (activeKcal != null && activeKcal > 0) {
            return BurnEstimate(resting + activeKcal, BurnSource.BMR_PLUS_ACTIVITY, resting)
        }
        val walk = walkKcal(steps, distanceKm, body)
        if (walk > 0) {
            return BurnEstimate(resting + walk, BurnSource.BMR_PLUS_STEPS, resting)
        }
        return BurnEstimate(resting, BurnSource.BMR_ONLY, resting)
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
