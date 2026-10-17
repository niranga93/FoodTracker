package com.example.nourio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import com.example.nourio.data.FeaturePrefs
import com.example.nourio.data.GoalStore
import com.example.nourio.data.MealDb
import com.example.nourio.data.ProfileStore
import com.example.nourio.data.Totals
import com.example.nourio.data.WaterStore
import com.example.nourio.data.WeightDb
import com.example.nourio.data.totals
import com.example.nourio.energy.Body
import com.example.nourio.energy.CalibrationStore
import com.example.nourio.energy.DayEnergy
import com.example.nourio.energy.EnergyModel
import com.example.nourio.energy.EnergyRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.withIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.ceil

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
fun HomeScreen(
    db: MealDb,
    goalStore: GoalStore,
    dayOffset: Int,
    onDayChange: (Int) -> Unit,
    onOpenTab: (NavTab) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPending: (Long) -> Unit,
    inner: PaddingValues
) {
    val context = LocalContext.current
    val features = remember { FeaturePrefs(context) }
    val refresh = LocalRefresh.current
    val profile = remember(refresh) { ProfileStore(context).load() }
    val (body, _) = remember(profile) { EnergyRepo.body(profile) }
    val goals = remember(refresh) { goalStore.load() }
    // Starts from the day's real totals (a quick local read), so a new day never flashes "0 eaten"
    var totals by remember(dayOffset) { mutableStateOf(loadDay(db, dayOffset).flatMap { it.items }.totals()) }
    val budget by rememberBudget(db, goalStore, dayOffset)
    var week by remember(dayOffset) { mutableStateOf<List<DayEnergy>>(emptyList()) }   // newest first
    val nf = remember { NumberFormat.getIntegerInstance() }
    val openPage = LocalOpenPage.current
    val water = remember { WaterStore(context) }
    var waterMl by remember(dayOffset, refresh) { mutableIntStateOf(water.get(dayOffset)) }
    // Latest weigh-in up to this day, and the change from about a week before it
    // Live weigh-ins: logging a weight on the Weigh-ins page updates the week card on return
    val weightDb = remember { WeightDb(context) }
    val weights by remember { weightDb.observeAll() }.collectAsStateWithLifecycle(initialValue = remember { weightDb.all() })
    val weightLine = remember(dayOffset, weights) {
        val zone = java.time.ZoneId.systemDefault()
        val end = java.time.LocalDate.now(zone).plusDays(dayOffset.toLong() + 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val all = weights.filter { it.ts < end }
        all.lastOrNull()?.let { last ->
            val weekAgo = all.lastOrNull { it.ts <= last.ts - 6 * 86_400_000L }
            WeightLine(last.kg, weekAgo?.let { last.kg - it.kg })
        }
    }
    val sleepGoal = remember { com.example.nourio.insights.InsightPrefs(context).sleepGoalH }
    var sleep by remember(dayOffset) { mutableStateOf<com.example.nourio.health.SleepData?>(null) }
    LaunchedEffect(dayOffset, LocalRefresh.current) {
        sleep = runCatching { com.example.nourio.health.SleepRepo.load(context, dayOffset) }
            .getOrDefault(com.example.nourio.health.SleepData(false, emptyMap(), emptyMap()))
    }

    // Every change to the meal log gets a new number (an edit keeps the meal count the same), so the
    // day's totals and the week recompute after any add, edit or delete
    val mealVersion by remember { db.observeChanges().withIndex().map { it.index } }.collectAsStateWithLifecycle(initialValue = 0)
    LaunchedEffect(dayOffset, mealVersion, LocalRefresh.current) {
        totals = withContext(Dispatchers.IO) { loadDay(db, dayOffset).flatMap { it.items }.totals() }
        if (body != null) {
            val offs = (0..6).map { dayOffset - it }
            val acts = EnergyRepo.activityFor(context, offs)
            val factor = CalibrationStore(context).factor
            val mealsByOffset = withContext(Dispatchers.IO) { loadDays(db, offs) }
            week = offs.map { off ->
                val kcal = mealsByOffset[off].orEmpty().flatMap { it.items }.totals().kcal
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
                Spacer(Modifier.width(8.dp))
                RoundNavButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
            }
        }

        // Graded as before; Home shows the grade as a badge and the weakest area as one focus line
        val report = Grading.report(
            DayInputs(
                totals = totals,
                budgetKcal = goalKcal,
                goals = goals,
                weightKg = profile.weightKg,
                waterMl = waterMl,
                waterGoalMl = water.goalMl,
                steps = day?.steps,
                isToday = dayOffset == 0,
                hourOfDay = Grading.nowHour(),
                sleep = sleep?.nights?.get(dayOffset),
                bedtimeShiftH = sleep?.shifts?.get(dayOffset),
                sleepGoalH = sleepGoal
            )
        )
        // 1. Today: energy, the key nutrients and one thing to focus on
        item {
            TodayCard(
                report = report, dayOffset = dayOffset, totals = totals, budget = goalKcal, burned = day?.burned,
                goals = goals, weightKg = profile.weightKg, waterMl = waterMl, waterGoalMl = water.goalMl,
                onAddWater = { waterMl = water.add(dayOffset, 250) },
                onOpenBudget = { openPage("budget:$dayOffset") }
            ) {
                if (body == null) {
                    TextButton(onClick = { onOpenTab(NavTab.Profile) }, contentPadding = PaddingValues(0.dp)) {
                        Text("Add your details for burn and balance")
                    }
                }
                budget?.planMessage?.let { Hint(it, error = true) }
            }
        }
        // Photos taken offline: hidden when there are none
        if (dayOffset == 0) item { PendingMealsCard(onOpenPending) }

        // 2. The body side of the day, one line each; the full views are a tap away
        val rows = buildList<@Composable () -> Unit> {
            if (features.isOn("home_sleep")) add { SleepBodyRow(sleep, dayOffset, sleepGoal) { openPage("body:$dayOffset") } }
            if (features.isOn("home_fast")) add { com.example.nourio.fasting.FastingBodyRow(db, dayOffset) }
            if (features.isOn("home_walk")) add {
                MoveBodyRow(day?.steps, day?.let { (it.burned - it.resting).coerceAtLeast(0.0) }, goals.stepGoal) { onOpenTab(NavTab.Burned) }
            }
        }
        if (rows.isNotEmpty()) item { BodyTodayCard(rows) }

        // 3. The week, with weight folded in
        if (features.isOn("home_week") && week.isNotEmpty()) item {
            WeekCard(week, goalKcal, weightLine) { openPage("weight") }
        }
    }
}
