package com.example.foodtracker

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Hosted Privacy Policy URL on GitHub Pages
const val PRIVACY_URL = "https://niranga93.github.io/FoodTracker/"

fun openPrivacyPolicy(context: android.content.Context) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)))
}

@Composable
fun PrivacyCard() {
    val context = LocalContext.current
    WidgetCard("Privacy") {
        Text(
            "Your log stays on this phone. Photos and AI summaries go to Google Gemini when you use them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { openPrivacyPolicy(context) }, modifier = Modifier.fillMaxWidth()) {
            Text("Read the policy")
        }
    }
}
