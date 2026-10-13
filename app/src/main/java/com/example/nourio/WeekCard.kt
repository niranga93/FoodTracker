package com.example.nourio

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nourio.data.MealDb
import com.example.nourio.data.WaterStore
import com.example.nourio.data.WeightDb
import com.example.nourio.data.totals
import com.example.nourio.energy.EnergyRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat

private data class WeekStats(
    val daysLogged: Int,
    val avgKcal: Double,
    val avgSugar: Double,
    val avgFat: Double,
    val carbsShare: Double,
    val proteinShare: Double,
    val fatShare: Double,
    val avgWaterMl: Int?,
    val avgSteps: Long?,
    val weightFirst: Double?,
    val weightLast: Double?,
    val highest: Pair<String, Double>,
    val lowest: Pair<String, Double>
)

private fun summaryText(s: WeekStats): String = buildString {
    val nf = NumberFormat.getIntegerInstance()
    appendLine("Nourio - last 7 days")
    appendLine("Days logged: ${s.daysLogged} of 7")
    appendLine(
        "Average per logged day: ${nf.format(s.avgKcal.toLong())} kcal, " +
            "sugar ${s.avgSugar.f1()} g, fat ${s.avgFat.f1()} g"
    )
    appendLine(
        "Calories from: carbs ${(s.carbsShare * 100).f0()}%, " +
            "protein ${(s.proteinShare * 100).f0()}%, fat ${(s.fatShare * 100).f0()}%"
    )
    s.avgWaterMl?.let { appendLine("Average water: ${nf.format(it)} ml per day") }
    s.avgSteps?.let { appendLine("Average steps: ${nf.format(it)}") }
    if (s.weightFirst != null && s.weightLast != null) {
        appendLine("Weight: ${s.weightFirst.f1()} to ${s.weightLast.f1()} kg")
    }
    appendLine("Highest day: ${s.highest.first} (${nf.format(s.highest.second.toLong())} kcal)")
    append("Lowest day: ${s.lowest.first} (${nf.format(s.lowest.second.toLong())} kcal)")
}

@Composable
private fun InfoLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
fun WeekCard(db: MealDb) {
    val context = LocalContext.current
    var stats by remember { mutableStateOf<WeekStats?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val offsets = (0 downTo -6).toList()
        val perDay = withContext(Dispatchers.IO) {
            val byDay = loadDays(db, offsets)
            offsets.map { off -> off to byDay[off].orEmpty().flatMap { it.items }.totals() }
        }
        val logged = perDay.filter { it.second.kcal > 0 }
        if (logged.isNotEmpty()) {
            val n = logged.size.toDouble()
            val carbsKcal = logged.sumOf { it.second.carbs } * 4.0
            val proteinKcal = logged.sumOf { it.second.protein } * 4.0
            val fatKcal = logged.sumOf { it.second.fat } * 9.0
            val macroKcal = carbsKcal + proteinKcal + fatKcal
            fun share(v: Double) = if (macroKcal > 0) v / macroKcal else 0.0

            val water = WaterStore(context)
            val waterDays = offsets.map { water.get(it) }.filter { it > 0 }
            val stepDays = EnergyRepo.activityFor(context, offsets).values.map { it.steps }.filter { it > 0 }
            val cutoff = System.currentTimeMillis() - 7L * 86_400_000L
            val weights = withContext(Dispatchers.IO) { WeightDb(context).all() }.filter { it.ts >= cutoff }

            val hi = logged.maxBy { it.second.kcal }
            val lo = logged.minBy { it.second.kcal }
            stats = WeekStats(
                daysLogged = logged.size,
                avgKcal = logged.sumOf { it.second.kcal } / n,
                avgSugar = logged.sumOf { it.second.sugar } / n,
                avgFat = logged.sumOf { it.second.fat } / n,
                carbsShare = share(carbsKcal),
                proteinShare = share(proteinKcal),
                fatShare = share(fatKcal),
                avgWaterMl = if (waterDays.isEmpty()) null else waterDays.average().toInt(),
                avgSteps = if (stepDays.isEmpty()) null else stepDays.average().toLong(),
                weightFirst = if (weights.size >= 2) weights.first().kg else null,
                weightLast = if (weights.size >= 2) weights.last().kg else null,
                highest = dayTitle(hi.first) to hi.second.kcal,
                lowest = dayTitle(lo.first) to lo.second.kcal
            )
        }
        loaded = true
    }

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val s = stats
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Last 7 days", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (s != null) {
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, summaryText(s))
                        }
                        context.startActivity(Intent.createChooser(send, "Share summary"))
                    }) { Text("Share") }
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                !loaded -> InfoLine("Calculating…")
                s == null -> InfoLine("Log meals to see your weekly summary.")
                else -> {
                    val nf = NumberFormat.getIntegerInstance()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("Days logged", "${s.daysLogged} / 7", Modifier.weight(1f))
                        StatTile("Avg calories", nf.format(s.avgKcal.toLong()), Modifier.weight(1f))
                        StatTile(
                            "Avg water",
                            s.avgWaterMl?.let { "${nf.format(it)} ml" } ?: "—",
                            Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    InfoLine("Per logged day: sugar ${s.avgSugar.f1()} g  ·  fat ${s.avgFat.f1()} g")
                    InfoLine(
                        "Calories from carbs ${(s.carbsShare * 100).f0()}%  ·  " +
                            "protein ${(s.proteinShare * 100).f0()}%  ·  fat ${(s.fatShare * 100).f0()}%"
                    )
                    s.avgSteps?.let { InfoLine("Average steps: ${nf.format(it)}") }
                    if (s.weightFirst != null && s.weightLast != null) {
                        InfoLine("Weight: ${s.weightFirst.f1()} to ${s.weightLast.f1()} kg")
                    }
                    InfoLine("Highest: ${s.highest.first} (${nf.format(s.highest.second.toLong())} kcal)")
                    InfoLine("Lowest: ${s.lowest.first} (${nf.format(s.lowest.second.toLong())} kcal)")
                }
            }
        }
    }
}
