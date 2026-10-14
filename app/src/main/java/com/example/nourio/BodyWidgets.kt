package com.example.nourio

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
import com.example.nourio.health.DayBody
import com.example.nourio.health.SleepStages
import com.example.nourio.health.HealthExtras
import com.example.nourio.health.HealthPrefs

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
                state.value = (0..6).map { HealthExtras.readDayBody(client, dayOffset - it, granted) }
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
        days.first().stages?.let { StagesBar(it) }
    }
}

/** The selected night split into deep, REM, light and awake, as one bar with a legend. */
@Composable
private fun StagesBar(s: SleepStages) {
    val parts = listOf(
        Triple("Deep", s.deep, MaterialTheme.colorScheme.primary),
        Triple("REM", s.rem, MaterialTheme.colorScheme.tertiary),
        Triple("Light", s.light, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        Triple("Awake", s.awake, MaterialTheme.colorScheme.outline)
    ).filter { it.second > 0 }
    val total = parts.sumOf { it.second }.coerceAtLeast(1L)
    Spacer(Modifier.height(16.dp))
    Text("Last night", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
        parts.forEach { (_, m, c) -> Box(Modifier.weight(m.toFloat() / total).fillMaxHeight().background(c)) }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        parts.forEach { (label, m, c) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(c))
                Spacer(Modifier.width(4.dp))
                Text(
                    "$label ${hm(m).removePrefix("0h ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun HeartRateCard(days: List<DayBody>) {
    val today = days.first()
    // Resting heart rate is the steadier fitness signal, so it drives the trend when the watch records it
    val resting = days.reversed().mapNotNull { it.restingHr?.toDouble() }
    val trend = resting.takeIf { it.size >= 2 } ?: days.reversed().mapNotNull { it.hrAvg?.toDouble() }
    WidgetCard("Heart rate") {
        Row(Modifier.fillMaxWidth()) {
            if (today.restingHr != null) Metric("Resting", "${today.restingHr}", Modifier.weight(1f))
            Metric("Average", today.hrAvg?.let { "$it" } ?: "—", Modifier.weight(1f))
            Metric("Lowest", today.hrMin?.let { "$it" } ?: "—", Modifier.weight(1f))
            Metric("Highest", today.hrMax?.let { "$it" } ?: "—", Modifier.weight(1f))
        }
        Text(
            "Beats per minute",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (trend.size >= 2) {
            Spacer(Modifier.height(12.dp))
            Sparkline(trend, Modifier.fillMaxWidth().height(48.dp))
            Text(
                if (resting.size >= 2) "Resting heart rate, last 7 days" else "Daily average, last 7 days",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** VO2 max, blood oxygen and distance for the selected day. Hidden when none are recorded. */
@Composable
fun VitalsCard(day: DayBody, dayOffset: Int) {
    WidgetCard(if (dayOffset == 0) "Fitness and vitals today" else "Fitness and vitals that day") {
        Row(Modifier.fillMaxWidth()) {
            day.vo2max?.let { Metric("VO2 max", "%.1f".format(it), Modifier.weight(1f)) }
            day.spo2?.let { Metric("Blood oxygen", "${it.f0()}%", Modifier.weight(1f)) }
            day.distanceKm?.let { Metric("Distance", "${"%.1f".format(it)} km", Modifier.weight(1f)) }
        }
        Text(
            "From your watch or phone through Health Connect. VO2 max is mL/kg/min, the latest in 30 days. " +
                "For information only, not medical advice.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
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
