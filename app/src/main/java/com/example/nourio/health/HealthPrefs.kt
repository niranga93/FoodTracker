package com.example.nourio.health

import android.content.Context
import androidx.core.content.edit

class HealthPrefs(context: Context) {
    private val p = context.getSharedPreferences("health", Context.MODE_PRIVATE)

    private fun set(key: String) = p.getStringSet(key, emptySet())?.toSet() ?: emptySet()

    // The user's on/off choice inside this app. Defaults to on so existing connections keep working.
    var enabled: Boolean
        get() = p.getBoolean("enabled", true)
        set(v) = p.edit { putBoolean("enabled", v) }

    // Opt-in extras
    var readExtras: Boolean
        get() = p.getBoolean("readExtras", false)
        set(v) = p.edit { putBoolean("readExtras", v) }

    var writeSync: Boolean
        get() = p.getBoolean("writeSync", false)
        set(v) = p.edit { putBoolean("writeSync", v) }

    // Bookkeeping for the two-way sync. The pushed sets hold "id:fingerprint", so only changes are sent.
    var pushedMealIds: Set<String>
        get() = set("pushedMealIds")
        set(v) = p.edit { putStringSet("pushedMealIds", v) }

    var pushedWeightTimes: Set<String>
        get() = set("pushedWeightTimes")
        set(v) = p.edit { putStringSet("pushedWeightTimes", v) }

    var pushedWaterDays: Set<String>
        get() = set("pushedWaterDays")
        set(v) = p.edit { putStringSet("pushedWaterDays", v) }

    var importedWeightIds: Set<String>
        get() = set("importedWeightIds")
        set(v) = p.edit { putStringSet("importedWeightIds", v) }

    var importedWeightTimes: Set<String>
        get() = set("importedWeightTimes")
        set(v) = p.edit { putStringSet("importedWeightTimes", v) }
}
