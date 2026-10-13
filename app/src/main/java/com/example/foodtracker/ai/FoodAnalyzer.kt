package com.example.foodtracker.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.example.foodtracker.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.max

object FoodAnalyzer {
    // Tried in order. When a model is overloaded (5xx), rate-limited or gone (404), the next one is used.
    private val MODELS = listOf("gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash")

    private fun urlFor(model: String) =
        "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

    // Blank keys are ignored, so the app still works with only one key configured
    private val keys: List<String> = listOf(
        BuildConfig.GEMINI_API_KEY,
        BuildConfig.GEMINI_API_KEY_2,
        BuildConfig.GEMINI_API_KEY_3
    ).filter { it.isNotBlank() }

    // Times until which we avoid something. Rate limits are per model and key, since quotas are.
    private val badKeyUntil = LongArray(3)
    private val rateLimitedUntil = Array(MODELS.size) { LongArray(3) }
    private val modelGoneUntil = LongArray(MODELS.size)
    private const val RATE_LIMIT_COOLDOWN_MS = 2 * 60 * 1000L
    private const val BAD_KEY_COOLDOWN_MS = 60 * 60 * 1000L
    private const val MODEL_GONE_COOLDOWN_MS = 60 * 60 * 1000L

    private enum class Outcome { NEXT_KEY, NEXT_MODEL }

    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val PHOTO_INTRO = """
You are a nutrition analyst. Identify every distinct food and drink in the photo
and estimate the portion of each in grams (ml for liquids). Include likely hidden
ingredients (cooking oil, sauces, dressings) as separate items when probable.
Use visible references (plate, cutlery, hands) for scale.
"""

    private const val TEXT_INTRO = """
You are a nutrition analyst. The user describes a meal in their own words (it may come from
speech recognition, so allow for small transcription errors). List every distinct food and
drink they mention and estimate the portion of each in grams (ml for liquids). Use the amounts
they give ("2 eggs", "a large bowl", "half a cup"); when no amount is given, assume a typical
single adult portion. Include likely hidden ingredients (cooking oil, butter, sauces) as
separate items only when the description implies them, e.g. "fried" or "buttered".
Use their wording for meal_description, tidied up.
"""

    private const val RULES = """
estimated_grams, grams_low and grams_high must be the EDIBLE weight as eaten
(exclude peel, bones, shells, pits, and inedible parts). grams_low and grams_high
are a realistic range around estimated_grams.
usda_search_term must be the plain food name as USDA would list it, including the
cooking method when it changes the nutrition, e.g. "Oranges, raw",
"Rice, white, cooked", "Chicken, breast, roasted", "Potatoes, french fried". No brand
names, and never include peel, skin, or rind unless that part is eaten.
Also give your best estimate of the nutrition per 100 g of each item as eaten
(kcal, protein, fat, saturated fat, carbs, sugar and fiber in grams, sodium in mg).
These are only used when no database match is found.
For each item, "contains" lists which of these tags the item is LIKELY to contain, including
typical hidden ingredients for that dish (for example fried rice often contains eggs, soy and
sesame): peanuts, tree_nuts, milk, eggs, fish, shellfish, soy, gluten, sesame, meat, pork,
alcohol, honey. Use an empty list if none apply.
"""

    private fun str() = JSONObject().put("type", "STRING")
    private fun num() = JSONObject().put("type", "NUMBER")
    private fun level() = str().put("enum", JSONArray(listOf("low", "medium", "high")))

    private fun obj(props: List<Pair<String, JSONObject>>): JSONObject {
        val names = JSONArray(props.map { it.first })
        val p = JSONObject()
        props.forEach { (k, v) -> p.put(k, v) }
        return JSONObject()
            .put("type", "OBJECT")
            .put("properties", p)
            .put("required", names)
            .put("propertyOrdering", names)
    }

    /** Gemini structured output, so the reply is always valid JSON in this shape. */
    private val RESPONSE_SCHEMA: JSONObject by lazy {
        val item = obj(
            listOf(
                "name" to str(),
                "usda_search_term" to str(),
                "estimated_grams" to num(),
                "grams_low" to num(),
                "grams_high" to num(),
                "confidence" to level(),
                "preparation" to str(),
                "notes" to str(),
                "contains" to JSONObject().put("type", "ARRAY").put("items", str()),
                "kcal_per_100g" to num(),
                "protein_per_100g" to num(),
                "fat_per_100g" to num(),
                "sat_fat_per_100g" to num(),
                "carbs_per_100g" to num(),
                "sugar_per_100g" to num(),
                "fiber_per_100g" to num(),
                "sodium_mg_per_100g" to num()
            )
        )
        obj(
            listOf(
                "meal_description" to str(),
                "items" to JSONObject().put("type", "ARRAY").put("items", item),
                "overall_confidence" to level()
            )
        )
    }

    suspend fun analyze(file: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            analyzeParts(
                JSONArray()
                    .put(JSONObject().put("text", PHOTO_INTRO + RULES))
                    .put(
                        JSONObject().put(
                            "inline_data", JSONObject()
                                .put("mime_type", "image/jpeg")
                                .put("data", encodeImage(file))
                        )
                    )
            )
        }
    }

    /** Same JSON as [analyze], from a typed or spoken meal description. */
    suspend fun analyzeText(description: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            analyzeParts(
                JSONArray()
                    .put(JSONObject().put("text", TEXT_INTRO + RULES))
                    .put(JSONObject().put("text", "Meal: $description"))
            )
        }
    }

    private suspend fun analyzeParts(parts: JSONArray): String {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", RESPONSE_SCHEMA)
            )

        val raw = callWithFailover(body.toString())
        val partsOut = JSONObject(raw)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        return buildString {
            for (i in 0 until partsOut.length()) {
                append(partsOut.getJSONObject(i).optString("text", ""))
            }
        }.trim()
    }

    /** Text-only request, used by the AI coach. */
    suspend fun askText(system: String, user: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
                )
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", user)))
                    )
                )
                .put("generationConfig", JSONObject().put("temperature", 0.4).put("maxOutputTokens", 1024))

            val raw = callWithFailover(body.toString())
            val partsOut = JSONObject(raw)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            buildString {
                for (i in 0 until partsOut.length()) append(partsOut.getJSONObject(i).optString("text", ""))
            }.trim()
        }
    }

    /**
     * Tries each model in [MODELS] with each available key.
     * - 429 (rate limit), invalid key, network error: next key, same model.
     * - 5xx (overloaded) or 404 (model not available): next model.
     * - Any other 400: fails at once, since no key or model would fix a malformed request.
     */
    private suspend fun callWithFailover(bodyJson: String): String {
        check(keys.isNotEmpty()) { "No Gemini API key configured in local.properties" }
        var waitMs = 2000L
        var lastError = "unknown"

        for (round in 0 until 4) {
            var triedAnyInRound = false

            for (m in MODELS.indices) {
                val now = System.currentTimeMillis()
                if (modelGoneUntil[m] > now) continue
                val rateUntil = rateLimitedUntil[m]

                for (i in keys.indices) {
                    if (badKeyUntil[i] > now || rateUntil[i] > now) continue
                    triedAnyInRound = true
                    val tag = "${MODELS[m]} key #${i + 1}"

                    val outcome = try {
                        val request = Request.Builder()
                            .url(urlFor(MODELS[m]))
                            .header("x-goog-api-key", keys[i])
                            .post(bodyJson.toRequestBody("application/json".toMediaType()))
                            .build()
                        client.newCall(request).execute().use { resp ->
                            val raw = resp.body?.string().orEmpty()
                            if (resp.isSuccessful) {
                                Log.d("Gemini", "success with $tag")
                                return raw
                            }
                            lastError = "${MODELS[m]} HTTP ${resp.code}: ${raw.take(300)}"
                            Log.w("Gemini", "$tag failed ($lastError)")

                            val badKey = resp.code == 401 || resp.code == 403 ||
                                (resp.code == 400 && "API_KEY_INVALID" in raw)
                            val t = System.currentTimeMillis()
                            when {
                                resp.code == 429 -> {
                                    rateUntil[i] = t + RATE_LIMIT_COOLDOWN_MS
                                    Outcome.NEXT_KEY
                                }
                                badKey -> {
                                    badKeyUntil[i] = t + BAD_KEY_COOLDOWN_MS
                                    Outcome.NEXT_KEY
                                }
                                resp.code == 404 -> {
                                    modelGoneUntil[m] = t + MODEL_GONE_COOLDOWN_MS
                                    Outcome.NEXT_MODEL
                                }
                                resp.code == 400 -> error("Gemini rejected the request. $lastError")
                                resp.code >= 500 -> Outcome.NEXT_MODEL
                                else -> Outcome.NEXT_KEY
                            }
                        }
                    } catch (e: IOException) {
                        lastError = "Network error: ${e.message}"
                        Log.w("Gemini", "$tag network error: ${e.message}")
                        Outcome.NEXT_KEY
                    }
                    if (outcome == Outcome.NEXT_MODEL) break
                }
            }

            if (!triedAnyInRound) {
                // Everything is resting: clear the cool-downs and try again after the wait
                badKeyUntil.fill(0L)
                modelGoneUntil.fill(0L)
                rateLimitedUntil.forEach { it.fill(0L) }
            }

            if (round < 3) {
                delay(waitMs)
                waitMs *= 2
            }
        }

        error("All Gemini API keys failed. Last error: $lastError")
    }

    private fun encodeImage(file: File): String {
        val bmp = ImageUtil.loadUpright(file, 1280) ?: error("Could not read the photo")
        val scale = 1280f / max(bmp.width, bmp.height)
        val scaled = if (scale < 1f)
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}