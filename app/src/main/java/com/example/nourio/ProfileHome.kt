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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.ceil

// Accents for the section icons, alongside the theme's mint, ember and sky
private val Pink = Color(0xFFFF6B9A)
private val Amber = Color(0xFFFFB547)
private val Violet = Color(0xFF9C8CFF)

private data class LogStats(val streak: Int, val meals30: Int)

/** Consecutive days with a meal logged (today counts once something is logged), and meals in 30 days. */
private fun logStats(db: MealDb): LogStats {
    // One query for the last year, then count days locally
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    val since = today.minusDays(365).atStartOfDay(zone).toInstant().toEpochMilli()
    val meals = db.between(since, today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
    val days = meals.groupingBy { java.time.Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }.eachCount()
    var streak = 0
    var day = if (today in days) today else today.minusDays(1)
    while (day in days) { streak++; day = day.minusDays(1) }
    val meals30 = days.filterKeys { it > today.minusDays(30) }.values.sum()
    return LogStats(streak, meals30)
}

@Composable
fun ProfileHub(db: MealDb, onOpenPage: (String) -> Unit, inner: PaddingValues) {
    val context = LocalContext.current
    val profile = remember { ProfileStore(context).load() }
    val goals = remember { GoalStore(context).load() }
    val plan = remember { PlanStore(context) }
    val diet = remember { DietStore(context).load() }
    val weights = remember { WeightDb(context).all() }
    val health = remember { HealthPrefs(context) }
    val driveEmail = remember { BackupPrefs(context).email }
    val keyCount = remember { GeminiKeys.count() }
    val budget by rememberBudget(db, GoalStore(context), 0)
    var stats by remember { mutableStateOf<LogStats?>(null) }
    LaunchedEffect(Unit) { stats = withContext(Dispatchers.IO) { logStats(db) } }

    val nf = remember { NumberFormat.getIntegerInstance() }
    val weightNow = weights.lastOrNull()?.kg ?: profile.weightKg
    val dailyGoal = budget?.baseKcal?.takeIf { it > 0 } ?: goals.kcal

    Column(
        Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        HeroCard(profile.name, profileLine(profile), weightNow, profile.bmi, dailyGoal, nf) { onOpenPage("me") }

        if (plan.targetKg > 0 && weights.isNotEmpty()) {
            JourneyCard(weights.first().kg, weightNow, plan.targetKg, plan.paceKgPerWeek) { onOpenPage("goals") }
        }

        stats?.let { s ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniTile(
                    Icons.Filled.ThumbUp, MaterialTheme.colorScheme.secondary,
                    if (s.streak == 1) "1 day" else "${s.streak} days", "logging streak", Modifier.weight(1f)
                )
                MiniTile(
                    Icons.Filled.Star, MaterialTheme.colorScheme.primary,
                    nf.format(s.meals30), "meals in 30 days", Modifier.weight(1f)
                )
            }
        }

        Section("Health") {
            IconRow(
                Icons.Filled.Search, Violet, "Insights & patterns",
                "How food, sleep, activity and heart data connect"
            ) { onOpenPage("insights") }
            Divider()
            IconRow(
                Icons.Filled.Favorite, Pink, "Sleep, heart rate and vitals",
                if (health.enabled && health.readExtras) "Sleep, heart rate, VO2 max, oxygen"
                else "Connect Health Connect to see them"
            ) { onOpenPage("body:0") }
            Divider()
            IconRow(
                Icons.Filled.DateRange, MaterialTheme.colorScheme.tertiary, "Weigh-ins",
                weights.lastOrNull()?.let { "Latest ${it.kg.f1()} kg  ·  ${weights.size} entries" } ?: "No entries yet"
            ) { onOpenPage("weight") }
        }

        Section("Your plan") {
            IconRow(
                Icons.Filled.Person, MaterialTheme.colorScheme.primary, "Personal details",
                if (profile.heightCm > 0 && profile.weightKg > 0)
                    "${profile.heightCm.f0()} cm  ·  ${profile.weightKg.f1()} kg" else "Add height, weight and age"
            ) { onOpenPage("me") }
            Divider()
            IconRow(
                Icons.Filled.Star, Amber, "Goals & target",
                buildString {
                    append(if (dailyGoal > 0) "${nf.format(dailyGoal.toLong())} kcal a day" else "No goal yet")
                    if (plan.targetKg > 0) append("  ·  target ${plan.targetKg.f1()} kg")
                }
            ) { onOpenPage("goals") }
            Divider()
            IconRow(
                Icons.Filled.Warning, MaterialTheme.colorScheme.secondary, "Diet & allergies",
                (diet.diets.size + diet.allergens.size + diet.other.size).let {
                    if (it == 0) "None set" else "$it selected"
                }
            ) { onOpenPage("diet") }
        }

        Section("App") {
            IconRow(
                Icons.Filled.Share, MaterialTheme.colorScheme.tertiary, "Connections",
                listOf(
                    if (keyCount > 0) "Gemini ${if (keyCount == 1) "1 key" else "$keyCount keys"}" else "No Gemini key",
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

/** Gradient header: avatar, name, and the three numbers people check most. */
@Composable
private fun HeroCard(
    name: String, line: String, weight: Double, bmi: Double?, dailyGoal: Double,
    nf: NumberFormat, onEdit: () -> Unit
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
                HeroStat("Weight", if (weight > 0) "${weight.f1()} kg" else "—", null, Modifier.weight(1f))
                val (label, good) = when {
                    bmi == null -> "add height" to true
                    bmi < 18.5 -> "below range" to false
                    bmi < 25 -> "healthy range" to true
                    else -> "above range" to false
                }
                HeroStat("BMI", bmi?.f1() ?: "—", label, Modifier.weight(1f), if (good) cs.primary else Amber)
                HeroStat("Daily goal", if (dailyGoal > 0) nf.format(dailyGoal.toLong()) else "—", "kcal", Modifier.weight(1f))
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

/** Start → now → target, with how far along you are. */
@Composable
private fun JourneyCard(start: Double, now: Double, target: Double, pace: Double, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val total = start - target
    val progress = if (abs(total) < 0.05) 1f else ((start - now) / total).toFloat().coerceIn(0f, 1f)
    val toGo = abs(now - target)
    val weeks = if (pace > 0 && toGo >= 0.1) ceil(toGo / pace).toInt() else null
    AppCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Journey to ${target.f1()} kg",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Pill("${(progress * 100).toInt()}%")
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(cs.surfaceVariant)) {
                Box(
                    Modifier.fillMaxWidth(progress.coerceAtLeast(0.03f)).fillMaxHeight()
                        .clip(RoundedCornerShape(7.dp))
                        .background(Brush.horizontalGradient(listOf(cs.tertiary, cs.primary)))
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                JourneyPoint("Start", start, Alignment.Start, Modifier.weight(1f))
                JourneyPoint("Now", now, Alignment.CenterHorizontally, Modifier.weight(1f))
                JourneyPoint("Target", target, Alignment.End, Modifier.weight(1f))
            }
            // Moving away from the target since the first weigh-in: say so kindly instead of a bare 0%
            val away = abs(now - target) > abs(start - target) + 0.05
            Text(
                when {
                    toGo < 0.1 -> "You've reached your target. Nice work."
                    away -> "${abs(now - start).f1()} kg ${if (now > start) "up" else "down"} since your first weigh-in. " +
                        "Day-to-day changes are normal; the trend is what counts. ${toGo.f1()} kg to go."
                    else -> "${toGo.f1()} kg to go" + (weeks?.let { "  ·  about $it ${if (it == 1) "week" else "weeks"} at your pace" } ?: "")
                },
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

@Composable
private fun JourneyPoint(label: String, kg: Double, align: Alignment.Horizontal, modifier: Modifier) {
    Column(modifier, horizontalAlignment = align) {
        Text("${kg.f1()} kg", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MiniTile(icon: ImageVector, tint: Color, value: String, label: String, modifier: Modifier) {
    AppCard(modifier) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tint)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
