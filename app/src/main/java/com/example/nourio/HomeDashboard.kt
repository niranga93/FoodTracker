package com.example.nourio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

// ------------------------------------------------------------------ This week

/** The latest weigh-in, and how it moved over about a week (null without an earlier one). */
data class WeightLine(val kg: Double, val weekChange: Double?)

/**
 * Seven days at a glance: deficit or surplus each day and the net total, with the latest weigh-in
 * beside it, since the scale is what the week's balance is heading toward.
 */
@Composable
fun WeekCard(week: List<DayEnergy>, budgetKcal: Double, weight: WeightLine?, onOpenWeight: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ordered = week.reversed()                         // oldest on the left
    val logged = ordered.filter { it.logComplete || (it.offset == 0 && it.eaten > 0) }
    val total = logged.sumOf { it.balance }
    WidgetCard("This week") {
        WeightRow(weight, onOpenWeight)
        if (logged.isEmpty()) {
            Muted("Log full days of meals and your week appears here, day by day.", Modifier.padding(top = 12.dp))
            return@WidgetCard
        }
        Spacer(Modifier.height(14.dp))
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
            underBudget?.let { StatCell("$it/${logged.size}", "on budget", Modifier.weight(1f)) }
            best?.let { StatCell("${dayLetter(it.offset)} ${signed(it.balance)}", "best day", Modifier.weight(1.3f), cs.primary) }
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

/** One line: the latest weight and the week's change, or a nudge to weigh in. Opens Weigh-ins. */
@Composable
private fun WeightRow(w: WeightLine?, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("⚖️", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(10.dp))
        if (w == null) {
            Text("Weigh in once or twice a week to see your trend", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        } else {
            Text("${w.kg.f1()} kg", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                w.weekChange?.let { "  ${"%+.1f".format(it)} kg in a week" } ?: "  latest weigh-in",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f)
            )
        }
        Text("Log ›", style = MaterialTheme.typography.labelMedium, color = cs.primary)
    }
}

