package com.example.nourio.data

import android.content.Context
import com.example.nourio.data.room.MealRow
import com.example.nourio.data.room.MealsDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray

/**
 * The meal log. Backed by Room ([MealsDatabase], the same meals.db file); this class keeps the methods
 * the rest of the app has always used, and adds Flows that update whenever a meal changes.
 */
class MealDb(context: Context) {
    private val appContext = context.applicationContext
    private val dao = MealsDatabase.get(appContext).meals()

    private fun json(items: List<FoodItem>): String = JSONArray().also { a -> items.forEach { a.put(it.toJson()) } }.toString()

    private fun MealRow.toMeal(): Meal {
        val arr = JSONArray(itemsJson)
        return Meal(id, ts, description, (0 until arr.length()).map { FoodItem.fromJson(arr.getJSONObject(it)) })
    }

    fun insert(description: String, items: List<FoodItem>, timestamp: Long = System.currentTimeMillis()): Long =
        dao.insert(MealRow(ts = timestamp, description = description, itemsJson = json(items)))

    fun getById(id: Long): Meal? = dao.byId(id)?.toMeal()

    fun update(id: Long, description: String, items: List<FoodItem>, timestamp: Long) =
        dao.update(id, timestamp, description, json(items))

    fun updateTimestamp(id: Long, timestamp: Long) = dao.updateTimestamp(id, timestamp)

    /** Distinct foods from your history, newest first. */
    fun recentItems(limit: Int = 200): List<FoodItem> =
        dao.latest(500).map { it.toMeal() }
            .flatMap { it.items }
            .distinctBy { it.name.trim().lowercase() + "|" + (it.matchedName ?: "") }
            .take(limit)

    fun delete(id: Long) {
        dao.delete(id)
        MealPhotos.delete(appContext, id)
    }

    fun all(): List<Meal> = between(0L, Long.MAX_VALUE)

    /** Every meal time, oldest first, without loading the items (for streaks and badges). */
    fun timestamps(): List<Long> = dao.timestamps()

    fun count(): Int = dao.count()

    fun replaceAll(meals: List<Meal>) =
        dao.replaceAll(meals.map { MealRow(ts = it.timestamp, description = it.description, itemsJson = json(it.items)) })

    fun between(startMs: Long, endMs: Long): List<Meal> = dao.between(startMs, endMs).map { it.toMeal() }

    /** Meals in a time range, newest first, re-emitted whenever any meal is added, edited or deleted. */
    fun observeBetween(startMs: Long, endMs: Long): Flow<List<Meal>> =
        dao.observeBetween(startMs, endMs).map { rows -> rows.map { it.toMeal() } }

    /** Emits on every change to the meal log, for screens that compute their own figures from meals. */
    fun observeChanges(): Flow<Int> = dao.observeChanges()

    /** The database is shared and stays open for the app's lifetime; kept so existing callers compile. */
    fun close() {}
}
