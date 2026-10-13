package com.example.foodtracker

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.foodtracker.data.FeaturePrefs
import com.example.foodtracker.data.FoodItem
import com.example.foodtracker.diet.ALLERGENS
import com.example.foodtracker.diet.DIETS
import com.example.foodtracker.diet.DietRules
import com.example.foodtracker.diet.DietSettings
import com.example.foodtracker.diet.DietStore

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DietCard() {
    val context = LocalContext.current
    val store = remember { DietStore(context) }
    var s by remember { mutableStateOf(store.load()) }
    var otherText by remember { mutableStateOf(s.other.joinToString(", ")) }

    fun update(n: DietSettings) {
        s = n
        store.save(n)
    }

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Diet & allergies", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Foods that may conflict get a warning when you log them. Saved on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Diet", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DIETS.forEach { d ->
                    FilterChip(
                        selected = d in s.diets,
                        onClick = { update(s.copy(diets = if (d in s.diets) s.diets - d else s.diets + d)) },
                        label = { Text(d) }
                    )
                }
            }
            Text("Allergies", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ALLERGENS.forEach { (key, label) ->
                    FilterChip(
                        selected = key in s.allergens,
                        onClick = {
                            update(s.copy(allergens = if (key in s.allergens) s.allergens - key else s.allergens + key))
                        },
                        label = { Text(label) }
                    )
                }
            }
            OutlinedTextField(
                value = otherText,
                onValueChange = {
                    otherText = it
                    update(s.copy(other = it.split(',').map { w -> w.trim().lowercase() }.filter { w -> w.isNotBlank() }))
                },
                label = { Text("Other foods to avoid (comma separated)") },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Warnings are best effort. Photos can't show hidden ingredients, so always check labels " +
                    "if you have a serious allergy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Warning lines for one item. Shows nothing if there's no conflict. */
@Composable
fun DietWarnings(food: FoodItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (!FeaturePrefs(context).isOn("diet")) return
    val settings = remember { DietStore(context).load() }
    val warnings = remember(food, settings) { DietRules.check(settings, food) }
    if (warnings.isNotEmpty()) {
        Column(modifier) {
            warnings.forEach {
                Text(
                    "⚠ $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** One-line summary above a list of items. */
@Composable
fun DietBanner(items: List<FoodItem>) {
    val context = LocalContext.current
    if (!FeaturePrefs(context).isOn("diet")) return
    val settings = remember { DietStore(context).load() }
    val flagged = remember(items, settings) {
        items.count { DietRules.check(settings, it).isNotEmpty() }
    }
    if (flagged > 0) {
        AppCard(Modifier.fillMaxWidth()) {
            Text(
                "⚠ $flagged item${if (flagged == 1) "" else "s"} may conflict with your diet or allergies. " +
                    "Photos can't show hidden ingredients, so check labels if you have a serious allergy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
