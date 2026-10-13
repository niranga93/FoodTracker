package com.example.foodtracker.energy

import android.content.Context

class CalibrationStore(context: Context) {
    private val p = context.getSharedPreferences("calibration", Context.MODE_PRIVATE)

    var factor: Double
        get() = p.getFloat("factor", 1f).toDouble()
        set(v) { p.edit().putFloat("factor", v.toFloat()).apply() }
}
