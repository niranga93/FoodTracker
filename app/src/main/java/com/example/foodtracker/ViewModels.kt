package com.example.foodtracker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.foodtracker.data.FoodItem
import com.example.foodtracker.nutrition.Analysis
import com.example.foodtracker.nutrition.MealPipeline
import com.example.foodtracker.nutrition.NutritionLookup
import com.example.foodtracker.nutrition.OffProduct
import com.example.foodtracker.nutrition.OpenFoodFacts
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
    val offline: Boolean = false     // photo not analyzed because there is no connection
)

/**
 * Analyzes either a meal photo or a typed/spoken description (exactly one is set).
 * [preloaded] is a finished analysis of a photo queued offline; [online] = false skips the attempt.
 */
class ReviewViewModel(
    private val file: File?,
    private val text: String?,
    preloaded: Analysis? = null,
    online: Boolean = true
) : ViewModel() {
    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    init {
        when {
            preloaded != null ->
                _state.value = ReviewUiState(isLoading = false, description = preloaded.description, items = preloaded.items)
            !online && file != null -> _state.value = ReviewUiState(isLoading = false, offline = true)
            else -> analyze()
        }
    }

    // Called from init, and again from the Retry button (only shown when not loading)
    fun analyze() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, offline = false) }
            val result = if (file != null) MealPipeline.run(file) else MealPipeline.runText(text.orEmpty())
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
