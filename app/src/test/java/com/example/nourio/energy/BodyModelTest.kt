package com.example.nourio.energy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Checks the body model against what the research says, and the weigh-in filter against a simulated
 * person whose real intake is higher than the log, as is typical.
 */
class BodyModelTest {
    // A 34-year-old man, 178 cm, 80 kg
    private val start = BodyModel.start(80.0, 178.0, 34, "Male")
    private val resting = BodyModel.Resting(start, EnergyModel.formulaBmr(Body(80.0, 178.0, 34, "Male")))
    private val activity = 350.0
    private val base = BodyData.baseline(start, resting, listOf(BodyModel.DayInput(null, activityKcal = activity)))
        .copy(carbKcal = 1100.0, sodiumMg = 3400.0)

    /** A logged day of [kcal] with the usual macro split: 20% protein, 45% carbohydrate, 35% fat. */
    private fun day(kcal: Double, carbShare: Double = 0.45, sodium: Double = 3400.0) = BodyModel.DayInput(
        eatenKcal = kcal,
        proteinG = kcal * 0.20 / 4,
        carbsG = kcal * carbShare / 4,
        fatG = kcal * (0.80 - carbShare) / 9,
        sodiumMg = sodium,
        activityKcal = activity,
    )

    private fun run(days: Int, s0: BodyModel.State = start, input: (Int) -> BodyModel.DayInput): BodyModel.State {
        var s = s0
        repeat(days) { s = BodyModel.step(s, input(it), base, resting).next }
        return s
    }

    @Test
    fun eatingMaintenanceKeepsWeightSteady() {
        val end = run(90) { day(base.intakeKcal) }
        assertEquals(80.0, end.weightKg, 0.4)
    }

    @Test
    fun smallDeficitLosesLessThanTheOldRule() {
        // 100 kcal/day under maintenance for a year. "7,700 kcal = 1 kg" says 4.7 kg; the dynamic model
        // (Hall 2011) says roughly half the eventual ~4.5 kg is reached in the first year.
        val end = run(365) { day(base.intakeKcal - 100) }
        val lost = 80.0 - end.weightKg
        assertTrue("lost $lost", lost in 1.8..3.8)
        assertTrue(lost < 365 * 100 / 7700.0)
    }

    @Test
    fun cuttingCarbsDropsWaterNotFat() {
        val kcal = base.intakeKcal - 300
        val normal = run(7) { day(kcal) }
        val lowCarb = run(7) { day(kcal, carbShare = 0.10) }
        val diff = normal.weightKg - lowCarb.weightKg
        assertTrue("low carb was $diff kg lighter", diff in 0.8..3.0)
        // Almost all of it is glycogen water and fluid, not tissue
        assertTrue(abs(normal.tissueKg - lowCarb.tissueKg) < 0.25)
    }

    @Test
    fun salt() {
        // 2,000 mg more sodium a day holds roughly 0.5–0.8 kg of water within a few days
        val normal = run(5) { day(base.intakeKcal) }
        val salty = run(5) { day(base.intakeKcal, sodium = 5400.0) }
        val diff = salty.weightKg - normal.weightKg
        assertTrue("salt added $diff kg", diff in 0.4..0.9)
    }

    @Test
    fun eatingLessSlowsBurnBy14Percent() {
        val end = run(60) { day(base.intakeKcal - 1000) }
        assertEquals(-140.0, end.adaptKcal, 15.0)
    }

    @Test
    fun aLeanerBodyStoresLessEnergyPerKg() {
        val lean = BodyModel.start(65.0, 180.0, 30, "Male")
        val heavy = BodyModel.start(120.0, 170.0, 50, "Female")
        val e1 = BodyModel.energyPerKg(lean)
        val e2 = BodyModel.energyPerKg(heavy)
        assertTrue("lean $e1, heavy $e2", e1 < e2)
        assertTrue(e1 in 4800.0..7600.0)   // ~8 kg fat: about a fifth of a change is lean tissue
        assertTrue(e2 in 7800.0..9200.0)
    }

    @Test
    fun unloggedDaysChangeNothing() {
        val end = run(30) { BodyModel.DayInput(null, activityKcal = activity) }
        assertEquals(80.0, end.weightKg, 0.1)
    }

    /**
     * 60 simulated people, each eating 0–30% more than they log (the range seen against doubly labelled
     * water), with their own deficit, logging 70–95% of days and weighing in on 40–90% of mornings with
     * 0.6 kg of scale and water noise. After 45 days each one gets a 30-day forecast. The new forecast must
     * beat the old "last weigh-in + logged balance ÷ 7,700" on average, learn the gap, and its 95% range
     * must hold the real weight for most people.
     */
    @Test
    fun forecastBeatsTheOldRuleAcrossSimulatedPeople() {
        val history = 45
        val horizon = 30
        var newErr = 0.0; var oldErr = 0.0; var gapErr = 0.0; var inside = 0
        val people = 60
        for (seed in 1..people) {
            val rnd = Random(seed)
            val under = rnd.nextDouble(0.0, 0.30)
            val deficit = rnd.nextDouble(-200.0, 800.0)
            val logRate = rnd.nextDouble(0.70, 0.95)
            val weighRate = rnd.nextDouble(0.40, 0.90)
            val logged = (0 until history + horizon).map { base.intakeKcal - deficit + rnd.nextDouble(-350.0, 350.0) }
            val isLogged = (0 until history + horizon).map { rnd.nextDouble() < logRate }

            var truth = start
            val truthW = ArrayList<Double>()
            for (i in 0 until history + horizon) {
                truthW += truth.weightKg
                truth = BodyModel.step(truth, day(logged[i] * (1 + under)), base, resting).next
            }
            truthW += truth.weightKg
            fun noise() = (rnd.nextDouble() + rnd.nextDouble() + rnd.nextDouble() - 1.5) * 1.2   // sd 0.6

            val days = (0 until history).map { i ->
                val input = if (isLogged[i]) day(logged[i]) else BodyModel.DayInput(null, activityKcal = activity)
                WeightFilter.Day.mornings(input, if (i > 0 && rnd.nextDouble() < weighRate) listOf(truthW[i] + noise()) else emptyList())
            } + WeightFilter.Day.mornings(null, listOf(truthW[history] + noise()))
            val r = WeightFilter.run(start, days, base, resting)

            // The app forecasts from the last two weeks of logged days
            val recent = (history - 14 until history).filter { isLogged[it] }.map { logged[it] }
            val typical = day(recent.average())
            val sd = kotlin.math.sqrt(recent.sumOf { (it - recent.average()).let { d -> d * d } } / (recent.size - 1))
            val out = WeightFilter.outlook(r, typical, sd, recent.size, base, resting, horizon)
            val actual = truthW[history + horizon]

            val lastScale = days.last().readings.first().kg
            val burned = BodyModel.step(r.model, typical, base, resting).burnedKcal
            val old = lastScale + horizon * (recent.average() - burned) / 7700.0

            newErr += abs(out.in30Kg - actual)
            oldErr += abs(old - actual)
            gapErr += abs(r.biasKcal - under * logged.take(history).average())
            if (actual in out.low30Kg..out.high30Kg) inside++
        }
        val newMae = newErr / people; val oldMae = oldErr / people; val gapMae = gapErr / people
        println("FORECAST 30-day error: new ${"%.2f".format(newMae)} kg, old ${"%.2f".format(oldMae)} kg; gap error ${"%.0f".format(gapMae)} kcal/day; inside 95% range $inside/$people")
        assertTrue("new $newMae vs old $oldMae", newMae < 0.75 * oldMae)
        assertTrue("new error $newMae kg", newMae < 0.8)
        assertTrue("gap error $gapMae kcal/day", gapMae < 150)
        assertTrue("inside $inside of $people", inside >= people * 0.85)
    }

    /**
     * Someone who weighs in at any time of day: in the evening they're heavier by what they've eaten and drunk
     * (1.1 kg by midnight here, unknown to the filter, which starts from 0.9). They log 25% less than they eat.
     * The filter should learn both, and its trend should match their real morning weight.
     */
    @Test
    fun learnsTheDaytimeRiseFromMixedWeighInTimes() {
        val rnd = Random(3)
        val rise = 1.1
        var truth = start
        val days = ArrayList<WeightFilter.Day>()
        val logged = ArrayList<Double>()
        repeat(60) { i ->
            val kcal = base.intakeKcal - 300 + rnd.nextDouble(-300.0, 300.0)
            logged += kcal
            val late = rnd.nextDouble()
            val reading = if (i > 0 && rnd.nextDouble() < 0.8)
                listOf(WeightFilter.Reading(truth.weightKg + rise * late + (rnd.nextDouble() - 0.5) * 1.0, late)) else emptyList()
            days += WeightFilter.Day(day(kcal), reading)
            truth = BodyModel.step(truth, day(kcal * 1.25), base, resting).next
        }
        days += WeightFilter.Day(null, emptyList())
        val r = WeightFilter.run(start, days, base, resting)
        assertEquals(rise, r.riseKg, 0.3)
        assertEquals(0.25 * logged.average(), r.biasKcal, 150.0)
        assertEquals(truth.weightKg, r.weightKg, 0.5)
    }

    @Test
    fun partLoggedDaysAreLeftOut() {
        // Usual day ~1,800 kcal: a 1,000 kcal day is probably just lunch and dinner
        val kcal = mapOf(-6 to 1800.0, -5 to 1750.0, -4 to 1900.0, -3 to 1000.0, -2 to 1850.0, -1 to 1700.0, -7 to 500.0)
        val complete = BodyData.completeDays(kcal)
        assertTrue(-3 !in complete)
        assertTrue(-7 !in complete)
        assertTrue(-1 in complete)
    }
}
