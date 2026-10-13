package com.example.foodtracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import com.example.foodtracker.health.DayBody
import com.example.foodtracker.health.HealthExtras
import com.example.foodtracker.health.HealthPrefs

private fun hm(minutes: Long) = "${minutes / 60}h ${minutes % 60}m"

/** The last 7 days (newest first). Null while loading, or when reading is off or not permitted. */
@Composable
fun rememberBodyData(dayOffset: Int): State<List<DayBody>?> {
    val context = LocalContext.current
    val state = remember(dayOffset) { mutableStateOf<List<DayBody>?>(null) }
    LaunchedEffect(dayOffset) {
        val prefs = HealthPrefs(context)
        if (prefs.enabled && prefs.readExtras &&
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        ) {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = runCatching { client.permissionController.getGrantedPermissions() }
                .getOrDefault(emptySet())
            if (granted.containsAll(HealthExtras.READ_PERMS)) {
                state.value = (0..6).map { HealthExtras.readDayBody(client, dayOffset - it) }
            }
        }
    }
    return state
}

@Composable
fun SleepCard(days: List<DayBody>, dayOffset: Int) {
    val ordered = days.reversed()                              // oldest on the left
    val hours = ordered.map { (it.sleepMinutes ?: 0L) / 60.0 }
    val logged = days.mapNotNull { it.sleepMinutes }
    val maxV = hours.max().coerceAtLeast(8.0)
    val color = MaterialTheme.colorScheme.primary
    WidgetCard(
        "Sleep, last 7 nights",
        trailing = { if (logged.isNotEmpty()) Pill("avg ${hm(logged.average().toLong())}") }
    ) {
        Row(
            Modifier.fillMaxWidth().height(90.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            hours.forEach { h ->
                Box(
                    Modifier.weight(1f).fillMaxHeight((h / maxV).toFloat().coerceIn(0.02f, 1f))
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (h > 0) color else MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ordered.indices.forEach { i ->
                Text(
                    dayLetter(dayOffset - (ordered.size - 1 - i)),
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
fun HeartRateCard(days: List<DayBody>) {
    val today = days.first()
    val trend = days.reversed().mapNotNull { it.hrAvg?.toDouble() }
    WidgetCard("Heart rate") {
        Row(Modifier.fillMaxWidth()) {
            Metric("Average", today.hrAvg?.let { "$it bpm" } ?: "—", Modifier.weight(1f))
            Metric("Lowest", today.hrMin?.let { "$it" } ?: "—", Modifier.weight(1f))
            Metric("Highest", today.hrMax?.let { "$it" } ?: "—", Modifier.weight(1f))
        }
        if (trend.size >= 2) {
            Spacer(Modifier.height(12.dp))
            Sparkline(trend, Modifier.fillMaxWidth().height(48.dp))
            Text(
                "Daily average, last 7 days",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun WorkoutsCard(day: DayBody, dayOffset: Int) {
    WidgetCard(if (dayOffset == 0) "Workouts today" else "Workouts that day") {
        day.workouts.forEach { w ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(w.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    hm(w.minutes).removePrefix("0h "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
