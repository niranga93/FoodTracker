package com.example.nourio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.nourio.data.GoalStore
import com.example.nourio.data.MealDb
import com.example.nourio.insights.Analysis
import com.example.nourio.insights.HistoryLoader
import com.example.nourio.insights.InsightEngine
import com.example.nourio.insights.InsightGoals
import com.example.nourio.insights.InsightPrefs
import com.example.nourio.insights.InsightWorker
import com.example.nourio.insights.Metric
import com.example.nourio.insights.Relationship
import com.example.nourio.insights.Tip
import com.example.nourio.insights.Trend
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import kotlin.math.abs

@Composable
private fun Label(text: String) = Text(
    text.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
    letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 6.dp, top = 6.dp)
)

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

/** Relationships, trends and tips from the last 60 days. Opened from Profile > Health or a tip notification. */
@Composable
fun InsightsPage(db: MealDb) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]       // follows a language change
    val prefs = remember { InsightPrefs(context) }
    var analysis by remember { mutableStateOf<Analysis?>(null) }
    var refresh by remember { mutableIntStateOf(0) }          // bumps after accepting, so goals re-read
    var notify by remember { mutableStateOf(prefs.notificationsOn) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh) {
        analysis = InsightEngine.analyse(HistoryLoader.load(context, db), InsightGoals.current(context), LocalDate.now())
            .also { prefs.saveTips(it.tips) }        // the daily notification sends from these
    }

    val a = analysis
    if (a == null) {
        AppCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Looking for patterns in your last 60 days…")
            }
        }
        return
    }

    val open = prefs.open(a.tips)

    // Daily tips need notification permission on Android 13+
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) message = "Notifications are off for Nourio. Turn them on in Android settings to get daily tips."
    }
    fun ensurePermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    OverviewCard(
        a, notify,
        onNotify = { on -> notify = on; prefs.notificationsOn = on; com.example.nourio.reminders.Reminders.schedule(context); if (on) ensurePermission() },
        onPreview = if (open.isEmpty()) null else ({
            ensurePermission()
            InsightWorker.notify(context, open.first())
            prefs.markNotified(open.first().id)
            message = "Sent. Check your notifications."
        })
    )
    Label("Recommended for you")
    message?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp))
    }
    if (open.isEmpty()) {
        AppCard(Modifier.fillMaxWidth()) {
            Muted(
                if (a.tips.isEmpty()) "No tips yet. They appear once there are clear patterns in your data."
                else "You're all caught up. New tips appear as your data changes.",
                Modifier.padding(20.dp)
            )
        }
    }
    open.forEach { tip ->
        key(tip.id) {
            TipCard(
                tip,
                onAccept = {
                    InsightGoals.accept(context, tip)
                    message = tip.change?.let { "Updated. ${it.text}" } ?: "Noted. Keep it up!"
                    refresh++
                },
                onDismiss = { prefs.dismiss(tip.id); refresh++ }
            )
        }
    }

    Label("Links in your data")
    if (a.relationships.isEmpty()) {
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("No clear links yet", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Muted(
                    "Each link needs at least ${InsightEngine.MIN_PAIRS} days where both things were recorded. Keep logging " +
                        "meals and wearing your watch. Nourio checks sleep against eating, steps against sleep and heart rate, " +
                        "late meals against sleep, sodium against weight and blood pressure, and more.",
                    Modifier.padding(top = 6.dp)
                )
            }
        }
    }
    a.relationships.forEach { RelationshipCard(it) }

    if (a.trends.isNotEmpty()) {
        Label("Last 2 weeks vs your usual")
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                a.trends.forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                    TrendRow(t)
                }
            }
        }
    }

    val accepted = prefs.accepted
    if (accepted.isNotEmpty()) {
        Label("Tips you've accepted")
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                val fmt = SimpleDateFormat("d MMM", locale)
                accepted.reversed().take(10).forEach { line ->
                    val parts = line.split('|')
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(parts.getOrNull(2).orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Muted(parts.getOrNull(1)?.toLongOrNull()?.let { fmt.format(Date(it)) }.orEmpty())
                    }
                }
            }
        }
    }

    // The coach's weekly review, written by Gemini from last week's log
    Label("Your week, reviewed")
    if (rememberAiAvailable()) WeeklyReviewCard(db, remember { GoalStore(context) })
    else AiLockedCard("Weekly review", "Add an AI key in Connections to get a short review of your week.")

    Muted(
        "Links show what tends to happen together in your own data, not proof that one causes the other. " +
            "Tips are general wellness guidance, not medical advice. Everything is worked out on this phone.",
        Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
    )
}

@Composable
private fun OverviewCard(a: Analysis, notify: Boolean, onNotify: (Boolean) -> Unit, onPreview: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Your health patterns", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Muted("From the last ${a.days} days of food, activity, sleep, heart and weight data.")
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                Big("${a.relationships.size}", "links found", cs.primary, Modifier.weight(1f))
                Big("${a.trends.size}", "trends", cs.tertiary, Modifier.weight(1f))
                Big("${a.tips.size}", "tips", cs.secondary, Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Text("Days with data", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            val cover = listOf(
                "Food" to a.coverage[Metric.KCAL], "Steps" to a.coverage[Metric.STEPS],
                "Sleep" to a.coverage[Metric.SLEEP], "Heart" to maxOf(a.coverage[Metric.RESTING_HR] ?: 0, a.coverage[Metric.AVG_HR] ?: 0),
                "Blood pressure" to a.coverage[Metric.SYSTOLIC], "Weigh-ins" to a.coverage[Metric.WEIGHT]
            )
            cover.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (name, n) -> CoverageChip(name, n ?: 0, Modifier.weight(1f)) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Daily tip notification", style = MaterialTheme.typography.bodyLarge)
                    Muted("One tip each morning, with an Accept button")
                }
                Switch(checked = notify, onCheckedChange = onNotify)
            }
            if (notify && onPreview != null) {
                TextButton(onClick = onPreview, contentPadding = PaddingValues(0.dp)) { Text("Send today's tip now") }
            }
        }
    }
}

@Composable
private fun Big(value: String, label: String, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
        Muted(label)
    }
}

@Composable
private fun CoverageChip(name: String, days: Int, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val c = when {
        days >= 21 -> cs.primary
        days >= InsightEngine.MIN_PAIRS -> cs.tertiary
        days > 0 -> Color(0xFFFFB547)
        else -> cs.onSurfaceVariant
    }
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text("$days", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = c)
        Text(name, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun TipCard(tip: Tip, onAccept: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(tip.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(tip.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            tip.change?.let { c ->
                Row(
                    Modifier.padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(cs.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(c.field.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text("  ${c.field.format(c.from)}  →  ", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    Text(c.field.format(c.to), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = cs.primary)
                }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) { Text(if (tip.change != null) "Accept and update goal" else "Got it") }
                OutlinedButton(onClick = onDismiss) { Text("Not now") }
            }
        }
    }
}

@Composable
fun RelationshipCard(r: Relationship) {
    val cs = MaterialTheme.colorScheme
    val color = when {
        abs(r.rho) >= 0.6 -> cs.primary
        abs(r.rho) >= 0.4 -> cs.tertiary
        else -> Color(0xFFFFB547)
    }
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${r.x.label} → ${r.y.label}" + if (r.lagged) " (next day)" else "",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                )
                Pill(r.strength)
            }
            Text(r.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(12.dp))
            Scatter(r.points, color, Modifier.fillMaxWidth().height(150.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Muted(r.x.label, Modifier.weight(1f))
                Muted("↑ ${r.y.label}")
            }
            // Strength meter
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(abs(r.rho).toFloat()).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
                }
                Muted("  ${r.n} days  ·  r = ${"%.2f".format(r.rho)}")
            }
        }
    }
}

/** Each day as a dot, with a straight best-fit line through them. */
@Composable
private fun Scatter(points: List<Pair<Double, Double>>, color: Color, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val xs = points.map { it.first }; val ys = points.map { it.second }
        val minX = xs.min(); val maxX = xs.max().let { if (it == minX) minX + 1 else it }
        val minY = ys.min(); val maxY = ys.max().let { if (it == minY) minY + 1 else it }
        val pad = 8.dp.toPx()
        fun px(x: Double) = (pad + (x - minX) / (maxX - minX) * (size.width - 2 * pad)).toFloat()
        fun py(y: Double) = (size.height - pad - (y - minY) / (maxY - minY) * (size.height - 2 * pad)).toFloat()
        drawLine(grid, Offset(pad, size.height - pad), Offset(size.width - pad, size.height - pad), 1.dp.toPx())
        drawLine(grid, Offset(pad, pad), Offset(pad, size.height - pad), 1.dp.toPx())
        points.forEach { (x, y) -> drawCircle(color.copy(alpha = 0.75f), 4.dp.toPx(), Offset(px(x), py(y))) }
        // Least-squares line
        val mx = xs.average(); val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx > 0) {
            val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
            val y0 = my + slope * (minX - mx); val y1 = my + slope * (maxX - mx)
            drawLine(color, Offset(px(minX), py(y0.coerceIn(minY, maxY))), Offset(px(maxX), py(y1.coerceIn(minY, maxY))),
                3.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun TrendRow(t: Trend) {
    val cs = MaterialTheme.colorScheme
    val color = when (t.good) { true -> cs.primary; false -> cs.error; null -> cs.tertiary }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Text(if (t.change > 0) "↑" else "↓", color = color, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.metric.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Muted("${t.metric.format(t.before)} → ${t.metric.format(t.recent)} ${if (t.metric in Metric.NIGHTLY) "a night" else "a day"}")
        }
        Sparkline(t.series, Modifier.width(80.dp).height(30.dp))
    }
}
