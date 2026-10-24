package com.example.nourio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.nourio.ai.AiSettings
import com.example.nourio.ai.GeminiKeys
import com.example.nourio.ai.Llm
import com.example.nourio.ai.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connections: which AI service does the analysis, the coach and the assistant. Gemini keeps its own
 * card (several free keys, automatic model choice); every other service takes one key and a model.
 */
@Composable
fun AiServiceCard() {
    val context = LocalContext.current
    AiSettings.init(context)
    var provider by remember { mutableStateOf(AiSettings.provider) }
    var confirm by remember { mutableStateOf<Provider?>(null) }
    var menu by remember { mutableStateOf(false) }

    // Changing company changes who receives the data, so it's confirmed and every consent is asked again
    confirm?.let { next ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Switch to ${next.label}?") },
            text = {
                Text(
                    "Meal analysis, the coach and the assistant will send their requests to ${next.company} instead of " +
                        "${provider.company}. Your four AI consents are turned off, and each feature asks again before it " +
                        "sends anything to ${next.company}."
                )
            },
            confirmButton = { TextButton(onClick = { AiSettings.choose(context, next); provider = next; confirm = null }) { Text("Switch") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }

    WidgetCard("AI service") {
        Text(
            "Choose which AI analyzes your meals and writes the coach notes. You use your own key and pay the " +
                "service directly; Nourio never sees your key outside this phone.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Box {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(provider.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(provider.company, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose AI service")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Provider.entries.forEach { p ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(p.label, fontWeight = if (p == provider) FontWeight.Bold else FontWeight.Normal)
                                Text(
                                    p.company + if (!p.photos) "  ·  text only" else "",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = { menu = false; if (p != provider) confirm = p }
                    )
                }
            }
        }
        Text(provider.note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }

    if (provider == Provider.GEMINI) GeminiKeyCard() else ProviderKeyCard(provider)
}

/** One key and one model for a service other than Gemini. */
@Composable
private fun ProviderKeyCard(p: Provider) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember(p) { mutableStateOf(AiSettings.key(p)) }
    var input by remember(p) { mutableStateOf("") }
    var model by remember(p) { mutableStateOf(AiSettings.model(p) ?: "") }
    var models by remember(p) { mutableStateOf(p.suggested) }
    var message by remember(p) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    // Checking a key = asking the service for its model list, which costs nothing
    fun check(key: String, saveIt: Boolean) {
        busy = true; message = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { Llm.listModels(p, key) } }.fold(
                onSuccess = { list ->
                    if (saveIt) { AiSettings.setKey(context, p, key); saved = key; input = "" }
                    com.example.nourio.ai.ModelCaps.remember(context, p, list)
                    models = (p.suggested + list.map { it.id }).distinct()
                    if (model.isBlank()) models.firstOrNull()?.let { model = it; AiSettings.setModel(context, p, it) }
                    val photoCount = list.count { it.images == true }
                    message = "Key works. ${list.size} chat models, $photoCount of them read photos."
                },
                onFailure = { message = it.message ?: "Couldn't reach ${p.label}." }
            )
            busy = false
        }
    }

    WidgetCard("Your ${p.label} key", trailing = { StatusChip(if (saved != null) "Key saved" else "Needed", saved != null) }) {
        val key = saved
        if (key == null) {
            OutlinedTextField(
                value = input, onValueChange = { input = it.trim() }, singleLine = true,
                label = { Text("Paste your API key") }, supportingText = { Text(p.keyHint) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { check(input, saveIt = true) }, enabled = input.length >= 10 && !busy) { Text("Check and save") }
                OutlinedButton(onClick = { openUrl(context, p.keyUrl) }) { Text("Get a key") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(GeminiKeys.mask(key), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { check(key, saveIt = false) }, enabled = !busy) { Text("Test") }
                TextButton(onClick = { AiSettings.setKey(context, p, null); saved = null; message = null }) { Text("Remove") }
            }

            // The model: suggested ones, the ones the key can use, or any ID typed in
            Text("Model", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            Box {
                OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(model.ifBlank { "Choose a model" }, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose model")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                    if (models.isEmpty()) DropdownMenuItem(text = { Text("Tap Load models first") }, onClick = { menu = false })
                    models.forEach { m ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(m, fontWeight = if (m == model) FontWeight.Bold else FontWeight.Normal)
                                    Text(
                                        com.example.nourio.ai.ModelCaps.label(p, m), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = { model = m; AiSettings.setModel(context, p, m); menu = false }
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { check(key, saveIt = false) }, enabled = !busy) { Text("Load models") }
                Spacer(Modifier.weight(1f))
                var typing by remember { mutableStateOf(false) }
                TextButton(onClick = { typing = !typing }) { Text("Type a model ID") }
                if (typing) {
                    var typed by remember { mutableStateOf(model) }
                    AlertDialog(
                        onDismissRequest = { typing = false },
                        title = { Text("Model ID") },
                        text = { OutlinedTextField(typed, { typed = it.trim() }, singleLine = true, label = { Text("For example ${p.suggested.firstOrNull() ?: "the exact ID from ${p.company}"}") }) },
                        confirmButton = { TextButton(onClick = { model = typed; AiSettings.setModel(context, p, typed); typing = false }, enabled = typed.isNotBlank()) { Text("Use") } },
                        dismissButton = { TextButton(onClick = { typing = false }) { Text("Cancel") } }
                    )
                }
            }
            val photoModel = com.example.nourio.ai.ModelCaps.photoModel(p, model.ifBlank { null })
            Text(
                when {
                    !p.photos -> "${p.label} reads text only: describe meals in words. Meal photos are turned off."
                    model.isBlank() -> "Choose a model. Ones marked \"Photos and text\" can analyze meal photos."
                    photoModel == model -> "$model: photos and text."
                    photoModel != null -> "$model reads text only. Meal photos go to $photoModel instead."
                    else -> "$model reads text only, and none of your models are known to read photos. " +
                        "Tap Load models, or describe meals in words."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
    }
}
