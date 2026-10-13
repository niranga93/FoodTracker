package com.example.nourio.data

import androidx.core.content.edit
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class FavMeal(val id: String, val name: String, val items: List<FoodItem>, val sig: String)

class FavoriteMealStore(context: Context) {
    private val p = context.getSharedPreferences("favorite_meals", Context.MODE_PRIVATE)

    fun sigOf(items: List<FoodItem>): String =
        items.joinToString("|") { it.name.trim().lowercase() + ":" + it.grams.toInt() }

    fun all(): List<FavMeal> {
        val arr = runCatching { JSONArray(p.getString("list", "[]") ?: "[]") }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val itemsArr = o.getJSONArray("items")
                FavMeal(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    items = (0 until itemsArr.length()).map { FoodItem.fromJson(itemsArr.getJSONObject(it)) },
                    sig = o.getString("sig")
                )
            }.getOrNull()
        }
    }

    private fun write(list: List<FavMeal>) {
        val arr = JSONArray()
        list.forEach { f ->
            val items = JSONArray()
            f.items.forEach { items.put(it.toJson()) }
            arr.put(
                JSONObject().put("id", f.id).put("name", f.name).put("sig", f.sig).put("items", items)
            )
        }
        p.edit { putString("list", arr.toString()) }
    }

    fun isFavorite(items: List<FoodItem>): Boolean = all().any { it.sig == sigOf(items) }

    fun add(name: String, items: List<FoodItem>) {
        if (isFavorite(items)) return
        write(all() + FavMeal(UUID.randomUUID().toString(), name, items, sigOf(items)))
    }

    fun removeBySig(items: List<FoodItem>) {
        val sig = sigOf(items)
        write(all().filter { it.sig != sig })
    }

    fun remove(id: String) = write(all().filter { it.id != id })
}
