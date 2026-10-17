package com.example.nourio.reminders

import com.example.nourio.data.Goals
import com.example.nourio.data.Totals
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * What the nutrition check-ins say, worked out from the log alone (no Android code, so it is tested
 * directly). Each check-in gives one concrete, food-based suggestion at a time it can still be acted on,
 * and never suggests skipping a meal or eating below a healthy amount.
 */
object NutritionNudges {
    data class Text(val title: String, val body: String)

    private val nf: NumberFormat = NumberFormat.getIntegerInstance()
    private fun n(x: Double) = nf.format(x.roundToInt())

    /** A day counts as logged, for the week, from this many kcal (the same bar as the rest of the app). */
    const val LOGGED_KCAL = 800.0
    /** Protein the body uses well in one meal; a single dinner shouldn't be asked to carry more. */
    private const val MEAL_PROTEIN_MAX = 40.0

    /**
     * 5 pm: the single most useful change for dinner, from what has been eaten so far today.
     * Order follows health impact: a limit already close (sodium, added sugar), then what's short
     * (protein, fibre), then the calories left. Null when the day looks fine or too little is logged.
     */
    fun dinnerPlan(t: Totals, g: Goals): Text? {
        if (t.kcal < 300) return null       // too little logged to judge, or still fasting
        val left = g.kcal - t.kcal

        if (g.sodiumMg > 0 && t.sodium >= g.sodiumMg * 0.85) return Text(
            "Make dinner a low-salt one",
            "You've had ${n(t.sodium)} of ${n(g.sodiumMg)} mg sodium today. Cook from fresh, go easy on soy sauce, stock " +
                "cubes, processed meat and instant noodles, and add potassium-rich foods like beans, leafy greens or potatoes."
        )
        if (g.addedSugar > 0 && t.addedSugar >= g.addedSugar * 0.8) return Text(
            "Keep tonight's sweet fruit-based",
            "You've had ${n(t.addedSugar)} of ${n(g.addedSugar)} g added sugar. If you want dessert, fruit or plain yoghurt " +
                "with berries keeps you under the limit. Sugar in whole fruit doesn't count towards it."
        )
        if (g.protein > 0 && t.protein < g.protein * 0.5) {
            val aim = (g.protein - t.protein).coerceAtMost(MEAL_PROTEIN_MAX)
            return Text(
                "Build dinner around protein",
                "You're at ${n(t.protein)} of ${n(g.protein)} g protein. Aim for about ${n(aim)} g at dinner: 150 g chicken or " +
                    "fish (about 35 g), 200 g firm tofu (about 30 g), or a cup of lentils with 2 eggs (about 30 g)."
            )
        }
        if (g.fiber > 0 && t.fiber < g.fiber * 0.4) return Text(
            "Add vegetables or beans tonight",
            "You've had ${n(t.fiber)} of ${n(g.fiber)} g fibre. Fill half the plate with vegetables, or add a cup of beans " +
                "or lentils (about 15 g fibre). It also keeps you full for longer."
        )
        if (g.kcal > 0 && left < g.kcal * 0.2) return Text(
            if (left > 0) "About ${n(left)} kcal left for dinner" else "Today's calories are used up",
            "A light, filling dinner works best: lean protein with a big portion of vegetables or a broth-based soup. " +
                "If you're hungry, eat. One day over doesn't undo a good week."
        )
        return null
    }

    /**
     * Sunday evening: the past week's averages against the goals, what went well, and one focus.
     * [days] are the daily totals of the last 7 finished days; null when fewer than 4 were logged.
     */
    fun weeklyReview(days: List<Totals>, g: Goals): Text? {
        val logged = days.filter { it.kcal >= LOGGED_KCAL }
        if (logged.size < 4) return null
        fun avg(f: (Totals) -> Double) = logged.map(f).average()
        val kcal = avg { it.kcal }

        // How far each nutrient is from its goal, as a share; the largest miss becomes the focus
        data class Item(val miss: Double, val good: String, val focus: String)
        val items = buildList {
            if (g.protein > 0) avg { it.protein }.let { v ->
                add(Item((1 - v / g.protein).coerceAtLeast(0.0), "protein averaged ${n(v)} g, on target",
                    "protein averaged ${n(v)} of ${n(g.protein)} g. Add a protein food to breakfast, like eggs, Greek yoghurt or tofu"))
            }
            if (g.fiber > 0) avg { it.fiber }.let { v ->
                add(Item((1 - v / g.fiber).coerceAtLeast(0.0), "fibre averaged ${n(v)} g, on target",
                    "fibre averaged ${n(v)} of ${n(g.fiber)} g. Add a portion of beans, lentils or whole grains to lunch"))
            }
            if (g.sodiumMg > 0) avg { it.sodium }.let { v ->
                add(Item((v / g.sodiumMg - 1).coerceAtLeast(0.0), "sodium stayed under the limit",
                    "sodium averaged ${n(v)} mg against a ${n(g.sodiumMg)} mg limit. Swap one packaged or takeaway meal for home-cooked"))
            }
            if (g.addedSugar > 0) avg { it.addedSugar }.let { v ->
                add(Item((v / g.addedSugar - 1).coerceAtLeast(0.0), "added sugar stayed under the limit",
                    "added sugar averaged ${n(v)} g against ${n(g.addedSugar)} g. Swap sweet drinks for water, tea or sparkling water"))
            }
        }
        val focus = items.filter { it.miss >= 0.15 }.maxByOrNull { it.miss }
        val good = items.filter { it.miss < 0.05 }.map { it.good }

        val energy = if (g.kcal > 0) " averaging ${n(kcal)} kcal a day (goal ${n(g.kcal)})" else " averaging ${n(kcal)} kcal a day"
        val body = buildString {
            append("${logged.size} of ${days.size} days logged,$energy.")
            if (good.isNotEmpty()) append(" Going well: ${good.take(2).joinToString(" and ")}.")
            append(if (focus != null) " Focus for next week: ${focus.focus}." else " Keep it up: every nutrient was close to its goal.")
        }
        return Text("Your week in food", body)
    }

    /**
     * Morning weigh-in nudge, for people who weigh in, once a week has passed since the last reading.
     * [weighIns] are timestamps, oldest first.
     */
    fun weighIn(weighIns: List<Long>, now: Long): Text? {
        if (weighIns.size < 2) return null                          // not someone who tracks weight
        val days = ((now - weighIns.last()) / 86_400_000L).toInt()
        if (days < 7) return null
        return Text(
            "Time for your weekly weigh-in",
            "Your last weigh-in was $days days ago. For readings you can compare, weigh in now: after the toilet, " +
                "before eating or drinking, in similar clothes. Day-to-day changes are mostly water; the weekly trend is what counts."
        )
    }
}
