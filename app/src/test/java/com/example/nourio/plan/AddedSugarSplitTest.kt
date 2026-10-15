package com.example.nourio.plan

import com.example.nourio.data.FoodItem
import com.example.nourio.data.totals
import com.example.nourio.nutrition.AddedSugar
import org.junit.Assert.assertEquals
import org.junit.Test

class AddedSugarSplitTest {
    private fun food(name: String, sugar100: Double, added100: Double? = null) =
        FoodItem(name, null, 100.0, 100.0, 0.0, 0.0, 0.0, 0.0, sugar100, 0.0, 0.0, addedSugar100 = added100)

    @Test
    fun namesAreSortedIntoAddedNaturalOrMixed() {
        assertEquals(0.0, AddedSugar.share("Banana"), 0.0)
        assertEquals(0.0, AddedSugar.share("Greek yogurt, plain"), 0.0)
        assertEquals(0.0, AddedSugar.share("Sweet potato, baked"), 0.0)
        assertEquals(0.0, AddedSugar.share("Unsweetened almond milk"), 0.0)
        assertEquals(1.0, AddedSugar.share("Orange juice"), 0.0)        // juice counts as free sugar
        assertEquals(1.0, AddedSugar.share("Chocolate chip cookies"), 0.0)
        assertEquals(1.0, AddedSugar.share("Cola"), 0.0)
        assertEquals(0.0, AddedSugar.share("Grilled chicken piece"), 0.0)  // "piece" isn't "pie"
        assertEquals(0.4, AddedSugar.share("Pad thai"), 0.0)
    }

    @Test
    fun aKnownValueWinsOverTheGuess() {
        assertEquals(4.0, food("Banana", 12.0, added100 = 4.0).addedSugar, 1e-9)
        assertEquals(12.0, food("Cola", 12.0, added100 = 30.0).addedSugar, 1e-9)    // never above total sugar
    }

    @Test
    fun totalsSplitIntoAddedAndNatural() {
        val t = listOf(food("Apple", 10.0), food("Cola", 11.0), food("Yogurt", 5.0, added100 = 2.0)).totals()
        assertEquals(26.0, t.sugar, 1e-9)
        assertEquals(13.0, t.addedSugar, 1e-9)
        assertEquals(13.0, t.naturalSugar, 1e-9)
    }
}
