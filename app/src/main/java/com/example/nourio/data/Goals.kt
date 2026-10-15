package com.example.nourio.data

import androidx.core.content.edit
import android.content.Context

data class Goals(
    val kcal: Double,
    val addedSugar: Double,              // limit for added sugar; natural sugar has no limit
    val fat: Double,
    val satFat: Double = 0.0,
    val sodiumMg: Double = 0.0,
    val carbs: Double = 0.0,
    val protein: Double = 0.0,
    val fiber: Double = 0.0,
    val steps: Double = 0.0              // daily step goal; 0 = the default 10,000
) {
    val stepGoal: Long get() = if (steps > 0) steps.toLong() else 10_000L
}

class GoalStore(context: Context) {
    private val prefs = context.getSharedPreferences("goals", Context.MODE_PRIVATE)

    // 0 means "no goal set"
    private fun d(key: String) = prefs.getFloat(key, 0f).toDouble()

    fun load() = Goals(
        kcal = d("kcal"),
        addedSugar = d("addedSugar"),        // the old "sugar" key held a total-sugar limit and is no longer used
        fat = d("fat"),
        satFat = d("satFat"),
        sodiumMg = d("sodium"),
        carbs = d("carbs"),
        protein = d("protein"),
        fiber = d("fiber"),
        steps = d("steps")
    )

    fun save(g: Goals) {
        prefs.edit {
            putFloat("kcal", g.kcal.toFloat())
            putFloat("addedSugar", g.addedSugar.toFloat())
            putFloat("fat", g.fat.toFloat())
            putFloat("satFat", g.satFat.toFloat())
            putFloat("sodium", g.sodiumMg.toFloat())
            putFloat("carbs", g.carbs.toFloat())
            putFloat("protein", g.protein.toFloat())
            putFloat("fiber", g.fiber.toFloat())
            putFloat("steps", g.steps.toFloat())
        }
    }
}
