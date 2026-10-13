package com.example.foodtracker.data

import android.content.Context

data class Goals(
    val kcal: Double,
    val sugar: Double,
    val fat: Double,
    val satFat: Double = 0.0,
    val sodiumMg: Double = 0.0,
    val carbs: Double = 0.0,
    val protein: Double = 0.0,
    val fiber: Double = 0.0
)

class GoalStore(context: Context) {
    private val prefs = context.getSharedPreferences("goals", Context.MODE_PRIVATE)

    // 0 means "no goal set"
    private fun d(key: String) = prefs.getFloat(key, 0f).toDouble()

    fun load() = Goals(
        kcal = d("kcal"),
        sugar = d("sugar"),
        fat = d("fat"),
        satFat = d("satFat"),
        sodiumMg = d("sodium"),
        carbs = d("carbs"),
        protein = d("protein"),
        fiber = d("fiber")
    )

    fun save(g: Goals) {
        prefs.edit()
            .putFloat("kcal", g.kcal.toFloat())
            .putFloat("sugar", g.sugar.toFloat())
            .putFloat("fat", g.fat.toFloat())
            .putFloat("satFat", g.satFat.toFloat())
            .putFloat("sodium", g.sodiumMg.toFloat())
            .putFloat("carbs", g.carbs.toFloat())
            .putFloat("protein", g.protein.toFloat())
            .putFloat("fiber", g.fiber.toFloat())
            .apply()
    }
}
