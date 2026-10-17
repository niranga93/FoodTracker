package com.example.nourio.export

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lays the [HealthReport] out on A4 pages. Drawing goes to a plain Canvas, so the same code fills a
 * PdfDocument in the app and a bitmap in tests.
 */
object ReportPdf {
    const val W = 595          // A4 in PostScript points
    const val H = 842
    private const val M = 40f  // page margin

    fun write(report: HealthReport, out: OutputStream) {
        val doc = PdfDocument()
        try {
            var page: PdfDocument.Page? = null
            draw(report,
                start = { n -> doc.startPage(PdfDocument.PageInfo.Builder(W, H, n).create()).also { page = it }.canvas },
                finish = { doc.finishPage(page) })
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    /** Draws every page; [start] supplies a blank canvas for page n (1-based), [finish] closes it. Returns the page count. */
    fun draw(report: HealthReport, start: (Int) -> Canvas, finish: () -> Unit): Int {
        val r = Renderer(report, start, finish)
        r.all()
        return r.page
    }

    // Colours: print-friendly, readable in black and white too
    private const val INK = 0xFF1F2933.toInt()
    private const val MUTED = 0xFF66707A.toInt()
    private const val LINE = 0xFFD9DEE3.toInt()
    private const val SOFT = 0xFFF2F5F4.toInt()
    private const val BRAND = 0xFF1F6F54.toInt()
    private const val PROTEIN = 0xFF2F6FDB.toInt()
    private const val CARBS = 0xFFE0A030.toInt()
    private const val FAT = 0xFFD9534F.toInt()
    private const val OVER = 0xFFE0A030.toInt()

    private class Renderer(val r: HealthReport, val startPage: (Int) -> Canvas, val finishPage: () -> Unit) {
        lateinit var c: Canvas
        var page = 0
        var y = 0f
        val right = W - M
        val width = W - 2 * M
        val loc: Locale = Locale.getDefault()
        val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", loc)
        val shortFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", loc)

        fun paint(size: Float, color: Int = INK, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size; this.color = color; textAlign = align
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }

        val body = paint(10f)
        val bodyBold = paint(10f, bold = true)
        val muted = paint(8.5f, MUTED)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f; color = LINE }

        fun num(v: Double, decimals: Int = 0): String =
            if (decimals == 0) String.format(loc, "%,d", v.roundToInt()) else String.format(loc, "%,.${decimals}f", v)

        fun signed(v: Double, decimals: Int = 1): String = (if (v > 0) "+" else if (v < 0) "−" else "") + num(abs(v), decimals)

        fun clock(h: Double): String {
            val m = ((h % 24 + 24) % 24 * 60).roundToInt() % (24 * 60)
            return String.format(loc, "%02d:%02d", m / 60, m % 60)
        }

        // ---- pages ----

        fun newPage() {
            if (page > 0) endPage()
            page++
            c = startPage(page)
            y = M
        }

        fun endPage() {
            val footY = H - 22f
            c.drawLine(M, footY - 12, right, footY - 12, stroke)
            c.drawText("Nourio · self-logged data, not a medical record", M, footY, muted)
            c.drawText("Page $page", right, footY, paint(8.5f, MUTED, align = Paint.Align.RIGHT))
            finishPage()
        }

        /** Starts a new page if [h] points don't fit on this one. */
        fun need(h: Float) { if (y + h > H - 50f) newPage() }

        fun all() {
            newPage()
            header()
            tiles()
            nutrition()
            calorieChart()
            weight()
            activity()
            sleep()
            notes()
            endPage()
        }

        // ---- blocks ----

        fun text(s: String, p: TextPaint = body, x: Float = M, w: Float = width): Float {
            val layout = StaticLayout.Builder.obtain(s, 0, s.length, p, w.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(1.5f, 1f).build()
            need(layout.height.toFloat())
            c.save(); c.translate(x, y); layout.draw(c); c.restore()
            y += layout.height
            return layout.height.toFloat()
        }

        fun section(title: String, minBody: Float) {
            need(28f + minBody)
            y += 14f
            c.drawText(title.uppercase(loc), M, y + 10f, paint(10.5f, BRAND, bold = true))
            y += 16f
            c.drawLine(M, y, right, y, stroke)
            y += 10f
        }

        fun header() {
            fill.color = BRAND
            c.drawRect(0f, 0f, W.toFloat(), 86f, fill)
            val white = 0xFFFFFFFF.toInt()
            c.drawText("Health summary", M, 40f, paint(22f, white, bold = true))
            c.drawText("${r.from.format(dateFmt)} – ${r.to.format(dateFmt)}  ·  last ${r.days.size} days",
                M, 60f, paint(10f, 0xFFDDEFE7.toInt()))
            c.drawText("Nourio", right, 40f, paint(14f, white, bold = true, align = Paint.Align.RIGHT))
            c.drawText("Generated ${r.generated.format(dateFmt)}", right, 60f,
                paint(9f, 0xFFDDEFE7.toInt(), align = Paint.Align.RIGHT))
            y = 108f

            val latest = r.dailyWeights.lastOrNull()?.second
            val bits = buildList {
                if (r.name.isNotEmpty()) add(r.name)
                r.age?.let { add("$it years") }
                if (r.sex.isNotEmpty() && r.sex != "Other") add(r.sex)
                if (r.heightCm > 0) add("${num(r.heightCm)} cm")
                latest?.let { kg ->
                    add("${num(kg, 1)} kg")
                    if (r.heightCm > 0) add("BMI ${num(kg / ((r.heightCm / 100) * (r.heightCm / 100)), 1)}")
                }
            }
            if (bits.isNotEmpty()) {
                c.drawText(bits.joinToString("   ·   "), M, y, paint(11f, bold = true))
                y += 8f
            }
        }

        fun tiles() {
            val avg = r.average
            val items = listOf(
                (avg?.let { num(it.kcal) } ?: "—") to "kcal / day eaten",
                "${r.logged.size} of ${r.days.size}" to "days logged",
                (r.weightChange?.let { "${signed(it)} kg" } ?: "—") to "weight change",
                (r.avgSteps?.let { num(it) } ?: "—") to "steps / day"
            )
            y += 10f
            val gap = 8f
            val tw = (width - gap * 3) / 4
            val th = 50f
            items.forEachIndexed { i, (big, label) ->
                val x = M + i * (tw + gap)
                fill.color = SOFT
                c.drawRoundRect(RectF(x, y, x + tw, y + th), 6f, 6f, fill)
                c.drawText(big, x + 10f, y + 24f, paint(15f, INK, bold = true))
                c.drawText(label, x + 10f, y + 40f, muted)
            }
            y += th
        }

        fun nutrition() {
            section("Nutrition", 170f)
            val avg = r.average
            if (avg == null) {
                text("No days with a complete food log in this period (at least ${num(com.example.nourio.energy.EnergyModel.MIN_LOGGED_KCAL)} kcal logged).", muted)
                return
            }
            val g = r.goals
            data class Row(val name: String, val value: Double, val unit: String, val goal: Double, val limit: Boolean)
            val rows = listOf(
                Row("Energy", avg.kcal, "kcal", g.kcal, false),
                Row("Protein", avg.protein, "g", g.protein, false),
                Row("Carbohydrate", avg.carbs, "g", g.carbs, false),
                Row("Fibre", avg.fiber, "g", g.fiber, false),
                Row("Fat", avg.fat, "g", g.fat, true),
                Row("Saturated fat", avg.satFat, "g", g.satFat, true),
                Row("Sugar, added", avg.addedSugar, "g", g.addedSugar, true),
                Row("Sugar, natural", avg.naturalSugar, "g", 0.0, false),
                Row("Sodium", avg.sodium, "mg", g.sodiumMg, true)
            )
            val col1 = M + 6f; val col2 = M + 250f; val col3 = M + 360f; val col4 = right - 6f
            val head = paint(8.5f, MUTED, bold = true)
            c.drawText("NUTRIENT", col1, y + 8f, head)
            c.drawText("DAILY AVERAGE", col2, y + 8f, paint(8.5f, MUTED, bold = true, align = Paint.Align.RIGHT))
            c.drawText("GOAL", col3, y + 8f, paint(8.5f, MUTED, bold = true, align = Paint.Align.RIGHT))
            c.drawText("OF GOAL", col4, y + 8f, paint(8.5f, MUTED, bold = true, align = Paint.Align.RIGHT))
            y += 14f
            val rightAlign = paint(10f, align = Paint.Align.RIGHT)
            rows.forEachIndexed { i, row ->
                if (i % 2 == 0) { fill.color = SOFT; c.drawRect(M, y, right, y + 18f, fill) }
                val base = y + 12.5f
                c.drawText(row.name, col1, base, body)
                c.drawText("${num(row.value, if (row.value < 10 && row.unit == "g") 1 else 0)} ${row.unit}", col2, base, rightAlign)
                if (row.goal > 0) {
                    c.drawText("${if (row.limit) "max " else ""}${num(row.goal)} ${row.unit}", col3, base, rightAlign)
                    val pct = row.value / row.goal * 100
                    val bad = (row.limit && pct > 100) || (row.name == "Energy" && abs(pct - 100) > 15)
                    c.drawText("${num(pct)}%", col4, base, paint(10f, if (bad) FAT else INK, bold = bad, align = Paint.Align.RIGHT))
                } else {
                    c.drawText("—", col3, base, paint(10f, MUTED, align = Paint.Align.RIGHT))
                }
                y += 18f
            }
            y += 6f
            text("Averages over the ${r.logged.size} days with a complete log (at least ${num(com.example.nourio.energy.EnergyModel.MIN_LOGGED_KCAL)} kcal). " +
                "Days with little or nothing logged are left out. Energy was within 10% of the goal on ${r.daysNearGoal} of them.", muted)

            r.macroSplit?.let { (p, cb, f) ->
                need(60f)
                y += 12f
                c.drawText("Energy from protein, carbohydrate and fat", M, y + 8f, bodyBold)
                y += 16f
                val bh = 16f
                var x = M
                listOf(p to PROTEIN, cb to CARBS, f to FAT).forEach { (pct, col) ->
                    val w = (width * pct / 100).toFloat()
                    fill.color = col
                    c.drawRect(x, y, x + w, y + bh, fill)
                    if (w > 34) c.drawText("${num(pct)}%", x + w / 2, y + 11.5f, paint(9f, 0xFFFFFFFF.toInt(), bold = true, align = Paint.Align.CENTER))
                    x += w
                }
                y += bh + 12f
                var lx = M
                listOf("Protein ${num(p)}%" to PROTEIN, "Carbohydrate ${num(cb)}%" to CARBS, "Fat ${num(f)}%" to FAT).forEach { (label, col) ->
                    fill.color = col
                    c.drawCircle(lx + 4f, y - 3f, 4f, fill)
                    c.drawText(label, lx + 12f, y, body)
                    lx += body.measureText(label) + 30f
                }
                y += 12f
                text("Reference ranges for adults (AMDR): protein 10–35%, carbohydrate 45–65%, fat 20–35% of energy.", muted)
            }
        }

        /** Axis with a few round ticks; returns the top value. */
        fun niceMax(v: Double): Double {
            if (v <= 0) return 1.0
            val mag = Math.pow(10.0, Math.floor(Math.log10(v)))
            val steps = listOf(1.0, 1.2, 1.6, 2.0, 2.4, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0)   // each splits into 4 round ticks
            return steps.first { it * mag >= v } * mag
        }

        /** A bar per day with a dashed goal line. [values] are null on days without data. */
        fun barChart(values: List<Double?>, goal: Double?, unit: String, highlightOver: Boolean, h: Float = 120f) {
            need(h + 44f)
            val left = M + 34f
            val top = y + 18f            // room for the goal label
            val bottom = top + h
            val maxV = niceMax(max(values.filterNotNull().maxOrNull() ?: 0.0, goal ?: 0.0) * 1.05)
            fun yOf(v: Double) = (bottom - v / maxV * h).toFloat()
            val axis = paint(8f, MUTED, align = Paint.Align.RIGHT)
            for (i in 0..4) {
                val v = maxV * i / 4
                val yy = yOf(v)
                c.drawLine(left, yy, right, yy, stroke)
                c.drawText(num(v), left - 4f, yy + 3f, axis)
            }
            val n = values.size
            val slot = (right - left) / n
            val bw = slot * 0.66f
            values.forEachIndexed { i, v ->
                val x = left + i * slot + (slot - bw) / 2
                if (v == null || v <= 0) {
                    fill.color = LINE
                    c.drawRect(x, bottom - 2f, x + bw, bottom, fill)
                } else {
                    fill.color = if (highlightOver && goal != null && goal > 0 && v > goal * 1.10) OVER else BRAND
                    c.drawRect(x, yOf(v), x + bw, bottom, fill)
                }
            }
            if (goal != null && goal > 0) {
                val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 1.2f; color = INK
                    pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f)
                }
                val gy = yOf(goal)
                c.drawPath(Path().apply { moveTo(left, gy); lineTo(right, gy) }, gp)
                // Labelled above the chart, where no bar can cover it
                val gl = "goal ${num(goal)} $unit"
                val gpaint = paint(8f, INK, bold = true, align = Paint.Align.RIGHT)
                c.drawText(gl, right, top - 6f, gpaint)
                c.drawPath(Path().apply { val x = right - gpaint.measureText(gl) - 6f; moveTo(x - 16f, top - 9f); lineTo(x, top - 9f) }, gp)
            }
            // Date labels about every 5 days
            val lbl = paint(8f, MUTED, align = Paint.Align.CENTER)
            r.days.forEachIndexed { i, d ->
                if (i % 5 == 0 || i == n - 1) c.drawText(d.date.format(shortFmt), left + i * slot + slot / 2, bottom + 11f, lbl)
            }
            y = bottom + 28f
        }

        fun calorieChart() {
            if (r.logged.isEmpty()) return
            section("Daily calories", 150f)
            barChart(r.days.map { d -> d.totals.kcal.takeIf { d.logged } }, r.goals.kcal.takeIf { it > 0 }, "kcal", highlightOver = true)
            legend(listOf("Logged day" to BRAND, "More than 10% over goal" to OVER, "Not logged" to LINE))
        }

        fun legend(items: List<Pair<String, Int>>) {
            var lx = M + 34f
            items.forEach { (label, col) ->
                fill.color = col
                c.drawRect(lx, y - 7f, lx + 8f, y + 1f, fill)
                c.drawText(label, lx + 12f, y, muted)
                lx += muted.measureText(label) + 26f
            }
            y += 6f
        }

        fun weight() {
            val dw = r.dailyWeights
            section("Weight", if (dw.size >= 2) 190f else 20f)
            if (dw.size < 2) {
                text(if (dw.isEmpty()) "No weigh-ins in this period." else "One weigh-in in this period (${num(dw[0].second, 1)} kg on ${dw[0].first.format(dateFmt)}); a trend needs at least two.", muted)
                return
            }
            val trend = r.weightTrend
            val facts = buildList {
                add("First" to "${num(dw.first().second, 1)} kg  (${dw.first().first.format(shortFmt)})")
                add("Latest" to "${num(dw.last().second, 1)} kg  (${dw.last().first.format(shortFmt)})")
                r.weightChange?.let { add("Trend change" to "${signed(it)} kg") }
                add("Rate" to (r.weeklyRate?.let { "${signed(it, 2)} kg / week" } ?: "needs readings a week apart"))
                add("Weigh-ins" to "${dw.size} days")
            }
            facts(facts)

            // Chart: dots for weigh-ins, line for the smoothed trend
            val h = 110f
            need(h + 30f)
            val left = M + 34f
            val top = y + 6f
            val bottom = top + h
            // Round kg ticks: 0.5, 1, 2 or 5 kg apart depending on the range
            val minKg = dw.minOf { it.second } - 0.3; val maxKg = dw.maxOf { it.second } + 0.3
            val tick = listOf(0.5, 1.0, 2.0, 5.0).firstOrNull { (maxKg - minKg) / it <= 6 } ?: 10.0
            val lo = Math.floor(minKg / tick) * tick
            val hi = (ceil(maxKg / tick) * tick).coerceAtLeast(lo + tick)
            val ticks = ((hi - lo) / tick).roundToInt()
            val x0 = r.from.toEpochDay(); val span = (r.to.toEpochDay() - x0).coerceAtLeast(1)
            fun xOf(d: java.time.LocalDate) = left + (d.toEpochDay() - x0).toFloat() / span * (right - left)
            fun yOf(v: Double) = (bottom - (v - lo) / (hi - lo) * h).toFloat()
            val axis = paint(8f, MUTED, align = Paint.Align.RIGHT)
            for (i in 0..ticks) {
                val v = lo + tick * i
                c.drawLine(left, yOf(v), right, yOf(v), stroke)
                c.drawText(num(v, 1), left - 4f, yOf(v) + 3f, axis)
            }
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = BRAND }
            val path = Path()
            dw.forEachIndexed { i, (d, _) -> if (i == 0) path.moveTo(xOf(d), yOf(trend[i])) else path.lineTo(xOf(d), yOf(trend[i])) }
            c.drawPath(path, line)
            fill.color = INK
            dw.forEach { (d, kg) -> c.drawCircle(xOf(d), yOf(kg), 2.4f, fill) }
            val lbl = paint(8f, MUTED, align = Paint.Align.CENTER)
            r.days.forEachIndexed { i, d -> if (i % 5 == 0 || i == r.days.size - 1) c.drawText(d.date.format(shortFmt), xOf(d.date), bottom + 11f, lbl) }
            y = bottom + 30f
            fill.color = INK; c.drawCircle(left + 4f, y - 3f, 2.4f, fill)
            c.drawText("Weigh-in", left + 12f, y, muted)
            c.drawLine(left + 70f, y - 3f, left + 84f, y - 3f, line)
            c.drawText("Smoothed trend", left + 90f, y, muted)
            y += 6f
        }

        /** Label and value pairs, two to a row. */
        fun facts(items: List<Pair<String, String>>) {
            val colW = width / 2
            items.chunked(2).forEach { pair ->
                need(16f)
                pair.forEachIndexed { i, (k, v) ->
                    val x = M + i * colW
                    c.drawText(k, x, y + 10f, muted)
                    c.drawText(v, x + 92f, y + 10f, bodyBold)
                }
                y += 16f
            }
            y += 4f
        }

        fun activity() {
            val stepDays = r.stepDays
            section("Activity (Health Connect)", if (stepDays.isNotEmpty()) 190f else 20f)
            if (stepDays.isEmpty() && r.avgBurned == null) {
                text("No activity from Health Connect for this period (not connected, or no data).", muted)
                return
            }
            facts(buildList {
                r.avgSteps?.let { add("Steps" to "${num(it)} / day  (${stepDays.size} days with data)") }
                if (stepDays.isNotEmpty()) add("Step goal met" to "${r.daysStepGoal} of ${stepDays.size} days  (goal ${num(r.goals.stepGoal.toDouble())})")
                r.avgActive?.let { add("Active burn" to "${num(it)} kcal / day") }
                r.avgBurned?.let { add("Total burn" to "${num(it)} kcal / day (estimated)") }
                r.avgBalance?.let { add("Balance" to "${signed(it, 0)} kcal / day  (eaten − burned)") }
            })
            if (stepDays.isNotEmpty()) {
                barChart(r.days.map { it.steps?.toDouble() }, r.goals.stepGoal.toDouble(), "steps", highlightOver = false, h = 100f)
                legend(listOf("Steps" to BRAND, "No data" to LINE))
            }
            text("Burn and balance are averaged over logged days only. Total burn combines a resting estimate with " +
                "Health Connect activity and is adjusted to the user's weigh-ins where enough are available.", muted)
        }

        fun sleep() {
            val nights = r.sleepNights
            if (nights.isEmpty()) return
            section("Sleep (Health Connect)", 40f)
            facts(buildList {
                r.avgSleepH?.let { add("Asleep" to "${num(it, 1)} h / night") }
                add("Nights recorded" to "${nights.size} of ${r.days.size}")
                r.avgBedtime?.let { add("Usual bedtime" to clock(it)) }
                r.avgWake?.let { add("Usual wake time" to clock(it)) }
            })
        }

        fun notes() {
            section("About these numbers", 60f)
            listOf(
                "Food intake is logged by the user in the Nourio app (photo, text or barcode). Nutrition values come from USDA FoodData Central where a match is found, otherwise from an estimate; portion sizes are often estimated.",
                "Resting energy uses the Mifflin–St Jeor equation, or the device's own value when Health Connect provides one.",
                "The weight trend is an exponentially smoothed average of the first weigh-in each day; the weekly rate is a Theil–Sen slope, which resists single unusual readings.",
                "This summary is for discussion with a health professional. It is not a medical record or a diagnosis."
            ).forEach { line ->
                text("•  $line", paint(9f, MUTED), x = M, w = width)
                y += 3f
            }
        }
    }
}
