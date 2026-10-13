package com.example.foodtracker

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.foodtracker.data.Meal
import com.example.foodtracker.data.MealDb
import com.example.foodtracker.data.ProfileStore
import com.example.foodtracker.data.Totals
import com.example.foodtracker.data.WaterStore
import com.example.foodtracker.data.WeightDb
import com.example.foodtracker.data.WeightEntry
import com.example.foodtracker.energy.DayEnergy
import com.example.foodtracker.energy.EnergyModel
import com.example.foodtracker.energy.EnergyRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max

private val SrcCarbs = Color(0xFFFFB300)
private val SrcProtein = Color(0xFF43A047)
private val SrcFat = Color(0xFF1E88E5)

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** Card with a title row and consistent padding. */
@Composable
fun WidgetCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    AppCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                trailing?.invoke()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

// ---------------------------------------------------------------- Home

/** The one ring in the app: how much of today's budget is used. */
@Composable
fun HeroRing(eaten: Double, goal: Double, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val over = goal > 0 && eaten > goal
    val color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val target = if (goal > 0) (eaten / goal).toFloat().coerceIn(0f, 1f) else 0f
    val sweep by animateFloatAsState(targetValue = target, animationSpec = tween(700), label = "hero")

    Box(modifier.size(176.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 16.dp.toPx()
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(
                color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arc,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            if (sweep > 0f) {
                drawArc(
                    color = color, startAngle = -90f, sweepAngle = 360f * sweep, useCenter = false,
                    topLeft = Offset(inset, inset), size = arc,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (goal > 0) {
                val left = goal - eaten
                Text(abs(left).f0(), fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (left >= 0) "kcal left" else "kcal over",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(eaten.f0(), fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text(
                    "kcal eaten",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CumulativeChart(values: List<Double>, counted: List<Boolean>) {
    val line = MaterialTheme.colorScheme.primary
    val dot = MaterialTheme.colorScheme.secondary
    val zero = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        if (values.size < 2) return@Canvas
        val maxAbs = max(values.maxOf { abs(it) }, 300.0)
        val w = size.width
        val hh = size.height.toDouble()
        fun x(i: Int) = w * i / (values.size - 1)
        fun y(v: Double) = (hh / 2 - (v / maxAbs) * (hh / 2) * 0.9).toFloat()

        drawLine(
            zero, Offset(0f, (hh / 2).toFloat()), Offset(w, (hh / 2).toFloat()),
            strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        )
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        values.forEachIndexed { i, v ->
            if (counted[i]) drawCircle(dot, 4.dp.toPx(), Offset(x(i), y(v)))
        }
    }
}

/** week: newest first. Running total of the energy balance over fully logged days. */
@Composable
fun BalanceTrendCard(week: List<DayEnergy>) {
    val ordered = week.reversed()
    val counted = ordered.map { it.logComplete }
    val cum = ordered.runningFold(0.0) { acc, d -> acc + if (d.logComplete) d.balance else 0.0 }.drop(1)
    val total = cum.lastOrNull() ?: 0.0
    WidgetCard(
        "This week's balance",
        trailing = {
            if (counted.any { it }) Pill("≈ ${"%+.2f".format(total / EnergyModel.KCAL_PER_KG)} kg")
        }
    ) {
        if (counted.none { it }) {
            Hint("Log full days to see the running total.")
        } else {
            Text("${"%+,.0f".format(total)} kcal", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            CumulativeChart(cum, counted)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                ordered.forEach { d ->
                    Text(
                        dayLetter(d.offset),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Intake

@Composable
private fun Donut(parts: List<Pair<Double, Color>>, top: String, bottom: String, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val total = parts.sumOf { it.first }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 18.dp.toPx()
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arc, style = Stroke(width = stroke))
            if (total > 0) {
                var start = -90f
                parts.forEach { (v, c) ->
                    val sweep = (v / total * 360.0).toFloat()
                    drawArc(color = c, startAngle = start, sweepAngle = sweep, useCenter = false,
                        topLeft = topLeft, size = arc, style = Stroke(width = stroke))
                    start += sweep
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(top, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(bottom, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SourceRow(label: String, share: Double, grams: Double, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${(share * 100).f0()}%  ·  ${grams.f0()} g", style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium)
    }
}

@Composable
fun CalorieSourcesCard(t: Totals) {
    val c = t.carbs * 4.0
    val p = t.protein * 4.0
    val f = t.fat * 9.0
    val total = c + p + f
    if (total <= 0) return
    WidgetCard("Where your calories come from") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Donut(listOf(c to SrcCarbs, p to SrcProtein, f to SrcFat), t.kcal.f0(), "kcal", Modifier.size(120.dp))
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SourceRow("Carbs", c / total, t.carbs, SrcCarbs)
                SourceRow("Protein", p / total, t.protein, SrcProtein)
                SourceRow("Fat", f / total, t.fat, SrcFat)
            }
        }
    }
}

@Composable
private fun LimitBar(label: String, value: Double, goal: Double) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${value.f0()} / ${goal.f0()} g",
                style = MaterialTheme.typography.bodyMedium,
                color = if (value > goal) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        SlimBar((value / goal).toFloat(), value > goal)
    }
}

@Composable
fun LimitsCard(sugar: Double, sugarGoal: Double, fat: Double, fatGoal: Double) {
    if (sugarGoal <= 0 && fatGoal <= 0) return
    WidgetCard("Staying within your limits") {
        if (sugarGoal > 0) LimitBar("Sugar", sugar, sugarGoal)
        if (fatGoal > 0) LimitBar("Fat", fat, fatGoal)
    }
}

@Composable
private fun HBarRow(label: String, value: String, fraction: Float, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.34f)
        )
        Box(
            Modifier.weight(0.46f).padding(horizontal = 8.dp).height(10.dp).clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight().background(color))
        }
        Text(
            value, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End,
            modifier = Modifier.weight(0.2f)
        )
    }
}

@Composable
fun MealTimingCard(meals: List<Meal>) {
    if (meals.isEmpty()) return
    val labels = listOf("Morning", "Midday", "Evening", "Late night")
    val sums = DoubleArray(4)
    meals.forEach { m ->
        val hour = Calendar.getInstance().apply { timeInMillis = m.timestamp }.get(Calendar.HOUR_OF_DAY)
        val block = when (hour) { in 5..10 -> 0; in 11..14 -> 1; in 15..20 -> 2; else -> 3 }
        sums[block] += m.totals.kcal
    }
    val maxV = sums.max().coerceAtLeast(1.0)
    WidgetCard("When you ate") {
        labels.forEachIndexed { i, label ->
            HBarRow(label, if (sums[i] > 0) sums[i].f0() else "—", (sums[i] / maxV).toFloat(),
                MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun BiggestItemsCard(meals: List<Meal>) {
    val top = meals.flatMap { it.items }
        .groupBy { it.name.trim().lowercase() }
        .map { (_, list) -> list.first().name to list.sumOf { it.kcal } }
        .sortedByDescending { it.second }
        .take(5)
    if (top.isEmpty()) return
    val maxV = top.first().second.coerceAtLeast(1.0)
    WidgetCard("Biggest items today") {
        top.forEach { (name, kcal) ->
            HBarRow(name, kcal.f0(), (kcal / maxV).toFloat(), MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
fun HydrationCard(dayOffset: Int) {
    val context = LocalContext.current
    val store = remember { WaterStore(context) }
    var ml by remember(dayOffset) { mutableStateOf(store.get(dayOffset)) }
    var goal by remember { mutableStateOf(store.goalMl) }
    var edit by remember { mutableStateOf(false) }
    val nf = remember { NumberFormat.getIntegerInstance() }

    if (edit) {
        var text by remember { mutableStateOf(goal.toString()) }
        AlertDialog(
            onDismissRequest = { edit = false },
            title = { Text("Daily water goal") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Goal") }, suffix = { Text("ml") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = text.trim().toIntOrNull()
                    if (v != null && v in 250..10000) { store.goalMl = v; goal = v; edit = false }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { edit = false }) { Text("Cancel") } }
        )
    }

    val glasses = ((goal + 249) / 250).coerceIn(1, 12)
    WidgetCard(
        "Hydration",
        trailing = { TextButton(onClick = { edit = true }) { Text("Goal ${nf.format(goal)} ml") } }
    ) {
        Text("${nf.format(ml)} ml", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 0 until glasses) {
                val frac = ((ml - i * 250) / 250f).coerceIn(0f, 1f)
                Box(
                    Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ml = store.add(dayOffset, -250) }, modifier = Modifier.weight(1f)) { Text("−250") }
            Button(onClick = { ml = store.add(dayOffset, 250) }, modifier = Modifier.weight(1f)) { Text("+250") }
            Button(onClick = { ml = store.add(dayOffset, 500) }, modifier = Modifier.weight(1f)) { Text("+500") }
        }
    }
}

// ---------------------------------------------------------------- Burned

@Composable
private fun ForecastChart(history: List<Pair<Double, Double>>, w0: Double, low: Double, mid: Double, high: Double) {
    val main = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        val minX = (history.minOfOrNull { it.first } ?: -14.0).coerceAtMost(-7.0)
        val maxX = 30.0
        val ys = history.map { it.second } + listOf(w0, w0 + low, w0 + mid, w0 + high)
        val minY = ys.min() - 0.3
        val maxY = ys.max() + 0.3
        val w = size.width
        val h = size.height
        fun px(x: Double) = (((x - minX) / (maxX - minX)) * w).toFloat()
        fun py(y: Double) = (h - ((y - minY) / (maxY - minY)) * h).toFloat()

        drawLine(grid, Offset(0f, h), Offset(w, h), strokeWidth = 1.dp.toPx())
        val nowX = px(0.0)
        drawLine(grid, Offset(nowX, 0f), Offset(nowX, h), strokeWidth = 1.dp.toPx())

        // likely range, widening over time
        val cone = Path().apply {
            moveTo(nowX, py(w0))
            lineTo(px(maxX), py(w0 + high))
            lineTo(px(maxX), py(w0 + low))
            close()
        }
        drawPath(cone, main.copy(alpha = 0.18f))
        drawLine(
            accent, Offset(nowX, py(w0)), Offset(px(maxX), py(w0 + mid)),
            strokeWidth = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
        )

        if (history.size >= 2) {
            val path = Path()
            history.forEachIndexed { i, p -> if (i == 0) path.moveTo(px(p.first), py(p.second)) else path.lineTo(px(p.first), py(p.second)) }
            drawPath(path, main, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        }
        history.forEach { drawCircle(main, 3.5.dp.toPx(), Offset(px(it.first), py(it.second))) }
    }
}

@Composable
fun OutlookCard(db: MealDb) {
    val context = LocalContext.current
    val profile = remember { ProfileStore(context).load() }
    val (body, reason) = remember { EnergyRepo.body(profile) }
    var result by remember { mutableStateOf<ForecastResult?>(null) }
    var weights by remember { mutableStateOf<List<WeightEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (body != null) {
            result = loadForecast(context, db, body)
            weights = withContext(Dispatchers.IO) { WeightDb(context).all() }
        }
        loaded = true
    }

    WidgetCard("Where your weight is heading") {
        val p = result?.prediction
        when {
            body == null -> Hint(reason ?: "")
            !loaded -> Hint("Calculating…")
            p == null -> Hint("Log 3 full days of meals to see this.")
            else -> {
                val w0 = weights.lastOrNull()?.kg ?: profile.weightKg
                val now = System.currentTimeMillis()
                val history = weights.takeLast(14)
                    .map { ((it.ts - now) / 86_400_000.0) to it.kg }
                    .filter { it.first >= -28.0 }
                Row(Modifier.fillMaxWidth()) {
                    Metric("Pace", "${"%+.1f".format(p.weeklyKg)} kg/wk", Modifier.weight(1f))
                    Metric("In 30 days", "${(w0 + p.change30Kg).f1()} kg", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                ForecastChart(history, w0, p.low30Kg, p.change30Kg, p.high30Kg)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendDot(MaterialTheme.colorScheme.primary, "Weigh-ins")
                    LegendDot(MaterialTheme.colorScheme.secondary, "Outlook")
                }
                if (p.largeDeficit) {
                    Hint("Large daily deficit. If it's accurate, talk to a doctor or dietitian.",
                        Modifier.padding(top = 6.dp), error = true)
                }
                Expandable("How it's calculated") {
                    Hint("Based on ${p.usedDays} logged days: ${p.avgEaten.f0()} kcal eaten, ${p.avgBurned.f0()} burned a day.")
                    if (result?.calibration != null || (result?.factor ?: 1.0) != 1.0) Hint("Adjusted using your weigh-ins.")
                    Hint("Estimate only. About 7,700 kcal per kg.")
                }
            }
        }
    }
}
