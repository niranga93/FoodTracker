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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.example.nourio.plan.Guidelines
import com.example.nourio.plan.PlanStore
import com.example.nourio.plan.Standards
import kotlinx.coroutines.delay
import java.util.Calendar

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

/** With [autoSave] (the setup guide) changes are saved as you type and when the card closes. */
@Composable
fun GoalsCard(goalStore: GoalStore, db: MealDb, autoSave: Boolean = false) {
    val context = LocalContext.current
    val current = remember { goalStore.load() }
    val plan = remember { PlanStore(context) }
    fun show(d: Double) = if (d <= 0) "" else if (d == d.toLong().toDouble()) d.f0() else d.f1()
    fun parse(s: String) = s.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 } ?: 0.0

    var kcal by remember { mutableStateOf(show(current.kcal)) }
    var sugar by remember { mutableStateOf(show(current.addedSugar)) }
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

    // Replaces every limit with values worked out for this person (see Guidelines for the sources)
    fun fillGuidelines() {
        val b = budget
        val profile = ProfileStore(context).load()
        val planKcal = b?.takeIf { it.plan != null }?.baseKcal
        val kcalGoal = planKcal ?: parse(kcal)
        val maintenance = b?.maintenanceKcal ?: 0.0
        val direction = Guidelines.direction(b?.plan?.dailyChange, kcalGoal, maintenance)
        val age = if (profile.birthYear > 0) Calendar.getInstance().get(Calendar.YEAR) - profile.birthYear else 0
        val v = Guidelines.compute(kcalGoal, maintenance, Guidelines.Person(profile.weightKg, profile.heightCm, age, profile.sex, direction))

        if (planKcal == null && kcal.isBlank()) kcal = v.kcal.f0()
        sugar = v.addedSugar.f0(); fat = v.fat.f0(); satFat = v.satFat.f0(); sodium = v.sodiumMg.f0()
        carbs = v.carbs.f0(); protein = v.protein.f0(); fiber = v.fiber.f0()

        val goalWord = when (direction) {
            Guidelines.Direction.LOSE -> "losing weight"
            Guidelines.Direction.GAIN -> "gaining weight"
            Guidelines.Direction.KEEP -> "keeping your weight"
        }
        fillNote = buildString {
            append(
                when (v.kcalSource) {
                    "your goal" -> "Based on ${v.kcal.f0()} kcal"
                    "maintenance" -> "Based on your maintenance of about ${v.kcal.f0()} kcal"
                    else -> "Based on the ${v.kcal.f0()} kcal reference"
                }
            )
            v.proteinPerKg?.let { append(", ${profile.weightKg.f0()} kg and $goalWord (protein ${it.f1()} g/kg)") }
            append(".")
            if (v.proteinPerKg == null) append(" Add your weight in Personal details for a protein goal that fits you.")
            if (v.kcalSource == "reference") append(" Add height, weight and year of birth for calories that fit you.")
        }
        saved = false
    }

    fun save() {
        goalStore.save(
            Goals(
                kcal = parse(kcal), addedSugar = parse(sugar), fat = parse(fat),
                satFat = parse(satFat), sodiumMg = parse(sodium), carbs = parse(carbs),
                protein = parse(protein), fiber = parse(fiber),
                steps = goalStore.load().steps     // set from Insights; keep it
            )
        )
        BackupService.autoBackup(context)
        saved = true
    }

    if (autoSave) {
        LaunchedEffect(kcal, sugar, fat, satFat, sodium, carbs, protein, fiber) { delay(600); save() }
        DisposableEffect(Unit) { onDispose { save() } }
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
                GField("Added sugar", "g", sugar, Modifier.weight(1f)) { sugar = it; saved = false }
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

            // Guideline changes found by the monthly AI check, each approved or rejected by the person
            GuidelineUpdates(onLimitsChanged = { fillGuidelines() })
            OutlinedButton(onClick = { fillGuidelines() }, modifier = Modifier.fillMaxWidth()) {
                Text("Use recommended values")
            }
            fillNote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Expandable("How these are worked out") {
                val s = Standards.current
                fun n(x: Double) = if (x % 1.0 == 0.0) "%,d".format(x.toLong()) else "%.1f".format(x)
                fun pct(x: Double) = "${(x * 100).toInt()}%"
                Text(
                    "Replaces the limits below with values for you, from your calorie goal (or your maintenance " +
                        "calories when there's no goal), weight, age and whether you're losing, keeping or gaining weight.\n\n" +
                        "• Protein: ${n(s.proteinPerKgAdult)} g per kg, ${n(s.proteinPerKgOlder)} g from age ${n(s.proteinOlderAge)}, " +
                        "${n(s.proteinPerKgLosing)} g while losing weight and ${n(s.proteinPerKgGaining)} g while " +
                        "gaining. Above a BMI of 25, the weight at BMI 25 is used.\n" +
                        "• Fat: up to ${pct(s.fatShare)} of calories. Saturated fat: up to ${pct(s.satFatShare)}.\n" +
                        "• Carbs: the calories left after protein and fat (40 to 60%).\n" +
                        "• Added sugar: under ${pct(s.addedSugarShare)} of calories, and at most ${n(s.addedSugarCapFemaleG)} g a day " +
                        "(${n(s.addedSugarCapMaleG)} g for men). Natural " +
                        "sugar in whole fruit, vegetables and milk has no limit, so it isn't counted here.\n" +
                        "• Fiber: ${n(s.fiberPer1000Kcal)} g per 1,000 kcal (${n(s.fiberMinG)} to ${n(s.fiberMaxG)} g).\n" +
                        "• Sodium: ${n(s.sodiumMg)} mg. 1,500 mg is often advised for high blood pressure.\n\n" +
                        "From WHO, US dietary guidelines and the American Heart Association. Once a month Nourio " +
                        "asks your AI service whether any of these changed, and shows you each change to approve or " +
                        "reject. Edit freely. " +
                        "General guidelines, not medical advice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!autoSave) Button(onClick = { save() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text(if (saved) "Saved" else "Save")
            }
        }
    }
}
