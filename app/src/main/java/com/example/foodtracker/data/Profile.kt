package com.example.foodtracker.data

import android.content.Context

data class Profile(
    val name: String = "",
    val birthYear: Int = 0,          // 0 = not set
    val sex: String = "",            // "", "Female", "Male", "Other"
    val heightCm: Double = 0.0,      // 0 = not set
    val weightKg: Double = 0.0
) {
    val bmi: Double?
        get() = if (heightCm > 0 && weightKg > 0) {
            val m = heightCm / 100.0
            weightKg / (m * m)
        } else null
}

class ProfileStore(context: Context) {
    private val p = context.getSharedPreferences("profile", Context.MODE_PRIVATE)

    fun load() = Profile(
        name = p.getString("name", "") ?: "",
        birthYear = p.getInt("birthYear", 0),
        sex = p.getString("sex", "") ?: "",
        heightCm = p.getFloat("heightCm", 0f).toDouble(),
        weightKg = p.getFloat("weightKg", 0f).toDouble()
    )

    fun save(x: Profile) {
        p.edit()
            .putString("name", x.name)
            .putInt("birthYear", x.birthYear)
            .putString("sex", x.sex)
            .putFloat("heightCm", x.heightCm.toFloat())
            .putFloat("weightKg", x.weightKg.toFloat())
            .apply()
    }
}