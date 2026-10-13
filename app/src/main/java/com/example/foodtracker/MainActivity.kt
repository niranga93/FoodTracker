package com.example.foodtracker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
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
import com.example.foodtracker.ai.ImageUtil
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.example.foodtracker.data.FoodItem
import com.example.foodtracker.data.GoalStore
import com.example.foodtracker.data.Goals
import com.example.foodtracker.data.Meal
import com.example.foodtracker.data.MealDb
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NutritionLookup.init(applicationContext)
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
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) pending?.let { nav.open(it) }
        pending = null
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
            onFavorites = { nav.open(Screen.Favorites) },
            onEditMeal = { nav.open(Screen.EditMeal(it)) },
            onOpenHistory = { nav.open(Screen.History) }
        )
        is Screen.History -> HistoryPage(
            db = db,
            goalStore = goalStore,
            onBack = nav::back,
            onOpenDay = nav::openDay
        )
        is Screen.Camera -> CameraScreen(
            onBack = nav::back
        ) { nav.open(Screen.Review(it)) }
        is Screen.Review -> ReviewScreen(
            file = s.file,
            db = db,
            initialOffset = state.dayOffset,
            onBack = nav::back,
            onRetake = { nav.open(Screen.Camera) },
            onDone = nav::finishSave
        )
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
            onBack = nav::back,
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
    onFavorites: () -> Unit,
    onEditMeal: (Long) -> Unit,
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
            if (tab == NavTab.Home || tab == NavTab.Intake) AddFab(onPhoto, onScan, onManual, onFavorites)
        }
    ) { inner ->
        when (tab) {
            NavTab.Home -> HomeScreen(db, goalStore, dayOffset, onDayChange, inner)
            NavTab.Intake -> IntakeScreen(db, goalStore, dayOffset, onDayChange, onEditMeal, onOpenHistory, inner)
            NavTab.Burned -> BurnedScreen(db, dayOffset, onDayChange, inner)
            NavTab.Profile -> ProfileScreen(goalStore, db, inner)
        }
    }
}

@Composable
fun AddFab(onPhoto: () -> Unit, onScan: () -> Unit, onManual: () -> Unit, onFavorites: () -> Unit) {
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
            DropdownMenuItem(text = { Text("Photo of a meal") }, onClick = { open = false; onPhoto() })
            DropdownMenuItem(text = { Text("Scan a barcode") }, onClick = { open = false; onScan() })
            DropdownMenuItem(text = { Text("Add manually") }, onClick = { open = false; onManual() })
            DropdownMenuItem(text = { Text("Favorite meals") }, onClick = { open = false; onFavorites() })
        }
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
    val days = remember {
        (0 downTo -13).map { off -> off to loadDay(db, off).flatMap { it.items } }
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
fun CameraScreen(onBack: () -> Unit, onCaptured: (File) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner
    val imageCapture = remember { ImageCapture.Builder().build() }
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
    }
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
    file: File,
    db: MealDb,
    initialOffset: Int,
    onBack: () -> Unit,
    onRetake: () -> Unit,
    onDone: (Int) -> Unit
) {
    // One ViewModel per photo, so a retake starts a fresh analysis
    val context = LocalContext.current
    val vm: ReviewViewModel = viewModel(
        key = file.absolutePath,
        factory = viewModelFactory { initializer { ReviewViewModel(file) } }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var logOffset by rememberSaveable { mutableStateOf(initialOffset) }
    var pickIndex by remember { mutableStateOf<Int?>(null) }
    val bitmap = remember(file) {
        ImageUtil.loadUpright(file, 4) ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
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
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Meal photo",
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .height(170.dp)
                .clip(RoundedCornerShape(20.dp)),
            contentScale = ContentScale.Crop
        )
        when {
            state.isLoading -> AnalyzingView(
                "Analyzing your meal…",
                "Identifying foods and looking up nutrition",
                Modifier.weight(1f).fillMaxWidth()
            )
            state.error != null -> Column(Modifier.weight(1f).padding(16.dp)) {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(
                        "Something went wrong: ${state.error}",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Button(onClick = { vm.analyze() }, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Try again")
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
            secondaryLabel = "Retake",
            onSecondary = onRetake,
            saveEnabled = !state.isLoading && state.error == null && state.items.isNotEmpty(),
            onSave = {
                db.insert(state.description, state.items, timestampForOffset(logOffset))
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
                        food.matchedName?.let { "USDA: $it" }
                            ?: "No USDA match - nutrition not counted",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (food.matchedName == null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
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
        }
    }
}