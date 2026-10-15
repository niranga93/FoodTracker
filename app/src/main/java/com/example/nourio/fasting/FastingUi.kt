package com.example.nourio.fasting

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nourio.LocalOpenPage
import com.example.nourio.RelationshipCard
import com.example.nourio.WidgetCard
import com.example.nourio.dayTitle
import com.example.nourio.data.MealDb
import com.example.nourio.insights.Analysis
import com.example.nourio.insights.HistoryLoader
import com.example.nourio.insights.InsightEngine
import com.example.nourio.insights.InsightGoals
import com.example.nourio.insights.Metric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToLong

private fun clock(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
private fun h1(h: Double) = "%.1f h".format(h)
private fun ratio(goal: Double) = "${goal.toInt()}:${(24 - goal).toInt()}"

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) = Text(
    text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier
)

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier, color: Color? = null) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color ?: MaterialTheme.colorScheme.onSurface)
        Muted(label)
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The current time, updated every 30 seconds while shown. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { delay(30_000); now = System.currentTimeMillis() }
    }
    return now
}

/** Fasting days from [from] to [to], with the meals of the two days before so the first fast is known. */
private suspend fun loadDays(db: MealDb, from: LocalDate, to: LocalDate): Pair<List<Long>, List<FastDay>> =
    withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val start = from.minusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val times = Fasting.eatingTimes(db.between(start, end))
        times to Fasting.days(times, from, to, zone)
    }

/** Ring with the hours fasted, toward the goal. */
@Composable
private fun FastRing(hours: Double, goal: Double, label: String, sub: String) {
    val cs = MaterialTheme.colorScheme
    val progress = (hours / goal).toFloat().coerceIn(0f, 1f)
    Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
        val track = cs.surfaceVariant
        val color = if (hours >= goal) cs.primary else cs.tertiary
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val arc = Size(size.width - stroke, size.height - stroke)
            val tl = Offset(stroke / 2, stroke / 2)
            drawArc(track, 0f, 360f, false, tl, arc, style = Stroke(stroke))
            drawArc(color, -90f, 360f * progress, false, tl, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Muted(sub)
        }
    }
}

/** The fast going on now: since the last meal, with the stage and when the goal is reached. */
@Composable
private fun FastNow(lastMeal: Long, now: Long, goal: Double) {
    val cs = MaterialTheme.colorScheme
    val h = Fasting.hours(lastMeal, now).coerceAtLeast(0.0)
    val stage = Fasting.stage(h)
    Row(verticalAlignment = Alignment.CenterVertically) {
        FastRing(h, goal, Fasting.duration(h), "of ${goal.toInt()} h")
        Spacer(Modifier.width(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(stage.title, "where your body is", color = cs.secondary)
            Stat(clock(lastMeal), "last meal")
            val goalAt = lastMeal + (goal * 3_600_000).toLong()
            if (h >= goal) Stat("Reached", "${goal.toInt()} h goal at ${clock(goalAt)}", color = cs.primary)
            else Stat(clock(goalAt), "${goal.toInt()} h goal, in ${Fasting.duration(goal - h)}", color = cs.tertiary)
        }
    }
    Note(stage.text, if (h >= goal) cs.primary else cs.secondary)
}

/** Home: the fast going on now (today), or that day's fast and eating window (earlier days). */
@Composable
fun FastingCard(db: MealDb, dayOffset: Int) {
    val context = LocalContext.current
    val openPage = LocalOpenPage.current
    val goal = remember { FastingPrefs(context).goalH }
    val date = LocalDate.now().plusDays(dayOffset.toLong())
    var data by remember(dayOffset) { mutableStateOf<Pair<List<Long>, List<FastDay>>?>(null) }
    LaunchedEffect(dayOffset) { data = loadDays(db, date, date) }
    val now = rememberNow()

    WidgetCard(
        if (dayOffset == 0) "Fasting" else "Fasting, ${dayTitle(dayOffset)}",
        Modifier.clickable { openPage("fasting") },
        trailing = { Muted("${ratio(goal)}  ›") }
    ) {
        val (times, days) = data ?: run { Muted("Working it out…"); return@WidgetCard }
        val day = days.first()
        val last = times.lastOrNull { it <= now }
        if (dayOffset == 0) {
            if (last == null || Fasting.hours(last, now) > Fasting.MAX_FAST_H) {
                Muted("Log your meals and Nourio tracks the time between them: your overnight fast and your eating window.")
                return@WidgetCard
            }
            FastNow(last, now, goal)
        } else {
            Row {
                Stat(day.fastH?.let { h1(it) } ?: "–", "overnight fast before", Modifier.weight(1f),
                    color = day.fastH?.let { if (it >= goal) MaterialTheme.colorScheme.primary else null })
                Stat(day.windowH?.takeIf { day.meals >= 2 }?.let { h1(it) } ?: "–", "eating window", Modifier.weight(1f))
            }
        }
        if (day.firstMeal != null && day.lastMeal != null && day.meals >= 2) {
            Muted(
                "Ate from ${clock(day.firstMeal)} to ${clock(day.lastMeal)}" +
                    (day.fastH?.takeIf { dayOffset == 0 }?.let { "  ·  last night's fast ${h1(it)}" } ?: ""),
                Modifier.padding(top = 10.dp)
            )
        } else if (dayOffset == 0 && day.fastH != null) {
            Muted("Last night's fast: ${h1(day.fastH)}", Modifier.padding(top = 10.dp))
        }
    }
}

// ------------------------------------------------------------------ page

/** Profile > Fasting, or a tap on the Home card. */
@Composable
fun FastingPage(db: MealDb) {
    val context = LocalContext.current
    val prefs = remember { FastingPrefs(context) }
    var goal by remember { mutableDoubleStateOf(prefs.goalH) }
    val today = LocalDate.now()
    var data by remember { mutableStateOf<Pair<List<Long>, List<FastDay>>?>(null) }
    var analysis by remember { mutableStateOf<Pair<List<com.example.nourio.insights.DayMetrics>, Analysis>?>(null) }
    LaunchedEffect(Unit) {
        data = loadDays(db, today.minusDays(29), today)
        val history = HistoryLoader.load(context, db, 60)
        analysis = history to withContext(Dispatchers.Default) { InsightEngine.analyse(history, InsightGoals.current(context), today) }
    }
    val now = rememberNow()
    val cs = MaterialTheme.colorScheme

    // 1. Now
    WidgetCard("Right now") {
        val last = data?.first?.lastOrNull { it <= now }
        when {
            data == null -> Muted("Working it out…")
            last == null || Fasting.hours(last, now) > Fasting.MAX_FAST_H ->
                Muted("No meal in the last 30 hours. Log your meals and your fasts appear here.")
            else -> FastNow(last, now, goal)
        }
    }

    // 2. Goal
    WidgetCard("Your fasting goal") {
        Muted("Hours between your last meal and your first meal the next day. The second number is your eating window.")
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Fasting.GOALS.forEach { g ->
                FilterChip(
                    selected = goal == g,
                    onClick = { goal = g; prefs.goalH = g },
                    label = { Text(ratio(g)) }
                )
            }
        }
        Muted(
            when (goal) {
                12.0 -> "12:12 is a gentle start: most people get there by not snacking after dinner."
                14.0 -> "14:10 suits most people and is easy to keep up."
                16.0 -> "16:8 is the most studied pattern, for example 10 am to 6 pm."
                18.0 -> "18:6 is a demanding pattern. Eat enough protein in your window."
                else -> "20:4 is very demanding and not for everyone. Talk to a doctor if you have a health condition."
            },
            Modifier.padding(top = 8.dp)
        )
    }

    val days = data?.second ?: return
    val stats = Fasting.stats(days, goal, today)

    // 3. Last 14 days
    WidgetCard("Overnight fasts, last 14 days") {
        FastBars(days.takeLast(14), goal)
        Row(Modifier.padding(top = 14.dp)) {
            Stat(stats.avgFastH?.let { h1(it) } ?: "–", "average fast", Modifier.weight(1f))
            Stat(stats.avgWindowH?.let { h1(it) } ?: "–", "average eating window", Modifier.weight(1f))
        }
        Row(Modifier.padding(top = 12.dp)) {
            Stat("${stats.daysAtGoal} of ${stats.daysWithFast}", "days at your goal (30 days)", Modifier.weight(1f), cs.primary)
            Stat(if (stats.streak == 1) "1 day" else "${stats.streak} days", "streak", Modifier.weight(1f))
        }
        Muted(
            "A fast counts from a meal of 50 kcal or more. Black coffee, tea and water don't break it. " +
                "Gaps over 30 hours usually mean meals weren't logged, so they're left out.",
            Modifier.padding(top = 12.dp)
        )
    }

    // 4. What your own data shows
    val (history, a) = analysis ?: run { WidgetCard("What your data shows") { Muted("Looking for patterns…") }; return }
    ImpactCard(Fasting.impact(history, goal))
    val links = a.relationships.filter { it.x == Metric.FAST || it.x == Metric.EATING_WINDOW }
    links.forEach { RelationshipCard(it) }
    a.trends.filter { it.metric == Metric.FAST || it.metric == Metric.EATING_WINDOW }.forEach { t ->
        WidgetCard("Trend: ${t.metric.label.lowercase()}") {
            Text("${h1(t.before)} → ${h1(t.recent)} a day, compared with two weeks before.", style = MaterialTheme.typography.bodyMedium)
        }
    }

    // 5. What the research says
    WidgetCard("About fasting") {
        Text(
            "• Time-restricted eating (eating within 8 to 10 hours) mostly helps weight loss because people eat " +
                "fewer calories. When calories are the same, it works about as well as ordinary calorie cutting " +
                "(Liu et al., New England Journal of Medicine, 2022).\n" +
                "• In trials of 2 to 3 months, people lose a few percent of their body weight on average.\n" +
                "• An earlier window (finishing dinner earlier) may be better for blood sugar than a late one.\n" +
                "• Keep protein up in your window to protect muscle, and drink water while you fast.\n\n" +
                "Not advised without a doctor: under 18, pregnant or breastfeeding, a history of eating disorders, " +
                "diabetes treated with insulin or tablets that lower blood sugar, or other medical conditions.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant
        )
    }
}

/** Bars for each day's overnight fast, with the goal as a dashed line. */
@Composable
private fun FastBars(days: List<FastDay>, goal: Double) {
    val cs = MaterialTheme.colorScheme
    val locale = LocalConfiguration.current.locales[0]
    val max = maxOf(goal + 2, days.mapNotNull { it.fastH }.maxOrNull() ?: 0.0)
    val hit = cs.primary; val miss = cs.tertiary.copy(alpha = 0.6f); val line = cs.onSurfaceVariant.copy(alpha = 0.6f)
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val slot = size.width / days.size
        val barW = slot * 0.6f
        days.forEachIndexed { i, d ->
            val h = d.fastH ?: return@forEachIndexed
            val bh = (h / max * size.height).toFloat()
            drawRoundRect(
                if (h >= goal) hit else miss,
                topLeft = Offset(i * slot + (slot - barW) / 2, size.height - bh),
                size = Size(barW, bh),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
            )
        }
        val gy = (size.height - goal / max * size.height).toFloat()
        drawLine(line, Offset(0f, gy), Offset(size.width, gy), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        days.forEach { d ->
            Text(
                d.date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

/** Calories and next-morning weight on days at the goal against other days. */
@Composable
private fun ImpactCard(i: FastImpact) {
    val cs = MaterialTheme.colorScheme
    WidgetCard("What your data shows") {
        val a = i.atGoal; val b = i.below
        if (a.days < 3 || b.days < 3) {
            Muted(
                "Needs at least 3 fully logged days at your ${i.goalH.toInt()} h goal and 3 below it " +
                    "(now ${a.days} and ${b.days}). Keep logging every meal and weighing in each morning."
            )
            return@WidgetCard
        }
        Row {
            Column(Modifier.weight(1.3f)) { Muted(" "); Muted("Days"); Muted("Calories that day"); Muted("Weight by next morning") }
            listOf("≥ ${i.goalH.toInt()} h fast" to a, "Shorter fast" to b).forEach { (title, g) ->
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Muted("${g.days}")
                    Muted(g.kcal?.let { "${it.roundToLong()} kcal" } ?: "–")
                    Muted(g.weightChangeKg?.let { "%+.2f kg".format(it) } ?: "–")
                }
            }
        }
        val kcalDiff = if (a.kcal != null && b.kcal != null) a.kcal - b.kcal else null
        val wDiff = if (a.weightChangeKg != null && b.weightChangeKg != null) a.weightChangeKg - b.weightChangeKg else null
        val text = buildString {
            if (kcalDiff != null && abs(kcalDiff) >= 50) append(
                if (kcalDiff < 0) "On days after reaching your goal you ate about ${(-kcalDiff).roundToLong()} kcal less. "
                else "On days after reaching your goal you ate about ${kcalDiff.roundToLong()} kcal more, so the fast may be making you hungrier. "
            )
            if (wDiff != null && abs(wDiff) >= 0.05) append(
                if (wDiff < 0) "Your weight did about ${"%.2f".format(-wDiff)} kg better by the next morning, roughly ${"%.1f".format(-wDiff * 7)} kg a week if every day were like that."
                else "Your weight did about ${"%.2f".format(wDiff)} kg worse by the next morning."
            )
            if (isEmpty()) append("So far, fasting days and other days look about the same for you. That's common: what you eat matters more than when.")
        }
        val good = (kcalDiff ?: 0.0) < 0 || (wDiff ?: 0.0) < 0
        Note(text, if (good) cs.primary else cs.tertiary)
        Muted(
            "Averages of your own days, not proof of cause: busy days, illness or a big dinner can move both. " +
                "Weight changes day to day are mostly water.",
            Modifier.padding(top = 10.dp)
        )
    }
}
