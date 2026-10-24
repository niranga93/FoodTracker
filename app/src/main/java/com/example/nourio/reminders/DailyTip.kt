package com.example.nourio.reminders

import com.example.nourio.data.Goals
import com.example.nourio.data.Totals
import java.text.NumberFormat
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One day of the past week, as the morning tip sees it. */
data class TipDay(
    val totals: Totals,
    val lateKcal: Double = 0.0,     // eaten from 8 pm on
    val waterMl: Int = 0,
)

/**
 * The 8 am tip when the Insights engine has no pattern to report: advice from the past week's log,
 * aimed at a healthier, stronger body. Every area that needs work becomes a candidate, ranked by how
 * far off it is; the day of the year rotates through the top ones so mornings don't repeat. A week
 * with nothing off gets an evidence-based habit to build on, also rotating.
 */
object DailyTip {
    data class Text(val id: String, val title: String, val body: String)

    private val nf: NumberFormat = NumberFormat.getIntegerInstance()
    private fun n(x: Double) = nf.format(x.roundToInt())

    private data class Candidate(val id: String, val score: Double, val title: String, val body: String)

    /**
     * [body]: tips from the body model and weigh-ins ([BodyTips]); they compete with the food tips on the same
     * scale, so a large logging gap outranks a small fibre shortfall.
     */
    fun pick(days: List<TipDay>, g: Goals, waterGoalMl: Int, dayOfYear: Int, lastId: String? = null, body: List<BodyTips.Tip> = emptyList()): Text {
        val logged = days.filter { it.totals.kcal >= NutritionNudges.LOGGED_KCAL }
        val bodyCandidates = body.map { Candidate(it.id, it.score, it.title, it.body) }
        val candidates = ((if (logged.size < 3) emptyList() else candidates(logged, g, waterGoalMl)) + bodyCandidates)
            .sortedByDescending { it.score }.take(3)
        val pool = candidates.ifEmpty { if (logged.size < 3) listOf(logMore(logged.size)) else evergreen() }
            .filter { it.id != lastId }.ifEmpty { candidates.ifEmpty { evergreen() } }
        val c = pool[Math.floorMod(dayOfYear, pool.size)]
        return Text(c.id, c.title, c.body)
    }

    private fun candidates(logged: List<TipDay>, g: Goals, waterGoalMl: Int): List<Candidate> = buildList {
        fun avg(f: (Totals) -> Double) = logged.map { f(it.totals) }.average()
        val kcal = avg { it.kcal }
        val days = "over your last ${logged.size} logged days"

        if (g.protein > 0) avg { it.protein }.let { v ->
            val miss = 1 - v / g.protein
            if (miss >= 0.1) add(Candidate("week_protein", miss, "Protein for a stronger body",
                "You averaged ${n(v)} of ${n(g.protein)} g protein a day $days. Muscle is built and kept by spreading protein " +
                    "through the day, about 25 to 30 g a meal, together with strength training twice a week. " +
                    "Easy wins: eggs or Greek yoghurt at breakfast, and beans, fish, chicken or tofu at lunch."))
        }
        if (g.fiber > 0) avg { it.fiber }.let { v ->
            val miss = 1 - v / g.fiber
            if (miss >= 0.15) add(Candidate("week_fibre", miss, "More fibre, steadier energy",
                "Fibre averaged ${n(v)} of ${n(g.fiber)} g $days. Fibre feeds healthy gut bacteria, keeps blood sugar " +
                    "steadier and lowers cholesterol. Try oats or whole-grain bread at breakfast, and a portion of beans, " +
                    "lentils or chickpeas most days (about 7 g per half cup)."))
        }
        if (g.sodiumMg > 0) avg { it.sodium }.let { v ->
            val over = v / g.sodiumMg - 1
            if (over >= 0.05) add(Candidate("week_sodium", over * 1.5, "Ease off the salt this week",
                "Sodium averaged ${n(v)} mg a day against a ${n(g.sodiumMg)} mg limit. Most salt comes from packaged food, " +
                    "sauces, bread and takeaways, not the salt shaker. Cooking one more meal at home, and flavouring with " +
                    "herbs, garlic, lemon or chilli, helps your blood pressure."))
        }
        if (g.addedSugar > 0) avg { it.addedSugar }.let { v ->
            val over = v / g.addedSugar - 1
            if (over >= 0.05) add(Candidate("week_sugar", over * 1.5, "Cut back on added sugar",
                "Added sugar averaged ${n(v)} g a day against ${n(g.addedSugar)} g. Sweet drinks are usually the biggest " +
                    "source: water, sparkling water, or tea and coffee without sugar make the quickest difference. " +
                    "Whole fruit is fine; its sugar doesn't count."))
        }
        if (g.satFat > 0) avg { it.satFat }.let { v ->
            val over = v / g.satFat - 1
            if (over >= 0.1) add(Candidate("week_satfat", over, "Swap to healthier fats",
                "Saturated fat averaged ${n(v)} g a day against ${n(g.satFat)} g. Swapping butter for olive oil, fatty or " +
                    "processed meat for fish, beans or poultry, and snacking on nuts instead of pastries is good for your heart."))
        }
        val lateShare = logged.sumOf { it.lateKcal } / logged.sumOf { it.totals.kcal }
        if (lateShare >= 0.25) add(Candidate("week_late", lateShare, "Eat a little earlier in the evening",
            "About ${n(lateShare * 100)}% of your food came after 8 pm $days. Large late meals tend to disturb sleep and " +
                "raise next-morning blood sugar. Try moving dinner earlier, or making lunch the bigger meal, and finishing " +
                "eating 2 to 3 hours before bed."))
        if (logged.size >= 4 && kcal > 0) {
            val sd = sqrt(logged.map { (it.totals.kcal - kcal).let { d -> d * d } }.average())
            if (sd / kcal >= 0.3) add(Candidate("week_swings", sd / kcal - 0.1, "Steadier days, fewer cravings",
                "Your calories swung a lot from day to day (about ${n(sd)} kcal either side of ${n(kcal)}). Very light days " +
                    "are often followed by big ones. Regular meals with protein and fibre keep hunger and energy more even."))
        }
        if (waterGoalMl > 0) {
            val withWater = logged.filter { it.waterMl > 0 }
            if (withWater.size >= 3) {
                val w = withWater.map { it.waterMl }.average()
                val miss = 1 - w / waterGoalMl
                if (miss >= 0.25) add(Candidate("week_water", miss * 0.8, "Drink a bit more water",
                    "You averaged ${n(w)} of ${n(waterGoalMl.toDouble())} ml water on the days you logged it. Even mild " +
                        "dehydration lowers energy and focus. A glass with each meal and one mid-afternoon closes most of the gap."))
            }
        }
    }

    private fun logMore(logged: Int) = Candidate("week_log", 1.0, "A fuller log means better advice",
        (if (logged == 0) "No complete days were logged last week. " else "Only $logged complete days were logged last week. ") +
            "Logging every meal for a few days, even with a quick photo, lets Nourio spot what's helping and what isn't. " +
            "Meanwhile, a simple start: half your plate vegetables, a quarter protein, a quarter whole grains.")

    /** For a week where nothing stood out: habits with strong evidence for a healthier, stronger body. */
    private fun evergreen() = listOf(
        Candidate("tip_strength", 0.0, "Your week was on track: add strength",
            "Your nutrition was close to your goals last week. The next step for a stronger body is muscle-strengthening " +
                "activity on 2 or more days a week (WHO): squats, push-ups, resistance bands or weights, 20 to 30 minutes is enough."),
        Candidate("tip_fish", 0.0, "Your week was on track: eat oily fish",
            "Last week looked balanced. For heart and brain health, aim for oily fish such as salmon, sardines or mackerel " +
                "twice a week. If you don't eat fish, walnuts, chia and flaxseed give plant omega-3."),
        Candidate("tip_colour", 0.0, "Your week was on track: eat more colours",
            "Your goals were close last week. Different coloured fruit and vegetables bring different protective nutrients. " +
                "Try to get three colours on your plate at lunch and dinner."),
        Candidate("tip_move", 0.0, "Your week was on track: keep moving",
            "Good nutrition week. Adults do best with at least 150 minutes of moderate activity a week (WHO), such as brisk " +
                "walking. A 10-minute walk after meals also helps blood sugar."),
        Candidate("tip_sleep", 0.0, "Your week was on track: protect your sleep",
            "Your food was on track last week. Sleep matters as much for appetite and recovery: 7 or more hours, at similar " +
                "times each night, helps keep cravings and energy in check."),
    )
}
