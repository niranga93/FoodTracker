package com.example.nourio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.nourio.data.GoalStore
import com.example.nourio.data.MealDb
import com.example.nourio.data.ProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the setup guide is showing. It opens by itself on the first start, and again from Profile. */
object Onboarding {
    private const val DONE = "done"
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private fun prefs(context: Context) = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    fun init(context: Context) {
        val p = prefs(context)
        if (!p.contains(DONE)) {
            // People updating from a version without the guide have already set the app up
            val profile = ProfileStore(context).load()
            val existing = profile.name.isNotBlank() || profile.weightKg > 0 || GoalStore(context).load().kcal > 0 ||
                runCatching { MealDb(context).count() > 0 }.getOrDefault(false)
            p.edit { putBoolean(DONE, existing) }
        }
        _visible.value = !p.getBoolean(DONE, false)
    }

    fun finish(context: Context) {
        prefs(context).edit { putBoolean(DONE, true) }
        _visible.value = false
    }

    fun replay() { _visible.value = true }
}

private class GuideStep(
    val icon: ImageVector?,          // null: the app logo
    val title: String,
    val intro: String,
    val why: String? = null,
    val content: @Composable ColumnScope.() -> Unit = {},
)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val db = remember { MealDb(context) }
    val goalStore = remember { GoalStore(context) }

    val steps = listOf(
        GuideStep(
            null, "Welcome to Nourio",
            "Track what you eat, see what you burn and learn what works for you. This short guide sets up " +
                "the app one step at a time. Every step is optional: tap Next to skip one, and change anything " +
                "later in Profile.",
        ) { WelcomeFeatures() },
        GuideStep(
            Icons.Filled.Refresh, "Back up and restore",
            "Used Nourio before? Restore your meals, weigh-ins and goals now, from Google Drive or a backup " +
                "file. New here? Connect Google Drive to keep a copy of your log, or skip this step.",
            "Google Drive: the backup goes into a private app folder in your own Drive, which only Nourio can " +
                "read. Nourio never sees your other files.",
        ) { BackupCard(db, goalStore); BackupFileCard(db, goalStore) },
        GuideStep(
            Icons.Filled.Person, "About you",
            "Your height, weight, age and sex let Nourio estimate how many calories your body burns each day.",
            "These numbers feed the calorie budget, the weight outlook and the protein guideline. " +
                "They stay on this phone. Your name is only used to greet you.",
        ) { PersonalCard(autoSave = true) },
        GuideStep(
            Icons.Filled.DateRange, "Your target",
            "Want to lose or gain weight? Set a target and a pace, and your daily calories are worked out for " +
                "you. Leave it empty to keep your weight steady.",
        ) { TargetCard(autoSave = true) },
        GuideStep(
            Icons.AutoMirrored.Filled.List, "Daily limits",
            "Set the most you want of calories, added sugar, fat and sodium, and the least you want of protein and " +
                "fiber. Tap \"Use recommended values\" for limits worked out for you.",
            "Your daily report card grades each day against these limits.",
        ) { GoalsCard(goalStore, db, autoSave = true) },
        GuideStep(
            Icons.Filled.ShoppingCart, "Diet and allergies",
            "Pick your diet and anything you avoid. Nourio warns you when a food you log doesn't fit.",
        ) { DietCard() },
        GuideStep(
            Icons.Filled.Favorite, "Health Connect",
            "Connect Health Connect to read the steps and calories burned that your phone and watch record.",
            "Steps and calories burned make your calorie budget match your real day. You can also let Nourio " +
                "read sleep, heart rate, workouts and other vitals for a more accurate burn, and share your " +
                "meals, water and weight with other health apps. Android asks you for each type, and you can " +
                "allow only some of them.",
        ) { HealthConnectorCard(); HealthExtrasCard() },
        GuideStep(
            Icons.Filled.Star, "AI food analysis",
            "Take a photo of a meal or describe it in words, and Gemini AI works out the food and its " +
                "nutrition. The AI coach and shared recipe links use it too.",
            "Gemini needs a free key from Google AI Studio. Tap \"Get a free key\", copy the key and come " +
                "back: Nourio offers to paste it. Without a key you can still scan barcodes and add food by hand.",
        ) { GeminiKeyCard() },
        GuideStep(
            Icons.Filled.Notifications, "Permissions",
            "Nourio asks for these two only when you need them. Allow them now, or later when you first use them.",
        ) { PermissionsStep() },
        GuideStep(
            Icons.Filled.CheckCircle, "You're all set",
            "Here's where to find everything.",
        ) { Tour() },
    )

    var index by rememberSaveable { mutableIntStateOf(0) }
    val last = steps.lastIndex
    BackHandler(enabled = index > 0) { index-- }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Step ${index + 1} of ${steps.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (index < last) TextButton(onClick = onDone) { Text("Skip setup") }
            else Spacer(Modifier.height(48.dp))
        }
        LinearProgressIndicator(
            progress = { (index + 1f) / steps.size },
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )

        AnimatedContent(
            targetState = index,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally { it * dir / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it * dir / 4 } + fadeOut())
            },
            label = "guide",
            modifier = Modifier.weight(1f)
        ) { i ->
            val step = steps[i]
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StepHeader(step)
                step.why?.let { WhyBox(it) }
                step.content(this)
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (index > 0) OutlinedButton(onClick = { index-- }, modifier = Modifier.weight(1f).height(50.dp)) {
                Text("Back")
            }
            Button(
                onClick = { if (index < last) index++ else onDone() },
                modifier = Modifier.weight(if (index > 0) 2f else 1f).height(50.dp)
            ) {
                Text(
                    when (index) {
                        0 -> "Get started"
                        last -> "Start using Nourio"
                        else -> "Next"
                    }
                )
            }
        }
    }
}

@Composable
private fun StepHeader(step: GuideStep) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (step.icon == null) AppLogo(88.dp)
        else Box(
            Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(step.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
        }
        Text(step.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(
            step.intro,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun WhyBox(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Column {
            Text("Why this is needed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WelcomeFeatures() {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            FeatureRow(Icons.Filled.Add, "Log food your way", "Photo, words, barcode, search or a recipe link")
            FeatureRow(Icons.Filled.Favorite, "See what you burn", "Steps and calories from Health Connect")
            FeatureRow(Icons.Filled.ThumbUp, "A report card every day", "Grades for calories, protein, fiber, water and steps")
            FeatureRow(Icons.Filled.Star, "Tips from your own data", "Find what affects your weight, sleep and energy")
        }
    }
    Text(
        "Your log stays on this phone. Nothing is shared unless you connect a service in this guide.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun Tour() {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            FeatureRow(Icons.Filled.Add, "The + button", "Add a meal: photo, describe or speak, scan a barcode, or add manually")
            FeatureRow(Icons.Filled.Home, "Home", "Today's report card, your calorie budget, steps and the week ahead. Pull down to refresh.")
            FeatureRow(Icons.AutoMirrored.Filled.List, "Intake", "Every meal and nutrient for the day, and your history")
            FeatureRow(Icons.Filled.Star, "Burned", "Calories burned, steps, workouts, sleep and heart")
            FeatureRow(Icons.Filled.Person, "Profile", "Weight journey, insights and daily tips, goals, connections and your data")
            FeatureRow(Icons.Filled.Share, "Share to Nourio", "Share a recipe link or a food photo from another app to log it")
        }
    }
    Text(
        "To see this guide again, open Profile > Setup guide.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

// ---------------------------------------------------------------- permissions

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun PermissionsStep() {
    val context = LocalContext.current
    val notifyNeedsAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var camera by remember { mutableStateOf(granted(context, Manifest.permission.CAMERA)) }
    var notify by remember {
        mutableStateOf(!notifyNeedsAsk || granted(context, Manifest.permission.POST_NOTIFICATIONS))
    }
    var asked by remember { mutableStateOf(setOf<String>()) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        camera = it; asked = asked + "camera"
    }
    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notify = it; asked = asked + "notify"
    }

    // Re-check after the user comes back from Android settings
    val owner = context as LifecycleOwner
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                camera = granted(context, Manifest.permission.CAMERA)
                if (notifyNeedsAsk) notify = granted(context, Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    PermissionCard(
        title = "Camera",
        why = "To photograph a meal for AI analysis and to scan barcodes on packaged food. The photo is " +
            "only sent to Gemini when you analyse it. Without the camera you can pick photos from your gallery.",
        allowed = camera,
        declined = "camera" in asked,
    ) { cameraLauncher.launch(Manifest.permission.CAMERA) }
    PermissionCard(
        title = "Notifications",
        why = "For a daily tip at 9:00 based on your own data, and to tell you when a photo saved while " +
            "offline has been analysed. The text is hidden on the lock screen.",
        allowed = notify,
        declined = "notify" in asked,
    ) { if (notifyNeedsAsk) notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}

@Composable
private fun PermissionCard(title: String, why: String, allowed: Boolean, declined: Boolean, onAllow: () -> Unit) {
    val context = LocalContext.current
    WidgetCard(title, trailing = { StatusChip(if (allowed) "Allowed" else "Not allowed", allowed) }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!allowed) {
                if (declined) {
                    // Android stops showing the request after it has been declined: send the user to settings
                    Text(
                        "Not allowed. You can allow it any time in Android settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open settings") }
                } else {
                    Button(onClick = onAllow, modifier = Modifier.fillMaxWidth()) { Text("Allow ${title.lowercase()}") }
                }
            }
        }
    }
}
