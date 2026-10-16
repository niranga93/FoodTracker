package com.example.nourio

import androidx.core.content.edit
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nourio.ai.FoodAnalyzer
import com.example.nourio.data.GoalStore
import com.example.nourio.data.MealDb
import com.example.nourio.data.ProfileStore
import com.example.nourio.data.WaterStore
import com.example.nourio.data.totals
import com.example.nourio.diet.ALLERGENS
import com.example.nourio.diet.DietStore
import com.example.nourio.energy.CalibrationStore
import com.example.nourio.energy.EnergyRepo
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

/** Withdraws the coach's consent; it asks again before its next request. Saved notes stay on the phone. */
fun withdrawCoachConsent(context: Context) {
    CoachPrefs(context).consented = false
}

private class CoachPrefs(private val context: Context) {
    private val p = context.getSharedPreferences("coach", Context.MODE_PRIVATE)

    // The consent lives with the others, so the switch in Data & privacy and this stay the same
    var consented: Boolean
        get() = com.example.nourio.ai.AiConsent.isGranted(context, com.example.nourio.ai.AiUse.COACH)
        set(v) { com.example.nourio.ai.AiConsent.set(context, com.example.nourio.ai.AiUse.COACH, v) }

    fun text(key: String): String? = p.getString("t_$key", null)
    fun time(key: String): Long = p.getLong("ts_$key", 0L)
    fun save(key: String, text: String) {
        p.edit { putString("t_$key", text).putLong("ts_$key", System.currentTimeMillis()) }
    }
}

private fun dateKey(offset: Int): String {
    val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
}

// The privacy policy says Health Connect data is not sent to Gemini. Keep this false
// unless you also update the policy and the consent text.
private const val SEND_HEALTH_CONNECT_DATA = false

/** Only food, totals and context are sent. No name, weight, age or other profile details. */
private suspend fun buildPrompt(context: Context, db: MealDb, goalStore: GoalStore, offset: Int): String {
    val meals = withContext(Dispatchers.IO) { loadDay(db, offset) }.sortedBy { it.timestamp }
    val t = meals.flatMap { it.items }.totals()
    val goals = goalStore.load()
    val water = WaterStore(context)
    val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
    val nf = NumberFormat.getIntegerInstance(Locale.US)

    // Estimated energy balance, when the profile has enough data
    val (body, _) = EnergyRepo.body(ProfileStore(context).load())
    var energyLine: String? = null
    var stepsLine: String? = null
    if (body != null) {
        val act = if (SEND_HEALTH_CONNECT_DATA) EnergyRepo.activityFor(context, listOf(offset))[offset] else null
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
            "Totals: ${t.kcal.f0()} kcal, sugar ${t.sugar.f1()} g (added ${t.addedSugar.f1()} g), fat ${t.fat.f1()} g " +
                "(saturated ${t.satFat.f1()} g), carbs ${t.carbs.f1()} g, protein ${t.protein.f1()} g, " +
                "fiber ${t.fiber.f1()} g, sodium ${t.sodium.f0()} mg"
        )
        val limits = buildList {
            if (goals.kcal > 0) add("calories ${goals.kcal.f0()}")
            if (goals.addedSugar > 0) add("added sugar ${goals.addedSugar.f0()} g (natural sugar in fruit, vegetables and milk has no limit)")
            if (goals.fat > 0) add("fat ${goals.fat.f0()} g")
        }
        if (limits.isNotEmpty()) appendLine("User's own daily maximums: ${limits.joinToString(", ")}")
        appendLine("Water: ${nf.format(water.get(offset))} ml (goal ${nf.format(water.goalMl)} ml)")
        energyLine?.let { appendLine(it) }
        stepsLine?.let { appendLine(it) }
        dietLine(context)?.let { appendLine(it) }
    }
}

@Composable
fun CoachCard(db: MealDb, goalStore: GoalStore, dayOffset: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { CoachPrefs(context) }
    val key = remember(dayOffset) { dateKey(dayOffset) }
    var text by remember(dayOffset) { mutableStateOf(prefs.text(key)) }
    var generatedAt by remember(dayOffset) { mutableLongStateOf(prefs.time(key)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var askConsent by remember { mutableStateOf(false) }
    var hasMeals by remember(dayOffset) { mutableStateOf(false) }
    LaunchedEffect(dayOffset) {
        hasMeals = withContext(Dispatchers.IO) { loadDay(db, dayOffset).isNotEmpty() }
    }
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
        AiConsentDialog(
            com.example.nourio.ai.AiUse.COACH,
            onAgree = {
                prefs.consented = true
                askConsent = false
                generate()
            },
            onDismiss = { askConsent = false }
        )
    }

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Coach note", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            val t = text
            when {
                loading -> Text(
                    "Writing…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                t != null -> {
                    Text(t, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${timeFmt.format(Date(generatedAt))} · AI-generated, not medical advice",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    TextButton(onClick = { request() }, enabled = hasMeals) { Text("Refresh") }
                }
                !hasMeals -> Text(
                    "Log a meal first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    Text(
                        "One short note on your day.",
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

private fun dietLine(context: Context): String? {
    val diet = DietStore(context).load()
    if (diet.isEmpty()) return null
    val labels = ALLERGENS.filter { it.first in diet.allergens }.map { it.second }
    return "Diet: ${diet.diets.joinToString(", ").ifBlank { "none stated" }}. " +
        "Allergies: ${labels.joinToString(", ").ifBlank { "none stated" }}. " +
        "Also avoids: ${diet.other.joinToString(", ").ifBlank { "nothing stated" }}."
}

// ---------------------------------------------------------------- weekly review

private const val WEEKLY_SYSTEM = """
You are a supportive nutrition coach inside a food-logging app. You receive the user's food log for
the last 7 days: per day the meals with times, foods and amounts, the day's totals and water.
Write plain text only (no markdown), at most 170 words, in this shape:
This week: 2 sentences summing up the week.
Patterns: two or three short lines starting with a dash. Look for real patterns across days, for
example meal timing, weekdays versus weekend, late eating, protein or fiber, sugar, water, repeated
foods. Mention specific days or foods so it feels personal.
Try this week: one specific, practical suggestion.
Rules:
- Be warm, neutral and encouraging. Never shame food choices, weight or body size.
- Give general nutrition information only. Do not diagnose or give medical advice.
- Never suggest skipping meals, eating very little, or compensating for food with exercise.
- Days marked "nothing logged" are gaps in the log, not fasting. Very low days are probably incomplete logs.
- If intake looks consistently very low, suggest talking to a doctor or dietitian.
- Respect the user's diet and allergies: never suggest anything that conflicts with them.
- The user's daily maximums are context only. Do not set new targets.
"""

private const val WEEK_DAYS = 7
private const val MIN_DAYS_FOR_WEEKLY = 3

/** Only food, totals, water, goals and diet are sent. No profile or Health Connect data. */
private fun buildWeekPrompt(context: Context, db: MealDb, goalStore: GoalStore): String {
    val offsets = (-WEEK_DAYS..-1).toList()                 // the last 7 complete days, oldest first
    val byDay = loadDays(db, offsets)
    val goals = goalStore.load()
    val water = WaterStore(context)
    val dayFmt = SimpleDateFormat("EEE d MMM", Locale.US)
    val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
    val nf = NumberFormat.getIntegerInstance(Locale.US)

    return buildString {
        offsets.forEach { off ->
            val date = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, off) }.time
            val meals = byDay[off].orEmpty().sortedBy { it.timestamp }
            if (meals.isEmpty()) {
                appendLine("${dayFmt.format(date)}: nothing logged")
                return@forEach
            }
            val t = meals.flatMap { it.items }.totals()
            appendLine(
                "${dayFmt.format(date)}: ${t.kcal.f0()} kcal, protein ${t.protein.f0()} g, carbs ${t.carbs.f0()} g, " +
                    "fat ${t.fat.f0()} g, sugar ${t.sugar.f0()} g (added ${t.addedSugar.f0()} g), fiber ${t.fiber.f0()} g, sodium ${t.sodium.f0()} mg, " +
                    "water ${nf.format(water.get(off))} ml"
            )
            meals.forEach { m ->
                appendLine(
                    "  ${timeFmt.format(Date(m.timestamp))} " +
                        m.items.take(8).joinToString("; ") { "${it.name} ${it.grams.f0()} g" }
                )
            }
        }
        val limits = buildList {
            if (goals.kcal > 0) add("calories ${goals.kcal.f0()}")
            if (goals.addedSugar > 0) add("added sugar ${goals.addedSugar.f0()} g (natural sugar in fruit, vegetables and milk has no limit)")
            if (goals.fat > 0) add("fat ${goals.fat.f0()} g")
        }
        if (limits.isNotEmpty()) appendLine("User's own daily maximums: ${limits.joinToString(", ")}")
        appendLine("Water goal: ${nf.format(water.goalMl)} ml")
        dietLine(context)?.let { appendLine(it) }
    }
}

@Composable
fun WeeklyReviewCard(db: MealDb, goalStore: GoalStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { CoachPrefs(context) }
    val key = remember { "week-" + dateKey(-1) }            // one review per week ending yesterday
    var text by remember { mutableStateOf(prefs.text(key)) }
    var generatedAt by remember { mutableLongStateOf(prefs.time(key)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var askConsent by remember { mutableStateOf(false) }
    var daysLogged by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        daysLogged = withContext(Dispatchers.IO) {
            loadDays(db, (-WEEK_DAYS..-1).toList()).count { it.value.isNotEmpty() }
        }
    }
    val enough = (daysLogged ?: 0) >= MIN_DAYS_FOR_WEEKLY
    val timeFmt = remember { SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()) }

    fun generate() {
        loading = true
        error = null
        scope.launch {
            val prompt = withContext(Dispatchers.IO) { buildWeekPrompt(context, db, goalStore) }
            FoodAnalyzer.askText(WEEKLY_SYSTEM, prompt).fold(
                onSuccess = {
                    text = it
                    prefs.save(key, it)
                    generatedAt = prefs.time(key)
                },
                onFailure = { error = it.message ?: "Could not get a review" }
            )
            loading = false
        }
    }

    fun request() {
        if (prefs.consented) generate() else askConsent = true
    }

    if (askConsent) {
        AiConsentDialog(
            com.example.nourio.ai.AiUse.COACH,
            onAgree = {
                prefs.consented = true
                askConsent = false
                generate()
            },
            onDismiss = { askConsent = false }
        )
    }

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Weekly AI review", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            val t = text
            when {
                loading -> Text(
                    "Looking over your week…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                t != null -> {
                    Text(t, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Last 7 days, generated ${timeFmt.format(Date(generatedAt))}. AI-generated general " +
                            "information, not medical advice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    TextButton(onClick = { request() }, enabled = enough) { Text("Refresh") }
                }
                daysLogged == null -> {}
                !enough -> Text(
                    "Log meals on at least $MIN_DAYS_FOR_WEEKLY of the last $WEEK_DAYS days to get a weekly " +
                        "review (${daysLogged ?: 0} so far).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    Text(
                        "Patterns across your last 7 days, with one idea for the week ahead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { request() }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Review my week")
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
