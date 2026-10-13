package com.example.foodtracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    var useTarget by remember { mutableStateOf(plan.useTargetGoal) }
    var carry by remember { mutableStateOf(plan.carryOver) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    fun save() {
        val p = ProfileStore(context).load()
        val t = if (target.isBlank()) 0.0 else target.replace(',', '.').toDoubleOrNull() ?: -1.0
        error = when {
            t < 0 || (t != 0.0 && t !in 30.0..300.0) ->
                "Enter a target weight between 30 and 300 kg, or leave it empty."
            t > 0 && (p.heightCm <= 0 || p.weightKg <= 0) ->
                "Add your height and weight in Personal details first."
            t > 0 && !BudgetModel.targetBmiOk(t, p.heightCm) ->
                "That target is below a healthy BMI range (18.5). Please choose a higher target, or talk to a doctor."
            else -> null
        }
        if (error == null) {
            plan.targetKg = t
            plan.paceKgPerWeek = pace
            plan.useTargetGoal = useTarget
            plan.carryOver = carry
            saved = true
        }
    }

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Target weight & budget", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = target,
                onValueChange = { target = it; error = null; saved = false },
                label = { Text("Target weight (optional)") },
                suffix = { Text("kg") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Text("Pace", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = pace < 0.4,
                    onClick = { pace = 0.25; saved = false },
                    label = { Text("Gentle · 0.25 kg/week") }
                )
                FilterChip(
                    selected = pace >= 0.4,
                    onClick = { pace = 0.5; saved = false },
                    label = { Text("Steady · 0.5 kg/week") }
                )
            }

            // Rough timeline at the chosen pace
            val cur = remember { ProfileStore(context).load().weightKg }
            val t = target.replace(',', '.').toDoubleOrNull() ?: 0.0
            if (t > 0 && cur > 0) {
                val delta = t - cur
                val effective = minOf(pace, BudgetModel.MAX_PACE_KG_WEEK, cur * 0.01)
                Text(
                    if (abs(delta) < 0.5) "Your target is close to your current weight, so the goal aims to maintain it."
                    else "From ${cur.f1()} kg to ${t.f1()} kg: about ${(abs(delta) / effective).toInt()} weeks at this pace.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use my target to set the calorie goal", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Estimated maintenance plus or minus a gentle daily change, never below a safe minimum. " +
                            "Sugar and fat limits still come from Daily goals.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = useTarget, onCheckedChange = { useTarget = it; saved = false })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Carry over yesterday", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Moves half of yesterday's gap into today's calorie goal, up to 300 kcal. After a day over " +
                            "your sugar or fat limit, the next limit is lowered by up to 20%.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = carry, onCheckedChange = { carry = it; saved = false })
            }

            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text(if (saved) "Saved" else "Save")
            }
            Text(
                "General estimates, not medical advice. If you have a medical condition, are pregnant, or have " +
                    "a history of disordered eating, talk to a doctor or dietitian before setting weight goals.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
