package com.example.nourio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nourio.data.FeaturePrefs
import com.example.nourio.data.GoalStore
import com.example.nourio.data.MealDb
import com.example.nourio.data.Profile
import com.example.nourio.data.ProfileStore
import com.example.nourio.data.WeightDb
import com.example.nourio.diet.DietStore
import com.example.nourio.plan.PlanStore
import kotlinx.coroutines.delay
import java.util.Calendar

// ---------------------------------------------------------------- sub-pages

@Composable
fun PageScreen(id: String, db: MealDb, goalStore: GoalStore, onBack: () -> Unit) {
    val title = when (id) {
        "me" -> "Personal details"
        "goals" -> "Goals & target"
        "diet" -> "Diet & allergies"
        "weight" -> "Weigh-ins"
        "connections" -> "Connections"
        "data" -> "Data & privacy"
        "about" -> "About"
        "insights" -> "Insights & patterns"
        "fasting" -> "Fasting"
        else -> when {
            id.startsWith("budget") -> "Your calorie budget"
            id.startsWith("body") -> "Sleep, heart and vitals"
            else -> "Settings"
        }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        ScreenTopBar(title, onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (id) {
                "me" -> PersonalCard()
                "goals" -> { GoalsCard(goalStore, db); TargetCard() }
                "diet" -> DietCard()
                "weight" -> WeightCard()
                "connections" -> {
                    GeminiKeyCard(); HealthConnectorCard(); HealthExtrasCard()
                    BackupCard(db, goalStore)
                    BackupFileCard(db, goalStore)
                }
                "data" -> {
                    DataCard(db)
                    PrivacyCard()
                }
                "about" -> AboutContent()
                "insights" -> InsightsPage(db)
                "fasting" -> com.example.nourio.fasting.FastingPage(db)
                // "budget:<day offset>", opened from the Home screen for the day shown there
                else -> if (id.startsWith("body")) {
                    BodyPage(id.substringAfter(':', "0").toIntOrNull() ?: 0)
                } else if (id.startsWith("budget")) {
                    BudgetPage(db, goalStore, id.substringAfter(':', "0").toIntOrNull() ?: 0)
                } else SettingsContent()
            }
        }
    }
}

// ---------------------------------------------------------------- settings

@Composable
fun SettingsContent() {
    val context = LocalContext.current
    val prefs = remember { FeaturePrefs(context) }
    val plan = remember { PlanStore(context) }

    FeaturePrefs.ALL.groupBy { it.group }.forEach { (group, features) ->
        GroupTitle(group)
        AppCard(Modifier.fillMaxWidth()) {
            Column {
                features.forEachIndexed { i, f ->
                    key(f.key) {
                        var on by remember { mutableStateOf(prefs.isOn(f.key)) }
                        SwitchRow(f.title, f.hint, on) { on = it; prefs.set(f.key, it) }
                    }
                    if (i < features.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }

    GroupTitle("Budget")
    AppCard(Modifier.fillMaxWidth()) {
        Column {
            var carry by remember { mutableStateOf(plan.carryOver) }
            var useTarget by remember { mutableStateOf(plan.useTargetGoal) }
            SwitchRow("Carry over", "Yesterday's gap, up to 300 kcal", carry) { carry = it; plan.carryOver = it }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SwitchRow("Target-weight goal", "Calories from your target", useTarget) {
                useTarget = it; plan.useTargetGoal = it
            }
        }
    }
}

// ---------------------------------------------------------------- personal details form

private fun numStr(v: Double): String =
    if (v <= 0) "" else if (v == v.toLong().toDouble()) v.f0() else v.f1()

private fun parseD(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

/** With [autoSave] (the setup guide) changes are saved as you type and when the card closes, with no Save button. */
@Composable
fun PersonalCard(autoSave: Boolean = false) {
    val context = LocalContext.current
    val store = remember { ProfileStore(context) }
    val saved = remember { store.load() }

    var name by remember { mutableStateOf(saved.name) }
    var birthYear by remember { mutableStateOf(if (saved.birthYear > 0) saved.birthYear.toString() else "") }
    var sex by remember { mutableStateOf(saved.sex) }
    var height by remember { mutableStateOf(numStr(saved.heightCm)) }
    var weight by remember { mutableStateOf(numStr(saved.weightKg)) }
    var error by remember { mutableStateOf<String?>(null) }
    var justSaved by remember { mutableStateOf(false) }

    fun save() {
        val h = if (height.isBlank()) 0.0 else parseD(height) ?: -1.0
        val w = if (weight.isBlank()) 0.0 else parseD(weight) ?: -1.0
        val y = if (birthYear.isBlank()) 0 else birthYear.trim().toIntOrNull() ?: -1
        val thisYear = Calendar.getInstance().get(Calendar.YEAR)
        error = when {
            h != 0.0 && h !in 50.0..260.0 -> "Height: 50 to 260 cm"
            w != 0.0 && w !in 20.0..400.0 -> "Weight: 20 to 400 kg"
            y != 0 && y !in 1900..thisYear -> "Check year of birth"
            else -> null
        }
        if (error == null) {
            store.save(Profile(name.trim(), y, sex, h, w))
            justSaved = true
        }
    }

    if (autoSave) {
        LaunchedEffect(name, birthYear, sex, height, weight) { delay(600); save() }
        DisposableEffect(Unit) { onDispose { save() } }
    }

    val bmi = remember(height, weight) {
        Profile(heightCm = parseD(height) ?: 0.0, weightKg = parseD(weight) ?: 0.0).bmi
    }

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it; justSaved = false },
                label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = height, onValueChange = { height = it; justSaved = false },
                    label = { Text("Height") }, suffix = { Text("cm") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = weight, onValueChange = { weight = it; justSaved = false },
                    label = { Text("Weight") }, suffix = { Text("kg") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = birthYear, onValueChange = { birthYear = it; justSaved = false },
                label = { Text("Year of birth") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Female", "Male", "Other").forEach { option ->
                    FilterChip(
                        selected = sex == option,
                        onClick = { sex = if (sex == option) "" else option; justSaved = false },
                        label = { Text(option) }
                    )
                }
            }
            bmi?.let { Pill("BMI ${it.f1()}") }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (!autoSave) Button(onClick = { save() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text(if (justSaved) "Saved" else "Save")
            }
        }
    }
}
