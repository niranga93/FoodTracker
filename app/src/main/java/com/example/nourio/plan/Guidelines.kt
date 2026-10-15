package com.example.nourio.plan

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Recommended daily limits for one person, from their calorie goal, body and weight direction.
 *
 * Sources for each value:
 * - Calories: the calorie goal (target-weight plan or typed goal), otherwise maintenance, otherwise the
 *   2,000 kcal reference used on food labels.
 * - Protein (a minimum): 0.8 g/kg, the RDA for adults (US/Canada Dietary Reference Intakes);
 *   1.0 g/kg from age 65 (PROT-AGE study group, 2013); 1.2 g/kg while losing weight, to keep muscle;
 *   1.4 g/kg while gaining. Above a BMI of 25 the weight used is the weight at BMI 25, since extra fat
 *   doesn't raise protein needs. Kept between 10% and 35% of calories (Acceptable Macronutrient
 *   Distribution Range, AMDR).
 * - Fat (a maximum): 30% of calories (WHO healthy diet; AMDR 20 to 35%).
 * - Saturated fat (a maximum): 10% of calories (WHO; Dietary Guidelines for Americans).
 * - Carbs (a maximum): the calories left after protein and fat, kept between 40% and 60%
 *   (AMDR 45 to 65%), so protein, fat and carbs add up to the calorie goal.
 * - Added sugar (a maximum): under 10% of calories (WHO free sugars; Dietary Guidelines for Americans),
 *   and no more than the American Heart Association's 25 g a day for women or 36 g for men.
 *   Natural sugar inside whole fruit, vegetables and milk has no limit in any of these guidelines.
 * - Fiber (a minimum): 14 g per 1,000 kcal (US/Canada Adequate Intake), between 21 and 38 g.
 * - Sodium (a maximum): 2,300 mg (Dietary Guidelines for Americans; Chronic Disease Risk Reduction
 *   intake). 1,500 mg is advised for high blood pressure, which is left to the person and their doctor.
 */
object Guidelines {
    const val REFERENCE_KCAL = 2000.0
    const val FAT_SHARE = 0.30
    const val SAT_FAT_SHARE = 0.10
    const val ADDED_SUGAR_SHARE = 0.10
    const val ADDED_SUGAR_CAP_MALE = 36.0
    const val ADDED_SUGAR_CAP_OTHER = 25.0
    const val FIBER_PER_1000_KCAL = 14.0
    const val SODIUM_MG = 2300.0
    private const val TREND_KCAL = 150.0      // a calorie goal this far from maintenance means losing or gaining

    enum class Direction { LOSE, KEEP, GAIN }

    data class Person(
        val weightKg: Double = 0.0,        // 0 = unknown
        val heightCm: Double = 0.0,
        val age: Int = 0,
        val sex: String = "",              // "Female", "Male", "Other" or ""
        val direction: Direction = Direction.KEEP,
    )

    data class Values(
        val kcal: Double,
        val kcalSource: String,            // "your goal", "maintenance" or "reference"
        val protein: Double,
        val proteinPerKg: Double?,         // null when protein came from calories (no weight)
        val fat: Double,
        val satFat: Double,
        val carbs: Double,
        val addedSugar: Double,
        val fiber: Double,
        val sodiumMg: Double,
    )

    /** Losing, keeping or gaining, from a plan's daily change or the calorie goal against maintenance. */
    fun direction(planDailyChange: Double?, kcalGoal: Double, maintenance: Double): Direction = when {
        planDailyChange != null -> when {
            planDailyChange < -1 -> Direction.LOSE
            planDailyChange > 1 -> Direction.GAIN
            else -> Direction.KEEP
        }
        kcalGoal <= 0 || maintenance <= 0 -> Direction.KEEP
        kcalGoal < maintenance - TREND_KCAL -> Direction.LOSE
        kcalGoal > maintenance + TREND_KCAL -> Direction.GAIN
        else -> Direction.KEEP
    }

    fun proteinPerKg(p: Person): Double = when (p.direction) {
        Direction.LOSE -> 1.2
        Direction.GAIN -> 1.4
        Direction.KEEP -> if (p.age >= 65) 1.0 else 0.8
    }

    /** Body weight for protein: actual weight, or the weight at BMI 25 when above it. */
    fun proteinWeight(p: Person): Double {
        if (p.heightCm <= 0) return p.weightKg
        val m = p.heightCm / 100.0
        return min(p.weightKg, 25.0 * m * m)
    }

    fun compute(kcalGoal: Double, maintenance: Double, p: Person): Values {
        val (kcal, source) = when {
            kcalGoal > 0 -> kcalGoal to "your goal"
            maintenance > 0 -> maintenance to "maintenance"
            else -> REFERENCE_KCAL to "reference"
        }

        val perKg = if (p.weightKg > 0) proteinPerKg(p) else null
        val rawProtein = perKg?.let { it * proteinWeight(p) } ?: (kcal * 0.15 / 4)
        val protein = rawProtein.coerceIn(kcal * 0.10 / 4, kcal * 0.35 / 4)

        val fat = kcal * FAT_SHARE / 9
        val carbShare = (1.0 - protein * 4 / kcal - FAT_SHARE).coerceIn(0.40, 0.60)

        return Values(
            kcal = roundTo(kcal, 10.0),
            kcalSource = source,
            protein = roundTo(protein, 5.0),
            proteinPerKg = perKg,
            fat = roundTo(fat, 5.0),
            satFat = roundTo(kcal * SAT_FAT_SHARE / 9, 1.0),
            carbs = roundTo(kcal * carbShare / 4, 5.0),
            addedSugar = roundTo(min(kcal * ADDED_SUGAR_SHARE / 4, if (p.sex == "Male") ADDED_SUGAR_CAP_MALE else ADDED_SUGAR_CAP_OTHER), 1.0),
            fiber = roundTo(max(21.0, min(38.0, kcal / 1000 * FIBER_PER_1000_KCAL)), 1.0),
            sodiumMg = SODIUM_MG,
        )
    }

    private fun roundTo(v: Double, step: Double) = (v / step).roundToInt() * step
}
