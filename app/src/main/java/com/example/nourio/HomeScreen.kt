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
    val profile = remember { ProfileStore(context).load() }
    val (body, _) = remember { EnergyRepo.body(profile) }
    val goals = remember { goalStore.load() }
    var totals by remember(dayOffset) { mutableStateOf(Totals()) }
    val budget by rememberBudget(db, goalStore, dayOffset)
    var week by remember(dayOffset) { mutableStateOf<List<DayEnergy>>(emptyList()) }   // newest first
    val nf = remember { NumberFormat.getIntegerInstance() }
    val openPage = LocalOpenPage.current
    val water = remember { WaterStore(context) }
    val sleepGoal = remember { com.example.nourio.insights.InsightPrefs(context).sleepGoalH }
    var sleep by remember(dayOffset) { mutableStateOf<com.example.nourio.health.SleepData?>(null) }
    LaunchedEffect(dayOffset) {
        sleep = runCatching { com.example.nourio.health.SleepRepo.load(context, dayOffset) }
            .getOrDefault(com.example.nourio.health.SleepData(false, emptyMap(), emptyMap()))
    }

    LaunchedEffect(dayOffset) {
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

        // The day as a report card: overall grade, subject grades, then the teacher's note
        val report = Grading.report(
            DayInputs(
                totals = totals,
                budgetKcal = goalKcal,
                goals = goals,
                weightKg = profile.weightKg,
                waterMl = water.get(dayOffset),
                waterGoalMl = water.goalMl,
                steps = day?.steps,
                isToday = dayOffset == 0,
                hourOfDay = Grading.nowHour(),
                sleep = sleep?.nights?.get(dayOffset),
                bedtimeShiftH = sleep?.shifts?.get(dayOffset),
                sleepGoalH = sleepGoal
            )
        )
        item {
            ReportHeader(
                report = report,
                name = profile.name,
                dayOffset = dayOffset,
                eaten = totals.kcal,
                budget = goalKcal,
                burned = day?.burned,
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
        item { TeacherNoteCard(report, dayOffset == 0) }
        // The night before sets up the day: right after the note, before the day's activity
        if (features.isOn("home_sleep")) item {
            LastNightCard(sleep, dayOffset, sleepGoal, report.subjects.firstOrNull { it.kind == SubjectKind.SLEEP })
        }
        if (features.isOn("home_fast")) item { com.example.nourio.fasting.FastingCard(db, dayOffset) }
        if (features.isOn("home_walk")) item { MoveCard(day, totals.kcal, body, dayOffset, goals.stepGoal) }
        if (dayOffset == 0 && features.isOn("home_exercise")) item {
            val bodyDays by rememberBodyData(0)
            com.example.nourio.exercise.ExerciseCard(day, week, body, goals.stepGoal, bodyDays)
        }
        if (features.isOn("home_week") && week.isNotEmpty()) item { WeekCard(week, goalKcal) }
        if (features.isOn("home_weight")) item { WeightOutlookCard(db, body) }
        // The detailed grades come last, after the dashboard
        item { SubjectsCard(report) }
    }
}
