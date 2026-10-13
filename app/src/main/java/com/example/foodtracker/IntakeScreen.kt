package com.example.foodtracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.foodtracker.data.FeaturePrefs
import com.example.foodtracker.data.GoalStore
import com.example.foodtracker.data.Meal
import com.example.foodtracker.data.MealDb
import com.example.foodtracker.data.totals
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun IntakeScreen(
    db: MealDb,
    goalStore: GoalStore,
    dayOffset: Int,
    onDayChange: (Int) -> Unit,
    onEditMeal: (Long) -> Unit,
    onOpenHistory: () -> Unit,
    inner: PaddingValues
) {
    val context = LocalContext.current
    val features = remember { FeaturePrefs(context) }
    var meals by remember(dayOffset) { mutableStateOf(loadDay(db, dayOffset)) }
    val goals = remember { goalStore.load() }
    val budget by rememberBudget(db, goalStore, dayOffset)
    var toDelete by remember { mutableStateOf<Meal?>(null) }
    val totals = remember(meals) { meals.flatMap { it.items }.totals() }
    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val sugarGoal = budget?.sugarGoal ?: goals.sugar
    val fatGoal = budget?.fatGoal ?: goals.fat

    toDelete?.let { meal ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete this meal?") },
            confirmButton = {
                TextButton(onClick = {
                    db.delete(meal.id)
                    meals = loadDay(db, dayOffset)
                    toDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(inner),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Intake", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = onOpenHistory) { Text("History") }
            }
        }
        item { DateNavigator(dayOffset, onDayChange) }

        if (features.isOn("in_sources")) item { CalorieSourcesCard(totals) }
        if (features.isOn("in_limits")) item { LimitsCard(totals.sugar, sugarGoal, totals.fat, fatGoal) }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text("Meals", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (meals.isNotEmpty()) {
                    Text(
                        "${totals.kcal.f0()} kcal",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (meals.isEmpty()) {
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(
                        "Nothing logged yet. Tap Add meal.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp)
                    )
                }
            }
        }
        items(meals, key = { it.id }) { meal ->
            AppCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            timeFmt.format(Date(meal.timestamp)).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            meal.description,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        meal.totals.kcal.f0(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    MealMenu(
                        meal = meal,
                        db = db,
                        onEdit = { onEditMeal(meal.id) },
                        onDelete = { toDelete = meal },
                        onLoggedToday = { if (dayOffset != 0) onDayChange(0) else meals = loadDay(db, 0) }
                    )
                }
            }
        }

        if (features.isOn("in_timing")) item { MealTimingCard(meals) }
        if (features.isOn("in_top")) item { BiggestItemsCard(meals) }
        if (features.isOn("in_water")) item { HydrationCard(dayOffset) }
        if (features.isOn("in_coach")) item { CoachCard(db, goalStore, dayOffset) }
    }
}

/** The old History tab, now a full screen opened from the Intake page. */
@Composable
fun HistoryPage(db: MealDb, goalStore: GoalStore, onBack: () -> Unit, onOpenDay: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        ScreenTopBar("History", onBack)
        Box(Modifier.weight(1f)) {
            HistoryScreen(db, goalStore, PaddingValues(0.dp), onOpenDay)
        }
    }
}
