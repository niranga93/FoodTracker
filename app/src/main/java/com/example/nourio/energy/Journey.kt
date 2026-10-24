package com.example.nourio.energy

import android.content.Context
import com.example.nourio.data.MealDb
import com.example.nourio.data.WeightDb
import com.example.nourio.data.WeightEntry
import com.example.nourio.dayStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Estimated weight on the morning of day [offset] (0 = today), from food and burn alone. */
data class EstimatePoint(val offset: Int, val kg: Double, val logged: Boolean)

/**
 * The journey's weight lines. [estimated] runs the body model on food and activity, plus the logging gap
 * the weigh-ins have shown once it's reliable ([gapKcal]); [logOnly] is the same without that correction.
 * [actual] is the weigh-ins as morning weights (an evening reading is adjusted by the learned daytime rise).
 */
data class JourneySeries(
    val startOffset: Int,
    val startKg: Double,
    val estimated: List<EstimatePoint>,
    val actual: List<Pair<Double, Double>>,   // (day offset with fraction, kg as a morning weight), oldest first
    val unloggedDays: Int,
    val logOnly: List<EstimatePoint> = estimated,
    val gapKcal: Double? = null,              // kcal/day added to the log, or null when not used
    val trendKg: Double? = null,              // smoothed weight today, from all weigh-ins
    val riseKg: Double? = null,               // learned daytime rise by midnight
) {
    val estimatedNow: Double get() = estimated.lastOrNull()?.kg ?: startKg
    val actualNow: Double? get() = trendKg ?: actual.lastOrNull()?.second

    /**
     * How fast the scale is moving, kg a week: a straight-line fit through the morning weigh-ins of the last
     * [days] days. Null with fewer than 4 weigh-ins or under a week between first and last.
     */
    fun scaleKgPerWeek(days: Int = 21): Double? {
        val pts = actual.filter { it.first >= -days }
        if (pts.size < 4 || pts.last().first - pts.first().first < 7) return null
        val mx = pts.sumOf { it.first } / pts.size
        val my = pts.sumOf { it.second } / pts.size
        val sxx = pts.sumOf { (it.first - mx) * (it.first - mx) }
        return pts.sumOf { (it.first - mx) * (it.second - my) } / sxx * 7
    }

    /** How fast the log alone (food − burn, without the learned gap) moves over the same days, kg a week. */
    fun estimatedKgPerWeek(days: Int = 21): Double? {
        val pts = logOnly.filter { it.offset >= -days }
        if (pts.size < 2) return null
        val span = pts.last().offset - pts.first().offset
        return if (span < 3) null else (pts.last().kg - pts.first().kg) / span * 7
    }
}

object JourneyModel {
    const val MAX_DAYS = 180

    /**
     * Where the journey starts: the first weigh-in, or for a longer history the last weigh-in on or before
     * the day [MAX_DAYS] ago. Returns (day offset, kg).
     */
    internal fun start(weights: List<WeightEntry>, now: Long = System.currentTimeMillis()): Pair<Int, Double>? {
        if (weights.isEmpty()) return null
        val sorted = weights.sortedBy { it.ts }
        val cutoff = dayStart(-MAX_DAYS)
        val first = sorted.lastOrNull { it.ts < cutoff + 86_400_000L } ?: sorted.first()
        val offset = (-MAX_DAYS..0).firstOrNull { o -> first.ts < dayStart(o + 1) } ?: 0
        return offset to first.kg
    }

    /**
     * Builds the lines. Null without a weigh-in. The gap is used only once the weigh-ins show it clearly (see below).
     */
    suspend fun load(context: Context, db: MealDb, body: Body): JourneySeries? {
        val weights = withContext(Dispatchers.IO) { WeightDb(context).all() }
        if (weights.isEmpty()) return null
        val h = BodyData.history(context, db, body, weights, MAX_DAYS)
        val r = WeightFilter.run(h.start, h.days, h.base, h.resting)
        val span = h.readings.keys.filter { it >= h.startOffset }.let { if (it.isEmpty()) 0 else it.max() - it.min() }

        // The estimate is a trend: fat and lean tissue plus the usual body water, without the day-to-day
        // glycogen and salt water swings (those show in the weigh-ins, not in what food and burn add up to)
        val usualWater = h.start.weightKg - h.start.tissueKg
        fun line(extra: Double): List<EstimatePoint> {
            var state = h.start
            val out = ArrayList<EstimatePoint>(h.offsets.size + 1)
            out += EstimatePoint(h.startOffset, state.tissueKg + usualWater, true)
            h.offsets.forEach { o ->
                val input = h.inputs.getValue(o)
                // The gap goes in as food the log missed; a day without a log already assumes balance
                val withGap = if (input.eatenKcal != null) input.copy(eatenKcal = input.eatenKcal + extra) else input
                state = BodyModel.step(state, withGap, h.base, h.resting).next
                out += EstimatePoint(o + 1, state.tissueKg + usualWater, input.eatenKcal != null)
            }
            return out
        }
        val raw = line(0.0)
        val observed = h.readings.filterKeys { it >= h.startOffset }.flatMap { (o, rs) ->
            rs.map { Obs(o, r.morning(it), WeightFilter.SCALE_SD + WeightFilter.LATE_EXTRA_SD * it.late) }
        }
        // The gap is the weigh-in filter's (the same figure the weight page and tips use), once it's clear:
        // 5+ weigh-ins over a week or more, and at least twice its own uncertainty. Only the starting level is
        // fitted here, so the line meets the weigh-ins.
        val gap = r.biasKcal.takeIf { observed.size >= 5 && span >= 7 && kotlin.math.abs(it) > 2 * r.biasSd }
        val withGap = if (gap != null) line(gap) else raw
        val level = fitLevel(withGap, observed)
        val logOnly = raw.shifted(level)
        val estimated = withGap.shifted(level)

        val today = dayStart(0)
        val now = System.currentTimeMillis()
        val actual = weights.sortedBy { it.ts }
            .filter { it.ts >= dayStart(h.startOffset) && it.ts <= now + 60_000 }
            .map { w ->
                val late = BodyData.readings(listOf(w)).values.first().first()
                ((w.ts - today) / 86_400_000.0) to r.morning(late)
            }
        return JourneySeries(
            h.startOffset, h.start.weightKg, estimated, actual, h.inputs.values.count { it.eatenKcal == null },
            logOnly = logOnly, gapKcal = gap, trendKg = r.weightKg, riseKg = r.riseKgOrNull(),
        )
    }

    internal data class Obs(val offset: Int, val kg: Double, val sd: Double)

    private fun List<EstimatePoint>.shifted(kg: Double) = map { it.copy(kg = it.kg + kg) }

    /**
     * The starting level that best fits the weigh-ins: the first weigh-in is one noisy reading, often with
     * water or food in it. Shrunk toward no shift by the filter's prior, so a few weigh-ins can't pull it far.
     */
    internal fun fitLevel(base: List<EstimatePoint>, obs: List<Obs>): Double {
        val b0 = base.associate { it.offset to it.kg }
        var a = 1 / (WeightFilter.OFFSET_PRIOR_SD * WeightFilter.OFFSET_PRIOR_SD); var y = 0.0
        obs.forEach { o -> val m = b0[o.offset] ?: return@forEach; val w = 1 / (o.sd * o.sd); a += w; y += w * (o.kg - m) }
        return y / a
    }

    private fun WeightFilter.Result.riseKgOrNull(): Double? = riseKg.takeIf { weighIns >= 3 }
}
