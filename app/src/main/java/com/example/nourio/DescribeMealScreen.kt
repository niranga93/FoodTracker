package com.example.nourio

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

private val EXAMPLES = listOf(
    "2 scrambled eggs, 2 slices of toast with butter, a latte",
    "Chicken caesar salad and a can of coke",
    "A large bowl of spaghetti bolognese"
)

/** Type or speak what you ate; the review screen then works it out like a photo. */
@Composable
fun DescribeMealScreen(initial: String, onBack: () -> Unit, onSubmit: (String) -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf(initial) }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!heard.isNullOrBlank()) text = if (text.isBlank()) heard else "${text.trimEnd()}, $heard"
        }
    }

    fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "What did you eat?")
        try {
            speech.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Speech input isn't available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        ScreenTopBar("Describe your meal", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("What did you eat?") },
                placeholder = { Text(EXAMPLES[0]) },
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = ::listen, modifier = Modifier.fillMaxWidth()) {
                Text("Speak instead")
            }
            Text(
                "Include amounts when you know them (\"2 eggs\", \"a large bowl\", \"200 g\"). " +
                    "Anything without an amount is counted as a typical portion. " +
                    "Your description is sent to Google Gemini for analysis.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Examples", style = MaterialTheme.typography.titleSmall)
            EXAMPLES.forEach { ex ->
                SuggestionChip(onClick = { text = ex }, label = { Text(ex) })
            }
        }
        Button(
            onClick = { onSubmit(text.trim()) },
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) { Text("Analyze") }
    }
}
