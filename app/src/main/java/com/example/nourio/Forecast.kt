package com.example.nourio

import android.content.Context
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
import androidx.compose.runtime.mutableDoubleStateOf
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
import com.example.nourio.data.ProfileStore
import com.example.nourio.data.WeightDb
import com.example.nourio.data.totals
import com.example.nourio.energy.Body
import com.example.nourio.energy.Calibration
import com.example.nourio.energy.CalibrationStore
import com.example.nourio.energy.EnergyModel
import com.example.nourio.energy.EnergyRepo
import com.example.nourio.energy.Prediction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ForecastResult(val prediction: Prediction?, val calibration: Calibration?, val factor: Double)

/** Last 28 completed days of intake and burn, calibrated by your weigh-ins. */
suspend fun loadForecast(context: Context, db: MealDb, body: Body): ForecastResult {
    val offsets = (-1 downTo -28).toList()
    val acts = EnergyRepo.activityFor(context, offsets)
    val raw = offsets.map { off ->
        val eaten = withContext(Dispatchers.IO) { loadDay(db, off).flatMap { it.items }.totals().kcal }
        EnergyRepo.build(body, off, eaten, acts[off])
    }
    val now = System.currentTimeMillis()
    val points = withContext(Dispatchers.IO) { WeightDb(context).all() }
        .map { ((it.ts - now) / 86_400_000.0) to it.kg }

    val cal = EnergyModel.calibrate(raw, points)
    val store = CalibrationStore(context)
    if (cal != null) store.factor = cal.factor
    val f = store.factor
    val pred = EnergyModel.predict(raw.take(14).map { it.copy(burned = it.burned * f) })
    return ForecastResult(pred, cal, f)
}

private fun signedStr(d: Double, decimals: Int) =
    if (decimals == 0) "%+.0f".format(d) else "%+.1f".format(d)

@Composable
private fun Note(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
fun ForecastSummaryCard(db: MealDb, onDetails: () -> Unit) {
    val context = LocalContext.current
    val profile = remember { ProfileStore(context).load() }
    val (body, reason) = remember { EnergyRepo.body(profile) }
    var result by remember { mutableStateOf<ForecastResult?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var weightKg by remember { mutableDoubleStateOf(profile.weightKg) }
    var showLog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (body != null) result = loadForecast(context, db, body)
        loaded = true
    }

    if (showLog) {
        LogWeightDialog(
            onDismiss = { showLog = false },
            onSaved = { weightKg = ProfileStore(context).load().weightKg }
        )
    }

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Weight forecast", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = { showLog = true }) { Text("Log weight") }
            }
            val r = result
            val p = r?.prediction
            when {
                body == null -> Note(reason ?: "")
                !loaded -> Note("Calculating…")
                p == null -> {
                    if (weightKg > 0) {
                        Text("${weightKg.f1()} kg", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold)
                    }
                    Note(
                        "Log at least 3 full days of meals (${EnergyModel.MIN_LOGGED_KCAL.f0()} kcal or more each) " +
                            "to forecast your weight from what you eat and burn.",
                        Modifier.padding(top = 4.dp)
                    )
                }
                else -> {
                    Text(
                        "≈ ${signedStr(p.weeklyKg, 1)} kg per week",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Note(
                        "Eaten ${p.avgEaten.f0()} kcal and burned ${p.avgBurned.f0()} kcal a day on average " +
                            "over ${p.usedDays} logged days (${signedStr(p.avgBalance, 0)} kcal a day).",
                        Modifier.padding(top = 4.dp)
                    )
                    Note(
                        "In 30 days: about ${(weightKg + p.change30Kg).f1()} kg " +
                            "(range ${(weightKg + p.low30Kg).f1()} to ${(weightKg + p.high30Kg).f1()} kg).",
                        Modifier.padding(top = 4.dp)
                    )
                    if (r.calibration != null || r.factor != 1.0) {
                        Note("Adjusted using your weigh-ins.", Modifier.padding(top = 4.dp))
                    }
                    if (p.largeDeficit) {
                        Note(
                            "That is a large daily deficit. If it's accurate, please talk to a doctor or " +
                                "dietitian before continuing.",
                            Modifier.padding(top = 4.dp),
                            error = true
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onDetails) { Text("Details") }
        }
    }
}
