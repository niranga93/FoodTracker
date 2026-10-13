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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.nourio.health.DayActivity
import com.example.nourio.health.HealthPrefs
import com.example.nourio.health.HealthRepo
import kotlinx.coroutines.launch
import java.text.NumberFormat

/** Shows nothing unless Health Connect is available, switched on in this app, and fully permitted. */
@Composable
fun ActivityCard(dayOffset: Int) {
    val context = LocalContext.current
    val available = remember {
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }
    if (available) ActivityContent(dayOffset)
}

@Composable
private fun ActivityContent(dayOffset: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { HealthConnectClient.getOrCreate(context) }
    val prefs = remember { HealthPrefs(context) }
    var connected by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf<DayActivity?>(null) }
    var failed by remember { mutableStateOf(false) }

    suspend fun recheck() {
        val permitted = runCatching { HealthRepo.hasPermissions(client) }.getOrDefault(false)
        connected = prefs.enabled && permitted
        if (!connected) {
            data = null
            failed = false
        }
    }

    LaunchedEffect(Unit) { recheck() }

    val owner = context as LifecycleOwner
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { recheck() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(connected, dayOffset) {
        if (connected) {
            failed = false
            data = null
            runCatching { HealthRepo.readDay(client, dayOffset) }
                .onSuccess { data = it }
                .onFailure { failed = true }
        }
    }

    if (connected) {
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("From Health Connect", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                val d = data
                when {
                    failed -> Text(
                        "Could not read Health Connect data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    d == null -> Text(
                        "Loading…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile("Steps", NumberFormat.getIntegerInstance().format(d.steps), Modifier.weight(1f))
                            StatTile(
                                "Active",
                                if (d.activeKcal > 0) "${d.activeKcal.f0()} kcal" else "—",
                                Modifier.weight(1f)
                            )
                            StatTile(
                                "Total",
                                if (d.totalKcal > 0) "${d.totalKcal.f0()} kcal" else "—",
                                Modifier.weight(1f)
                            )
                        }
                        Text(
                            if (d.activeKcal <= 0 && d.steps > 0)
                                "Exactly as Health Connect reports it. It has no active calories, so the " +
                                    "calories burned above add an estimate for your steps."
                            else "Exactly as Health Connect reports it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
