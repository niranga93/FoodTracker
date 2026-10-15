package com.example.nourio

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Toast
import android.content.Intent
import android.os.Build
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import androidx.activity.viewModels
import com.example.nourio.pending.AnalyzePendingWorker
import com.example.nourio.pending.PendingMeal
import com.example.nourio.pending.PendingMeals
import com.example.nourio.pending.PendingStatus
import com.example.nourio.pending.dayOffsetOf
import com.example.nourio.pending.isOnline
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.example.nourio.ai.ImageUtil
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.nourio.data.AI_ESTIMATE
import com.example.nourio.data.FoodItem
import com.example.nourio.data.GoalStore
import com.example.nourio.data.MealDb
import com.example.nourio.data.MealPhotos
import com.example.nourio.data.PortionStore
import com.example.nourio.data.Totals
import com.example.nourio.data.totals
import com.example.nourio.nutrition.NutritionLookup
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.nourio.backup.BackupService
import com.example.nourio.health.HealthSync
import androidx.compose.material.icons.filled.Person

class MainActivity : ComponentActivity() {
    // Same instance FoodApp gets from viewModel(), so notification taps can navigate
    private val nav: NavViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NutritionLookup.init(applicationContext)
        PortionStore.init(applicationContext)
        BackupService.init(applicationContext)
        com.example.nourio.energy.EnergyRepo.init(applicationContext)
        com.example.nourio.insights.InsightWorker.schedule(applicationContext)     // daily tip, keeps an existing schedule
        com.example.nourio.ai.GeminiKeys.init(applicationContext)
        Onboarding.init(applicationContext)                                        // the setup guide on the first start
        com.example.nourio.achievements.Achievements.init(applicationContext)
        if (savedInstanceState == null) {
            openPendingFrom(intent)
            handleShare(intent)
        }
        // Photos queued offline get another chance whenever the app starts
        lifecycleScope.launch(Dispatchers.IO) {
            if (PendingMeals.all(this@MainActivity).any { it.status == PendingStatus.WAITING }) {
                AnalyzePendingWorker.enqueue(this@MainActivity)
            }
        }

        val action = intent?.action
        if (action == "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" ||
            action == "android.intent.action.VIEW_PERMISSION_USAGE"
        ) {
            openPrivacyPolicy(this)
        }

        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var showSplash by rememberSaveable { mutableStateOf(true) }
                    LaunchedEffect(Unit) {
                        delay(200)
                        showSplash = false
                    }
                    val guide by Onboarding.visible.collectAsStateWithLifecycle()
                    val page = when { showSplash -> 0; guide -> 1; else -> 2 }
                    Crossfade(targetState = page, label = "splash") {
                        when (it) {
                            0 -> SplashScreen()
                            1 -> OnboardingScreen(onDone = { Onboarding.finish(applicationContext) })
                            else -> FoodApp()
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openPendingFrom(intent)
        handleShare(intent)
    }

    override fun onStart() {
        super.onStart()
        HealthSync.runAsync(this)           // imports new weights from Health Connect
        // Work out tips while Health Connect can be read (the app is open), for the daily notification
        lifecycleScope.launch { runCatching { com.example.nourio.insights.InsightWorker.refreshTips(applicationContext) } }
    }

    override fun onStop() {
        super.onStop()
        BackupService.autoBackup(this)      // saves anything changed while the app was open
        HealthSync.runAsync(this)           // sends meals, water and weight to Health Connect
        com.example.nourio.reminders.Reminders.schedule(this)   // fasting times follow the meals just logged
    }

    /** A photo, a recipe link or meal text shared from another app opens the review. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val type = intent.type ?: return
        if (!com.example.nourio.ai.GeminiKeys.hasAny()) {
            Toast.makeText(this, "Photo and text sharing needs your free Gemini key", Toast.LENGTH_LONG).show()
            nav.open(Screen.Page("connections"))
            return
        }
        when {
            type.startsWith("image/") -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                } ?: return
                lifecycleScope.launch {
                    val file = withContext(Dispatchers.IO) { ImageUtil.copyToCache(applicationContext, uri) }
                    if (file == null) {
                        Toast.makeText(this@MainActivity, "Could not open that photo", Toast.LENGTH_SHORT).show()
                    } else {
                        ImageUtil.photoDayOffset(file)?.let { nav.setDay(it) }   // preselect the day it was taken
                        nav.open(Screen.Review(file))
                    }
                }
            }
            type == "text/plain" -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                if (text.isNotBlank()) nav.open(Screen.ReviewText(text.take(8000)))
            }
        }
    }

    /** Opens the review for a queued photo when started from its "ready to review" notification. */
    private fun openPendingFrom(intent: Intent?) {
        val id = intent?.getLongExtra(AnalyzePendingWorker.EXTRA_PENDING_ID, -1L) ?: -1L
        if (id > 0) nav.open(Screen.ReviewPending(id))
        // From the daily tip notification. Only this page is allowed, since other apps can start this activity.
        if (intent?.getStringExtra(com.example.nourio.insights.InsightWorker.EXTRA_OPEN_PAGE) == "insights") {
            nav.open(Screen.Page("insights"))
        }
        // From a reminder. Again only a fixed set of places.
        when (intent?.getStringExtra(com.example.nourio.reminders.Reminders.EXTRA_OPEN_TAB)) {
            "intake" -> { nav.open(Screen.Main); nav.setDay(0); nav.selectTab(NavTab.Intake) }
            "home" -> { nav.open(Screen.Main); nav.setDay(0); nav.selectTab(NavTab.Home) }
            "fasting" -> nav.open(Screen.Page("fasting"))
        }
    }
}

@Composable
fun SplashScreen() {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AppLogo(120.dp)
        Spacer(Modifier.height(24.dp))
        Text(
            "Nourio",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "Nourish. Track. Balance.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(40.dp))
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

sealed interface Screen {
    object Main : Screen
    object Camera : Screen
    data class Review(val file: File) : Screen
    data class Describe(val initial: String = "") : Screen
    data class ReviewText(val text: String) : Screen
    data class ReviewPending(val id: Long) : Screen
    object Barcode : Screen
    data class Scanned(val code: String) : Screen
    object Manual : Screen
    data class EditMeal(val id: Long) : Screen
    object History : Screen
    object Favorites : Screen
    data class Page(val id: String) : Screen
}

enum class NavTab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Intake("Intake", Icons.AutoMirrored.Filled.List),
    Burned("Burned", Icons.Filled.Star),
    Profile("Profile", Icons.Filled.Person)
}

@Composable
fun FoodApp(nav: NavViewModel = viewModel()) {
    val context = LocalContext.current
    val db = remember { MealDb(context) }
    val goalStore = remember { GoalStore(context) }
    val state by nav.state.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<Screen?>(null) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val scope = rememberCoroutineScope()
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val file = withContext(Dispatchers.IO) { ImageUtil.copyToCache(context, uri) }
                if (file == null) {
                    Toast.makeText(context, "Could not open that photo", Toast.LENGTH_SHORT).show()
                } else {
                    ImageUtil.photoDayOffset(file)?.let { nav.setDay(it) }   // preselect the day it was taken
                    nav.open(Screen.Review(file))
                }
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        when {
            granted -> pending?.let { nav.open(it) }
            // Without the camera, a photo can still come from the gallery
            pending == Screen.Camera -> galleryLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        pending = null
    }

    fun needKey(): Boolean {
        if (com.example.nourio.ai.GeminiKeys.hasAny()) return false
        Toast.makeText(context, "Add your free Gemini key first", Toast.LENGTH_LONG).show()
        nav.open(Screen.Page("connections"))
        return true
    }

    fun pickFromGallery() {
        if (!needKey()) galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun go(target: Screen) {
        if (hasPermission) nav.open(target)
        else {
            pending = target
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    BackHandler(enabled = state.screen !is Screen.Main || state.tab != NavTab.Home) {
        nav.back()
    }

    // Lets any widget open a Profile page, e.g. the key page from a locked AI card
    CompositionLocalProvider(LocalOpenPage provides { nav.open(Screen.Page(it)) }) {
        when (val s = state.screen) {
            is Screen.Main -> MainScaffold(
                tab = state.tab,
                onTab = nav::selectTab,
                db = db,
                goalStore = goalStore,
                dayOffset = state.dayOffset,
                onDayChange = nav::setDay,
                onPhoto = { if (!needKey()) go(Screen.Camera) },
                onScan = { go(Screen.Barcode) },
                onManual = { nav.open(Screen.Manual) },
                onDescribe = { nav.open(Screen.Describe()) },
                onEditMeal = { nav.open(Screen.EditMeal(it)) },
                onOpenHistory = { nav.open(Screen.History) },
                onOpenPage = { nav.open(Screen.Page(it)) },
                onOpenPending = { nav.open(Screen.ReviewPending(it)) }
            )
            is Screen.History -> HistoryPage(
                db = db,
                goalStore = goalStore,
                onBack = nav::back,
                onOpenDay = nav::openDay
            )
            is Screen.Camera -> CameraScreen(
                onBack = nav::back,
                onGallery = ::pickFromGallery
            ) { nav.open(Screen.Review(it)) }
            is Screen.Review -> ReviewScreen(
                file = s.file,
                text = null,
                db = db,
                initialOffset = state.dayOffset,
                onBack = nav::back,
                onRetake = { nav.open(Screen.Camera) },
                onDone = nav::finishSave
            )
            is Screen.Describe -> DescribeMealScreen(
                initial = s.initial,
                onBack = nav::back,
                onSubmit = { nav.open(Screen.ReviewText(it)) }
            )
            is Screen.ReviewText -> ReviewScreen(
                file = null,
                text = s.text,
                db = db,
                initialOffset = state.dayOffset,
                onBack = nav::back,
                onRetake = { nav.open(Screen.Describe(s.text)) },
                onDone = nav::finishSave
            )
            is Screen.ReviewPending -> {
                val p = remember(s.id) { PendingMeals.get(context, s.id) }
                if (p == null) {
                    // Already saved or discarded (e.g. an old notification)
                    LaunchedEffect(s.id) { nav.back() }
                } else {
                    ReviewScreen(
                        file = p.photo,
                        text = null,
                        db = db,
                        initialOffset = dayOffsetOf(p.mealTs),
                        onBack = nav::back,
                        onRetake = {},
                        onDone = nav::finishSave,
                        pending = p
                    )
                }
            }
            is Screen.Barcode -> BarcodeScreen(
                onBack = nav::back
            ) { nav.open(Screen.Scanned(it)) }
            is Screen.Scanned -> ScannedScreen(
                code = s.code,
                db = db,
                initialOffset = state.dayOffset,
                onBack = nav::back,
                onRescan = { nav.open(Screen.Barcode) },
                onDone = nav::finishSave
            )
            is Screen.Manual -> ManualEntryScreen(
                db = db,
                initialOffset = state.dayOffset,
                onBack = nav::back,
                onFavorites = { nav.open(Screen.Favorites) },
                onDone = nav::finishSave
            )
            is Screen.EditMeal -> EditMealScreen(
                mealId = s.id,
                db = db,
                onBack = nav::back,
                onDone = nav::finishSave
            )
            is Screen.Favorites -> FavoriteMealsScreen(
                db = db,
                initialOffset = state.dayOffset,
                onBack = { nav.open(Screen.Manual) },   // opened from Add manually
                onDone = nav::finishSave
            )
            is Screen.Page -> PageScreen(
                id = s.id,
                db = db,
                goalStore = goalStore,
                onBack = nav::back
            )
        }
    }
}

// ---------------------------------------------------------------- main tabs

@Composable
fun MainScaffold(
    tab: NavTab,
    onTab: (NavTab) -> Unit,
    db: MealDb,
    goalStore: GoalStore,
    dayOffset: Int,
    onDayChange: (Int) -> Unit,
    onPhoto: () -> Unit,
    onScan: () -> Unit,
    onManual: () -> Unit,
    onDescribe: () -> Unit,
    onEditMeal: (Long) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenPage: (String) -> Unit,
    onOpenPending: (Long) -> Unit
) {
    val aiOn = rememberAiAvailable()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = cardContainer(), tonalElevation = 0.dp) {
                NavTab.values().forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { onTab(t) },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        colors = appNavColors()
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == NavTab.Home || tab == NavTab.Intake) {
                AddFab(aiOn, onPhoto, onDescribe, onScan, onManual) { onOpenPage("connections") }
            }
        }
    ) { inner ->
        RefreshBox(topInset = inner.calculateTopPadding()) {
            when (tab) {
                NavTab.Home -> HomeScreen(db, goalStore, dayOffset, onDayChange, onTab, { onOpenPage("settings") }, onOpenPending, inner)
                NavTab.Intake -> IntakeScreen(db, goalStore, dayOffset, onDayChange, onEditMeal, onOpenHistory, inner)
                NavTab.Burned -> BurnedScreen(db, dayOffset, onDayChange, inner)
                NavTab.Profile -> ProfileHub(db, onOpenPage, inner)
            }
        }
    }
}

@Composable
fun AddFab(
    aiEnabled: Boolean,
    onPhoto: () -> Unit,
    onDescribe: () -> Unit,
    onScan: () -> Unit,
    onManual: () -> Unit,
    onSetupKey: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ExtendedFloatingActionButton(
            onClick = { open = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Add meal") },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // Photo and Describe use Gemini, so they are locked until a key is added
            val lockedHint = "Needs a Gemini key"
            DropdownMenuItem(
                enabled = aiEnabled,
                text = { MenuLabel("Photo", if (aiEnabled) "Camera, or pick from gallery" else lockedHint) },
                onClick = { open = false; onPhoto() }
            )
            DropdownMenuItem(
                enabled = aiEnabled,
                text = { MenuLabel("Describe or speak", if (aiEnabled) "Type or say what you ate" else lockedHint) },
                onClick = { open = false; onDescribe() }
            )
            DropdownMenuItem(
                text = { MenuLabel("Scan a barcode", "Packaged food") },
                onClick = { open = false; onScan() }
            )
            DropdownMenuItem(
                text = { MenuLabel("Add manually", "Search, recent, quick add, favorite meals") },
                onClick = { open = false; onManual() }
            )
            if (!aiEnabled) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Get a free key to unlock photos", color = MaterialTheme.colorScheme.primary) },
                    onClick = { open = false; onSetupKey() }
                )
            }
        }
    }
}

@Composable
private fun MenuLabel(title: String, hint: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- capture flows

@Composable
fun CameraScreen(onBack: () -> Unit, onGallery: () -> Unit, onCaptured: (File) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner
    // The photo is shrunk to 1280 px before analysis, so a full-resolution capture only adds delay
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1920, 1440),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .build()
    }
    val providerRef = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    DisposableEffect(Unit) {
        onDispose { providerRef[0]?.unbindAll() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    providerRef[0] = provider
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageCapture
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).systemBarsPadding().padding(8.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.Black.copy(alpha = 0.45f),
            modifier = Modifier.align(Alignment.TopCenter).systemBarsPadding().padding(top = 14.dp)
        ) {
            Text(
                "Place your meal in frame",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
        Box(
            Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp)
                .size(80.dp)
                .border(4.dp, Color.White, CircleShape)
                .padding(8.dp)
                .clip(CircleShape)
                .background(Color.White)
                .clickable {
                    val file = File(context.cacheDir, "meal_${System.currentTimeMillis()}.jpg")
                    imageCapture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(file).build(),
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(r: ImageCapture.OutputFileResults) =
                                onCaptured(file)

                            override fun onError(e: ImageCaptureException) {
                                Log.e("Camera", "Capture failed", e)
                            }
                        }
                    )
                }
        )
        // Gallery, level with the shutter (shutter centre is 72 dp above the bottom)
        IconButton(
            onClick = onGallery,
            modifier = Modifier.align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 40.dp, bottom = 46.dp)
                .size(52.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
        ) {
            Icon(GalleryIcon, contentDescription = "Pick from gallery", tint = Color.White)
        }
    }
}

// Material "Image" icon; the core icon set used by this app doesn't include it
private val GalleryIcon: ImageVector by lazy {
    ImageVector.Builder("Gallery", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = addPathNodes(
                "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14" +
                    "c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z"
            ),
            fill = SolidColor(Color.Black)
        )
        .build()
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
fun BarcodeScreen(onBack: () -> Unit, onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E
                )
                .build()
        )
    }
    val done = remember { AtomicBoolean(false) }
    val providerRef = remember { arrayOfNulls<ProcessCameraProvider>(1) }

    DisposableEffect(Unit) {
        onDispose {
            providerRef[0]?.unbindAll()
            scanner.close()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    providerRef[0] = provider
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy ->
                        val media = proxy.image
                        if (media == null || done.get()) {
                            proxy.close()
                            return@setAnalyzer
                        }
                        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                        scanner.process(input)
                            .addOnSuccessListener { codes ->
                                val raw = codes.firstOrNull()?.rawValue
                                if (raw != null && done.compareAndSet(false, true)) onScanned(raw)
                            }
                            .addOnCompleteListener { proxy.close() }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )
        Box(
            Modifier.align(Alignment.Center)
                .size(width = 280.dp, height = 160.dp)
                .border(3.dp, Color.White, RoundedCornerShape(16.dp))
        )
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).systemBarsPadding().padding(8.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.Black.copy(alpha = 0.45f),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 48.dp)
        ) {
            Text(
                "Point the camera at a barcode",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
fun ReviewScreen(
    file: File?,            // a meal photo, or
    text: String?,          // a typed / spoken description
    db: MealDb,
    initialOffset: Int,
    onBack: () -> Unit,
    onRetake: () -> Unit,
    onDone: (Int) -> Unit,
    pending: PendingMeal? = null   // a photo queued offline; [file] is its photo
) {
    // One ViewModel per photo or description, so a retake starts a fresh analysis
    val context = LocalContext.current
    val vm: ReviewViewModel = viewModel(
        key = pending?.let { "pending-${it.id}" } ?: file?.absolutePath ?: "text-$text",
        factory = viewModelFactory {
            initializer { ReviewViewModel(file, text, pending?.analysis, isOnline(context)) }
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var logOffset by rememberSaveable { mutableIntStateOf(initialOffset) }
    var pickIndex by remember { mutableStateOf<Int?>(null) }

    // Offline: keep the photo and analyze it in the background once connected
    fun queueNow() {
        if (file == null) return
        PendingMeals.add(context, file, timestampForOffset(logOffset))
        AnalyzePendingWorker.enqueue(context)
        Toast.makeText(context, "Saved. You'll be notified when it's ready to review.", Toast.LENGTH_LONG).show()
        onBack()
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { queueNow() }   // queue either way; without permission the in-app card still shows it
    fun queueForLater() {
        val needsAsk = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsAsk) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else queueNow()
    }
    val canQueue = file != null && pending == null
    val bitmap by produceState<Bitmap?>(null, file) {
        if (file != null) value = withContext(Dispatchers.IO) { ImageUtil.loadUpright(file, 800) }
    }

    pickIndex?.let { idx ->
        state.items.getOrNull(idx)?.let { food ->
            MatchPickerDialog(
                foodName = food.name,
                onPick = { p, save ->
                    vm.replaceMatch(idx, p)
                    if (save) NutritionLookup.saveCorrection(food.name, p)
                    pickIndex = null
                },
                onDismiss = { pickIndex = null }
            )
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        ScreenTopBar("Review meal", onBack)
        if (file != null) {
            Box(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(170.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Meal photo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        } else if (text != null) {
            Text(
                "“$text”",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        when {
            state.isLoading -> AnalyzingView(
                "Analyzing your meal…",
                "Identifying foods…",
                Modifier.weight(1f).fillMaxWidth()
            )
            state.offline -> Column(Modifier.weight(1f).padding(16.dp)) {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("You're offline", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            if (canQueue) "Save this photo and it will be analyzed automatically when you're " +
                                "back online. You'll get a notification when it's ready to review."
                            else "This photo is queued and will be analyzed automatically when you're back online.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canQueue) Button(onClick = ::queueForLater) { Text("Analyze when online") }
                    OutlinedButton(onClick = { vm.analyze() }) { Text("Try now") }
                }
            }
            state.error != null -> Column(Modifier.weight(1f).padding(16.dp)) {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(
                        "Something went wrong: ${state.error}",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.analyze() }) { Text("Try again") }
                    if (canQueue) OutlinedButton(onClick = ::queueForLater) { Text("Analyze later") }
                }
            }
            else -> LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { DietBanner(state.items) }
                item {
                    Text(
                        state.description,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                item { TotalsCard(state.items.totals()) }
                itemsIndexed(state.items) { index, food ->
                    ItemRow(
                        index = index,
                        food = food,
                        onGrams = { g -> vm.setGrams(index, g) },
                        onRemove = { vm.remove(index) },
                        onChangeMatch = { pickIndex = index }
                    )
                }
            }
        }
        ActionBar(
            logOffset = logOffset,
            onOffset = { logOffset = it },
            secondaryLabel = when {
                pending != null -> "Discard"
                file != null -> "Retake"
                else -> "Edit text"
            },
            onSecondary = {
                if (pending != null) {
                    PendingMeals.remove(context, pending.id)
                    onBack()
                } else onRetake()
            },
            saveEnabled = !state.isLoading && state.error == null && !state.offline && state.items.isNotEmpty(),
            onSave = {
                // A queued photo keeps the time it was taken, moved to whichever day is picked
                val ts = if (pending != null) shiftDays(pending.mealTs, logOffset - initialOffset)
                else timestampForOffset(logOffset)
                val id = db.insert(state.description, state.items, ts)
                if (pending != null) PendingMeals.remove(context, pending.id, keepPhoto = true)
                if (file != null) {
                    // Learn from how the kept grams compare with the AI's photo guess
                    state.items.forEach { f -> f.estimate?.let { PortionStore.record(f.name, it.aiGrams, f.grams) } }
                    if (id > 0) MealPhotos.saveAsync(context, id, file)
                }
                BackupService.autoBackup(context)
                onDone(logOffset)
            }
        )
    }
}

@Composable
fun ScannedScreen(
    code: String,
    db: MealDb,
    initialOffset: Int,
    onBack: () -> Unit,
    onRescan: () -> Unit,
    onDone: (Int) -> Unit
) {
    val context = LocalContext.current
    val vm: ScannedViewModel = viewModel(
        key = "scan-$code",
        factory = viewModelFactory { initializer { ScannedViewModel(code) } }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var logOffset by rememberSaveable { mutableIntStateOf(initialOffset) }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        ScreenTopBar("Scanned product", onBack)
        val current = state.item
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.isLoading -> AnalyzingView(
                    "Looking up product…",
                    "Barcode $code",
                    Modifier.fillMaxSize()
                )
                state.error != null -> Column(Modifier.padding(16.dp)) {
                    AppCard(Modifier.fillMaxWidth()) {
                        Text(
                            state.error ?: "",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Button(onClick = { vm.load() }, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Try again")
                    }
                }
                current != null -> Column(
                    Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DietBanner(listOf(current))
                    TotalsCard(listOf(current).totals())
                    ItemRow(
                        index = 0,
                        food = current,
                        onGrams = { g -> vm.setGrams(g) },
                        onRemove = onRescan
                    )
                    state.product?.let { ProductDetails(it) }
                    Text(
                        "Set the amount you ate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        ActionBar(
            logOffset = logOffset,
            onOffset = { logOffset = it },
            secondaryLabel = "Scan again",
            onSecondary = onRescan,
            saveEnabled = !state.isLoading && state.error == null && state.item != null,
            onSave = {
                state.item?.let { db.insert(it.name, listOf(it), timestampForOffset(logOffset)) }
                BackupService.autoBackup(context)
                onDone(logOffset)
            }
        )
    }
}

@Composable
fun TotalsCard(t: Totals) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Meal total",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${t.kcal.f0()} kcal",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Sugar", "${t.sugar.f1()} g", Modifier.weight(1f))
                StatTile("Fat", "${t.fat.f1()} g", Modifier.weight(1f))
                StatTile("Carbs", "${t.carbs.f1()} g", Modifier.weight(1f))
                StatTile("Protein", "${t.protein.f1()} g", Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun ItemRow(
    index: Int,
    food: FoodItem,
    onGrams: (Double) -> Unit,
    onRemove: () -> Unit,
    onChangeMatch: (() -> Unit)? = null
) {
    var text by remember(index, food.name) {
        mutableStateOf(
            if (food.grams == food.grams.toLong().toDouble()) food.grams.f0() else food.grams.f1()
        )
    }
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 16.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 4.dp)) {
                    Text(
                        food.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        when (food.matchedName) {
                            null -> "No match · not counted"
                            AI_ESTIMATE -> "AI estimate"
                            else -> food.matchedName
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (food.matchedName) {
                            null -> MaterialTheme.colorScheme.error
                            AI_ESTIMATE -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    onChangeMatch?.let {
                        TextButton(onClick = it, contentPadding = PaddingValues(0.dp)) { Text("Change match") }
                    }
                    DietWarnings(food)
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        it.replace(',', '.').toDoubleOrNull()?.let(onGrams)
                    },
                    label = { Text("grams") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(120.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        "${food.kcal.f0()} kcal",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Sugar ${food.sugar.f1()} g (added ${food.addedSugar.f1()} g)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Fat ${food.fat.f1()} g",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            food.estimate?.let { e ->
                val unsure = e.confidence == "low"
                val range = if (e.highGrams > e.lowGrams) "${e.lowGrams.f0()}-${e.highGrams.f0()} g" else null
                Text(
                    buildString {
                        append(if (unsure) "AI wasn't sure about this one" else "AI confidence: ${e.confidence}")
                        range?.let { append(" · likely $it") }
                        if (e.preparation.isNotBlank()) append(" · ${e.preparation}")
                        e.learnedFactor?.let {
                            append(" · adjusted from ${e.aiGrams.f0()} g to match your usual portion")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (unsure) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}