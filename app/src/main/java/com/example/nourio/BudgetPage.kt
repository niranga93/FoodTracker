package com.example.nourio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nourio.data.GoalStore
import com.example.nourio.data.MealDb
import com.example.nourio.data.totals
import com.example.nourio.plan.Budget
import com.example.nourio.plan.PlanStore
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.roundToLong

private val nf: NumberFormat get() = NumberFormat.getIntegerInstance()
private fun kcal(d: Double) = "${nf.format(d.roundToLong())} kcal"
private fun signedKcal(d: Double) = (if (d >= 0) "+" else "−") + kcal(abs(d))

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** One step of the calculation: a label and amount, with a plain explanation under it. */
@Composable
private fun Step(label: String, amount: String, explain: String?, bold: Boolean = false, over: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (bold) FontWeight.Bold else null)
            Text(
                amount,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }
        explain?.let { Muted(it, Modifier.padding(top = 2.dp)) }
    }
}

/** Where the daily goal comes from, in plain words. */
private fun goalExplanation(b: Budget, plan: PlanStore): String {
    val g = b.plan ?: return "The calorie goal you set in Goals & target."
    val maintain = "Your body uses about ${kcal(g.maintenance)} a day"
    val pace = "%.2f".format(plan.paceKgPerWeek).trimEnd('0').trimEnd('.')
    val toward = "toward your target of ${"%.1f".format(plan.targetKg)} kg"
    val eta = g.etaWeeks?.let { w -> " At this pace you'd get there in about ${w.roundToLong().coerceAtLeast(1)} weeks." } ?: ""
    return when {
        abs(g.dailyChange) < 1 -> "$maintain, and you're at your target weight, so your goal is to keep it there."
        g.dailyChange < 0 -> "$maintain. Eating ${kcal(-g.dailyChange)} less than that loses about $pace kg a week $toward.$eta"
        else -> "$maintain. Eating ${kcal(g.dailyChange)} more than that gains about $pace kg a week $toward.$eta"
    } + if (g.clamped) " The pace was slowed so your goal stays at a safe minimum." else ""
}

private fun carryExplanation(b: Budget, carryOn: Boolean): String = when {
    !carryOn -> "Carry-over is off, so each day starts fresh. You can turn it on in Profile > Settings."
    b.carryNote != null -> b.carryNote
    b.carryKcal >= 1 -> "You ate less than your goal yesterday, so half of the difference is added today (up to 300 kcal)."
    b.carryKcal <= -1 -> "You went over your goal yesterday, so half of the extra comes off today (up to 300 kcal), " +
        "but never below a safe minimum."
    else -> "Yesterday was on target or not logged, so nothing changes today."
}

/** Plain-language breakdown of the calorie budget for [dayOffset]. Opened from the Home screen. */
@Composable
fun BudgetPage(db: MealDb, goalStore: GoalStore, dayOffset: Int) {
    val context = LocalContext.current
    val openPage = LocalOpenPage.current
    val plan = remember { PlanStore(context) }
    val budget by rememberBudget(db, goalStore, dayOffset)
    val totals = remember(dayOffset) { loadDay(db, dayOffset).flatMap { it.items }.totals() }
    val b = budget
    val today = dayOffset == 0

    when {
        b == null -> {
            WidgetCard("Your calorie budget") { Muted("Working it out…") }
            return
        }
        b.kcalGoal <= 0 -> {
            WidgetCard("Your calorie budget") {
                Text(
                    "You don't have a budget yet. Set a daily calorie goal, or a target weight and the app " +
                        "works one out for you.",
                    style = MaterialTheme.typography.bodyMedium
                )
                b.planMessage?.let { Muted(it, Modifier.padding(top = 8.dp), error = true) }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { openPage("goals") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Set a goal or target weight")
                }
            }
            return
        }
    }

    // 1. The headline: budget, eaten, left
    WidgetCard(if (today) "Today's budget" else "Budget for ${dayTitle(dayOffset)}") {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(kcal(b.kcalGoal), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            val left = b.kcalGoal - totals.kcal
            SlimBar((totals.kcal / b.kcalGoal).toFloat(), left < 0, Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text(
                if (left >= 0) "${kcal(totals.kcal)} eaten, ${kcal(left)} left"
                else "${kcal(totals.kcal)} eaten, ${kcal(-left)} over",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    // 2. How it's worked out
    WidgetCard("How it's worked out") {
        Step("Your daily goal", kcal(b.baseKcal), goalExplanation(b, plan))
        HorizontalDivider()
        Step("From yesterday", if (abs(b.carryKcal) >= 1) signedKcal(b.carryKcal) else "0 kcal", carryExplanation(b, plan.carryOver))
        HorizontalDivider()
        Step(if (today) "Today's budget" else "That day's budget", kcal(b.kcalGoal), null, bold = true)
        b.planMessage?.let { Muted(it, Modifier.padding(top = 6.dp), error = true) }
    }

    // 3. Sugar and fat limits, which only ever go down after an over day
    if (b.baseSugar > 0 || b.baseFat > 0) {
        WidgetCard("Sugar and fat") {
            if (b.baseSugar > 0) {
                val cut = b.baseSugar - b.sugarGoal
                Step(
                    "Added sugar", "${totals.addedSugar.f0()} of ${b.sugarGoal.f0()} g",
                    if (cut >= 0.5) "${cut.f0()} g lower than usual because you went over yesterday." else null,
                    over = totals.addedSugar > b.sugarGoal
                )
            }
            if (b.baseFat > 0) {
                val cut = b.baseFat - b.fatGoal
                Step(
                    "Fat", "${totals.fat.f0()} of ${b.fatGoal.f0()} g",
                    if (cut >= 0.5) "${cut.f0()} g lower than usual because you went over yesterday." else null,
                    over = totals.fat > b.fatGoal
                )
            }
        }
    }

    // 4. How the recent days went
    WidgetCard("How you've been doing") {
        val a7 = b.agg7
        if (a7.days == 0) {
            Muted("Log full days of meals and you'll see how you're doing against your goal here.")
        } else {
            val days7 = if (a7.days == 1) "1 day" else "${a7.days} days"
            Text(
                "Last 7 days ($days7 logged): " +
                    if (a7.underGoal >= 0) "${kcal(a7.underGoal)} under your goal in total."
                    else "${kcal(-a7.underGoal)} over your goal in total.",
                style = MaterialTheme.typography.bodyMedium
            )
            Muted(
                (if (a7.balance <= 0) "You burned ${kcal(-a7.balance)} more than you ate"
                else "You ate ${kcal(a7.balance)} more than you burned") +
                    ", which is roughly ${"%.1f".format(abs(a7.kg))} kg of body weight.",
                Modifier.padding(top = 4.dp)
            )
            val a30 = b.agg30
            if (a30.days > a7.days) {
                Muted(
                    "Last 30 days (${a30.days} days logged): " +
                        (if (a30.balance <= 0) "${kcal(-a30.balance)} burned more than eaten"
                        else "${kcal(a30.balance)} eaten more than burned") +
                        ", roughly ${"%.1f".format(abs(a30.kg))} kg.",
                    Modifier.padding(top = 8.dp)
                )
            }
            Muted(
                "Days with less than 800 kcal logged are left out, since they're probably not complete.",
                Modifier.padding(top = 8.dp)
            )
        }
    }

    OutlinedButton(onClick = { openPage("goals") }, modifier = Modifier.fillMaxWidth()) {
        Text("Change goal or target weight")
    }
}
