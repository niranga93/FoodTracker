package com.example.nourio.assistant

import android.content.Context
import com.example.nourio.data.GoalStore
import com.example.nourio.data.MealDb
import com.example.nourio.data.ProfileStore
import com.example.nourio.data.WaterStore
import com.example.nourio.data.WeightDb
import com.example.nourio.data.totals
import com.example.nourio.energy.CalibrationStore
import com.example.nourio.energy.EnergyModel
import com.example.nourio.energy.EnergyRepo
import com.example.nourio.fasting.Fasting
import com.example.nourio.insights.HistoryLoader
import com.example.nourio.insights.InsightPrefs
import com.example.nourio.insights.Metric
import com.example.nourio.plan.PlanStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Reads, on the phone, only the measures a plan asks for, over the dates it covers. */
object AssistantData {
    /** Health Connect and burn estimates are read day by day, so they stop this far back. */
    private const val MAX_HEALTH_DAYS = 120

    private val HEALTH = mapOf(
        AMetric.STEPS to Metric.STEPS, AMetric.SLEEP to Metric.SLEEP, AMetric.BEDTIME to Metric.BEDTIME,
        AMetric.WAKE to Metric.WAKE_TIME, AMetric.AWAKE to Metric.AWAKE, AMetric.DEEP to Metric.DEEP_SLEEP,
        AMetric.REM to Metric.REM_SLEEP, AMetric.EFFICIENCY to Metric.SLEEP_EFFICIENCY,
        AMetric.RESTING_HR to Metric.RESTING_HR, AMetric.SYSTOLIC to Metric.SYSTOLIC,
    )

    suspend fun load(context: Context, db: MealDb, plan: Plan): Table = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val first = db.timestamps().firstOrNull()?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

        val ranges = plan.steps.map { it.range } + plan.links.map { it.range }
        val spans = ranges.map { Executor.resolve(it, today, first) }
        val from = (spans.minOfOrNull { it.first } ?: today).minusDays(1)          // the day before, for fasts
        val to = today.plusDays(1)                                                  // next-day links
        val wanted = (plan.steps.flatMap { listOfNotNull(it.metric, it.where?.metric) } +
            plan.links.flatMap { listOf(it.x, it.y) }).toSet()
        val groups = wanted.map { it.group }.toSet() + if (plan.steps.any { it.agg == Agg.TOP_FOODS }) setOf(AMetric.Group.FOOD_ITEM) else emptySet()

        val days = HashMap<AMetric, HashMap<LocalDate, Double>>()
        fun put(m: AMetric, d: LocalDate, v: Double) { days.getOrPut(m) { HashMap() }[d] = v }
        val foods = ArrayList<FoodEntry>()

        // Meals: day totals, timing and single foods
        val needMeals = groups.any { it == AMetric.Group.FOOD || it == AMetric.Group.FOOD_ITEM || it == AMetric.Group.ENERGY }
        val meals = if (needMeals) db.between(from.atStartOfDay(zone).toInstant().toEpochMilli(), to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()) else emptyList()
        meals.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }.forEach { (d, list) ->
            val t = list.flatMap { it.items }.totals()
            put(AMetric.KCAL, d, t.kcal); put(AMetric.PROTEIN, d, t.protein); put(AMetric.CARBS, d, t.carbs)
            put(AMetric.FAT, d, t.fat); put(AMetric.SAT_FAT, d, t.satFat); put(AMetric.SUGAR, d, t.sugar)
            put(AMetric.ADDED_SUGAR, d, t.addedSugar); put(AMetric.FIBER, d, t.fiber); put(AMetric.SODIUM, d, t.sodium)
            put(AMetric.MEALS, d, list.size.toDouble())
            fun hour(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).let { it.hour + it.minute / 60.0 }
            put(AMetric.FIRST_MEAL, d, hour(list.minOf { it.timestamp }))
            put(AMetric.LAST_MEAL, d, hour(list.maxOf { it.timestamp }))
            list.forEach { m -> m.items.forEach { foods += FoodEntry(d, it.name, it.grams, it.kcal) } }
        }
        if (AMetric.Group.FOOD in groups) {
            Fasting.days(Fasting.eatingTimes(meals), from.plusDays(1), today, zone).forEach { fd ->
                fd.fastH?.let { put(AMetric.FAST, fd.date, it) }
                if (fd.meals >= 2) fd.windowH?.let { put(AMetric.EATING_WINDOW, fd.date, it) }
            }
        }

        if (AMetric.Group.WATER in groups) {
            WaterStore(context).allDays().forEach { (k, ml) -> runCatching { LocalDate.parse(k) }.getOrNull()?.let { put(AMetric.WATER, it, ml.toDouble()) } }
        }
        if (AMetric.Group.WEIGHT in groups) {
            WeightDb(context).all().groupBy { Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate() }
                .forEach { (d, list) -> put(AMetric.WEIGHT, d, list.minBy { it.ts }.kg) }
        }

        val healthFrom = maxOf(from, today.minusDays(MAX_HEALTH_DAYS.toLong()))
        val healthDays = ChronoUnit.DAYS.between(healthFrom, today).toInt() + 1
        if (AMetric.Group.HEALTH in groups) {
            HistoryLoader.load(context, db, healthDays).forEach { dm ->
                HEALTH.forEach { (a, m) -> dm[m]?.let { put(a, dm.date, it) } }
            }
            // The history covers finished days; today's steps come straight from Health Connect
            EnergyRepo.activityFor(context, listOf(0))[0]?.steps?.takeIf { it > 0 }?.let { put(AMetric.STEPS, today, it.toDouble()) }
        }
        if (AMetric.Group.ENERGY in groups) {
            val body = EnergyRepo.body(ProfileStore(context).load()).first
            if (body != null) {
                val offsets = (0 until healthDays).map { -it }
                val acts = EnergyRepo.activityFor(context, offsets)
                val factor = CalibrationStore(context).factor
                offsets.forEach { off ->
                    val d = today.plusDays(off.toLong())
                    val eaten = days[AMetric.KCAL]?.get(d) ?: 0.0
                    val burned = EnergyRepo.build(body, off, eaten, acts[off]).burned * factor
                    put(AMetric.BURNED, d, burned)
                    // A balance only means something on a day with a real food log
                    if (eaten >= EnergyModel.MIN_LOGGED_KCAL || (off == 0 && eaten > 0)) put(AMetric.BALANCE, d, eaten - burned)
                }
            }
        }

        val g = GoalStore(context).load()
        val goals = mapOf(
            "kcal" to g.kcal, "protein" to g.protein, "fiber" to g.fiber, "added_sugar" to g.addedSugar,
            "sodium" to g.sodiumMg, "fat" to g.fat, "steps" to g.stepGoal.toDouble(),
            "water_ml" to WaterStore(context).goalMl.toDouble(), "sleep_h" to InsightPrefs(context).sleepGoalH,
            "weight_kg" to PlanStore(context).targetKg,
        )
        Table(today, days, foods, goals, first)
    }
}
