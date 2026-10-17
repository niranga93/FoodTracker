package com.example.nourio.export

import android.content.Context
import com.example.nourio.data.GoalStore
import com.example.nourio.data.Goals
import com.example.nourio.data.MealDb
import com.example.nourio.data.ProfileStore
import com.example.nourio.data.Totals
import com.example.nourio.data.WeightDb
import com.example.nourio.data.WeightEntry
import com.example.nourio.data.totals
import com.example.nourio.energy.CalibrationStore
import com.example.nourio.energy.EnergyModel
import com.example.nourio.energy.EnergyRepo
import com.example.nourio.health.SleepRepo
import com.example.nourio.loadDays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One finished day in the report. Activity and sleep are null when Health Connect had nothing for the day. */
data class ReportDay(
    val date: LocalDate,
    val totals: Totals,
    val meals: Int,
    val steps: Long? = null,
    val activeKcal: Double? = null,
    val burned: Double? = null,      // estimated total burn, calibrated the same way as Home
    val sleepH: Double? = null,
    val bedtime: Double? = null,     // hour; after midnight counts past 24
    val wakeTime: Double? = null
) {
    /** A day with too little food logged is treated as not logged, so it can't drag the averages down. */
    val logged: Boolean get() = totals.kcal >= EnergyModel.MIN_LOGGED_KCAL
}

/**
 * The last 30 finished days of nutrition, weight, activity and sleep, summarised for a doctor or
 * dietitian. Every number here is worked out on the phone; nothing is sent anywhere.
 */
data class HealthReport(
    val name: String,
    val age: Int?,
    val sex: String,
    val heightCm: Double,
    val generated: LocalDate,
    val days: List<ReportDay>,           // oldest first
    val goals: Goals,
    val weights: List<WeightEntry>,      // weigh-ins in the period, oldest first
    val zone: ZoneId = ZoneId.systemDefault()
) {
    val from: LocalDate get() = days.first().date
    val to: LocalDate get() = days.last().date
    val logged: List<ReportDay> get() = days.filter { it.logged }

    /** Average daily intake over the logged days; null if none were logged. */
    val average: Totals? get() {
        val l = logged
        if (l.isEmpty()) return null
        fun avg(f: (Totals) -> Double) = l.map { f(it.totals) }.average()
        return Totals(
            kcal = avg { it.kcal }, protein = avg { it.protein }, fat = avg { it.fat }, satFat = avg { it.satFat },
            carbs = avg { it.carbs }, sugar = avg { it.sugar }, fiber = avg { it.fiber }, sodium = avg { it.sodium },
            addedSugar = avg { it.addedSugar }
        )
    }

    /** Share of energy from protein, carbohydrate and fat, in percent (4, 4 and 9 kcal per gram). */
    val macroSplit: Triple<Double, Double, Double>? get() {
        val a = average ?: return null
        val p = a.protein * 4; val c = a.carbs * 4; val f = a.fat * 9
        val sum = p + c + f
        if (sum <= 0) return null
        return Triple(p / sum * 100, c / sum * 100, f / sum * 100)
    }

    /** Logged days within 10% of the calorie goal. */
    val daysNearGoal: Int get() =
        if (goals.kcal <= 0) 0 else logged.count { kotlin.math.abs(it.totals.kcal - goals.kcal) <= goals.kcal * 0.10 }

    /** The first weigh-in of each day, oldest first, so several readings on one day count once. */
    val dailyWeights: List<Pair<LocalDate, Double>> get() =
        weights.groupBy { Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate() }
            .map { (d, ws) -> d to ws.minBy { it.ts }.kg }
            .sortedBy { it.first }

    val weightTrend: List<Double> get() = EnergyModel.trend(dailyWeights.map { it.second })

    /** Change in the smoothed trend from the first to the last weigh-in, so one salty dinner doesn't decide it. */
    val weightChange: Double? get() = weightTrend.takeIf { it.size >= 2 }?.let { it.last() - it.first() }

    /** kg per week from the Theil-Sen slope; needs readings at least a week apart. */
    val weeklyRate: Double? get() {
        val dw = dailyWeights
        if (dw.size < 2 || dw.last().first.toEpochDay() - dw.first().first.toEpochDay() < 7) return null
        val pts = dw.map { it.first.toEpochDay().toDouble() to it.second }
        return EnergyModel.theilSen(pts)?.times(7)
    }

    val stepDays: List<ReportDay> get() = days.filter { (it.steps ?: 0) > 0 }
    val avgSteps: Double? get() = stepDays.takeIf { it.isNotEmpty() }?.map { it.steps!!.toDouble() }?.average()
    val daysStepGoal: Int get() = stepDays.count { it.steps!! >= goals.stepGoal }
    val avgActive: Double? get() = days.mapNotNull { it.activeKcal?.takeIf { k -> k > 0 } }.takeIf { it.isNotEmpty() }?.average()

    /** Burn and balance only on logged days, where intake is known. */
    private val balanceDays: List<ReportDay> get() = logged.filter { it.burned != null }
    val avgBurned: Double? get() = balanceDays.takeIf { it.isNotEmpty() }?.map { it.burned!! }?.average()
    val avgBalance: Double? get() = balanceDays.takeIf { it.isNotEmpty() }?.map { it.totals.kcal - it.burned!! }?.average()

    val sleepNights: List<ReportDay> get() = days.filter { it.sleepH != null }
    val avgSleepH: Double? get() = sleepNights.takeIf { it.isNotEmpty() }?.map { it.sleepH!! }?.average()
    val avgBedtime: Double? get() = sleepNights.mapNotNull { it.bedtime }.takeIf { it.isNotEmpty() }?.average()
    val avgWake: Double? get() = sleepNights.mapNotNull { it.wakeTime }.takeIf { it.isNotEmpty() }?.average()

    companion object {
        const val DAYS = 30

        /** Reads the last [DAYS] finished days (today is still under way, so it's left out). */
        suspend fun build(context: Context, db: MealDb, today: LocalDate = LocalDate.now()): HealthReport {
            val profile = ProfileStore(context).load()
            val goals = GoalStore(context).load()
            val offs = (-DAYS..-1).toList()
            val meals = withContext(Dispatchers.IO) { loadDays(db, offs) }
            val acts = runCatching { EnergyRepo.activityFor(context, offs) }.getOrDefault(emptyMap())
            // SleepRepo reads 15 nights ending on the day given
            val nights = runCatching { SleepRepo.load(context, -16).nights + SleepRepo.load(context, -1).nights }
                .getOrDefault(emptyMap())
            val body = EnergyRepo.body(profile).first
            val factor = CalibrationStore(context).factor
            val zone = ZoneId.systemDefault()

            val days = offs.map { off ->
                val dayMeals = meals[off].orEmpty()
                val totals = dayMeals.flatMap { it.items }.totals()
                val act = acts[off]
                val burned = when {
                    body != null -> EnergyRepo.build(body, off, totals.kcal, act).burned * factor
                    else -> act?.totalKcal?.takeIf { it > 0 }
                }
                val night = nights[off]
                ReportDay(
                    date = today.plusDays(off.toLong()), totals = totals, meals = dayMeals.size,
                    steps = act?.steps?.takeIf { it > 0 }, activeKcal = act?.activeKcal?.takeIf { it > 0 },
                    burned = burned, sleepH = night?.asleepH, bedtime = night?.bedtime, wakeTime = night?.wakeTime
                )
            }
            val start = today.minusDays(DAYS.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val weights = withContext(Dispatchers.IO) { WeightDb(context).all() }
                .filter { it.ts in start until end }.sortedBy { it.ts }
            return HealthReport(
                name = profile.name.trim(),
                age = profile.birthYear.takeIf { it > 0 }?.let { today.year - it },
                sex = profile.sex, heightCm = profile.heightCm,
                generated = today, days = days, goals = goals, weights = weights, zone = zone
            )
        }
    }
}
