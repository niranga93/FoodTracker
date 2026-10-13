package com.example.nourio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nourio.data.MealDb
import com.example.nourio.data.ProfileStore
import com.example.nourio.data.WeightDb
import com.example.nourio.data.totals
import com.example.nourio.energy.Calibration
import com.example.nourio.energy.CalibrationStore
import com.example.nourio.energy.DayEnergy
import com.example.nourio.energy.EnergyModel
import com.example.nourio.energy.EnergyRepo
import com.example.nourio.energy.Prediction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun signed(d: Double, decimals: Int): String =
    if (decimals == 0) "%+.0f".format(d) else "%+.1f".format(d)

/** Eaten vs burned for the day being viewed on the Today screen. */
@Composable
fun BalanceCard(dayOffset: Int, eatenKcal: Double) {
    val context = LocalContext.current
    val profile = remember { ProfileStore(context).load() }
    val (body, reason) = remember { EnergyRepo.body(profile) }
    var day by remember(dayOffset, eatenKcal) { mutableStateOf<DayEnergy?>(null) }

    LaunchedEffect(dayOffset, eatenKcal) {
        if (body != null) {
            val act = EnergyRepo.activityFor(context, listOf(dayOffset))[dayOffset]
            val factor = CalibrationStore(context).factor
            val raw = EnergyRepo.build(body, dayOffset, eatenKcal, act)
            day = raw.copy(burned = raw.burned * factor)
        }
    }

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Energy balance", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            val d = day
            when {
                body == null -> Text(
                    reason ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                d == null -> Text(
                    "Calculating…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("Eaten", "${d.eaten.f0()} kcal", Modifier.weight(1f))
                        StatTile("Burned", "${d.burned.f0()} kcal", Modifier.weight(1f))
                        StatTile(
                            if (d.balance >= 0) "Surplus" else "Deficit",
                            if (d.eaten > 0) "${signed(d.balance, 0)} kcal" else "—",
                            Modifier.weight(1f)
                        )
                    }
                    Text(
                        "Burn estimated from " + d.source.label +
                            (d.steps?.let { " (${"%,d".format(it)} steps)" } ?: "") + ".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    if (dayOffset == 0) {
                        Text(
                            "Today's burn assumes no more activity, so the balance changes as you move and eat.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (d.eaten <= 0) {
                        Text(
                            "Log your meals to see your balance.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Weight forecast from the last 14 completed days, calibrated by your weigh-ins. */
@Composable
fun PredictionCard(db: MealDb) {
    val context = LocalContext.current
    val profile = remember { ProfileStore(context).load() }
    val (body, reason) = remember { EnergyRepo.body(profile) }
    var prediction by remember { mutableStateOf<Prediction?>(null) }
    var calibration by remember { mutableStateOf<Calibration?>(null) }
    var factor by remember { mutableDoubleStateOf(1.0) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (body != null) {
            val offsets = (-1 downTo -28).toList()          // completed days, newest first
            val acts = EnergyRepo.activityFor(context, offsets)
            val byDay = withContext(Dispatchers.IO) { loadDays(db, offsets) }
            val raw = offsets.map { off ->
                val eaten = byDay[off].orEmpty().flatMap { it.items }.totals().kcal
                EnergyRepo.build(body, off, eaten, acts[off])
            }
            val now = System.currentTimeMillis()
            val points = withContext(Dispatchers.IO) { WeightDb(context).all() }
                .map { ((it.ts - now) / 86_400_000.0) to it.kg }

            val cal = EnergyModel.calibrate(raw, points)
            val store = CalibrationStore(context)
            if (cal != null) store.factor = cal.factor      // otherwise keep the last known value
            val f = store.factor
            calibration = cal
            factor = f
            prediction = EnergyModel.predict(raw.take(14).map { it.copy(burned = it.burned * f) })
        }
        loaded = true
    }

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Weight forecast", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            val p = prediction
            when {
                body == null -> Text(
                    reason ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                !loaded -> Text(
                    "Calculating…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                p == null -> Text(
                    "Log at least 3 full days of meals to see a forecast. " +
                        "A day counts once you've logged ${EnergyModel.MIN_LOGGED_KCAL.f0()} kcal or more.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    val w = profile.weightKg
                    Text(
                        "≈ ${signed(p.weeklyKg, 1)} kg per week",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Average daily balance ${signed(p.avgBalance, 0)} kcal " +
                            (if (p.avgBalance >= 0) "(surplus)" else "(deficit)"),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        "In 30 days at this pace: about ${(w + p.change30Kg).f1()} kg " +
                            "(range ${(w + p.low30Kg).f1()} to ${(w + p.high30Kg).f1()} kg)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        "Based on ${p.usedDays} logged days; activity data on ${p.activityDays} of them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    val cal = calibration
                    when {
                        cal != null -> Text(
                            "Calibrated from your weigh-ins: you burn about ${cal.measuredMaintenance.f0()} kcal/day " +
                                "(formula: ${cal.modelMaintenance.f0()}). Burn estimates adjusted by " +
                                "${signed((cal.factor - 1) * 100, 0)}%.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        factor != 1.0 -> Text(
                            "Burn estimates adjusted by ${signed((factor - 1) * 100, 0)}% from earlier weigh-ins.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        else -> Text(
                            "To personalize this, log your weight at least 5 times over 2+ weeks " +
                                "along with your meals.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (p.activityDays < (p.usedDays + 1) / 2) {
                        Text(
                            "Calories burned are mostly estimated from your profile. " +
                                "Connect Health Connect in Profile for better accuracy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (p.largeDeficit) {
                        Text(
                            "Your logged average is a large daily deficit. Gaps in logging are common, " +
                                "but if this is accurate, please talk to a doctor or dietitian before continuing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            Text(
                "Estimates only: about 7,700 kcal per kg of body weight. Photo logging and calorie-burn " +
                    "estimates are rough, and real weight changes vary, especially in the first weeks. " +
                    "Not medical advice. Keep your weight up to date by logging it on this screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}
