package com.example.foodtracker.data

import android.content.Context

data class Goals(val kcal: Double, val sugar: Double, val fat: Double)

class GoalStore(context: Context) {
    private val prefs = context.getSharedPreferences("goals", Context.MODE_PRIVATE)

    // 0 means "no goal set"
    fun load() = Goals(
        kcal = prefs.getFloat("kcal", 0f).toDouble(),
        sugar = prefs.getFloat("sugar", 0f).toDouble(),
        fat = prefs.getFloat("fat", 0f).toDouble()
    )

    fun save(g: Goals) {
        prefs.edit()
            .putFloat("kcal", g.kcal.toFloat())
            .putFloat("sugar", g.sugar.toFloat())
            .putFloat("fat", g.fat.toFloat())
            .apply()
    }
}