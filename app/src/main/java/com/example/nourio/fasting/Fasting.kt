package com.example.nourio.fasting

import android.content.Context
import androidx.core.content.edit
import com.example.nourio.data.Meal
import com.example.nourio.insights.DayMetrics
import com.example.nourio.insights.Metric
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One day's fasting: the fast that ended with the day's first meal, and the eating window. */
data class FastDay(
    val date: LocalDate,
    val fastH: Double?,        // null: no meal that day, or the gap before it looks like a day without logging
    val windowH: Double?,      // first to last meal; null without meals
    val firstMeal: Long?,
    val lastMeal: Long?,
    val meals: Int,
)

data class Stage(val title: String, val text: String)

data class FastStats(
    val avgFastH: Double?,
    val avgWindowH: Double?,
    val daysAtGoal: Int,
    val daysWithFast: Int,
    val streak: Int,           // finished days in a row at the goal, counting back from yesterday
    val longestH: Double?,
)

/** Days at the fasting goal against other days, from the same data the Insights page uses. */
data class FastImpact(
    val goalH: Double,
    val atGoal: Group,
    val below: Group,
) {
    data class Group(val days: Int, val kcal: Double?, val weightChangeKg: Double?, val weightDays: Int)
}

/**
 * Fasting from the meal log. A meal under [MIN_MEAL_KCAL] (black coffee, tea, a splash of milk) doesn't
 * end a fast. Gaps over [MAX_FAST_H] are left out of the numbers: they are almost always a day or a meal
 * that wasn't logged, not a real fast.
 */
object Fasting {
    const val MIN_MEAL_KCAL = 50.0
    const val MAX_FAST_H = 30.0
    val GOALS = listOf(12.0, 14.0, 16.0, 18.0, 20.0)

    private const val HOUR_MS = 3_600_000.0

    /** Times of the meals that break a fast, oldest first. */
    fun eatingTimes(meals: List<Meal>): List<Long> =
        meals.filter { it.totals.kcal >= MIN_MEAL_KCAL }.map { it.timestamp }.sorted()

    fun hours(fromMs: Long, toMs: Long) = (toMs - fromMs) / HOUR_MS

    /** Each day from [from] to [to], from [times] (which should start a day or two before [from]). */
    fun days(times: List<Long>, from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<FastDay> {
        val byDate = times.groupBy { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val out = ArrayList<FastDay>()
        var d = from
        while (!d.isAfter(to)) {
            val day = byDate[d].orEmpty()
            val first = day.firstOrNull()
            val fast = first?.let { f ->
                times.lastOrNull { it < f }?.let { hours(it, f) }?.takeIf { it in 0.0..MAX_FAST_H }
            }
            out += FastDay(
                date = d,
                fastH = fast,
                windowH = if (day.isEmpty()) null else hours(day.first(), day.last()),
                firstMeal = first,
                lastMeal = day.lastOrNull(),
                meals = day.size,
            )
            d = d.plusDays(1)
        }
        return out
    }

    /**
     * What the body is roughly doing this long after eating. Timings vary from person to person,
     * with the last meal's size and with activity (Anton et al., Obesity 2018; Longo & Mattson 2014).
     */
    fun stage(h: Double): Stage = when {
        h < 4 -> Stage("Digesting", "Your last meal is being digested. Blood sugar and insulin are up, and your body runs on that food.")
        h < 12 -> Stage("Settling", "Insulin is falling and your body draws on the sugar stored in your liver (glycogen).")
        h < 18 -> Stage("Switching to fat", "Liver glycogen is running low, so your body burns more fat and starts making ketones.")
        h < 24 -> Stage("Deeper fast", "Fat is now the main fuel and ketone levels keep rising. Drink water.")
        else -> Stage("Long fast", "Over a day without food. Fasts this long aren't for everyone: check with a doctor if you do them often.")
    }

    fun stats(days: List<FastDay>, goalH: Double, today: LocalDate): FastStats {
        val finished = days.filter { it.date < today }
        val fasts = finished.mapNotNull { it.fastH }
        val windows = finished.filter { it.meals >= 2 }.mapNotNull { it.windowH }
        var streak = 0
        for (d in finished.sortedByDescending { it.date }) {
            if ((d.fastH ?: break) >= goalH) streak++ else break
        }
        return FastStats(
            avgFastH = fasts.takeIf { it.isNotEmpty() }?.average(),
            avgWindowH = windows.takeIf { it.isNotEmpty() }?.average(),
            daysAtGoal = fasts.count { it >= goalH },
            daysWithFast = fasts.size,
            streak = streak,
            longestH = fasts.maxOrNull(),
        )
    }

    /**
     * Calories on the day a fast ended, and the weight change from that morning to the next, on days the
     * fast reached [goalH] versus days it didn't. Only finished days with a full food log count.
     */
    fun impact(days: List<DayMetrics>, goalH: Double): FastImpact {
        fun group(list: List<DayMetrics>): FastImpact.Group {
            val kcal = list.mapNotNull { it[Metric.KCAL] }
            val weight = list.mapNotNull { it[Metric.WEIGHT_CHANGE] }
            return FastImpact.Group(
                days = list.size,
                kcal = kcal.takeIf { it.size >= 3 }?.average(),
                weightChangeKg = weight.takeIf { it.size >= 3 }?.average(),
                weightDays = weight.size,
            )
        }
        val withFast = days.filter { it[Metric.FAST] != null }
        return FastImpact(
            goalH,
            group(withFast.filter { it[Metric.FAST]!! >= goalH }),
            group(withFast.filter { it[Metric.FAST]!! < goalH }),
        )
    }

    /** "13 h 25 m" */
    fun duration(h: Double): String {
        val totalMin = (h * 60).toLong().coerceAtLeast(0)
        return if (totalMin < 60) "$totalMin m" else "${totalMin / 60} h ${totalMin % 60} m"
    }
}

/** The fasting goal in hours: 12, 14, 16, 18 or 20. */
class FastingPrefs(context: Context) {
    private val p = context.getSharedPreferences("fasting", Context.MODE_PRIVATE)

    var goalH: Double
        get() = p.getFloat("goal", 14f).toDouble()
        set(v) = p.edit { putFloat("goal", v.toFloat()) }
}
