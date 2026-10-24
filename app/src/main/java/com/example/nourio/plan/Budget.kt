package com.example.nourio.plan

import android.content.Context
import com.example.nourio.data.GoalStore
import com.example.nourio.data.MealDb
import com.example.nourio.data.ProfileStore
import com.example.nourio.data.totals
import com.example.nourio.energy.CalibrationStore
import com.example.nourio.energy.EnergyModel
import com.example.nourio.energy.EnergyRepo
import com.example.nourio.loadDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.round

data class Budget(
    val baseKcal: Double,
    val baseSource: String,
    val carryKcal: Double,
    val carryNote: String?,
    val kcalGoal: Double,              // the day's goal: the day's burn and the plan (or the base), plus carry-over
    val baseSugar: Double,             // added sugar
    val sugarGoal: Double,
    val baseFat: Double,
    val fatGoal: Double,
    val plan: BaseGoal?,
    val maintenanceKcal: Double,       // 0 without height, weight and year of birth
    val planMessage: String?,
    val agg7: Aggregate,
    val agg30: Aggregate,
    // With a target-weight plan and activity data, the day's budget follows that day's burn:
    // burn + planChange (negative while losing) + carry. Null: the budget is the base goal plus carry.
    val dayBurnKcal: Double? = null,
    val dayActivityKcal: Double = 0.0,      // the movement part of dayBurnKcal; the rest is resting
    val planChangeKcal: Double = 0.0,
    val raisedToMinimum: Boolean = false,   // the plan asked for more than is advisable and was limited
    val limit: BudgetLimit? = null,         // which limit applied
    val yesterdayTargetKcal: Double = 0.0,  // what yesterday's burn allowed at your pace (or the goal)
    val yesterdayEatenKcal: Double = 0.0,
    val yesterdayBurnKcal: Double? = null,
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
    val planChange = info?.let { it.kcal - it.maintenance } ?: 0.0
    // Yesterday is judged against what its own burn allowed at your pace (the same rule as today's budget),
    // not against the average day. Without activity data, against the average-day goal.
    val yBurn = days.firstOrNull()?.takeIf { info != null && it.source != com.example.nourio.energy.BurnSource.BMR_ONLY }?.burned
    val yTarget = when {
        base <= 0 -> 0.0
        yBurn != null -> round(yBurn + planChange).let { w ->
            if (w < yBurn) max(w, round(BudgetModel.lowestBudget(body!!, yBurn).first)) else w
        }
        else -> base
    }
    // The plan's daily deficit (or surplus) is taken from the burn of the day itself, so a more active day
    // gives more to eat and the budget, burn and plan always add up. Without activity data the average day is used.
    var dayActivity = 0.0
    val dayBurn = if (body != null && info != null) {
        val eaten = withContext(Dispatchers.IO) { loadDay(db, dayOffset).flatMap { it.items }.totals().kcal }
        val act = EnergyRepo.activityFor(context, listOf(dayOffset))[dayOffset]
        val e = EnergyRepo.build(body, dayOffset, eaten, act)
        dayActivity = e.activityKcal * factor
        (e.burned * factor).takeIf { e.source != com.example.nourio.energy.BurnSource.BMR_ONLY }
    } else null

    // Half of yesterday's difference. When the budget follows the day's burn, the safe minimum is applied once,
    // at the end, as its own visible step, so the carry itself isn't trimmed (that showed "half" as less than half).
    val carry = if (plan.carryOver) BudgetModel.carry(if (dayBurn != null) null else body, yTarget, yesterday?.kcal ?: 0.0)
    else Carry(0.0, null)
    var raised = false
    var limit: BudgetLimit? = null
    val adjusted = when {
        base <= 0 -> 0.0
        dayBurn != null -> {
            // From the rounded parts, so the rows shown (burn, pace, yesterday) add up exactly to the budget
            val wanted = (Math.round(dayBurn) + Math.round(planChange) + Math.round(carry.kcal)).toDouble()
            // A deficit is kept within 25% of the day's burn and 500 kcal, and above the safe minimum
            val (lowest, why) = BudgetModel.lowestBudget(body!!, dayBurn)
            if (wanted < dayBurn && wanted < round(lowest)) { raised = true; limit = why; round(lowest) } else wanted
        }
        else -> round(base + carry.kcal)
    }

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
        agg7 = BudgetModel.aggregate(days, 7, base, body?.let { BudgetModel.kcalPerKg(it) } ?: EnergyModel.KCAL_PER_KG),
        agg30 = BudgetModel.aggregate(days, 30, base, body?.let { BudgetModel.kcalPerKg(it) } ?: EnergyModel.KCAL_PER_KG),
        dayBurnKcal = dayBurn,
        dayActivityKcal = dayActivity,
        planChangeKcal = planChange,
        raisedToMinimum = raised,
        limit = limit,
        yesterdayTargetKcal = yTarget,
        yesterdayEatenKcal = yesterday?.kcal ?: 0.0,
        yesterdayBurnKcal = yBurn,
    )
}
