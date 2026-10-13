package com.example.nourio

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.nourio.data.GoalStore
import com.example.nourio.data.MealDb
import com.example.nourio.data.Totals
import com.example.nourio.data.totals
import java.text.NumberFormat
import kotlin.math.max

@Composable
private fun CalorieHistoryChart(values: List<Double>, offsets: List<Int>, goal: Double) {
    val ok = MaterialTheme.colorScheme.primary
    val over = MaterialTheme.colorScheme.error
    val none = MaterialTheme.colorScheme.surfaceVariant
    val goalLine = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val maxV = max(max(values.maxOrNull() ?: 0.0, goal), 1.0) * 1.1

    Column {
        Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            val n = values.size
            val gap = 6.dp.toPx()
            val barW = (size.width - gap * (n - 1)) / n
            values.forEachIndexed { i, v ->
                val h = if (v <= 0) 4.dp.toPx() else (v / maxV * size.height).toFloat()
                val color = when {
                    v <= 0 -> none
                    goal > 0 && v > goal -> over
                    else -> ok
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(i * (barW + gap), size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(4.dp.toPx())
                )
            }
            if (goal > 0) {
                val y = (size.height - goal / maxV * size.height).toFloat()
                drawLine(
                    goalLine, Offset(0f, y), Offset(size.width, y),
                    strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            offsets.forEach { off ->
                Text(
                    dayLetter(off),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DayRow(off: Int, t: Totals, maxKcal: Double, goal: Double, onClick: () -> Unit) {
    val color = if (goal > 0 && t.kcal > goal) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            dayTitle(off), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.34f)
        )
        Box(
            Modifier.weight(0.46f).padding(horizontal = 8.dp).height(10.dp).clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (t.kcal > 0) {
                Box(Modifier.fillMaxWidth((t.kcal / maxKcal).toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().background(color))
            }
        }
        Text(
            if (t.kcal > 0) t.kcal.f0() else "—",
            style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End,
            modifier = Modifier.weight(0.2f)
        )
    }
}

@Composable
fun HistoryPage(db: MealDb, goalStore: GoalStore, onBack: () -> Unit, onOpenDay: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        ScreenTopBar("History", onBack)
        Box(Modifier.weight(1f)) {
            RefreshBox { HistoryList(db, goalStore, onOpenDay) }
        }
    }
}

// Loads its own data, so pull-to-refresh reloads the 14 days
@Composable
private fun HistoryList(db: MealDb, goalStore: GoalStore, onOpenDay: (Int) -> Unit) {
    val context = LocalContext.current
    val goals = remember { goalStore.load() }
    val budget by rememberBudget(db, goalStore, 0)
    val goal = budget?.baseKcal?.takeIf { it > 0 } ?: goals.kcal
    val days = remember { (0 downTo -13).map { off -> off to loadDay(db, off).flatMap { it.items }.totals() } }
    val logged = days.filter { it.second.kcal > 0 }
    val avg = if (logged.isEmpty()) 0.0 else logged.map { it.second.kcal }.average()
    val within = logged.count { goal <= 0 || it.second.kcal <= goal }
    val maxKcal = (days.maxOfOrNull { it.second.kcal } ?: 0.0).coerceAtLeast(1.0)
    val nf = remember { NumberFormat.getIntegerInstance() }
    val timeline = days.reversed()      // oldest on the left

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            WidgetCard(
                "Calories, last 14 days",
                trailing = {
                    TextButton(onClick = {
                        val week = days.take(7).filter { it.second.kcal > 0 }
                        val text = if (week.isEmpty()) "Nourio: no meals logged in the last 7 days."
                        else "Nourio, last 7 days\n" +
                            "Days logged: ${week.size} of 7\n" +
                            "Average: ${nf.format(week.map { it.second.kcal }.average().toLong())} kcal a day\n" +
                            "Sugar ${week.map { it.second.sugar }.average().f1()} g, " +
                            "fat ${week.map { it.second.fat }.average().f1()} g a day"
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, "Share"))
                    }) { Text("Share") }
                }
            ) {
                Row(Modifier.fillMaxWidth()) {
                    Metric("Days logged", "${logged.size}/14", Modifier.weight(1f))
                    Metric("Daily average", nf.format(avg.toLong()), Modifier.weight(1f))
                    if (goal > 0) Metric("Within goal", "$within", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                CalorieHistoryChart(timeline.map { it.second.kcal }, timeline.map { it.first }, goal)
                if (goal > 0) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LegendDot(MaterialTheme.colorScheme.primary, "Within goal")
                        LegendDot(MaterialTheme.colorScheme.error, "Over goal")
                    }
                }
            }
        }
        item {
            Text("Day by day", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Column {
                    days.forEachIndexed { i, (off, t) ->
                        DayRow(off, t, maxKcal, goal) { onOpenDay(off) }
                        if (i < days.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}
