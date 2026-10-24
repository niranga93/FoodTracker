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

/**
 * Sends every Gemini request in the app (photo and text analysis, the coach).
 *
 * Models: the best free-tier model available to the user's keys is tried first. The list comes from
 * Google's model catalog (free to call, no quota used), keeping the Flash family, newest and stable first.
 *
 * Keys: one key is used until it stops working. Every model is tried on it before moving to the
 * next key, and the key that worked last stays the current one for later requests (see [route]).
 */
object GeminiRouter {
    // Used when the catalog can't be read
    private val FALLBACK_MODELS = listOf("gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash")
    private const val MAX_MODELS = 4
    private const val CATALOG_TTL_MS = 24 * 60 * 60 * 1000L
    private const val RATE_LIMIT_COOLDOWN_MS = 2 * 60 * 1000L
    private const val BAD_KEY_COOLDOWN_MS = 60 * 60 * 1000L
    private const val MODEL_GONE_COOLDOWN_MS = 60 * 60 * 1000L
    private const val BUSY_COOLDOWN_MS = 60 * 1000L      // an overloaded model is skipped for a minute
    private const val ROUNDS = 3
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    // A reply usually takes 5-10 s. While other models are left to try, a model gets QUICK_S before the
    // next one is tried (one stuck model used to cost 40 s); the last option gets PATIENT_S.
    private const val QUICK_S = 20L
    private const val PATIENT_S = 45L

    // Times until which something is avoided. Rate limits are per model and key, as Google's quotas are.
    private val badKeyUntil = ConcurrentHashMap<String, Long>()
    private val rateLimitedUntil = ConcurrentHashMap<String, Long>()     // "model|key"
    private val modelGoneUntil = ConcurrentHashMap<String, Long>()
    private val busyUntil = ConcurrentHashMap<String, Long>()           // "model|key"
    @Volatile private var seenKeysVersion = -1

    // The key in use: the first one, or the last one that worked. Changes only when every model fails on it.
    @Volatile private var currentKey: String? = null
    // The model that answered last: tried first next time, so a busy best model isn't retried every request
    @Volatile private var currentModel: String? = null
    @Volatile private var currentModelAt = 0L
    // A fallback that answered during a busy spell is kept for a while, then the best model gets another turn
    private const val STICKY_MODEL_MS = 15 * 60 * 1000L

    private val catalogLock = Mutex()
    @Volatile private var catalog: List<String> = emptyList()
    @Volatile private var catalogAt = 0L

    private fun tag(i: Int, model: String) = "$model key #${i + 1}"

    private fun now() = System.currentTimeMillis()

    private fun clearCooldowns() {
        badKeyUntil.clear(); rateLimitedUntil.clear(); modelGoneUntil.clear(); busyUntil.clear()
    }

    /** Back to the first key with no cool-downs: after the keys change, and in tests. */
    internal fun reset() {
        clearCooldowns()
        currentKey = null
        currentModel = null
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
        return rank(names.filter { ModelCaps.isChat(it) })
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

    /** What one generateContent call came back with. */
    internal sealed interface Attempt {
        data class Ok(val raw: String) : Attempt
        data class Fail(val kind: Failure, val message: String) : Attempt
    }

    internal enum class Failure {
        RATE_LIMITED,   // 429: this model's quota on this key is used up
        BAD_KEY,        // 401/403/invalid key: no model will work with it
        MODEL_GONE,     // 404: the model isn't offered any more
        OVERLOADED,     // 5xx and anything else: try the next model
        NETWORK,        // no connection: switching key or model won't help
        REJECTED,       // other 400: the request itself is wrong
    }

    /**
     * Sends [bodyJson] to generateContent and returns the raw response. See [route] for the order.
     */
    suspend fun generate(bodyJson: String): String {
        val keys = GeminiKeys.list()
        check(keys.isNotEmpty()) { "No Gemini key yet. Add yours in Profile > Connections." }
        if (seenKeysVersion != GeminiKeys.version) {
            reset()
            catalogAt = 0L                       // new keys may unlock different models
            seenKeysVersion = GeminiKeys.version
        }
        return route(keys, modelsFor(keys), { model, key, patient -> send(model, key, bodyJson, patient) }, { delay(it) })
    }

    /**
     * The order every request follows:
     * 1. Start with the current key: the first key, or the last one that worked.
     * 2. Try the models on that key, best first. On success, keep the key for the next requests.
     * 3. Only when every model has failed on the key, move to the next key, and keep using that one.
     * 4. After all keys, wait and go round again (3 rounds in all).
     *
     * A model that hit its rate limit on a key is skipped on that key for 2 minutes, a bad key for an
     * hour, and a model that no longer exists for an hour. No connection ends the round at once without
     * changing key, and a malformed request fails at once, as no key or model would fix it.
     */
    internal suspend fun route(
        keys: List<String>,
        models: List<String>,
        send: suspend (model: String, key: String, patient: Boolean) -> Attempt,
        pause: suspend (Long) -> Unit,
    ): String {
        var waitMs = 2000L
        var lastError = "unknown"
        for (round in 0 until ROUNDS) {
            // A retry round is for the models that were busy a moment ago, so their short cooldown is lifted
            if (round > 0) busyUntil.clear()
            var triedAny = false
            val start = keys.indexOf(currentKey).coerceAtLeast(0)
        // The model that worked last goes first; the rest keep their best-first order
        val order = currentModel?.takeIf { it in models && now() - currentModelAt < STICKY_MODEL_MS }
            ?.let { listOf(it) + (models - it) } ?: models
            keyLoop@ for (step in keys.indices) {
                val i = (start + step) % keys.size
                val key = keys[i]
                if ((badKeyUntil[key] ?: 0L) > now()) continue
                val masked = AiProgress.maskKey(i, key)
                for ((mi, model) in order.withIndex()) {
                    val t = now()
                    if ((modelGoneUntil[model] ?: 0L) > t || (rateLimitedUntil["$model|$key"] ?: 0L) > t ||
                        (busyUntil["$model|$key"] ?: 0L) > t) continue
                    triedAny = true
                    AiProgress.trying(model, masked)
                    // Patient only when nothing else is left to try in this round, or on a retry round
                    val patient = round > 0 || (step == keys.size - 1 && mi == order.size - 1)
                    when (val a = send(model, key, patient)) {
                        is Attempt.Ok -> {
                            if (currentKey != key) Log.d("Gemini", "now using key #${i + 1}")
                            currentKey = key
                            currentModel = model
                            currentModelAt = now()
                            return a.raw
                        }
                        is Attempt.Fail -> {
                            lastError = a.message
                            Log.w("Gemini", "${tag(i, model)} failed (${a.message})")
                            when (a.kind) {
                                Failure.RATE_LIMITED -> {
                                    rateLimitedUntil["$model|$key"] = now() + RATE_LIMIT_COOLDOWN_MS
                                    AiProgress.event("$model on key $masked hit its limit, trying the next one")
                                }
                                Failure.MODEL_GONE -> {
                                    modelGoneUntil[model] = now() + MODEL_GONE_COOLDOWN_MS
                                    AiProgress.event("$model isn't available any more, skipping it")
                                }
                                Failure.OVERLOADED -> {
                                    busyUntil["$model|$key"] = now() + BUSY_COOLDOWN_MS
                                    AiProgress.event("$model is busy or slow, trying the next model")
                                }
                                Failure.BAD_KEY -> {
                                    badKeyUntil[key] = now() + BAD_KEY_COOLDOWN_MS
                                    AiProgress.event("Key $masked was rejected, switching key")
                                    break
                                }
                                Failure.NETWORK -> break@keyLoop
                                Failure.REJECTED -> error("Gemini rejected the request. ${a.message}")
                            }
                        }
                    }
                }
                // Every model failed on this key: the next key becomes the current one
                currentKey = keys[(i + 1) % keys.size]
            }
            // Everything is resting: clear the cool-downs and try again after the wait
            if (!triedAny) clearCooldowns()
            if (round < ROUNDS - 1) {
                AiProgress.event("Every model is busy, waiting ${waitMs / 1000} s before trying again")
                pause(waitMs)
                waitMs *= 2
            }
        }
        error("All Gemini API keys failed. Last error: $lastError")
    }

    private suspend fun send(model: String, key: String, bodyJson: String, patient: Boolean): Attempt = try {
        val request = Request.Builder()
            .url("$BASE/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        val call = client.newCall(request)
        call.timeout().timeout(if (patient) PATIENT_S else QUICK_S, TimeUnit.SECONDS)
        call.await().use { resp ->
            val raw = resp.body.string()
            if (resp.isSuccessful) Attempt.Ok(raw)
            else {
                val message = "$model HTTP ${resp.code}: ${raw.take(300)}"
                val kind = when {
                    resp.code == 429 -> Failure.RATE_LIMITED
                    resp.code == 401 || resp.code == 403 || (resp.code == 400 && "API_KEY_INVALID" in raw) -> Failure.BAD_KEY
                    resp.code == 404 -> Failure.MODEL_GONE
                    resp.code == 400 -> Failure.REJECTED
                    else -> Failure.OVERLOADED
                }
                Attempt.Fail(kind, message)
            }
        }
    } catch (e: java.io.InterruptedIOException) {
        // Couldn't even connect: the network is the problem, so stop rather than try every model and key.
        // Connected but no reply in time: this model is slow right now, so the next one gets a turn.
        val msg = e.message.orEmpty().lowercase()
        if ("connect" in msg) Attempt.Fail(Failure.NETWORK, "Network error: ${e.message}")
        else Attempt.Fail(Failure.OVERLOADED, "$model timed out")
    } catch (e: IOException) {
        Attempt.Fail(Failure.NETWORK, "Network error: ${e.message}")
    }
}
