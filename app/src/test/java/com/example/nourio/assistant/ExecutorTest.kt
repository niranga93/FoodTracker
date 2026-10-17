package com.example.nourio.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

// Robolectric for Android's org.json, which the plan parser uses
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExecutorTest {
    private val today = LocalDate.of(2026, 10, 16)       // a Friday

    private fun table(
        days: Map<AMetric, Map<LocalDate, Double>> = emptyMap(),
        foods: List<FoodEntry> = emptyList(),
        goals: Map<String, Double> = mapOf("protein" to 100.0, "steps" to 8000.0),
    ) = Table(today, days, foods, goals, today.minusDays(90))

    private fun back(n: Long) = today.minusDays(n)

    @Test
    fun rangesResolve() {
        assertEquals(LocalDate.of(2026, 10, 12) to today, Executor.resolve(Range(RangeKind.THIS_WEEK), today, null))
        assertEquals(LocalDate.of(2026, 10, 5) to LocalDate.of(2026, 10, 11), Executor.resolve(Range(RangeKind.LAST_WEEK), today, null))
        assertEquals(LocalDate.of(2026, 9, 1) to LocalDate.of(2026, 9, 30), Executor.resolve(Range(RangeKind.LAST_MONTH), today, null))
        assertEquals(back(6) to today, Executor.resolve(Range(RangeKind.LAST_DAYS, 7), today, null))
    }

    @Test
    fun averageFillsTheTemplateWithGoal() {
        val plan = Plan.parse("""{"kind":"data","steps":[{"id":"s1","metric":"protein","agg":"average","range":{"kind":"last_days","days":7}}],
            "answer":"You averaged {s1} a day. Your goal is {goal:protein}.","method":"m","chart":"s1"}""")
        val t = table(mapOf(AMetric.PROTEIN to mapOf(back(0) to 80.0, back(1) to 100.0, back(2) to 120.0, back(20) to 999.0)))
        val a = Executor.answer(t, plan)
        assertEquals("You averaged 100 g a day. Your goal is 100 g.", a.text)
        assertFalse(a.missing)
        assertTrue(a.footnote!!.startsWith("Based on 3 days"))
        assertEquals(3, a.chart!!.series.size)
    }

    @Test
    fun weekendsAgainstWeekdaysWithDiff() {
        val kcal = (0L until 14).associate { back(it) to if (back(it).dayOfWeek.value >= 6) 2600.0 else 2000.0 }
        val plan = Plan.parse("""{"kind":"data","steps":[
            {"id":"s1","metric":"kcal","agg":"average","range":{"kind":"last_days","days":14},"weekdays":[6,7]},
            {"id":"s2","metric":"kcal","agg":"average","range":{"kind":"last_days","days":14},"weekdays":[1,2,3,4,5]}],
            "answer":"Weekends {s1}, weekdays {s2}, difference {diff:s1,s2}."}""")
        assertEquals("Weekends 2,600 kcal, weekdays 2,000 kcal, difference +600 kcal.", Executor.answer(table(mapOf(AMetric.KCAL to kcal)), plan).text)
    }

    @Test
    fun lastTimeAFoodWasEaten() {
        val foods = listOf(FoodEntry(back(30), "Pepperoni pizza", 200.0, 540.0), FoodEntry(back(3), "Pizzas, margherita", 150.0, 400.0),
            FoodEntry(back(1), "Salad", 100.0, 50.0))
        val plan = Plan.parse("""{"kind":"data","steps":[{"id":"s1","metric":"food_count","agg":"latest","range":{"kind":"all_time"},"food":"pizzas"}],"answer":"Last on {s1}."}""")
        assertEquals("Last on ${Executor.date(back(3))}.", Executor.answer(table(foods = foods), plan).text)
    }

    @Test
    fun countDaysAgainstAGoal() {
        val steps = mapOf(back(0) to 9000.0, back(1) to 7000.0, back(2) to 8000.0, back(3) to 12000.0)
        val plan = Plan.parse("""{"kind":"data","steps":[{"id":"s1","metric":"steps","agg":"count_days","range":{"kind":"last_days","days":7},
            "where":{"metric":"steps","op":"ge","goal":"steps"}}],"answer":"{s1} at {goal:steps}."}""")
        assertEquals("3 days at 8,000 steps.", Executor.answer(table(mapOf(AMetric.STEPS to steps)), plan).text)
    }

    @Test
    fun weightChangeIsSigned() {
        val plan = Plan.parse("""{"kind":"data","steps":[{"id":"s1","metric":"weight_kg","agg":"change","range":{"kind":"last_days","days":30}}],"answer":"{s1}"}""")
        val t = table(mapOf(AMetric.WEIGHT to mapOf(back(25) to 80.0, back(10) to 79.0, back(0) to 78.4)))
        assertTrue(Executor.answer(t, plan).text.startsWith("−1.6 kg"))
    }

    @Test
    fun missingDataGivesAnHonestReply() {
        val plan = Plan.parse("""{"kind":"data","steps":[{"id":"s1","metric":"sleep_h","agg":"average","range":{"kind":"this_week"}}],"answer":"You slept {s1}."}""")
        val a = Executor.answer(table(), plan)
        assertTrue(a.missing)
        assertTrue(a.text.contains("Health Connect"))
    }

    @Test
    fun unrelatedDataIsNotCalledALink() {
        val r = kotlin.random.Random(3)
        val sleep = (0L until 30).associate { back(it) to 5.0 + r.nextDouble() * 4 }
        val kcal = (0L until 30).associate { back(it) to 1500.0 + r.nextDouble() * 1000 }
        val plan = Plan.parse("""{"kind":"data","links":[{"id":"l1","x":"sleep_h","y":"kcal","next_day":false,"range":{"kind":"last_days","days":30}}],"answer":"There was {l1}."}""")
        val text = Executor.answer(table(mapOf(AMetric.SLEEP to sleep, AMetric.KCAL to kcal)), plan).text
        assertTrue(text, text.startsWith("There was no clear link (30 days)"))
    }

    @Test
    fun linkIsDescribedLocally() {
        val sleep = (0L until 20).associate { back(it) to 5.0 + (it % 5) }
        val kcal = sleep.mapValues { (_, h) -> 3000 - h * 150 }
        val plan = Plan.parse("""{"kind":"data","links":[{"id":"l1","x":"sleep_h","y":"kcal","next_day":false,"range":{"kind":"last_days","days":30}}],"answer":"There was {l1}."}""")
        val text = Executor.answer(table(mapOf(AMetric.SLEEP to sleep, AMetric.KCAL to kcal)), plan).text
        assertTrue(text, text.startsWith("There was a strong link (20 days)"))
    }

    @Test
    fun unknownMetricsAreDroppedNotFatal() {
        val plan = Plan.parse("""{"kind":"data","steps":[{"id":"s1","metric":"blood_glucose","agg":"average","range":{"kind":"today"}}],"answer":"x"}""")
        assertTrue(plan.steps.isEmpty())
    }

    @Test
    fun theSchemaCarriesNoUserValues() {
        // The request is built from the schema, the catalog and the question only
        val s = Plan.SCHEMA.toString()
        assertFalse(s.contains("100.0") || s.contains("8000"))
    }
}
