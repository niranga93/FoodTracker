package com.example.nourio.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Every way the app sends the user's data to an AI service (the one chosen in Connections). Each needs its own
 * consent: asked the first time, and switchable on and off under Profile > Data & privacy.
 */
enum class AiUse(val key: String, val title: String, val sends: String, val purpose: String) {
    PHOTO(
        "photo", "Meal photo analysis",
        "A resized photo of your meal.",
        "The AI names the foods and estimates the portions."
    ),
    TEXT(
        "text", "Meal descriptions",
        "The meal you type, speak or share, and the recipe text from a link you share.",
        "The AI turns it into foods and amounts."
    ),
    COACH(
        "coach", "AI coach",
        "The day's or week's foods, amounts and totals, your goals, water, an estimate of calories burned, and your diet and allergy choices.",
        "The AI writes a short note on your day or week."
    ),
    ASSISTANT(
        "assistant", "Nourio Assistant",
        "Only your question and earlier questions in the chat. Your meals, weight and health data are never sent.",
        "The AI plans how to answer; your phone works out the answer from your data."
    ),
    STANDARDS(
        "standards", "Guideline updates",
        "Only the health guideline values the app uses, such as the 2,300 mg sodium limit, and where each comes from. Nothing about you.",
        "Once a month the AI checks official sources for changed guidelines. You approve or reject each change."
    );

    companion object {
        /** Said with every consent, naming the company that will receive the data. */
        fun common(p: Provider): String =
            "Your name, age, sex, height, weight and Health Connect data are never sent. Requests go straight from your " +
                "phone to ${p.company} with your own API key, under ${p.company}'s terms for API use." +
                when (p) {
                    Provider.GEMINI -> " On Gemini's free tier, Google may use them to improve its products and people may review them."
                    Provider.OPENROUTER -> " OpenRouter passes them on to the company behind the model you choose."
                    else -> ""
                }
    }
}

/** Thrown when something tries to reach an AI service for a use the user hasn't agreed to. */
class AiConsentRequired(val use: AiUse) : IllegalStateException("Consent needed: ${use.title}")

object AiConsent {
    private var prefs: SharedPreferences? = null
    private var legacy: Map<AiUse, Boolean> = emptyMap()

    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        prefs = app.getSharedPreferences("ai_consent", Context.MODE_PRIVATE)
        // The coach and the assistant asked before this store existed: keep what people agreed to
        legacy = mapOf(
            AiUse.COACH to app.getSharedPreferences("coach", Context.MODE_PRIVATE).getBoolean("consent", false),
            AiUse.ASSISTANT to app.getSharedPreferences("assistant", Context.MODE_PRIVATE).getBoolean("consent", false),
        )
    }

    fun isGranted(context: Context, use: AiUse): Boolean {
        init(context)
        return isGranted(use)
    }

    /** For code without a context; false until [init] has run, so nothing is sent by accident. */
    fun isGranted(use: AiUse): Boolean {
        val p = prefs ?: return false
        return p.getBoolean(use.key, legacy[use] ?: false)
    }

    fun set(context: Context, use: AiUse, on: Boolean) {
        init(context)
        prefs!!.edit { putBoolean(use.key, on) }
    }

    /** Tests run many app instances in one process: start each from nothing. */
    internal fun resetForTests() {
        prefs = null
        legacy = emptyMap()
    }

    /** Throws [AiConsentRequired] unless the user agreed to [use]. Called right before every request. */
    fun require(use: AiUse) {
        if (!isGranted(use)) throw AiConsentRequired(use)
    }
}
