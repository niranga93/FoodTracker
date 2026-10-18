package com.example.nourio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.example.nourio.energy.EnergyRepo
import com.example.nourio.health.HealthExtras
import com.example.nourio.health.HealthPrefs
import com.example.nourio.health.HealthSync

/** Opt-in extras: read sleep, heart rate, workouts and weight; write meals, water and weight. */
@Composable
fun HealthExtrasCard() {
    val context = LocalContext.current
    val available = remember { HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE }
    if (!available) return

    val prefs = remember { HealthPrefs(context) }
    val client = remember { HealthConnectClient.getOrCreate(context) }
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    var readOn by remember { mutableStateOf(prefs.readExtras) }
    var writeOn by remember { mutableStateOf(prefs.writeSync) }
    var pending by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
    }

    val launcher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { result ->
        granted = granted + result
        EnergyRepo.clearCache()                         // newly allowed types (distance) count at once
        if (pending == "more") {
            message = if (granted.containsAll(HealthExtras.MORE_READ_PERMS)) "Allowed." else "Some were not allowed. That's fine."
            HealthSync.runAsync(context)
            return@rememberLauncherForActivityResult
        }
        val needed = if (pending == "read") HealthExtras.READ_PERMS else HealthExtras.WRITE_PERMS
        val ok = granted.containsAll(needed)
        if (pending == "read") { prefs.readExtras = ok; readOn = ok } else { prefs.writeSync = ok; writeOn = ok }
        message = if (ok) "On." else "Not all permissions were allowed."
        if (ok) HealthSync.runAsync(context)
    }

    val readNow = readOn && granted.containsAll(HealthExtras.READ_PERMS)
    val writeNow = writeOn && granted.containsAll(HealthExtras.WRITE_PERMS)
    // Turned on before these types existed, or some were declined: offer them separately
    val missingMore = readNow && !granted.containsAll(HealthExtras.MORE_READ_PERMS)

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                "More from Health Connect",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            SwitchRow(
                "Read sleep, heart rate, workouts, weight",
                "Also blood pressure, distance and height if you allow them. " +
                    "Shown on Burned, and used for a more accurate burn.",
                readNow
            ) { on ->
                message = null
                if (on) {
                    pending = "read"
                    if (granted.containsAll(HealthExtras.READ_PERMS)) {
                        prefs.readExtras = true; readOn = true
                        HealthSync.runAsync(context)
                        if (!granted.containsAll(HealthExtras.MORE_READ_PERMS)) {
                            pending = "more"; launcher.launch(HealthExtras.MORE_READ_PERMS)
                        }
                    } else launcher.launch(HealthExtras.READ_PERMS + HealthExtras.MORE_READ_PERMS)
                } else {
                    prefs.readExtras = false; readOn = false
                    EnergyRepo.clearCache()
                }
            }
            if (missingMore) {
                TextButton(
                    onClick = { message = null; pending = "more"; launcher.launch(HealthExtras.MORE_READ_PERMS) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) { Text("Allow new data: blood pressure, distance, height") }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SwitchRow("Share meals, water, weight", "Written to Health Connect for other apps.", writeNow) { on ->
                message = null
                if (on) {
                    pending = "write"
                    if (granted.containsAll(HealthExtras.WRITE_PERMS)) {
                        prefs.writeSync = true; writeOn = true
                        HealthSync.runAsync(context)
                    } else launcher.launch(HealthExtras.WRITE_PERMS)
                } else {
                    prefs.writeSync = false; writeOn = false
                }
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}
