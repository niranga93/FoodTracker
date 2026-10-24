package com.example.nourio.energy

import androidx.core.content.edit
import android.content.Context

class CalibrationStore(context: Context) {
    private val p = context.getSharedPreferences("calibration", Context.MODE_PRIVATE).also { prefs ->
        // 1.2.0 counts everyday movement and digestion properly; a factor learned against the old, lower burn
        // estimate would now over-correct, so it starts again from 1 and the weigh-ins relearn it
        if (prefs.getInt("model", 1) < MODEL_VERSION) prefs.edit { clear(); putInt("model", MODEL_VERSION) }
    }

    var factor: Double
        get() = p.getFloat("factor", 1f).toDouble()
        set(v) { p.edit { putFloat("factor", v.toFloat()) } }

    /**
     * Takes a new estimate from the weigh-ins. The first one is used as it is; after that the factor
     * moves 40% of the way toward each new estimate, at most once a day, so the calorie budget doesn't
     * jump about with day-to-day water weight.
     */
    fun update(estimate: Double, today: Long = System.currentTimeMillis() / 86_400_000L) {
        if (p.getLong("day", -1L) == today) return
        val next = if (!p.contains("day")) estimate else factor + 0.4 * (estimate - factor)
        p.edit { putFloat("factor", next.toFloat()).putLong("day", today) }
    }

    private companion object { const val MODEL_VERSION = 2 }
}
