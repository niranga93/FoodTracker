package com.example.foodtracker

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.foodtracker.data.ProfileStore
import com.example.foodtracker.data.WeightDb
import com.example.foodtracker.data.WeightEntry
import com.example.foodtracker.energy.EnergyModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val shortDate = SimpleDateFormat("d MMM", Locale.getDefault())
private fun signedKg(d: Double) = "%+.1f".format(d)

// Keep the profile weight equal to the most recent weigh-in so calorie estimates stay current
private fun syncProfileWeight(context: Context, entries: List<WeightEntry>) {
    val latest = entries.lastOrNull() ?: return
    val store = ProfileStore(context)
    store.save(store.load().copy(weightKg = latest.kg))
}

@Composable
fun WeightCard() {
    val context = LocalContext.current
    val weightDb = remember { WeightDb(context) }
    var entries by remember { mutableStateOf(weightDb.all()) }
    var showAdd by remember { mutableStateOf(false) }

    if (showAdd) {
        var kgText by remember { mutableStateOf(entries.lastOrNull()?.kg?.f1() ?: "") }
        var offset by remember { mutableStateOf(0) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Log weight") },
            text = {
                Column {
                    OutlinedTextField(
                        value = kgText,
                        onValueChange = { kgText = it; error = null },
                        label = { Text("Weight") },
                        suffix = { Text("kg") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    DateNavigator(offset) { offset = it }
                    error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val kg = kgText.replace(',', '.').toDoubleOrNull()
                    if (kg == null || kg !in 20.0..400.0) {
                        error = "Enter a weight between 20 and 400 kg."
                    } else {
                        weightDb.add(timestampForOffset(offset), kg)
                        entries = weightDb.all()
                        syncProfileWeight(context, entries)
                        showAdd = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } }
        )
    }

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Weight", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Button(onClick = { showAdd = true }) { Text("Log weight") }
            }
            Spacer(Modifier.height(8.dp))
            if (entries.isEmpty()) {
                Text(
                    "Log your weight a few times a week. After about two weeks of weigh-ins and meals, " +
                        "the forecast learns how your body actually responds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val window = entries.takeLast(30)
                val latest = entries.last()
                val allTrend = EnergyModel.trend(entries.map { it.kg })
                val windowTrend = allTrend.takeLast(window.size)
                Text("${latest.kg.f1()} kg", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Trend ${allTrend.last().f1()} kg" +
                        if (window.size > 1)
                            "  ·  ${signedKg(latest.kg - window.first().kg)} kg since ${shortDate.format(Date(window.first().ts))}"
                        else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                WeightChart(window, windowTrend)
                Spacer(Modifier.height(8.dp))
                Text("Recent weigh-ins", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                entries.takeLast(5).reversed().forEach { e ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            shortDate.format(Date(e.ts)),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text("${e.kg.f1()} kg", fontWeight = FontWeight.Medium)
                        IconButton(onClick = {
                            weightDb.delete(e.id)
                            entries = weightDb.all()
                            syncProfileWeight(context, entries)
                        }) { Icon(Icons.Filled.Delete, contentDescription = "Delete weigh-in") }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeightChart(points: List<WeightEntry>, trend: List<Double>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.secondary
    val gridColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        if (points.size < 2 || trend.size != points.size) return@Canvas
        val minT = points.first().ts.toDouble()
        val spanT = (points.last().ts - points.first().ts).toDouble().coerceAtLeast(1.0)
        val minV = minOf(points.minOf { it.kg }, trend.min()) - 0.5
        val maxV = maxOf(points.maxOf { it.kg }, trend.max()) + 0.5
        val w = this.size.width
        val h = this.size.height
        fun px(t: Long) = (((t - minT) / spanT) * w).toFloat()
        fun py(v: Double) = (h - ((v - minV) / (maxV - minV)) * h).toFloat()

        drawLine(gridColor, Offset(0f, 0f), Offset(w, 0f), strokeWidth = 1.dp.toPx())
        drawLine(gridColor, Offset(0f, h), Offset(w, h), strokeWidth = 1.dp.toPx())

        val path = Path()
        points.forEachIndexed { i, e ->
            val x = px(e.ts)
            val y = py(trend[i])
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 3.dp.toPx()))
        points.forEach { e ->
            drawCircle(dotColor, radius = 3.5.dp.toPx(), center = Offset(px(e.ts), py(e.kg)))
        }
    }
}
