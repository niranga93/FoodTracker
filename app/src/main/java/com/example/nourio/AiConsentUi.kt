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
                    "You can turn this off any time in Profile > Data & privacy.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { AiConsent.set(context, use, true); onAgree() }) { Text("Allow") } },
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
        AiUse.entries.forEachIndexed { i, use ->
            key(use) {
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
                        androidx.compose.material3.Switch(checked = on, onCheckedChange = { on = it; AiConsent.set(context, use, it) })
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
