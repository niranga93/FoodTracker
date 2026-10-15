package com.example.nourio.insights

import android.content.Context
import androidx.core.content.edit
import com.example.nourio.data.GoalStore
import com.example.nourio.data.ProfileStore
import com.example.nourio.data.WaterStore
import org.json.JSONArray
import org.json.JSONObject

/** Which tips were accepted or dismissed, the sleep goal, and the daily-tip switch. */
class InsightPrefs(context: Context) {
    private val p = context.getSharedPreferences("insights", Context.MODE_PRIVATE)

    var notificationsOn: Boolean
        get() = p.getBoolean("notify", true)
        set(v) = p.edit { putBoolean("notify", v) }

    // Hours. Only used by tips and the Insights page.
    var sleepGoalH: Double
        get() = p.getFloat("sleepGoal", 7f).toDouble()
        set(v) = p.edit { putFloat("sleepGoal", v.toFloat()) }

    /** "id|epoch ms|text" for accepted tips, newest last. */
    var accepted: List<String>
        get() = p.getString("accepted", "")!!.split('\n').filter { it.isNotBlank() }
        set(v) = p.edit { putString("accepted", v.takeLast(30).joinToString("\n")) }

    fun isAccepted(id: String) = accepted.any { it.substringBefore('|') == id }

    fun dismissedUntil(id: String): Long = p.getLong("dismiss_$id", 0L)
    fun dismiss(id: String, days: Int = 14) =
        p.edit { putLong("dismiss_$id", System.currentTimeMillis() + days * 86_400_000L) }

    fun lastNotified(id: String): Long = p.getLong("notified_$id", 0L)
    fun markNotified(id: String) = p.edit { putLong("notified_$id", System.currentTimeMillis()) }

    /**
     * Tips from the last foreground analysis. Health Connect can't be read in the background without an
     * extra permission, so the daily worker sends from this list, worked out while the app was open.
     */
    fun saveTips(tips: List<Tip>) {
        val arr = JSONArray()
        tips.forEach { t ->
            arr.put(JSONObject().put("id", t.id).put("title", t.title).put("body", t.body).apply {
                t.change?.let { put("field", it.field.name).put("from", it.from).put("to", it.to) }
            })
        }
        p.edit { putString("tips", arr.toString()).putLong("tipsAt", System.currentTimeMillis()) }
    }

    /** The saved tips, or null when there are none newer than [maxAgeMs]. */
    fun savedTips(maxAgeMs: Long): List<Tip>? {
        if (System.currentTimeMillis() - p.getLong("tipsAt", 0L) > maxAgeMs) return null
        val arr = runCatching { JSONArray(p.getString("tips", "[]")) }.getOrNull() ?: return null
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val change = o.optString("field").takeIf { it.isNotBlank() }?.let { f ->
                runCatching { GoalChange(GoalField.valueOf(f), o.getDouble("from"), o.getDouble("to")) }.getOrNull()
            }
            Tip(o.optString("id"), o.optString("title"), o.optString("body"), change).takeIf { it.id.isNotBlank() }
        }
    }

    val tipsAge: Long get() = System.currentTimeMillis() - p.getLong("tipsAt", 0L)

    /** Tips that can be offered now. */
    fun open(tips: List<Tip>): List<Tip> {
        val now = System.currentTimeMillis()
        return tips.filter { !isAccepted(it.id) && dismissedUntil(it.id) < now }
    }
}

object InsightGoals {
    /** The goals as the engine sees them, with sensible defaults where none are set. */
    fun current(context: Context): CurrentGoals {
        val g = GoalStore(context).load()
        val weight = ProfileStore(context).load().weightKg
        return CurrentGoals(
            sodiumMg = g.sodiumMg,
            sugar = g.addedSugar,
            fiber = g.fiber.takeIf { it > 0 } ?: 25.0,
            protein = g.protein.takeIf { it > 0 } ?: (weight * 0.8),
            waterMl = WaterStore(context).goalMl.toDouble(),
            steps = g.stepGoal.toDouble(),
            sleepH = InsightPrefs(context).sleepGoalH,
            weightKg = weight
        )
    }

    /** Applies a tip's goal change and records it as accepted. */
    fun accept(context: Context, tip: Tip) {
        tip.change?.let { c ->
            val store = GoalStore(context)
            val g = store.load()
            when (c.field) {
                GoalField.SODIUM -> store.save(g.copy(sodiumMg = c.to))
                GoalField.SUGAR -> store.save(g.copy(addedSugar = c.to))
                GoalField.FIBER -> store.save(g.copy(fiber = c.to))
                GoalField.PROTEIN -> store.save(g.copy(protein = c.to))
                GoalField.STEPS -> store.save(g.copy(steps = c.to))
                GoalField.WATER -> WaterStore(context).goalMl = c.to.toInt()
                GoalField.SLEEP -> InsightPrefs(context).sleepGoalH = c.to
            }
        }
        val prefs = InsightPrefs(context)
        prefs.accepted = prefs.accepted + "${tip.id}|${System.currentTimeMillis()}|${tip.change?.text ?: tip.title}"
    }
}
