package com.example.nourio.data

import androidx.core.database.sqlite.transaction
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

class MealDb(context: Context) : SQLiteOpenHelper(context, "meals.db", null, 2) {
    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE meals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                description TEXT NOT NULL,
                items_json TEXT NOT NULL
            )"""
        )
        db.execSQL(CREATE_TS_INDEX)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL(CREATE_TS_INDEX)
    }

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
        query("SELECT id, ts, description, items_json FROM meals ORDER BY ts DESC LIMIT 500", emptyArray())
            .flatMap { it.items }
            .distinctBy { it.name.trim().lowercase() + "|" + (it.matchedName ?: "") }
            .take(limit)
    fun delete(id: Long) {
        writableDatabase.delete("meals", "id = ?", arrayOf(id.toString()))
        MealPhotos.delete(appContext, id)
    }
    fun all(): List<Meal> = between(0L, Long.MAX_VALUE)

    /** Every meal time, oldest first, without loading the items (for streaks and badges). */
    fun timestamps(): List<Long> {
        val out = ArrayList<Long>()
        readableDatabase.rawQuery("SELECT ts FROM meals ORDER BY ts", null).use { c ->
            while (c.moveToNext()) out += c.getLong(0)
        }
        return out
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM meals", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun replaceAll(meals: List<Meal>) {
        writableDatabase.transaction {
            delete("meals", null, null)
            for (m in meals) {
                val arr = JSONArray()
                m.items.forEach { arr.put(it.toJson()) }
                val v = ContentValues().apply {
                    put("ts", m.timestamp)
                    put("description", m.description)
                    put("items_json", arr.toString())
                }
                insert("meals", null, v)
            }
        }
    }

    fun between(startMs: Long, endMs: Long): List<Meal> =
        query(
            "SELECT id, ts, description, items_json FROM meals " +
                    "WHERE ts >= ? AND ts < ? ORDER BY ts DESC",
            arrayOf(startMs.toString(), endMs.toString())
        )

    private fun query(sql: String, args: Array<String>): List<Meal> {
        val out = mutableListOf<Meal>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                val arr = JSONArray(c.getString(3))
                val items = (0 until arr.length()).map { FoodItem.fromJson(arr.getJSONObject(it)) }
                out.add(Meal(c.getLong(0), c.getLong(1), c.getString(2), items))
            }
        }
        return out
    }

    private companion object {
        const val CREATE_TS_INDEX = "CREATE INDEX IF NOT EXISTS idx_meals_ts ON meals(ts)"
    }
}