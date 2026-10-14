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
