package com.example.foodtracker.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class WeightEntry(val id: Long, val ts: Long, val kg: Double)

class WeightDb(context: Context) : SQLiteOpenHelper(context, "weights.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE weights (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, kg REAL NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun add(ts: Long, kg: Double): Long {
        val v = ContentValues().apply {
            put("ts", ts)
            put("kg", kg)
        }
        return writableDatabase.insert("weights", null, v)
    }

    fun delete(id: Long) {
        writableDatabase.delete("weights", "id = ?", arrayOf(id.toString()))
    }

    /** Oldest first. */
    fun all(): List<WeightEntry> {
        val out = mutableListOf<WeightEntry>()
        readableDatabase.rawQuery("SELECT id, ts, kg FROM weights ORDER BY ts ASC", null).use { c ->
            while (c.moveToNext()) out.add(WeightEntry(c.getLong(0), c.getLong(1), c.getDouble(2)))
        }
        return out
    }
}
