package com.example.foodtracker.plan

import com.example.foodtracker.energy.Body
import com.example.foodtracker.energy.BurnSource
import com.example.foodtracker.energy.DayEnergy
import com.example.foodtracker.energy.EnergyModel
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

data class Aggregate(val days: Int, val underGoal: Double, val balance: Double) {
    val kg: Double get() = balance / EnergyModel.KCAL_PER_KG
}

object BudgetModel {
    const val MIN_GOAL_KCAL = 1200.0
    const val MAX_PACE_KG_WEEK = 0.5
    const val MAX_CARRY_KCAL = 300.0
    const val MAX_CARRY_SHARE_OF_GOAL = 0.15
    const val CARRY_SHARE = 0.5
    const val MAX_MACRO_REDUCTION = 0.2
    const val MIN_VALID_YESTERDAY_KCAL = 800.0
    const val MIN_TARGET_BMI = 18.5

    fun floorKcal(b: Body): Double = max(MIN_GOAL_KCAL, EnergyModel.bmr(b))

    fun targetBmiOk(targetKg: Double, heightCm: Double): Boolean {
        val m = heightCm / 100.0
        return targetKg / (m * m) >= MIN_TARGET_BMI
    }

    /** Average burn over days that have real activity data; otherwise a sedentary estimate. */
    fun maintenance(body: Body, days: List<DayEnergy>): Double {
        val withData = days.filter { it.source != BurnSource.BMR_ONLY }.take(14)
        return if (withData.size >= 3) withData.map { it.burned }.average()
        else EnergyModel.bmr(body) * 1.2
    }

    fun baseGoal(body: Body, maintenance: Double, targetKg: Double, paceKgPerWeek: Double): BaseGoal {
        val delta = targetKg - body.weightKg
        if (abs(delta) < 0.5) return BaseGoal(maintenance, maintenance, 0.0, false, null)

        val sign = if (delta < 0) -1.0 else 1.0
        val pace = minOf(paceKgPerWeek, MAX_PACE_KG_WEEK, body.weightKg * 0.01)
        val wanted = maintenance + sign * pace * EnergyModel.KCAL_PER_KG / 7.0
        var goal = wanted
        if (sign < 0) goal = max(goal, min(maintenance, floorKcal(body)))

        val effectivePace = abs(goal - maintenance) * 7.0 / EnergyModel.KCAL_PER_KG   // kg per week
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
    fun aggregate(days: List<DayEnergy>, n: Int, baseKcal: Double): Aggregate {
        val used = days.take(n).filter { it.logComplete }
        return Aggregate(
            days = used.size,
            underGoal = if (baseKcal > 0) used.sumOf { baseKcal - it.eaten } else 0.0,
            balance = used.sumOf { it.balance }
        )
    }
}
