package com.example.nourio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.nourio.backup.BackupService
import com.example.nourio.data.FavoritesStore
import com.example.nourio.data.FoodItem
import com.example.nourio.data.MealDb
import com.example.nourio.nutrition.NutritionLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun toNum(s: String): Double = s.trim().replace(',', '.').toDoubleOrNull() ?: 0.0

@Composable
fun ManualEntryScreen(
    db: MealDb,
    initialOffset: Int,
    onBack: () -> Unit,
    onFavorites: () -> Unit,
    onDone: (Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var logOffset by rememberSaveable { mutableIntStateOf(initialOffset) }
    var mode by rememberSaveable { mutableIntStateOf(0) }          // 0 quick add, 1 search, 2 recent

    // quick add
    var qName by rememberSaveable { mutableStateOf("") }
    var qKcal by rememberSaveable { mutableStateOf("") }
    var qSugar by rememberSaveable { mutableStateOf("") }
    var qAdded by rememberSaveable { mutableStateOf("") }      // empty = guess from the name
    var qFat by rememberSaveable { mutableStateOf("") }
    var qCarbs by rememberSaveable { mutableStateOf("") }
    var qProtein by rememberSaveable { mutableStateOf("") }

    // search
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<NutritionLookup.Per100g>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<NutritionLookup.Per100g?>(null) }
    var gramsText by rememberSaveable { mutableStateOf("100") }

    // recent and favorites
    val favStore = remember { FavoritesStore(context) }
    var favKeys by remember { mutableStateOf(favStore.all()) }
    var recents by remember { mutableStateOf<List<FoodItem>>(emptyList()) }
    var recentPick by remember { mutableStateOf<FoodItem?>(null) }
    var recentGrams by rememberSaveable { mutableStateOf("100") }
    var packagedItem by remember { mutableStateOf<FoodItem?>(null) }
    LaunchedEffect(Unit) { recents = withContext(Dispatchers.IO) { db.recentItems(200) } }
    val shown = remember(recents, favKeys) {
        recents.sortedByDescending { FavoritesStore.key(it) in favKeys }   // favorites first, then newest
    }

    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun runSearch() {
        if (query.isBlank()) return
        keyboard?.hide()   // otherwise the keyboard covers the results
        searching = true
        searchError = null
        picked = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { NutritionLookup.searchMany(query.trim(), 10) } }
            r.onSuccess {
                results = it
                if (it.isEmpty()) searchError = "No results. Try a simpler name."
            }.onFailure {
                searchError = it.message ?: "Search failed"
            }
            searching = false
        }
    }

    val item: FoodItem? = when (mode) {
        0 -> {
            val kcal = toNum(qKcal)
            if (kcal > 0) FoodItem(
                name = qName.trim().ifBlank { "Quick add" },
                matchedName = "Quick add",
                grams = 100.0,
                kcal100 = kcal,
                protein100 = toNum(qProtein),
                fat100 = toNum(qFat),
                satFat100 = 0.0,
                carbs100 = toNum(qCarbs),
                sugar100 = toNum(qSugar),
                fiber100 = 0.0,
                sodium100 = 0.0,
                addedSugar100 = qAdded.takeIf { it.isNotBlank() }?.let { toNum(it).coerceAtMost(toNum(qSugar)) }
            ) else null
        }
        1 -> {
            val p = picked
            val g = toNum(gramsText)
            if (p != null && g > 0) FoodItem(
                name = p.description,
                matchedName = p.description,
                grams = g,
                kcal100 = p.kcal,
                protein100 = p.protein,
                fat100 = p.fat,
                satFat100 = p.satFat,
                carbs100 = p.carbs,
                sugar100 = p.sugar,
                fiber100 = p.fiber,
                sodium100 = p.sodiumMg,
                addedSugar100 = p.addedSugar
            ) else null
        }
        3 -> packagedItem
        else -> {
            val f = recentPick
            if (f == null) null else {
                val g = if (f.matchedName == "Quick add") 100.0 else toNum(recentGrams)
                if (g > 0) f.copy(grams = g) else null
            }
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        ScreenTopBar("Add manually", onBack)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Quick add") })
            FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Search food") })
            FilterChip(selected = mode == 3, onClick = { mode = 3 }, label = { Text("Packaged") })
            FilterChip(selected = mode == 2, onClick = { mode = 2 }, label = { Text("Recent") })
            // Saved whole meals have their own screen
            AssistChip(onClick = onFavorites, label = { Text("Favorite meals") })
        }

        when (mode) {
            0 -> Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Only calories are required.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = qName, onValueChange = { qName = it },
                    label = { Text("Name (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = qKcal, onValueChange = { qKcal = it },
                    label = { Text("Calories") }, suffix = { Text("kcal") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = qSugar, onValueChange = { qSugar = it },
                        label = { Text("Sugar") }, suffix = { Text("g") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = qFat, onValueChange = { qFat = it },
                        label = { Text("Fat") }, suffix = { Text("g") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = qCarbs, onValueChange = { qCarbs = it },
                        label = { Text("Carbs") }, suffix = { Text("g") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = qProtein, onValueChange = { qProtein = it },
                        label = { Text("Protein") }, suffix = { Text("g") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = qAdded, onValueChange = { qAdded = it },
                    label = { Text("Of the sugar, added (optional)") }, suffix = { Text("g") }, singleLine = true,
                    supportingText = { Text("On labels as \"Includes added sugars\". Empty: Nourio estimates it.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            1 -> Column(Modifier.weight(1f).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        label = { Text("Food name") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { runSearch() }, enabled = !searching && query.isNotBlank()) {
                        Text(if (searching) "…" else "Search")
                    }
                }
                Spacer(Modifier.height(12.dp))
                val p = picked
                when {
                    searchError != null -> Text(
                        searchError ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    p != null -> AppCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(p.description, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${p.kcal.f0()} kcal per 100 g",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = gramsText, onValueChange = { gramsText = it },
                                    label = { Text("Amount") }, suffix = { Text("g") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.weight(1f)
                                )
                                val g = toNum(gramsText)
                                Column(Modifier.padding(start = 16.dp)) {
                                    Text(
                                        "${(p.kcal * g / 100).f0()} kcal",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "Sugar ${(p.sugar * g / 100).f1()} g",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { picked = null },
                                modifier = Modifier.padding(top = 8.dp)
                            ) { Text("Choose another") }
                        }
                    }
                    results.isNotEmpty() -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(results) { r ->
                            AppCard(Modifier.fillMaxWidth().clickable { picked = r }) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(r.description, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${r.kcal.f0()} kcal  ·  Sugar ${r.sugar.f1()} g  ·  Fat ${r.fat.f1()} g  (per 100 g)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    else -> Text(
                        "Search a food, then pick a match.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            3 -> PackagedPane(Modifier.weight(1f).padding(16.dp)) { packagedItem = it }
            else -> Column(Modifier.weight(1f).padding(16.dp)) {
                val rp = recentPick
                when {
                    rp != null -> AppCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(rp.name, fontWeight = FontWeight.SemiBold)
                            if (rp.matchedName == "Quick add") {
                                Text(
                                    "${rp.kcal100.f0()} kcal (quick add)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    "${rp.kcal100.f0()} kcal per 100 g",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = recentGrams, onValueChange = { recentGrams = it },
                                        label = { Text("Amount") }, suffix = { Text("g") }, singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        modifier = Modifier.weight(1f)
                                    )
                                    val g = toNum(recentGrams)
                                    Column(Modifier.padding(start = 16.dp)) {
                                        Text(
                                            "${(rp.kcal100 * g / 100).f0()} kcal",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "Sugar ${(rp.sugar100 * g / 100).f1()} g",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            OutlinedButton(
                                onClick = { recentPick = null },
                                modifier = Modifier.padding(top = 8.dp)
                            ) { Text("Choose another") }
                        }
                    }
                    shown.isEmpty() -> Text(
                        "Your logged foods appear here. Heart = favorite.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shown) { f ->
                            val fav = FavoritesStore.key(f) in favKeys
                            val quick = f.matchedName == "Quick add"
                            AppCard(
                                Modifier.fillMaxWidth().clickable {
                                    recentPick = f
                                    recentGrams =
                                        if (f.grams == f.grams.toLong().toDouble()) f.grams.f0() else f.grams.f1()
                                }
                            ) {
                                Row(
                                    Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            f.name,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            if (quick) "${f.kcal.f0()} kcal"
                                            else "${f.grams.f0()} g  ·  ${f.kcal.f0()} kcal",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { favKeys = favStore.toggle(f) }) {
                                        Icon(
                                            if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                            contentDescription = if (fav) "Remove favorite" else "Add favorite"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item?.let { DietWarnings(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }

        ActionBar(
            logOffset = logOffset,
            onOffset = { logOffset = it },
            secondaryLabel = "Cancel",
            onSecondary = onBack,
            saveEnabled = item != null,
            onSave = {
                item?.let {
                    db.insert(it.name, listOf(it), timestampForOffset(logOffset))
                    BackupService.autoBackup(context)
                    onDone(logOffset)
                }
            }
        )
    }
}
