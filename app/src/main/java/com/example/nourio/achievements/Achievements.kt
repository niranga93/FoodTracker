package com.example.nourio.achievements

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.nourio.data.MealDb
import com.example.nourio.data.WaterStore
import com.example.nourio.energy.EnergyRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Milestones. [goal] is in the badge's own unit (days, meals, steps or links). */
enum class Badge(val emoji: String, val title: String, val text: String, val goal: Int, val unit: String) {
    FIRST_MEAL("🍽️", "First Bite", "Log your first meal", 1, "meal"),
    STREAK_7("🔥", "Week Strong", "Log meals 7 days in a row", 7, "days"),
    TRACKER_30("📅", "30-Day Tracker", "Log meals 30 days in a row", 30, "days"),
    HYDRATION("💧", "Hydration Champion", "Reach your water goal 7 days in a row", 7, "days"),
    STEPS_10K("👟", "10k Steps Club", "Walk 10,000 steps in a day (from Health Connect)", 10_000, "steps"),
    RECIPE("🔗", "Recipe Explorer", "Log a meal from a recipe link", 1, "link"),
    MEALS_100("💯", "Century", "Log 100 meals", 100, "meals"),
}

data class Streaks(
    val current: Int,          // days in a row with a meal; today counts once something is logged
    val best: Int,
    val daysLogged: Int,
    val meals: Int,
    val waterCurrent: Int,     // days in a row at the water goal
    val waterBest: Int,
)

data class BadgeState(val badge: Badge, val progress: Int, val unlockedOn: LocalDate?) {
    val unlocked get() = unlockedOn != null
    val fraction get() = (progress.toFloat() / badge.goal).coerceIn(0f, 1f)
}

data class Achievement(val streaks: Streaks, val badges: List<BadgeState>, val newlyUnlocked: List<Badge>) {
    val unlockedCount get() = badges.count { it.unlocked }
    /** The locked badge closest to done, to show as the next goal. */
    val next get() = badges.filter { !it.unlocked }.maxByOrNull { it.fraction }
}

/**
 * Streaks and badges from the meal log, water log, Health Connect steps and recipe links.
 * A badge stays unlocked once earned, even if the data behind it is later deleted.
 */
object Achievements {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences("badges", Context.MODE_PRIVATE)
        }
    }

    /** Called when a recipe page was read for a meal. */
    fun markRecipeLink() {
        if (!::prefs.isInitialized) return
        prefs.edit { putInt("recipe_links", prefs.getInt("recipe_links", 0) + 1) }
    }

    /** Longest run of consecutive days, and the run ending today (or yesterday, if today isn't in yet). */
    internal fun runs(days: Set<LocalDate>, today: LocalDate): Pair<Int, Int> {
        var best = 0
        for (d in days) {
            if (d.minusDays(1) in days) continue          // only count from the start of each run
            var n = 0
            var x = d
            while (x in days) { n++; x = x.plusDays(1) }
            best = maxOf(best, n)
        }
        var current = 0
        var day = if (today in days) today else today.minusDays(1)
        while (day in days) { current++; day = day.minusDays(1) }
        return current to best
    }

    suspend fun evaluate(context: Context, db: MealDb): Achievement {
        init(context)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        val (streaks, maxSteps) = withContext(Dispatchers.IO) {
            val times = db.timestamps()
            val days = times.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.toSet()
            val (current, best) = runs(days, today)

            val water = WaterStore(context)
            val goal = water.goalMl
            val waterDays = if (goal <= 0) emptySet() else water.allDays()
                .filterValues { it >= goal }.keys.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()
            val (wCurrent, wBest) = runs(waterDays, today)

            // Steps from the last week; the best day seen is kept, since Health Connect only keeps 30 days
            val steps = runCatching { EnergyRepo.activityFor(context, (0 downTo -6).toList()) }.getOrDefault(emptyMap())
                .values.maxOfOrNull { it.steps } ?: 0L
            val maxSteps = maxOf(steps, prefs.getLong("max_steps", 0L))
            if (maxSteps > prefs.getLong("max_steps", 0L)) prefs.edit { putLong("max_steps", maxSteps) }

            Streaks(current, best, days.size, times.size, wCurrent, wBest) to maxSteps
        }

        val progress = mapOf(
            Badge.FIRST_MEAL to streaks.meals,
            Badge.STREAK_7 to streaks.best,
            Badge.TRACKER_30 to streaks.best,
            Badge.HYDRATION to streaks.waterBest,
            Badge.STEPS_10K to maxSteps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            Badge.RECIPE to prefs.getInt("recipe_links", 0),
            Badge.MEALS_100 to streaks.meals,
        )
        val newly = ArrayList<Badge>()
        val states = Badge.entries.map { b ->
            val p = progress[b] ?: 0
            var on = prefs.getLong("u_${b.name}", -1L).takeIf { it >= 0 }?.let { LocalDate.ofEpochDay(it) }
            if (on == null && p >= b.goal) {
                on = today
                prefs.edit { putLong("u_${b.name}", today.toEpochDay()) }
                newly += b
            }
            BadgeState(b, p, on)
        }
        return Achievement(streaks, states, newly)
    }
}
