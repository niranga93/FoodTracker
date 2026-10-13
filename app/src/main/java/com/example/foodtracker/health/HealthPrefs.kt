package com.example.foodtracker.health

import android.content.Context

class HealthPrefs(context: Context) {
    private val p = context.getSharedPreferences("health", Context.MODE_PRIVATE)

    // The user's on/off choice inside this app. Defaults to on so existing connections keep working.
    var enabled: Boolean
        get() = p.getBoolean("enabled", true)
        set(v) { p.edit().putBoolean("enabled", v).apply() }

    // Opt-in extras
    var readExtras: Boolean
        get() = p.getBoolean("readExtras", false)
        set(v) { p.edit().putBoolean("readExtras", v).apply() }

    var writeSync: Boolean
        get() = p.getBoolean("writeSync", false)
        set(v) { p.edit().putBoolean("writeSync", v).apply() }

    // Bookkeeping for the two-way sync
    var pushedMealIds: Set<String>
        get() = p.getStringSet("pushedMealIds", emptySet())?.toSet() ?: emptySet()
        set(v) { p.edit().putStringSet("pushedMealIds", v).apply() }

    var pushedWeightTimes: Set<String>
        get() = p.getStringSet("pushedWeightTimes", emptySet())?.toSet() ?: emptySet()
        set(v) { p.edit().putStringSet("pushedWeightTimes", v).apply() }

    var importedWeightIds: Set<String>
        get() = p.getStringSet("importedWeightIds", emptySet())?.toSet() ?: emptySet()
        set(v) { p.edit().putStringSet("importedWeightIds", v).apply() }

    var importedWeightTimes: Set<String>
        get() = p.getStringSet("importedWeightTimes", emptySet())?.toSet() ?: emptySet()
        set(v) { p.edit().putStringSet("importedWeightTimes", v).apply() }
}
