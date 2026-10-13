package com.example.foodtracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.foodtracker.backup.BackupService
import com.example.foodtracker.data.FavoriteMealStore
import com.example.foodtracker.data.Meal
import com.example.foodtracker.data.MealDb
import com.example.foodtracker.data.totals
import com.example.foodtracker.diet.DietRules
import com.example.foodtracker.diet.DietStore

/** The ⋮ menu on a meal card. */
@Composable
fun MealMenu(
    meal: Meal,
    db: MealDb,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onLoggedToday: () -> Unit
) {
    val context = LocalContext.current
    val favs = remember { FavoriteMealStore(context) }
    var open by remember { mutableStateOf(false) }
    var isFav by remember(meal.id) { mutableStateOf(favs.isFavorite(meal.items)) }

    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Meal options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Edit") }, onClick = { open = false; onEdit() })
            DropdownMenuItem(
                text = { Text("Log again now") },
                onClick = {
                    open = false
                    db.insert(meal.description, meal.items, System.currentTimeMillis())
                    BackupService.autoBackup(context)
                    onLoggedToday()
                }
            )
            DropdownMenuItem(
                text = { Text(if (isFav) "Remove from favorites" else "Save as favorite") },
                onClick = {
                    open = false
                    if (isFav) favs.removeBySig(meal.items) else favs.add(meal.description, meal.items)
                    isFav = !isFav
                }
            )
            DropdownMenuItem(text = { Text("Delete") }, onClick = { open = false; onDelete() })
        }
    }
}

@Composable
fun FavoriteMealsScreen(db: MealDb, initialOffset: Int, onBack: () -> Unit, onDone: (Int) -> Unit) {
    val context = LocalContext.current
    val store = remember { FavoriteMealStore(context) }
    var favs by remember { mutableStateOf(store.all()) }
    var offset by rememberSaveable { mutableStateOf(initialOffset) }
    var message by remember { mutableStateOf<String?>(null) }
    val diet = remember { DietStore(context).load() }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        ScreenTopBar("Favorite meals", onBack)
        Column(Modifier.padding(horizontal = 16.dp)) {
            DayPicker(offset) { offset = it; message = null }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (favs.isEmpty()) {
            Text(
                "No favorites yet. Use a meal's ⋮ menu.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(favs, key = { it.id }) { f ->
                val t = f.items.totals()
                val warned = f.items.any { DietRules.check(diet, it).isNotEmpty() }
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f).padding(top = 4.dp)) {
                                Text(
                                    f.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    f.items.joinToString("  ·  ") {
                                        if (it.matchedName == "Quick add") it.name else "${it.name} ${it.grams.f0()} g"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${t.kcal.f0()} kcal  ·  Sugar ${t.sugar.f1()} g  ·  Fat ${t.fat.f1()} g",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { store.remove(f.id); favs = store.all() }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove favorite")
                            }
                        }
                        if (warned) {
                            Text(
                                "⚠ Some items may conflict with your diet or allergies.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        Button(
                            onClick = {
                                db.insert(f.name, f.items, timestampForOffset(offset))
                                BackupService.autoBackup(context)
                                message = "Logged \"${f.name}\" to ${dayTitle(offset)}."
                            },
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 8.dp)
                        ) { Text("Log to ${dayTitle(offset)}") }
                    }
                }
            }
        }
        Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
            OutlinedButton(
                onClick = { onDone(offset) },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) { Text("Done") }
        }
    }
}
