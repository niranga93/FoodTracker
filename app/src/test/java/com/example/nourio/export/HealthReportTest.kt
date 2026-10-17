package com.example.nourio.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.example.nourio.data.Goals
import com.example.nourio.data.Totals
import com.example.nourio.data.WeightEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HealthReportTest {
    private val today = LocalDate.of(2026, 10, 17)
    private val zone = ZoneOffset.UTC
    private val goals = Goals(kcal = 2000.0, addedSugar = 30.0, fat = 70.0, satFat = 20.0, sodiumMg = 2300.0,
        carbs = 250.0, protein = 120.0, fiber = 30.0, steps = 8000.0)

    private fun ms(d: LocalDate, hour: Int = 7) = d.atTime(hour, 0).toInstant(zone).toEpochMilli()

    /** 30 days: every 6th day barely logged, steps and sleep most days, a weigh-in every other day sliding down. */
    private fun sample(): HealthReport {
        val days = (1..30).map { i ->
            val d = today.minusDays((31 - i).toLong())
            val kcal = if (i % 6 == 0) 300.0 else 1800.0 + (i % 5) * 120
            ReportDay(
                d, Totals(kcal = kcal, protein = kcal * 0.25 / 4, carbs = kcal * 0.45 / 4, fat = kcal * 0.30 / 9,
                    satFat = 18.0, sugar = 50.0, addedSugar = 22.0, fiber = 24.0, sodium = 2500.0),
                meals = 3,
                steps = if (i % 7 == 3) null else 6000L + i * 150,
                activeKcal = 300.0, burned = 2300.0,
                sleepH = if (i % 4 == 0) null else 7.2, bedtime = 23.5, wakeTime = 6.9
            )
        }
        val weights = (0 until 15).map { k -> WeightEntry(k.toLong(), ms(today.minusDays(30L - 2 * k)), 82.0 - k * 0.1) }
        return HealthReport("Alex", 38, "Male", 178.0, today, days, goals, weights, zone)
    }

    @Test fun averagesUseOnlyLoggedDays() {
        val r = sample()
        assertEquals(25, r.logged.size)                 // days 6, 12, 18, 24, 30 hold too little
        assertTrue(r.average!!.kcal > 1800)             // the 300 kcal days don't drag it down
        val (p, c, f) = r.macroSplit!!
        assertEquals(25.0, p, 0.5); assertEquals(45.0, c, 0.5); assertEquals(30.0, f, 0.5)
        assertEquals(-400.0, r.avgBalance!!, 150.0)
    }

    @Test fun weightTrendAndRate() {
        val r = sample()
        assertEquals(15, r.dailyWeights.size)
        assertEquals(-0.35, r.weeklyRate!!, 0.01)       // 0.1 kg every 2 days
        assertTrue(r.weightChange!! < 0)
    }

    @Test fun severalWeighInsOnADayCountOnce() {
        val d = today.minusDays(5)
        val w = listOf(WeightEntry(1, ms(d, 7), 80.0), WeightEntry(2, ms(d, 21), 81.2))
        val r = sample().copy(weights = w)
        assertEquals(listOf(d to 80.0), r.dailyWeights)  // the morning reading
        assertNull(r.weeklyRate)
    }

    @Test fun emptyPeriodStillMakesAReport() {
        val days = (1..30).map { ReportDay(today.minusDays((31 - it).toLong()), Totals(), 0) }
        val r = HealthReport("", null, "", 0.0, today, days, goals, emptyList(), zone)
        assertNull(r.average); assertNull(r.avgSteps); assertNull(r.weightChange)
        assertEquals(1, render(r, "empty"))
    }

    // PdfDocument has no working native side under Robolectric, so the tests draw the same pages to bitmaps

    /** Renders the pages to PNGs under build/report-preview, for checking the layout by eye. */
    @Test fun rendersPages() {
        assertTrue(render(sample(), "page") in 1..4)
    }

    private fun render(report: HealthReport, prefix: String): Int {
        val dir = File("build/report-preview").apply { mkdirs() }
        var bmp: Bitmap? = null
        var n = 0
        return ReportPdf.draw(report,
            start = { i ->
                n = i
                bmp = Bitmap.createBitmap(ReportPdf.W * 2, ReportPdf.H * 2, Bitmap.Config.ARGB_8888)
                Canvas(bmp!!).apply { drawColor(Color.WHITE); scale(2f, 2f) }
            },
            finish = { File(dir, "$prefix$n.png").outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) } })
    }
}
