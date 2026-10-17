package com.example.nourio.plan

import android.content.Context
import com.example.nourio.ai.AiConsent
import com.example.nourio.ai.AiSettings
import com.example.nourio.ai.AiUse
import com.example.nourio.ai.Llm
import com.example.nourio.ai.LlmRequest
import com.example.nourio.ai.LlmTurn
import com.example.nourio.ai.Provider
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Asks the person's AI service whether any standard in [Standards] has officially changed. Sends only
 * the standards and their sources, never anything about the person, and only with their consent
 * ([AiUse.STANDARDS]). The answer is screened by [Standards.screen] and kept for the person to review.
 */
object StandardsChecker {

    /** Whether the chosen service can search the web; others answer from what their model already knows. */
    fun searches(): Boolean = AiSettings.provider == Provider.GEMINI || AiSettings.provider == Provider.CLAUDE

    private val SCHEMA = JSONObject("""
        {"type":"OBJECT","properties":{
          "changes":{"type":"ARRAY","items":{"type":"OBJECT","properties":{
            "key":{"type":"STRING"},
            "proposed":{"type":"ARRAY","items":{"type":"NUMBER"}},
            "source_title":{"type":"STRING"},
            "source_url":{"type":"STRING"},
            "published":{"type":"STRING"},
            "quote":{"type":"STRING"},
            "reason":{"type":"STRING"}},
            "required":["key","proposed","source_title","source_url","published","quote","reason"]}}},
         "required":["changes"]}
    """.trimIndent())

    fun prompt(today: LocalDate = LocalDate.now()): String = """
        Today is $today. A nutrition app bases its advice on these values, each with the guideline it comes from:

        ${Standards.describeForCheck().trim()}

        For each value, check whether the organisation behind it (WHO, the Dietary Guidelines for Americans,
        the US/Canada Dietary Reference Intakes / National Academies, the American Heart Association, the
        US FDA, the American Academy of Sleep Medicine / Sleep Research Society, or the cited study group)
        has officially published a different number since the cited guideline. A new edition of a guideline
        counts. News articles, single studies, blogs and opinion pieces do not.

        Report only values that officially changed, with the official document's title, its https URL, its
        publication date, a short quote of the sentence that states the new number, and one sentence on
        what changed. Use the same units as above (shares as fractions, e.g. 0.10 not 10%). For the
        amdr_* ranges give two numbers, low and high. Never guess a number: if you can't confirm a change
        from an official source, leave that value out. If nothing changed, return an empty list.

        Reply with JSON only, in this shape:
        {"changes":[{"key":"sodium_mg","proposed":[2000],"source_title":"...","source_url":"https://...",
        "published":"YYYY-MM","quote":"...","reason":"..."}]}
    """.trimIndent()

    fun parse(json: String): List<Proposal> {
        val a: JSONArray = JSONObject(json).optJSONArray("changes") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            runCatching {
                val o = a.getJSONObject(i)
                val nums = o.getJSONArray("proposed")
                Proposal(
                    key = o.getString("key"),
                    proposed = (0 until nums.length()).map { nums.getDouble(it) },
                    sourceTitle = o.optString("source_title"), sourceUrl = o.optString("source_url"),
                    published = o.optString("published"), quote = o.optString("quote"), reason = o.optString("reason"),
                )
            }.getOrNull()
        }
    }

    /**
     * Runs the check. Returns the new proposals now waiting for review (empty when nothing changed).
     * Throws when there is no consent, no AI service, or the request fails.
     */
    suspend fun check(context: Context): List<Proposal> {
        AiConsent.require(AiUse.STANDARDS)
        val reply = Llm.complete(
            LlmRequest(
                system = "You check public health guidelines against their official sources. You are careful and never invent numbers.",
                turns = listOf(LlmTurn("user", prompt())),
                schema = SCHEMA,
                temperature = 0.0,
                maxTokens = 8000,
                webSearch = searches(),
            )
        )
        val found = parse(Llm.extractJson(reply))
        Standards.markChecked(context)
        return Standards.addPending(context, found)
    }
}
