package com.example.nourio.plan

import android.content.Context
import com.example.nourio.data.GoalStore
import com.example.nourio.data.MealDb
import com.example.nourio.data.ProfileStore
import com.example.nourio.data.totals
import com.example.nourio.energy.CalibrationStore
import com.example.nourio.energy.EnergyRepo
import com.example.nourio.loadDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.round

data class Budget(
    val baseKcal: Double,
    val baseSource: String,
    val carryKcal: Double,
    val carryNote: String?,
    val kcalGoal: Double,              // today's goal: base plus carry-over
    val baseSugar: Double,             // added sugar
    val sugarGoal: Double,
    val baseFat: Double,
    val fatGoal: Double,
    val plan: BaseGoal?,
    val maintenanceKcal: Double,       // 0 without height, weight and year of birth
    val planMessage: String?,
    val agg7: Aggregate,
    val agg30: Aggregate
)

suspend fun computeBudget(context: Context, db: MealDb, goalStore: GoalStore, dayOffset: Int): Budget {
    val goals = goalStore.load()
    val plan = PlanStore(context)
    val (body, _) = EnergyRepo.body(ProfileStore(context).load())
    val factor = CalibrationStore(context).factor

    // Up to 30 completed days before the viewed day, newest first
    val offsets = (1..30).map { dayOffset - it }
    val acts = EnergyRepo.activityFor(context, offsets)
    val totalsByOffset = withContext(Dispatchers.IO) {
        val mealsByOffset = com.example.nourio.loadDays(db, offsets)
        offsets.associateWith { off -> mealsByOffset[off].orEmpty().flatMap { it.items }.totals() }
    }
    val days = if (body == null) emptyList() else offsets.map { off ->
        val raw = EnergyRepo.build(body, off, totalsByOffset.getValue(off).kcal, acts[off])
        raw.copy(burned = raw.burned * factor)
    }

    val maintenance = body?.let { BudgetModel.maintenance(it, days) } ?: 0.0

    // Base calorie goal: from the target weight plan, otherwise the manual goal
    var base = goals.kcal
    var source = if (goals.kcal > 0) "your goal" else ""
    var info: BaseGoal? = null
    var planMessage: String? = null
    if (body != null && plan.targetKg > 0 && plan.useTargetGoal) {
        if (!BudgetModel.targetBmiOk(plan.targetKg, body.heightCm)) {
            planMessage = "Your target weight is below a healthy BMI range, so it isn't used. " +
                "Choose a higher target or talk to a doctor."
        } else {
            val g = BudgetModel.baseGoal(
                body, maintenance, plan.targetKg, plan.paceKgPerWeek
            )
            info = g
            base = g.kcal
            source = "target-weight plan"
            if (g.clamped) planMessage = "Pace reduced to keep your goal at a safe minimum."
        }
    }
    if (base > 0) base = round(base / 10.0) * 10.0

    // Carry-over from yesterday
    val yesterday = totalsByOffset[dayOffset - 1]
    val carry = if (plan.carryOver) BudgetModel.carry(body, base, yesterday?.kcal ?: 0.0) else Carry(0.0, null)
    val adjusted = if (base > 0) round(base + carry.kcal) else 0.0

    val yLogged = (yesterday?.kcal ?: 0.0) >= BudgetModel.MIN_VALID_YESTERDAY_KCAL
    val sugarCut = if (plan.carryOver) BudgetModel.macroReduction(goals.addedSugar, yesterday?.addedSugar ?: 0.0, yLogged) else 0.0
    val fatCut = if (plan.carryOver) BudgetModel.macroReduction(goals.fat, yesterday?.fat ?: 0.0, yLogged) else 0.0

    return Budget(
        baseKcal = base,
        baseSource = source,
        carryKcal = carry.kcal,
        carryNote = carry.note,
        kcalGoal = adjusted,
        baseSugar = goals.addedSugar,
        sugarGoal = goals.addedSugar - sugarCut,
        baseFat = goals.fat,
        fatGoal = goals.fat - fatCut,
        plan = info,
        maintenanceKcal = maintenance,
        planMessage = planMessage,
        agg7 = BudgetModel.aggregate(days, 7, base),
        agg30 = BudgetModel.aggregate(days, 30, base)
    )
}
