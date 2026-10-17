package com.example.nourio.fasting

import kotlin.math.max
import kotlin.math.min

/** One recent meal, reduced to what decides how long it lasts. */
data class MealFuel(val timestamp: Long, val kcal: Double, val carbs: Double, val fat: Double, val fiber: Double = 0.0)

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
 * The state of a fast, worked out from what was eaten, in two parts:
 *
 * 1. Digestion. The stomach empties one meal after another, at about 3 kcal a minute for a mixed meal
 *    (Hunt & Stubbs, Gut 1975; Calbet & MacLean, J Physiol 1997), slower with more fat and fibre, after a
 *    short lag; nutrients reach the blood about 45 minutes after they leave the stomach. A snack eaten
 *    while lunch is still in the stomach waits behind it, so it extends the meal instead of running alongside.
 *
 * 2. Liver glycogen, the body's store of glucose between meals. After digestion it holds about 30 g plus a
 *    quarter of the last 24 hours' carbs, up to about 100 g (Taylor et al., J Clin Invest 1996), and
 *    drains at 0.046 g per kg of body weight per hour while fasting (Rothman et al., Science 1991, by
 *    magnetic resonance). Stored fat becomes the main fuel when about 65 g is left, and ketone
 *    production picks up when about 35 g is left, typically 12 to 16 and 18 to 24 hours after a normal day's
 *    last meal (Anton et al., Obesity 2018; Cahill, Annu Rev Nutr 2006).
 *
 * 3. Activity. Moving drains the store faster: during exercise about half the energy comes from
 *    carbohydrate and about a quarter of that from the liver (Wahren et al., J Clin Invest 1971; Trefts
 *    et al., Physiology 2015), about 0.03 g of liver glycogen per active kcal. Activity from Health
 *    Connect since the meal was used up is counted; times still ahead assume rest and move earlier as
 *    you move. Activity while still digesting is fuelled by the meal, so it doesn't count.
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
    val glycogenG: Double = 0.0,   // estimated liver glycogen now, grams
    val activeKcal: Double = 0.0,  // activity from Health Connect counted since the meal was used up
)

object Fuel {
    private const val HOUR = 3_600_000.0

    // Digestion
    private const val EMPTY_KCAL_PER_H = 180.0      // ~3 kcal/min for a mixed meal
    private const val LAG_H = 0.33                  // before the stomach starts emptying
    private const val ABSORB_H = 0.75               // from leaving the stomach to reaching the blood

    // Liver glycogen, grams
    private const val GLYCOGEN_MIN = 30.0
    private const val GLYCOGEN_MAX = 100.0
    private const val GLYCOGEN_FROM_CARBS = 0.25
    private const val DRAIN_G_PER_KG_H = 0.046
    private const val FAT_MAIN_AT_G = 65.0
    private const val KETONES_AT_G = 35.0
    /** Liver glycogen per active kcal: half from carbohydrate, a quarter of that from the liver, 4 kcal/g. */
    const val LIVER_G_PER_ACTIVE_KCAL = 0.5 * 0.25 / 4.0

    /** Stomach emptying speed for a meal, kcal per hour: slower with more fat and more fibre. */
    fun emptyRate(m: MealFuel): Double {
        val fatShare = if (m.kcal > 0) (m.fat * 9 / m.kcal).coerceIn(0.0, 1.0) else 0.0
        return EMPTY_KCAL_PER_H * (1 - 0.4 * fatShare) / (1 + m.fiber / 40.0)
    }

    /** Hours one meal takes on its own, from eating to fully absorbed. */
    fun absorbHours(m: MealFuel): Double = (LAG_H + m.kcal / emptyRate(m) + ABSORB_H).coerceIn(1.5, 8.0)

    /** When each meal's stomach emptying starts and ends, queued in the order eaten. */
    internal fun queue(eating: List<MealFuel>): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        var free = Long.MIN_VALUE
        for (m in eating) {
            val start = max(m.timestamp + (LAG_H * HOUR).toLong(), free)
            val emptyH = (absorbHours(m) - LAG_H - ABSORB_H).coerceAtLeast(0.4)
            val end = start + (emptyH * HOUR).toLong()
            out += start to end
            free = end
        }
        return out
    }

    /** Liver glycogen once digestion is done, from the last 24 hours' carbs. */
    fun glycogenFed(carbs24h: Double): Double = (GLYCOGEN_MIN + GLYCOGEN_FROM_CARBS * carbs24h).coerceIn(GLYCOGEN_MIN, GLYCOGEN_MAX)

    /** Active kcal between [from] and [to], from hourly slices (hour start, kcal), counting part hours pro rata. */
    internal fun activeBetween(activity: List<Pair<Long, Double>>, from: Long, to: Long): Double =
        if (to <= from) 0.0 else activity.sumOf { (start, kcal) ->
            val end = start + HOUR.toLong()
            val overlap = min(end, to) - max(start, from)
            if (overlap > 0) kcal * overlap / HOUR else 0.0
        }

    /**
     * [meals] are the last day or so of meals, any order; null when nothing breaks a fast. [activity] is
     * Health Connect's active energy per hour, (hour start, kcal); empty when not connected.
     */
    fun state(
        meals: List<MealFuel>, now: Long, weightKg: Double = 70.0,
        activity: List<Pair<Long, Double>> = emptyList(),
    ): FuelState? {
        val eating = meals.filter { it.kcal >= Fasting.MIN_MEAL_KCAL && it.timestamp <= now }.sortedBy { it.timestamp }
        val last = eating.lastOrNull() ?: return null
        val absorbMs = (ABSORB_H * HOUR).toLong()

        // 1. Digestion, meals queued in the stomach
        val q = queue(eating)
        val absorbedAt = q.maxOf { it.second } + absorbMs
        // Food reaches the blood as the stomach empties, 45 minutes later
        val still = eating.indices.sumOf { i ->
            val (s, e) = q[i]
            val done = if (e > s) (now - (s + absorbMs)).toDouble() / (e - s) else 1.0
            eating[i].kcal * (1 - done).coerceIn(0.0, 1.0)
        }
        val lastKcal = eating.filter { it.timestamp >= last.timestamp - HOUR }.sumOf { it.kcal }

        // 2. Liver glycogen, draining from the end of digestion
        val carbs24 = eating.filter { it.timestamp > last.timestamp - 24 * HOUR }.sumOf { it.carbs }
        val g0 = glycogenFed(carbs24)
        val drain = DRAIN_G_PER_KG_H * weightKg.coerceIn(40.0, 150.0)     // g per hour at rest
        // Activity so far drains extra; what's ahead is unknown, so it's taken as rest
        val moved = activeBetween(activity, absorbedAt, now)
        fun glycogenAt(t: Long): Double = if (t <= absorbedAt) g0 else
            g0 - (t - absorbedAt) / HOUR * drain - activeBetween(activity, absorbedAt, min(t, now)) * LIVER_G_PER_ACTIVE_KCAL
        // The first time the store is down to [g], in 5-minute steps (it only ever falls)
        fun whenLeft(g: Double): Long {
            if (g0 <= g) return absorbedAt
            val step = 5 * 60_000L
            var t = absorbedAt
            val limit = absorbedAt + (48 * HOUR).toLong()
            while (t < limit && glycogenAt(t) > g) t += step
            return t
        }
        val fatAt = whenLeft(FAT_MAIN_AT_G).coerceIn(absorbedAt, last.timestamp + (20 * HOUR).toLong())
        val ketosisAt = whenLeft(KETONES_AT_G).coerceIn(fatAt + (4 * HOUR).toLong(), fatAt + (16 * HOUR).toLong())
        val glycogenNow = max(0.0, glycogenAt(now))

        // Fuel mix (food, glycogen, fat) at the milestones; stored glucose leads until fat takes over
        val points = listOf(
            last.timestamp to Triple(0.85, 0.05, 0.10),
            absorbedAt to Triple(0.0, 0.6, 0.4),
            fatAt to Triple(0.0, 0.5, 0.5),
            ketosisAt to Triple(0.0, 0.2, 0.8),
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
        return FuelState(phase, last.timestamp, absorbedAt, fatAt, ketosisAt, still, lastKcal,
            mix.first, mix.second, mix.third, glycogenNow, moved)
    }
}
