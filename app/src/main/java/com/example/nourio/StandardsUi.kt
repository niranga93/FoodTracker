package com.example.nourio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nourio.ai.AiConsent
import com.example.nourio.ai.AiUse
import com.example.nourio.plan.Proposal
import com.example.nourio.plan.Standards
import com.example.nourio.plan.StandardsChecker
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Keys that feed the daily limits; approving one of these recalculates the limits. */
private val LIMIT_KEYS = setOf(
    "sodium_mg", "fat_share", "sat_fat_share", "added_sugar_share", "added_sugar_cap_male_g", "added_sugar_cap_female_g",
    "fiber_per_1000_kcal_g", "fiber_min_g", "fiber_max_g", "protein_per_kg_adult", "protein_per_kg_older",
    "protein_older_age", "protein_per_kg_losing", "protein_per_kg_gaining", "reference_kcal", "amdr_protein"
)

/**
 * Changed health guidelines waiting for the person's answer, and the monthly check itself.
 * [onLimitsChanged] runs after an approval that affects the daily limits, to recalculate them.
 */
@Composable
fun GuidelineUpdates(onLimitsChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf(Standards.pending(context)) }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var askConsent by remember { mutableStateOf(false) }
    val aiReady = rememberAiAvailable()

    fun checkNow() {
        checking = true; status = "Checking official sources…"
        scope.launch {
            status = runCatching { StandardsChecker.check(context) }.fold(
                { fresh -> if (fresh.isEmpty()) "No guideline has changed." else "${fresh.size} change${if (fresh.size > 1) "s" else ""} to review." },
                { "Couldn't check: ${it.message}" }
            )
            pending = Standards.pending(context)
            checking = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pending.forEach { p ->
            ProposalCard(p,
                onApprove = {
                    val ok = Standards.approve(context, p)
                    pending = Standards.pending(context)
                    status = if (!ok) "That change no longer fits the other guidelines, so it was dropped."
                    else if (p.key in LIMIT_KEYS) { onLimitsChanged(); "Updated. Your daily limits were recalculated." }
                    else "Updated."
                },
                onReject = {
                    Standards.reject(context, p)
                    pending = Standards.pending(context)
                    status = "Kept the current value. This suggestion won't be shown again."
                })
        }

        val last = Standards.lastChecked(context)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                if (last > 0) "Guidelines last checked ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(last))}"
                else "Guidelines are checked once a month",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (aiReady) TextButton(enabled = !checking, onClick = {
                if (AiConsent.isGranted(context, AiUse.STANDARDS)) checkNow() else askConsent = true
            }) { Text("Check now") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }

    if (askConsent) AiConsentDialog(AiUse.STANDARDS,
        onAgree = { AiConsent.set(context, AiUse.STANDARDS, true); askConsent = false; checkNow() },
        onDismiss = { askConsent = false })
}

@Composable
private fun ProposalCard(p: Proposal, onApprove: () -> Unit, onReject: () -> Unit) {
    val uri = LocalUriHandler.current
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Guideline changed: ${Standards.label(p.key)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Current value: ${Standards.currentText(p.key)}", style = MaterialTheme.typography.bodyMedium)
            Text("New recommendation: ${Standards.proposedText(p)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (p.reason.isNotBlank()) Text(p.reason, style = MaterialTheme.typography.bodySmall)
            if (p.quote.isNotBlank()) Text("“${p.quote}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { runCatching { uri.openUri(p.sourceUrl) } }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("${p.sourceTitle}${if (p.published.isNotBlank()) " (${p.published})" else ""}", style = MaterialTheme.typography.labelMedium)
            }
            Text("Found by AI. Check the source before approving.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApprove) { Text("Approve") }
                OutlinedButton(onClick = onReject) { Text("Reject") }
            }
        }
    }
}
