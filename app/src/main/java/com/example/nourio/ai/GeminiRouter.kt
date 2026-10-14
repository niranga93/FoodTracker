package com.example.nourio.ai

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sends every Gemini request in the app (photo and text analysis, the coach).
 *
 * Models: the best free-tier model available to the user's keys is tried first. The list comes from
 * Google's model catalog (free to call, no quota used), keeping the Flash family, newest and stable first.
 *
 * Keys: round robin. Each request starts at the next key, so usage spreads over all saved keys.
 * A key that is rate-limited on the best model is skipped for that model only, and the next key is
 * tried before falling back to a lesser model.
 */
object GeminiRouter {
    // Used when the catalog can't be read
    private val FALLBACK_MODELS = listOf("gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash")
    private const val MAX_MODELS = 4
    private const val CATALOG_TTL_MS = 24 * 60 * 60 * 1000L
    private const val RATE_LIMIT_COOLDOWN_MS = 2 * 60 * 1000L
    private const val BAD_KEY_COOLDOWN_MS = 60 * 60 * 1000L
    private const val MODEL_GONE_COOLDOWN_MS = 60 * 60 * 1000L
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private enum class Outcome { NEXT_KEY, NEXT_MODEL }

    // Times until which something is avoided. Rate limits are per model and key, as Google's quotas are.
    private val badKeyUntil = ConcurrentHashMap<String, Long>()
    private val rateLimitedUntil = ConcurrentHashMap<String, Long>()     // "model|key"
    private val modelGoneUntil = ConcurrentHashMap<String, Long>()
    @Volatile private var seenKeysVersion = -1

    private val cursor = AtomicInteger(0)

    private val catalogLock = Mutex()
    @Volatile private var catalog: List<String> = emptyList()
    @Volatile private var catalogAt = 0L

    private fun tag(i: Int, model: String) = "$model key #${i + 1}"

    private fun clearCooldowns() {
        badKeyUntil.clear(); rateLimitedUntil.clear(); modelGoneUntil.clear()
    }

    /** Models to try, best first. Exposed for the key card. */
    suspend fun models(): List<String> {
        val keys = GeminiKeys.list()
        if (keys.isEmpty()) return FALLBACK_MODELS
        return modelsFor(keys)
    }

    private suspend fun modelsFor(keys: List<String>): List<String> = catalogLock.withLock {
        val now = System.currentTimeMillis()
        if (catalog.isNotEmpty() && now - catalogAt < CATALOG_TTL_MS) return@withLock catalog
        val fresh = keys.firstNotNullOfOrNull { k -> runCatching { listModels(k) }.getOrNull()?.takeIf { it.isNotEmpty() } }
        if (fresh != null) {
            catalog = fresh
            catalogAt = now
            Log.d("Gemini", "models: $fresh")
        }
        catalog.ifEmpty { FALLBACK_MODELS }
    }

    /** The key's generateContent models, ranked by [rank]. Blocking. */
    private fun listModels(key: String): List<String> {
        val names = mutableListOf<String>()
        var page: String? = null
        do {
            val url = "$BASE/models?pageSize=200" + (page?.let { "&pageToken=$it" } ?: "")
            val req = Request.Builder().url(url).header("x-goog-api-key", key).build()
            val body = client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("models HTTP ${r.code}")
                r.body.string()
            }
            val root = JSONObject(body)
            val arr = root.optJSONArray("models")
            if (arr != null) for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val methods = m.optJSONArray("supportedGenerationMethods")
                val generates = methods != null && (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                if (generates) names += m.optString("name").removePrefix("models/")
            }
            page = root.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        return rank(names)
    }

    // Free-tier text and vision models are the Flash family. Pro has little or no free quota, and the
    // special-purpose variants can't do photo-to-JSON analysis.
    private val EXCLUDE = listOf("image", "tts", "audio", "live", "embedding", "aqa", "computer-use", "robotics", "learnlm")
    private val VERSION = Regex("^gemini-(\\d+(?:\\.\\d+)?)-")

    /**
     * Stable before preview, newer before older, Flash before Flash-Lite, plain names before dated ones.
     * The best Flash-Lite is always kept as the last fallback: it is usually less busy when the full
     * Flash models are overloaded.
     */
    internal fun rank(names: List<String>): List<String> {
        val ranked = names.distinct()
            .filter { n -> n.startsWith("gemini-") && "flash" in n && EXCLUDE.none { it in n } }
            .mapNotNull { n -> VERSION.find(n)?.groupValues?.get(1)?.toDoubleOrNull()?.let { n to it } }
            .sortedWith(
                compareBy<Pair<String, Double>> { (n, _) -> "preview" in n || "exp" in n }
                    .thenByDescending { it.second }
                    .thenBy { (n, _) -> "lite" in n }
                    .thenBy { (n, _) -> n.length }
            )
            .map { it.first }
        val top = ranked.take(MAX_MODELS)
        val lite = ranked.firstOrNull { "lite" in it }
        return if (lite == null || lite in top) top else top.take(MAX_MODELS - 1) + lite
    }

    /**
     * Sends [bodyJson] to generateContent and returns the raw response.
     * - 429 (rate limit), invalid key, network error: next key, same model.
     * - 5xx (overloaded) or 404 (model not available): next model.
     * - Any other 400: fails at once, since no key or model would fix a malformed request.
     */
    suspend fun generate(bodyJson: String): String {
        val keys = GeminiKeys.list()
        check(keys.isNotEmpty()) { "No Gemini key yet. Add yours in Profile > Connections." }
        if (seenKeysVersion != GeminiKeys.version) {
            clearCooldowns()
            catalogAt = 0L                       // new keys may unlock different models
            seenKeysVersion = GeminiKeys.version
        }
        val models = modelsFor(keys)

        // Round robin: this request starts at the next key in turn
        val start = Math.floorMod(cursor.getAndIncrement(), keys.size)
        val order = keys.indices.map { (start + it) % keys.size }

        var waitMs = 2000L
        var lastError = "unknown"
        for (round in 0 until 4) {
            var triedAny = false
            for (model in models) {
                val now = System.currentTimeMillis()
                if ((modelGoneUntil[model] ?: 0L) > now) continue
                for (i in order) {
                    val key = keys[i]
                    if ((badKeyUntil[key] ?: 0L) > now || (rateLimitedUntil["$model|$key"] ?: 0L) > now) continue
                    triedAny = true
                    val outcome = try {
                        val request = Request.Builder()
                            .url("$BASE/models/$model:generateContent")
                            .header("x-goog-api-key", key)
                            .post(bodyJson.toRequestBody("application/json".toMediaType()))
                            .build()
                        client.newCall(request).execute().use { resp ->
                            val raw = resp.body.string()
                            if (resp.isSuccessful) {
                                Log.d("Gemini", "success with ${tag(i, model)}")
                                return raw
                            }
                            lastError = "$model HTTP ${resp.code}: ${raw.take(300)}"
                            Log.w("Gemini", "${tag(i, model)} failed ($lastError)")
                            val t = System.currentTimeMillis()
                            val badKey = resp.code == 401 || resp.code == 403 ||
                                (resp.code == 400 && "API_KEY_INVALID" in raw)
                            when {
                                resp.code == 429 -> { rateLimitedUntil["$model|$key"] = t + RATE_LIMIT_COOLDOWN_MS; Outcome.NEXT_KEY }
                                badKey -> { badKeyUntil[key] = t + BAD_KEY_COOLDOWN_MS; Outcome.NEXT_KEY }
                                resp.code == 404 -> { modelGoneUntil[model] = t + MODEL_GONE_COOLDOWN_MS; Outcome.NEXT_MODEL }
                                resp.code == 400 -> error("Gemini rejected the request. $lastError")
                                resp.code >= 500 -> Outcome.NEXT_MODEL
                                else -> Outcome.NEXT_KEY
                            }
                        }
                    } catch (e: IOException) {
                        lastError = "Network error: ${e.message}"
                        Log.w("Gemini", "${tag(i, model)} network error: ${e.message}")
                        Outcome.NEXT_KEY
                    }
                    if (outcome == Outcome.NEXT_MODEL) break
                }
            }
            // Everything is resting: clear the cool-downs and try again after the wait
            if (!triedAny) clearCooldowns()
            if (round < 3) {
                delay(waitMs)
                waitMs *= 2
            }
        }
        error("All Gemini API keys failed. Last error: $lastError")
    }
}
