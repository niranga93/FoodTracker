package com.example.nourio.export

import com.example.nourio.data.Meal
import com.example.nourio.data.WeightEntry
import com.example.nourio.data.totals
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvExport {
    private const val BOM = "\uFEFF"   // helps Excel read UTF-8 correctly

    private fun esc(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"" + s.replace("\"", "\"\"") + "\""
        else s

    private fun n(d: Double, decimals: Int = 1): String =
        String.format(Locale.US, "%.${decimals}f", d)

    /** One row per food item. */
    fun meals(meals: List<Meal>): String {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
        val sb = StringBuilder(BOM)
        sb.append("date,time,meal,item,grams,kcal,sugar_g,added_sugar_g,fat_g,sat_fat_g,carbs_g,protein_g,fiber_g,sodium_mg,nutrition_source\n")
        for (m in meals.sortedBy { it.timestamp }) {
            val d = Date(m.timestamp)
            for (item in m.items) {
                sb.append(
                    listOf(
                        dateFmt.format(d), timeFmt.format(d), esc(m.description), esc(item.name),
                        n(item.grams), n(item.kcal, 0), n(item.sugar), n(item.addedSugar), n(item.fat), n(item.satFat),
                        n(item.carbs), n(item.protein), n(item.fiber), n(item.sodium, 0),
                        esc(item.matchedName ?: "No match")
                    ).joinToString(",")
                ).append('\n')
            }
        }
        return sb.toString()
    }

    fun weights(entries: List<WeightEntry>): String {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
        val sb = StringBuilder(BOM)
        sb.append("date,time,kg\n")
        for (e in entries.sortedBy { it.ts }) {
            val d = Date(e.ts)
            sb.append(dateFmt.format(d)).append(',').append(timeFmt.format(d)).append(',')
                .append(n(e.kg)).append('\n')
        }
        return sb.toString()
    }

    /** One row per day: meal totals plus water. */
    fun daily(meals: List<Meal>, water: Map<String, Int>): String {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val byDay = meals.groupBy { dateFmt.format(Date(it.timestamp)) }
        val days = (byDay.keys + water.keys).toSortedSet()
        val sb = StringBuilder(BOM)
        sb.append("date,meals,kcal,sugar_g,added_sugar_g,fat_g,sat_fat_g,carbs_g,protein_g,fiber_g,sodium_mg,water_ml\n")
        for (day in days) {
            val dayMeals = byDay[day].orEmpty()
            val t = dayMeals.flatMap { it.items }.totals()
            sb.append(
                listOf(
                    day, dayMeals.size.toString(), n(t.kcal, 0), n(t.sugar), n(t.addedSugar), n(t.fat), n(t.satFat),
                    n(t.carbs), n(t.protein), n(t.fiber), n(t.sodium, 0), (water[day] ?: 0).toString()
                ).joinToString(",")
            ).append('\n')
        }
        return sb.toString()
    }
}
