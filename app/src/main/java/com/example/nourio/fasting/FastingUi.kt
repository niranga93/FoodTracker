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
import androidx.compose.material3.Switch
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
import com.example.nourio.data.totals
import com.example.nourio.insights.Analysis
import com.example.nourio.insights.HistoryLoader
import com.example.nourio.insights.InsightEngine
import com.example.nourio.insights.InsightGoals
import com.example.nourio.insights.Metric
import com.example.nourio.reminders.Reminder
import com.example.nourio.reminders.ReminderPrefs
import com.example.nourio.reminders.Reminders
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

private val KetoneColor = Color(0xFF9C8CFF)

@Composable
private fun phaseColor(p: FuelPhase): Color = phaseColorRaw(p, MaterialTheme.colorScheme)

/** "4:30 pm", or "2:00 am tomorrow" when it falls on another day than [now]. */
private fun clockFrom(ms: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val d = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return clock(ms) + when (d) { today -> ""; today.plusDays(1) -> " tomorrow"; today.minusDays(1) -> " yesterday"; else -> "" }
}

/**
 * The fast going on now: hours since the last meal toward the goal, what the body is burning, the
 * fuel mix, and when the meal runs out and stored fat takes over.
 */
@Composable
private fun FastNow(lastMeal: Long, now: Long, goal: Double, fuel: FuelState?) {
    val cs = MaterialTheme.colorScheme
    val h = Fasting.hours(lastMeal, now).coerceAtLeast(0.0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        FastRing(h, goal, Fasting.duration(h), "of ${goal.toInt()} h")
        Spacer(Modifier.width(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (fuel != null) Stat(fuel.phase.title, "what your body is using now", color = phaseColor(fuel.phase))
            Stat(clock(lastMeal), "last meal" + (fuel?.lastMealKcal?.let { ", ${it.toLong()} kcal" } ?: ""))
            val goalAt = lastMeal + (goal * 3_600_000).toLong()
            if (h >= goal) Stat("Reached", "${goal.toInt()} h goal at ${clock(goalAt)}", color = cs.primary)
            else Stat(clockFrom(goalAt, now), "${goal.toInt()} h goal, in ${Fasting.duration(goal - h)}", color = cs.tertiary)
        }
    }
    if (fuel == null) {
        Note(Fasting.stage(h).text, cs.secondary)
        return
    }

    Spacer(Modifier.height(16.dp))
    FuelMix(fuel)
    Spacer(Modifier.height(16.dp))
    FuelTimeline(fuel, now)

    val left = { t: Long -> Fasting.duration((t - now) / 3_600_000.0) }
    val detail = when (fuel.phase) {
        FuelPhase.FED -> "About ${fuel.stillAbsorbingKcal.toLong()} kcal of your food is still being absorbed. It'll be used up " +
            "by about ${clockFrom(fuel.absorbedAt, now)}, then your liver's stored glucose takes over."
        FuelPhase.GLYCOGEN -> "Your meal was used up at about ${clockFrom(fuel.absorbedAt, now)}. Stored fat becomes your main fuel " +
            "at about ${clockFrom(fuel.fatAt, now)}, in ${left(fuel.fatAt)}."
        FuelPhase.FAT -> "Stored fat took over at about ${clockFrom(fuel.fatAt, now)}. Ketone production picks up around " +
            "${clockFrom(fuel.ketosisAt, now)}, in ${left(fuel.ketosisAt)}."
        FuelPhase.KETOSIS -> "Ketones have been rising since about ${clockFrom(fuel.ketosisAt, now)}. Fasts this long aren't for " +
            "everyone: check with a doctor if you do them often."
    }
    Note("${fuel.phase.text}\n\n$detail", phaseColor(fuel.phase))
    Muted(
        "Estimates from your meals' size, carbs and fat. Exercise brings each step forward.",
        Modifier.padding(top = 8.dp)
    )
}

/** Where this moment's energy comes from, as one bar in three parts. */
@Composable
private fun FuelMix(f: FuelState) {
    val cs = MaterialTheme.colorScheme
    val parts = listOf(
        Triple(f.fromFood, cs.secondary, "Food"),
        Triple(f.fromGlycogen, cs.tertiary, "Stored glucose"),
        Triple(f.fromFat, cs.primary, "Stored fat"),
    )
    Text("Fuel mix now", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
        parts.filter { it.first > 0.005 }.forEach { (share, color, _) ->
            Box(Modifier.weight(share.toFloat()).fillMaxSize().background(color))
        }
    }
    Spacer(Modifier.height(6.dp))
    // Whole percentages that always add up to 100: the largest share takes the rounding
    val pct = parts.map { Math.round(it.first * 100).toInt() }.toMutableList()
    pct[pct.indices.maxBy { parts[it].first }] += 100 - pct.sum()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        parts.forEachIndexed { i, (_, color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(4.dp))
                Text("$label ${pct[i]}%", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

/** The fast from the last meal to ketosis: four coloured stretches, a marker for now, and the times. */
@Composable
private fun FuelTimeline(f: FuelState, now: Long) {
    val cs = MaterialTheme.colorScheme
    val end = f.ketosisAt + 2 * 3_600_000L
    val span = (end - f.lastMeal).toFloat()
    val stretches = listOf(
        Triple(f.lastMeal, f.absorbedAt, cs.secondary),
        Triple(f.absorbedAt, f.fatAt, cs.tertiary),
        Triple(f.fatAt, f.ketosisAt, cs.primary),
        Triple(f.ketosisAt, end, KetoneColor),
    )
    val marker = cs.onSurface
    Text("Your fast, step by step", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    Canvas(Modifier.fillMaxWidth().height(22.dp)) {
        val y = size.height / 2
        stretches.forEach { (a, b, c) ->
            val x0 = (a - f.lastMeal) / span * size.width
            val x1 = (b - f.lastMeal) / span * size.width
            // Done stretches solid, the rest faint
            val done = now >= b
            drawLine(c.copy(alpha = if (done || now >= a) 1f else 0.35f), Offset(x0 + 2f, y), Offset(x1 - 2f, y),
                strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round)
        }
        val nx = ((now - f.lastMeal).coerceIn(0L, end - f.lastMeal)) / span * size.width
        drawCircle(marker, 7.dp.toPx(), Offset(nx, y))
        drawCircle(phaseColorRaw(f.phase, cs), 4.dp.toPx(), Offset(nx, y))
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        Milestone("Meal used up", f.absorbedAt, now, cs.secondary, Modifier.weight(1f))
        Milestone("Fat takes over", f.fatAt, now, cs.primary, Modifier.weight(1f))
        Milestone("Ketones rise", f.ketosisAt, now, KetoneColor, Modifier.weight(1f))
    }
}

private fun phaseColorRaw(p: FuelPhase, cs: androidx.compose.material3.ColorScheme): Color = when (p) {
    FuelPhase.FED -> cs.secondary
    FuelPhase.GLYCOGEN -> cs.tertiary
    FuelPhase.FAT -> cs.primary
    FuelPhase.KETOSIS -> KetoneColor
}

/** The time on one line in colour; what happens, and on which day, underneath. */
@Composable
private fun Milestone(label: String, at: Long, now: Long, color: Color, modifier: Modifier) {
    val day = clockFrom(at, now).removePrefix(clock(at)).trim()
    Column(modifier) {
        Text(clock(at), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
        Muted(if (day.isEmpty()) label else "$label, $day")
    }
}

/** The last day and a half of meals, for the fuel estimate. */
private suspend fun loadFuel(db: MealDb): List<MealFuel> = withContext(Dispatchers.IO) {
    val now = System.currentTimeMillis()
    db.between(now - 36 * 3_600_000L, now + 60_000L).map { m ->
        val t = m.items.totals()
        MealFuel(m.timestamp, t.kcal, t.carbs, t.fat)
    }
}

/**
 * Home's fasting line. Today: hours since the last meal, what the body is using, and the next step
 * (fat taking over). Earlier days: that day's overnight fast and eating window. The full view is a tap away.
 */
@Composable
fun FastingBodyRow(db: MealDb, dayOffset: Int) {
    val context = LocalContext.current
    val openPage = LocalOpenPage.current
    val goal = remember { FastingPrefs(context).goalH }
    val date = LocalDate.now().plusDays(dayOffset.toLong())
    var data by remember(dayOffset) { mutableStateOf<Pair<List<Long>, List<FastDay>>?>(null) }
    var meals by remember { mutableStateOf<List<MealFuel>>(emptyList()) }
    LaunchedEffect(dayOffset) {
        data = loadDays(db, date, date)
        if (dayOffset == 0) meals = loadFuel(db)
    }
    val now = rememberNow()
    val open = { openPage("fasting") }
    val cs = MaterialTheme.colorScheme

    val (times, days) = data ?: run { com.example.nourio.BodyRow("⏳", "Fasting", "…", "Working it out", cs.secondary, onClick = open); return }
    val day = days.first()
    if (dayOffset != 0) {
        com.example.nourio.BodyRow(
            "⏳", "Fasting", day.fastH?.let { h1(it) } ?: "—",
            "Overnight fast before ${ratio(goal)} goal" +
                (day.windowH?.takeIf { day.meals >= 2 }?.let { "  ·  ate over ${h1(it)}" } ?: ""),
            if ((day.fastH ?: 0.0) >= goal) cs.primary else cs.secondary,
            progress = day.fastH?.let { (it / goal).toFloat().coerceIn(0f, 1f) }, onClick = open
        )
        return
    }
    val last = times.lastOrNull { it <= now }
    if (last == null || Fasting.hours(last, now) > Fasting.MAX_FAST_H) {
        com.example.nourio.BodyRow("⏳", "Fasting", "—", "Log your meals to track your fasts", cs.secondary, onClick = open)
        return
    }
    val h = Fasting.hours(last, now)
    val fuel = remember(meals, now) { Fuel.state(meals, now) }
    val next = fuel?.let { f ->
        when (f.phase) {
            FuelPhase.FED -> "meal used up ${clockFrom(f.absorbedAt, now)}"
            FuelPhase.GLYCOGEN -> "fat takes over ${clockFrom(f.fatAt, now)}"
            FuelPhase.FAT -> "ketones rise ${clockFrom(f.ketosisAt, now)}"
            FuelPhase.KETOSIS -> "in ketosis"
        }
    }
    com.example.nourio.BodyRow(
        "⏳", "Fasting · ${fuel?.phase?.short ?: "since last meal"}", Fasting.duration(h),
        listOfNotNull(next, "goal ${goal.toInt()} h").joinToString("  ·  "),
        fuel?.let { phaseColor(it.phase) } ?: cs.secondary,
        progress = (h / goal).toFloat().coerceIn(0f, 1f), onClick = open
    )
}

// ------------------------------------------------------------------ page

/** Profile > Fasting, or a tap on the Home card. */
@Composable
fun FastingPage(db: MealDb) {
    val context = LocalContext.current
    val prefs = remember { FastingPrefs(context) }
    val reminders = remember { ReminderPrefs(context) }
    var goal by remember { mutableDoubleStateOf(prefs.goalH) }
    val today = LocalDate.now()
    var data by remember { mutableStateOf<Pair<List<Long>, List<FastDay>>?>(null) }
    var analysis by remember { mutableStateOf<Pair<List<com.example.nourio.insights.DayMetrics>, Analysis>?>(null) }
    var meals by remember { mutableStateOf<List<MealFuel>>(emptyList()) }
    LaunchedEffect(Unit) {
        data = loadDays(db, today.minusDays(29), today)
        meals = loadFuel(db)
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
            else -> FastNow(last, now, goal, remember(meals, now) { Fuel.state(meals, now) })
        }
    }

    // 2. Goal
    WidgetCard("Your fasting goal") {
        Muted("Hours between your last meal and your first meal the next day. The second number is your eating window.")
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Fasting.GOALS.forEach { g ->
                FilterChip(
                    selected = goal == g,
                    onClick = { goal = g; prefs.goalH = g; Reminders.schedule(context) },
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
        var remind by remember { mutableStateOf(reminders.isOn(Reminder.FAST_START)) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Remind me", style = MaterialTheme.typography.bodyLarge)
                Muted("When your eating window closes and when you reach your goal")
            }
            Switch(checked = remind, onCheckedChange = {
                remind = it
                reminders.set(Reminder.FAST_START, it)
                Reminders.schedule(context)
            })
        }
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
