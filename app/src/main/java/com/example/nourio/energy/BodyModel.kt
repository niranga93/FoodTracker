package com.example.nourio.energy

import kotlin.math.ln
import kotlin.math.max

/**
 * A day-by-day model of body weight, after the NIH dynamic model (Hall et al., Lancet 2011, and its web
 * appendix). Compared with "7,700 kcal = 1 kg" it accounts for:
 *
 * - Fat and lean tissue store different energy (9,440 vs 1,816 kcal/kg), and how a change splits between
 *   them depends on body fat (Forbes): a lean person loses more lean tissue per kg, so each kg holds less energy.
 * - Resting burn follows body composition (13 and 92 kJ/kg/day for fat and lean), so it falls as weight falls.
 * - Digesting food costs energy by macronutrient: protein ~25%, carbohydrate ~7%, fat ~2% (Tappy 1996).
 * - Adaptive thermogenesis: eating less lowers burn by a further 14% of the change, over ~2 weeks.
 * - Building tissue costs energy (750 and 960 kJ/kg for fat and lean).
 * - Glycogen holds ~2.7 g of water per g and follows carbohydrate intake, so cutting carbs drops 1–2 kg of
 *   water in days, and a high-carb day puts it back.
 * - Salt: extra sodium holds extracellular water (~0.33 kg per extra 1,000 mg/day), and low carbohydrate
 *   intake makes the kidneys shed sodium and water.
 *
 * Everything that makes weigh-ins differ from this (logging gaps, a wearable's error, water this model
 * doesn't know about) is left to [WeightFilter], which learns it from the weigh-ins.
 */
object BodyModel {
    // Energy densities, kcal/kg
    const val RHO_FAT = 9440.0
    const val RHO_LEAN = 1816.0
    const val RHO_GLYCOGEN = 4207.0
    // Cost of building tissue, kcal/kg
    private const val ETA_FAT = 179.3
    private const val ETA_LEAN = 229.4
    // Resting burn per kg of tissue, kcal/kg/day
    private const val GAMMA_FAT = 3.107
    private const val GAMMA_LEAN = 21.99
    // Forbes partition: share of a change that is lean tissue = C / (C + fat kg)
    private const val FORBES_C = 10.4 * RHO_LEAN / RHO_FAT
    // Adaptive thermogenesis: 14% of the change in intake, reached over ~14 days
    const val BETA_AT = 0.14
    private const val TAU_AT = 14.0
    // Thermic effect of food, share of the energy eaten
    private const val TEF_PROTEIN = 0.25
    private const val TEF_CARB = 0.075
    private const val TEF_FAT = 0.02
    const val TEF_MIXED = 0.10            // when the macros aren't known
    // Glycogen and water
    private const val GLYCOGEN_START = 0.5     // kg
    const val WATER_PER_GLYCOGEN = 2.7
    // Sodium and extracellular water
    private const val NA_CONC = 3220.0          // mg per litre of extracellular fluid
    private const val XI_NA = 3000.0            // mg/L/day
    private const val XI_CARB = 4000.0          // mg/day
    private const val SUBSTEPS = 8

    /** Body fat in kg from BMI, age and sex (Jackson et al. 2002, as the NIH model uses when fat isn't measured). */
    fun initialFatKg(weightKg: Double, heightCm: Double, age: Int, sex: String): Double {
        val bmi = weightKg / ((heightCm / 100) * (heightCm / 100))
        val male = 0.14 * age + 37.31 * ln(bmi) - 103.94
        val female = 0.14 * age + 39.96 * ln(bmi) - 102.01
        val pct = when (sex) { "Male" -> male; "Female" -> female; else -> (male + female) / 2 }
        return weightKg * pct.coerceIn(5.0, 60.0) / 100.0
    }

    /** What the person did on one day. Null fields weren't logged or measured. */
    data class DayInput(
        val eatenKcal: Double?,          // null: not logged fully
        val proteinG: Double = 0.0,
        val carbsG: Double = 0.0,
        val fatG: Double = 0.0,
        val sodiumMg: Double? = null,
        val activityKcal: Double = 0.0,  // burn above resting: steps, workouts, device active energy
        val waterCarbsG: Double? = null, // carbs the glycogen and water terms follow: a few days' average, as logs are rough
    )

    /** The person's usual intake, which the water and adaptation terms are measured from. */
    data class Baseline(val intakeKcal: Double, val carbKcal: Double, val sodiumMg: Double)

    data class State(
        val fatKg: Double,
        val leanKg: Double,
        val glycogenKg: Double,
        val fluidKg: Double,      // extracellular water above the starting level
        val adaptKcal: Double,    // adaptive thermogenesis, kcal/day (negative when eating less)
    ) {
        val weightKg: Double get() = fatKg + leanKg + glycogenKg * (1 + WATER_PER_GLYCOGEN) + fluidKg
        /** Weight without the water swings: what fat and lean tissue alone say. */
        val tissueKg: Double get() = fatKg + leanKg
    }

    /** A person at [weightKg], with glycogen and fluid at their usual levels. */
    fun start(weightKg: Double, heightCm: Double, age: Int, sex: String): State {
        val fat = initialFatKg(weightKg, heightCm, age, sex)
        val lean = weightKg - fat - GLYCOGEN_START * (1 + WATER_PER_GLYCOGEN)
        return State(fat, lean, GLYCOGEN_START, 0.0, 0.0)
    }

    /**
     * The resting-burn formula, anchored so the starting body burns what Mifflin-St Jeor says, then
     * following fat and lean tissue as they change.
     */
    class Resting(start: State, mifflinAtStart: Double) {
        private val k = mifflinAtStart - GAMMA_FAT * start.fatKg - GAMMA_LEAN * start.leanKg
        fun at(s: State): Double = max(800.0, k + GAMMA_FAT * s.fatKg + GAMMA_LEAN * s.leanKg)
    }

    fun tef(d: DayInput, eaten: Double): Double {
        val macroKcal = d.proteinG * 4 + d.carbsG * 4 + d.fatG * 9
        if (macroKcal < 0.5 * eaten || eaten <= 0) return TEF_MIXED * eaten
        // Scale the macros to the logged energy, so rounding in food data doesn't change the total
        val f = eaten / macroKcal
        return f * (TEF_PROTEIN * d.proteinG * 4 + TEF_CARB * d.carbsG * 4 + TEF_FAT * d.fatG * 9)
    }

    data class Step(val next: State, val burnedKcal: Double, val eatenKcal: Double)

    /**
     * Moves the body one day forward. A day that wasn't logged counts as eating what was burned, with usual
     * carbohydrate and salt, so it changes nothing. [extraKcal] is added to the energy balance (the
     * personal correction [WeightFilter] learns; 0 for the food-and-activity-only estimate).
     */
    fun step(s: State, d: DayInput, base: Baseline, resting: Resting, extraKcal: Double = 0.0): Step {
        val rmr = resting.at(s)
        val logged = d.eatenKcal != null
        val carbKcal = if (logged) (d.waterCarbsG ?: d.carbsG) * 4 else base.carbKcal
        val sodium = if (logged) d.sodiumMg ?: base.sodiumMg else base.sodiumMg

        // Without a log, eating = burning (before tissue costs), so only the learned correction moves weight
        val burnBefore = if (logged) {
            rmr + tef(d, d.eatenKcal!!) + d.activityKcal + s.adaptKcal
        } else {
            (rmr + d.activityKcal + s.adaptKcal) / (1 - TEF_MIXED)
        }
        val eaten = d.eatenKcal ?: burnBefore

        var g = s.glycogenKg
        var fluid = s.fluidKg
        var fat = s.fatKg
        var lean = s.leanKg
        val dt = 1.0 / SUBSTEPS
        val kG = max(base.carbKcal, 200.0) / (GLYCOGEN_START * GLYCOGEN_START)
        var spent = 0.0
        repeat(SUBSTEPS) {
            // Glycogen fills with carbohydrate and empties toward the level that intake supports
            val dG = (carbKcal - kG * g * g) / RHO_GLYCOGEN * dt
            g = (g + dG).coerceIn(0.05, 1.2)
            // Salt holds water; little carbohydrate makes the kidneys release sodium and water
            val carbRatio = (carbKcal / max(base.carbKcal, 200.0)).coerceIn(0.0, 3.0)
            val dNa = (sodium - base.sodiumMg).coerceIn(-2000.0, 2000.0)   // logged sodium is rough: limit what it can do
            val dFluid = (dNa - XI_NA * fluid - XI_CARB * (1 - carbRatio)) / NA_CONC * dt
            fluid += dFluid

            // Energy left for tissue, after glycogen, with the cost of building or the saving of breaking down
            val p = FORBES_C / (FORBES_C + max(fat, 1.0))
            val raw = (eaten - burnBefore + extraKcal) * dt - RHO_GLYCOGEN * dG
            val divisor = 1 + (if (raw > 0) ETA_FAT * (1 - p) / RHO_FAT + ETA_LEAN * p / RHO_LEAN else 0.0)
            val balance = raw / divisor
            fat += (1 - p) * balance / RHO_FAT
            lean += p * balance / RHO_LEAN
            spent += (raw - balance)          // tissue building cost, counted as burned
        }
        // Adaptation follows the change in intake from usual, over about two weeks
        val target = if (logged) BETA_AT * (eaten - base.intakeKcal) else s.adaptKcal
        val adapt = s.adaptKcal + (target - s.adaptKcal) / TAU_AT

        return Step(State(fat, lean, g, fluid, adapt), burnBefore + spent, eaten)
    }

    /** kcal stored or released per kg of tissue right now: about 7,000–7,600 for most adults, less when lean. */
    fun energyPerKg(s: State): Double {
        val p = FORBES_C / (FORBES_C + max(s.fatKg, 1.0))
        return 1.0 / ((1 - p) / RHO_FAT + p / RHO_LEAN)
    }
}
