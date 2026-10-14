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
