package com.example.nourio.assistant

import android.content.Context
import androidx.core.content.edit
import com.example.nourio.ai.FoodAnalyzer
import com.example.nourio.data.MealDb
import java.time.LocalDate

/**
 * Nourio Assistant. Gemini receives only the question (and earlier questions in the chat, with the
 * plans it wrote for them) plus the catalog of measures below. It never receives a value from the
 * user's data. It returns a [Plan]; the phone runs it with [Executor] and fills in the numbers.
 */
object Assistant {
    private fun catalog() = buildString {
        appendLine("Measures (metric: meaning, unit):")
        AMetric.entries.forEach { appendLine("- ${it.key}: ${it.describe} (${it.unit.ifEmpty { "count" }})") }
        appendLine("Aggregations:")
        Agg.entries.forEach { appendLine("- ${it.key}: ${it.describe}") }
        appendLine("Range kinds: today, yesterday, last_days (with days), this_week (Monday to today), last_week, this_month, last_month, between (from/to as YYYY-MM-DD), all_time.")
        appendLine("Goals you can name (values stay on the phone): ${GOAL_KEYS.joinToString(", ")}.")
    }

    private fun system(today: LocalDate) = """
You plan answers and actions for Nourio, a food and health tracking app. You NEVER see the user's data and must never
invent or guess any number about them. Instead you return a plan that the phone runs on its own data, and an
answer template the phone fills in.

Today is ${today.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} $today.

${catalog()}
How to plan:
- kind "data": the question is about the user's own records. Add steps (each with an id like s1, s2) and,
  for "does X go with Y" questions, links (ids l1, l2). Sleep belongs to the day the user wakes up, so
  "after a short night" is the same day (next_day false); "the day after eating late" is next_day true.
- food_* metrics need `food` set to one simple food word (singular, e.g. "pizza", "chocolate").
- For "how often did I reach my goal" use count_days with where {metric, op, goal} instead of a value.
- Compare groups (weekends vs weekdays, this week vs last week) with two steps and {diff:s1,s2}.
- kind "general": a nutrition or app question that needs none of their data. Put a short, plain answer
  (at most 80 words) in `general` and repeat it in `answer`. No steps.
- kind "action": the user wants to report a bug, suggest a feature, report a problem with their data,
  delete data, or withdraw a consent or permission. Fill `action`. The app shows it as a card and does
  nothing until the user confirms, so describe it, don't claim it's done.
  - report_bug, feature_request, data_issue: `title` (at most 8 words) and `details`: a clear draft built
    only from the user's words (what happened, what they expected, steps if given). Never invent details.
    answer: "Here's a draft. Check it, then send it from your email app."
  - delete_data: `scope` is meals, water, weights, favorites, chat or everything. Add `range` only when the
    user names dates; leave it out for "all my meals". Use everything only when they clearly ask to erase
    all their data. answer: one sentence saying the card shows exactly what would go.
  - withdraw_consent: `consent` is ai_photos, ai_text, ai_coach, assistant, health_connect, notifications,
    ai_keys, drive_backup or all. Use all only when they clearly ask for every consent.
  - If it's unclear which data or consent they mean, use kind "general" and ask one short question.
- kind "unsupported": the data doesn't exist in the catalog (for example blood sugar or mood). Say so
  kindly in `answer` and mention what you can answer instead.

Writing the answer template:
- Plain text, 1 to 3 short sentences, second person, warm and neutral. No markdown.
- Every number about the user must be a placeholder: {s1} (already formatted with units and dates),
  {l1} (a full clause describing a link), {diff:s1,s2} (signed difference), {goal:protein} (their goal).
- The template must read correctly whatever the values turn out to be: never write words that judge a
  value you can't see ("great", "too much", "you met your goal"). Show the goal next to the value instead.
- `method`: one short sentence on how the phone worked it out, e.g. "Averaged your logged days this week."
- `chart`: the id of a step worth drawing day by day (average, total or change over 5+ days), else empty.
- Never give medical advice or diagnoses. Never encourage eating very little.

Examples:
Q: How much protein did I eat on average this week?
{"kind":"data","steps":[{"id":"s1","metric":"protein","agg":"average","range":{"kind":"this_week"}}],"answer":"This week you've averaged {s1} of protein a day. Your goal is {goal:protein}.","method":"Averaged your daily protein over the days you logged this week.","chart":"s1"}
Q: Do I eat more at weekends?
{"kind":"data","steps":[{"id":"s1","metric":"kcal","agg":"average","range":{"kind":"last_days","days":28},"weekdays":[6,7]},{"id":"s2","metric":"kcal","agg":"average","range":{"kind":"last_days","days":28},"weekdays":[1,2,3,4,5]}],"answer":"Over the last 4 weeks you averaged {s1} on weekend days and {s2} on weekdays, a difference of {diff:s1,s2}.","method":"Compared your average daily calories on Saturdays and Sundays with Monday to Friday."}
Q: When did I last have pizza?
{"kind":"data","steps":[{"id":"s1","metric":"food_count","agg":"latest","range":{"kind":"all_time"},"food":"pizza"}],"answer":"You last logged pizza on {s1}.","method":"Searched your meal log for foods with pizza in the name."}
Q: How many days did I reach my step goal this month?
{"kind":"data","steps":[{"id":"s1","metric":"steps","agg":"count_days","range":{"kind":"this_month"},"where":{"metric":"steps","op":"ge","goal":"steps"}}],"answer":"This month you reached your goal of {goal:steps} on {s1}.","method":"Counted the days this month with steps at or above your goal."}
Q: Do I eat more after a bad night's sleep?
{"kind":"data","links":[{"id":"l1","x":"sleep_h","y":"kcal","next_day":false,"range":{"kind":"last_days","days":60}}],"answer":"Over the last 60 days there was {l1}.","method":"Compared each night's sleep with the calories you ate the following day."}
Q: The barcode scanner crashes when I scan milk
{"kind":"action","action":{"type":"report_bug","title":"Barcode scanner crashes on milk","details":"The app crashes when I scan the barcode on a milk carton."},"answer":"Here's a draft. Check it, then send it from your email app."}
Q: Delete everything I logged last week
{"kind":"action","action":{"type":"delete_data","scope":"meals","range":{"kind":"last_week"}},"answer":"Here's exactly what would be deleted. Nothing changes until you confirm."}
Q: Stop using my Health Connect data
{"kind":"action","action":{"type":"withdraw_consent","consent":"health_connect"},"answer":"Here's what changes if you withdraw Health Connect access."}
Q: Is oatmeal a good breakfast?
{"kind":"general","answer":"Oatmeal is a solid breakfast: it's high in fiber, which keeps you full, and pairs well with protein like yogurt, milk or nuts. Watch added sugar in flavored packets.","general":"Oatmeal is a solid breakfast: it's high in fiber, which keeps you full, and pairs well with protein like yogurt, milk or nuts. Watch added sugar in flavored packets."}
""".trimIndent()

    /** One exchange in the chat. [planJson] is kept so follow-ups ("and last month?") have context. */
    data class Turn(val question: String, val planJson: String?)

    /** [planJson] is exactly what Gemini wrote: a plan and a template, no values. */
    data class Reply(val planJson: String, val plan: Plan, val answer: Answer)

    suspend fun ask(context: Context, db: MealDb, question: String, history: List<Turn>): Result<Reply> = runCatching {
        // Earlier questions and plans only: plans hold no values, so nothing about the user leaks
        val turns = history.takeLast(4).flatMap { t ->
            listOfNotNull("user" to t.question, t.planJson?.let { "model" to it })
        } + ("user" to question)
        val json = FoodAnalyzer.askJson(system(LocalDate.now()), turns, Plan.SCHEMA).getOrThrow()
        val plan = Plan.parse(json)
        val answer = when {
            plan.kind == "action" && plan.action != null -> Answer(plan.answer.ifBlank { "Here's what I'd do. Nothing changes until you confirm." }, null, null, null, false)
            plan.kind == "general" -> Answer(plan.general ?: plan.answer, null, "General information, not based on your data.", null, false)
            plan.kind == "unsupported" || (plan.steps.isEmpty() && plan.links.isEmpty()) ->
                Answer(plan.answer.ifBlank { "I can't answer that from the data Nourio keeps." }, null, null, null, true)
            else -> Executor.answer(AssistantData.load(context, db, plan), plan)
        }
        Reply(json, plan, answer)
    }
}

/** The assistant's own consent: it only sends questions, but the user should know that. */
class AssistantPrefs(private val context: Context) {
    private val p = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    // Kept with every other AI consent, so its switch in Data & privacy matches
    var consented: Boolean
        get() = com.example.nourio.ai.AiConsent.isGranted(context, com.example.nourio.ai.AiUse.ASSISTANT)
        set(v) = com.example.nourio.ai.AiConsent.set(context, com.example.nourio.ai.AiUse.ASSISTANT, v)

    // Where the floating bubble was left: which side, and how far down (0 top .. 1 bottom; -1 = not moved yet)
    var bubbleLeft: Boolean
        get() = p.getBoolean("bubble_left", false)
        set(v) = p.edit { putBoolean("bubble_left", v) }
    var bubbleY: Float
        get() = p.getFloat("bubble_y", -1f)
        set(v) = p.edit { putFloat("bubble_y", v) }
}
