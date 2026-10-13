package com.example.foodtracker

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.foodtracker.ai.FoodAnalyzer
import com.example.foodtracker.data.GoalStore
import com.example.foodtracker.data.MealDb
import com.example.foodtracker.data.ProfileStore
import com.example.foodtracker.data.WaterStore
import com.example.foodtracker.data.totals
import com.example.foodtracker.diet.ALLERGENS
import com.example.foodtracker.diet.DietStore
import com.example.foodtracker.energy.CalibrationStore
import com.example.foodtracker.energy.EnergyRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val COACH_SYSTEM = """
You are a supportive nutrition coach inside a food-logging app. You receive one day's logged foods, totals and context.
Write plain text only (no markdown), at most 120 words, in this shape:
1-2 sentences summing up the day.
Went well: one or two short lines starting with a dash.
Try next: one specific, practical suggestion.
Rules:
- Be warm, neutral and encouraging. Never shame food choices, weight or body size.
- Give general nutrition information only. Do not diagnose or give medical advice.
- Never suggest skipping meals, eating very little, or compensating for food with exercise.
- If the log looks incomplete or unusually low, say the log may be incomplete and suggest logging the remaining meals. If the person may be eating too little, suggest talking to a doctor or dietitian.
- Respect the user's diet and allergies: never suggest anything that conflicts with them.
- If the day is still in progress, make the suggestion for the rest of the day.
- The user's daily maximums are context only. Do not set new targets.
"""

private class CoachPrefs(context: Context) {
    private val p = context.getSharedPreferences("coach", Context.MODE_PRIVATE)

    var consented: Boolean
        get() = p.getBoolean("consent", false)
        set(v) { p.edit().putBoolean("consent", v).apply() }

    fun text(key: String): String? = p.getString("t_$key", null)
    fun time(key: String): Long = p.getLong("ts_$key", 0L)
    fun save(key: String, text: String) {
        p.edit().putString("t_$key", text).putLong("ts_$key", System.currentTimeMillis()).apply()
    }
}

private fun dateKey(offset: Int): String {
    val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
}

/** Only food, totals and context are sent. No name, weight, age or other profile details. */
private suspend fun buildPrompt(context: Context, db: MealDb, goalStore: GoalStore, offset: Int): String {
    val meals = withContext(Dispatchers.IO) { loadDay(db, offset) }.sortedBy { it.timestamp }
    val t = meals.flatMap { it.items }.totals()
    val goals = goalStore.load()
    val water = WaterStore(context)
    val diet = DietStore(context).load()
    val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
    val nf = NumberFormat.getIntegerInstance(Locale.US)

    // Estimated energy balance, when the profile has enough data
    val (body, _) = EnergyRepo.body(ProfileStore(context).load())
    var energyLine: String? = null
    var stepsLine: String? = null
    if (body != null) {
        val act = EnergyRepo.activityFor(context, listOf(offset))[offset]
        val raw = EnergyRepo.build(body, offset, t.kcal, act)
        val burned = raw.burned * CalibrationStore(context).factor
        energyLine = "Estimated calories burned: ${burned.f0()} kcal " +
            "(balance ${"%+.0f".format(t.kcal - burned)} kcal)" +
            if (offset == 0) ", assuming no more activity today" else ""
        raw.steps?.let { stepsLine = "Steps: ${nf.format(it)}" }
    }

    return buildString {
        appendLine("Day: ${dayTitle(offset)} (${if (offset == 0) "still in progress" else "complete day"})")
        appendLine("Meals logged:")
        meals.forEach { m ->
            appendLine(
                "- ${timeFmt.format(Date(m.timestamp))}: " +
                    m.items.joinToString("; ") { "${it.name} ${it.grams.f0()} g (${it.kcal.f0()} kcal)" }
            )
        }
        appendLine(
            "Totals: ${t.kcal.f0()} kcal, sugar ${t.sugar.f1()} g, fat ${t.fat.f1()} g " +
                "(saturated ${t.satFat.f1()} g), carbs ${t.carbs.f1()} g, protein ${t.protein.f1()} g, " +
                "fiber ${t.fiber.f1()} g, sodium ${t.sodium.f0()} mg"
        )
        val limits = buildList {
            if (goals.kcal > 0) add("calories ${goals.kcal.f0()}")
            if (goals.sugar > 0) add("sugar ${goals.sugar.f0()} g")
            if (goals.fat > 0) add("fat ${goals.fat.f0()} g")
        }
        if (limits.isNotEmpty()) appendLine("User's own daily maximums: ${limits.joinToString(", ")}")
        appendLine("Water: ${nf.format(water.get(offset))} ml (goal ${nf.format(water.goalMl)} ml)")
        energyLine?.let { appendLine(it) }
        stepsLine?.let { appendLine(it) }
        if (!diet.isEmpty()) {
            val labels = ALLERGENS.filter { it.first in diet.allergens }.map { it.second }
            appendLine(
                "Diet: ${diet.diets.joinToString(", ").ifBlank { "none stated" }}. " +
                    "Allergies: ${labels.joinToString(", ").ifBlank { "none stated" }}. " +
                    "Also avoids: ${diet.other.joinToString(", ").ifBlank { "nothing stated" }}."
            )
        }
    }
}

@Composable
fun CoachCard(db: MealDb, goalStore: GoalStore, dayOffset: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { CoachPrefs(context) }
    val key = remember(dayOffset) { dateKey(dayOffset) }
    var text by remember(dayOffset) { mutableStateOf(prefs.text(key)) }
    var generatedAt by remember(dayOffset) { mutableStateOf(prefs.time(key)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var askConsent by remember { mutableStateOf(false) }
    val hasMeals = remember(dayOffset) { loadDay(db, dayOffset).isNotEmpty() }
    val timeFmt = remember { SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()) }

    fun generate() {
        loading = true
        error = null
        scope.launch {
            val prompt = buildPrompt(context, db, goalStore, dayOffset)
            FoodAnalyzer.askText(COACH_SYSTEM, prompt).fold(
                onSuccess = {
                    text = it
                    prefs.save(key, it)
                    generatedAt = prefs.time(key)
                },
                onFailure = { error = it.message ?: "Could not get a summary" }
            )
            loading = false
        }
    }

    fun request() {
        if (prefs.consented) generate() else askConsent = true
    }

    if (askConsent) {
        AlertDialog(
            onDismissRequest = { askConsent = false },
            title = { Text("Send this day to Gemini?") },
            text = {
                Text(
                    "The AI coach sends the day's foods, amounts and totals, your goals, water, estimated " +
                        "calories burned and diet preferences to Google's Gemini API to write the summary. " +
                        "Your name, weight, age and other profile details are not sent. On the free tier, " +
                        "Google may use this data to improve its products and humans may review it."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.consented = true
                    askConsent = false
                    generate()
                }) { Text("Agree and continue") }
            },
            dismissButton = { TextButton(onClick = { askConsent = false }) { Text("Cancel") } }
        )
    }

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("AI coach", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            val t = text
            when {
                loading -> Text(
                    "Writing your summary…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                t != null -> {
                    Text(t, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Generated ${timeFmt.format(Date(generatedAt))}. AI-generated general information, " +
                            "not medical advice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    TextButton(onClick = { request() }, enabled = hasMeals) { Text("Refresh") }
                }
                !hasMeals -> Text(
                    "Log at least one meal to get a summary for this day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    Text(
                        "A short, friendly summary of this day with one idea to try next.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { request() }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Get summary")
                    }
                }
            }
            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
