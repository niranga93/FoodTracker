package com.example.foodtracker.nutrition

import com.example.foodtracker.ai.FoodAnalyzer
import com.example.foodtracker.data.FoodItem
import com.example.foodtracker.diet.AllergenDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class Analysis(val description: String, val items: List<FoodItem>)

object MealPipeline {
    suspend fun run(file: File): Result<Analysis> = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject(FoodAnalyzer.analyze(file).getOrThrow())
            val arr = json.getJSONArray("items")
            if (arr.length() == 0) error("No food detected in the photo")

            val items = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                val grams = o.optDouble("estimated_grams", 0.0)
                val term = o.optString("usda_search_term", name).ifBlank { name }
                val n = NutritionLookup.correctionFor(name)
                    ?: runCatching { NutritionLookup.search(term) }.getOrNull()

                // Allergen / diet tags: what the model reported, plus keywords in the food names
                val tags = LinkedHashSet<String>()
                o.optJSONArray("contains")?.let { a ->
                    for (k in 0 until a.length()) tags.add(a.optString(k).trim().lowercase())
                }
                tags.retainAll(AllergenDetector.KNOWN)
                tags.addAll(AllergenDetector.detect(name + " " + (n?.description ?: "")))
                val tagList = tags.toList()

                if (n == null) {
                    FoodItem(name, null, grams, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, tags = tagList)
                } else {
                    FoodItem(
                        name, n.description, grams,
                        n.kcal, n.protein, n.fat, n.satFat,
                        n.carbs, n.sugar, n.fiber, n.sodiumMg,
                        tags = tagList
                    )
                }
            }
            Analysis(json.optString("meal_description", "Meal"), items)
        }
    }
}
