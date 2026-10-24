package com.example.nourio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nourio.ai.GeminiKeys
import com.example.nourio.backup.BackupPrefs
import com.example.nourio.data.GoalStore
import com.example.nourio.data.MealDb
import com.example.nourio.data.ProfileStore
import com.example.nourio.data.WeightDb
import com.example.nourio.diet.DietStore
import com.example.nourio.health.HealthPrefs
import com.example.nourio.plan.PlanStore
import java.text.NumberFormat
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.ceil

// Accents for the section icons, alongside the theme's mint, ember and sky
private val Pink = Color(0xFFFF6B9A)
private val Amber = Color(0xFFFFB547)
private val Violet = Color(0xFF9C8CFF)

@Composable
fun ProfileHub(db: MealDb, onOpenPage: (String) -> Unit, inner: PaddingValues) {
    val context = LocalContext.current
    val refresh = LocalRefresh.current            // re-read on pull-to-refresh, without clearing the page
    val profile = remember(refresh) { ProfileStore(context).load() }
    val goals = remember(refresh) { GoalStore(context).load() }
    val plan = remember { PlanStore(context) }
    val diet = remember(refresh) { DietStore(context).load() }
    val weights = remember(refresh) { WeightDb(context).all() }
    val health = remember { HealthPrefs(context) }
    val driveEmail = remember(refresh) { BackupPrefs(context).email }
    val keyCount = remember(refresh) { GeminiKeys.count() }
    val budget by rememberBudget(db, GoalStore(context), 0)

    val nf = remember { NumberFormat.getIntegerInstance() }
    val weightNow = weights.lastOrNull()?.kg ?: profile.weightKg
    val dailyGoal = budget?.baseKcal?.takeIf { it > 0 } ?: goals.kcal

    Column(
        Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Each number appears once on this page: weight in the journey (or Weigh-ins), the calorie goal
        // and BMI in the header, the streak as the header's third tile
        var streak by remember { mutableStateOf<com.example.nourio.achievements.Achievement?>(null) }
        LaunchedEffect(LocalRefresh.current) {
            streak = runCatching { com.example.nourio.achievements.Achievements.evaluate(context, db) }.getOrNull()
            // Celebrate badges earned since the last look
            streak?.newlyUnlocked?.takeIf { it.isNotEmpty() }?.let { b ->
                android.widget.Toast.makeText(
                    context, if (b.size == 1) "Badge unlocked: ${b[0].emoji} ${b[0].title}" else "${b.size} badges unlocked!",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        HeroCard(profile.name, profileLine(profile), profile.bmi, dailyGoal, streak, nf,
            onEdit = { onOpenPage("me") }, onStreak = { onOpenPage("badges") })

        val journey = plan.targetKg > 0 && weights.isNotEmpty()
        if (journey) {
            // The estimated line needs a full profile (for calories burned) and reads months of meals: load it off screen
            val (body, missing) = remember(refresh) { com.example.nourio.energy.EnergyRepo.body(profile) }
            // (finished loading, series)
            val journeyData by produceState<Pair<Boolean, com.example.nourio.energy.JourneySeries?>>(false to null, refresh, body) {
                value = true to body?.let { b -> runCatching { com.example.nourio.energy.JourneyModel.load(context, db, b) }.getOrNull() }
            }
            JourneyCard(
                weights.first().kg, weightNow, plan.targetKg, plan.paceKgPerWeek,
                series = journeyData.second, loading = body != null && !journeyData.first, missing = missing,
            ) { onOpenPage("goals") }
        }

        Section("Health") {
            IconRow(
                Icons.Filled.Search, Violet, "Insights & patterns",
                "How food, sleep, activity and heart data connect"
            ) { onOpenPage("insights") }
            Divider()
            IconRow(
                Icons.Filled.Refresh, MaterialTheme.colorScheme.secondary, "Fasting",
                "Your fasts, eating window and what they do for you"
            ) { onOpenPage("fasting") }
            Divider()
            IconRow(
                Icons.Filled.Favorite, Pink, "Sleep, heart rate and vitals",
                if (health.enabled && health.readExtras) "Sleep, heart rate, workouts, distance"
                else "Connect Health Connect to see them"
            ) { onOpenPage("body:0") }
            Divider()
            IconRow(
                Icons.Filled.DateRange, MaterialTheme.colorScheme.tertiary, "Weigh-ins",
                weights.lastOrNull()?.let { last ->
                    // The journey above already shows the weight, so say how often instead
                    if (journey) "${weights.size} entries  ·  last on ${java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(last.ts))}"
                    else "Latest ${last.kg.f1()} kg  ·  ${weights.size} entries"
                } ?: "No entries yet"
            ) { onOpenPage("weight") }
        }

        Section("Your plan") {
            // Personal details are the pencil on the header; the goals row leads with the target weight
            IconRow(
                Icons.Filled.Star, Amber, "Goals & target",
                listOfNotNull(
                    plan.targetKg.takeIf { it > 0 }?.let {
                        "target ${it.f1()} kg at ${"%.2f".format(plan.paceKgPerWeek).trimEnd('0').trimEnd('.')} kg a week"
                    },
                    goals.protein.takeIf { it > 0 }?.let { "protein ${it.f0()} g" },
                    goals.fiber.takeIf { it > 0 }?.let { "fiber ${it.f0()} g" },
                    goals.addedSugar.takeIf { it > 0 }?.let { "added sugar ≤ ${it.f0()} g" },
                ).joinToString("  ·  ").replaceFirstChar { it.uppercase() }.ifBlank { "Calories, nutrients and target weight" }
            ) { onOpenPage("goals") }
            Divider()
            IconRow(
                Icons.Filled.Warning, MaterialTheme.colorScheme.secondary, "Diet & allergies",
                (diet.diets.size + diet.allergens.size + diet.other.size).let {
                    when {
                        it > 0 -> "$it selected"
                        diet.noDiet && diet.noAllergies -> "No diet or allergies"
                        diet.noDiet -> "No dietary requirements"
                        diet.noAllergies -> "No allergies"
                        else -> "None set"
                    }
                }
            ) { onOpenPage("diet") }
        }

        Section("App") {
            IconRow(
                Icons.Filled.Share, MaterialTheme.colorScheme.tertiary, "Connections",
                listOf(
                    // The chosen AI service, and whether it's ready to use
                    com.example.nourio.ai.AiSettings.provider.let { p ->
                        when {
                            p == com.example.nourio.ai.Provider.GEMINI && keyCount > 0 -> "Gemini ${if (keyCount == 1) "1 key" else "$keyCount keys"}"
                            com.example.nourio.ai.AiSettings.isReady() -> "${p.label} · ${com.example.nourio.ai.AiSettings.model(p)}"
                            else -> "No ${p.label} key"
                        }
                    },
                    if (health.enabled) "Health Connect" else null,
                    if (driveEmail != null) "Drive" else null
                ).filterNotNull().joinToString("  ·  ")
            ) { onOpenPage("connections") }
            Divider()
            IconRow(Icons.Filled.Lock, Violet, "Data & privacy", "Export your data, privacy policy") { onOpenPage("data") }
            Divider()
            IconRow(
                Icons.Filled.Settings, MaterialTheme.colorScheme.onSurfaceVariant, "Settings", "Choose what each page shows"
            ) { onOpenPage("settings") }
            Divider()
            IconRow(
                Icons.Filled.ThumbUp, MaterialTheme.colorScheme.primary, "Setup guide",
                "Walk through setup and permissions again"
            ) { Onboarding.replay() }
            Divider()
            IconRow(
                Icons.Filled.Info, MaterialTheme.colorScheme.onSurfaceVariant, "About",
                "Version ${remember { appVersion(context).name }}"
            ) { onOpenPage("about") }
        }

        Text(
            "Nourio  ·  Nourish. Track. Balance.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
        )
    }
}

private fun profileLine(p: com.example.nourio.data.Profile): String {
    val age = if (p.birthYear > 0) Calendar.getInstance().get(Calendar.YEAR) - p.birthYear else null
    return listOfNotNull(
        age?.let { "$it years" },
        p.sex.takeIf { it.isNotBlank() && it != "Other" },
        p.heightCm.takeIf { it > 0 }?.let { "${it.f0()} cm" }
    ).joinToString("  ·  ").ifBlank { "Add your details for accurate estimates" }
}

/** Gradient header: avatar, name, and three numbers: BMI, the daily calorie goal and the logging streak. */
@Composable
private fun HeroCard(
    name: String, line: String, bmi: Double?, dailyGoal: Double,
    streak: com.example.nourio.achievements.Achievement?, nf: NumberFormat, onEdit: () -> Unit, onStreak: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val gradient = Brush.linearGradient(listOf(cs.primary.copy(alpha = 0.32f), cs.tertiary.copy(alpha = 0.22f), cardContainer()))
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = cardContainer()),
        border = BorderStroke(1.dp, cardOutline()),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.background(gradient).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(68.dp).clip(CircleShape)
                        .border(3.dp, cs.primary, CircleShape)
                        .padding(5.dp).clip(CircleShape)
                        .background(cs.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        name.trim().take(1).uppercase().ifBlank { "N" },
                        fontSize = 26.sp, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    val shown = name.trim().ifBlank { "Welcome" }
                    Text(
                        shown,
                        style = if (shown.length <= 14) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(line, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit personal details", tint = cs.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val (label, good) = when {
                    bmi == null -> "add height" to true
                    bmi < 18.5 -> "below range" to false
                    bmi < 25 -> "healthy range" to true
                    else -> "above range" to false
                }
                HeroStat("BMI", bmi?.f1() ?: "—", label, Modifier.weight(1f), if (good) cs.primary else Amber)
                HeroStat("Daily goal", if (dailyGoal > 0) nf.format(dailyGoal.toLong()) else "—", "kcal", Modifier.weight(1f))
                val s = streak?.streaks?.current
                HeroStat(
                    "Streak", s?.let { "🔥 $it" } ?: "—",
                    streak?.let { "${it.unlockedCount}/${it.badges.size} badges" } ?: "days",
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(onClick = onStreak)
                )
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, note: String?, modifier: Modifier, noteColor: Color? = null) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(cardContainer().copy(alpha = 0.7f))
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        note?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = noteColor ?: MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector, tint: Color) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
        )
        AppCard(Modifier.fillMaxWidth()) { Column(content = content) }
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 70.dp, end = 16.dp))

@Composable
private fun IconRow(icon: ImageVector, tint: Color, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(icon, tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
