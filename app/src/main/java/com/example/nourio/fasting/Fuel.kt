package com.example.nourio.fasting

import kotlin.math.max
import kotlin.math.min

/** One recent meal, reduced to what decides how long it lasts. */
data class MealFuel(val timestamp: Long, val kcal: Double, val carbs: Double, val fat: Double)

/** Where the body's energy is coming from, in plain words. */
enum class FuelPhase(val title: String, val short: String, val text: String) {
    FED(
        "Running on your meal", "your meal",
        "Glucose and fat from the food you ate are entering your blood. Insulin is up, so your body uses " +
            "this energy first and stores the rest as glycogen and fat."
    ),
    GLYCOGEN(
        "Burning stored glucose", "stored glucose",
        "Your meal is used up. Your liver now releases its stored glucose (glycogen) to keep your blood " +
            "sugar steady, while fat burning slowly rises."
    ),
    FAT(
        "Burning stored fat", "stored fat",
        "Liver glycogen is running low, so stored body fat has become your main fuel. Insulin is low and " +
            "fat cells are releasing fatty acids."
    ),
    KETOSIS(
        "Fat and ketones", "fat and ketones",
        "Your liver is turning fat into ketones, which fuel your brain and muscles alongside fat. Drink water, " +
            "and break the fast gently."
    ),
}

/**
 * The state of a fast, worked out from what was eaten:
 * - each meal takes 2.5 to 6 hours to digest and absorb, longer when it's bigger or fattier;
 * - after that, liver glycogen covers the gap until stored fat takes over, about 12 hours after the last
 *   meal, sooner after a low-carb day and later after a high-carb one (10 to 16 hours);
 * - ketone production picks up about 6 hours after that.
 * These are typical timings (Anton et al., Obesity 2018; Cahill, Annu Rev Nutr 2006). Activity speeds
 * every step up, so the times are estimates and the app says so.
 */
data class FuelState(
    val phase: FuelPhase,
    val lastMeal: Long,
    val absorbedAt: Long,          // when the last meals are used up
    val fatAt: Long,               // when stored fat becomes the main fuel
    val ketosisAt: Long,           // when ketone production picks up
    val stillAbsorbingKcal: Double,
    val lastMealKcal: Double,      // the last meal, with anything eaten within the hour before it
    val fromFood: Double,          // share of energy now, 0..1; the three add up to 1
    val fromGlycogen: Double,
    val fromFat: Double,
)

object Fuel {
    private const val HOUR = 3_600_000.0

    /** Hours a meal takes to digest and absorb. */
    fun absorbHours(m: MealFuel): Double = (2.0 + m.kcal / 250.0 + m.fat / 50.0).coerceIn(2.5, 6.0)

    /** Hours after the last meal until fat is the main fuel, from the carbs of the last 24 hours. */
    fun fatAfterHours(carbs24h: Double): Double = (12.0 + (carbs24h - 200.0) / 100.0).coerceIn(10.0, 16.0)

    /** [meals] are the last day or so of meals, any order; null when nothing breaks a fast. */
    fun state(meals: List<MealFuel>, now: Long): FuelState? {
        val eating = meals.filter { it.kcal >= Fasting.MIN_MEAL_KCAL && it.timestamp <= now }.sortedBy { it.timestamp }
        val last = eating.lastOrNull() ?: return null
        val absorbedAt = eating.maxOf { it.timestamp + (absorbHours(it) * HOUR).toLong() }
        val carbs24 = eating.filter { it.timestamp > now - 24 * HOUR }.sumOf { it.carbs }
        val fatAt = max(last.timestamp + (fatAfterHours(carbs24) * HOUR).toLong(), absorbedAt + (4 * HOUR).toLong())
        val ketosisAt = fatAt + (6 * HOUR).toLong()

        val still = eating.sumOf { m ->
            val done = (now - m.timestamp) / (absorbHours(m) * HOUR)
            m.kcal * (1 - done).coerceIn(0.0, 1.0)
        }
        val lastKcal = eating.filter { it.timestamp >= last.timestamp - HOUR }.sumOf { it.kcal }

        // Fuel mix at the milestones (food, glycogen, fat), joined by straight lines between them
        val points = listOf(
            last.timestamp to Triple(0.85, 0.0, 0.15),
            absorbedAt to Triple(0.0, 0.6, 0.4),
            fatAt to Triple(0.0, 0.35, 0.65),
            ketosisAt to Triple(0.0, 0.15, 0.85),
        )
        val mix = when {
            now >= ketosisAt -> points.last().second
            else -> {
                val i = points.indexOfLast { it.first <= now }.coerceAtLeast(0)
                val (t0, a) = points[i]; val (t1, b) = points[min(i + 1, points.lastIndex)]
                val f = if (t1 > t0) ((now - t0).toDouble() / (t1 - t0)).coerceIn(0.0, 1.0) else 0.0
                Triple(a.first + (b.first - a.first) * f, a.second + (b.second - a.second) * f, a.third + (b.third - a.third) * f)
            }
        }
        val phase = when {
            now < absorbedAt -> FuelPhase.FED
            now < fatAt -> FuelPhase.GLYCOGEN
            now < ketosisAt -> FuelPhase.FAT
            else -> FuelPhase.KETOSIS
        }
        return FuelState(phase, last.timestamp, absorbedAt, fatAt, ketosisAt, still, lastKcal, mix.first, mix.second, mix.third)
    }
}
