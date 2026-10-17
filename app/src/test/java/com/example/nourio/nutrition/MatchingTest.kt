package com.example.nourio.nutrition

import com.example.nourio.data.PortionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchingTest {
    private fun per100(desc: String, kcal: Double) = NutritionLookup.Per100g(desc, kcal, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    @Test
    fun weakMatchesAreRejected() {
        assertTrue(NutritionLookup.relevant("Rice, white, cooked", "Rice, white, long-grain, regular, enriched, cooked"))
        assertFalse(NutritionLookup.relevant("Chicken tikka masala", "Spices, curry powder"))
        assertFalse(NutritionLookup.relevant("Quinoa, cooked", "Bread, multigrain"))
    }

    @Test
    fun implausibleCaloriesMeanTheWrongFood() {
        val ai = per100("AI estimate", 884.0)                                    // olive oil
        assertFalse(MealPipeline.plausible(per100("Olives, ripe, canned", 115.0), ai))
        assertTrue(MealPipeline.plausible(per100("Oil, olive, salad or cooking", 884.0), ai))
        // Low-calorie foods: a small absolute gap is fine even when the ratio is large
        assertTrue(MealPipeline.plausible(per100("Cucumber, raw", 15.0), per100("AI estimate", 40.0)))
        // No AI estimate: nothing to check against
        assertTrue(MealPipeline.plausible(per100("Anything", 500.0), null))
    }

    @Test
    fun learnedPortionsNeedEvidence() {
        assertEquals(1.25, PortionStore.shrink(1.5, 2), 1e-9)                  // half the change after 2
        assertEquals(1.375, PortionStore.shrink(1.5, 6), 1e-9)                 // three quarters after 6
        assertEquals(0.8, PortionStore.shrink(0.6, 2), 1e-9)
    }
}
