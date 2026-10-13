package com.example.foodtracker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.foodtracker.energy.DayEnergy
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

fun dayLetter(offset: Int): String {
    val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
    return SimpleDateFormat("EEE", Locale.getDefault()).format(c.time).take(1)
}

@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 10.dp, vertical = 3.dp)
    )
}

@Composable
fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Details" toggle that keeps explanations out of sight until tapped. */
@Composable
fun Expandable(label: String = "Details", content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
            Text(if (open) "Hide" else label)
        }
        if (open) Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val minV = values.min()
        val span = (values.max() - minV).coerceAtLeast(0.2)
        val w = size.width
        val h = size.height
        fun x(i: Int) = w * i / (values.size - 1)
        fun y(v: Double) = (h - ((v - minV) / span * h * 0.8 + h * 0.1)).toFloat()
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(line, 4.dp.toPx(), Offset(x(values.size - 1), y(values.last())))
    }
}

/** days: newest first. Two bars per day, oldest on the left. */
@Composable
fun EatenBurnedChart(days: List<DayEnergy>, modifier: Modifier = Modifier) {
    val eatenColor = MaterialTheme.colorScheme.primary
    val burnedColor = MaterialTheme.colorScheme.secondary
    val maxV = (days.maxOfOrNull { maxOf(it.eaten, it.burned) } ?: 1.0).coerceAtLeast(1.0)
    val ordered = days.reversed()
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(110.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            ordered.forEach { d ->
                Row(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Box(
                        Modifier.weight(1f).fillMaxHeight((d.eaten / maxV).toFloat().coerceIn(0.02f, 1f))
                            .clip(RoundedCornerShape(3.dp)).background(eatenColor)
                    )
                    Box(
                        Modifier.weight(1f).fillMaxHeight((d.burned / maxV).toFloat().coerceIn(0.02f, 1f))
                            .clip(RoundedCornerShape(3.dp)).background(burnedColor)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

/** Steps per day from the same list. Hidden when there is no step data. */
@Composable
fun StepsChartCard(days: List<DayEnergy>) {
    val ordered = days.reversed()
    val steps = ordered.map { it.steps ?: 0L }
    if (steps.all { it == 0L }) return
    val maxV = steps.max().coerceAtLeast(1L)
    val color = MaterialTheme.colorScheme.secondary
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Steps", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().height(90.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                steps.forEach { s ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight((s.toFloat() / maxV).coerceIn(0.02f, 1f))
                            .clip(RoundedCornerShape(4.dp)).background(color)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

@Composable
fun GroupTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

@Composable
fun SwitchRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            hint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
