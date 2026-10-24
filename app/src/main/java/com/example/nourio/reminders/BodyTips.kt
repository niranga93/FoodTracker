package com.example.nourio.reminders

import com.example.nourio.ForecastResult
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * Tips from the body model and the weigh-in filter (see BodyModel and WeightFilter), each sent only when
 * the person's own numbers show the situation:
 *
 * - Scale jump: this morning is well above the tissue trend and the model puts much of it down to water from
 *   carbohydrate (~2.7 g per g of glycogen) or salt. Said so the jump isn't read as fat gain.
 * - Losing too fast: more than ~1% of body weight a week, or a deficit over 1,000 kcal a day. Fast loss takes
 *   more lean tissue (Forbes; Hall 2007) and is hard to keep up.
 * - Missing food: weigh-ins show a reliable gap above 150 kcal a day. Self-reports usually come out 10–35%
 *   low against doubly labelled water.
 * - Slowing is normal: in a steady deficit, burn falls about 14% of the cut as the body adapts (Hall 2011),
 *   and further as weight falls.
 * - Protein while losing: under 1.2 g per kg in a deficit. Higher protein keeps more lean mass during weight
 *   loss (Kim et al., Nutr Rev 2016), and it costs the most to digest (~25%).
 * - Watch calories: devices tend to overestimate exercise energy (errors of 15–40% in validation studies),
 *   and part of exercise is offset by moving less or eating more (Pontzer), so not all of it should be eaten back.
 */
object BodyTips {
    data class Signals(
        val trendKg: Double,               // smoothed weight now
        val waterKg: Double?,              // carb and salt water above usual, from the model
        val latestMorningKg: Double?,      // today's or yesterday's first weigh-in
        val gapKcal: Double?,
        val gapSd: Double?,
        val weighIns: Int,
        val balanceKcal: Double,           // eaten + gap - burned, per day, recent
        val tissueKgPerWeek: Double,
        val proteinGPerKg: Double?,
        val deviceActiveKcal: Double?,
    )

    data class Tip(val id: String, val score: Double, val urgent: Boolean, val title: String, val body: String)

    private val nf: NumberFormat = NumberFormat.getIntegerInstance()
    private fun n(x: Double) = nf.format(x.roundToInt())
    private fun kg(x: Double) = "%.1f".format(x)

    fun signals(r: ForecastResult?): Signals? {
        val p = r?.prediction ?: return null
        val now = p.nowKg ?: return null
        return Signals(
            trendKg = now,
            waterKg = p.waterKg,
            latestMorningKg = r.latestMorning?.second,
            gapKcal = p.gapKcal, gapSd = p.gapSd, weighIns = p.weighIns,
            balanceKcal = p.avgBalance,
            tissueKgPerWeek = p.weeklyKg,
            proteinGPerKg = r.avgProteinG?.let { it / now },
            deviceActiveKcal = r.avgDeviceActiveKcal,
        )
    }

    fun tips(s: Signals): List<Tip> = buildList {
        // The tissue trend: smoothed weight without today's modelled water
        val water = s.waterKg ?: 0.0
        val tissue = s.trendKg - water
        s.latestMorningKg?.let { scale ->
            val jump = scale - tissue
            if (jump >= 0.8 && water >= 0.3) add(Tip("body_water_jump", 2.0, urgent = true,
                "Up ${kg(jump)} kg? Mostly water",
                "Your scale shows ${kg(scale)} kg, but your trend is about ${kg(tissue)} kg. Around ${kg(water)} kg of the " +
                    "difference is water held with recent carbohydrate and salt, which passes in a day or two. One day " +
                    "can't add that much fat: even 500 kcal over is under 0.1 kg. Keep going as you were."))
        }

        val fastLoss = -s.tissueKgPerWeek > 0.01 * s.trendKg || s.balanceKcal < -1000
        if (fastLoss) add(Tip("body_too_fast", 1.8, urgent = true,
            "Slow down a little to keep your muscle",
            "You're on course to lose about ${kg(-s.tissueKgPerWeek)} kg a week" +
                (if (s.balanceKcal < -1000) " (about ${n(-s.balanceKcal)} kcal a day under what you burn)" else "") +
                ". Losing faster than about 1% of your weight a week takes more muscle with the fat and is hard to keep up. " +
                "A little more food, mostly protein, protects your muscle and energy. If this is on purpose, talk it over " +
                "with a doctor or dietitian."))

        val gap = s.gapKcal
        val sd = s.gapSd
        if (gap != null && sd != null && s.weighIns >= 5 && gap > 150 && gap > 2 * sd) add(Tip("body_log_gap", gap / 300,
            urgent = false, "Some food may be missing from your log",
            "Your weigh-ins move as if you eat about ${n(gap)} kcal a day more than you log. That's very common: most " +
                "people's logs come out 10 to 35% low. The usual culprits are cooking oil and butter, drinks, sauces, and " +
                "bites while cooking. Logging these for a few days makes your budget and forecast more accurate."))

        if (s.balanceKcal < -300 && !fastLoss) add(Tip("body_adapt", 0.5, urgent = false,
            "If weight loss slows, that's expected",
            "In a steady deficit your body burns a little less over time: about 14% of the cut as it adapts, plus less " +
                "as you get lighter. So progress slows even when you do everything right. Nourio's budget and forecast " +
                "already allow for this. Strength training twice a week and enough protein keep the slowdown small."))

        s.proteinGPerKg?.let { p ->
            if (s.balanceKcal < -250 && p < 1.2) add(Tip("body_protein", 0.5 + (1.2 - p) / 1.2, urgent = false,
                "More protein while you lose weight",
                "You're averaging about ${"%.1f".format(p)} g of protein per kg of body weight. While eating less, around 1.2 g " +
                    "per kg or more helps you keep muscle and lose more of the weight as fat, and protein keeps you fuller. " +
                    "Add a protein food to each meal: eggs, yoghurt, fish, chicken, tofu, beans or lentils."))
        }

        s.deviceActiveKcal?.let { a ->
            if (a >= 300) add(Tip("body_watch", 0.4, urgent = false,
                "Eat back only part of your exercise calories",
                "Your watch counts about ${n(a)} kcal of exercise a day. Watches tend to overestimate exercise calories, and " +
                    "the body makes up some of it by moving less the rest of the day. Eating back about half is a safer rule; " +
                    "your weigh-ins will show if you need more."))
        }
    }
}
