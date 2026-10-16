package com.example.nourio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nourio.data.Goals
import com.example.nourio.data.Totals
import com.example.nourio.health.SleepData
import com.example.nourio.insights.Metric
import java.text.NumberFormat

// ------------------------------------------------------------------ Today

/**
 * The day at a glance. Energy first (what's left of the budget), then the four nutrients that matter
 * most day to day, then the one thing to work on. The grade is a small badge, not the headline.
 */
@Composable
fun TodayCard(
    report: Report,
    dayOffset: Int,
    totals: Totals,
    budget: Double,
    burned: Double?,
    goals: Goals,
    weightKg: Double,
    waterMl: Int,
    waterGoalMl: Int,
    onAddWater: (() -> Unit)?,
    onOpenBudget: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val nf = NumberFormat.getIntegerInstance()
    val eaten = totals.kcal
    val share = Grading.expectedShare(dayOffset == 0, Grading.nowHour())

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            // 1. Energy
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (dayOffset == 0) "TODAY" else dayTitle(dayOffset).uppercase()),
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp, color = cs.onSurfaceVariant
                    )
                    if (budget > 0) {
                        val left = budget - eaten
                        Text(
                            if (left >= 0) "${nf.format(left.toLong())} kcal ${if (dayOffset == 0) "left" else "under"}"
                            else "${nf.format((-left).toLong())} kcal over",
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            color = if (left >= 0) cs.onSurface else cs.error
                        )
                        Text(
                            "${nf.format(eaten.toLong())} eaten of ${nf.format(budget.toLong())} kcal",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                        )
                    } else {
                        Text("${nf.format(eaten.toLong())} kcal eaten", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                }
                GradeBadge(report)
            }
            if (budget > 0) {
                Spacer(Modifier.height(12.dp))
                SlimBar((eaten / budget).toFloat(), eaten > budget)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    burned?.let {
                        "Burned ${nf.format(it.toLong())}" + if (eaten > 0) "  ·  balance ${"%+,.0f".format(eaten - it)} kcal" else ""
                    } ?: "Add your details to see calories burned",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f)
                )
                if (budget > 0) Text(
                    "Budget ›", style = MaterialTheme.typography.labelMedium, color = cs.primary,
                    modifier = Modifier.clickable(onClick = onOpenBudget).padding(vertical = 4.dp)
                )
            }

            // 2. The nutrients that matter most after calories
            HorizontalDivider(Modifier.padding(vertical = 14.dp))
            val protein = goals.protein.takeIf { it > 0 } ?: (weightKg * 0.8).takeIf { it > 0 } ?: 50.0
            val fiber = goals.fiber.takeIf { it > 0 } ?: 25.0
            val sugar = goals.addedSugar.takeIf { it > 0 } ?: 50.0
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Nutrient("Protein", totals.protein, protein, "g", minimum = true, share, Modifier.weight(1f))
                Nutrient("Fiber", totals.fiber, fiber, "g", minimum = true, share, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Nutrient("Added sugar", totals.addedSugar, sugar, "g", minimum = false, share, Modifier.weight(1f))
                Nutrient(
                    "Water", waterMl.toDouble(), waterGoalMl.toDouble(), "ml", minimum = true, share, Modifier.weight(1f),
                    action = onAddWater?.let { add -> { Text("+250", style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold, color = cs.primary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = add)
                            .background(cs.primary.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp)) } }
                )
            }

            // 3. One thing to do next, instead of a page of grades
            Focus(Grading.focus(report, dayOffset == 0), gradeColor(report.grade))
            extra()
        }
    }
}

@Composable
private fun GradeBadge(r: Report) {
    val color = gradeColor(r.grade)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)).border(3.dp, color, CircleShape),
            contentAlignment = Alignment.Center
        ) { Text(r.grade ?: "—", fontSize = 26.sp, fontWeight = FontWeight.Black, color = color) }
        Text(
            r.grade?.let { Grading.verdict(it) } ?: "No grade yet",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * A nutrient against its goal. A minimum (protein, fiber, water) is on track when it keeps pace with
 * the time of day; a limit (added sugar) only turns red when it's over.
 */
@Composable
private fun Nutrient(
    label: String, value: Double, goal: Double, unit: String, minimum: Boolean, share: Double,
    modifier: Modifier, action: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val nf = NumberFormat.getIntegerInstance()
    val over = !minimum && value > goal
    val behind = minimum && value < goal * share * 0.8
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            action?.invoke()
        }
        Text(
            "${nf.format(value.toLong())} / ${nf.format(goal.toLong())} $unit",
            style = MaterialTheme.typography.bodySmall,
            color = when { over -> cs.error; behind -> cs.onSurfaceVariant; else -> cs.primary }
        )
        Spacer(Modifier.height(4.dp))
        SlimBar((value / goal).toFloat(), over)
    }
}

@Composable
private fun Focus(text: String, color: Color) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("💡", fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

// ------------------------------------------------------------------ Your body

/** One tappable line of the body card: what it is, its number, a short context line, and a way in. */
@Composable
fun BodyRow(icon: String, title: String, value: String, sub: String, tint: Color, progress: Float? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) { Text(icon, fontSize = 18.sp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = tint)
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2)
            progress?.let { Spacer(Modifier.height(6.dp)); SlimBar(it, false) }
        }
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = cs.onSurfaceVariant)
    }
}

/** Sleep, fasting and movement in one card: the body side of the day, each a tap away from its page. */
@Composable
fun BodyTodayCard(rows: List<@Composable () -> Unit>) {
    if (rows.isEmpty()) return
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(
                "YOUR BODY", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp)
            )
            rows.forEachIndexed { i, row ->
                row()
                if (i < rows.lastIndex) HorizontalDivider(Modifier.padding(start = 70.dp, end = 16.dp))
            }
        }
    }
}

@Composable
fun SleepBodyRow(data: SleepData?, dayOffset: Int, goalH: Double, onOpen: () -> Unit) {
    val tint = Color(0xFF9C8CFF)
    val night = data?.nights?.get(dayOffset)
    when {
        data == null -> BodyRow("😴", "Sleep", "…", "Reading your sleep", tint, onClick = onOpen)
        !data.connected -> BodyRow("😴", "Sleep", "Connect", "See your sleep from your watch or sleep app", tint, onClick = onOpen)
        night == null -> BodyRow("😴", "Sleep", "—", if (dayOffset == 0) "Nothing recorded last night" else "Nothing recorded that night", tint, onClick = onOpen)
        else -> {
            val s = Grading.sleepSubject(night, data.shifts[dayOffset], goalH)
            BodyRow(
                "😴", "Sleep", Grading.hm(night.asleepH * 60),
                "${Metric.clock(night.bedtime % 24)} – ${Metric.clock(night.wakeTime)}  ·  grade ${s.grade}" +
                    (night.efficiency?.let { "  ·  ${it.toLong()}% asleep" } ?: ""),
                tint, progress = (night.asleepH / goalH).toFloat(), onClick = onOpen
            )
        }
    }
}

@Composable
fun MoveBodyRow(steps: Long?, activeKcal: Double?, stepGoal: Long, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val nf = NumberFormat.getIntegerInstance()
    if (steps == null) {
        BodyRow("👟", "Movement", "—", "Connect Health Connect to count your steps", cs.tertiary, onClick = onOpen)
        return
    }
    BodyRow(
        "👟", "Movement", "${nf.format(steps)} steps",
        "Goal ${nf.format(stepGoal)}" + (activeKcal?.let { "  ·  ${nf.format(it.toLong())} kcal burned by activity" } ?: ""),
        if (steps >= stepGoal) cs.primary else cs.tertiary,
        progress = (steps.toFloat() / stepGoal).coerceIn(0f, 1f), onClick = onOpen
    )
}
