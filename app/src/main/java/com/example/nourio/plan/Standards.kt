package com.example.nourio.plan

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * The published health standards the app's recommendations rest on (WHO, Dietary Guidelines for
 * Americans, US/Canada Dietary Reference Intakes, American Heart Association).
 *
 * The values built into the app are the starting point. Once a month [StandardsChecker] asks the
 * person's AI service whether any of them has officially changed. Each change it reports becomes a
 * [Proposal]: the person sees the current and the new value with the source, and only an approved
 * proposal changes the value. A rejected one is remembered, so the same suggestion isn't shown again.
 * Every proposal must sit inside [BOUNDS] even to be shown, so a wrong answer can't become advice.
 */
data class StandardValues(
    val referenceKcal: Double = 2000.0,
    val fatShare: Double = 0.30,               // max share of calories from fat (WHO)
    val satFatShare: Double = 0.10,            // max share from saturated fat (WHO, DGA)
    val addedSugarShare: Double = 0.10,        // max share from free/added sugar (WHO, DGA)
    val addedSugarCapMaleG: Double = 36.0,     // AHA
    val addedSugarCapFemaleG: Double = 25.0,   // AHA, also used for "Other" and unset
    val fiberPer1000Kcal: Double = 14.0,       // US/Canada Adequate Intake
    val fiberMinG: Double = 21.0,
    val fiberMaxG: Double = 38.0,
    val sodiumMg: Double = 2300.0,             // DGA / Chronic Disease Risk Reduction intake
    val proteinPerKgAdult: Double = 0.8,       // RDA
    val proteinPerKgOlder: Double = 1.0,       // PROT-AGE
    val proteinOlderAge: Double = 65.0,
    val proteinPerKgLosing: Double = 1.2,
    val proteinPerKgGaining: Double = 1.4,
    val amdrProtein: IntRange = 10..35,        // Acceptable Macronutrient Distribution Ranges, % of calories
    val amdrCarbs: IntRange = 45..65,
    val amdrFat: IntRange = 20..35,
    val stepsDefault: Double = 10_000.0,       // daily step goal until the person sets one
    val sleepGoalH: Double = 7.0,              // adults: at least 7 hours (AASM/SRS, CDC)
)

/** One standard: how it's named, shown, bounded and read from or written to [StandardValues]. */
class StandardField(
    val key: String,
    val label: String,
    val source: String,
    val bounds: ClosedFloatingPointRange<Double>,
    val show: (Double) -> String,
    val get: (StandardValues) -> Double,
    val set: (StandardValues, Double) -> StandardValues,
)

/** A percent-of-calories range (the AMDRs). */
class StandardRange(
    val key: String,
    val label: String,
    val source: String,
    val bounds: IntRange,
    val get: (StandardValues) -> IntRange,
    val set: (StandardValues, IntRange) -> StandardValues,
)

/** A change the AI reported, waiting for the person's answer. [proposed] holds one number, or two for a range. */
data class Proposal(
    val key: String,
    val proposed: List<Double>,
    val sourceTitle: String,
    val sourceUrl: String,
    val published: String,
    val quote: String,
    val reason: String,
) {
    fun toJson(): JSONObject = JSONObject().put("key", key).put("proposed", JSONArray(proposed))
        .put("sourceTitle", sourceTitle).put("sourceUrl", sourceUrl).put("published", published)
        .put("quote", quote).put("reason", reason)

    companion object {
        fun fromJson(o: JSONObject) = Proposal(
            o.getString("key"),
            o.getJSONArray("proposed").let { a -> (0 until a.length()).map { a.getDouble(it) } },
            o.optString("sourceTitle"), o.optString("sourceUrl"), o.optString("published"),
            o.optString("quote"), o.optString("reason"),
        )
    }
}

object Standards {
    val DEFAULTS = StandardValues()

    private fun n(x: Double) = if (x % 1.0 == 0.0) "%,d".format(x.toLong()) else "%.2f".format(x).trimEnd('0').trimEnd('.')
    private fun pct(x: Double) = "${n(x * 100)}% of calories"

    /** Every standard the app uses, with the guideline it comes from and the range a new value must fall in. */
    val FIELDS: List<StandardField> = listOf(
        StandardField("sodium_mg", "Sodium limit", "Dietary Guidelines for Americans 2020-2025; NASEM Chronic Disease Risk Reduction intake (2019): under 2,300 mg/day",
            1200.0..3000.0, { "${n(it)} mg a day" }, { it.sodiumMg }, { s, v -> s.copy(sodiumMg = v) }),
        StandardField("fat_share", "Fat limit", "WHO healthy diet: total fat under 30% of energy",
            0.20..0.40, ::pct, { it.fatShare }, { s, v -> s.copy(fatShare = v) }),
        StandardField("sat_fat_share", "Saturated fat limit", "WHO saturated fatty acid guideline (2023); DGA 2020-2025: under 10% of calories",
            0.05..0.12, ::pct, { it.satFatShare }, { s, v -> s.copy(satFatShare = v) }),
        StandardField("added_sugar_share", "Added sugar limit", "WHO sugars guideline (2015); DGA 2020-2025: under 10% of calories",
            0.03..0.12, ::pct, { it.addedSugarShare }, { s, v -> s.copy(addedSugarShare = v) }),
        StandardField("added_sugar_cap_male_g", "Added sugar cap for men", "American Heart Association: at most 36 g (9 tsp) a day for men",
            15.0..60.0, { "${n(it)} g a day" }, { it.addedSugarCapMaleG }, { s, v -> s.copy(addedSugarCapMaleG = v) }),
        StandardField("added_sugar_cap_female_g", "Added sugar cap for women", "American Heart Association: at most 25 g (6 tsp) a day for women",
            10.0..50.0, { "${n(it)} g a day" }, { it.addedSugarCapFemaleG }, { s, v -> s.copy(addedSugarCapFemaleG = v) }),
        StandardField("fiber_per_1000_kcal_g", "Fibre", "US/Canada Dietary Reference Intakes: Adequate Intake 14 g per 1,000 kcal",
            10.0..20.0, { "${n(it)} g per 1,000 kcal" }, { it.fiberPer1000Kcal }, { s, v -> s.copy(fiberPer1000Kcal = v) }),
        StandardField("fiber_min_g", "Lowest fibre goal", "US/Canada DRI Adequate Intake, women 51+ (21 g)",
            15.0..30.0, { "${n(it)} g a day" }, { it.fiberMinG }, { s, v -> s.copy(fiberMinG = v) }),
        StandardField("fiber_max_g", "Highest fibre goal", "US/Canada DRI Adequate Intake, men 19-50 (38 g)",
            25.0..50.0, { "${n(it)} g a day" }, { it.fiberMaxG }, { s, v -> s.copy(fiberMaxG = v) }),
        StandardField("protein_per_kg_adult", "Protein for adults", "US/Canada DRI Recommended Dietary Allowance: 0.8 g/kg",
            0.6..1.2, { "${n(it)} g per kg" }, { it.proteinPerKgAdult }, { s, v -> s.copy(proteinPerKgAdult = v) }),
        StandardField("protein_per_kg_older", "Protein for older adults", "PROT-AGE Study Group (2013): 1.0-1.2 g/kg from 65",
            0.8..1.5, { "${n(it)} g per kg" }, { it.proteinPerKgOlder }, { s, v -> s.copy(proteinPerKgOlder = v) }),
        StandardField("protein_older_age", "Older-adult protein from age", "PROT-AGE Study Group (2013): 65 years",
            50.0..80.0, { "${n(it)} years" }, { it.proteinOlderAge }, { s, v -> s.copy(proteinOlderAge = v) }),
        StandardField("protein_per_kg_losing", "Protein while losing weight", "Higher protein during energy restriction to keep lean mass (ISSN position stand)",
            0.8..2.0, { "${n(it)} g per kg" }, { it.proteinPerKgLosing }, { s, v -> s.copy(proteinPerKgLosing = v) }),
        StandardField("protein_per_kg_gaining", "Protein while gaining weight", "ISSN position stand: 1.4-2.0 g/kg for lean mass gain",
            0.8..2.2, { "${n(it)} g per kg" }, { it.proteinPerKgGaining }, { s, v -> s.copy(proteinPerKgGaining = v) }),
        StandardField("reference_kcal", "Reference calories", "US FDA Daily Value reference diet: 2,000 kcal",
            1500.0..2500.0, { "${n(it)} kcal" }, { it.referenceKcal }, { s, v -> s.copy(referenceKcal = v) }),
        StandardField("steps_default", "Default step goal", "Paluch et al., Lancet Public Health 2022: benefit levels off around 8,000-10,000 steps",
            5000.0..15000.0, { "${n(it)} steps" }, { it.stepsDefault }, { s, v -> s.copy(stepsDefault = v) }),
        StandardField("sleep_goal_h", "Sleep goal", "AASM/Sleep Research Society consensus and CDC: adults need 7 or more hours",
            6.0..9.0, { "${n(it)} hours" }, { it.sleepGoalH }, { s, v -> s.copy(sleepGoalH = v) }),
    )

    val RANGES: List<StandardRange> = listOf(
        StandardRange("amdr_protein", "Protein range", "US/Canada DRI Acceptable Macronutrient Distribution Range: 10-35% of calories",
            5..40, { it.amdrProtein }, { s, r -> s.copy(amdrProtein = r) }),
        StandardRange("amdr_carbs", "Carbohydrate range", "US/Canada DRI Acceptable Macronutrient Distribution Range: 45-65% of calories",
            35..75, { it.amdrCarbs }, { s, r -> s.copy(amdrCarbs = r) }),
        StandardRange("amdr_fat", "Fat range", "US/Canada DRI Acceptable Macronutrient Distribution Range: 20-35% of calories",
            15..45, { it.amdrFat }, { s, r -> s.copy(amdrFat = r) }),
    )

    val KEYS: List<String> get() = FIELDS.map { it.key } + RANGES.map { it.key }

    @Volatile var current: StandardValues = DEFAULTS
        private set

    private const val PREFS = "standards"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Loads the values the person has approved over the built-in ones. */
    fun init(context: Context) {
        current = withApproved(DEFAULTS, approved(context))
    }

    private fun approved(context: Context): JSONObject =
        runCatching { JSONObject(prefs(context).getString("approved", "{}")!!) }.getOrDefault(JSONObject())

    private fun withApproved(base: StandardValues, a: JSONObject): StandardValues {
        var s = base
        FIELDS.forEach { f -> if (a.has(f.key)) s = f.set(s, a.getDouble(f.key)) }
        RANGES.forEach { r -> a.optJSONArray(r.key)?.let { arr -> s = r.set(s, arr.getInt(0)..arr.getInt(1)) } }
        return s
    }

    /** Rules between values, checked before an approved change is used. */
    private fun consistent(s: StandardValues): Boolean =
        s.fiberMinG < s.fiberMaxG && s.satFatShare < s.fatShare &&
            s.fatShare * 100 in s.amdrFat.first.toDouble()..s.amdrFat.last.toDouble()

    /** Current value, in words, for the review screen. */
    fun currentText(key: String, s: StandardValues = current): String =
        FIELDS.firstOrNull { it.key == key }?.let { it.show(it.get(s)) }
            ?: RANGES.first { it.key == key }.get(s).let { "${it.first}–${it.last}% of calories" }

    fun proposedText(p: Proposal): String =
        FIELDS.firstOrNull { it.key == p.key }?.show?.invoke(p.proposed[0])
            ?: "${p.proposed[0].toInt()}–${p.proposed[1].toInt()}% of calories"

    fun label(key: String): String =
        FIELDS.firstOrNull { it.key == key }?.label ?: RANGES.first { it.key == key }.label

    /** The values the AI is asked about: current number and the guideline it came from. */
    fun describeForCheck(s: StandardValues = current): String = buildString {
        FIELDS.forEach { appendLine("- ${it.key} = ${it.get(s)}  (${it.label}; source: ${it.source})") }
        RANGES.forEach { r -> appendLine("- ${r.key} = [${r.get(s).first}, ${r.get(s).last}]  (${r.label}; source: ${r.source})") }
    }

    /**
     * Keeps only proposals worth showing: a known key, inside its bounds, different from the current
     * value, with an https source, and not something the person already rejected.
     */
    fun screen(context: Context, proposals: List<Proposal>): List<Proposal> {
        val rejected = prefs(context).getStringSet("rejected", emptySet())!!
        return proposals.filter { p ->
            if (!p.sourceUrl.startsWith("https://") || "${p.key}=${p.proposed}" in rejected) return@filter false
            val f = FIELDS.firstOrNull { it.key == p.key }
            val r = RANGES.firstOrNull { it.key == p.key }
            when {
                f != null -> p.proposed.size == 1 && p.proposed[0] in f.bounds && p.proposed[0] != f.get(current) &&
                    consistent(f.set(current, p.proposed[0]))
                r != null -> p.proposed.size == 2 && p.proposed.all { it % 1.0 == 0.0 } &&
                    p.proposed[0] < p.proposed[1] && p.proposed[0].toInt() in r.bounds && p.proposed[1].toInt() in r.bounds &&
                    (p.proposed[0].toInt()..p.proposed[1].toInt()) != r.get(current) &&
                    consistent(r.set(current, p.proposed[0].toInt()..p.proposed[1].toInt()))
                else -> false
            }
        }.distinctBy { it.key }
    }

    fun pending(context: Context): List<Proposal> =
        runCatching {
            val a = JSONArray(prefs(context).getString("pending", "[]"))
            (0 until a.length()).map { Proposal.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())

    /** Adds new proposals for review; a newer one for the same standard replaces the older one. Returns those added. */
    fun addPending(context: Context, proposals: List<Proposal>): List<Proposal> {
        val fresh = screen(context, proposals)
        if (fresh.isEmpty()) return fresh
        val keep = pending(context).filter { old -> fresh.none { it.key == old.key } }
        savePending(context, keep + fresh)
        return fresh
    }

    private fun savePending(context: Context, list: List<Proposal>) =
        prefs(context).edit { putString("pending", JSONArray(list.map { it.toJson() }).toString()) }

    /** The person agreed: the new value is used from now on. False if it no longer fits (then it's dropped). */
    fun approve(context: Context, p: Proposal): Boolean {
        savePending(context, pending(context).filter { it.key != p.key })
        if (screen(context, listOf(p)).isEmpty()) return false
        val a = approved(context)
        if (p.proposed.size == 1) a.put(p.key, p.proposed[0]) else a.put(p.key, JSONArray(p.proposed.map { it.toInt() }))
        prefs(context).edit {
            putString("approved", a.toString())
            putString("history", (history(context) + "${java.time.LocalDate.now()}: ${label(p.key)} ${currentText(p.key)} → ${proposedText(p)} (${p.sourceTitle})")
                .takeLast(20).joinToString("\n"))
        }
        current = withApproved(DEFAULTS, a)
        return true
    }

    /** The person said no: nothing changes, and this exact suggestion won't be shown again. */
    fun reject(context: Context, p: Proposal) {
        savePending(context, pending(context).filter { it.key != p.key })
        val set = prefs(context).getStringSet("rejected", emptySet())!! + "${p.key}=${p.proposed}"
        prefs(context).edit { putStringSet("rejected", set) }
    }

    /** Approved changes, oldest first. */
    fun history(context: Context): List<String> =
        prefs(context).getString("history", null)?.split("\n")?.filter { it.isNotBlank() }.orEmpty()

    /** Back to the built-in values; pending suggestions and the rejected list are cleared too. */
    fun resetToBuiltIn(context: Context) {
        prefs(context).edit { remove("approved"); remove("pending"); remove("rejected") }
        current = DEFAULTS
    }

    fun lastChecked(context: Context): Long = prefs(context).getLong("checkedAt", 0L)
    fun markChecked(context: Context, at: Long = System.currentTimeMillis()) = prefs(context).edit { putLong("checkedAt", at) }

    /** Tests only. */
    fun resetForTests() { current = DEFAULTS }
}
