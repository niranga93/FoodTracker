package com.example.nourio.ai

import androidx.core.graphics.scale
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

object FoodAnalyzer {
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
If the text is a recipe (an ingredient list, possibly with a yield), estimate ONE serving:
divide every amount by the stated number of servings (assume 2 if none is stated) and add the
serving count to meal_description, e.g. "Chicken curry (1 of 4 servings)".
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
added_sugar_per_100g is the part of the sugar that is added or free (sugar, syrups and honey added
to the food, and the sugar in fruit juice). Sugar naturally inside whole fruit, vegetables, plain
milk and plain yogurt is NOT added. It can never be more than sugar_per_100g.
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
                "added_sugar_per_100g" to num(),
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

    /** Every request goes through [GeminiRouter]: one key until it fails, every model on it first. */
    private suspend fun callWithFailover(bodyJson: String): String = GeminiRouter.generate(bodyJson)

    private fun encodeImage(file: File): String {
        val bmp = ImageUtil.loadUpright(file, 1280) ?: error("Could not read the photo")
        val scale = 1280f / max(bmp.width, bmp.height)
        val scaled = if (scale < 1f)
            bmp.scale((bmp.width * scale).toInt(), (bmp.height * scale).toInt())
        else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}