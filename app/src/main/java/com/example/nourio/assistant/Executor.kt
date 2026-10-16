package com.example.nourio.assistant

import com.example.nourio.insights.InsightEngine
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** One logged food, for food questions ("when did I last eat pizza?"). */
data class FoodEntry(val date: LocalDate, val name: String, val grams: Double, val kcal: Double)

/** Everything a plan needs, already read from the phone. Missing days are simply absent. */
data class Table(
    val today: LocalDate,
    val days: Map<AMetric, Map<LocalDate, Double>>,
    val foods: List<FoodEntry>,
    val goals: Map<String, Double>,
    val firstDate: LocalDate?,
)

data class StepResult(
    val text: String,
    val value: Double?,
    val metric: AMetric,
    val from: LocalDate,
    val to: LocalDate,
    val series: List<Pair<LocalDate, Double>>,
) {
    val empty get() = value == null && text.isEmpty()
}

data class Answer(
    val text: String,
    val method: String?,
    val footnote: String?,
    val chart: StepResult?,
    val missing: Boolean,         // a lookup found no data, so [text] is the app's own explanation
)

/**
 * Runs a [Plan] on the phone. Nothing here talks to the network: Gemini only wrote the plan and the
 * sentence, and the numbers are put into the sentence here.
 */
object Executor {
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

    fun resolve(r: Range, today: LocalDate, first: LocalDate?): Pair<LocalDate, LocalDate> = when (r.kind) {
        RangeKind.TODAY -> today to today
        RangeKind.YESTERDAY -> today.minusDays(1) to today.minusDays(1)
        RangeKind.LAST_DAYS -> today.minusDays(r.days.toLong() - 1) to today
        RangeKind.THIS_WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to today
        RangeKind.LAST_WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1).let { it to it.plusDays(6) }
        RangeKind.THIS_MONTH -> today.withDayOfMonth(1) to today
        RangeKind.LAST_MONTH -> today.minusMonths(1).withDayOfMonth(1).let { it to it.with(TemporalAdjusters.lastDayOfMonth()) }
        RangeKind.ALL_TIME -> (first ?: today.minusDays(365)) to today
        RangeKind.BETWEEN -> {
            val f = runCatching { LocalDate.parse(r.from) }.getOrNull()
            val t = runCatching { LocalDate.parse(r.to) }.getOrNull() ?: today
            if (f == null || f.isAfter(t)) today.minusDays(6) to today else f to minOf(t, today)
        }
    }

    // ------------------------------------------------------------ formatting

    fun hm(h: Double): String {
        val m = (abs(h) * 60).roundToLong()
        return if (m < 60) "$m min" else if (m % 60 == 0L) "${m / 60} h" else "${m / 60} h ${m % 60} min"
    }

    fun clock(h: Double): String {
        val x = ((h % 24) + 24) % 24
        val hh = x.toInt(); val mm = ((x - hh) * 60).roundToLong().toInt().coerceIn(0, 59)
        return "%d:%02d %s".format(if (hh % 12 == 0) 12 else hh % 12, mm, if (hh < 12) "am" else "pm")
    }

    fun fmt(m: AMetric, v: Double): String = when (m.unit) {
        "time" -> clock(v)
        "h" -> hm(v)
        "kg" -> "%.1f kg".format(v)
        "g" -> if (abs(v) < 10) "%.1f g".format(v) else "%,d g".format(v.roundToLong())
        "%" -> "${v.roundToLong()}%"
        "" -> "%,d".format(v.roundToLong())
        else -> "%,d %s".format(v.roundToLong(), m.unit)
    }

    private fun signed(m: AMetric, v: Double): String {
        val sign = if (v > 0) "+" else if (v < 0) "−" else ""
        return sign + when (m.unit) { "time" -> hm(abs(v)); else -> fmt(m, abs(v)) }
    }

    fun date(d: LocalDate): String = d.format(DAY)

    // ------------------------------------------------------------ lookups

    private fun foodMatches(name: String, query: String): Boolean {
        val n = name.lowercase()
        val q = query.lowercase().trim()
        return q in n || (q.length > 3 && q.endsWith("s") && q.dropLast(1) in n)
    }

    /** Day values for a step, after the date range, weekday and "where" filters. Oldest first. */
    fun series(t: Table, s: Step): List<Pair<LocalDate, Double>> {
        val (from, to) = resolve(s.range, t.today, t.firstDate)
        val base: Map<LocalDate, Double> = if (s.metric.group == AMetric.Group.FOOD_ITEM) {
            t.foods.filter { it.date in from..to && (s.food == null || foodMatches(it.name, s.food)) }
                .groupBy { it.date }
                .mapValues { (_, list) ->
                    when (s.metric) {
                        AMetric.FOOD_KCAL -> list.sumOf { it.kcal }
                        AMetric.FOOD_GRAMS -> list.sumOf { it.grams }
                        else -> list.size.toDouble()
                    }
                }
        } else t.days[s.metric].orEmpty().filterKeys { it in from..to }

        return base.filter { (d, _) ->
            (s.weekdays.isEmpty() || d.dayOfWeek.value in s.weekdays) &&
                (s.where == null || t.days[s.where.metric]?.get(d)?.let { passes(it, s.where, t) } == true)
        }.toSortedMap().map { it.key to it.value }
    }

    /** A filter against a goal uses the goal's value from the phone; an unset goal matches nothing. */
    private fun passes(v: Double, w: Where, t: Table): Boolean {
        val limit = if (w.goal != null) t.goals[w.goal]?.takeIf { it > 0 } ?: return false else w.value
        return when (w.op) { "lt" -> v < limit; "le" -> v <= limit; "gt" -> v > limit; else -> v >= limit }
    }

    fun run(t: Table, s: Step): StepResult {
        val (from, to) = resolve(s.range, t.today, t.firstDate)
        if (s.agg == Agg.TOP_FOODS) {
            val top = t.foods.filter { it.date in from..to && (s.food == null || foodMatches(it.name, s.food)) }
                .groupBy { it.name.trim().lowercase() }
                .map { (name, list) -> name to list.sumOf { it.kcal } }
                .sortedByDescending { it.second }.take(5)
            val text = top.joinToString(", ") { (n, k) -> "$n (${fmt(AMetric.KCAL, k)})" }
            return StepResult(text, top.firstOrNull()?.second, AMetric.KCAL, from, to, emptyList())
        }
        val ser = series(t, s)
        if (ser.isEmpty()) {
            return if (s.agg == Agg.COUNT_DAYS) StepResult("0 days", 0.0, s.metric, from, to, ser)
            else StepResult("", null, s.metric, from, to, ser)
        }
        val m = s.metric
        val isFood = m.group == AMetric.Group.FOOD_ITEM
        val (text, value) = when (s.agg) {
            Agg.AVERAGE -> ser.map { it.second }.average().let { fmt(m, it) to it }
            Agg.TOTAL -> ser.sumOf { it.second }.let { fmt(m, it) to it }
            Agg.MINIMUM -> ser.minBy { it.second }.let { (d, v) -> "${fmt(m, v)} on ${date(d)}" to v }
            Agg.MAXIMUM -> ser.maxBy { it.second }.let { (d, v) -> "${fmt(m, v)} on ${date(d)}" to v }
            Agg.LATEST -> ser.last().let { (d, v) -> (if (isFood) date(d) else "${fmt(m, v)} on ${date(d)}") to v }
            Agg.EARLIEST -> ser.first().let { (d, v) -> (if (isFood) date(d) else "${fmt(m, v)} on ${date(d)}") to v }
            Agg.CHANGE -> (ser.last().second - ser.first().second).let { c ->
                "${signed(m, c)} (from ${fmt(m, ser.first().second)} on ${date(ser.first().first)} to ${fmt(m, ser.last().second)} on ${date(ser.last().first)})" to c
            }
            Agg.COUNT_DAYS -> ser.size.let { (if (it == 1) "1 day" else "$it days") to it.toDouble() }
            Agg.TOP_FOODS -> "" to null
        }
        return StepResult(text, value, m, from, to, ser)
    }

    /** A plain-language reading of a link, worked out with the same statistics as the Insights page. */
    fun link(t: Table, l: Link): String? {
        val (from, to) = resolve(l.range, t.today, t.firstDate)
        val xs = t.days[l.x].orEmpty(); val ys = t.days[l.y].orEmpty()
        val pairs = xs.filterKeys { it in from..to }.mapNotNull { (d, x) ->
            ys[if (l.nextDay) d.plusDays(1) else d]?.let { x to it }
        }
        if (pairs.size < 7) return null
        val rho = InsightEngine.spearman(pairs.map { it.first }, pairs.map { it.second })
        val split = pairs.map { it.first }.sorted()[pairs.size / 2]
        val hi = pairs.filter { it.first >= split }.map { it.second }
        val lo = pairs.filter { it.first < split }.map { it.second }
        if (hi.isEmpty() || lo.isEmpty()) return null
        val strength = when { abs(rho) >= 0.6 -> "a strong"; abs(rho) >= 0.4 -> "a clear"; abs(rho) >= 0.2 -> "a weak"; else -> "no real" }
        val when_ = if (l.nextDay) "the next day" else "that day"
        return "$strength link (${pairs.size} days): when ${l.x.label} was ${fmt(l.x, split)} or more, " +
            "${l.y.label} $when_ averaged ${fmt(l.y, hi.average())}, against ${fmt(l.y, lo.average())} otherwise"
    }

    private val GOAL_METRIC = mapOf(
        "kcal" to AMetric.KCAL, "protein" to AMetric.PROTEIN, "fiber" to AMetric.FIBER, "added_sugar" to AMetric.ADDED_SUGAR,
        "sodium" to AMetric.SODIUM, "water_ml" to AMetric.WATER, "steps" to AMetric.STEPS, "sleep_h" to AMetric.SLEEP,
        "weight_kg" to AMetric.WEIGHT, "fat" to AMetric.FAT,
    )

    private fun missingText(m: AMetric, from: LocalDate, to: LocalDate): String {
        val span = if (from == to) "on ${date(from)}" else "between ${date(from)} and ${date(to)}"
        val hint = when (m.group) {
            AMetric.Group.HEALTH -> " It comes from your watch or phone through Health Connect (Profile > Connections)."
            AMetric.Group.WEIGHT -> " Log a weigh-in on the Weigh-ins page and I can track it."
            AMetric.Group.WATER -> " Log water on the Intake tab."
            AMetric.Group.ENERGY -> " Add your height, weight and year of birth in Profile so calories burned can be estimated."
            else -> ""
        }
        return "I couldn't find any ${m.label} $span.$hint"
    }

    /** Runs every step and link, then fills the answer template. */
    fun answer(t: Table, p: Plan): Answer {
        val steps = p.steps.associate { it.id to run(t, it) }
        val links = p.links.associate { it.id to link(t, it) }

        val used = Regex("\\{([^}]+)\\}").findAll(p.answer).map { it.groupValues[1] }.toList()
        val firstMissing = used.mapNotNull { key ->
            val id = key.removePrefix("diff:").split(",").first().trim()
            steps[id]?.takeIf { it.empty }
        }.firstOrNull()
        if (firstMissing != null) {
            return Answer(missingText(firstMissing.metric, firstMissing.from, firstMissing.to), p.method, null, null, true)
        }
        if (used.any { it in links && links[it] == null }) {
            return Answer("There aren't enough days with both measures yet to compare them. Give it a couple of weeks of logging.",
                p.method, null, null, true)
        }

        val text = Regex("\\{([^}]+)\\}").replace(p.answer) { mr ->
            val key = mr.groupValues[1].trim()
            when {
                key.startsWith("diff:") -> {
                    val (a, b) = key.removePrefix("diff:").split(",").map { it.trim() }.let { it[0] to it.getOrElse(1) { "" } }
                    val ra = steps[a]; val rb = steps[b]
                    if (ra?.value != null && rb?.value != null) signed(ra.metric, ra.value - rb.value) else "?"
                }
                key.startsWith("goal:") -> {
                    val g = key.removePrefix("goal:")
                    val v = t.goals[g]
                    if (v != null && v > 0) GOAL_METRIC[g]?.let { fmt(it, v) } ?: "%,d".format(v.roundToLong()) else "not set"
                }
                key in steps -> steps.getValue(key).text
                key in links -> links.getValue(key) ?: "?"
                else -> "?"
            }
        }

        val first = p.steps.firstOrNull()?.let { steps[it.id] }
        val footnote = first?.takeIf { it.series.isNotEmpty() }?.let { r ->
            val n = r.series.size
            "Based on ${if (n == 1) "1 day" else "$n days"} with data" +
                if (r.from == r.to) ", ${date(r.from)}." else ", ${date(r.from)} – ${date(r.to)}."
        }
        val chart = p.chart?.let { steps[it] }?.takeIf { it.series.size >= 2 }
        return Answer(text, p.method.ifBlank { null }, footnote, chart, false)
    }
}
