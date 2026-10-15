package com.example.nourio.nutrition

import com.example.nourio.BuildConfig
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import android.content.Context
import android.util.Log

object NutritionLookup {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Per100g(
        val description: String,
        val kcal: Double, val protein: Double, val fat: Double,
        val satFat: Double, val carbs: Double, val sugar: Double,
        val fiber: Double, val sodiumMg: Double,
        val addedSugar: Double? = null     // null when the source doesn't list it
    )

    // Try generic foods first, then fall back to everything (branded included)
    // Bump this number if you change the matching/scoring logic, so old cached matches are ignored
    private const val CACHE_VERSION = 1

    private lateinit var cache: FoodCache

    fun init(context: Context) {
        if (!::cache.isInitialized) cache = FoodCache(context.applicationContext)
    }

    /** Matches the user picked by hand, for the backup. */
    fun exportCorrections(): Map<String, String> =
        if (::cache.isInitialized) cache.dumpPrefix("fix|") else emptyMap()

    fun importCorrections(map: Map<String, String>) {
        if (!::cache.isInitialized) return
        cache.deletePrefix("fix|")
        map.forEach { (k, v) -> cache.putRaw(k, v) }
    }

    /** A match the user picked by hand for this food name, if any. */
    fun correctionFor(itemName: String): Per100g? =
        if (::cache.isInitialized) cache.get("fix|" + itemName.trim().lowercase()) else null

    fun saveCorrection(itemName: String, p: Per100g) {
        if (::cache.isInitialized) cache.put("fix|" + itemName.trim().lowercase(), p)
    }

    // Try generic foods first, then fall back to everything (branded included).
// Successful results are cached; "no match" is never cached since it may be a temporary failure.
    fun search(term: String): Per100g? {
        val key = "v$CACHE_VERSION|" + term.trim().lowercase()
        if (::cache.isInitialized) {
            cache.get(key)?.let {
                if (BuildConfig.DEBUG) Log.d("FoodCache", "hit: $key")
                return it
            }
        }
        val found = query(term, "Foundation,SR Legacy") ?: query(term, null)
        if (found != null && ::cache.isInitialized) {
            cache.put(key, found)
            if (BuildConfig.DEBUG) Log.d("FoodCache", "stored: $key")
        }
        return found
    }

    /** Top results for a typed food name, per 100 g. Blocking: call from Dispatchers.IO. */
    fun searchMany(term: String, limit: Int = 10): List<Per100g> {
        val url = HttpUrl.Builder()
            .scheme("https").host("api.nal.usda.gov")
            .addPathSegments("fdc/v1/foods/search")
            .addQueryParameter("query", term)
            .addQueryParameter("pageSize", limit.toString())
            .addQueryParameter("api_key", BuildConfig.USDA_API_KEY)
            .build()
        val foods = JSONObject(getWithRetry(url)).optJSONArray("foods") ?: return emptyList()
        val out = ArrayList<Per100g>()
        for (i in 0 until foods.length()) {
            parseFood(foods.getJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    private fun parseFood(food: JSONObject): Per100g? {
        val nutrients = food.optJSONArray("foodNutrients") ?: return null
        val map = HashMap<Int, Double>()
        var sugarByName: Double? = null
        for (i in 0 until nutrients.length()) {
            val n = nutrients.getJSONObject(i)
            val value = n.optDouble("value", 0.0)
            map[n.optInt("nutrientId")] = value
            if (n.optString("nutrientName").startsWith("Sugars, total", ignoreCase = true)) {
                sugarByName = value
            }
        }
        fun g(vararg ids: Int): Double {
            for (id in ids) {
                map[id]?.let { return it }
            }
            return 0.0
        }
        return Per100g(
            description = food.optString("description"),
            kcal = g(1008, 2047, 2048),
            protein = g(1003),
            fat = g(1004),
            satFat = g(1258),
            carbs = g(1005),
            sugar = sugarByName ?: g(2000, 1063),
            fiber = g(1079),
            sodiumMg = g(1093),
            addedSugar = map[1235]             // "Sugars, added": mostly branded foods
        )
    }

    // Blocking call with retry; only used from Dispatchers.IO
    private fun getWithRetry(url: HttpUrl): String {
        var waitMs = 2000L
        var lastError = "unknown"
        for (attempt in 0 until 4) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    val raw = resp.body.string()
                    if (resp.isSuccessful) return raw
                    lastError = "USDA HTTP ${resp.code}"
                    // Retry only temporary errors; fail fast on bad key etc.
                    if (resp.code !in listOf(429, 500, 502, 503, 504)) error(lastError)
                }
            } catch (e: IOException) {          // includes timeouts
                lastError = "USDA network error: ${e.message}"
            }
            if (attempt < 3) {
                Thread.sleep(waitMs)
                waitMs *= 2                      // 2s, 4s, 8s
            }
        }
        error("USDA failed after 4 attempts. Last error: $lastError")
    }

    private val BAD_WORDS = setOf(
        "peel", "juice", "dried", "canned", "frozen", "concentrate", "syrup",
        "babyfood", "baby", "pie", "cake", "candy", "drink", "beverage", "sauce",
        "jam", "jelly", "cereal", "bar", "sorbet", "smoothie", "dehydrated",
        "powder", "oil", "extract", "rind", "zest"
    )

    private fun words(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z]+"))
            .filter { it.length > 1 }
            .map { it.removeSuffix("s") }
            .toSet()

    private fun score(term: String, desc: String): Int {
        val t = words(term)
        val d = words(desc)
        val matches = t.count { it in d }
        val badExtras = (d intersect BAD_WORDS).minus(t).size
        val firstWordBonus = if (words(desc.substringBefore(",")).any { it in t }) 3 else 0
        val extraWords = (d - t).size
        return 3 * matches - 4 * badExtras + firstWordBonus - extraWords
    }
    private fun query(term: String, dataType: String?): Per100g? {
        val url = HttpUrl.Builder()
            .scheme("https").host("api.nal.usda.gov")
            .addPathSegments("fdc/v1/foods/search")
            .addQueryParameter("query", term)
            .addQueryParameter("pageSize", "10")
            .addQueryParameter("api_key", BuildConfig.USDA_API_KEY)
            .apply { if (dataType != null) addQueryParameter("dataType", dataType) }
            .build()

        val raw = getWithRetry(url)
        val foods = JSONObject(raw).optJSONArray("foods")
        if (foods == null || foods.length() == 0) return null

        // Pick the best-scoring candidate; earlier results win ties
        var food: JSONObject? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until foods.length()) {
            val f = foods.getJSONObject(i)
            val sc = score(term, f.optString("description")) * 10 - i
            if (sc > bestScore) { bestScore = sc; food = f }
        }
        val chosen = food ?: return null
        return parseFood(chosen)
    }
}
