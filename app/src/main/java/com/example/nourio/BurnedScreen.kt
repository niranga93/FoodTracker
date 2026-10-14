package com.example.nourio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nourio.data.FeaturePrefs
import com.example.nourio.data.MealDb
import com.example.nourio.data.ProfileStore
import com.example.nourio.data.totals
import com.example.nourio.energy.BurnSource
import com.example.nourio.energy.CalibrationStore
import com.example.nourio.energy.DayEnergy
import com.example.nourio.energy.EnergyModel
import com.example.nourio.energy.EnergyRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun SplitRow(color: Color, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BurnSplitCard(day: DayEnergy?, dayOffset: Int, hasDeviceData: Boolean) {
    val fromDevice = day?.source == BurnSource.DEVICE_TOTAL ||
        day?.source == BurnSource.DEVICE_RESTING_PLUS_STEPS ||
        day?.source == BurnSource.BMR_PLUS_ACTIVITY
    WidgetCard(
        if (dayOffset == 0) "Where today's burn comes from" else "Where the burn came from",
        trailing = { if (day != null) Pill(if (fromDevice) "Health Connect" else "Estimated") }
    ) {
        if (day == null) {
            Text("Calculating…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val resting = day.resting.coerceAtMost(day.burned)
            val activity = (day.burned - resting).coerceAtLeast(0.0)
            Text("${day.burned.f0()} kcal", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp))) {
                Box(Modifier.weight(resting.toFloat().coerceAtLeast(0.01f)).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.secondaryContainer))
                if (activity > 0) {
                    Box(Modifier.weight(activity.toFloat()).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.secondary))
                }
            }
            SplitRow(MaterialTheme.colorScheme.secondaryContainer, "Resting", "${resting.f0()} kcal")
            SplitRow(MaterialTheme.colorScheme.secondary, "Activity", "${activity.f0()} kcal")
            if (!hasDeviceData) {
                Text(
                    "Connect Health Connect for real data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
fun BurnedScreen(db: MealDb, dayOffset: Int, onDayChange: (Int) -> Unit, inner: PaddingValues) {
    val context = LocalContext.current
    val features = remember { FeaturePrefs(context) }
    val profile = remember { ProfileStore(context).load() }
    val (body, reason) = remember { EnergyRepo.body(profile) }
    var week by remember(dayOffset) { mutableStateOf<List<DayEnergy>>(emptyList()) }   // newest first
    var hasDeviceData by remember(dayOffset) { mutableStateOf(false) }
    val bodyDays by rememberBodyData(dayOffset)

    LaunchedEffect(dayOffset) {
        if (body != null) {
            val offs = (0..6).map { dayOffset - it }
            val acts = EnergyRepo.activityFor(context, offs)
            val factor = CalibrationStore(context).factor
            hasDeviceData = acts.isNotEmpty()
            week = offs.map { off ->
                val kcal = withContext(Dispatchers.IO) { loadDay(db, off).flatMap { it.items }.totals().kcal }
                val raw = EnergyRepo.build(body, off, kcal, acts[off])
                raw.copy(burned = raw.burned * factor, resting = raw.resting * factor)
            }
        }
    }
    val day = week.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(inner),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { DateNavigator(dayOffset, onDayChange) }

        if (body == null) {
            item {
                WidgetCard("Burn") {
                    Text(reason ?: "", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            if (features.isOn("burn_split")) item { BurnSplitCard(day, dayOffset, hasDeviceData) }
            if (features.isOn("burn_week") && week.isNotEmpty()) {
                item {
                    WidgetCard(
                        "Eaten vs burned",
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                LegendDot(MaterialTheme.colorScheme.primary, "Eaten")
                                LegendDot(MaterialTheme.colorScheme.secondary, "Burned")
                            }
                        }
                    ) { EatenBurnedChart(week) }
                }
            }
            if (features.isOn("burn_steps")) item { StepsChartCard(week) }
            if (features.isOn("burn_outlook")) item { OutlookCard(db) }
            bodyDays?.let { d ->
                if (features.isOn("burn_sleep") && d.any { it.sleepMinutes != null }) item { SleepCard(d, dayOffset) }
                if (features.isOn("burn_heart") && d.any { it.hrAvg != null || it.restingHr != null }) {
                    item { HeartRateCard(d) }
                }
                val first = d.first()
                if (features.isOn("burn_vitals") && (first.vo2max != null || first.spo2 != null || first.distanceKm != null)) {
                    item { VitalsCard(first, dayOffset) }
                }
                if (features.isOn("burn_workouts") && first.workouts.isNotEmpty()) {
                    item { WorkoutsCard(first, dayOffset) }
                }
            }
            item {
                Expandable("Source data") {
                    ActivityCard(dayOffset)
                    Text(
                        (if (EnergyModel.usesDeviceBmr(body))
                            "Resting burn uses the basal metabolic rate from your watch (${body.deviceBmr!!.f0()} kcal a day). "
                        else "Resting burn comes from your profile. ") +
                            "Activity comes from Health Connect, or from your steps and distance. " +
                            "Estimates can be off by 10 to 20%.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}
