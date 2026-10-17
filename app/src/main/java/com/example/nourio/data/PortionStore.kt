package com.example.nourio.data

import androidx.core.content.edit
import android.content.Context
import android.content.SharedPreferences

/**
 * Learns how your portions compare with the AI's photo estimates, per food.
 * Each time a photo meal is saved, the ratio (grams you kept / grams the AI guessed) is blended
 * into a running factor for that food, which then scales future estimates.
 */
object PortionStore {
    private const val MIN_SAMPLES = 2      // corrections needed before a factor is applied
    private const val BLEND = 0.4          // weight of the newest ratio
    private const val MIN_FACTOR = 0.5
    private const val MAX_FACTOR = 2.5

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences("portions", Context.MODE_PRIVATE)
        }
    }

    /** "Steamed  Rice!" -> "steamed rice" */
    private fun key(name: String): String =
        name.lowercase().replace(Regex("[^a-z ]"), " ").trim().replace(Regex(" +"), " ")

    /**
     * The learned factor for this food, or null if there isn't enough history yet. It's pulled toward 1
     * in proportion to how little evidence there is (n / (n + 2)): two corrections apply half the
     * learned change, six apply three quarters, so one odd plate can't skew every later portion.
     */
    fun factorFor(name: String): Double? {
        if (!::prefs.isInitialized) return null
        val k = key(name)
        val n = prefs.getInt("n_$k", 0)
        if (n < MIN_SAMPLES) return null
        return shrink(prefs.getFloat("f_$k", 1f).toDouble(), n).coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    internal fun shrink(factor: Double, n: Int): Double = 1.0 + (factor - 1.0) * n / (n + 2.0)

    fun record(name: String, aiGrams: Double, keptGrams: Double) {
        if (!::prefs.isInitialized || aiGrams <= 0 || keptGrams <= 0) return
        val k = key(name)
        if (k.isEmpty()) return
        val ratio = (keptGrams / aiGrams).coerceIn(0.25, 4.0)
        val n = prefs.getInt("n_$k", 0)
        val old = prefs.getFloat("f_$k", 1f).toDouble()
        val next = if (n == 0) ratio else old + BLEND * (ratio - old)
        prefs.edit { putFloat("f_$k", next.toFloat()).putInt("n_$k", n + 1) }
    }
}
