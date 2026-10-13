package com.example.foodtracker.nutrition

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

class FoodCache(context: Context) : SQLiteOpenHelper(context, "food_cache.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE cache (key TEXT PRIMARY KEY, json TEXT NOT NULL, ts INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun get(key: String): NutritionLookup.Per100g? {
        readableDatabase.rawQuery("SELECT json FROM cache WHERE key = ?", arrayOf(key)).use { c ->
            if (!c.moveToFirst()) return null
            return runCatching {
                val o = JSONObject(c.getString(0))
                NutritionLookup.Per100g(
                    description = o.getString("description"),
                    kcal = o.getDouble("kcal"), protein = o.getDouble("protein"),
                    fat = o.getDouble("fat"), satFat = o.getDouble("satFat"),
                    carbs = o.getDouble("carbs"), sugar = o.getDouble("sugar"),
                    fiber = o.getDouble("fiber"), sodiumMg = o.getDouble("sodiumMg")
                )
            }.getOrNull()
        }
    }

    fun put(key: String, v: NutritionLookup.Per100g) {
        val json = JSONObject()
            .put("description", v.description)
            .put("kcal", v.kcal).put("protein", v.protein)
            .put("fat", v.fat).put("satFat", v.satFat)
            .put("carbs", v.carbs).put("sugar", v.sugar)
            .put("fiber", v.fiber).put("sodiumMg", v.sodiumMg)
            .toString()
        val values = ContentValues().apply {
            put("key", key)
            put("json", json)
            put("ts", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "cache", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }
}