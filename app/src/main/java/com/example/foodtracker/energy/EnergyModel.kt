package com.example.foodtracker.energy

enum class BurnSource(val label: String) {
    DEVICE_TOTAL("device total calories"),
    BMR_PLUS_ACTIVITY("resting + device active calories"),
    BMR_PLUS_STEPS("resting + estimate from steps"),
    BMR_ONLY("resting estimate only (no activity data)")
}

data class Body(val weightKg: Double, val heightCm: Double, val age: Int, val sex: String)

data class DayEnergy(
    val offset: Int,            // 0 = today, -1 = yesterday ...
    val eaten: Double,
    val burned: Double,
    val source: BurnSource,
    val steps: Long?,
    val logComplete: Boolean    // finished day with a plausible amount of food logged
) {
    val balance: Double get() = eaten - burned
}

data class Prediction(
    val usedDays: Int,
    val avgBalance: Double,     // kcal/day
    val weeklyKg: Double,
    val change30Kg: Double,
    val low30Kg: Double,
    val high30Kg: Double,
    val activityDays: Int,
    val largeDeficit: Boolean
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
    fun bmr(b: Body): Double {
        val s = when (b.sex) {
            "Male" -> 5.0
            "Female" -> -161.0
            else -> -78.0                   // unset or other: midpoint
        }
        return (10.0 * b.weightKg + 6.25 * b.heightCm - 5.0 * b.age + s).coerceAtLeast(800.0)
    }

    /** Net walking energy for a number of steps. */
    fun stepKcal(steps: Long, b: Body): Double {
        val stepKm = b.heightCm * STEP_LENGTH_FACTOR / 100_000.0
        return steps * stepKm * b.weightKg * WALK_KCAL_PER_KG_KM
    }

    fun estimateBurn(
        body: Body,
        steps: Long?,
        activeKcal: Double?,
        deviceTotal: Double?,
        dayComplete: Boolean
    ): Pair<Double, BurnSource> {
        val bmr = bmr(body)
        if (dayComplete && deviceTotal != null && deviceTotal in (0.9 * bmr)..(3.5 * bmr)) {
            return deviceTotal to BurnSource.DEVICE_TOTAL
        }
        val resting = bmr * RESTING_FACTOR
        if (activeKcal != null && activeKcal > 0) {
            return (resting + activeKcal) to BurnSource.BMR_PLUS_ACTIVITY
        }
        if (steps != null && steps > 0) {
            return (resting + stepKcal(steps, body)) to BurnSource.BMR_PLUS_STEPS
        }
        return resting to BurnSource.BMR_ONLY
    }

    /** Uses completed days only. Returns null when there isn't enough reliable data. */
    fun predict(days: List<DayEnergy>): Prediction? {
        val usable = days.filter { it.logComplete }
        if (usable.size < 3) return null
        val avg = usable.map { it.balance }.average()
        val c30 = avg * 30.0 / KCAL_PER_KG
        val a = c30 * 0.7
        val b = c30 * 1.3
        return Prediction(
            usedDays = usable.size,
            avgBalance = avg,
            weeklyKg = avg * 7.0 / KCAL_PER_KG,
            change30Kg = c30,
            low30Kg = minOf(a, b),
            high30Kg = maxOf(a, b),
            activityDays = usable.count { it.source != BurnSource.BMR_ONLY },
            largeDeficit = avg < -1000.0
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
     * rawDays: up to 28 completed days with the formula's (uncalibrated) burn.
     * weights: (days relative to now, negative = past, kg).
     * Compares actual weight change with calories eaten to infer real maintenance calories.
     */
    fun calibrate(rawDays: List<DayEnergy>, weights: List<Pair<Double, Double>>): Calibration? {
        val usable = rawDays.filter { it.logComplete }
        if (usable.size < 10) return null
        val w = weights.filter { it.first >= -28.0 }
        if (w.size < 5) return null
        val span = w.maxOf { it.first } - w.minOf { it.first }
        if (span < 14.0) return null

        val xm = w.map { it.first }.average()
        val ym = w.map { it.second }.average()
        val sxx = w.sumOf { (it.first - xm) * (it.first - xm) }
        if (sxx == 0.0) return null
        val slope = w.sumOf { (it.first - xm) * (it.second - ym) } / sxx   // kg per day

        val avgEaten = usable.map { it.eaten }.average()
        val avgModelBurn = usable.map { it.burned }.average()
        val measured = avgEaten - slope * KCAL_PER_KG
        val factor = (measured / avgModelBurn).coerceIn(0.8, 1.2)
        return Calibration(factor, measured, avgModelBurn, slope * 7.0)
    }
}
