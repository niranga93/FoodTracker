package com.example.foodtracker.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class WaterStore(context: Context) {
    private val p = context.getSharedPreferences("water", Context.MODE_PRIVATE)

    private fun key(offset: Int): String {
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
        return "d_" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
    }

    fun get(offset: Int): Int = p.getInt(key(offset), 0)

    /** Adds (or subtracts) ml for the day and returns the new total (never below 0). */
    fun add(offset: Int, ml: Int): Int {
        val v = (get(offset) + ml).coerceAtLeast(0)
        p.edit().putInt(key(offset), v).apply()
        return v
    }

    // A neutral starting point the user can change
    var goalMl: Int
        get() = p.getInt("goal", 2000)
        set(v) { p.edit().putInt("goal", v).apply() }

    /** date (yyyy-MM-dd) -> ml, for every day with a recorded amount. */
    fun allDays(): Map<String, Int> =
        p.all.entries
            .filter { it.key.startsWith("d_") && it.value is Int && (it.value as Int) > 0 }
            .associate { it.key.removePrefix("d_") to (it.value as Int) }
}
