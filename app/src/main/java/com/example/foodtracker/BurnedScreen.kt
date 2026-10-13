package com.example.foodtracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.foodtracker.data.MealDb
import com.example.foodtracker.data.ProfileStore
import com.example.foodtracker.data.totals
import com.example.foodtracker.energy.BurnSource
import com.example.foodtracker.energy.CalibrationStore
import com.example.foodtracker.energy.DayEnergy
import com.example.foodtracker.energy.EnergyRepo
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private data class BurnInfo(val day: DayEnergy, val resting: Double, val activity: Double, val hasDeviceData: Boolean)

@Composable
private fun LegendRow(color: Color, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun BurnedScreen(db: MealDb, dayOffset: Int, onDayChange: (Int) -> Unit, inner: PaddingValues) {
    val context = LocalContext.current
    val profile = remember { ProfileStore(context).load() }
    val (body, reason) = remember { EnergyRepo.body(profile) }
    val factor = remember { CalibrationStore(context).factor }
    var info by remember(dayOffset) { mutableStateOf<BurnInfo?>(null) }
    var week by remember { mutableStateOf<List<DayEnergy>>(emptyList()) }

    LaunchedEffect(dayOffset) {
        if (body != null) {
            val act = EnergyRepo.activityFor(context, listOf(dayOffset))[dayOffset]
            val eaten = withContext(Dispatchers.IO) { loadDay(db, dayOffset).flatMap { it.items }.totals().kcal }
            val raw = EnergyRepo.build(body, dayOffset, eaten, act)
            val burned = raw.burned * factor
            val resting = (raw.resting * factor).coerceAtMost(burned)
            info = BurnInfo(
                day = raw.copy(burned = burned),
                resting = resting,
                activity = (burned - resting).coerceAtLeast(0.0),
                hasDeviceData = act != null
            )
        }
    }
    LaunchedEffect(Unit) {
        if (body != null) {
            val offsets = (0 downTo -6).toList()
            val acts = EnergyRepo.activityFor(context, offsets)
            val byDay = withContext(Dispatchers.IO) { loadDays(db, offsets) }
            week = offsets.map { off ->
                val eaten = byDay[off].orEmpty().flatMap { it.items }.totals().kcal
                val raw = EnergyRepo.build(body, off, eaten, acts[off])
                raw.copy(burned = raw.burned * factor)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(inner),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Burned", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { DateNavigator(dayOffset, onDayChange) }

        if (body == null) {
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(
                        reason ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Calories burned", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        val i = info
                        if (i == null) {
                            Text(
                                "Calculating…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        } else {
                            Text(
                                "${i.day.burned.f0()} kcal",
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp))) {
                                Box(
                                    Modifier.weight(i.resting.toFloat().coerceAtLeast(0.01f)).fillMaxHeight()
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                if (i.activity > 0) {
                                    Box(
                                        Modifier.weight(i.activity.toFloat()).fillMaxHeight()
                                            .background(MaterialTheme.colorScheme.secondary)
                                    )
                                }
                            }
                            val fromDevice = i.day.source == BurnSource.DEVICE_TOTAL ||
                                i.day.source == BurnSource.DEVICE_RESTING_PLUS_STEPS
                            val stepsEstimated = i.day.source == BurnSource.BMR_PLUS_STEPS ||
                                i.day.source == BurnSource.DEVICE_RESTING_PLUS_STEPS
                            LegendRow(
                                MaterialTheme.colorScheme.primary,
                                if (fromDevice) "Resting (Health Connect)" else "Resting (BMR + digestion)",
                                "${i.resting.f0()} kcal"
                            )
                            LegendRow(
                                MaterialTheme.colorScheme.secondary,
                                if (stepsEstimated) "Activity (estimated from steps)" else "Activity",
                                "${i.activity.f0()} kcal"
                            )
                            Text(
                                "Source: ${i.day.source.label}" +
                                    (i.day.steps?.let { " (${"%,d".format(it)} steps)" } ?: "") + ".",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                        }
                    }
                }
            }

            // Health Connect numbers (hidden when not connected), or a hint to connect
            item { ActivityCard(dayOffset) }
            val i = info
            if (i != null && !i.hasDeviceData) {
                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        Text(
                            "Connect Health Connect in Connections for real data.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Eaten vs burned, last 7 days", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        if (week.isEmpty()) {
                            Text(
                                "Calculating…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            EatenBurnedChart(week)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                                LegendDot(MaterialTheme.colorScheme.primary, "Eaten")
                                LegendDot(MaterialTheme.colorScheme.secondary, "Burned")
                            }
                        }
                    }
                }
            }

            item { StepsChartCard(week) }

            item { PredictionCard(db) }

            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Expandable("How it's estimated") {
                            Text(
                                "Resting burn from your profile, plus Health Connect or step activity. Estimates can be off by 10 to 20%.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
