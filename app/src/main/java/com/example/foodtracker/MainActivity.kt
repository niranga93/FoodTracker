package com.example.foodtracker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Toast
import android.content.Intent
import android.os.Build
import androidx.activity.viewModels
import com.example.foodtracker.pending.AnalyzePendingWorker
import com.example.foodtracker.pending.PendingMeal
import com.example.foodtracker.pending.PendingMeals
import com.example.foodtracker.pending.PendingStatus
import com.example.foodtracker.pending.dayOffsetOf
import com.example.foodtracker.pending.isOnline
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.example.foodtracker.ai.ImageUtil
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.foodtracker.data.AI_ESTIMATE
import com.example.foodtracker.data.FoodItem
import com.example.foodtracker.data.GoalStore
import com.example.foodtracker.data.Goals
import com.example.foodtracker.data.Meal
import com.example.foodtracker.data.MealDb
import com.example.foodtracker.data.MealPhotos
import com.example.foodtracker.data.PortionStore
import com.example.foodtracker.data.Totals
import com.example.foodtracker.data.totals
import com.example.foodtracker.nutrition.MealPipeline
import com.example.foodtracker.nutrition.NutritionLookup
import com.example.foodtracker.nutrition.OpenFoodFacts
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.foodtracker.backup.BackupService
import androidx.compose.material.icons.filled.Person

class MainActivity : ComponentActivity() {
    // Same instance FoodApp gets from viewModel(), so notification taps can navigate
    private val nav: NavViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NutritionLookup.init(applicationContext)
        PortionStore.init(applicationContext)
        if (savedInstanceState == null) openPendingFrom(intent)
        // Photos queued offline get another chance whenever the app starts
        if (PendingMeals.all(this).any { it.status == PendingStatus.WAITING }) AnalyzePendingWorker.enqueue(this)

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
                        delay(1400)
                        showSplash = false
                    }
                    Crossfade(targetState = showSplash, label = "splash") { splash ->
                        if (splash) SplashScreen() else FoodApp()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openPendingFrom(intent)
    }

    /** Opens the review for a queued photo when started from its "ready to review" notification. */
    private fun openPendingFrom(intent: Intent?) {
        val id = intent?.getLongExtra(AnalyzePendingWorker.EXTRA_PENDING_ID, -1L) ?: -1L
        if (id > 0) nav.open(Screen.ReviewPending(id))
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
            "FoodTracker",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "Snap. Scan. Track.",
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

    fun pickFromGallery() {
        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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

    when (val s = state.screen) {
        is Screen.Main -> MainScaffold(
            tab = state.tab,
            onTab = nav::selectTab,
            db = db,
            goalStore = goalStore,
            dayOffset = state.dayOffset,
            onDayChange = nav::setDay,
            onPhoto = { go(Screen.Camera) },
            onScan = { go(Screen.Barcode) },
            onManual = { nav.open(Screen.Manual) },
            onDescribe = { nav.open(Screen.Describe()) },
            onEditMeal = { nav.open(Screen.EditMeal(it)) },
            onOpenPending = { nav.open(Screen.ReviewPending(it)) },
            onOpenHistory = { nav.open(Screen.History) }
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
    onOpenPending: (Long) -> Unit,
    onOpenHistory: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = cardContainer()) {
                NavTab.values().forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { onTab(t) },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == NavTab.Home || tab == NavTab.Intake) AddFab(onPhoto, onDescribe, onScan, onManual)
        }
    ) { inner ->
        when (tab) {
            NavTab.Home -> HomeScreen(db, goalStore, dayOffset, onDayChange, onTab, inner, onOpenPending)
            NavTab.Intake -> IntakeScreen(db, goalStore, dayOffset, onDayChange, onEditMeal, onOpenHistory, inner, onOpenPending)
            NavTab.Burned -> BurnedScreen(db, dayOffset, onDayChange, inner)
            NavTab.Profile -> ProfileScreen(goalStore, db, inner)
        }
    }
}

@Composable
fun AddFab(
    onPhoto: () -> Unit,
    onDescribe: () -> Unit,
    onScan: () -> Unit,
    onManual: () -> Unit
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
            DropdownMenuItem(
                text = { MenuLabel("Photo", "Camera, or pick from gallery") },
                onClick = { open = false; onPhoto() }
            )
            DropdownMenuItem(
                text = { MenuLabel("Describe or speak", "Type or say what you ate") },
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



@Composable
fun HistoryScreen(
    db: MealDb,
    goalStore: GoalStore,
    inner: PaddingValues,
    onOpenDay: (Int) -> Unit
) {
    val goals = remember { goalStore.load() }
    val offsets = remember { (0 downTo -13).toList() }
    val days by produceState(offsets.map { it to emptyList<FoodItem>() }) {
        value = withContext(Dispatchers.IO) {
            val byDay = loadDays(db, offsets)
            offsets.map { off -> off to byDay[off].orEmpty().flatMap { it.items } }
        }
    }
    val logged = days.filter { it.second.isNotEmpty() }
    val avgKcal = if (logged.isEmpty()) 0.0 else logged.map { it.second.totals().kcal }.average()
    val maxKcal = days.maxOfOrNull { it.second.totals().kcal } ?: 0.0
    val scale = maxOf(maxKcal, goals.kcal, 1.0)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(inner),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("History", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        item { WeekCard(db) }
        item { WeeklyReviewCard(db, goalStore) }
        item { PredictionCard(db) }
        item { WeightCard() }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("Days logged", "${logged.size} / 14", Modifier.weight(1f))
                        StatTile("Daily average", "${avgKcal.f0()} kcal", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Calories, last 14 days", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().height(110.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        days.reversed().forEach { (_, foods) ->
                            val kcal = foods.totals().kcal
                            val frac = (kcal / scale).toFloat().coerceIn(0.03f, 1f)
                            val over = goals.kcal > 0 && kcal > goals.kcal
                            Box(
                                Modifier.weight(1f).fillMaxHeight(frac)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        when {
                                            kcal == 0.0 -> MaterialTheme.colorScheme.surfaceVariant
                                            over -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.primary
                                        }
                                    )
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "14 days ago",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Today",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        items(days) { (off, foods) ->
            val t = foods.totals()
            AppCard(Modifier.fillMaxWidth().clickable { onOpenDay(off) }) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(dayTitle(off), fontWeight = FontWeight.Bold)
                        if (foods.isNotEmpty()) {
                            Text(
                                "Sugar ${t.sugar.f1()} g  ·  Fat ${t.fat.f1()} g  ·  Carbs ${t.carbs.f1()} g",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        if (foods.isEmpty()) "—" else "${t.kcal.f0()} kcal",
                        fontWeight = FontWeight.Bold,
                        color = if (goals.kcal > 0 && t.kcal > goals.kcal)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
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
    var logOffset by rememberSaveable { mutableStateOf(initialOffset) }
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
                "Identifying foods and looking up nutrition",
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
    var logOffset by rememberSaveable { mutableStateOf(initialOffset) }

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
                    Text(
                        "Grams default to one serving (or 100 g if the product lists none). " +
                                "Set the amount you actually ate.",
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
                            null -> "No USDA match - nutrition not counted"
                            AI_ESTIMATE -> "No USDA match - using the AI's nutrition estimate"
                            else -> "USDA: ${food.matchedName}"
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
                        "Sugar ${food.sugar.f1()} g",
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