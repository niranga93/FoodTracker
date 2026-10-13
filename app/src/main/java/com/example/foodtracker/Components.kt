package com.example.foodtracker

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.foodtracker.data.Meal
import com.example.foodtracker.data.MealDb
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// ---------- formatting & date helpers ----------

fun Double.f0(): String = "%.0f".format(this)
fun Double.f1(): String = "%.1f".format(this)

fun loadDay(db: MealDb, offset: Int): List<Meal> {
    val c = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, offset)
    }
    val start = c.timeInMillis
    c.add(Calendar.DAY_OF_YEAR, 1)
    return db.between(start, c.timeInMillis)
}

fun dayTitle(offset: Int): String = when (offset) {
    0 -> "Today"
    -1 -> "Yesterday"
    else -> {
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
        SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(c.time)
    }
}

fun dayFull(offset: Int): String {
    val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
    return SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(c.time)
}

fun timestampForOffset(offset: Int): Long {
    val c = Calendar.getInstance()
    c.add(Calendar.DAY_OF_YEAR, offset)
    return c.timeInMillis
}

fun shiftDays(timestamp: Long, days: Int): Long {
    val c = Calendar.getInstance().apply {
        timeInMillis = timestamp
        add(Calendar.DAY_OF_YEAR, days)
    }
    return c.timeInMillis
}

// ---------- logo ----------

@Composable
fun AppLogo(logoSize: Dp = 96.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(logoSize)) {
        val w = this.size.minDimension
        drawCircle(brush = Brush.linearGradient(listOf(Color(0xFF66BB6A), Color(0xFF1B5E20))))
        drawCircle(color = Color.White, radius = w * 0.30f, style = Stroke(width = w * 0.055f))
        drawCircle(color = Color(0xFFFFA726), radius = w * 0.14f)
        val leaf = Path().apply {
            moveTo(w * 0.52f, w * 0.36f)
            quadraticBezierTo(w * 0.60f, w * 0.10f, w * 0.86f, w * 0.14f)
            quadraticBezierTo(w * 0.82f, w * 0.38f, w * 0.52f, w * 0.36f)
            close()
        }
        drawPath(leaf, color = Color(0xFFC8E6C9))
    }
}

// ---------- building blocks ----------

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardContainer()),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        content = content
    )
}

@Composable
fun SlimBar(progress: Float, over: Boolean, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(
        modifier.fillMaxWidth().height(8.dp)
            .clip(RoundedCornerShape(4.dp)).background(track)
    ) {
        Box(
            Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(fill)
        )
    }
}

@Composable
fun CalorieRing(kcal: Double, goal: Double, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val over = goal > 0 && kcal > goal
    val color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val target = if (goal > 0) (kcal / goal).toFloat().coerceIn(0f, 1f) else 0f
    val sweep by animateFloatAsState(targetValue = target, animationSpec = tween(700), label = "ring")

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
            Text(kcal.f0(), fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Text(
                if (goal > 0) "of ${goal.f0()} kcal" else "kcal",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun LabeledBar(label: String, value: Double, goal: Double, unit: String, decimals: Int) {
    fun fmt(d: Double) = if (decimals == 0) d.f0() else d.f1()
    val over = value > goal
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text(
                "${fmt(value)} / ${fmt(goal)} $unit" + if (over) "  (over by ${fmt(value - goal)})" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (over) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        SlimBar((value / goal).toFloat(), over)
    }
}

@Composable
fun DateNavigator(offset: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(offset - 1) }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous day")
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(dayTitle(offset), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                dayFull(offset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(enabled = offset < 0, onClick = { onChange(offset + 1) }) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next day")
        }
    }
}

@Composable
fun DayPicker(offset: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "Log to",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange(offset - 1) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous day")
            }
            Text(
                dayTitle(offset),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 100.dp)
            )
            IconButton(enabled = offset < 0, onClick = { onChange(offset + 1) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next day")
            }
        }
    }
}

@Composable
fun ScreenTopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun AnalyzingView(title: String, subtitle: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AppLogo(88.dp, Modifier.scale(pulse))
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
    }
}

@Composable
fun ActionBar(
    logOffset: Int,
    onOffset: (Int) -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    saveEnabled: Boolean,
    onSave: () -> Unit
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            DayPicker(logOffset, onOffset)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onSecondary,
                    modifier = Modifier.weight(1f).height(50.dp)
                ) { Text(secondaryLabel) }
                Button(
                    onClick = onSave,
                    enabled = saveEnabled,
                    modifier = Modifier.weight(1f).height(50.dp)
                ) { Text("Save meal") }
            }
        }
    }
}