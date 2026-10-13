package com.example.foodtracker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.foodtracker.data.FoodItem
import com.example.foodtracker.nutrition.NutritionLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Same item and portion, but with different nutrition data. */
fun FoodItem.withMatch(p: NutritionLookup.Per100g): FoodItem = copy(
    matchedName = p.description,
    kcal100 = p.kcal,
    protein100 = p.protein,
    fat100 = p.fat,
    satFat100 = p.satFat,
    carbs100 = p.carbs,
    sugar100 = p.sugar,
    fiber100 = p.fiber,
    sodium100 = p.sodiumMg
)

@Composable
fun MatchPickerDialog(
    foodName: String,
    onPick: (NutritionLookup.Per100g, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(foodName) }
    var results by remember { mutableStateOf<List<NutritionLookup.Per100g>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saveChoice by remember { mutableStateOf(true) }

    fun search() {
        if (query.isBlank()) return
        loading = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { NutritionLookup.searchMany(query.trim(), 10) }
            }
            r.onSuccess {
                results = it
                if (it.isEmpty()) error = "No results. Try a simpler name."
            }.onFailure { error = it.message ?: "Search failed" }
            loading = false
        }
    }
    LaunchedEffect(Unit) { search() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose the right match") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { search() }, enabled = !loading) { Text(if (loading) "…" else "Go") }
                }
                Spacer(Modifier.height(8.dp))
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(results) { r ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable { onPick(r, saveChoice) }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(r.description, fontWeight = FontWeight.Medium)
                            Text(
                                "${r.kcal.f0()} kcal  ·  Sugar ${r.sugar.f1()} g  ·  Fat ${r.fat.f1()} g  (per 100 g)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Remember this choice for \"$foodName\"",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = saveChoice, onCheckedChange = { saveChoice = it })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
