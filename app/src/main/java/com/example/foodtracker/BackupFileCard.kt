package com.example.foodtracker

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.foodtracker.backup.BackupService
import com.example.foodtracker.backup.Remote
import com.example.foodtracker.data.GoalStore
import com.example.foodtracker.data.MealDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Backup to a file the user chooses: Google Drive, Dropbox, OneDrive, or the phone. No sign-in needed. */
@Composable
fun BackupFileCard(db: MealDb, goalStore: GoalStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Remote?>(null) }
    var busy by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()) }

    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val json = BackupService.buildFileBackup(db, goalStore.load())
                        val out = context.contentResolver.openOutputStream(uri, "wt")
                            ?: error("Could not open the file")
                        out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    }
                }
                message = result.fold({ "Backup file saved." }, { "Failed: ${it.message}" })
                busy = false
            }
        }
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = context.contentResolver.openInputStream(uri)
                            ?.use { it.readBytes().toString(Charsets.UTF_8) }
                            ?: error("Could not read the file")
                        BackupService.parseFileBackup(text)
                    }
                }
                result.onSuccess { pending = it }
                    .onFailure { message = "That doesn't look like a FoodTracker backup." }
            }
        }
    }

    pending?.let { r ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "${r.meals.size} meals, saved ${fmt.format(Date(r.savedAt))}. " +
                        "This replaces the data on this phone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { BackupService.restoreData(db, goalStore, r) }
                        }
                        message = result.fold({ "Restored ${r.meals.size} meals." }, { "Failed: ${it.message}" })
                        busy = false
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } }
        )
    }

    WidgetCard("Backup file") {
        Text(
            "Save to Google Drive, Dropbox, or your phone. The file isn't encrypted, so keep it private.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    save.launch("fuelo-backup-$date.json")
                }
            ) { Text("Save file") }
            OutlinedButton(enabled = !busy, onClick = { open.launch(arrayOf("*/*")) }) {
                Text("Restore from file")
            }
        }
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
