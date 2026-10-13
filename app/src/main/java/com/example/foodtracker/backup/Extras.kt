package com.example.foodtracker.backup

import android.content.Context
import com.example.foodtracker.data.WeightDb
import com.example.foodtracker.data.WeightEntry
import com.example.foodtracker.nutrition.NutritionLookup
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything beyond meals: settings files, weigh-ins and match corrections.
 * Device-specific files are left out on purpose: backup state, the Health Connect switch,
 * and the AI coach's consent and saved notes.
 */
object BackupExtras {
    private val PREF_FILES = listOf(
        "goals", "profile", "plan", "diet", "water",
        "favorites", "favorite_meals", "features", "calibration"
    )

    private fun prefsToJson(context: Context, name: String): JSONObject {
        val out = JSONObject()
        val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
        for (k in all.keys.sorted()) {            // sorted, so the content fingerprint is stable
            val v = all[k]
            val e = JSONObject()
            when (v) {
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is String -> e.put("t", "s").put("v", v)
                is Set<*> -> e.put("t", "ss").put("v", JSONArray(v.map { it.toString() }.sorted()))
                else -> continue
            }
            out.put(k, e)
        }
        return out
    }

    private fun jsonToPrefs(context: Context, name: String, json: JSONObject) {
        val ed = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        for (k in json.keys()) {
            val e = json.optJSONObject(k) ?: continue
            when (e.optString("t")) {
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "s" -> ed.putString(k, e.getString("v"))
                "ss" -> {
                    val a = e.getJSONArray("v")
                    ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                }
            }
        }
        ed.apply()
    }

    fun collect(context: Context): JSONObject {
        val prefs = JSONObject()
        PREF_FILES.forEach { prefs.put(it, prefsToJson(context, it)) }

        val weights = JSONArray()
        WeightDb(context).all().forEach { weights.put(JSONObject().put("ts", it.ts).put("kg", it.kg)) }

        val fixes = JSONObject()
        NutritionLookup.exportCorrections().toSortedMap().forEach { (k, v) -> fixes.put(k, v) }

        return JSONObject().put("prefs", prefs).put("weights", weights).put("corrections", fixes)
    }

    /** Replaces local data with the backup. Sections missing from an older backup are left alone. */
    fun apply(context: Context, extras: JSONObject) {
        extras.optJSONObject("prefs")?.let { p ->
            for (name in PREF_FILES) p.optJSONObject(name)?.let { jsonToPrefs(context, name, it) }
        }
        extras.optJSONArray("weights")?.let { a ->
            val list = (0 until a.length()).map {
                val o = a.getJSONObject(it)
                WeightEntry(0, o.getLong("ts"), o.getDouble("kg"))
            }
            WeightDb(context).replaceAll(list)
        }
        extras.optJSONObject("corrections")?.let { c ->
            val map = HashMap<String, String>()
            for (k in c.keys()) map[k] = c.getString(k)
            NutritionLookup.importCorrections(map)
        }
    }
}
