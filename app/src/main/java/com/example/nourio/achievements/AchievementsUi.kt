package com.example.nourio.achievements

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nourio.AppCard
import com.example.nourio.LocalOpenPage
import com.example.nourio.SlimBar
import com.example.nourio.WidgetCard
import com.example.nourio.data.MealDb
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private fun days(n: Int) = if (n == 1) "1 day" else "$n days"

/** Loads the streaks and badges, and announces any badge earned since the last look. */
@Composable
private fun rememberAchievement(db: MealDb): Achievement? {
    val context = LocalContext.current
    var a by remember { mutableStateOf<Achievement?>(null) }
    LaunchedEffect(Unit) {
        val result = runCatching { Achievements.evaluate(context, db) }.getOrNull() ?: return@LaunchedEffect
        a = result
        result.newlyUnlocked.takeIf { it.isNotEmpty() }?.let { b ->
            val text = if (b.size == 1) "Badge unlocked: ${b[0].emoji} ${b[0].title}" else "${b.size} badges unlocked!"
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }
    return a
}

/** Home: the logging streak and the next badge. Opens the full page. */
@Composable
fun StreakCard(db: MealDb) {
    val a = rememberAchievement(db) ?: return
    val openPage = LocalOpenPage.current
    val s = a.streaks
    AppCard(Modifier.fillMaxWidth().clickable { openPage("badges") }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (s.current > 0) "🔥" else "🌱", fontSize = 30.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (s.current > 0) "${s.current}-day logging streak!"
                    else "Log a meal to start a streak",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
                )
                val next = a.next
                Text(
                    buildString {
                        if (s.best > s.current) append("Best ${days(s.best)}  ·  ")
                        append("${a.unlockedCount} of ${a.badges.size} badges")
                        if (next != null) append("  ·  next: ${next.badge.title}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** The "badges" page: streak numbers, then every badge with its progress. */
@Composable
fun BadgesPage(db: MealDb) {
    val a = rememberAchievement(db)
    if (a == null) {
        Text("Counting your days…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val s = a.streaks
    val nf = remember { NumberFormat.getIntegerInstance() }
    val dateFmt = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }

    WidgetCard("Streaks") {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Stat("🔥 ${days(s.current)}", "logging now", Modifier.weight(1f))
            Stat(days(s.best), "best ever", Modifier.weight(1f))
            Stat(nf.format(s.daysLogged), "days logged", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            Stat("💧 ${days(s.waterCurrent)}", "at water goal", Modifier.weight(1f))
            Stat(days(s.waterBest), "best water run", Modifier.weight(1f))
            Stat(nf.format(s.meals), "meals logged", Modifier.weight(1f))
        }
    }

    WidgetCard("Badges", trailing = {
        Text("${a.unlockedCount} of ${a.badges.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }) {
        Column(Modifier.padding(top = 4.dp)) {
            a.badges.forEachIndexed { i, b ->
                BadgeRow(b, nf, dateFmt)
                if (i < a.badges.lastIndex) HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BadgeRow(b: BadgeState, nf: NumberFormat, dateFmt: DateTimeFormatter) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .background(if (b.unlocked) cs.primaryContainer else cs.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(b.badge.emoji, fontSize = 24.sp, modifier = Modifier.alpha(if (b.unlocked) 1f else 0.35f))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                b.badge.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                color = if (b.unlocked) cs.onSurface else cs.onSurfaceVariant
            )
            Text(b.badge.text, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            if (b.unlocked) {
                Text(
                    "Unlocked ${b.unlockedOn!!.format(dateFmt)}",
                    style = MaterialTheme.typography.labelSmall, color = cs.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else if (b.badge.goal > 1) {
                SlimBar(b.fraction, over = false, modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(4.dp)))
                Text(
                    "${nf.format(b.progress.coerceAtMost(b.badge.goal))} of ${nf.format(b.badge.goal)} ${b.badge.unit}",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
