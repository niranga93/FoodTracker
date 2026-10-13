package com.example.foodtracker

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.foodtracker.data.GoalStore
import com.example.foodtracker.data.MealDb
import com.example.foodtracker.data.Totals
import com.example.foodtracker.plan.Budget
import com.example.foodtracker.plan.computeBudget
import kotlin.math.abs

/** Today's goals after the target-weight plan and carry-over. Null while calculating. */
@Composable
fun rememberBudget(db: MealDb, goalStore: GoalStore, dayOffset: Int): State<Budget?> {
    val context = LocalContext.current
    val state = remember(dayOffset) { mutableStateOf<Budget?>(null) }
    LaunchedEffect(dayOffset) { state.value = computeBudget(context, db, goalStore, dayOffset) }
    return state
}

private fun signedInt(d: Double) = "%+,.0f".format(d)

@Composable
private fun Line(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
fun BudgetCard(budget: Budget?, totals: Totals, dayOffset: Int, onOpenProfile: () -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Daily budget", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            when {
                budget == null -> Line("Calculating…", Modifier.padding(top = 6.dp))
                budget.kcalGoal <= 0 -> {
                    Line(
                        "Set a calorie goal or a target weight to get a daily budget with carry-over.",
                        Modifier.padding(top = 6.dp)
                    )
                    budget.planMessage?.let { Line(it, Modifier.padding(top = 4.dp), error = true) }
                    TextButton(onClick = onOpenProfile) { Text("Open Profile") }
                }
                else -> {
                    Text(
                        "${budget.kcalGoal.f0()} kcal",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Line(
                        "Base ${budget.baseKcal.f0()} (${budget.baseSource})" +
                            if (abs(budget.carryKcal) >= 1)
                                "  ·  ${signedInt(budget.carryKcal)} carried over from yesterday"
                            else ""
                    )
                    Spacer(Modifier.height(10.dp))
                    val remaining = budget.kcalGoal - totals.kcal
                    SlimBar((totals.kcal / budget.kcalGoal).toFloat(), remaining < 0)
                    Line(
                        if (remaining >= 0) "${remaining.f0()} kcal left of today's budget"
                        else "${(-remaining).f0()} kcal over today's budget",
                        Modifier.padding(top = 4.dp)
                    )
                    budget.carryNote?.let { Line(it, Modifier.padding(top = 4.dp)) }

                    if (budget.baseSugar > 0) {
                        val cut = budget.baseSugar - budget.sugarGoal
                        Line(
                            "Sugar: ${totals.sugar.f1()} of ${budget.sugarGoal.f0()} g" +
                                if (cut >= 0.5) "  (${cut.f0()} g lower after yesterday)" else "",
                            Modifier.padding(top = 8.dp)
                        )
                    }
                    if (budget.baseFat > 0) {
                        val cut = budget.baseFat - budget.fatGoal
                        Line(
                            "Fat: ${totals.fat.f1()} of ${budget.fatGoal.f0()} g" +
                                if (cut >= 0.5) "  (${cut.f0()} g lower after yesterday)" else "",
                            Modifier.padding(top = 2.dp)
                        )
                    }

                    // Running totals before the viewed day
                    val a7 = budget.agg7
                    if (a7.days > 0) {
                        Spacer(Modifier.height(10.dp))
                        Line(
                            "Last 7 days: ${abs(a7.underGoal).f0()} kcal " +
                                (if (a7.underGoal >= 0) "under" else "over") +
                                " your goal in total (${a7.days} logged days)."
                        )
                        Line(
                            "Energy balance (eaten minus burned): ${signedInt(a7.balance)} kcal, " +
                                "about ${"%+.2f".format(a7.kg)} kg.",
                            Modifier.padding(top = 2.dp)
                        )
                    }
                    val a30 = budget.agg30
                    if (a30.days > a7.days) {
                        Line(
                            "Last 30 days: ${signedInt(a30.balance)} kcal energy balance, " +
                                "about ${"%+.1f".format(a30.kg)} kg (${a30.days} logged days).",
                            Modifier.padding(top = 2.dp)
                        )
                    }
                    budget.planMessage?.let { Line(it, Modifier.padding(top = 6.dp), error = true) }
                }
            }
        }
    }
}
