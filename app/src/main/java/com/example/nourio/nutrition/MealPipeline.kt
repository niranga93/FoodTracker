package com.example.nourio.nutrition

import com.example.nourio.ai.FoodAnalyzer
import com.example.nourio.ai.RecipeFetcher
import com.example.nourio.data.AI_ESTIMATE
import com.example.nourio.data.FoodItem
import com.example.nourio.data.PortionEstimate
import com.example.nourio.data.PortionStore
import com.example.nourio.diet.AllergenDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

data class Analysis(val description: String, val items: List<FoodItem>)

object MealPipeline {
    /** Photo meals also apply portion sizes learned from your past corrections. */
    suspend fun run(file: File): Result<Analysis> = withContext(Dispatchers.IO) {
        runCatching {
            build(FoodAnalyzer.analyze(file).getOrThrow(), "No food detected in the photo", learned = true)
        }
    }

    /**
     * A typed, spoken or shared description, a recipe, or a link to a recipe page.
     * Amounts come from the user, so no learned scaling.
     */
    suspend fun runText(description: String): Result<Analysis> = withContext(Dispatchers.IO) {
        runCatching {
            val content = RecipeFetcher.expand(description)
            build(FoodAnalyzer.analyzeText(content).getOrThrow(), "No food found in that description", learned = false)
        }
    }

    private suspend fun build(raw: String, emptyMessage: String, learned: Boolean): Analysis {
        val json = JSONObject(raw)
        val arr = json.getJSONArray("items")
        if (arr.length() == 0) error(emptyMessage)

        // Look up all items at once; each lookup is a blocking network call
        val items = coroutineScope {
            (0 until arr.length()).map { i ->
                async(Dispatchers.IO) { toFoodItem(arr.getJSONObject(i), learned) }
            }.awaitAll()
        }
        return Analysis(json.optString("meal_description", "Meal"), items)
    }

    private fun toFoodItem(o: JSONObject, learned: Boolean): FoodItem {
        val name = o.optString("name")
        val aiGrams = o.optDouble("estimated_grams", 0.0)
        val factor = if (learned) PortionStore.factorFor(name)?.takeIf { abs(it - 1.0) >= 0.05 } else null
        val scale = factor ?: 1.0
        val grams = aiGrams * scale
        val term = o.optString("usda_search_term", name).ifBlank { name }
        val n = NutritionLookup.correctionFor(name)
            ?: runCatching { NutritionLookup.search(term) }.getOrNull()
            ?: aiEstimate(o)

        // Allergen / diet tags: what the model reported, plus keywords in the food names
        val tags = LinkedHashSet<String>()
        o.optJSONArray("contains")?.let { a ->
            for (k in 0 until a.length()) tags.add(a.optString(k).trim().lowercase())
        }
        tags.retainAll(AllergenDetector.KNOWN)
        tags.addAll(AllergenDetector.detect(name + " " + (n?.description ?: "")))
        val tagList = tags.toList()

        val estimate = PortionEstimate(
            lowGrams = (o.optDouble("grams_low", aiGrams).takeIf { it > 0 } ?: aiGrams) * scale,
            highGrams = (o.optDouble("grams_high", aiGrams).takeIf { it > 0 } ?: aiGrams) * scale,
            confidence = o.optString("confidence", "medium"),
            preparation = o.optString("preparation"),
            aiGrams = aiGrams,
            learnedFactor = factor
        )

        return if (n == null) {
            FoodItem(
                name, null, grams, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                tags = tagList, estimate = estimate
            )
        } else {
            FoodItem(
                name, n.description, grams,
                n.kcal, n.protein, n.fat, n.satFat,
                n.carbs, n.sugar, n.fiber, n.sodiumMg,
                addedSugar100 = n.addedSugar ?: aiAddedShare(o)?.let { it * n.sugar },
                tags = tagList, estimate = estimate
            )
        }
    }

    /** The model's own per-100 g values, used when no database match is found. */
    private fun aiEstimate(o: JSONObject): NutritionLookup.Per100g? {
        val kcal = o.optDouble("kcal_per_100g", Double.NaN)
        if (kcal.isNaN() || kcal < 0) return null
        fun v(key: String) = o.optDouble(key, 0.0).takeIf { !it.isNaN() && it >= 0 } ?: 0.0
        return NutritionLookup.Per100g(
            description = AI_ESTIMATE,
            kcal = kcal,
            protein = v("protein_per_100g"),
            fat = v("fat_per_100g"),
            satFat = v("sat_fat_per_100g"),
            carbs = v("carbs_per_100g"),
            sugar = v("sugar_per_100g"),
            fiber = v("fiber_per_100g"),
            sodiumMg = v("sodium_mg_per_100g"),
            addedSugar = aiAddedShare(o)?.let { it * v("sugar_per_100g") }
        )
    }

    /** The model's share of the sugar that is added, applied to a database match's sugar. Null if it didn't say. */
    private fun aiAddedShare(o: JSONObject): Double? {
        val added = o.optDouble("added_sugar_per_100g", Double.NaN)
        val sugar = o.optDouble("sugar_per_100g", Double.NaN)
        if (added.isNaN() || added < 0) return null
        return if (sugar > 0) (added / sugar).coerceIn(0.0, 1.0) else 0.0
    }
}
