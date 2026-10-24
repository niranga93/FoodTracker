package com.example.nourio.energy

import android.content.Context
import com.example.nourio.data.MealDb
import com.example.nourio.data.WeightEntry
import com.example.nourio.data.totals
import com.example.nourio.dayStart
import com.example.nourio.loadDays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor

/** Turns the meal log, Health Connect and the profile into [BodyModel] inputs. */
object BodyData {
    /** Each finished day's food (null when not logged fully) and activity above resting. */
    suspend fun inputs(context: Context, db: MealDb, body: Body, offsets: List<Int>): Map<Int, BodyModel.DayInput> {
        if (offsets.isEmpty()) return emptyMap()
        val meals = withContext(Dispatchers.IO) { loadDays(db, offsets) }
        val acts = EnergyRepo.activityFor(context, offsets)
        val totals = offsets.associateWith { o -> meals[o].orEmpty().flatMap { it.items }.totals() }
        val complete = completeDays(totals.mapValues { it.value.kcal })
        // Logged sodium is noisy (photo estimates, foods without sodium data): the water it holds follows a
        // 3-day average of complete days, which is also closer to how the kidneys respond
        val sodium = offsets.associateWith { o ->
            (o - 2..o).filter { it in complete }.mapNotNull { d -> totals[d]?.sodium?.takeIf { it > 0 } }
                .takeIf { it.isNotEmpty() }?.average()
        }
        val carbs = offsets.associateWith { o ->
            (o - 2..o).filter { it in complete }.mapNotNull { d -> totals[d]?.carbs?.takeIf { it > 0 } }
                .takeIf { it.isNotEmpty() }?.average()
        }
        return offsets.associateWith { o ->
            val t = totals.getValue(o)
            val burn = EnergyRepo.build(body, o, t.kcal, acts[o])
            val logged = o in complete
            BodyModel.DayInput(
                eatenKcal = if (logged) t.kcal else null,
                proteinG = t.protein, carbsG = t.carbs, fatG = t.fat,
                sodiumMg = if (logged) sodium[o] else null,
                activityKcal = (burn.burned - burn.resting).coerceAtLeast(0.0),
                waterCarbsG = if (logged) carbs[o] else null,
            )
        }
    }

    /**
     * Days whose log looks complete: at least 800 kcal and at least 60% of the person's usual (median) logged
     * day. A day with only lunch logged would otherwise read as a huge deficit.
     */
    internal fun completeDays(kcal: Map<Int, Double>): Set<Int> {
        val logged = kcal.values.filter { it >= EnergyModel.MIN_LOGGED_KCAL }.sorted()
        val usual = if (logged.size >= 5) logged[logged.size / 2] else 0.0
        val floor = maxOf(EnergyModel.MIN_LOGGED_KCAL, 0.6 * usual)
        return kcal.filterValues { it >= floor }.keys
    }

    /**
     * Usual intake: maintenance at the start (resting + typical activity + digestion), the average carbohydrate
     * of logged days, and the median logged sodium (3,400 mg, a typical adult intake, when there's none).
     */
    fun baseline(start: BodyModel.State, resting: BodyModel.Resting, days: Collection<BodyModel.DayInput>): BodyModel.Baseline {
        val activity = days.map { it.activityKcal }.average().takeIf { !it.isNaN() } ?: 0.0
        val maintenance = (resting.at(start) + activity) / (1 - BodyModel.TEF_MIXED)
        val logged = days.filter { it.eatenKcal != null }
        val carbs = logged.map { it.carbsG * 4 }.filter { it > 0 }.average().takeIf { !it.isNaN() } ?: (0.45 * maintenance)
        val sodium = logged.mapNotNull { it.sodiumMg }.sorted().let { if (it.isEmpty()) 3400.0 else it[it.size / 2] }
        return BodyModel.Baseline(maintenance, carbs.coerceAtLeast(200.0), sodium)
    }

    /** Day offset (0 = today) of a reading. */
    fun offsetOf(ts: Long): Int = floor((ts - dayStart(0)) / 86_400_000.0).toInt()

    /** Before 4 am still belongs to the evening before: the day's food is in it, not yet slept off. */
    private const val DAY_TURNS_H = 4.0
    private const val WAKE_H = 6.0

    /**
     * Each weigh-in, by the day it belongs to, with how late in that waking day it was (0 at 6 am or earlier,
     * 1 at midnight or later), so an evening reading isn't mistaken for a morning one.
     */
    fun readings(weights: List<WeightEntry>): Map<Int, List<WeightFilter.Reading>> =
        weights.sortedBy { it.ts }.groupBy({ w ->
            val o = offsetOf(w.ts)
            if (hourOf(w.ts, o) < DAY_TURNS_H) o - 1 else o
        }) { w ->
            val o = offsetOf(w.ts)
            var h = hourOf(w.ts, o)
            if (h < DAY_TURNS_H) h += 24
            WeightFilter.Reading(w.kg, ((h - WAKE_H) / (24 - WAKE_H)).coerceIn(0.0, 1.0))
        }

    private fun hourOf(ts: Long, offset: Int): Double = (ts - dayStart(offset)) / 3_600_000.0

    /**
     * Each weigh-in as a morning weight, in time order, using the usual daytime rise. For display; the
     * forecast learns the person's own rise.
     */
    fun morningWeights(weights: List<WeightEntry>): List<Double> =
        weights.sortedBy { it.ts }.map { w ->
            val r = readings(listOf(w)).values.first().first()
            r.kg - WeightFilter.RISE_PRIOR_KG * r.late
        }

    /** The first reading of each day, by day offset (see [readings]). */
    fun mornings(weights: List<WeightEntry>): Map<Int, Double> =
        readings(weights).mapValues { it.value.first().kg }

    /** Everything the forecast and the journey need, from one place so they always agree. */
    class History(
        val startOffset: Int,
        val start: BodyModel.State,
        val resting: BodyModel.Resting,
        val base: BodyModel.Baseline,
        val offsets: List<Int>,                         // finished days, oldest first
        val inputs: Map<Int, BodyModel.DayInput>,
        val readings: Map<Int, List<WeightFilter.Reading>>,
    ) {
        /** Filter days: each finished day with its weigh-ins, then today's weigh-ins. */
        val days: List<WeightFilter.Day> get() =
            offsets.map { o -> WeightFilter.Day(inputs[o], if (o == startOffset) emptyList() else readings[o].orEmpty()) } +
                WeightFilter.Day(null, readings[0].orEmpty())
        val weighIns: Int get() = readings.filterKeys { it >= startOffset }.values.sumOf { it.size }
    }

    /**
     * From the last weigh-in on or before [maxDays] ago (or the first weigh-in after it) up to today. The
     * starting weight is taken as a morning one, so an evening start doesn't bias everything after it.
     */
    suspend fun history(context: Context, db: MealDb, body: Body, weights: List<WeightEntry>, maxDays: Int): History {
        val readings = readings(weights)
        val before = readings.filterKeys { it <= -maxDays }.maxByOrNull { it.key }
        val firstIn = readings.filterKeys { it > -maxDays && it < 0 }.minByOrNull { it.key }
        val (startOffset, startReading) = when {
            before != null -> -maxDays to before.value.first()
            firstIn != null -> firstIn.key to firstIn.value.first()
            else -> -28 to WeightFilter.Reading(body.weightKg)
        }
        val startKg = startReading.kg - WeightFilter.RISE_PRIOR_KG * startReading.late
        val offsets = (startOffset..-1).toList()
        val startBody = body.copy(weightKg = startKg)
        val inputs = inputs(context, db, startBody, offsets)
        val start = BodyModel.start(startKg, body.heightCm, body.age, body.sex)
        val resting = BodyModel.Resting(start, EnergyModel.formulaBmr(startBody))
        return History(startOffset, start, resting, baseline(start, resting, inputs.values), offsets, inputs, readings)
    }
}
