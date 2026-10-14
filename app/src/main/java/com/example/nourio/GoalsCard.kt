package com.example.nourio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.nourio.backup.BackupService
import com.example.nourio.data.GoalStore
import com.example.nourio.data.Goals
import com.example.nourio.data.MealDb
import com.example.nourio.data.ProfileStore
import com.example.nourio.plan.PlanStore
import kotlin.math.roundToInt

@Composable
private fun GField(label: String, unit: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        suffix = { Text(unit) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )
}

@Composable
fun GoalsCard(goalStore: GoalStore, db: MealDb) {
    val context = LocalContext.current
    val current = remember { goalStore.load() }
    val plan = remember { PlanStore(context) }
    fun show(d: Double) = if (d <= 0) "" else if (d == d.toLong().toDouble()) d.f0() else d.f1()
    fun parse(s: String) = s.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 } ?: 0.0

    var kcal by remember { mutableStateOf(show(current.kcal)) }
    var sugar by remember { mutableStateOf(show(current.sugar)) }
    var fat by remember { mutableStateOf(show(current.fat)) }
    var satFat by remember { mutableStateOf(show(current.satFat)) }
    var sodium by remember { mutableStateOf(show(current.sodiumMg)) }
    var carbs by remember { mutableStateOf(show(current.carbs)) }
    var protein by remember { mutableStateOf(show(current.protein)) }
    var fiber by remember { mutableStateOf(show(current.fiber)) }
    var saved by remember { mutableStateOf(false) }

    // Calories from the target-weight plan when it is in use, otherwise the typed calorie goal
    val budget by rememberBudget(db, goalStore, 0)
    var fillNote by remember { mutableStateOf<String?>(null) }

    // Only fills empty fields. Sugar is skipped: the app counts total sugar, not added sugar.
    // Fat, saturated fat and carbs follow the FDA Daily Value proportions (78 g, 20 g and 275 g at 2,000 kcal).
    fun fillGuidelines() {
        val kcalGoal = budget?.takeIf { it.plan != null }?.baseKcal ?: parse(kcal)
        val weight = ProfileStore(context).load().weightKg
        if (sodium.isBlank()) sodium = "2300"
        if (fiber.isBlank()) fiber = "25"
        if (kcalGoal > 0) {
            if (fat.isBlank()) fat = (kcalGoal * 0.35 / 9).roundToInt().toString()
            if (satFat.isBlank()) satFat = (kcalGoal * 0.10 / 9).roundToInt().toString()
            if (carbs.isBlank()) carbs = (kcalGoal * 0.55 / 4).roundToInt().toString()
        }
        if (protein.isBlank() && weight > 0) protein = (weight * 0.8).roundToInt().toString()
        fillNote = when {
            kcalGoal <= 0 -> "Set a calorie goal or a target weight to fill fat, saturated fat and carbs."
            weight <= 0 && protein.isBlank() -> "Add your weight in Personal details to fill protein."
            else -> null
        }
        saved = false
    }

    WidgetCard("Daily limits") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (plan.targetKg > 0 && plan.useTargetGoal) {
                Text(
                    "Calories come from your target weight.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            GField("Calories", "kcal", kcal, Modifier.fillMaxWidth()) { kcal = it; saved = false }

            Text("Maximums", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GField("Sugar", "g", sugar, Modifier.weight(1f)) { sugar = it; saved = false }
                GField("Fat", "g", fat, Modifier.weight(1f)) { fat = it; saved = false }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GField("Sat. fat", "g", satFat, Modifier.weight(1f)) { satFat = it; saved = false }
                GField("Sodium", "mg", sodium, Modifier.weight(1f)) { sodium = it; saved = false }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GField("Carbs", "g", carbs, Modifier.weight(1f)) { carbs = it; saved = false }
                Column(Modifier.weight(1f)) {}
            }

            Text("Minimums", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GField("Protein", "g", protein, Modifier.weight(1f)) { protein = it; saved = false }
                GField("Fiber", "g", fiber, Modifier.weight(1f)) { fiber = it; saved = false }
            }

            OutlinedButton(onClick = { fillGuidelines() }, modifier = Modifier.fillMaxWidth()) {
                Text("Fill common guidelines")
            }
            fillNote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Expandable("What this fills") {
                Text(
                    "Empty fields only. Sodium 2,300 mg, fiber 25 g, and from your calorie goal: fat at 35%, " +
                        "saturated fat at 10% and carbs at 55% of calories. Protein 0.8 g per kg of body weight. " +
                        "Sugar is left to you. Edit freely. General guidelines, not medical advice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = {
                    goalStore.save(
                        Goals(
                            kcal = parse(kcal), sugar = parse(sugar), fat = parse(fat),
                            satFat = parse(satFat), sodiumMg = parse(sodium), carbs = parse(carbs),
                            protein = parse(protein), fiber = parse(fiber),
                            steps = goalStore.load().steps     // set from Insights; keep it
                        )
                    )
                    BackupService.autoBackup(context)
                    saved = true
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) { Text(if (saved) "Saved" else "Save") }
        }
    }
}
