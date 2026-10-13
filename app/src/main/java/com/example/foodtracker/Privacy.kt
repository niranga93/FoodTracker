package com.example.foodtracker

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Hosted Privacy Policy URL on GitHub Pages
const val PRIVACY_URL = "https://niranga93.github.io/FoodTracker/privacy.html"

fun openPrivacyPolicy(context: android.content.Context) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)))
}

@Composable
fun PrivacyCard() {
    val context = LocalContext.current
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Privacy", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Your food log and profile stay on this phone. Meal photos and the AI coach's daily " +
                    "summary go to Google Gemini when you use them. Food names go to USDA, and barcodes " +
                    "go to Open Food Facts. Backup to Google Drive is optional.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            Button(onClick = { openPrivacyPolicy(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("Read the privacy policy")
            }
        }
    }
}
