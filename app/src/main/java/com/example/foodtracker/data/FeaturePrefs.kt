package com.example.foodtracker.data

import android.content.Context

data class Feature(val key: String, val title: String, val hint: String, val group: String)

class FeaturePrefs(context: Context) {
    private val p = context.getSharedPreferences("features", Context.MODE_PRIVATE)

    fun isOn(key: String): Boolean = p.getBoolean(key, true)
    fun set(key: String, on: Boolean) { p.edit().putBoolean(key, on).apply() }

    companion object {
        val ALL = listOf(
            Feature("budget", "Daily budget", "Goal with carry-over", "Home"),
            Feature("week", "7-day chart", "Eaten vs burned", "Home"),
            Feature("steps", "Steps", "Steps and walking target", "Home"),
            Feature("forecast", "Weight trend", "Latest weight and pace", "Home"),
            Feature("macros", "Macros", "Carbs, protein, fat", "Intake"),
            Feature("water", "Water", "Daily tracker", "Intake"),
            Feature("coach", "AI coach", "Daily summary", "Intake"),
            Feature("diet", "Diet warnings", "Diets and allergies", "Smart")
        )
    }
}
