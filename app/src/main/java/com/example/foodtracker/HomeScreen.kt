package com.example.foodtracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.foodtracker.data.FeaturePrefs
import com.example.foodtracker.data.GoalStore
import com.example.foodtracker.data.MealDb
import com.example.foodtracker.data.ProfileStore
import com.example.foodtracker.data.WeightDb
import com.example.foodtracker.data.totals
import com.example.foodtracker.energy.Body
import com.example.foodtracker.energy.CalibrationStore
import com.example.foodtracker.energy.DayEnergy
import com.example.foodtracker.energy.EnergyModel
import com.example.foodtracker.energy.EnergyRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.ceil

private fun sgn(d: Double) = "%+,.0f".format(d)

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
private fun WalkCard(day: DayEnergy?, eaten: Double, body: Body?, dayOffset: Int) {
    val nf = remember { NumberFormat.getIntegerInstance() }
    val steps = day?.steps
    var needed: Long? = null
    if (day != null && body != null && dayOffset == 0 && eaten > 0 && day.balance > 0) {
        needed = ceil(day.balance / EnergyModel.kcalPerStep(body)).toLong()
    }
    WidgetCard(if (dayOffset == 0) "Walk to balance" else "Steps that day") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Steps", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(steps?.let { nf.format(it) } ?: "—", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
            }
            if (dayOffset == 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("To go", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        when {
                            eaten <= 0 -> "—"
                            needed == null -> "None"
                            needed > 15_000 -> "Over days"
                            else -> "≈ ${nf.format(needed)}"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        if (steps != null && needed != null && needed <= 15_000) {
            Spacer(Modifier.height(10.dp))
            SlimBar(steps.toFloat() / (steps + needed), false)
        }
    }
}

@Composable
private fun WeightCardMini(db: MealDb, body: Body?) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(WeightDb(context).all()) }
    var pace by remember { mutableStateOf<Double?>(null) }
    var showLog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (body != null) pace = loadForecast(context, db, body).prediction?.weeklyKg
    }
    if (showLog) {
        LogWeightDialog(onDismiss = { showLog = false }, onSaved = { entries = WeightDb(context).all() })
    }

    WidgetCard("Your weight", trailing = { TextButton(onClick = { showLog = true }) { Text("Log") } }) {
        if (entries.isEmpty()) {
            Hint("No entries yet")
        } else {
            val values = remember(entries) { EnergyModel.trend(entries.takeLast(30).map { it.kg }) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(0.4f)) {
                    Text("${entries.last().kg.f1()} kg", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    pace?.let { Pill("${"%+.1f".format(it)} kg/wk", Modifier.padding(top = 4.dp)) }
                }
                Sparkline(values, Modifier.weight(0.6f).height(56.dp))
            }
        }
    }
}

@Composable
fun HomeScreen(
    db: MealDb,
    goalStore: GoalStore,
    dayOffset: Int,
    onDayChange: (Int) -> Unit,
    onOpenTab: (NavTab) -> Unit,
    onOpenSettings: () -> Unit,
    inner: PaddingValues
) {
    val context = LocalContext.current
    val features = remember { FeaturePrefs(context) }
    val profile = remember { ProfileStore(context).load() }
    val (body, _) = remember { EnergyRepo.body(profile) }
    val goals = remember { goalStore.load() }
    val totals = remember(dayOffset) { loadDay(db, dayOffset).flatMap { it.items }.totals() }
    val budget by rememberBudget(db, goalStore, dayOffset)
    var week by remember(dayOffset) { mutableStateOf<List<DayEnergy>>(emptyList()) }   // newest first
    val nf = remember { NumberFormat.getIntegerInstance() }

    LaunchedEffect(dayOffset) {
        if (body != null) {
            val offs = (0..6).map { dayOffset - it }
            val acts = EnergyRepo.activityFor(context, offs)
            val factor = CalibrationStore(context).factor
            week = offs.map { off ->
                val kcal = withContext(Dispatchers.IO) { loadDay(db, off).flatMap { it.items }.totals().kcal }
                val raw = EnergyRepo.build(body, off, kcal, acts[off])
                raw.copy(burned = raw.burned * factor)
            }
        }
    }

    val day = week.firstOrNull()
    val goalKcal = budget?.kcalGoal?.takeIf { it > 0 } ?: goals.kcal

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(inner),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { DateNavigator(dayOffset, onDayChange) }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
            }
        }

        item {
            WidgetCard(if (dayOffset == 0) "Calories left today" else "Calories left, ${dayTitle(dayOffset)}") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    HeroRing(totals.kcal, goalKcal)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Metric("Eaten", nf.format(totals.kcal.toLong()), Modifier.weight(1f))
                        Metric("Burned", day?.let { nf.format(it.burned.toLong()) } ?: "—", Modifier.weight(1f))
                        Metric(
                            "Balance",
                            if (day != null && totals.kcal > 0) sgn(totals.kcal - day.burned) else "—",
                            Modifier.weight(1f)
                        )
                    }
                    if (body == null) {
                        TextButton(onClick = { onOpenTab(NavTab.Profile) }) { Text("Add your details") }
                    }
                    val b = budget
                    if (features.isOn("home_budget") && b != null && b.kcalGoal > 0) {
                        Expandable("Budget") {
                            Hint("Base ${b.baseKcal.f0()}  ·  ${sgn(b.carryKcal)} carried")
                            b.carryNote?.let { Hint(it) }
                            if (b.agg7.days > 0) {
                                Hint(
                                    "7 days: ${abs(b.agg7.underGoal).f0()} kcal " +
                                        (if (b.agg7.underGoal >= 0) "under" else "over") + " goal"
                                )
                            }
                            b.planMessage?.let { Hint(it, error = true) }
                        }
                    }
                }
            }
        }

        if (features.isOn("home_walk")) item { WalkCard(day, totals.kcal, body, dayOffset) }
        if (features.isOn("home_week") && week.isNotEmpty()) item { BalanceTrendCard(week) }
        if (features.isOn("home_weight")) item { WeightCardMini(db, body) }
    }
}
