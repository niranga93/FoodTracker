package com.example.foodtracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.foodtracker.data.ProfileStore
import com.example.foodtracker.plan.BudgetModel
import com.example.foodtracker.plan.PlanStore
import kotlin.math.abs

@Composable
fun TargetCard() {
    val context = LocalContext.current
    val plan = remember { PlanStore(context) }
    var target by remember {
        mutableStateOf(
            plan.targetKg.let { if (it <= 0) "" else if (it == it.toLong().toDouble()) it.f0() else it.f1() }
        )
    }
    var pace by remember { mutableStateOf(plan.paceKgPerWeek) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    fun save() {
        val p = ProfileStore(context).load()
        val t = if (target.isBlank()) 0.0 else target.replace(',', '.').toDoubleOrNull() ?: -1.0
        error = when {
            t < 0 || (t != 0.0 && t !in 30.0..300.0) -> "Enter 30 to 300 kg, or leave empty."
            t > 0 && (p.heightCm <= 0 || p.weightKg <= 0) -> "Add your height and weight first."
            t > 0 && !BudgetModel.targetBmiOk(t, p.heightCm) -> "That target is too low (BMI under 18.5)."
            else -> null
        }
        if (error == null) {
            plan.targetKg = t
            plan.paceKgPerWeek = pace
            saved = true
        }
    }

    WidgetCard("Target weight") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = target,
                onValueChange = { target = it; error = null; saved = false },
                label = { Text("Target (optional)") },
                suffix = { Text("kg") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = pace < 0.4, onClick = { pace = 0.25; saved = false },
                    label = { Text("Gentle 0.25 kg/wk") })
                FilterChip(selected = pace >= 0.4, onClick = { pace = 0.5; saved = false },
                    label = { Text("Steady 0.5 kg/wk") })
            }

            val cur = remember { ProfileStore(context).load().weightKg }
            val t = target.replace(',', '.').toDoubleOrNull() ?: 0.0
            if (t > 0 && cur > 0) {
                val delta = t - cur
                val effective = minOf(pace, BudgetModel.MAX_PACE_KG_WEEK, cur * 0.01)
                Text(
                    if (abs(delta) < 0.5) "Maintains your weight."
                    else "About ${(abs(delta) / effective).toInt()} weeks at this pace.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text(if (saved) "Saved" else "Save")
            }
            Text(
                "Estimates, not medical advice. Ask a doctor first if you have a health condition or a " +
                    "history of disordered eating.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
