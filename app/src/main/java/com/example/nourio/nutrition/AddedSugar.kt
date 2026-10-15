package com.example.nourio.nutrition

/**
 * Splits a food's sugar into added and natural when no source gave the added amount
 * (older meals, plain database matches, products without the value on their label).
 *
 * "Added" follows the WHO definition of free sugars: sugar added to food, plus the sugar in honey,
 * syrups and fruit juice. Sugar inside whole fruit, vegetables, plain milk and plain yogurt is natural.
 * The words are checked in order, so "orange juice" is added and "orange" is natural.
 */
object AddedSugar {
    private val ADDED = listOf(
        "juice", "smoothie", "soda", "cola", "soft drink", "lemonade", "energy drink", "iced tea", "milkshake",
        "frappe", "frappuccino", "latte", "mocha", "hot chocolate", "syrup", "honey", "jam", "jelly", "marmalade",
        "candy", "sweets", "chocolate", "caramel", "toffee", "fudge", "cake", "cookie", "biscuit", "brownie",
        "muffin", "donut", "doughnut", "pastry", "croissant", "pie", "tart", "pudding", "custard", "dessert",
        "ice cream", "gelato", "sorbet", "waffle", "pancake", "sweetened", "sweet", "sugar", "nutella", "spread",
        "cereal bar", "granola bar", "protein bar", "ketchup", "bbq sauce", "barbecue", "teriyaki", "sweet chili",
        "condensed milk", "flavored", "flavoured", "fruit yogurt", "yoghurt drink", "kombucha",
    )
    private val NATURAL = listOf(
        "apple", "banana", "orange", "mango", "grape", "berry", "berries", "pear", "peach", "plum", "cherry",
        "melon", "pineapple", "kiwi", "papaya", "guava", "fig", "date", "raisin", "apricot", "lychee",
        "pomegranate", "fruit", "vegetable", "salad", "carrot", "tomato", "onion", "pepper", "beet", "pumpkin",
        "squash", "sweet potato", "corn", "pea", "bean", "lentil", "chickpea", "milk", "plain yogurt",
        "greek yogurt", "yogurt", "yoghurt", "curd", "cheese", "egg", "rice", "potato", "oat", "nut", "seed",
        "chicken", "beef", "pork", "fish", "tofu", "water", "coffee", "tea",
    )

    /** Mixed dishes: sauces and dressings usually bring some added sugar. */
    private const val MIXED_SHARE = 0.4

    // Vegetables whose names contain a sweet word
    private val SWEET_VEG = Regex("""\bsweet ?(potato(es)?|corn|peppers?)\b""")
    private val UNSWEETENED = Regex("""\bunsweetened\b|\bno added sugars?\b|\bsugar[- ]free\b""")

    private fun matcher(words: List<String>) =
        Regex(words.joinToString("|", """\b(""", """)(s|es)?\b""") { Regex.escape(it) })

    private val addedRe = matcher(ADDED)
    private val naturalRe = matcher(NATURAL)

    /** Share of the sugar that is added, from 0 to 1, guessed from the food's name. */
    fun share(name: String): Double {
        val n = name.lowercase().replace(SWEET_VEG, "vegetable")
        if (UNSWEETENED.containsMatchIn(n)) return 0.0
        if (addedRe.containsMatchIn(n)) return 1.0
        if (naturalRe.containsMatchIn(n)) return 0.0
        return MIXED_SHARE
    }
}
