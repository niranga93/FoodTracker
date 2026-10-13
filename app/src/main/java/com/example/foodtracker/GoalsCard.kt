package com.example.foodtracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.foodtracker.backup.BackupService
import com.example.foodtracker.data.GoalStore
import com.example.foodtracker.data.Goals

@Composable
private fun GoalsField(label: String, unit: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        suffix = { Text(unit) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun GoalsCard(goalStore: GoalStore) {
    val context = LocalContext.current
    val current = remember { goalStore.load() }
    fun show(d: Double) = if (d <= 0) "" else if (d == d.toLong().toDouble()) d.f0() else d.f1()
    fun parse(s: String) = s.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 } ?: 0.0

    var kcal by remember { mutableStateOf(show(current.kcal)) }
    var sugar by remember { mutableStateOf(show(current.sugar)) }
    var fat by remember { mutableStateOf(show(current.fat)) }
    var saved by remember { mutableStateOf(false) }

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Daily goals", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "The most you want per day. Leave a field empty for no goal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            GoalsField("Calories", "kcal", kcal) { kcal = it; saved = false }
            GoalsField("Sugar", "g", sugar) { sugar = it; saved = false }
            GoalsField("Fat", "g", fat) { fat = it; saved = false }
            Button(
                onClick = {
                    goalStore.save(Goals(parse(kcal), parse(sugar), parse(fat)))
                    BackupService.autoBackup(context)
                    saved = true
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) { Text(if (saved) "Saved" else "Save goals") }
        }
    }
}
