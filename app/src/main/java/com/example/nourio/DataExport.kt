package com.example.nourio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import com.example.nourio.data.MealDb
import com.example.nourio.data.WaterStore
import com.example.nourio.data.WeightDb
import androidx.core.content.FileProvider
import com.example.nourio.export.CsvExport
import com.example.nourio.export.HealthReport
import com.example.nourio.export.ReportPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The last 30 days as a PDF for a doctor or dietitian: saved where the user picks, or shared to email and other apps. */
@Composable
fun HealthReportCard(db: MealDb) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun fileName() = "nourio-health-summary-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.pdf"

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; message = "Preparing…"
        scope.launch {
            val result = runCatching {
                val report = HealthReport.build(context, db)
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the file")
                    out.use { ReportPdf.write(report, it) }
                }
            }
            message = result.fold({ "Saved." }, { "Failed: ${it.message}" })
            busy = false
        }
    }

    fun share() {
        busy = true; message = "Preparing…"
        scope.launch {
            val result = runCatching {
                val report = HealthReport.build(context, db)
                val file = withContext(Dispatchers.IO) {
                    // One report at a time in the cache; the previous one is removed
                    val dir = File(context.cacheDir, "reports").apply { deleteRecursively(); mkdirs() }
                    File(dir, fileName()).also { f -> f.outputStream().use { ReportPdf.write(report, it) } }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Health summary, last ${HealthReport.DAYS} days")
                    putExtra(Intent.EXTRA_TEXT, "My nutrition, weight, activity and sleep summary from Nourio.")
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Share health summary"))
            }
            message = result.fold({ null }, { "Failed: ${it.message}" })
            busy = false
        }
    }

    WidgetCard("Health summary (PDF)") {
        Column {
            Text(
                "Your last ${HealthReport.DAYS} days as a short PDF for your doctor or dietitian: average calories and " +
                    "nutrients against your goals, macro split, daily calories, weight trend, and steps, burn and " +
                    "sleep from Health Connect. Made on your phone; it goes only where you send it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { message = null; saver.launch(fileName()) }, enabled = !busy,
                    modifier = Modifier.weight(1f)) { Text("Save PDF") }
                OutlinedButton(onClick = { share() }, enabled = !busy,
                    modifier = Modifier.weight(1f)) { Text("Share or email") }
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

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
            OutlinedButton(onClick = { start("meals", "nourio-meals") }, enabled = !busy,
                modifier = Modifier.fillMaxWidth()) { Text("Meals") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { start("daily", "nourio-daily") }, enabled = !busy,
                modifier = Modifier.fillMaxWidth()) { Text("Daily totals") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { start("weights", "nourio-weights") }, enabled = !busy,
                modifier = Modifier.fillMaxWidth()) { Text("Weights") }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
