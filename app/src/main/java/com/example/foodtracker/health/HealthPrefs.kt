package com.example.foodtracker.health

import android.content.Context

class HealthPrefs(context: Context) {
    private val p = context.getSharedPreferences("health", Context.MODE_PRIVATE)

    // The user's on/off choice inside this app. Defaults to on so existing connections keep working.
    var enabled: Boolean
        get() = p.getBoolean("enabled", true)
        set(v) { p.edit().putBoolean("enabled", v).apply() }
}
