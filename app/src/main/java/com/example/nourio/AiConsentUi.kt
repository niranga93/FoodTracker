package com.example.nourio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nourio.ai.AiConsent
import com.example.nourio.ai.AiUse

/**
 * Asked before the first time [use] sends anything to the AI service: what is sent, to whom, why, and where to change
 * it. Agreeing turns its switch on in Data & privacy; nothing is sent if the user declines.
 */
@Composable
fun AiConsentDialog(use: AiUse, onAgree: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Allow ${use.title.lowercase()}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(use.purpose, style = MaterialTheme.typography.bodyMedium)
                Column {
                    val p = com.example.nourio.ai.AiSettings.provider
                    Text("Sent to ${p.company} (${p.label})", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(use.sends, style = MaterialTheme.typography.bodyMedium)
                }
                Text(AiUse.common(com.example.nourio.ai.AiSettings.provider), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "\"Always allow\" covers every AI feature (photos, meal descriptions, coach, assistant and guideline " +
                        "updates), so Nourio won't ask again. You can turn it off any time in Profile > Data & privacy.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = { AiConsent.set(context, use, true); onAgree() }) { Text("Only this") }
                androidx.compose.material3.Button(onClick = { AiConsent.setAll(context, true); onAgree() }) { Text("Always allow") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } }
    )
}

/** Data & privacy: one switch per AI use, each saying exactly what it sends. */
@Composable
fun AiConsentCard() {
    val context = LocalContext.current
    WidgetCard("AI and your data") {
        Text(
            "Nourio asks before it sends anything to your AI service (${com.example.nourio.ai.AiSettings.provider.label}). Each switch below is your consent for one " +
                "feature; turn it off and that feature stops sending until you allow it again.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        // Bumped whenever a switch changes, so "always allow" and the per-feature switches stay in step
        var version by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        var all by remember { mutableStateOf(AiConsent.isAllGranted(context)) }
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text("Always allow AI features", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Every feature below may send its data without asking. Turn off to be asked again for each one.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.Switch(checked = all, onCheckedChange = {
                all = it; AiConsent.setAll(context, it); version++
            })
        }
        HorizontalDivider()
        AiUse.entries.forEachIndexed { i, use ->
            key(use, version) {
                var on by remember { mutableStateOf(AiConsent.isGranted(context, use)) }
                Column(Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(use.title, style = MaterialTheme.typography.bodyLarge)
                            Text("Sends: ${use.sends}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.Switch(checked = on, onCheckedChange = {
                            on = it; AiConsent.set(context, use, it)
                            all = AiConsent.isAllGranted(context)
                        })
                    }
                    if (i < AiUse.entries.lastIndex) HorizontalDivider()
                }
            }
        }
        Text(
            AiUse.common(com.example.nourio.ai.AiSettings.provider), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
        )
    }
}
