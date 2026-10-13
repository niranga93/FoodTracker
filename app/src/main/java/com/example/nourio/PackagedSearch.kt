package com.example.nourio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.nourio.data.FoodItem
import com.example.nourio.nutrition.OffProduct
import com.example.nourio.nutrition.OpenFoodFacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Nutri-Score, NOVA, allergens, additives and ingredients, with the long parts folded. */
@Composable
fun ProductDetails(p: OffProduct) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            p.nutriscore?.let { Pill("Nutri-Score $it") }
            p.nova?.let { Pill("NOVA $it") }
            if (p.additives.isNotEmpty()) Pill("${p.additives.size} additives")
        }
        if (p.allergens.isNotEmpty()) {
            Text(
                "Contains: ${p.allergens.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Expandable("Ingredients") {
            Text(
                p.ingredients ?: "Not listed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (p.additives.isNotEmpty()) {
            Expandable("Additives") {
                Text(
                    p.additives.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            "Data from Open Food Facts",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Text search for packaged products. Reports the chosen item (with grams) to the parent. */
@Composable
fun PackagedPane(modifier: Modifier, onItem: (FoodItem?) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<OffProduct>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<OffProduct?>(null) }
    var gramsText by rememberSaveable { mutableStateOf("100") }

    LaunchedEffect(picked, gramsText) {
        val p = picked
        val g = gramsText.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
        onItem(if (p != null && g > 0) p.item.copy(grams = g) else null)
    }

    fun runSearch() {
        if (query.isBlank()) return
        searching = true
        error = null
        picked = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { OpenFoodFacts.search(query.trim()) } }
            r.onSuccess {
                results = it
                if (it.isEmpty()) error = "No packaged products found."
            }.onFailure { error = it.message ?: "Search failed" }
            searching = false
        }
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Product name") }, singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { runSearch() }, enabled = !searching && query.isNotBlank()) {
                Text(if (searching) "…" else "Search")
            }
        }
        Spacer(Modifier.height(12.dp))
        val p = picked
        when {
            error != null -> Text(error ?: "", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            p != null -> AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.item.name, fontWeight = FontWeight.SemiBold)
                    Text("${p.item.kcal100.f0()} kcal per 100 g", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = gramsText, onValueChange = { gramsText = it },
                            label = { Text("Amount") }, suffix = { Text("g") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        val g = gramsText.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
                        Column(Modifier.padding(start = 16.dp)) {
                            Text("${(p.item.kcal100 * g / 100).f0()} kcal",
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Sugar ${(p.item.sugar100 * g / 100).f1()} g",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    DietWarnings(p.item)
                    ProductDetails(p)
                    OutlinedButton(onClick = { picked = null }) { Text("Choose another") }
                }
            }
            results.isNotEmpty() -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results) { r ->
                    AppCard(Modifier.fillMaxWidth().clickable {
                        picked = r
                        gramsText = r.item.grams.f0()
                    }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(r.item.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${r.item.kcal100.f0()} kcal / 100 g" + (r.nutriscore?.let { "  ·  Nutri-Score $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            DietWarnings(r.item)
                        }
                    }
                }
            }
            else -> Text(
                "Search packaged products by name.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
