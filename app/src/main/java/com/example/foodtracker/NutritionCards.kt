package com.example.foodtracker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.foodtracker.data.Totals
import com.example.foodtracker.data.WaterStore
import java.text.NumberFormat

private val CarbsColor = Color(0xFFFFB300)
private val ProteinColor = Color(0xFF43A047)
private val FatColor = Color(0xFF1E88E5)

private fun nf(i: Int): String = NumberFormat.getIntegerInstance().format(i)

// ---------------------------------------------------------------- macro ring

@Composable
private fun MacroRing(parts: List<Pair<Double, Color>>, centerText: String, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val total = parts.sumOf { it.first }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 18.dp.toPx()
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arc, style = Stroke(width = stroke)
            )
            if (total > 0) {
                var start = -90f
                parts.forEach { (v, c) ->
                    val sweep = (v / total * 360.0).toFloat()
                    drawArc(
                        color = c, startAngle = start, sweepAngle = sweep, useCenter = false,
                        topLeft = topLeft, size = arc, style = Stroke(width = stroke)
                    )
                    start += sweep
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("kcal", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MacroLegend(label: String, share: Double, grams: Double, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            "${(share * 100).f0()}%  ·  ${grams.f1()} g",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/** Share of calories from carbs, protein and fat (4, 4 and 9 kcal per gram). */
@Composable
fun MacroCard(t: Totals) {
    val carbsKcal = t.carbs * 4.0
    val proteinKcal = t.protein * 4.0
    val fatKcal = t.fat * 9.0
    val total = carbsKcal + proteinKcal + fatKcal
    fun share(v: Double) = if (total > 0) v / total else 0.0

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Macros", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MacroRing(
                    parts = listOf(carbsKcal to CarbsColor, proteinKcal to ProteinColor, fatKcal to FatColor),
                    centerText = total.f0(),
                    modifier = Modifier.size(120.dp)
                )
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MacroLegend("Carbs", share(carbsKcal), t.carbs, CarbsColor)
                    MacroLegend("Protein", share(proteinKcal), t.protein, ProteinColor)
                    MacroLegend("Fat", share(fatKcal), t.fat, FatColor)
                }
            }
            Text(
                if (total > 0) "Percent of calories from carbs, protein and fat."
                else "Log a meal to see your macro split.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

// ---------------------------------------------------------------- water

@Composable
fun WaterCard(dayOffset: Int) {
    val context = LocalContext.current
    val store = remember { WaterStore(context) }
    var ml by remember(dayOffset) { mutableStateOf(store.get(dayOffset)) }
    var goal by remember { mutableStateOf(store.goalMl) }
    var editGoal by remember { mutableStateOf(false) }

    if (editGoal) {
        var text by remember { mutableStateOf(goal.toString()) }
        AlertDialog(
            onDismissRequest = { editGoal = false },
            title = { Text("Daily water goal") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Goal") },
                    suffix = { Text("ml") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = text.trim().toIntOrNull()
                    if (v != null && v in 250..10000) {
                        store.goalMl = v
                        goal = v
                        editGoal = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editGoal = false }) { Text("Cancel") } }
        )
    }

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Water", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = { editGoal = true }) { Text("Goal: ${nf(goal)} ml") }
            }
            Text(
                "${nf(ml)} ml",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            SlimBar((ml.toFloat() / goal.coerceAtLeast(1)), false)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ml = store.add(dayOffset, -250) }, modifier = Modifier.weight(1f)) {
                    Text("−250")
                }
                Button(onClick = { ml = store.add(dayOffset, 250) }, modifier = Modifier.weight(1f)) {
                    Text("+250")
                }
                Button(onClick = { ml = store.add(dayOffset, 500) }, modifier = Modifier.weight(1f)) {
                    Text("+500")
                }
            }
        }
    }
}
