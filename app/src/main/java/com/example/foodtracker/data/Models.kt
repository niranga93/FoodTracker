package com.example.foodtracker.data

import org.json.JSONArray
import org.json.JSONObject

data class FoodItem(
    val name: String,
    val matchedName: String?,          // null = no USDA match
    val grams: Double,
    val kcal100: Double, val protein100: Double, val fat100: Double,
    val satFat100: Double, val carbs100: Double, val sugar100: Double,
    val fiber100: Double, val sodium100: Double,
    val tags: List<String> = emptyList()   // allergen / diet tags such as "milk", "gluten", "meat"
) {
    val kcal get() = kcal100 * grams / 100
    val protein get() = protein100 * grams / 100
    val fat get() = fat100 * grams / 100
    val satFat get() = satFat100 * grams / 100
    val carbs get() = carbs100 * grams / 100
    val sugar get() = sugar100 * grams / 100
    val fiber get() = fiber100 * grams / 100
    val sodium get() = sodium100 * grams / 100

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("matched", matchedName ?: JSONObject.NULL)
        .put("grams", grams)
        .put("kcal100", kcal100).put("protein100", protein100)
        .put("fat100", fat100).put("satFat100", satFat100)
        .put("carbs100", carbs100).put("sugar100", sugar100)
        .put("fiber100", fiber100).put("sodium100", sodium100)
        .put("tags", JSONArray(tags))

    companion object {
        fun fromJson(o: JSONObject) = FoodItem(
            name = o.optString("name"),
            matchedName = if (o.isNull("matched")) null else o.getString("matched"),
            grams = o.optDouble("grams", 0.0),
            kcal100 = o.optDouble("kcal100", 0.0),
            protein100 = o.optDouble("protein100", 0.0),
            fat100 = o.optDouble("fat100", 0.0),
            satFat100 = o.optDouble("satFat100", 0.0),
            carbs100 = o.optDouble("carbs100", 0.0),
            sugar100 = o.optDouble("sugar100", 0.0),
            fiber100 = o.optDouble("fiber100", 0.0),
            sodium100 = o.optDouble("sodium100", 0.0),
            tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                ?: emptyList()
        )
    }
}

data class Totals(
    val kcal: Double, val protein: Double, val fat: Double, val satFat: Double,
    val carbs: Double, val sugar: Double, val fiber: Double, val sodium: Double
)

fun List<FoodItem>.totals() = Totals(
    kcal = sumOf { it.kcal }, protein = sumOf { it.protein },
    fat = sumOf { it.fat }, satFat = sumOf { it.satFat },
    carbs = sumOf { it.carbs }, sugar = sumOf { it.sugar },
    fiber = sumOf { it.fiber }, sodium = sumOf { it.sodium }
)

data class Meal(
    val id: Long,
    val timestamp: Long,
    val description: String,
    val items: List<FoodItem>
) {
    val totals: Totals get() = items.totals()
}
