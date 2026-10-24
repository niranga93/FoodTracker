package com.example.nourio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nourio.ai.AiProgress
import com.example.nourio.ai.AiStage
import com.example.nourio.ai.AiStatus
import kotlinx.coroutines.delay

/**
 * Live progress of a meal analysis: each step with a tick, spinner or empty circle, which service,
 * model and key is being asked, the time so far, and what happened on the way (busy models, switched
 * keys). Replaces the plain spinner.
 */
@Composable
fun AiProgressView(title: String, photo: Boolean, onCancel: (() -> Unit)?, modifier: Modifier = Modifier) {
    val status by AiProgress.status.collectAsState()
    val s = status ?: AiStatus()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    val cs = MaterialTheme.colorScheme

    Column(modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("${secs(now - s.startedAt)} so far", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        val fraction = when (s.stage) {
            AiStage.PREPARING -> 0.1f
            AiStage.ASKING -> 0.35f
            AiStage.NUTRITION -> 0.7f + 0.25f * (if (s.foodsTotal > 0) s.foodsDone.toFloat() / s.foodsTotal else 0f)
            AiStage.DONE -> 1f
        }
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))

        val steps = listOf(
            AiStage.PREPARING to (if (photo) "Preparing the photo" else "Reading your description"),
            AiStage.ASKING to "Asking ${s.provider.ifBlank { "the AI" }}",
            AiStage.NUTRITION to "Looking up nutrition",
        )
        steps.forEach { (stage, label) ->
            val state = when {
                s.stage.ordinal > stage.ordinal -> 1
                s.stage == stage -> 0
                else -> -1
            }
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 6.dp)) {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    when (state) {
                        1 -> Icon(Icons.Filled.CheckCircle, contentDescription = "Done", tint = cs.primary, modifier = Modifier.size(20.dp))
                        0 -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else -> Box(Modifier.size(12.dp).padding(1.dp)) {
                            androidx.compose.foundation.Canvas(Modifier.size(10.dp)) {
                                drawCircle(cs.outline, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                            }
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        label, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (state == 0) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (state < 0) cs.onSurfaceVariant else cs.onSurface
                    )
                    // Details under the step in progress
                    if (state == 0) when (stage) {
                        AiStage.ASKING -> {
                            val who = listOfNotNull(s.model, s.key?.let { "key $it" }).joinToString("  ·  ")
                            if (who.isNotBlank()) Detail(who)
                            if (s.attempt > 1) Detail("Try ${s.attempt}")
                            Detail("${secs(now - s.stageStartedAt)} waiting for the reply")
                        }
                        AiStage.NUTRITION -> if (s.foodsTotal > 0) Detail("${s.foodsDone} of ${s.foodsTotal} foods")
                        else -> Unit
                    }
                }
            }
        }

        if (s.events.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            s.events.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        }
        if (onCancel != null) {
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel") }
        }
    }
}

@Composable
private fun Detail(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun secs(ms: Long): String = "${(ms.coerceAtLeast(0) / 1000)} s"

/** One line for small waits (coach, assistant): which model and key is being asked, and for how long. */
@Composable
fun AiStatusLine(modifier: Modifier = Modifier) {
    val status by AiProgress.status.collectAsState()
    val s = status ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    val who = listOfNotNull(s.provider, s.model, s.key?.let { "key $it" }).joinToString("  ·  ")
    Column(modifier) {
        Text("$who  ·  ${secs(now - s.startedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        s.events.lastOrNull()?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
