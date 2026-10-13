package com.example.foodtracker.data

import android.content.Context

class FavoritesStore(context: Context) {
    private val p = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun all(): Set<String> = p.getStringSet("keys", emptySet())?.toSet() ?: emptySet()

    /** Adds or removes the item and returns the new set of favorite keys. */
    fun toggle(f: FoodItem): Set<String> {
        val k = key(f)
        val cur = all().toMutableSet()
        if (!cur.add(k)) cur.remove(k)
        p.edit().putStringSet("keys", cur).apply()
        return cur
    }

    companion object {
        fun key(f: FoodItem): String = f.name.trim().lowercase() + "|" + (f.matchedName ?: "")
    }
}
