package com.example.foodtracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.foodtracker.backup.BackupService
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
    var meals by remember(dayOffset) { mutableStateOf(loadDay(db, dayOffset)) }
    val goals = remember { goalStore.load() }
    var toDelete by remember { mutableStateOf<Meal?>(null) }
    val totals = remember(meals) { meals.flatMap { it.items }.totals() }
    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    toDelete?.let { meal ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete this meal?") },
            text = { Text("It will be removed from your log.") },
            confirmButton = {
                TextButton(onClick = {
                    db.delete(meal.id)
                    BackupService.autoBackup(context)
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
                TextButton(onClick = onOpenHistory) { Text("History & trends") }
            }
        }
        item { DateNavigator(dayOffset, onDayChange) }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CalorieRing(totals.kcal, goals.kcal)
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("Sugar", "${totals.sugar.f1()} g", Modifier.weight(1f))
                        StatTile("Fat", "${totals.fat.f1()} g", Modifier.weight(1f))
                        StatTile("Carbs", "${totals.carbs.f1()} g", Modifier.weight(1f))
                        StatTile("Protein", "${totals.protein.f1()} g", Modifier.weight(1f))
                    }
                    if (goals.sugar > 0) LabeledBar("Sugar", totals.sugar, goals.sugar, "g", 1)
                    if (goals.fat > 0) LabeledBar("Fat", totals.fat, goals.fat, "g", 1)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Fiber ${totals.fiber.f1()} g  ·  Sat. fat ${totals.satFat.f1()} g  ·  Sodium ${totals.sodium.f0()} mg",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        item { MacroCard(totals) }
        item { WaterCard(dayOffset) }
        item { CoachCard(db, goalStore, dayOffset) }
        item {
            Text("Meals", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        if (meals.isEmpty()) {
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Nothing logged yet", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tap Add meal to photograph a meal, scan a barcode, or type it in.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        items(meals, key = { it.id }) { meal ->
            val t = meal.totals
            AppCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 10.dp, end = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            timeFmt.format(Date(meal.timestamp)).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            meal.description,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            meal.items.joinToString("  ·  ") {
                                if (it.matchedName == "Quick add") it.name else "${it.name} ${it.grams.f0()} g"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "Sugar ${t.sugar.f1()} g  ·  Fat ${t.fat.f1()} g",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            t.kcal.f0(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Text(
                            "kcal",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp)
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
        }
    }
}

/** The old History tab, now a full screen opened from the Intake page. */
@Composable
fun HistoryPage(db: MealDb, goalStore: GoalStore, onBack: () -> Unit, onOpenDay: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        ScreenTopBar("History & trends", onBack)
        Box(Modifier.weight(1f)) {
            HistoryScreen(db, goalStore, PaddingValues(0.dp), onOpenDay)
        }
    }
}
