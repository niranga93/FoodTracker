package com.example.nourio

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nourio.data.Meal
import com.example.nourio.data.MealDb
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// ---------- formatting & date helpers ----------

fun Double.f0(): String = "%.0f".format(this)
fun Double.f1(): String = "%.1f".format(this)

private fun dayStart(offset: Int): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    add(Calendar.DAY_OF_YEAR, offset)
}.timeInMillis

fun loadDay(db: MealDb, offset: Int): List<Meal> = db.between(dayStart(offset), dayStart(offset + 1))

/** Meals for each day offset, read with a single query. Days without meals map to an empty list. */
fun loadDays(db: MealDb, offsets: List<Int>): Map<Int, List<Meal>> {
    if (offsets.isEmpty()) return emptyMap()
    val starts = offsets.associateWith { dayStart(it) to dayStart(it + 1) }
    val meals = db.between(starts.values.minOf { it.first }, starts.values.maxOf { it.second })
    return starts.mapValues { (_, range) -> meals.filter { it.timestamp >= range.first && it.timestamp < range.second } }
}

fun dayTitle(offset: Int): String = when (offset) {
    0 -> "Today"
    -1 -> "Yesterday"
    else -> {
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
        SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(c.time)
    }
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
fun AppLogo(logoSize: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(logoSize)) {
        val w = this.size.minDimension
        val k = w / 108f
        fun p(n: Float) = n * k
        drawRoundRect(NourioInk, cornerRadius = CornerRadius(w * 0.26f, w * 0.26f))
        drawCircle(
            color = NourioMint, radius = 24f * k, center = Offset(w / 2f, w / 2f),
            style = Stroke(width = 6f * k, cap = StrokeCap.Round)
        )
        val leaf = Path().apply {
            moveTo(p(54f), p(40f))
            cubicTo(p(63f), p(45f), p(66f), p(54f), p(60f), p(62f))
            cubicTo(p(56f), p(67f), p(49f), p(67f), p(46f), p(62f))
            cubicTo(p(42f), p(56f), p(45f), p(46f), p(54f), p(40f))
            close()
        }
        drawPath(leaf, Color.White)
        drawLine(Color(0xFF0E1A13), Offset(p(49f), p(63f)), Offset(p(57f), p(50f)), strokeWidth = 2f * k, cap = StrokeCap.Round)
    }
}

// ---------- building blocks ----------

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = cardContainer()),
        border = BorderStroke(1.dp, cardOutline()),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
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
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
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

/** 44 dp round button with a hairline outline, used for the previous/next day arrows. */
@Composable
fun RoundNavButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(44.dp)
            .clip(CircleShape)
            .background(cardContainer())
            .border(1.dp, cardOutline(), CircleShape)
    ) { content() }
}

@Composable
fun DateNavigator(offset: Int, onChange: (Int) -> Unit) {
    val longDate = remember(offset) {
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
        SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(c.time)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                longDate,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                dayTitle(offset),
                style = MaterialTheme.typography.headlineSmall,
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
        RoundNavButton(onClick = { onChange(offset - 1) }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous day")
        }
        Spacer(Modifier.width(8.dp))
        RoundNavButton(onClick = { onChange(offset + 1) }, enabled = offset < 0) {
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
    Surface(color = cardContainer(), tonalElevation = 0.dp, shadowElevation = 0.dp) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = cardOutline())
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                DayPicker(logOffset, onOffset)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onSecondary,
                        shape = RoundedCornerShape(26.dp),
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) { Text(secondaryLabel, fontWeight = FontWeight.Bold) }
                    Button(
                        onClick = onSave,
                        enabled = saveEnabled,
                        shape = RoundedCornerShape(26.dp),
                        modifier = Modifier.weight(2f).height(52.dp)
                    ) { Text("Save meal", fontWeight = FontWeight.ExtraBold) }
                }
            }
        }
    }
}