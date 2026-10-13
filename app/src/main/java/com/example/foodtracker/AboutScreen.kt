package com.example.foodtracker

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Calendar

private val FEATURES = listOf(
    "Photo, barcode, and manual logging",
    "Calories, nutrients, and daily limits",
    "Steps, sleep, heart rate, and weight from Health Connect",
    "Weight outlook from what you eat and burn",
    "Optional backup to your Google Drive"
)

private val SOURCES = listOf(
    "USDA FoodData Central" to "Nutrition values for common foods",
    "Open Food Facts" to "Packaged products, under the Open Database License",
    "Google Gemini" to "Photo and text analysis, coach notes",
    "Health Connect" to "Steps, activity, sleep, heart rate, workouts, and weight"
)

private val LICENSES = listOf(
    "Jetpack Compose and AndroidX" to "Apache License 2.0",
    "Kotlin and Coroutines" to "Apache License 2.0",
    "OkHttp" to "Apache License 2.0",
    "Health Connect client" to "Apache License 2.0",
    "Google ML Kit, barcode scanning" to "ML Kit Terms of Service",
    "Google Play services" to "Android SDK Terms of Service",
    "Google Gemini API" to "Gemini API Additional Terms",
    "USDA FoodData Central" to "Public domain (CC0 1.0)",
    "Open Food Facts" to "ODbL 1.0"
)

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
fun AboutContent() {
    val context = LocalContext.current
    val v = remember { appVersion(context) }

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppLogo(72.dp)
            Spacer(Modifier.height(12.dp))
            Text("Fuelo", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Muted("Fuel in, fuel out.")
            Spacer(Modifier.height(8.dp))
            Pill("Version ${v.name} (${v.code})")
        }
    }

    WidgetCard("About the app") {
        Text(
            "Track what you eat and burn in one place. Log meals by photo, barcode, search, or sharing, " +
                "then see calories, nutrients, activity, and weight trends.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        FEATURES.forEach { Muted("•  $it", Modifier.padding(vertical = 1.dp)) }
    }

    AppCard(Modifier.fillMaxWidth()) {
        Column {
            NavRow("Privacy policy", "How your data is used") { openPrivacyPolicy(context) }
            if (TERMS_URL.isNotBlank()) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                NavRow("Terms of use", "The rules for using the app") { openUrl(context, TERMS_URL) }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            NavRow("Contact support", SUPPORT_EMAIL) { sendSupportEmail(context) }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            NavRow("Rate this app", "Opens Google Play") { openStorePage(context) }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            NavRow("Share the app", "Send a link") { shareApp(context) }
        }
    }

    WidgetCard("Data sources") {
        SOURCES.forEachIndexed { i, (name, note) ->
            Column(Modifier.padding(top = if (i == 0) 0.dp else 8.dp)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Muted(note)
            }
        }
    }

    WidgetCard("Good to know") {
        Muted(
            "Calories, burn, and weight outlook are estimates, not medical advice. Allergen warnings are " +
                "best effort, so check labels. For health decisions, ask a doctor or dietitian."
        )
    }

    WidgetCard("Open-source licenses") {
        Expandable("Show") {
            LICENSES.forEach { (name, license) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.6f))
                    Muted(license, Modifier.weight(0.4f))
                }
            }
        }
    }

    WidgetCard("Developer") {
        Text(DEVELOPER_NAME, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Muted(SUPPORT_EMAIL)
        if (WEBSITE_URL.isNotBlank()) Muted(WEBSITE_URL)
        Spacer(Modifier.height(8.dp))
        Muted("© ${Calendar.getInstance().get(Calendar.YEAR)} $DEVELOPER_NAME")
    }
}
