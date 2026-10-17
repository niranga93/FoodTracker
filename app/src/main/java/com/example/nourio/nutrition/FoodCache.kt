package com.example.nourio.nutrition

import android.content.Context
import com.example.nourio.data.room.CacheRow
import com.example.nourio.data.room.FoodCacheDatabase
import org.json.JSONObject

/**
 * Nutrition lookups and the user's own match corrections. Backed by Room ([FoodCacheDatabase], the same
 * food_cache.db file), with the methods the app always used.
 */
class FoodCache(context: Context) {
    private val dao = FoodCacheDatabase.get(context.applicationContext).cache()

    fun get(key: String): NutritionLookup.Per100g? {
        val json = dao.json(key) ?: return null
        return runCatching {
            val o = JSONObject(json)
            NutritionLookup.Per100g(
                description = o.getString("description"),
                kcal = o.getDouble("kcal"), protein = o.getDouble("protein"),
                fat = o.getDouble("fat"), satFat = o.getDouble("satFat"),
                carbs = o.getDouble("carbs"), sugar = o.getDouble("sugar"),
                fiber = o.getDouble("fiber"), sodiumMg = o.getDouble("sodiumMg"),
                addedSugar = if (o.isNull("addedSugar")) null else o.optDouble("addedSugar").takeIf { !it.isNaN() }
            )
        }.getOrNull()
    }

    fun put(key: String, v: NutritionLookup.Per100g) {
        val json = JSONObject()
            .put("description", v.description)
            .put("kcal", v.kcal).put("protein", v.protein)
            .put("fat", v.fat).put("satFat", v.satFat)
            .put("carbs", v.carbs).put("sugar", v.sugar)
            .put("fiber", v.fiber).put("sodiumMg", v.sodiumMg)
            .put("addedSugar", v.addedSugar ?: JSONObject.NULL)
            .toString()
        putRaw(key, json)
    }

    /** Raw cache rows whose key starts with the prefix (used for saved match corrections). */
    fun dumpPrefix(prefix: String): Map<String, String> = dao.withPrefix(prefix).associate { it.key to it.json }

    fun putRaw(key: String, json: String) = dao.upsert(CacheRow(key, json, System.currentTimeMillis()))

    fun deletePrefix(prefix: String) = dao.deletePrefix(prefix)
}
