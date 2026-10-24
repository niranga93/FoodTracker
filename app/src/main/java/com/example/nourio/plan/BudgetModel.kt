package com.example.nourio.plan

import com.example.nourio.energy.Body
import com.example.nourio.energy.BodyModel
import com.example.nourio.energy.BurnSource
import com.example.nourio.energy.DayEnergy
import com.example.nourio.energy.EnergyModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class BaseGoal(
    val kcal: Double,
    val maintenance: Double,
    val dailyChange: Double,        // negative = deficit, positive = surplus
    val clamped: Boolean,           // the pace was reduced to respect the safe minimum
    val etaWeeks: Double?
)

data class Carry(val kcal: Double, val note: String?)

/** [kcalPerKg]: energy in a kg of body change for this person ([kcalPerKg] below), not a fixed 7,700. */
data class Aggregate(val days: Int, val underGoal: Double, val balance: Double, val kcalPerKg: Double = EnergyModel.KCAL_PER_KG) {
    val kg: Double get() = balance / kcalPerKg
}

/** Which limit set a day's budget when the plan asked for more than is advisable. */
enum class BudgetLimit { DEFICIT_SHARE, DEFICIT_CAP, MINIMUM }

object BudgetModel {
    // A day's deficit is limited to 25% of that day's burn and to 500 kcal: deficits over ~500 kcal/day cost
    // lean mass even with strength training (Murphy & Koehler 2022, meta-analysis), and a proportional limit
    // keeps it gentle for smaller and leaner people, who lose more lean tissue per kg (Forbes; Hall 2007).
    const val MAX_DEFICIT_SHARE = 0.25
    const val MAX_DEFICIT_KCAL = 500.0
    const val CLINICAL_MIN_KCAL = 800.0          // under this is a very-low-calorie diet (NICE): medical only
    const val MIN_GOAL_KCAL = 1200.0
    const val MIN_GOAL_MALE_KCAL = 1500.0
    const val MAX_PACE_KG_WEEK = 0.5
    const val MAX_CARRY_KCAL = 300.0
    const val MAX_CARRY_SHARE_OF_GOAL = 0.15
    const val CARRY_SHARE = 0.5
    const val MAX_MACRO_REDUCTION = 0.2
    const val MIN_VALID_YESTERDAY_KCAL = 800.0
    const val MIN_TARGET_BMI = 18.5

    /**
     * The lowest daily budget while losing weight without medical supervision: 1,500 kcal for men and 1,200 for
     * women (the common guideline; under 800 needs a doctor). It used to be the resting burn, but that isn't a
     * safety limit (the body burns more than its resting rate even on a quiet day, digestion alone ~10% of
     * intake) and on quiet days it wiped out the deficit, so following the budget didn't lose weight.
     */
    fun floorKcal(b: Body): Double = when (b.sex) {
        "Male" -> MIN_GOAL_MALE_KCAL
        "Female" -> MIN_GOAL_KCAL
        else -> (MIN_GOAL_KCAL + MIN_GOAL_MALE_KCAL) / 2
    }

    /**
     * The lowest advisable budget on a day with burn [burn]: the deficit at most 25% of the burn and 500 kcal,
     * and the intake at least the safe minimum (never under 800, and never more than the burn itself).
     */
    fun lowestBudget(b: Body, burn: Double): Pair<Double, BudgetLimit> {
        val share = MAX_DEFICIT_SHARE * burn
        val (deficitFloor, why) = if (share <= MAX_DEFICIT_KCAL) burn - share to BudgetLimit.DEFICIT_SHARE
            else burn - MAX_DEFICIT_KCAL to BudgetLimit.DEFICIT_CAP
        val minimum = minOf(max(floorKcal(b), CLINICAL_MIN_KCAL), burn)
        return if (minimum > deficitFloor) minimum to BudgetLimit.MINIMUM else deficitFloor to why
    }

    fun targetBmiOk(targetKg: Double, heightCm: Double): Boolean {
        val m = heightCm / 100.0
        return targetKg / (m * m) >= MIN_TARGET_BMI
    }

    /**
     * Average burn over days that have real activity data; otherwise a sedentary estimate of about 1.4× BMR, the
     * low end of measured burn for sedentary adults (FAO/WHO/UNU 2004). The old 1.2× ran 15% low.
     */
    fun maintenance(body: Body, days: List<DayEnergy>): Double {
        val withData = days.filter { it.source != BurnSource.BMR_ONLY }.take(14)
        return if (withData.size >= 3) withData.map { it.burned }.average()
        else EnergyModel.estimateBurn(body, null, null, null).burned
    }

    /**
     * kcal in a kg of weight change for this body: about 7,000–8,000 for most adults, less the leaner you are,
     * as more of the change is lean tissue (Forbes; Hall 2007).
     */
    fun kcalPerKg(b: Body): Double =
        BodyModel.energyPerKg(BodyModel.start(b.weightKg, b.heightCm, b.age, b.sex))

    /** The same at the target weight, averaged with now: weight loss gets leaner as it goes. */
    private fun kcalPerKgOnTheWay(b: Body, targetKg: Double): Double =
        (kcalPerKg(b) + kcalPerKg(b.copy(weightKg = targetKg))) / 2

    fun baseGoal(body: Body, maintenance: Double, targetKg: Double, paceKgPerWeek: Double): BaseGoal {
        val delta = targetKg - body.weightKg
        if (abs(delta) < 0.5) return BaseGoal(maintenance, maintenance, 0.0, false, null)

        val sign = if (delta < 0) -1.0 else 1.0
        val pace = minOf(paceKgPerWeek, MAX_PACE_KG_WEEK, body.weightKg * 0.01)
        val rho = kcalPerKgOnTheWay(body, targetKg)
        // The body adapts to eating less (or more) by about 14% of the change (Hall 2011), and the burn estimate
        // doesn't see that, so the gap is widened to keep the chosen pace
        val keep = 1 - BodyModel.BETA_AT
        val wanted = maintenance + sign * pace * rho / 7.0 / keep
        var goal = wanted
        if (sign < 0) goal = max(goal, lowestBudget(body, maintenance).first)

        val effectivePace = abs(goal - maintenance) * keep * 7.0 / rho   // kg per week
        val eta = if (effectivePace > 0.01) abs(delta) / effectivePace else null
        return BaseGoal(goal, maintenance, goal - maintenance, abs(goal - wanted) > 1.0, eta)
    }

    /** Half of yesterday's gap to the base goal, capped, and never below the safe floor. */
    fun carry(body: Body?, baseKcal: Double, yesterdayEaten: Double): Carry {
        if (baseKcal <= 0 || yesterdayEaten <= 0) return Carry(0.0, null)
        if (yesterdayEaten < max(MIN_VALID_YESTERDAY_KCAL, 0.7 * baseKcal)) {
            return Carry(0.0, "Yesterday looks only partly logged, so nothing was carried over.")
        }
        val delta = baseKcal - yesterdayEaten                      // + = under goal, - = over
        val cap = min(MAX_CARRY_KCAL, MAX_CARRY_SHARE_OF_GOAL * baseKcal)
        var c = (delta * CARRY_SHARE).coerceIn(-cap, cap)
        if (body != null) c = max(c, min(baseKcal, floorKcal(body)) - baseKcal)
        return Carry(c, null)
    }

    /** Sugar and fat only ever go down after an over day. */
    fun macroReduction(base: Double, yesterday: Double, yesterdayLogged: Boolean): Double {
        if (base <= 0 || !yesterdayLogged) return 0.0
        val excess = max(0.0, yesterday - base)
        return min(excess * CARRY_SHARE, base * MAX_MACRO_REDUCTION)
    }

    /** days: newest first. Only fully logged days count. */
    fun aggregate(days: List<DayEnergy>, n: Int, baseKcal: Double, kcalPerKg: Double = EnergyModel.KCAL_PER_KG): Aggregate {
        val used = days.take(n).filter { it.logComplete }
        return Aggregate(
            days = used.size,
            underGoal = if (baseKcal > 0) used.sumOf { baseKcal - it.eaten } else 0.0,
            balance = used.sumOf { it.balance },
            kcalPerKg = kcalPerKg,
        )
    }
}
