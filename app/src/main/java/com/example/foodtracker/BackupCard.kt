package com.example.foodtracker

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.example.foodtracker.backup.AuthResult
import com.example.foodtracker.backup.BackupPrefs
import com.example.foodtracker.backup.BackupService
import com.example.foodtracker.backup.DriveApi
import com.example.foodtracker.backup.DriveAuth
import com.example.foodtracker.backup.Remote
import com.example.foodtracker.data.GoalStore
import com.example.foodtracker.data.MealDb
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class Decision(val remote: Remote, val token: String)

@Composable
fun BackupCard(db: MealDb, goalStore: GoalStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { BackupPrefs(context) }
    val fmt = remember { SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()) }

    var email by remember { mutableStateOf(prefs.email) }
    var lastBackup by remember { mutableStateOf(prefs.lastBackup) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var decision by remember { mutableStateOf<Decision?>(null) }
    var pendingAction by remember { mutableStateOf("") }

    // action: "signin" | "backup" | "restore"
    suspend fun runAction(action: String, token: String) {
        busy = true
        message = null
        try {
            when (action) {
                "signin" -> {
                    val mail = withContext(Dispatchers.IO) { DriveApi.userEmail(token) }
                    prefs.email = mail ?: "Google account"
                    email = prefs.email
                    val remote = withContext(Dispatchers.IO) { BackupService.fetchRemote(token) }
                    if (remote == null) {
                        val hasMeals = db.count() > 0
                        if (hasMeals) {
                            withContext(Dispatchers.IO) {
                                BackupService.backupNow(token, db, goalStore.load(), prefs)
                            }
                        }
                        prefs.baselineSet = true
                        lastBackup = prefs.lastBackup
                        message = if (hasMeals) "Connected. First backup saved to Google Drive."
                        else "Connected. Your meals will be backed up automatically."
                    } else {
                        decision = Decision(remote, token)
                    }
                }
                "backup" -> {
                    if (db.count() == 0) {
                        message = "Nothing to back up yet."
                    } else {
                        withContext(Dispatchers.IO) {
                            BackupService.backupNow(token, db, goalStore.load(), prefs)
                        }
                        prefs.baselineSet = true
                        lastBackup = prefs.lastBackup
                        message = "Backup saved to Google Drive."
                    }
                }
                "restore" -> {
                    val remote = withContext(Dispatchers.IO) { BackupService.fetchRemote(token) }
                    if (remote == null) message = "No backup found in this Google account."
                    else decision = Decision(remote, token)
                }
            }
        } catch (e: Exception) {
            message = e.message ?: "Something went wrong"
        } finally {
            busy = false
        }
    }

    val authLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        val token = runCatching {
            Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(res.data).accessToken
        }.getOrNull()
        if (token == null) {
            busy = false
            message = "Google authorization was cancelled."
        } else {
            scope.launch { runAction(pendingAction, token) }
        }
    }

    fun startAuth(action: String) {
        pendingAction = action
        busy = true
        message = null
        scope.launch {
            when (val r = DriveAuth.authorize(context)) {
                is AuthResult.Token -> runAction(action, r.value)
                is AuthResult.NeedsConsent ->
                    authLauncher.launch(IntentSenderRequest.Builder(r.sender).build())
                is AuthResult.Failure -> {
                    busy = false
                    message = r.message
                }
            }
        }
    }

    // Shown when a Drive copy exists and the user must choose which side wins
    decision?.let { d ->
        val localCount = remember(d) { db.count() }
        AlertDialog(
            onDismissRequest = { decision = null },
            title = { Text("Backup found") },
            text = {
                Column {
                    Text("Google Drive copy: ${d.remote.meals.size} meals, saved ${fmt.format(Date(d.remote.savedAt))}.")
                    Text("This phone: $localCount meals.", modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            BackupService.applyRemote(db, goalStore, d.remote, prefs)
                            lastBackup = prefs.lastBackup
                            message = "Restored ${d.remote.meals.size} meals from Google Drive."
                            decision = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Use Google Drive copy") }
                    if (localCount > 0) {
                        OutlinedButton(
                            onClick = {
                                decision = null
                                scope.launch {
                                    busy = true
                                    try {
                                        withContext(Dispatchers.IO) {
                                            BackupService.backupNow(d.token, db, goalStore.load(), prefs)
                                        }
                                        prefs.baselineSet = true
                                        lastBackup = prefs.lastBackup
                                        message = "Google Drive copy replaced with this phone's data."
                                    } catch (e: Exception) {
                                        message = e.message ?: "Backup failed"
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) { Text("Keep this phone's data") }
                        Text(
                            "Whichever you choose replaces the other copy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { decision = null }) { Text("Not now") } }
        )
    }

    val mail = email
    ConnectorCard(
        title = "Google Drive",
        subtitle = "Back up and restore your meals.",
        connected = mail != null,
        statusLabel = if (mail != null) "Connected" else "Not connected",
        busy = busy,
        details = if (mail != null) listOf(
            "Account" to mail,
            "Access" to "This app's private folder only",
            "Last backup" to (if (lastBackup > 0) fmt.format(Date(lastBackup)) else "None from this phone yet"),
            "Auto-backup" to (if (prefs.baselineSet) "On" else "Starts after your first backup or restore"),
            "Stores" to "Meals, weigh-ins, profile, settings"
        ) else listOf(
            "Access" to "This app's private folder only. It can't see your other Drive files",
            "Stores" to "Meals, weigh-ins, profile, settings"
        ),
        disconnectNote = "Auto-backup will stop on this phone. Your copy in Google Drive stays.",
        onConnect = { startAuth("signin") },
        onDisconnect = {
            prefs.clear()
            email = null
            lastBackup = 0L
            message = "Disconnected. Your Google Drive copy is untouched."
        }
    ) {
        if (mail != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { startAuth("backup") }) { Text("Back up now") }
                OutlinedButton(enabled = !busy, onClick = { startAuth("restore") }) { Text("Restore") }
            }
        }
        if (busy) {
            Text(
                "Working…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
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