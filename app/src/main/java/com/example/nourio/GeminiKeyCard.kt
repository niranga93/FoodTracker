package com.example.nourio

import androidx.core.net.toUri
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.nourio.ai.GeminiKeys
import com.example.nourio.ai.GeminiRouter
import com.example.nourio.ai.KeyStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val STUDIO_URL = "https://aistudio.google.com/apikey"

private fun looksLikeKey(s: String): Boolean {
    val t = s.trim()
    return t.startsWith("AIza") && t.length in 30..60 && !t.contains(' ')
}

private fun readClipboard(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

@Composable
fun GeminiKeyCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var slots by remember { mutableStateOf(GeminiKeys.slots()) }
    var statuses by remember { mutableStateOf(mapOf<String, KeyStatus>()) }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var awaiting by remember { mutableStateOf(false) }       // the user went to AI Studio to create a key
    var copiedKey by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(slots) { models = runCatching { GeminiRouter.models() }.getOrDefault(emptyList()) }

    fun saveAndTest(raw: String) {
        val key = raw.trim()
        scope.launch {
            busy = true
            message = null
            val status = GeminiKeys.test(key)
            when {
                status == KeyStatus.INVALID -> message = "Google rejected this key."
                status == KeyStatus.OFFLINE -> message = "Couldn't reach Google. Check your connection."
                !GeminiKeys.save(key) -> message = "Already saved, or all ${GeminiKeys.SLOTS} slots are used."
                else -> {
                    slots = GeminiKeys.slots()
                    statuses = statuses + (key to status)
                    input = ""
                    message = if (status == KeyStatus.OK) "Key works." else "Saved. It's at its limit for now."
                }
            }
            busy = false
        }
    }

    // Back from AI Studio: offer the key the user just copied
    val owner = context as LifecycleOwner
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaiting) {
                scope.launch {
                    delay(500)          // the clipboard is only readable once the app has focus
                    readClipboard(context)?.takeIf { looksLikeKey(it) }?.let { copiedKey = it.trim() }
                    awaiting = false
                }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    copiedKey?.let { k ->
        AlertDialog(
            onDismissRequest = { copiedKey = null },
            title = { Text("Use the copied key?") },
            text = { Text(GeminiKeys.mask(k)) },
            confirmButton = {
                TextButton(onClick = { copiedKey = null; saveAndTest(k) }) { Text("Save & test") }
            },
            dismissButton = { TextButton(onClick = { copiedKey = null }) { Text("No") } }
        )
    }

    val hasUserKey = slots.any { it != null }
    WidgetCard(
        "Your Gemini key",
        trailing = {
            StatusChip(
                if (hasUserKey) "Key saved" else if (GeminiKeys.hasAny()) "Dev key" else "Needed",
                hasUserKey
            )
        }
    ) {
        Text(
            "Photo and text analysis and the coach use your own free key. Add up to ${GeminiKeys.SLOTS} keys: " +
                "requests take turns across them, so you reach the daily free limit less often.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                awaiting = true
                context.startActivity(Intent(Intent.ACTION_VIEW, STUDIO_URL.toUri()))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Get a free key") }
        Expandable("How it works") {
            Text(
                "1. Tap Get a free key and sign in with Gmail.\n" +
                    "2. Tap Create API key, then copy it.\n" +
                    "3. Come back here and tap Use the copied key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it; message = null },
            label = { Text("Paste a key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { readClipboard(context)?.let { input = it.trim() } },
                modifier = Modifier.weight(1f)
            ) { Text("Paste") }
            Button(
                onClick = { saveAndTest(input) },
                enabled = !busy && input.trim().length >= 20,
                modifier = Modifier.weight(1f)
            ) { Text(if (busy) "Testing…" else "Save & test") }
        }
        if (input.isNotBlank() && !input.trim().startsWith("AIza")) {
            Text(
                "Gemini keys usually start with AIza.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        // Saved keys
        slots.forEachIndexed { i, key ->
            if (key != null) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(GeminiKeys.mask(key), style = MaterialTheme.typography.bodyMedium)
                        statuses[key]?.let {
                            Text(
                                when (it) {
                                    KeyStatus.OK -> "Works"
                                    KeyStatus.LIMITED -> "At its limit"
                                    KeyStatus.INVALID -> "Rejected"
                                    KeyStatus.OFFLINE -> "Offline"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    TextButton(onClick = {
                        scope.launch { statuses = statuses + (key to GeminiKeys.test(key)) }
                    }) { Text("Test") }
                    TextButton(onClick = {
                        GeminiKeys.remove(i)
                        slots = GeminiKeys.slots()
                    }) { Text("Remove") }
                }
            }
        }
        if (hasUserKey) {
            Text(
                "${GeminiKeys.count()} of ${GeminiKeys.SLOTS} keys saved",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 10.dp)
            )
            if (models.isNotEmpty()) {
                Text(
                    "Best free model first: " + models.joinToString(", then "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Text(
                "Free limits are per Google Cloud project, so keys made in the same project share one limit. " +
                    "Create each key in its own project to add more.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                "Stored encrypted on this phone. Not included in backups.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
