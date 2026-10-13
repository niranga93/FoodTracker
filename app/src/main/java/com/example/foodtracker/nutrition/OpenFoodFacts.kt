package com.example.foodtracker.nutrition

import com.example.foodtracker.data.FoodItem
import com.example.foodtracker.diet.AllergenDetector
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object OpenFoodFacts {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Returns null if the product isn't in the database. Blocking: call from Dispatchers.IO. */
    fun fetch(barcode: String): FoodItem? {
        val url = "https://world.openfoodfacts.org/api/v2/product/$barcode.json" +
                "?fields=product_name,brands,nutriments,serving_quantity,allergens_tags,ingredients_analysis_tags"
        // Open Food Facts asks apps to send a descriptive User-Agent
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "FoodTracker/1.0 (personal Android app)")
            .build()

        var raw: String? = null
        var lastError = "unknown"
        var waitMs = 2000L
        for (attempt in 0 until 3) {
            try {
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    when {
                        resp.isSuccessful -> raw = body
                        resp.code == 404 -> return null
                        else -> lastError = "Open Food Facts HTTP ${resp.code}"
                    }
                }
            } catch (e: IOException) {
                lastError = "Network error: ${e.message}"
            }
            if (raw != null) break
            if (attempt < 2) {
                Thread.sleep(waitMs)
                waitMs *= 2
            }
        }
        val body = raw ?: error("Lookup failed. $lastError")

        val root = JSONObject(body)
        if (root.optInt("status", 0) != 1) return null
        val p = root.optJSONObject("product") ?: return null
        val n = p.optJSONObject("nutriments") ?: return null

        fun v(key: String) = n.optDouble(key, Double.NaN)
        fun or0(d: Double) = if (d.isNaN()) 0.0 else d

        val kcal = v("energy-kcal_100g").takeUnless { it.isNaN() }
            ?: v("energy_100g").takeUnless { it.isNaN() }?.let { it / 4.184 }  // kJ -> kcal
            ?: error("This product has no calorie data in Open Food Facts")
        val sodiumMg = v("sodium_100g").takeUnless { it.isNaN() }?.let { it * 1000 }
            ?: v("salt_100g").takeUnless { it.isNaN() }?.let { it / 2.5 * 1000 }
            ?: 0.0

        val name = listOf(p.optString("brands").substringBefore(","), p.optString("product_name"))
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Product $barcode" }
        val serving = p.optDouble("serving_quantity", 0.0).takeIf { it > 0 } ?: 100.0

        val tags = mutableSetOf<String>()
        p.optJSONArray("allergens_tags")?.let { a ->
            for (i in 0 until a.length()) {
                when (a.getString(i).removePrefix("en:")) {
                    "peanuts" -> tags += "peanuts"
                    "nuts" -> tags += "tree_nuts"
                    "milk" -> tags += "milk"
                    "eggs" -> tags += "eggs"
                    "fish" -> tags += "fish"
                    "crustaceans", "molluscs" -> tags += "shellfish"
                    "soybeans" -> tags += "soy"
                    "gluten" -> tags += "gluten"
                    "sesame-seeds" -> tags += "sesame"
                }
            }
        }
        p.optJSONArray("ingredients_analysis_tags")?.let { a ->
            for (i in 0 until a.length()) {
                when (a.getString(i)) {
                    "en:non-vegan" -> tags += "non-vegan"
                    "en:non-vegetarian" -> tags += "non-vegetarian"
                }
            }
        }
        tags += AllergenDetector.detect(name)

        return FoodItem(
            name = name,
            matchedName = "Open Food Facts",
            grams = serving,                 // defaults to one serving, or 100 g if unknown
            kcal100 = kcal,
            protein100 = or0(v("proteins_100g")),
            fat100 = or0(v("fat_100g")),
            satFat100 = or0(v("saturated-fat_100g")),
            carbs100 = or0(v("carbohydrates_100g")),
            sugar100 = or0(v("sugars_100g")),
            fiber100 = or0(v("fiber_100g")),
            sodium100 = sodiumMg,
            tags = tags.toList()
        )
    }
}
