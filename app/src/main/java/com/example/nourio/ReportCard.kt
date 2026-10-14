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

enum class SubjectKind { CALORIES, PROTEIN, FIBER, SUGAR, FAT, SAT_FAT, SODIUM, WATER, STEPS }

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
    val hourOfDay: Double
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
    private fun n(d: Double) = nf.format(d.toLong())

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
            val comment = when {
                t.kcal > b -> "${n(t.kcal - b)} kcal over budget. A lighter next meal or a walk will balance it."
                d.isToday -> "${n(b - t.kcal)} kcal left for today."
                r >= 0.95 -> "Right on budget."
                else -> "${n(b - t.kcal)} kcal under budget. Eating far too little can backfire."
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
            if (d.goals.sugar > 0) out += limitSubject(
                SubjectKind.SUGAR, "Sugar", t.sugar, d.goals.sugar, "g",
                "Swap sweet drinks and snacks for fruit or nuts."
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

        // Calories count three times: it's the main thing the day is about
        val graded = if (logged) out else emptyList()
        val score = if (graded.isEmpty()) null else {
            val w = graded.sumOf { if (it.kind == SubjectKind.CALORIES) 3.0 else 1.0 }
            graded.sumOf { it.score * if (it.kind == SubjectKind.CALORIES) 3.0 else 1.0 } / w
        }
        return Report(out, score)
    }

    private fun limitSubject(kind: SubjectKind, name: String, value: Double, limit: Double, unit: String, tip: String): Subject {
        val s = limitScore(value, limit)
        return Subject(
            kind, name, "${n(value)} of ${n(limit)} $unit", s,
            if (value <= limit) "Within your limit." else "${n(value - limit)} $unit over. $tip"
        )
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

/** The top of the report card: date, overall grade and the day's calories. */
@Composable
fun ReportHeader(
    report: Report,
    name: String,
    dayOffset: Int,
    eaten: Double,
    budget: Double,
    burned: Double?,
    onOpenBudget: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val nf = NumberFormat.getIntegerInstance()
    val grade = report.grade
    val color = gradeColor(grade)
    AppCard(Modifier.fillMaxWidth()) {
        // Coloured strip across the top, like the header band of a printed report
        Box(Modifier.fillMaxWidth().height(6.dp).background(color))
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "DAILY REPORT CARD", style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = cs.onSurfaceVariant
                    )
                    // The date is already in the header above, so the card is titled with the student
                    Text(
                        name.trim().substringBefore(' ').ifBlank { "Your day" },
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (dayOffset == 0) "Graded against the time of day" else "Final grades, ${dayTitle(dayOffset)}",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        grade?.let { (if (dayOffset == 0) "So far: " else "") + Grading.verdict(it) } ?: "Not graded yet",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = color
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(88.dp).clip(CircleShape).background(color.copy(alpha = 0.14f))
                            .border(4.dp, color, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(grade ?: "—", fontSize = 44.sp, fontWeight = FontWeight.Black, color = color)
                    }
                    Text(
                        report.score?.let { "${(it * 100).toInt()} / 100" } ?: "no score",
                        style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            if (budget > 0) {
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text("${nf.format(eaten.toLong())}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        " of ${nf.format(budget.toLong())} kcal",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(bottom = 2.dp)
                    )
                    val left = budget - eaten
                    Text(
                        if (left >= 0) "${nf.format(left.toLong())} left" else "${nf.format((-left).toLong())} over",
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                        color = if (left >= 0) cs.primary else cs.error
                    )
                }
                Spacer(Modifier.height(6.dp))
                SlimBar((eaten / budget).toFloat(), eaten > budget)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroTile("Eaten", nf.format(eaten.toLong()), cs.primaryContainer, cs.primary, Modifier.weight(1f))
                HeroTile("Burned", burned?.let { nf.format(it.toLong()) } ?: "—", cs.secondaryContainer, cs.secondary, Modifier.weight(1f))
                HeroTile(
                    "Balance",
                    if (burned != null && eaten > 0) "%+,.0f".format(eaten - burned) else "—",
                    cs.tertiaryContainer, cs.tertiary, Modifier.weight(1f)
                )
            }
            if (budget > 0) {
                TextButton(onClick = onOpenBudget, contentPadding = PaddingValues(0.dp)) { Text("How your budget is worked out") }
            }
            extra()
        }
    }
}

/** The subjects table: one row per area of the day with its grade, numbers and a comment. */
@Composable
fun SubjectsCard(report: Report) {
    if (report.subjects.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text("SUBJECT", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("GRADE", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = cs.onSurfaceVariant)
            }
            report.subjects.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                SubjectRow(s)
            }
        }
    }
}

@Composable
private fun SubjectRow(s: Subject) {
    val cs = MaterialTheme.colorScheme
    val color = gradeColor(s.grade)
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(s.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text("  ${s.detail}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.surfaceVariant)) {
                Box(
                    Modifier.fillMaxWidth(s.score.toFloat().coerceIn(0.03f, 1f)).fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp)).background(color)
                )
            }
            Text(s.comment, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text(s.grade, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = color)
        }
    }
}

/** The teacher's note at the bottom of the report. */
@Composable
fun TeacherNoteCard(report: Report, isToday: Boolean, onAddMeal: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    AppCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp)) {
            Box(Modifier.width(4.dp).height(56.dp).clip(RoundedCornerShape(2.dp)).background(gradeColor(report.grade)))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    "TEACHER'S NOTE", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = cs.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(Grading.note(report, isToday), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Grades are a guide based on your goals, not medical advice.",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Start, modifier = Modifier.padding(top = 8.dp)
                )
                if (onAddMeal != null && report.grade == null) {
                    TextButton(onClick = onAddMeal, contentPadding = PaddingValues(0.dp)) { Text("Log a meal") }
                }
            }
        }
    }
}
