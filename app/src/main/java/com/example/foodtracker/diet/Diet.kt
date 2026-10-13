package com.example.foodtracker.diet

import android.content.Context
import com.example.foodtracker.data.FoodItem

val ALLERGENS: List<Pair<String, String>> = listOf(
    "peanuts" to "Peanuts",
    "tree_nuts" to "Tree nuts",
    "milk" to "Milk",
    "eggs" to "Eggs",
    "fish" to "Fish",
    "shellfish" to "Shellfish",
    "soy" to "Soy",
    "gluten" to "Gluten / wheat",
    "sesame" to "Sesame"
)

val DIETS: List<String> =
    listOf("Vegetarian", "Vegan", "Pescatarian", "Halal", "Gluten-free", "Dairy-free")

data class DietSettings(
    val diets: Set<String> = emptySet(),
    val allergens: Set<String> = emptySet(),
    val other: List<String> = emptyList()
) {
    fun isEmpty() = diets.isEmpty() && allergens.isEmpty() && other.isEmpty()
}

class DietStore(context: Context) {
    private val p = context.getSharedPreferences("diet", Context.MODE_PRIVATE)

    fun load() = DietSettings(
        diets = p.getStringSet("diets", emptySet())?.toSet() ?: emptySet(),
        allergens = p.getStringSet("allergens", emptySet())?.toSet() ?: emptySet(),
        other = (p.getString("other", "") ?: "").split(',')
            .map { it.trim().lowercase() }.filter { it.isNotBlank() }
    )

    fun save(s: DietSettings) {
        p.edit()
            .putStringSet("diets", s.diets)
            .putStringSet("allergens", s.allergens)
            .putString("other", s.other.joinToString(","))
            .apply()
    }
}

/** Keyword-based detection from a food name. Imperfect by nature: hidden ingredients can't be seen. */
object AllergenDetector {
    val KNOWN = setOf(
        "peanuts", "tree_nuts", "milk", "eggs", "fish", "shellfish", "soy", "gluten", "sesame",
        "meat", "pork", "alcohol", "honey", "non-vegan", "non-vegetarian"
    )

    private val keywords: Map<String, List<String>> = mapOf(
        "peanuts" to listOf("peanut", "groundnut"),
        "tree_nuts" to listOf("almond", "cashew", "walnut", "pecan", "pistachio", "hazelnut",
            "macadamia", "brazil nut", "pine nut", "chestnut"),
        "milk" to listOf("milk", "cheese", "butter", "cream", "yogurt", "yoghurt", "whey", "casein",
            "ghee", "custard", "paneer", "curd", "lactose"),
        "eggs" to listOf("egg", "mayonnaise", "mayo", "meringue", "omelet", "omelette"),
        "fish" to listOf("fish", "salmon", "tuna", "cod", "sardine", "anchovy", "mackerel",
            "tilapia", "trout"),
        "shellfish" to listOf("shrimp", "prawn", "crab", "lobster", "mussel", "oyster", "clam",
            "squid", "scallop", "crayfish"),
        "soy" to listOf("soy", "tofu", "edamame", "miso", "tempeh"),
        "gluten" to listOf("wheat", "bread", "pasta", "noodle", "flour", "bun", "bagel", "cake",
            "cookie", "biscuit", "cracker", "pizza", "couscous", "barley", "rye", "seitan", "roti",
            "naan", "croissant", "pancake", "waffle"),
        "sesame" to listOf("sesame", "tahini"),
        "meat" to listOf("chicken", "beef", "pork", "bacon", "ham", "sausage", "lamb", "mutton",
            "turkey", "duck", "steak", "burger", "salami", "meatball"),
        "pork" to listOf("pork", "bacon", "ham", "lard", "prosciutto"),
        "alcohol" to listOf("wine", "beer", "vodka", "whiskey", "whisky", "rum", "liquor",
            "cocktail", "sake"),
        "honey" to listOf("honey")
    )

    private val regexes: Map<String, List<Regex>> = keywords.mapValues { (_, words) ->
        words.map { Regex("\\b" + Regex.escape(it) + "(e?s)?\\b") }
    }

    // Plant milks, nut butters and rice noodles shouldn't count as dairy or gluten
    private val milkExclusions = Regex(
        "(coconut|almond|soy|oat|rice|cashew|hazelnut) milk|peanut butter|almond butter|" +
            "nut butter|cocoa butter|shea butter|butternut"
    )
    private val glutenExclusions = Regex("rice noodle|rice flour|rice cake|rice pasta|gluten[- ]free")

    fun detect(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val lower = text.lowercase()
        val out = mutableSetOf<String>()
        for ((tag, rs) in regexes) {
            val t = when (tag) {
                "milk" -> milkExclusions.replace(lower, " ")
                "gluten" -> glutenExclusions.replace(lower, " ")
                else -> lower
            }
            if (rs.any { it.containsMatchIn(t) }) out.add(tag)
        }
        if ("pork" in out) out.add("meat")
        return out
    }
}

object DietRules {
    private val MEAT_LIKE = setOf("meat", "pork", "fish", "shellfish", "non-vegetarian")

    fun tagsOf(f: FoodItem): Set<String> =
        f.tags.toSet() + AllergenDetector.detect(f.name) + AllergenDetector.detect(f.matchedName ?: "")

    /** Human-readable warnings for one item. Empty if nothing conflicts. */
    fun check(s: DietSettings, f: FoodItem): List<String> {
        if (s.isEmpty()) return emptyList()
        val tags = tagsOf(f)
        val out = mutableListOf<String>()

        for ((key, label) in ALLERGENS) {
            if (key in s.allergens && key in tags) out += "May contain ${label.lowercase()} (your allergy)"
        }

        val animal = tags.any { it in MEAT_LIKE }
        if ("Vegan" in s.diets &&
            (animal || "milk" in tags || "eggs" in tags || "honey" in tags || "non-vegan" in tags)
        ) {
            out += "May not be vegan"
        } else if ("Vegetarian" in s.diets && animal) {
            out += "May not be vegetarian"
        }
        if ("Pescatarian" in s.diets && ("meat" in tags || "pork" in tags)) out += "Contains meat (pescatarian)"
        if ("Halal" in s.diets && ("pork" in tags || "alcohol" in tags)) out += "May not be halal (pork or alcohol)"
        if ("Gluten-free" in s.diets && "gluten" in tags && "gluten" !in s.allergens) out += "May contain gluten"
        if ("Dairy-free" in s.diets && "milk" in tags && "milk" !in s.allergens) out += "May contain dairy"

        val text = (f.name + " " + (f.matchedName ?: "")).lowercase()
        for (w in s.other) if (text.contains(w)) out += "Contains \"$w\" (on your avoid list)"

        return out.distinct()
    }
}
