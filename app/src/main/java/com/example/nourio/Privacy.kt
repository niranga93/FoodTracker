package com.example.nourio

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// PRIVACY_URL and openPrivacyPolicy live in AppInfo.kt

@Composable
fun PrivacyCard() {
    val context = LocalContext.current
    WidgetCard("Privacy") {
        Text(
            "Your log stays on this phone. Photos, meal descriptions, shared text and coach notes go to " +
                "Google Gemini when you use them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { openPrivacyPolicy(context) }, modifier = Modifier.fillMaxWidth()) {
            Text("Read the policy")
        }
    }
}
