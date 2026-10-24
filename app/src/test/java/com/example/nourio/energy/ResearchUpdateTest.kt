package com.example.nourio.energy

import com.example.nourio.ForecastResult
import com.example.nourio.plan.BudgetLimit
import com.example.nourio.plan.BudgetModel
import com.example.nourio.reminders.BodyTips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 1.2.0 research updates to the burn estimate, the calorie budget and the body tips. */
class ResearchUpdateTest {
    private val man = Body(80.0, 178.0, 34, "Male")
    private val bmr = EnergyModel.bmr(man)

    @Test
    fun aSedentaryDayLandsInTheMeasuredRange() {
        // FAO/WHO/UNU: sedentary adults burn 1.4–1.7× BMR. No data at all should sit at the low end.
        val noData = EnergyModel.estimateBurn(man, null, null, null).burned / bmr
        assertTrue("no data: $noData", noData in 1.35..1.45)
        // 4,000 steps on a 2,000 kcal day: still sedentary, not the old ~1.2×
        val fewSteps = EnergyModel.estimateBurn(man, 4000, null, null, eaten = 2000.0).burned / bmr
        assertTrue("4,000 steps: $fewSteps", fewSteps in 1.35..1.6)
    }

    @Test
    fun digestionFollowsWhatWasEaten() {
        val light = EnergyModel.estimateBurn(man, 6000, null, null, eaten = 1500.0)
        val heavy = EnergyModel.estimateBurn(man, 6000, null, null, eaten = 2500.0)
        assertEquals(100.0, heavy.burned - light.burned, 1.0)    // 10% of the extra 1,000 kcal
    }

    @Test
    fun watchExerciseIsTrimmed() {
        val e = EnergyModel.estimateBurn(man, null, 500.0, 2400.0)
        assertEquals(2350.0, e.burned, 1.0)                      // 10% of 500 kcal taken off
        assertEquals(1900.0, e.resting, 1.0)                     // the device's own resting part is kept
    }

    @Test
    fun budgetUsesThisBodysEnergyAndAllowsForAdaptation() {
        val maintenance = 2500.0
        // 0.25 kg a week at this body's kcal per kg, widened by the 14% the body adapts
        val g = BudgetModel.baseGoal(man, maintenance, 72.0, 0.25)
        val perKg = (BudgetModel.kcalPerKg(man) + BudgetModel.kcalPerKg(man.copy(weightKg = 72.0))) / 2
        assertEquals(0.25 * perKg / 7 / 0.86, maintenance - g.kcal, 1.0)
        assertFalse(g.clamped)
        // 0.5 kg a week would need ~600 kcal a day: kept to a 500 kcal deficit, and the ETA says so
        val fast = BudgetModel.baseGoal(man, maintenance, 72.0, 0.5)
        assertEquals(500.0, maintenance - fast.kcal, 1.0)
        assertTrue(fast.clamped)
        assertTrue("eta ${fast.etaWeeks}", fast.etaWeeks!! in 14.0..22.0)
    }

    @Test
    fun aDaysDeficitIsLimitedByItsBurn() {
        // A quiet day for this man: 25% of 1,764 would allow 1,323, but his 1,500 minimum is higher
        val (low, why) = BudgetModel.lowestBudget(man, 1764.0)
        assertEquals(1500.0, low, 0.5)
        assertEquals(BudgetLimit.MINIMUM, why)
        // An active day: 25% of 2,400 is 600, so the 500 kcal cap applies
        assertEquals(1900.0 to BudgetLimit.DEFICIT_CAP, BudgetModel.lowestBudget(man, 2400.0))
        // A small woman: 25% of 1,800 = 450 -> 1,350, above her 1,200 minimum
        val woman = Body(55.0, 160.0, 30, "Female")
        assertEquals(1350.0 to BudgetLimit.DEFICIT_SHARE, BudgetModel.lowestBudget(woman, 1800.0))
        // Never asked to eat more than the burn itself
        assertEquals(1100.0, BudgetModel.lowestBudget(woman, 1100.0).first, 0.5)
    }

    @Test
    fun noActivityDataMaintenanceIsNoLongerTooLow() {
        val m = BudgetModel.maintenance(man, emptyList())
        assertTrue("maintenance ${m / bmr}× BMR", m / bmr > 1.35)
    }

    private fun signals(
        trend: Double = 80.0, water: Double? = 0.0, scale: Double? = null, gap: Double? = null, gapSd: Double? = null,
        weighIns: Int = 10, balance: Double = -400.0, pace: Double = -0.4, protein: Double? = 1.4, watch: Double? = null,
    ) = BodyTips.Signals(trend, water, scale, gap, gapSd, weighIns, balance, pace, protein, watch)

    private fun ids(s: BodyTips.Signals) = BodyTips.tips(s).map { it.id }.toSet()

    @Test
    fun aWaterJumpIsExplainedNotPunished() {
        val t = BodyTips.tips(signals(trend = 80.6, water = 0.6, scale = 81.2)).first { it.id == "body_water_jump" }
        assertTrue(t.urgent)
        assertFalse("no water: no tip", "body_water_jump" in ids(signals(scale = 81.2, water = 0.0)))
    }

    @Test
    fun losingTooFastWarns() {
        assertTrue("body_too_fast" in ids(signals(pace = -1.0)))          // over 1% of 80 kg a week
        assertTrue("body_too_fast" in ids(signals(balance = -1200.0)))
        assertFalse("body_too_fast" in ids(signals()))
    }

    @Test
    fun logGapNeedsEvidence() {
        assertTrue("body_log_gap" in ids(signals(gap = 300.0, gapSd = 80.0)))
        assertFalse("too uncertain", "body_log_gap" in ids(signals(gap = 300.0, gapSd = 200.0)))
        assertFalse("too few weigh-ins", "body_log_gap" in ids(signals(gap = 300.0, gapSd = 80.0, weighIns = 3)))
    }

    @Test
    fun proteinAndWatchTips() {
        assertTrue("body_protein" in ids(signals(protein = 0.8)))
        assertFalse("not in a deficit", "body_protein" in ids(signals(protein = 0.8, balance = 0.0)))
        assertTrue("body_watch" in ids(signals(watch = 450.0)))
    }

    @Test
    fun signalsComeFromTheForecast() {
        val p = Prediction(14, -400.0, -0.4, -1.7, -2.5, -0.9, 10, false, nowKg = 79.0, waterKg = 0.2, weighIns = 8)
        val s = BodyTips.signals(ForecastResult(p, null, 1.0, latestMorning = 0 to 79.5, avgProteinG = 95.0))!!
        assertEquals(79.5, s.latestMorningKg!!, 1e-9)
        assertEquals(95.0 / 79.0, s.proteinGPerKg!!, 1e-9)
    }

    @Test
    fun aDeviceDailyTotalIsNotCountedTwice() {
        // Samsung Health: a finished 8,000-step day sends ~2,000 kcal total and no active calories
        val day = EnergyModel.estimateBurn(man, 8000, null, 2000.0, eaten = 1800.0)
        assertEquals(2000.0, day.burned, 1.0)
        // Movement shown is the total above resting, not zero because digestion was counted in resting
        assertEquals(2000.0 - bmr, day.shownActivity!!, 1.0)
        // Before the device adds the walking, resting plus the steps is used
        val early = EnergyModel.estimateBurn(man, 3000, null, 1620.0)
        assertTrue(early.burned >= bmr)
    }

    @Test
    fun todaysBurnNeverDropsAsYouWalk() {
        // Samsung's total and the steps both rise through the day; the burn must rise with them, never jump down
        var last = 0.0
        val day = listOf(0L to 1600.0, 500L to 1610.0, 3000L to 1700.0, 3000L to 1780.0, 6000L to 1850.0, 9000L to 2050.0)
        for ((steps, total) in day) {
            val b = EnergyModel.estimateBurn(man, steps, null, total).burned
            assertTrue("$steps steps, total $total: $b after $last", b >= last - 0.5)
            last = b
        }
    }
}
