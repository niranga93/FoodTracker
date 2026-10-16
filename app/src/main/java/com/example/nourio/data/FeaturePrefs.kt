package com.example.nourio.data

import androidx.core.content.edit
import android.content.Context

data class Feature(val key: String, val title: String, val hint: String, val group: String)

class FeaturePrefs(context: Context) {
    private val p = context.getSharedPreferences("features", Context.MODE_PRIVATE)

    fun isOn(key: String): Boolean = p.getBoolean(key, true)
    fun set(key: String, on: Boolean) { p.edit { putBoolean(key, on) } }

    companion object {
        val ALL = listOf(
            // The three lines of the "Your body" card, then the week
            Feature("home_sleep", "Sleep", "Last night's sleep, bedtime and grade", "Home"),
            Feature("home_fast", "Fasting", "Time since your last meal and what your body is using", "Home"),
            Feature("home_walk", "Movement", "Steps toward your goal and activity burn", "Home"),
            Feature("home_week", "This week", "Day-by-day balance and your latest weigh-in", "Home"),

            Feature("in_sources", "Where your calories come from", "Carbs, protein, fat", "Intake"),
            Feature("in_limits", "Nutrients today", "Sugar, fat, fiber and more", "Intake"),
            Feature("in_timing", "When you ate", "Calories by time of day", "Intake"),
            Feature("in_top", "Biggest items", "Top foods today", "Intake"),
            Feature("in_water", "Hydration", "Water tracker", "Intake"),
            Feature("in_coach", "Coach note", "AI daily summary", "Intake"),

            Feature("burn_coach", "What to do today", "Exercise suggestions from your calorie balance", "Burned"),
            Feature("burn_split", "Where your burn comes from", "Resting and activity", "Burned"),
            Feature("burn_week", "Eaten vs burned", "Last 7 days", "Burned"),
            Feature("burn_steps", "Daily steps", "Last 7 days", "Burned"),
            Feature("burn_outlook", "Where your weight is heading", "30-day outlook", "Burned"),
            Feature("burn_workouts", "Workouts", "Sessions that day", "Burned"),

            Feature("diet", "Diet warnings", "Diets and allergies", "Smart"),
            Feature("assistant_bubble", "Floating assistant", "The ✦ bubble that answers questions about your data", "Smart")
        )
    }
}
