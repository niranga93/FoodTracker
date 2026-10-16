package com.example.nourio.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the assistant can look up. Gemini sees these names, labels and units, never a value.
 * [group] decides where the numbers come from on the phone.
 */
enum class AMetric(val key: String, val label: String, val unit: String, val group: Group, val describe: String) {
    KCAL("kcal", "calories eaten", "kcal", Group.FOOD, "calories eaten per day"),
    PROTEIN("protein", "protein", "g", Group.FOOD, "protein per day"),
    CARBS("carbs", "carbs", "g", Group.FOOD, "carbohydrates per day"),
    FAT("fat", "fat", "g", Group.FOOD, "fat per day"),
    SAT_FAT("sat_fat", "saturated fat", "g", Group.FOOD, "saturated fat per day"),
    SUGAR("sugar", "sugar", "g", Group.FOOD, "total sugar per day"),
    ADDED_SUGAR("added_sugar", "added sugar", "g", Group.FOOD, "added sugar per day"),
    FIBER("fiber", "fiber", "g", Group.FOOD, "fiber per day"),
    SODIUM("sodium", "sodium", "mg", Group.FOOD, "sodium per day"),
    MEALS("meals", "meals logged", "", Group.FOOD, "number of meals logged per day"),
    FIRST_MEAL("first_meal_time", "first meal", "time", Group.FOOD, "clock time of the first meal of the day"),
    LAST_MEAL("last_meal_time", "last meal", "time", Group.FOOD, "clock time of the last meal of the day"),
    EATING_WINDOW("eating_window_h", "eating window", "h", Group.FOOD, "hours from first to last meal"),
    FAST("fast_h", "overnight fast", "h", Group.FOOD, "hours from the previous day's last meal to this day's first meal"),
    FOOD_KCAL("food_kcal", "calories from that food", "kcal", Group.FOOD_ITEM, "calories per day from foods whose name contains `food`"),
    FOOD_GRAMS("food_grams", "amount of that food", "g", Group.FOOD_ITEM, "grams per day of foods whose name contains `food`"),
    FOOD_COUNT("food_count", "times eaten", "", Group.FOOD_ITEM, "how many times per day a food whose name contains `food` was logged"),
    WATER("water_ml", "water", "ml", Group.WATER, "water drunk per day"),
    WEIGHT("weight_kg", "weight", "kg", Group.WEIGHT, "morning weigh-in"),
    BURNED("kcal_burned", "calories burned", "kcal", Group.ENERGY, "estimated calories burned per day"),
    BALANCE("balance_kcal", "calorie balance", "kcal", Group.ENERGY, "eaten minus burned per day; positive is a surplus"),
    STEPS("steps", "steps", "steps", Group.HEALTH, "steps per day"),
    SLEEP("sleep_h", "sleep", "h", Group.HEALTH, "hours asleep the night before the day"),
    BEDTIME("bedtime", "bedtime", "time", Group.HEALTH, "clock time you went to bed the night before"),
    WAKE("wake_time", "wake-up time", "time", Group.HEALTH, "clock time you woke up"),
    AWAKE("awake_min", "time awake at night", "min", Group.HEALTH, "minutes awake during the night"),
    DEEP("deep_sleep_min", "deep sleep", "min", Group.HEALTH, "minutes of deep sleep"),
    REM("rem_sleep_min", "REM sleep", "min", Group.HEALTH, "minutes of REM sleep"),
    EFFICIENCY("sleep_efficiency", "sleep efficiency", "%", Group.HEALTH, "share of time in bed spent asleep"),
    RESTING_HR("resting_hr", "resting heart rate", "bpm", Group.HEALTH, "resting heart rate"),
    SYSTOLIC("systolic", "systolic blood pressure", "mmHg", Group.HEALTH, "systolic blood pressure");

    enum class Group { FOOD, FOOD_ITEM, WATER, WEIGHT, ENERGY, HEALTH }

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/** Goals a plan can refer to by name; the values stay on the phone. */
val GOAL_KEYS = listOf("kcal", "protein", "fiber", "added_sugar", "sodium", "fat", "steps", "water_ml", "sleep_h", "weight_kg")

enum class Agg(val key: String, val describe: String) {
    AVERAGE("average", "average per day over days that have data"),
    TOTAL("total", "sum over the days"),
    MINIMUM("minimum", "lowest day, with its date"),
    MAXIMUM("maximum", "highest day, with its date"),
    LATEST("latest", "most recent value, with its date (for food metrics: the last day it was eaten)"),
    EARLIEST("earliest", "first value, with its date (for food metrics: the first day it was eaten)"),
    CHANGE("change", "latest value minus earliest value in the range"),
    COUNT_DAYS("count_days", "number of days that have data and pass the filters"),
    TOP_FOODS("top_foods", "the 5 foods that gave the most calories in the range (metric is ignored)");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

enum class RangeKind(val key: String) {
    TODAY("today"), YESTERDAY("yesterday"), LAST_DAYS("last_days"), THIS_WEEK("this_week"), LAST_WEEK("last_week"),
    THIS_MONTH("this_month"), LAST_MONTH("last_month"), BETWEEN("between"), ALL_TIME("all_time");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

data class Range(val kind: RangeKind, val days: Int = 7, val from: String? = null, val to: String? = null)

/** Keep only days where another measure passes a test, e.g. sleep_h lt 6. */
data class Where(val metric: AMetric, val op: String, val value: Double, val goal: String? = null)

data class Step(
    val id: String,
    val metric: AMetric,
    val agg: Agg,
    val range: Range,
    val food: String? = null,
    val where: Where? = null,
    val weekdays: List<Int> = emptyList(),      // 1 = Monday .. 7 = Sunday
)

/** Does [x] go with [y] (the same day, or the next day when [nextDay])? */
data class Link(val id: String, val x: AMetric, val y: AMetric, val nextDay: Boolean, val range: Range)

data class Plan(
    val kind: String,                  // "data", "general", "action" or "unsupported"
    val steps: List<Step>,
    val links: List<Link>,
    val answer: String,                // template with {s1}, {l1}, {diff:s1,s2}, {goal:protein}
    val method: String,                // one sentence on how it was worked out
    val chart: String?,                // a step id to draw day by day
    val general: String?,              // a plain answer for questions that need no data
    val action: ProposedAction? = null,   // kind "action": shown as a card, run only when the user confirms
) {
    companion object {
        private fun range(o: JSONObject?): Range = Range(
            RangeKind.of(o?.optString("kind")) ?: RangeKind.LAST_DAYS,
            o?.optInt("days", 7)?.coerceIn(1, 730) ?: 7,
            o?.optString("from")?.ifBlank { null },
            o?.optString("to")?.ifBlank { null },
        )

        private fun JSONArray?.objects() = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        /** Lenient: steps naming an unknown metric or aggregation are dropped, not fatal. */
        fun parse(json: String): Plan {
            val o = JSONObject(json)
            val steps = o.optJSONArray("steps").objects().mapNotNull { s ->
                val agg = Agg.of(s.optString("agg")) ?: return@mapNotNull null
                val metric = AMetric.of(s.optString("metric")) ?: if (agg == Agg.TOP_FOODS) AMetric.KCAL else return@mapNotNull null
                val where = s.optJSONObject("where")?.let { w ->
                    val m = AMetric.of(w.optString("metric")) ?: return@let null
                    val op = w.optString("op").takeIf { it in setOf("lt", "le", "gt", "ge") } ?: return@let null
                    val goal = w.optString("goal").ifBlank { null }
                    if (!w.has("value") && goal == null) null else Where(m, op, w.optDouble("value", 0.0), goal)
                }
                val weekdays = s.optJSONArray("weekdays")?.let { a -> (0 until a.length()).map { a.optInt(it) }.filter { it in 1..7 } }.orEmpty()
                Step(s.optString("id"), metric, agg, range(s.optJSONObject("range")),
                    s.optString("food").trim().ifBlank { null }, where, weekdays)
            }
            val links = o.optJSONArray("links").objects().mapNotNull { l ->
                Link(l.optString("id"), AMetric.of(l.optString("x")) ?: return@mapNotNull null,
                    AMetric.of(l.optString("y")) ?: return@mapNotNull null, l.optBoolean("next_day"), range(l.optJSONObject("range")))
            }
            val action = o.optJSONObject("action")?.let { a ->
                val type = ActionType.of(a.optString("type")) ?: return@let null
                val scope = DeleteScope.of(a.optString("scope"))
                val consent = Consent.of(a.optString("consent"))
                when {
                    type == ActionType.DELETE_DATA && scope == null -> null
                    type == ActionType.WITHDRAW_CONSENT && consent == null -> null
                    else -> ProposedAction(
                        type, a.optString("title").trim(), a.optString("details").trim(), scope,
                        a.optJSONObject("range")?.let { range(it) }, consent,
                    )
                }
            }
            return Plan(
                o.optString("kind", "data"), steps, links,
                o.optString("answer"), o.optString("method"),
                o.optString("chart").ifBlank { null }, o.optString("general").ifBlank { null },
                action,
            )
        }

        private fun str() = JSONObject().put("type", "STRING")
        private fun enumOf(values: List<String>) = str().put("enum", JSONArray(values))
        private val RANGE = JSONObject().put("type", "OBJECT").put(
            "properties", JSONObject()
                .put("kind", enumOf(RangeKind.entries.map { it.key }))
                .put("days", JSONObject().put("type", "INTEGER"))
                .put("from", str()).put("to", str())
        ).put("required", JSONArray().put("kind"))

        /** Gemini must answer in exactly this shape. */
        val SCHEMA: JSONObject = JSONObject().put("type", "OBJECT").put(
            "properties", JSONObject()
                .put("kind", enumOf(listOf("data", "general", "action", "unsupported")))
                .put("action", JSONObject().put("type", "OBJECT").put(
                    "properties", JSONObject()
                        .put("type", enumOf(ActionType.entries.map { it.key }))
                        .put("title", str())
                        .put("details", str())
                        .put("scope", enumOf(DeleteScope.entries.map { it.key }))
                        .put("consent", enumOf(Consent.entries.map { it.key }))
                        .put("range", RANGE)
                ).put("required", JSONArray().put("type")))
                .put("steps", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "OBJECT").put(
                    "properties", JSONObject()
                        .put("id", str())
                        .put("metric", enumOf(AMetric.entries.map { it.key }))
                        .put("agg", enumOf(Agg.entries.map { it.key }))
                        .put("range", RANGE)
                        .put("food", str())
                        .put("where", JSONObject().put("type", "OBJECT").put(
                            "properties", JSONObject()
                                .put("metric", enumOf(AMetric.entries.map { it.key }))
                                .put("op", enumOf(listOf("lt", "le", "gt", "ge")))
                                .put("value", JSONObject().put("type", "NUMBER"))
                                .put("goal", enumOf(GOAL_KEYS))
                        ))
                        .put("weekdays", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "INTEGER")))
                ).put("required", JSONArray().put("id").put("metric").put("agg").put("range"))))
                .put("links", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "OBJECT").put(
                    "properties", JSONObject()
                        .put("id", str())
                        .put("x", enumOf(AMetric.entries.map { it.key }))
                        .put("y", enumOf(AMetric.entries.map { it.key }))
                        .put("next_day", JSONObject().put("type", "BOOLEAN"))
                        .put("range", RANGE)
                ).put("required", JSONArray().put("id").put("x").put("y").put("range"))))
                .put("answer", str())
                .put("method", str())
                .put("chart", str())
                .put("general", str())
        ).put("required", JSONArray().put("kind").put("answer"))
    }
}
