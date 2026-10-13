package com.example.foodtracker.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

class MealDb(context: Context) : SQLiteOpenHelper(context, "meals.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE meals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                description TEXT NOT NULL,
                items_json TEXT NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insert(
        description: String,
        items: List<FoodItem>,
        timestamp: Long = System.currentTimeMillis()
    ): Long {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        val values = ContentValues().apply {
            put("ts", timestamp)
            put("description", description)
            put("items_json", arr.toString())
        }
        return writableDatabase.insert("meals", null, values)
    }
    fun getById(id: Long): Meal? {
        readableDatabase.rawQuery(
            "SELECT id, ts, description, items_json FROM meals WHERE id = ?",
            arrayOf(id.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            val arr = JSONArray(c.getString(3))
            val items = (0 until arr.length()).map { FoodItem.fromJson(arr.getJSONObject(it)) }
            return Meal(c.getLong(0), c.getLong(1), c.getString(2), items)
        }
    }

    fun update(id: Long, description: String, items: List<FoodItem>, timestamp: Long) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        val v = ContentValues().apply {
            put("ts", timestamp)
            put("description", description)
            put("items_json", arr.toString())
        }
        writableDatabase.update("meals", v, "id = ?", arrayOf(id.toString()))
    }

    fun updateTimestamp(id: Long, timestamp: Long) {
        val values = ContentValues().apply { put("ts", timestamp) }
        writableDatabase.update("meals", values, "id = ?", arrayOf(id.toString()))
    }

    /** Distinct foods from your history, newest first. */
    fun recentItems(limit: Int = 200): List<FoodItem> =
        all().flatMap { it.items }
            .distinctBy { it.name.trim().lowercase() + "|" + (it.matchedName ?: "") }
            .take(limit)
    fun delete(id: Long) {
        writableDatabase.delete("meals", "id = ?", arrayOf(id.toString()))
    }
    fun all(): List<Meal> = between(0L, Long.MAX_VALUE)

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM meals", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun replaceAll(meals: List<Meal>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("meals", null, null)
            for (m in meals) {
                val arr = JSONArray()
                m.items.forEach { arr.put(it.toJson()) }
                val v = ContentValues().apply {
                    put("ts", m.timestamp)
                    put("description", m.description)
                    put("items_json", arr.toString())
                }
                db.insert("meals", null, v)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun between(startMs: Long, endMs: Long): List<Meal> {
        val out = mutableListOf<Meal>()
        readableDatabase.rawQuery(
            "SELECT id, ts, description, items_json FROM meals " +
                    "WHERE ts >= ? AND ts < ? ORDER BY ts DESC",
            arrayOf(startMs.toString(), endMs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val arr = JSONArray(c.getString(3))
                val items = (0 until arr.length()).map { FoodItem.fromJson(arr.getJSONObject(it)) }
                out.add(Meal(c.getLong(0), c.getLong(1), c.getString(2), items))
            }
        }
        return out
    }
}