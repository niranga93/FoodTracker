package com.example.nourio.nutrition

import com.example.nourio.data.FoodItem
import com.example.nourio.diet.AllergenDetector
import okhttp3.CertificatePinner
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A packaged product from Open Food Facts. item holds per-100 g values; grams is one serving, or 100. */
data class OffProduct(
    val code: String,
    val item: FoodItem,
    val brand: String,
    val ingredients: String?,
    val additives: List<String>,
    val allergens: List<String>,
    val nova: Int?,
    val nutriscore: String?
)

object OpenFoodFacts {
    private const val USER_AGENT = "Nourio/1.0 (foodtracker777@gmail.com)"
    private const val FIELDS = "code,product_name,brands,nutriments,serving_quantity,allergens_tags," +
        "ingredients_analysis_tags,ingredients_text,additives_tags,nova_group,nutriscore_grade"

    private val pinner = CertificatePinner.Builder()
        .apply {
            LetsEncryptPins.ROOTS.forEach {
                add("world.openfoodfacts.org", it)
                add("search.openfoodfacts.org", it)
            }
        }
        .build()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .certificatePinner(pinner)
        .build()

    @Volatile private var lastSearchAt = 0L

    /** Blocking. Returns null on 404. */
    private fun call(request: Request): String? {
        var waitMs = 2000L
        var lastError = "unknown"
        for (attempt in 0 until 3) {
            try {
                client.newCall(request).execute().use { resp ->
                    val body = resp.body.string()
                    when {
                        resp.isSuccessful -> return body
                        resp.code == 404 -> return null
                        else -> lastError = "Open Food Facts HTTP ${resp.code}"
                    }
                }
            } catch (e: IOException) {
                lastError = "Network error: ${e.message}"
            }
            if (attempt < 2) {
                Thread.sleep(waitMs)
                waitMs *= 2
            }
        }
        error("Lookup failed. $lastError")
    }

    private fun get(url: HttpUrl) = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

    fun fetch(barcode: String): FoodItem? = fetchProduct(barcode)?.item

    fun fetchProduct(barcode: String): OffProduct? {
        val url = "https://world.openfoodfacts.org/api/v2/product/$barcode.json".toHttpUrl()
            .newBuilder().addQueryParameter("fields", FIELDS).build()
        val body = call(get(url)) ?: return null
        val root = JSONObject(body)
        if (root.optInt("status", 0) != 1) return null
        val p = root.optJSONObject("product") ?: return null
        return parse(p, barcode) ?: error("This product has no calorie data in Open Food Facts")
    }

    /** Text search. Open Food Facts allows about 10 searches a minute, so searches are spaced out. */
    fun search(query: String, limit: Int = 20): List<OffProduct> {
        val wait = 6_500L - (System.currentTimeMillis() - lastSearchAt)
        if (wait > 0) Thread.sleep(wait)
        lastSearchAt = System.currentTimeMillis()

        val legacy = runCatching { searchLegacy(query, limit) }
        if (legacy.isSuccess && legacy.getOrThrow().isNotEmpty()) return legacy.getOrThrow()
        val modern = runCatching { searchLicious(query, limit) }
        return modern.getOrNull() ?: legacy.getOrThrow()      // throws the first error if both failed
    }

    private fun searchLegacy(query: String, limit: Int): List<OffProduct> {
        val url = "https://world.openfoodfacts.org/cgi/search.pl".toHttpUrl().newBuilder()
            .addQueryParameter("search_terms", query)
            .addQueryParameter("search_simple", "1")
            .addQueryParameter("action", "process")
            .addQueryParameter("json", "1")
            .addQueryParameter("page_size", limit.toString())
            .addQueryParameter("fields", FIELDS)
            .build()
        val arr = JSONObject(call(get(url)).orEmpty().ifBlank { "{}" }).optJSONArray("products")
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { parse(arr.getJSONObject(it), "") }
    }

    // Open Food Facts' newer full-text search service
    private fun searchLicious(query: String, limit: Int): List<OffProduct> {
        val url = "https://search.openfoodfacts.org/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page_size", limit.toString())
            .addQueryParameter("fields", FIELDS)
            .build()
        val arr = JSONObject(call(get(url)).orEmpty().ifBlank { "{}" }).optJSONArray("hits")
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { parse(arr.getJSONObject(it), "") }
    }

    /** Null when the product has no calorie data. */
    private fun parse(p: JSONObject, codeFallback: String): OffProduct? {
        val n = p.optJSONObject("nutriments") ?: return null
        fun v(key: String) = n.optDouble(key, Double.NaN)
        fun or0(d: Double) = if (d.isNaN()) 0.0 else d

        val kcal = v("energy-kcal_100g").takeUnless { it.isNaN() }
            ?: v("energy_100g").takeUnless { it.isNaN() }?.let { it / 4.184 }      // kJ -> kcal
            ?: return null
        val sodiumMg = v("sodium_100g").takeUnless { it.isNaN() }?.let { it * 1000 }
            ?: v("salt_100g").takeUnless { it.isNaN() }?.let { it / 2.5 * 1000 }
            ?: 0.0

        val code = p.optString("code").ifBlank { codeFallback }
        // The product API sends brands as "A, B"; the newer search service sends a list ["A", "B"]
        val brand = when (val b = p.opt("brands")) {
            is org.json.JSONArray -> if (b.length() > 0) b.optString(0) else ""
            null -> ""
            else -> b.toString()
        }.substringBefore(",").trim()
        val name = listOf(brand, p.optString("product_name")).filter { it.isNotBlank() }
            .joinToString(" ").ifBlank { if (code.isBlank()) return null else "Product $code" }
        val serving = p.optDouble("serving_quantity", 0.0).takeIf { it > 0 } ?: 100.0

        val tags = mutableSetOf<String>()
        val allergens = mutableListOf<String>()
        p.optJSONArray("allergens_tags")?.let { a ->
            for (i in 0 until a.length()) {
                val raw = a.getString(i).removePrefix("en:")
                allergens += raw.replace('-', ' ')
                when (raw) {
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

        val additives = mutableListOf<String>()
        p.optJSONArray("additives_tags")?.let { a ->
            for (i in 0 until a.length()) additives += a.getString(i).removePrefix("en:").uppercase()
        }

        val item = FoodItem(
            name = name,
            matchedName = "Open Food Facts",
            grams = serving,
            kcal100 = kcal,
            protein100 = or0(v("proteins_100g")),
            fat100 = or0(v("fat_100g")),
            satFat100 = or0(v("saturated-fat_100g")),
            carbs100 = or0(v("carbohydrates_100g")),
            sugar100 = or0(v("sugars_100g")),
            fiber100 = or0(v("fiber_100g")),
            sodium100 = sodiumMg,
            addedSugar100 = v("added-sugars_100g").takeIf { !it.isNaN() },
            tags = tags.toList()
        )
        return OffProduct(
            code = code,
            item = item,
            brand = brand,
            ingredients = p.optString("ingredients_text").takeIf { it.isNotBlank() },
            additives = additives,
            allergens = allergens.distinct(),
            nova = p.optInt("nova_group", 0).takeIf { it in 1..4 },
            nutriscore = p.optString("nutriscore_grade").takeIf { it.length == 1 && it[0] in 'a'..'e' }?.uppercase()
        )
    }
}
