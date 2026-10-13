package com.example.foodtracker

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.foodtracker.data.MealDb
import com.example.foodtracker.data.WaterStore
import com.example.foodtracker.data.WeightDb
import com.example.foodtracker.export.CsvExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DataCard(db: MealDb) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            val kind = pending
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val csv = when (kind) {
                            "meals" -> CsvExport.meals(db.all())
                            "weights" -> CsvExport.weights(WeightDb(context).all())
                            else -> CsvExport.daily(db.all(), WaterStore(context).allDays())
                        }
                        val out = context.contentResolver.openOutputStream(uri, "wt")
                            ?: error("Could not open the file")
                        out.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                    }
                }
                message = result.fold({ "Saved." }, { "Failed: ${it.message}" })
                busy = false
            }
        }
    }

    fun start(kind: String, baseName: String) {
        pending = kind
        message = null
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        launcher.launch("$baseName-$date.csv")
    }

    WidgetCard("Export to CSV") {
        Column {
            OutlinedButton(onClick = { start("meals", "foodtracker-meals") }, enabled = !busy,
                modifier = Modifier.fillMaxWidth()) { Text("Meals") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { start("daily", "foodtracker-daily") }, enabled = !busy,
                modifier = Modifier.fillMaxWidth()) { Text("Daily totals") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { start("weights", "foodtracker-weights") }, enabled = !busy,
                modifier = Modifier.fillMaxWidth()) { Text("Weights") }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
