package com.example.nourio.plan

import com.example.nourio.plan.Guidelines.Direction
import com.example.nourio.plan.Guidelines.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidelinesTest {

    @Test
    fun referenceDayMatchesLabelValues() {
        val v = Guidelines.compute(2000.0, 0.0, Person())
        assertEquals("your goal", v.kcalSource)
        assertEquals(25.0, v.addedSugar, 0.0)   // 10% would be 50 g; the AHA cap of 25 g is lower
        assertEquals(65.0, v.fat, 0.0)          // 30% of 2,000 kcal
        assertEquals(22.0, v.satFat, 0.0)       // 10%
        assertEquals(28.0, v.fiber, 0.0)        // 14 g per 1,000 kcal
        assertEquals(2300.0, v.sodiumMg, 0.0)
        assertNull(v.proteinPerKg)
        assertEquals(75.0, v.protein, 0.0)      // 15% of calories without a weight
    }

    @Test
    fun addedSugarIsTenPercentWithinTheHeartAssociationCap() {
        assertEquals(36.0, Guidelines.compute(2500.0, 0.0, Person(sex = "Male")).addedSugar, 0.0)
        assertEquals(25.0, Guidelines.compute(2500.0, 0.0, Person(sex = "Female")).addedSugar, 0.0)
        assertEquals(30.0, Guidelines.compute(1200.0, 0.0, Person(sex = "Male")).addedSugar, 0.0)    // 10% of 1,200
        assertEquals(25.0, Guidelines.compute(1200.0, 0.0, Person(sex = "Female")).addedSugar, 0.0)
    }

    @Test
    fun proteinFollowsWeightAgeAndDirection() {
        val keep = Guidelines.compute(2200.0, 0.0, Person(70.0, 175.0, 30, direction = Direction.KEEP))
        assertEquals(55.0, keep.protein, 0.0)                 // 0.8 x 70 = 56
        val older = Guidelines.compute(2000.0, 0.0, Person(70.0, 175.0, 70, direction = Direction.KEEP))
        assertEquals(70.0, older.protein, 0.0)                // 1.0 x 70
        val losing = Guidelines.compute(1700.0, 0.0, Person(70.0, 175.0, 30, direction = Direction.LOSE))
        assertEquals(85.0, losing.protein, 0.0)               // 1.2 x 70 = 84
        val gaining = Guidelines.compute(2800.0, 0.0, Person(70.0, 175.0, 30, direction = Direction.GAIN))
        assertEquals(100.0, gaining.protein, 0.0)             // 1.4 x 70 = 98
    }

    @Test
    fun proteinUsesWeightAtBmi25WhenAbove() {
        // 120 kg at 170 cm: BMI 41.5. Weight at BMI 25 is 72.25 kg; 1.2 x 72.25 = 86.7
        val v = Guidelines.compute(2000.0, 0.0, Person(120.0, 170.0, 40, direction = Direction.LOSE))
        assertEquals(85.0, v.protein, 0.0)
    }

    @Test
    fun macrosAddUpToTheCalorieGoal() {
        for (kcal in listOf(1400.0, 1800.0, 2400.0, 3000.0)) {
            val v = Guidelines.compute(kcal, 0.0, Person(80.0, 180.0, 35, direction = Direction.LOSE))
            val total = v.protein * 4 + v.fat * 9 + v.carbs * 4
            assertTrue("kcal $kcal adds up to $total", kotlin.math.abs(total - kcal) <= kcal * 0.04)
        }
    }

    @Test
    fun caloriesFallBackToMaintenanceThenReference() {
        assertEquals("maintenance", Guidelines.compute(0.0, 2412.0, Person()).kcalSource)
        assertEquals(2410.0, Guidelines.compute(0.0, 2412.0, Person()).kcal, 0.0)
        assertEquals("reference", Guidelines.compute(0.0, 0.0, Person()).kcalSource)
    }

    @Test
    fun directionFromPlanOrGoal() {
        assertEquals(Direction.LOSE, Guidelines.direction(-500.0, 1800.0, 2300.0))
        assertEquals(Direction.GAIN, Guidelines.direction(300.0, 2600.0, 2300.0))
        assertEquals(Direction.LOSE, Guidelines.direction(null, 1900.0, 2300.0))
        assertEquals(Direction.KEEP, Guidelines.direction(null, 2250.0, 2300.0))
        assertEquals(Direction.KEEP, Guidelines.direction(null, 1800.0, 0.0))
    }
}
