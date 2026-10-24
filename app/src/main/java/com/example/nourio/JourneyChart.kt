package com.example.nourio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.example.nourio.energy.JourneySeries
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Start → estimated and actual weight → target. The estimate comes from calories eaten minus burned;
 * the actual line is the weigh-ins. Both are drawn separately on one chart, with the target dashed.
 * [series] is null while it loads, or when the profile is missing height, age or sex ([missing] says why).
 */
@Composable
fun JourneyCard(
    start: Double, lastWeighIn: Double, target: Double, pace: Double,
    series: JourneySeries?, loading: Boolean, missing: String?, onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val estColor = cs.primary
    val actColor = cs.tertiary
    val begin = series?.startKg ?: start
    val estNow = series?.estimatedNow
    val actNow = series?.actualNow ?: lastWeighIn

    AppCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Journey to ${target.f1()} kg", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                )
                val left = abs(actNow - target)
                Pill(if (left < 0.1) "Reached" else "${left.f1()} kg to go")
            }
            Spacer(Modifier.height(12.dp))

            // The pace each line shows, and when the scale's pace reaches the target
            val scalePace = series?.scaleKgPerWeek()
            val logPace = series?.estimatedKgPerWeek()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaceTile("Scale", scalePace?.let { perWeek(it) } ?: "–", "per week", actColor, Modifier.weight(1f))
                PaceTile("Your log", logPace?.let { perWeek(it) } ?: "–", "per week", estColor, Modifier.weight(1f))
                val eta = etaText(actNow, target, scalePace)
                PaceTile("Target", eta.first, eta.second, cs.onSurface, Modifier.weight(1f))
            }
            verdict(scalePace, logPace, series?.actual?.size ?: 0, series?.gapKcal)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
            Spacer(Modifier.height(12.dp))

            // Four equal, centred columns; the mark under each name is how that value is drawn in the chart,
            // so this row is also the chart's legend
            Row(Modifier.fillMaxWidth()) {
                Point("Start", begin, cs.onSurface, Modifier.weight(1f)) { Mark(MarkKind.START, cs.onSurfaceVariant) }
                Point("Estimated", estNow, estColor, Modifier.weight(1f)) { Mark(MarkKind.LINE, estColor) }
                Point("Actual", actNow, actColor, Modifier.weight(1f)) { Mark(MarkKind.DOT, actColor) }
                Point("Target", target, cs.onSurface, Modifier.weight(1f)) { Mark(MarkKind.DASHED, cs.outline) }
            }

            Spacer(Modifier.height(12.dp))
            when {
                series != null && series.estimated.size >= 2 -> {
                    JourneyChart(series, target, estColor, actColor)
                    if (series.gapKcal != null) Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Mark(MarkKind.DOTTED, estColor.copy(alpha = 0.6f))
                        Spacer(Modifier.width(6.dp))
                        Text("Log only, without the missing calories", style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant)
                    }
                }
                loading -> Note("Working out your estimate from your meals and activity…")
                missing != null -> Note(missing)
                else -> Note("The estimated line appears after your first full day of logging since your first weigh-in.")
            }

            val toGo = abs(actNow - target)
            val weeks = if (pace > 0 && toGo >= 0.1) ceil(toGo / pace).toInt() else null
            val details = buildList {
                weeks?.let { add("Your plan: ${"%.2f".format(pace).trimEnd('0').trimEnd('.')} kg a week, about $it ${if (it == 1) "week" else "weeks"} to go.") }
                series?.gapKcal?.let { g ->
                    add("The solid line adds about ${"%,.0f".format(abs(g))} kcal a day ${if (g > 0) "to" else "from"} your log, " +
                        "worked out from your weigh-ins; the dotted line is the log alone." +
                        if (g > 0) " Cooking oil, drinks, sauces and portion sizes are the usual reasons." else "")
                }
                if (estNow != null && series?.actualNow != null && scalePace == null) {
                    val gap = actNow - estNow
                    if (abs(gap) >= 0.5) add("Your scale is ${abs(gap).f1()} kg ${if (gap > 0) "above" else "below"} the estimate; water and " +
                        "unlogged food usually explain it.")
                }
                series?.riseKg?.takeIf { series.actual.isNotEmpty() }?.let { rise ->
                    add("Later weigh-ins are shown as morning weights (you're about ${rise.f1()} kg heavier by bedtime). Weighing on waking is clearest.")
                }
                series?.unloggedDays?.takeIf { it > 0 }?.let {
                    add("$it partly logged ${if (it == 1) "day counts" else "days count"} as no change.")
                }
            }
            if (toGo < 0.1) Text("You've reached your target. Nice work.", style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            if (details.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { open = !open }) {
                        Text(if (open) "Hide details" else "How this is worked out")
                    }
                }
                // The details themselves read best left-aligned, as a short list
                if (open) details.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun PaceTile(label: String, value: String, sub: String, color: Color, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(cs.surfaceVariant.copy(alpha = 0.5f)).padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(sub, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
    }
}

private fun perWeek(kg: Double): String =
    if (abs(kg) < 0.05) "steady" else "${if (kg > 0) "+" else "−"}${"%.2f".format(abs(kg))} kg"

/** When the scale's current pace reaches the target: (value, caption). */
internal fun etaText(nowKg: Double, target: Double, pace: Double?): Pair<String, String> {
    val toGo = target - nowKg
    if (abs(toGo) < 0.1) return "Reached" to "keep it steady"
    if (pace == null) return "–" to "needs a week of weigh-ins"
    if (abs(pace) < 0.05) return "Not yet" to "scale is flat"
    if (pace * toGo < 0) return "Not yet" to "scale going ${if (pace > 0) "up" else "down"}"
    val days = (toGo / pace * 7).toInt().coerceAtMost(3650)
    return dateOf(days) to "at the scale's pace"
}

/**
 * Does the log explain the scale? A kg a week is about 1,000 kcal a day (7,000 kcal per kg of mixed tissue),
 * so the difference in pace says roughly how much food or burn the log is missing.
 */
internal fun verdict(scale: Double?, log: Double?, weighIns: Int, gapKcal: Double? = null): String? {
    if (scale == null || log == null) return null
    val diff = scale - log
    val early = if (weighIns < 10) " (early estimate)" else ""
    // The weigh-in filter's estimate when it's reliable (it uses every weigh-in); otherwise from the two paces
    val kcalValue = gapKcal?.takeIf { (it > 0) == (diff > 0) }?.let { abs(it) } ?: (abs(diff) * 1000)
    val kcal = "%,d".format((kcalValue / 50).toInt() * 50)
    return when {
        abs(diff) < 0.15 -> "Your log and your scale agree."
        diff > 0 -> "About $kcal kcal a day missing from your log$early."
        else -> "You're losing faster than your log says: about $kcal kcal a day more burn, or water$early."
    }
}

@Composable
private fun Point(label: String, kg: Double?, color: Color, modifier: Modifier, mark: @Composable () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(kg?.let { "${it.f1()} kg" } ?: "–", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        mark()
    }
}

@Composable
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

private enum class MarkKind { START, LINE, DOTTED, DOT, DASHED }

/** A small sample of how a value is drawn in the chart, all the same size so they line up. */
@Composable
private fun Mark(kind: MarkKind, color: Color) {
    Canvas(Modifier.size(width = 22.dp, height = 8.dp)) {
        val y = size.height / 2
        val stroke = 3.dp.toPx()
        when (kind) {
            MarkKind.LINE -> drawLine(color, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
            MarkKind.DOTTED -> drawLine(color, Offset(0f, y), Offset(size.width, y), stroke / 1.5f, StrokeCap.Round,
                PathEffect.dashPathEffect(floatArrayOf(2f, 6f)))
            MarkKind.DASHED -> drawLine(color, Offset(0f, y), Offset(size.width, y), stroke / 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f)))
            MarkKind.DOT -> drawCircle(color, radius = size.height / 2, center = Offset(size.width / 2, y))
            MarkKind.START -> Unit          // not drawn in the chart: an empty slot keeps the row aligned
        }
    }
}

private fun dateOf(offset: Int): String {
    val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
    return SimpleDateFormat("d MMM", Locale.getDefault()).format(c.time)
}

/** Both weight lines over the days since the start, with the kg range on the left and dates below. */
@Composable
private fun JourneyChart(s: JourneySeries, target: Double, est: Color, act: Color) {
    val cs = MaterialTheme.colorScheme
    val values = s.estimated.map { it.kg } + s.actual.map { it.second } + target +
        (if (s.gapKcal != null) s.logOnly.map { it.kg } else emptyList())
    val pad = 0.5
    val minV = values.min() - pad
    val maxV = values.max() + pad
    val minX = s.startOffset.toDouble()
    // Today's weigh-ins sit part-way through today, so the axis runs to the latest one (or the end of today)
    val maxX = maxOf(0.0, s.actual.maxOfOrNull { it.first } ?: 0.0).coerceAtLeast(minX + 1)
    val grid = cs.outlineVariant
    val targetColor = cs.outline

    Row {
        Column(Modifier.height(170.dp).padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(maxV.f1(), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Text(minV.f1(), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(170.dp)) {
                val w = size.width
                val h = size.height
                fun px(x: Double) = (((x - minX) / (maxX - minX)) * w).toFloat()
                fun py(v: Double) = (h - ((v - minV) / (maxV - minV)) * h).toFloat()

                // Light grid: top, middle, bottom
                listOf(0f, h / 2, h).forEach { y -> drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f) }

                // Target
                drawLine(
                    targetColor, Offset(0f, py(target)), Offset(w, py(target)), strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
                )

                // From the log alone, faint, when the main line includes the logging gap
                val dotted = PathEffect.dashPathEffect(floatArrayOf(4f, 8f))
                if (s.gapKcal != null) for (i in 1 until s.logOnly.size) {
                    val a = s.logOnly[i - 1]; val b = s.logOnly[i]
                    drawLine(est.copy(alpha = 0.3f), Offset(px(a.offset.toDouble()), py(a.kg)), Offset(px(b.offset.toDouble()), py(b.kg)),
                        strokeWidth = 2.dp.toPx(), pathEffect = dotted)
                }
                // Estimated: solid where the day was logged, dotted where it counted as no change
                for (i in 1 until s.estimated.size) {
                    val a = s.estimated[i - 1]
                    val b = s.estimated[i]
                    drawLine(
                        if (b.logged) est else est.copy(alpha = 0.45f),
                        Offset(px(a.offset.toDouble()), py(a.kg)), Offset(px(b.offset.toDouble()), py(b.kg)),
                        strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = if (b.logged) null else dotted
                    )
                }

                // Actual: weigh-ins joined by a thin line, each one a dot
                val pts = s.actual.map { Offset(px(it.first), py(it.second)) }
                for (i in 1 until pts.size) drawLine(act.copy(alpha = 0.5f), pts[i - 1], pts[i], strokeWidth = 1.5.dp.toPx())
                pts.forEach { drawCircle(act, radius = 4.dp.toPx(), center = it) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(dateOf(s.startOffset), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("Today", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
    }
}
