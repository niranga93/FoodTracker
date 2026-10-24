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
import com.example.nourio.energy.BodyData
import com.example.nourio.energy.BodyModel
import com.example.nourio.energy.WeightFilter
import com.example.nourio.energy.Calibration
import com.example.nourio.energy.CalibrationStore
import com.example.nourio.energy.EnergyModel
import com.example.nourio.energy.EnergyRepo
import com.example.nourio.energy.Prediction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ForecastResult(
    val prediction: Prediction?,
    val calibration: Calibration?,
    val factor: Double,
    // For tips: the most recent morning weigh-in (day offset, kg) of today or yesterday, recent protein, and
    // what the device says exercise burned
    val latestMorning: Pair<Int, Double>? = null,
    val avgProteinG: Double? = null,
    val avgDeviceActiveKcal: Double? = null,
)

/** Days of history the forecast learns from. */
private const val HISTORY_DAYS = 60

/**
 * The body model (fat, lean, glycogen and salt water, adaptation) run over the last [HISTORY_DAYS] days of
 * food and activity, corrected by every weigh-in with a Kalman filter, then run 30 days ahead with the
 * recent average day. See [BodyModel] and [WeightFilter].
 */
suspend fun loadForecast(context: Context, db: MealDb, body: Body): ForecastResult {
    val weights = withContext(Dispatchers.IO) { WeightDb(context).all() }.sortedBy { it.ts }
    val h = BodyData.history(context, db, body, weights, HISTORY_DAYS)
    val offsets = h.offsets
    val inputs = h.inputs
    val base = h.base
    val resting = h.resting
    val r = WeightFilter.run(h.start, h.days, base, resting)
    val mornings = BodyData.mornings(weights)

    // The coming days look like the last two weeks
    val recent = offsets.takeLast(14).mapNotNull { inputs[it] }
    val logged = recent.filter { it.eatenKcal != null }
    if (logged.size < 3) return ForecastResult(null, null, CalibrationStore(context).factor)
    val eaten = logged.map { it.eatenKcal!! }
    val avgEaten = eaten.average()
    fun avg(f: (com.example.nourio.energy.BodyModel.DayInput) -> Double) = logged.map(f).average()
    val typical = BodyModel.DayInput(
        eatenKcal = avgEaten, proteinG = avg { it.proteinG }, carbsG = avg { it.carbsG }, fatG = avg { it.fatG },
        sodiumMg = logged.mapNotNull { it.sodiumMg }.takeIf { it.isNotEmpty() }?.average(),
        activityKcal = recent.map { it.activityKcal }.average(),
    )
    val sd = if (eaten.size > 1) kotlin.math.sqrt(eaten.sumOf { (it - avgEaten) * (it - avgEaten) } / (eaten.size - 1)) else 400.0
    val out = WeightFilter.outlook(r, typical, sd, logged.size, base, resting)
    // Burn on a typical day with the learned gap counted as eaten, as the outlook does
    val burned = BodyModel.step(r.model, typical.copy(eatenKcal = avgEaten + r.biasKcal), base, resting).burnedKcal

    // Other screens scale the burn estimate by a factor: keep it in step with the learned gap once it's reliable
    val store = CalibrationStore(context)
    val span = mornings.keys.let { k -> if (k.isEmpty()) 0 else k.max() - k.min() }
    if (r.weighIns >= 5 && span >= 14 && r.biasSd < 150) {
        store.update(((burned - r.biasKcal) / burned).coerceIn(0.8, 1.2))
    }

    val balance = avgEaten - burned + r.biasKcal
    val pred = Prediction(
        usedDays = logged.size,
        avgBalance = balance,
        weeklyKg = out.tissuePerWeekKg,
        change30Kg = out.in30Kg - out.nowKg,
        low30Kg = out.low30Kg - out.nowKg,
        high30Kg = out.high30Kg - out.nowKg,
        activityDays = recent.count { it.activityKcal > 0 },
        largeDeficit = balance < -1000.0,
        avgEaten = avgEaten,
        avgBurned = burned,
        nowKg = out.nowKg,
        gapKcal = r.biasKcal.takeIf { r.weighIns >= 2 },
        gapSd = r.biasSd.takeIf { r.weighIns >= 2 },
        waterKg = out.waterNowKg,
        weighIns = r.weighIns,
    )
    val acts = EnergyRepo.activityFor(context, (-7..-1).toList())
    return ForecastResult(
        pred, null, store.factor,
        // The latest reading, as if taken on waking
        latestMorning = listOf(0, -1).firstNotNullOfOrNull { o -> h.readings[o]?.lastOrNull()?.let { o to r.morning(it) } },
        avgProteinG = avg { it.proteinG },
        avgDeviceActiveKcal = acts.values.map { it.activeKcal }.filter { it > 0 }.takeIf { it.size >= 3 }?.average(),
    )
}
