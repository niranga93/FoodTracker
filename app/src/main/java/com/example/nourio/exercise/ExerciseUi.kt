package com.example.nourio.exercise

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nourio.WidgetCard
import com.example.nourio.energy.Body
import com.example.nourio.energy.DayEnergy
import com.example.nourio.health.DayBody
import java.time.LocalTime

/**
 * Today's exercise suggestion. [week] is newest first with today at index 0, like the Home and Burned
 * screens build it; [bodyDays] is the same, from Health Connect, or null without it.
 */
@Composable
fun ExerciseCard(day: DayEnergy?, week: List<DayEnergy>, body: Body?, stepGoal: Long, bodyDays: List<DayBody>?) {
    WidgetCard("What to do today") {
        if (day == null || body == null) {
            Text(
                "Add your height, weight and year of birth in Profile and get exercise suggestions that balance what you eat.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@WidgetCard
        }
        val rec = remember(day, week, bodyDays) {
            ExerciseCoach.recommend(
                CoachInput(
                    weightKg = body.weightKg,
                    age = body.age,
                    eaten = day.eaten,
                    burned = day.burned,
                    weekBalances = week.filter { it.offset < 0 && it.logComplete }.map { it.balance },
                    steps = day.steps,
                    stepGoal = stepGoal,
                    workouts = bodyDays.orEmpty().flatMapIndexed { i, d ->
                        d.workouts.map { PastWorkout(i, it.name, it.minutes) }
                    },
                    sleepLastNightH = bodyDays?.firstOrNull()?.sleepMinutes?.let { it / 60.0 },
                    hour = LocalTime.now().hour,
                )
            )
        }
        val cs = MaterialTheme.colorScheme
        Text(rec.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = if (rec.targetKcal > 0) cs.secondary else cs.primary, modifier = Modifier.padding(top = 4.dp))
        Text(rec.reason, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

        Column(Modifier.padding(top = 10.dp)) {
            rec.options.forEachIndexed { i, o ->
                OptionRow(o, best = i == 0)
                if (i < rec.options.lastIndex) HorizontalDivider()
            }
        }
        rec.note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun OptionRow(o: ExerciseOption, best: Boolean) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                .background(if (best) cs.secondaryContainer else cs.surfaceVariant),
            contentAlignment = Alignment.Center
        ) { Text(o.activity.emoji, fontSize = 22.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.activity.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                Text("${o.minutes} min", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                    color = if (best) cs.secondary else cs.onSurface)
            }
            Text("about ${o.kcal} kcal  ·  ${o.why}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            if (open) Text(o.activity.how, style = MaterialTheme.typography.bodySmall, color = cs.primary)
        }
    }
}
