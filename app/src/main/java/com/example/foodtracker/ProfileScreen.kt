package com.example.foodtracker

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.foodtracker.data.GoalStore
import com.example.foodtracker.data.MealDb
import com.example.foodtracker.data.Profile
import com.example.foodtracker.data.ProfileStore
import com.example.foodtracker.health.HealthPrefs
import com.example.foodtracker.health.HealthRepo
import kotlinx.coroutines.launch
import java.util.Calendar

// ---------------------------------------------------------------- reusable connector UI

@Composable
fun StatusChip(label: String, positive: Boolean) {
    val bg = if (positive) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (positive) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        label,
        color = fg,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
fun ConnectorCard(
    title: String,
    subtitle: String,
    connected: Boolean,
    statusLabel: String,
    busy: Boolean,
    details: List<Pair<String, String>>,
    disconnectNote: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    connectEnabled: Boolean = true,
    extra: @Composable ColumnScope.() -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Disconnect $title?") },
            text = { Text(disconnectNote) },
            confirmButton = {
                TextButton(onClick = { confirm = false; onDisconnect() }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        title.take(1),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                StatusChip(statusLabel, connected)
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide details" else "Details")
            }
            if (expanded) {
                details.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(0.4f)
                        )
                        Text(
                            value,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(0.6f)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            extra()
            Spacer(Modifier.height(8.dp))
            if (connected) {
                OutlinedButton(
                    onClick = { confirm = true },
                    enabled = !busy,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Disconnect") }
            } else {
                Button(
                    onClick = onConnect,
                    enabled = !busy && connectEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Connect") }
            }
        }
    }
}

// ---------------------------------------------------------------- Health Connect connector

@Composable
fun HealthConnectorCard() {
    val context = LocalContext.current
    val status = remember { HealthConnectClient.getSdkStatus(context) }
    when (status) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectorContent()
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> ConnectorCard(
            title = "Health Connect",
            subtitle = "Steps and calories burned from your phone and watch.",
            connected = false,
            statusLabel = "Update needed",
            busy = false,
            details = listOf("Status" to "Update Health Connect from the Play Store"),
            disconnectNote = "",
            onConnect = {},
            onDisconnect = {},
            connectEnabled = false
        )
        else -> ConnectorCard(
            title = "Health Connect",
            subtitle = "Steps and calories burned from your phone and watch.",
            connected = false,
            statusLabel = "Not supported",
            busy = false,
            details = listOf("Status" to "Health Connect isn't available on this device"),
            disconnectNote = "",
            onConnect = {},
            onDisconnect = {},
            connectEnabled = false
        )
    }
}

@Composable
private fun HealthConnectorContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { HealthConnectClient.getOrCreate(context) }
    val prefs = remember { HealthPrefs(context) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var granted by remember { mutableStateOf<Set<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val stepsP = remember { HealthPermission.getReadPermission(StepsRecord::class) }
    val activeP = remember { HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) }
    val totalP = remember { HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class) }

    suspend fun refresh() {
        granted = runCatching { client.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
    }

    val launcher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { result ->
        granted = result
        if (result.isNotEmpty()) {
            prefs.enabled = true
            enabled = true
            message = null
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // Re-check when the user returns from Health Connect settings
    val owner = context as LifecycleOwner
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { refresh() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    val g = granted ?: emptySet()
    val all = g.containsAll(HealthRepo.PERMISSIONS)
    val any = HealthRepo.PERMISSIONS.any { it in g }
    val connected = enabled && any
    fun allowed(p: String) = if (enabled && p in g) "Allowed" else "Not allowed"

    ConnectorCard(
        title = "Health Connect",
        subtitle = "Steps and calories burned, collected by Health Connect from your phone, " +
            "watch, and apps such as Google Health or Samsung Health.",
        connected = connected,
        statusLabel = when {
            granted == null -> "Checking…"
            connected && all -> "Connected"
            connected -> "Partial access"
            else -> "Not connected"
        },
        busy = busy || granted == null,
        details = listOf(
            "Provider" to "Health Connect (on this phone)",
            "Steps" to allowed(stepsP),
            "Active calories" to allowed(activeP),
            "Total calories" to allowed(totalP),
            "Access" to "Read-only. Data stays on this phone"
        ),
        disconnectNote = "Steps and calories burned will be hidden in this app. " +
            "You can connect again any time. The app may restart briefly.",
        onConnect = {
            if (all) {
                prefs.enabled = true      // already permitted: just switch it back on
                enabled = true
                message = null
            } else {
                launcher.launch(HealthRepo.PERMISSIONS)
            }
        },
        onDisconnect = {
            prefs.enabled = false         // hides steps in the app right away
            enabled = false
            scope.launch {
                busy = true
                message = runCatching { client.permissionController.revokeAllPermissions() }.fold(
                    onSuccess = { "Disconnected. Access was also removed in Health Connect." },
                    onFailure = {
                        "Hidden in this app. To fully remove access, open Health Connect > App permissions."
                    }
                )
                refresh()
                busy = false
            }
        }
    ) {
        if (connected && !all) {
            TextButton(onClick = { launcher.launch(HealthRepo.PERMISSIONS) }) {
                Text("Grant missing access")
            }
        }
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ---------------------------------------------------------------- Profile screen

private fun num(v: Double): String =
    if (v <= 0) "" else if (v == v.toLong().toDouble()) v.f0() else v.f1()

private fun parseNum(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

@Composable
fun ProfileScreen(goalStore: GoalStore, db: MealDb, inner: PaddingValues) {
    val context = LocalContext.current
    val store = remember { ProfileStore(context) }
    val saved = remember { store.load() }

    var name by remember { mutableStateOf(saved.name) }
    var birthYear by remember { mutableStateOf(if (saved.birthYear > 0) saved.birthYear.toString() else "") }
    var sex by remember { mutableStateOf(saved.sex) }
    var height by remember { mutableStateOf(num(saved.heightCm)) }
    var weight by remember { mutableStateOf(num(saved.weightKg)) }
    var error by remember { mutableStateOf<String?>(null) }
    var justSaved by remember { mutableStateOf(false) }

    fun save() {
        val h = if (height.isBlank()) 0.0 else parseNum(height) ?: -1.0
        val w = if (weight.isBlank()) 0.0 else parseNum(weight) ?: -1.0
        val y = if (birthYear.isBlank()) 0 else birthYear.trim().toIntOrNull() ?: -1
        val thisYear = Calendar.getInstance().get(Calendar.YEAR)
        error = when {
            h != 0.0 && h !in 50.0..260.0 -> "Height should be between 50 and 260 cm."
            w != 0.0 && w !in 20.0..400.0 -> "Weight should be between 20 and 400 kg."
            y != 0 && y !in 1900..thisYear -> "Enter a valid year of birth."
            else -> null
        }
        if (error == null) {
            store.save(Profile(name.trim(), y, sex, h, w))
            justSaved = true
        }
    }

    val bmi = remember(height, weight) {
        Profile(heightCm = parseNum(height) ?: 0.0, weightKg = parseNum(weight) ?: 0.0).bmi
    }

    Column(
        Modifier.fillMaxSize().padding(inner).imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        // header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    name.trim().take(1).uppercase().ifBlank { "?" },
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    name.trim().ifBlank { "Your profile" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Personal details and connected services",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // personal details
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Personal details", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; justSaved = false },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = height,
                        onValueChange = { height = it; justSaved = false },
                        label = { Text("Height") },
                        suffix = { Text("cm") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = weight,
                        onValueChange = { weight = it; justSaved = false },
                        label = { Text("Weight") },
                        suffix = { Text("kg") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = birthYear,
                    onValueChange = { birthYear = it; justSaved = false },
                    label = { Text("Year of birth") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Sex (optional)", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Female", "Male", "Other").forEach { option ->
                        FilterChip(
                            selected = sex == option,
                            onClick = { sex = if (sex == option) "" else option; justSaved = false },
                            label = { Text(option) }
                        )
                    }
                }
                bmi?.let {
                    Text(
                        "BMI ${it.f1()}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = { save() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Text(if (justSaved) "Saved" else "Save profile")
                }
                Text(
                    "Stored on this phone only. Not sent to Gemini or USDA.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        GoalsCard(goalStore)

        Spacer(Modifier.height(24.dp))

        // connectors
        Text("Connectors", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Services this app can use. Connect or disconnect any time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
        )
        HealthConnectorCard()
        Spacer(Modifier.height(12.dp))
        BackupCard(db, goalStore)
        Spacer(Modifier.height(12.dp))
        DataCard(db)
        Spacer(Modifier.height(24.dp))
    }
}
