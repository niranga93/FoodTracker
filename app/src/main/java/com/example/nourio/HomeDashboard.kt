package com.example.nourio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.nourio.data.MealDb
import com.example.nourio.data.WeightDb
import com.example.nourio.energy.Body
import com.example.nourio.energy.DayEnergy
import com.example.nourio.energy.EnergyModel
import com.example.nourio.energy.Prediction
import com.example.nourio.plan.PlanStore
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

private val nf: NumberFormat get() = NumberFormat.getIntegerInstance()
private fun n(d: Double) = nf.format(d.roundToLong())
private fun signed(d: Double) = (if (d >= 0) "+" else "−") + n(abs(d))
private const val STEPS_PER_MINUTE = 100.0

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) = Text(
    text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier
)

/** A highlighted line that tells the user what to do next. */
@Composable
private fun Nudge(text: String, color: Color) {
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

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier, color: Color? = null) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color ?: MaterialTheme.colorScheme.onSurface)
        Muted(label)
    }
}

// ------------------------------------------------------------------ Move

/** Steps ring, what the activity burned, and the walk that would balance the day or reach the goal. */
@Composable
fun MoveCard(day: DayEnergy?, eaten: Double, body: Body?, dayOffset: Int, stepGoal: Long) {
    val cs = MaterialTheme.colorScheme
    val steps = day?.steps ?: 0L
    val goal = stepGoal
    val progress = (steps.toFloat() / goal).coerceIn(0f, 1f)
    val today = dayOffset == 0
    WidgetCard(if (today) "Move today" else "Movement, ${dayTitle(dayOffset)}") {
        if (day == null || body == null) {
            Muted("Add your details in Profile and connect Health Connect to see your steps and what they burn.")
            return@WidgetCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(118.dp), contentAlignment = Alignment.Center) {
                val track = cs.surfaceVariant
                val color = if (steps >= goal) cs.primary else cs.tertiary
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 12.dp.toPx()
                    val arc = Size(size.width - stroke, size.height - stroke)
                    val tl = Offset(stroke / 2, stroke / 2)
                    drawArc(track, 0f, 360f, false, tl, arc, style = Stroke(stroke))
                    drawArc(color, -90f, 360f * progress, false, tl, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(n(steps.toDouble()), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Muted("of ${n(goal.toDouble())}")
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCell("${n((day.burned - day.resting).coerceAtLeast(0.0))} kcal", "burned by activity", color = cs.secondary)
                StatCell("${"%.1f".format(steps * EnergyModel.stepKm(body))} km", "walked, about")
                StatCell("${(progress * 100).toInt()}%", "of your step goal", color = if (steps >= goal) cs.primary else cs.tertiary)
            }
        }

        // What would help most right now
        val surplus = if (eaten > 0) eaten - day.burned else 0.0
        val toBalance = if (surplus > 0) ceil(surplus / EnergyModel.kcalPerStep(body)).toLong() else 0L
        val toGoal = (goal - steps).coerceAtLeast(0)
        val (msg, color) = when {
            !today && steps >= goal -> "Step goal reached that day. Well done." to cs.primary
            !today -> "${n(toGoal.toDouble())} steps short of the goal that day." to cs.tertiary
            steps >= goal && surplus <= 0 -> "Step goal smashed and you're in a deficit. Brilliant day!" to cs.primary
            steps >= goal -> "Step goal reached! Every extra step is a bonus." to cs.primary
            surplus > 0 && toBalance <= 12_000 -> "A ${ceil(toBalance / STEPS_PER_MINUTE).toLong()}-minute walk (about ${n(toBalance.toDouble())} steps) would balance what you've eaten today." to cs.secondary
            else -> "${n(toGoal.toDouble())} steps to your goal. That's about ${ceil(toGoal / STEPS_PER_MINUTE).toLong()} minutes of walking." to cs.tertiary
        }
        Nudge(msg, color)
        if (today && steps < goal) {
            val tenMin = (10 * STEPS_PER_MINUTE).toLong()
            Muted(
                "Quick win: a 10-minute walk is about ${n(tenMin.toDouble())} steps and ${n(EnergyModel.stepKcal(tenMin, body))} kcal.",
                Modifier.padding(top = 8.dp)
            )
        }
    }
}

// ------------------------------------------------------------------ This week

/** Seven days at a glance: deficit or surplus each day, the net total, and how the week compares. */
@Composable
fun WeekCard(week: List<DayEnergy>, budgetKcal: Double) {
    val cs = MaterialTheme.colorScheme
    val ordered = week.reversed()                         // oldest on the left
    val logged = ordered.filter { it.logComplete || (it.offset == 0 && it.eaten > 0) }
    val total = logged.sumOf { it.balance }
    WidgetCard(
        "This week",
        trailing = { if (logged.isNotEmpty()) Pill("≈ ${"%+.2f".format(total / EnergyModel.KCAL_PER_KG)} kg") }
    ) {
        if (logged.isEmpty()) {
            Muted("Log full days of meals and your week appears here, day by day.")
            return@WidgetCard
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${signed(total)} kcal", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = if (total <= 0) cs.primary else cs.error)
            Muted(if (total <= 0) "  net deficit" else "  net surplus", Modifier.padding(bottom = 4.dp))
        }
        Spacer(Modifier.height(14.dp))

        // One chip per day: green = burned more than eaten, red = ate more, grey = not logged
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ordered.forEach { d ->
                val counted = d.logComplete || (d.offset == 0 && d.eaten > 0)
                val c = when {
                    !counted -> cs.surfaceVariant
                    d.balance <= 0 -> cs.primary
                    else -> cs.error
                }
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = if (counted) 0.16f else 0.5f))
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(dayLetter(d.offset), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (counted) c else cs.onSurfaceVariant.copy(alpha = 0.3f)))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (counted) "%+.1fk".format(d.balance / 1000) else "—",
                        style = MaterialTheme.typography.labelSmall, color = if (counted) c else cs.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        val underBudget = if (budgetKcal > 0) logged.count { it.eaten <= budgetKcal } else null
        val best = logged.minByOrNull { it.balance }
        Row(Modifier.fillMaxWidth()) {
            StatCell("${logged.size}/7", "days logged", Modifier.weight(1f))
            StatCell(n(logged.map { it.eaten }.average()), "avg eaten", Modifier.weight(1f), cs.primary)
            StatCell(n(logged.map { it.burned }.average()), "avg burned", Modifier.weight(1f), cs.secondary)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            underBudget?.let { StatCell("$it/${logged.size}", "days on budget", Modifier.weight(1f)) }
            best?.let { StatCell("${dayLetter(it.offset)}  ${signed(it.balance)}", "best day", Modifier.weight(2f), cs.primary) }
        }

        val kg = abs(total) / EnergyModel.KCAL_PER_KG
        Nudge(
            when {
                total <= 0 && logged.size >= 4 -> "You've burned ${n(-total)} kcal more than you ate this week, roughly ${"%.2f".format(kg)} kg. Keep this rhythm going!"
                total <= 0 -> "Good start: ${n(-total)} kcal in deficit so far. Log every day to see the full picture."
                else -> "You're ${n(total)} kcal over this week. Two lighter days or a few extra walks would even it out."
            },
            if (total <= 0) cs.primary else cs.secondary
        )
    }
}

// ------------------------------------------------------------------ Weight outlook

/** Weigh-ins, the trend line, where it's heading in 30 days, and when you'd reach your target. */
@Composable
fun WeightOutlookCard(db: MealDb, body: Body?) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val locale = LocalConfiguration.current.locales[0]       // follows a language change
    var entries by remember { mutableStateOf(WeightDb(context).all()) }
    var prediction by remember { mutableStateOf<Prediction?>(null) }
    var showLog by remember { mutableStateOf(false) }
    val target = remember { PlanStore(context).targetKg }
    LaunchedEffect(Unit) { if (body != null) prediction = loadForecast(context, db, body).prediction }
    if (showLog) LogWeightDialog(onDismiss = { showLog = false }, onSaved = { entries = WeightDb(context).all() })

    WidgetCard("Weight outlook", trailing = { TextButton(onClick = { showLog = true }) { Text("Log weight") } }) {
        if (entries.isEmpty()) {
            Muted("Log your weight once or twice a week. Your trend and a 30-day outlook appear here.")
            return@WidgetCard
        }
        val now = entries.last().kg
        val trend = EnergyModel.trend(entries.takeLast(30).map { it.kg })
        val weekAgo = entries.lastOrNull { it.ts <= System.currentTimeMillis() - 7 * 86_400_000L }?.kg
        val p = prediction

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("${now.f1()} kg", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Muted(weekAgo?.let { "${"%+.1f".format(now - it)} kg in 7 days" } ?: "${entries.size} weigh-ins")
            }
            p?.let { Pill("${"%+.2f".format(it.weeklyKg)} kg/week") }
        }
        Spacer(Modifier.height(12.dp))
        OutlookChart(trend, p?.let { now + it.change30Kg }, target.takeIf { it > 0 }, Modifier.fillMaxWidth().height(120.dp))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            LegendDot(cs.primary, "Trend")
            Spacer(Modifier.width(12.dp))
            LegendDot(cs.secondary, "Next 30 days")
            if (target > 0) { Spacer(Modifier.width(12.dp)); LegendDot(cs.onSurfaceVariant, "Target") }
        }

        if (p != null) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                StatCell("${(now + p.change30Kg).f1()} kg", "in 30 days", Modifier.weight(1f), cs.secondary)
                StatCell("${(now + p.low30Kg).f1()}–${(now + p.high30Kg).f1()}", "likely range", Modifier.weight(1f))
                if (target > 0) StatCell("${abs(now - target).f1()} kg", "to target", Modifier.weight(1f))
            }
        }

        // Arrival date when the trend points toward the target
        val towards = p != null && target > 0 && abs(now - target) >= 0.2 &&
            ((target < now && p.weeklyKg < -0.02) || (target > now && p.weeklyKg > 0.02))
        val msg = when {
            p == null -> "Log at least 3 full days of meals and your 30-day outlook unlocks."
            target > 0 && abs(now - target) < 0.2 -> "You're at your target weight. Now it's about keeping it steady."
            towards -> {
                val weeks = abs(now - target) / abs(p.weeklyKg)
                val date = LocalDate.now().plusDays((weeks * 7).roundToLong())
                "At this pace you'll reach ${target.f1()} kg around ${date.format(DateTimeFormatter.ofPattern("d MMMM", locale))}. Stay consistent!"
            }
            target > 0 -> "Your trend isn't heading toward ${target.f1()} kg yet. More vegetables and protein, fewer sweet drinks, and a daily walk make the biggest difference."
            p.largeDeficit -> "You're eating much less than you burn. Losing too fast is hard to keep up, so make sure you eat enough."
            else -> "Weigh in at the same time of day, a few times a week, for the most accurate trend."
        }
        Nudge(msg, if (towards || (target > 0 && abs(now - target) < 0.2)) cs.primary else cs.tertiary)
    }
}

/** Weigh-in trend, a dashed line to the 30-day projection, and the target as a faint dashed line. */
@Composable
private fun OutlookChart(trend: List<Double>, projected: Double?, target: Double?, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val line = cs.primary
    val proj = cs.secondary
    val goal = cs.onSurfaceVariant.copy(alpha = 0.6f)
    Canvas(modifier) {
        if (trend.isEmpty()) return@Canvas
        val values = trend + listOfNotNull(projected, target)
        val minV = values.min() - 0.3
        val maxV = values.max() + 0.3
        val w = size.width
        val h = size.height
        val histW = if (projected != null) w * 0.62f else w
        fun y(v: Double) = (h - (v - minV) / (maxV - minV) * h).toFloat()
        fun x(i: Int) = if (trend.size == 1) histW / 2 else histW * i / (trend.size - 1)

        target?.let {
            drawLine(goal, Offset(0f, y(it)), Offset(w, y(it)), 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
        }
        val path = Path()
        trend.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        drawPath(path, line, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        val last = Offset(x(trend.lastIndex), y(trend.last()))
        projected?.let {
            val end = Offset(w - 6.dp.toPx(), y(it))
            drawLine(proj, last, end, 3.dp.toPx(), cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
            drawCircle(proj, 5.dp.toPx(), end)
        }
        drawCircle(line, 6.dp.toPx(), last)
    }
}
