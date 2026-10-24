package com.example.nourio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nourio.data.FoodItem
import com.example.nourio.nutrition.Analysis
import com.example.nourio.nutrition.MealPipeline
import com.example.nourio.nutrition.NutritionLookup
import com.example.nourio.nutrition.OffProduct
import com.example.nourio.nutrition.OpenFoodFacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------------- navigation ----------------

data class NavState(
    val screen: Screen = Screen.Main,
    val tab: NavTab = NavTab.Home,
    val dayOffset: Int = 0
)

class NavViewModel : ViewModel() {
    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state.asStateFlow()

    fun open(screen: Screen) = _state.update { it.copy(screen = screen) }

    // From a sub-screen go to the main screen; from another tab go Home
    fun back() = _state.update {
        if (it.screen !is Screen.Main) it.copy(screen = Screen.Main)
        else it.copy(tab = NavTab.Home)
    }

    fun selectTab(tab: NavTab) = _state.update { it.copy(tab = tab) }
    fun setDay(offset: Int) = _state.update { it.copy(dayOffset = offset) }
    fun openDay(offset: Int) =
        _state.update { it.copy(dayOffset = offset, tab = NavTab.Intake, screen = Screen.Main) }
    fun finishSave(offset: Int) =
        _state.update { NavState(screen = Screen.Main, tab = NavTab.Intake, dayOffset = offset) }
}

// ---------------- photo review ----------------

data class ReviewUiState(
    val isLoading: Boolean = true,
    val description: String = "",
    val items: List<FoodItem> = emptyList(),
    val error: String? = null,
    val offline: Boolean = false,    // photo not analyzed because there is no connection
    val needsConsent: com.example.nourio.ai.AiUse? = null,  // waiting for the user to allow sending it to Gemini
    val slow: Boolean = false        // still analyzing after SLOW_MS: the screen moves it to the background
)

/** How long the review screen waits before finishing an analysis in the background. */
const val SLOW_MS = 30_000L

/**
 * Analyzes either a meal photo or a typed/spoken description (exactly one is set).
 * [preloaded] is a finished analysis of a photo queued offline; [online] = false skips the attempt.
 */
class ReviewViewModel(
    private val file: File?,
    private val text: String?,
    preloaded: Analysis? = null,
    private val online: Boolean = true
) : ViewModel() {
    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    // A photo or a description: each has its own consent
    private val use = if (file != null) com.example.nourio.ai.AiUse.PHOTO else com.example.nourio.ai.AiUse.TEXT

    init {
        when {
            preloaded != null ->
                _state.value = ReviewUiState(isLoading = false, description = preloaded.description, items = preloaded.items)
            // Ask first, even before queueing a photo for later: queued photos are sent too
            !com.example.nourio.ai.AiConsent.isGranted(use) -> _state.value = ReviewUiState(isLoading = false, needsConsent = use)
            else -> start()
        }
    }

    private fun start() {
        if (!online && file != null) _state.value = ReviewUiState(isLoading = false, offline = true) else analyze()
    }

    /** The user allowed it in the consent dialog. */
    fun consentGiven() {
        _state.update { it.copy(needsConsent = null) }
        start()
    }

    // Called from init, and again from the Retry button (only shown when not loading)
    fun analyze() {
        if (!com.example.nourio.ai.AiConsent.isGranted(use)) {
            _state.update { it.copy(isLoading = false, needsConsent = use) }
            return
        }
        job?.cancel()
        slowTimer?.cancel()
        slowTimer = viewModelScope.launch {
            kotlinx.coroutines.delay(SLOW_MS)
            if (_state.value.isLoading) _state.update { it.copy(slow = true) }
        }
        job = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, offline = false, slow = false) }
            val result = if (file != null) MealPipeline.run(file) else MealPipeline.runText(text.orEmpty())
            if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) return@launch
            slowTimer?.cancel()
            if (_state.value.slow) return@launch     // already handed to the background
            result.fold(
                onSuccess = { res ->
                    _state.update {
                        it.copy(isLoading = false, description = res.description, items = res.items)
                    }
                },
                onFailure = { err ->
                    _state.update {
                        it.copy(isLoading = false, error = err.message ?: "Analysis failed")
                    }
                }
            )
        }
    }

    private var job: kotlinx.coroutines.Job? = null
    private var slowTimer: kotlinx.coroutines.Job? = null

    /** Stops the on-screen request because the background worker takes it over. */
    fun cancelForBackground() {
        job?.cancel()
        slowTimer?.cancel()
        job = null
        com.example.nourio.ai.AiProgress.end()
    }

    /** Cancel on the progress screen: stops the request; nothing is saved. */
    fun cancel() {
        job?.cancel()
        slowTimer?.cancel()
        job = null
        com.example.nourio.ai.AiProgress.end()
        _state.update { it.copy(isLoading = false, error = "Cancelled. Nothing was sent to your diary.") }
    }

    fun setGrams(index: Int, grams: Double) = _state.update { s ->
        if (index !in s.items.indices) s
        else s.copy(items = s.items.toMutableList().also { it[index] = it[index].copy(grams = grams) })
    }

    fun remove(index: Int) = _state.update { s ->
        if (index !in s.items.indices) s
        else s.copy(items = s.items.toMutableList().also { it.removeAt(index) })
    }

    fun replaceMatch(index: Int, p: NutritionLookup.Per100g) = _state.update { s ->
        if (index !in s.items.indices) s
        else s.copy(items = s.items.toMutableList().also { it[index] = it[index].withMatch(p) })
    }
}

// ---------------- barcode result ----------------

data class ScannedUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val item: FoodItem? = null,
    val product: OffProduct? = null
)

class ScannedViewModel(private val code: String) : ViewModel() {
    private val _state = MutableStateFlow(ScannedUiState())
    val state: StateFlow<ScannedUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching { withContext(Dispatchers.IO) { OpenFoodFacts.fetchProduct(code) } }.fold(
                onSuccess = { p ->
                    _state.update {
                        if (p == null) it.copy(
                            isLoading = false,
                            error = "Product $code was not found in Open Food Facts."
                        ) else it.copy(isLoading = false, item = p.item, product = p)
                    }
                },
                onFailure = { err ->
                    _state.update {
                        it.copy(isLoading = false, error = err.message ?: "Lookup failed")
                    }
                }
            )
        }
    }

    fun setGrams(grams: Double) = _state.update { s -> s.copy(item = s.item?.copy(grams = grams)) }
}
