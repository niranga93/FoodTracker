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
    // If you get a 404, pick a current free Flash model in AI Studio and change this.
    private const val MODEL = "gemini-1.5-flash"
    private const val URL =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    // Blank keys are ignored, so the app still works with only one key configured
    private val keys: List<String> = listOf(
        BuildConfig.GEMINI_API_KEY,
        BuildConfig.GEMINI_API_KEY_2,
        BuildConfig.GEMINI_API_KEY_3
    ).filter { it.isNotBlank() }

    // Per key: time until which we avoid it (after a 429 or an invalid-key response)
    private val coolingUntil = LongArray(3)
    private const val RATE_LIMIT_COOLDOWN_MS = 2 * 60 * 1000L
    private const val BAD_KEY_COOLDOWN_MS = 60 * 60 * 1000L

    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val PROMPT = """
You are a nutrition analyst. Identify every distinct food and drink in the photo
and estimate the portion of each in grams (ml for liquids). Include likely hidden
ingredients (cooking oil, sauces, dressings) as separate items when probable.
Use visible references (plate, cutlery, hands) for scale.
estimated_grams, grams_low and grams_high must be the EDIBLE weight as eaten
(exclude peel, bones, shells, pits, and inedible parts).
usda_search_term must be the plain food name as USDA would list it, e.g.
"Oranges, raw", "Rice, white, cooked", "Chicken, breast, roasted". No brand
names, and never include peel, skin, or rind unless that part is eaten.
For each item, "contains" lists which of these tags the item is LIKELY to contain, including
typical hidden ingredients for that dish (for example fried rice often contains eggs, soy and
sesame): peanuts, tree_nuts, milk, eggs, fish, shellfish, soy, gluten, sesame, meat, pork,
alcohol, honey. Use an empty list if none apply.
Return ONLY JSON, no other text, matching this schema:
{
  "meal_description": "string",
  "items": [
    {
      "name": "string",
      "usda_search_term": "string",
      "estimated_grams": 0,
      "grams_low": 0,
      "grams_high": 0,
      "confidence": "low|medium|high",
      "preparation": "string",
      "notes": "string",
      "contains": ["string"]
    }
  ],
  "overall_confidence": "low|medium|high"
}
"""

    suspend fun analyze(file: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val parts = JSONArray()
                .put(JSONObject().put("text", PROMPT))
                .put(
                    JSONObject().put(
                        "inline_data", JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", encodeImage(file))
                    )
                )
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                .put(
                    "generationConfig",
                    JSONObject().put("responseMimeType", "application/json")
                )

            val raw = callWithFailover(body.toString())
            val partsOut = JSONObject(raw)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            buildString {
                for (i in 0 until partsOut.length()) {
                    append(partsOut.getJSONObject(i).optString("text", ""))
                }
            }.replace("```json", "").replace("```", "").trim()
        }
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
     * Tries the available keys in order (key 1 preferred).
     * - 429 (limit reached): rest that key for a while and move to the next key immediately.
     * - 403 / invalid key: same, with a longer rest.
     * - 5xx or network error: back off and retry the whole round (up to 4 rounds).
     * - Any other error (e.g. 400, 404): fail fast, since another key won't help.
     */
    private suspend fun callWithFailover(bodyJson: String): String {
        check(keys.isNotEmpty()) { "No Gemini API key configured in local.properties" }
        var waitMs = 2000L
        var lastError = "unknown"

        for (round in 0 until 4) {
            val now = System.currentTimeMillis()
            // Keys that are not resting first, then the rest ordered by soonest recovery
            val order = keys.indices.sortedWith(
                compareBy({ coolingUntil[it] > now }, { coolingUntil[it] })
            )
            var retryable = false

            for (i in order) {
                try {
                    val request = Request.Builder()
                        .url(URL)
                        .header("x-goog-api-key", keys[i])
                        .post(bodyJson.toRequestBody("application/json".toMediaType()))
                        .build()
                    client.newCall(request).execute().use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            Log.d("Gemini", "success with key #${i + 1}")
                            return raw
                        }
                        lastError = "HTTP ${resp.code}: ${raw.take(300)}"
                        val keyInvalid = resp.code == 403 ||
                                raw.contains("API_KEY_INVALID") || raw.contains("API key not valid")
                        when {
                            resp.code == 429 -> {
                                Log.w("Gemini", "key #${i + 1} hit its limit, trying next")
                                coolingUntil[i] = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
                                lastError = "Gemini free limit reached (HTTP 429)"
                            }
                            keyInvalid -> {
                                Log.w("Gemini", "key #${i + 1} rejected, trying next")
                                coolingUntil[i] = System.currentTimeMillis() + BAD_KEY_COOLDOWN_MS
                            }
                            resp.code in listOf(500, 502, 503, 504) -> retryable = true
                            else -> error(lastError)   // bad request, wrong model, etc.
                        }
                    }
                } catch (e: IOException) {             // includes timeouts
                    lastError = "Network error: ${e.message}"
                    retryable = true
                }
            }

            if (!retryable) break                      // every key is out of quota or rejected
            if (round < 3) {
                delay(waitMs)
                waitMs *= 2                            // 2s, 4s, 8s
            }
        }

        error(
            if (lastError.contains("429"))
                "Gemini free limit reached on all keys. Try again in a few minutes."
            else
                "Gemini request failed. $lastError"
        )
    }

    private fun encodeImage(file: File): String {
        val bmp = ImageUtil.loadUpright(file, 2) ?: error("Could not read the photo")
        val scale = 1280f / max(bmp.width, bmp.height)
        val scaled = if (scale < 1f)
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}