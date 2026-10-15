package com.example.nourio.exercise

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How hard an activity is, for recovery and bedtime rules. */
enum class Intensity { LIGHT, MODERATE, VIGOROUS }

/** What an activity mainly trains, for variety across the week. */
enum class Kind { CARDIO, STRENGTH, MOBILITY }

/**
 * An activity and its MET value (energy use as a multiple of resting), from the Compendium of
 * Physical Activities (Ainsworth et al., 2011).
 */
enum class Activity(val label: String, val emoji: String, val met: Double, val intensity: Intensity, val kind: Kind, val how: String) {
    BRISK_WALK("Brisk walk", "🚶", 4.3, Intensity.MODERATE, Kind.CARDIO, "About 5.5 km/h, fast enough that talking takes a little effort"),
    EASY_WALK("Easy walk", "🚶", 3.0, Intensity.LIGHT, Kind.CARDIO, "A relaxed stroll, good after a meal"),
    JOG("Jog", "🏃", 8.3, Intensity.VIGOROUS, Kind.CARDIO, "About 8 km/h, or run-walk intervals if you're starting out"),
    CYCLING("Cycling", "🚴", 6.8, Intensity.MODERATE, Kind.CARDIO, "Steady riding at 16 to 19 km/h, or a stationary bike"),
    SWIM("Swimming", "🏊", 5.8, Intensity.MODERATE, Kind.CARDIO, "Easy to moderate laps, gentle on the joints"),
    HIIT("HIIT circuit", "⚡", 8.0, Intensity.VIGOROUS, Kind.CARDIO, "30 s hard, 30 s easy: burpees, squat jumps, mountain climbers"),
    STRENGTH("Strength training", "🏋️", 5.0, Intensity.MODERATE, Kind.STRENGTH, "Squats, push-ups, lunges, rows and planks, 3 sets each"),
    YOGA("Yoga or stretching", "🧘", 2.5, Intensity.LIGHT, Kind.MOBILITY, "Slow flows that loosen hips, back and shoulders"),
    DANCE("Dancing", "💃", 5.0, Intensity.MODERATE, Kind.CARDIO, "Put on some music; it all counts");

    /**
     * Calories above resting per minute. Resting burn is already in the day's total,
     * so only the extra (MET - 1) counts toward the balance.
     */
    fun kcalPerMinute(weightKg: Double) = (met - 1) * weightKg / 60.0
}

/** A past session, reduced to what matters for the next suggestion. */
data class PastWorkout(val daysAgo: Int, val name: String, val minutes: Long) {
    val kind: Kind get() = classify(name).second
    val hard: Boolean get() = classify(name).first == Intensity.VIGOROUS && minutes >= 30

    companion object {
        /** Health Connect gives names like "Running" or the app's own title, so match on words. */
        fun classify(name: String): Pair<Intensity, Kind> {
            val n = name.lowercase()
            return when {
                listOf("strength", "weight", "gym", "lift", "resistance", "calisthenic").any { it in n } -> Intensity.MODERATE to Kind.STRENGTH
                listOf("yoga", "pilates", "stretch", "mobility").any { it in n } -> Intensity.LIGHT to Kind.MOBILITY
                listOf("run", "jog", "hiit", "interval", "crossfit", "boxing", "football", "soccer", "basketball", "tennis", "squash")
                    .any { it in n } -> Intensity.VIGOROUS to Kind.CARDIO
                listOf("walk", "stroll").any { it in n } -> Intensity.LIGHT to Kind.CARDIO
                else -> Intensity.MODERATE to Kind.CARDIO
            }
        }
    }
}

data class CoachInput(
    val weightKg: Double,
    val age: Int,
    val eaten: Double,               // today so far
    val burned: Double,              // today's estimated burn
    val weekBalances: List<Double>,  // eaten - burned on the last few finished, fully logged days
    val steps: Long?,
    val stepGoal: Long,
    val workouts: List<PastWorkout>, // last 7 days, today = 0
    val sleepLastNightH: Double?,
    val hour: Int,                   // local hour now, 0..23
)

data class ExerciseOption(val activity: Activity, val minutes: Int, val kcal: Int, val why: String)

data class Recommendation(
    val headline: String,
    val reason: String,
    val targetKcal: Int,             // 0 when the day doesn't need balancing
    val options: List<ExerciseOption>,
    val note: String?,
)

/**
 * Suggests what to do today from the calorie balance, then fits the kind of exercise to the person:
 * recovery after hard days, nothing vigorous close to bedtime or after a short night, strength when the
 * week has none, and gentler choices from 65 on.
 */
object ExerciseCoach {
    /** More than this from exercise in one day isn't a sensible target; the rest is for meals. */
    const val MAX_TARGET_KCAL = 600
    private const val MIN_MINUTES = 10
    private const val MAX_MINUTES = 75

    fun recommend(i: CoachInput): Recommendation {
        val surplus = i.eaten - i.burned
        val avgWeek = if (i.weekBalances.isEmpty()) 0.0 else i.weekBalances.average()

        // What to burn: today's surplus, or a dent in a week that ran over
        val target: Double
        val headline: String
        val reason: String
        when {
            i.eaten <= 0 -> {
                target = 0.0
                headline = "Move for your health today"
                reason = "Nothing logged yet, so there's no balance to work out. Here's what suits you today."
            }
            surplus > 0 -> {
                target = min(surplus, MAX_TARGET_KCAL.toDouble())
                headline = "Burn about ${round10(target)} kcal to balance today"
                reason = "You've eaten ${round10(i.eaten)} kcal and burned about ${round10(i.burned)}, " +
                    "so you're ${round10(surplus)} kcal over." +
                    if (surplus > MAX_TARGET_KCAL) " Exercise can cover part of it; trim tomorrow's meals for the rest." else ""
            }
            avgWeek > 150 -> {
                target = min(300.0, avgWeek)
                headline = "Today is balanced, but the week ran over"
                reason = "You averaged ${round10(avgWeek)} kcal over a day this week. " +
                    "About ${round10(target)} kcal of exercise today makes up for some of it."
            }
            surplus < -1000 && i.hour >= 18 -> {
                target = 0.0
                headline = "You've done enough today"
                reason = "You're ${round10(-surplus)} kcal under. A big deficit is hard to keep up, so keep any movement gentle and eat enough protein."
            }
            else -> {
                target = 0.0
                headline = "You're in balance. Train for strength and health"
                reason = "You're ${round10(-surplus)} kcal under what you've burned. No need to burn more, so pick what keeps you fit."
            }
        }

        // What kind of exercise suits today
        val hardRecently = i.workouts.count { it.daysAgo in 1..2 && it.hard }
        val recovering = i.workouts.any { it.daysAgo == 1 && it.hard } || hardRecently >= 2
        val tired = i.sleepLastNightH != null && i.sleepLastNightH < 6.0
        val late = i.hour >= 20
        val older = i.age >= 65
        val noStrength = i.workouts.none { it.kind == Kind.STRENGTH }
        val stepsShort = i.steps != null && i.steps < i.stepGoal

        val allowed = Activity.entries.filter { a ->
            when {
                late -> a.intensity == Intensity.LIGHT || a == Activity.BRISK_WALK
                recovering || tired -> a.intensity != Intensity.VIGOROUS
                older -> a.intensity != Intensity.VIGOROUS
                else -> true
            }
        }

        // Rank: each pick has a reason that comes from the person's own data
        val picks = LinkedHashMap<Activity, String>()
        fun add(a: Activity, why: String) { if (a in allowed && a !in picks) picks[a] = why }
        if (late) {
            add(Activity.EASY_WALK, "Gentle movement late in the day won't keep you awake")
            add(Activity.YOGA, "Stretching before bed helps you wind down")
            add(Activity.BRISK_WALK, "Still early enough for a brisk walk")
        }
        if (recovering) {
            add(Activity.SWIM, "You trained hard recently; swimming keeps you moving while your legs recover")
            add(Activity.YOGA, "Recovery day: mobility work helps the next hard session")
        }
        if (tired) add(Activity.BRISK_WALK, "You slept under 6 hours, so keep it moderate. Daylight walking also helps tonight's sleep")
        if (noStrength && target < 400) add(Activity.STRENGTH, "No strength session this week. Muscle raises the calories you burn at rest")
        if (stepsShort) add(Activity.BRISK_WALK, "You're ${"%,d".format(i.stepGoal - i.steps!!)} steps short of your goal, and a walk counts toward both")
        if (target >= 250) {
            add(Activity.JOG, "Burns the most per minute, so the surplus goes quickly")
            add(Activity.HIIT, "Short and intense, when you're short on time")
            add(Activity.CYCLING, "A big burn that's easy on the knees")
        }
        if (older) add(Activity.CYCLING, "Low-impact cardio that's easy on the joints")
        add(Activity.BRISK_WALK, "The easiest to fit in: no kit, anywhere")
        add(Activity.CYCLING, "Steady cardio that's easy on the joints")
        add(Activity.SWIM, "Works the whole body without impact")
        add(Activity.DANCE, "Fun counts: it's real cardio")
        add(Activity.YOGA, "Flexibility and balance, which matter more with age")

        val options = picks.entries.take(3).map { (a, why) -> option(a, why, target, i.weightKg) }

        val note = when {
            late && target > 0 -> "It's late, so the suggestions are gentle. Anything left over is fine to balance tomorrow."
            surplus > 0 && target >= 150 -> "Or split it: a 10-minute walk after each meal also steadies blood sugar."
            else -> null
        }
        return Recommendation(headline, reason, round10(target), options, note)
    }

    private fun option(a: Activity, why: String, target: Double, weightKg: Double): ExerciseOption {
        val perMin = a.kcalPerMinute(weightKg)
        // Without a target, a standard session: 30 min, or 40 for strength and yoga
        val wanted = if (target > 0) ceil(target / perMin) else if (a.kind == Kind.CARDIO) 30.0 else 40.0
        val minutes = (ceil(wanted / 5) * 5).toInt().coerceIn(MIN_MINUTES, MAX_MINUTES)
        return ExerciseOption(a, minutes, (perMin * minutes).roundToInt(), why)
    }

    private fun round10(v: Double) = (max(0.0, v) / 10).roundToInt() * 10
}
