package com.example.foodtracker.plan

import android.content.Context

class PlanStore(context: Context) {
    private val p = context.getSharedPreferences("plan", Context.MODE_PRIVATE)

    var targetKg: Double                      // 0 = no target
        get() = p.getFloat("target", 0f).toDouble()
        set(v) { p.edit().putFloat("target", v.toFloat()).apply() }

    var paceKgPerWeek: Double
        get() = p.getFloat("pace", 0.25f).toDouble()
        set(v) { p.edit().putFloat("pace", v.toFloat()).apply() }

    var useTargetGoal: Boolean
        get() = p.getBoolean("useTarget", true)
        set(v) { p.edit().putBoolean("useTarget", v).apply() }

    var carryOver: Boolean
        get() = p.getBoolean("carry", true)
        set(v) { p.edit().putBoolean("carry", v).apply() }
}
