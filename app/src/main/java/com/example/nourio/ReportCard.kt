package com.example.nourio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nourio.data.Goals
import com.example.nourio.data.Totals
import java.text.NumberFormat
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

// ------------------------------------------------------------------ grading

enum class SubjectKind { CALORIES, PROTEIN, FIBER, SUGAR, FAT, SAT_FAT, SODIUM, WATER, STEPS, SLEEP }

/** One line of the report: a 0..1 score, its letter grade and a short comment. */
data class Subject(
    val kind: SubjectKind,
    val name: String,
    val detail: String,
    val score: Double,
    val comment: String
) {
    val grade: String get() = Grading.letter(score)
}

data class Report(val subjects: List<Subject>, val score: Double?) {
    val grade: String? get() = score?.let(Grading::letter)
}

/** Everything the grading needs for one day. Limits and minimums of 0 mean "not set". */
data class DayInputs(
    val totals: Totals,
    val budgetKcal: Double,
    val goals: Goals,
    val weightKg: Double,
    val waterMl: Int,
    val waterGoalMl: Int,
    val steps: Long?,
    val isToday: Boolean,
    val hourOfDay: Double,
    val sleep: com.example.nourio.insights.SleepNight? = null,   // the night before this day
    val bedtimeShiftH: Double? = null,                           // that bedtime against your usual one
    val sleepGoalH: Double = 7.0
)

object Grading {

    private const val MIN_LOGGED_KCAL = 800.0

    fun letter(score: Double): String = when {
        score >= 0.9 -> "A"
        score >= 0.8 -> "B"
        score >= 0.7 -> "C"
        score >= 0.6 -> "D"
        else -> "E"
    }

    fun verdict(grade: String): String = when (grade) {
        "A" -> "Excellent day"
        "B" -> "Good day"
        "C" -> "Fair day"
        "D" -> "Needs work"
        else -> "Tough day"
    }

    /** For today, how much of a daily minimum you'd expect by now: from 15% at 6 am to all of it by 9 pm. */
    fun expectedShare(isToday: Boolean, hour: Double): Double =
        if (!isToday) 1.0 else ((hour - 6.0) / 15.0).coerceIn(0.15, 1.0)

    private val nf: NumberFormat get() = NumberFormat.getIntegerInstance()
    private fun n(d: Double) = nf.format(Math.round(d))

    /** Over a limit costs 2 points per 1% over: 10% over is a B, 25% over is an E. */
    private fun limitScore(value: Double, limit: Double) =
        if (value <= limit) 1.0 else max(0.0, 1.0 - (value / limit - 1.0) * 2.0)

    private fun minimumScore(value: Double, target: Double) = if (target <= 0) 1.0 else min(1.0, value / target)

    fun report(d: DayInputs): Report {
        val t = d.totals
        val logged = t.kcal > 0
        val share = expectedShare(d.isToday, d.hourOfDay)
        val out = mutableListOf<Subject>()

        // Calories: today only over-budget counts against you; a finished day is graded on how close it landed
        if (logged && d.budgetKcal > 0 && (d.isToday || t.kcal >= MIN_LOGGED_KCAL)) {
            val b = d.budgetKcal
            val r = t.kcal / b
            val score = if (d.isToday) limitScore(t.kcal, b) else max(0.0, 1.0 - kotlin.math.abs(r - 1.0) * 2.0)
            // Rounded first, so these match the "eaten of" and "left" figures on Home
            val eatenR = Math.round(t.kcal).toDouble()
            val budgetR = Math.round(b).toDouble()
            val comment = when {
                eatenR > budgetR -> "${n(eatenR - budgetR)} kcal over budget. A lighter next meal or a walk will balance it."
                d.isToday -> "${n(budgetR - eatenR)} kcal left for today."
                r >= 0.95 -> "Right on budget."
                else -> "${n(budgetR - eatenR)} kcal under budget. Eating far too little can backfire."
            }
            out += Subject(SubjectKind.CALORIES, "Calories", "${n(t.kcal)} of ${n(b)} kcal", score, comment)
        }

        if (logged) {
            val protein = d.goals.protein.takeIf { it > 0 } ?: (d.weightKg * 0.8).takeIf { it > 0 }
            protein?.let { g ->
                val target = g * share
                val s = minimumScore(t.protein, target)
                out += Subject(
                    SubjectKind.PROTEIN, "Protein", "${n(t.protein)} of ${n(g)} g", s,
                    if (s >= 1) if (d.isToday && share < 1) "On track for the time of day." else "Goal reached."
                    else "${n(target - t.protein)} g behind. Add eggs, yogurt, chicken, tofu or beans."
                )
            }
            val fiber = d.goals.fiber.takeIf { it > 0 } ?: 25.0
            val fTarget = fiber * share
            val fs = minimumScore(t.fiber, fTarget)
            out += Subject(
                SubjectKind.FIBER, "Fiber", "${n(t.fiber)} of ${n(fiber)} g", fs,
                if (fs >= 1) if (d.isToday && share < 1) "On track for the time of day." else "Goal reached."
                else "${n(fTarget - t.fiber)} g behind. Add vegetables, fruit, beans or whole grains."
            )
            if (d.goals.addedSugar > 0) out += limitSubject(
                SubjectKind.SUGAR, "Added sugar", t.addedSugar, d.goals.addedSugar, "g",
                "Swap sweet drinks, juice and snacks for whole fruit, yogurt or nuts."
            )
            if (d.goals.fat > 0) out += limitSubject(
                SubjectKind.FAT, "Fat", t.fat, d.goals.fat, "g", "Choose grilled or steamed over fried."
            )
            val satLimit = d.goals.satFat.takeIf { it > 0 } ?: (d.budgetKcal * 0.10 / 9).takeIf { it > 0 }
            satLimit?.let {
                out += limitSubject(
                    SubjectKind.SAT_FAT, "Saturated fat", t.satFat, it, "g",
                    "Pick fish, lean meat or plant oils over butter, cheese and fatty meat."
                )
            }
            out += limitSubject(
                SubjectKind.SODIUM, "Sodium", t.sodium, d.goals.sodiumMg.takeIf { it > 0 } ?: 2300.0, "mg",
                "Go easy on sauces, salty snacks and packaged food."
            )
        }

        if (d.waterGoalMl > 0 && (logged || d.waterMl > 0)) {
            val target = d.waterGoalMl * share
            val s = minimumScore(d.waterMl.toDouble(), target)
            out += Subject(
                SubjectKind.WATER, "Water", "${n(d.waterMl.toDouble())} of ${n(d.waterGoalMl.toDouble())} ml", s,
                if (s >= 1) if (d.isToday && share < 1) "On track for the time of day." else "Well hydrated."
                else "About ${n(target - d.waterMl)} ml behind. Have a glass of water."
            )
        }

        d.steps?.takeIf { it > 0 }?.let { steps ->
            val goal = d.goals.stepGoal
            val target = goal * share
            val s = minimumScore(steps.toDouble(), target)
            out += Subject(
                SubjectKind.STEPS, "Steps", "${n(steps.toDouble())} of ${n(goal.toDouble())}", s,
                if (s >= 1) if (d.isToday && share < 1) "On track for the time of day." else "Goal reached."
                else "${n(target - steps)} steps behind. A 20-minute walk adds about 2,000."
            )
        }

        d.sleep?.let { out += sleepSubject(it, d.bedtimeShiftH, d.sleepGoalH) }

        // Calories count three times: it's the main thing the day is about
        val graded = if (logged) out else emptyList()
        val score = if (graded.isEmpty()) null else {
            val w = graded.sumOf { if (it.kind == SubjectKind.CALORIES) 3.0 else 1.0 }
            graded.sumOf { it.score * if (it.kind == SubjectKind.CALORIES) 3.0 else 1.0 } / w
        }
        return Report(out, score)
    }

    /** "7 h 20 m" */
    fun hm(minutes: Double): String {
        val m = minutes.toLong().coerceAtLeast(0)
        return if (m < 60) "$m m" else "${m / 60} h ${m % 60} m"
    }

    /**
     * Last night, graded mostly on time asleep against the goal (70%), then on quality: share of the
     * night spent asleep (15%) and a bedtime close to your usual one (15%). Parts without data drop out.
     */
    fun sleepSubject(night: com.example.nourio.insights.SleepNight, shiftH: Double?, goalH: Double): Subject {
        val goal = goalH.takeIf { it > 0 } ?: 7.0
        val r = night.asleepH / goal
        val duration = if (r >= 1) 1.0 else max(0.0, 1.0 - (1.0 - r) * 1.5)      // 1 h short of 7 h is a C
        val quality = night.efficiency?.let { if (it >= 85) 1.0 else max(0.0, 1.0 - (85 - it) / 30) }
        val rhythm = shiftH?.let { if (it <= 0.5) 1.0 else max(0.0, 1.0 - (it - 0.5) / 2) }
        val parts = listOfNotNull(duration to 0.7, quality?.let { it to 0.15 }, rhythm?.let { it to 0.15 })
        val score = parts.sumOf { it.first * it.second } / parts.sumOf { it.second }

        val comment = when {
            duration < 0.9 -> "${hm((goal - night.asleepH) * 60)} short of your ${goal.trimmed()} h goal. An earlier bedtime tonight helps."
            quality != null && quality < 0.9 -> "Restless night: asleep ${night.efficiency!!.toLong()}% of your time in bed. Keep the room cool and dark."
            rhythm != null && rhythm < 0.9 -> "Bedtime was ${hm(shiftH!! * 60)} off your usual. A steady bedtime makes sleep come easier."
            else -> "Well rested."
        }
        return Subject(SubjectKind.SLEEP, "Sleep", "${hm(night.asleepH * 60)} of ${goal.trimmed()} h", score, comment)
    }

    private fun Double.trimmed(): String = if (this % 1.0 == 0.0) toLong().toString() else "%.1f".format(this)

    private fun limitSubject(kind: SubjectKind, name: String, value: Double, limit: Double, unit: String, tip: String): Subject {
        val s = limitScore(value, limit)
        return Subject(
            kind, name, "${n(value)} of ${n(limit)} $unit", s,
            if (value <= limit) "Within your limit." else "${n(value - limit)} $unit over. $tip"
        )
    }

    /**
     * One thing to work on, for Home: the weakest area and what to do about it. Calories come first when
     * they're the problem; otherwise the area furthest behind.
     */
    fun focus(r: Report, isToday: Boolean): String {
        if (r.grade == null) return "Log a meal and your day fills in here."
        val worst = r.subjects.filter { it.score < 0.9 }
            .minWithOrNull(compareBy<Subject> { if (it.kind == SubjectKind.CALORIES) -1.0 else 0.0 }.thenBy { it.score })
            ?: return if (isToday) "Everything's on track so far. Keep it up." else "Every area was on track. Great day."
        return "${worst.name}: ${worst.comment}"
    }

    /** The teacher's note: verdict, best subject, and one thing to work on. */
    fun note(r: Report, isToday: Boolean): String {
        val grade = r.grade ?: return "Log a meal and your report card fills in."
        val best = r.subjects.maxByOrNull { it.score }
        val worst = r.subjects.filter { it.score < 0.9 }.minByOrNull { it.score }
        return buildString {
            append(if (isToday) "So far: ${verdict(grade).lowercase()}." else "${verdict(grade)}.")
            if (best != null && best.score >= 0.9) append(" Best subject: ${best.name}.")
            if (worst != null) append(" Work on ${worst.name.lowercase()}: ${worst.comment}")
            else append(" Every subject is on track. Keep it up.")
        }
    }

    fun nowHour(): Double {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0
    }
}

// ------------------------------------------------------------------ UI

@Composable
fun gradeColor(grade: String?): Color {
    val cs = MaterialTheme.colorScheme
    return when (grade) {
        "A" -> cs.primary
        "B" -> cs.tertiary
        "C" -> Color(0xFFFFB547)
        "D" -> cs.secondary
        "E" -> cs.error
        else -> cs.onSurfaceVariant
    }
}

