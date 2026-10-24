package com.example.nourio.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject

/** A model from a service's list. [images]: true reads photos, false text only, null not known. */
data class ModelInfo(val id: String, val images: Boolean?)

/**
 * What each model can do, so photos only go to models that read images and nothing goes to models
 * that can't chat (embeddings, speech, image generation).
 *
 * Where the service says so in its model list (OpenRouter, Mistral) that answer is used. Otherwise the
 * model's name decides. A model that rejects a photo is remembered as text only.
 */
object ModelCaps {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("model_caps", Context.MODE_PRIVATE)
    }

    // Models for something other than chat: never offered, never sent anything
    private val NOT_CHAT = listOf(
        "embed", "tts", "whisper", "transcribe", "dall-e", "gpt-image", "imagen", "moderation", "rerank",
        "realtime", "audio", "speech", "guard", "ocr", "babbage", "davinci", "-image-", "image-generation",
        "veo", "lyria", "sora", "computer-use", "robotics", "aqa",
    )

    fun isChat(id: String): Boolean {
        val n = id.lowercase()
        return NOT_CHAT.none { it in n }
    }

    /** Words that mark a model as reading images, whatever the service. */
    private val VISION_WORDS = listOf("vision", "-vl", "vl-", "pixtral", "omni", "qvq", "multimodal")

    /** From the name alone. */
    internal fun imagesByName(p: Provider, id: String): Boolean? {
        val n = id.lowercase()
        if (!p.photos) return false
        if (VISION_WORDS.any { it in n }) return true
        return when (p) {
            Provider.GEMINI -> n.startsWith("gemini-") || n.startsWith("gemma-3")
            Provider.CLAUDE -> n.startsWith("claude-")      // every current Claude model reads images
            Provider.OPENAI -> when {
                n.startsWith("gpt-3.5") || n.startsWith("o1-mini") || n.startsWith("o3-mini") -> false
                n.startsWith("gpt-4o") || n.startsWith("chatgpt-4o") || n.startsWith("gpt-4.1") ||
                    n.startsWith("gpt-4-turbo") || n.startsWith("gpt-5") || n.startsWith("o1") ||
                    n.startsWith("o3") || n.startsWith("o4") -> true
                else -> null
            }
            Provider.GROK -> when {
                n.startsWith("grok-4") -> true
                n.startsWith("grok-3") || "code" in n -> false
                else -> null
            }
            Provider.GROQ -> when {
                "llama-4" in n -> true
                else -> false                                  // the rest of Groq's list is text only
            }
            Provider.MISTRAL -> when {
                n.startsWith("mistral-medium") || n.startsWith("mistral-small") || n.startsWith("mistral-large-3") -> true
                "codestral" in n || "devstral" in n || n.startsWith("open-mistral") || n.startsWith("ministral") -> false
                else -> null
            }
            Provider.KIMI -> when {
                n.startsWith("kimi-latest") || n.startsWith("kimi-k2.5") || n.startsWith("kimi-k3") -> true
                n.startsWith("moonshot-v1") || n.startsWith("kimi-k2-") -> false
                else -> null
            }
            Provider.QWEN -> when {
                n.startsWith("qwen3.5") || n.startsWith("qwen3.6") -> true
                n.startsWith("qwen-max") || n.startsWith("qwen-plus") || n.startsWith("qwen-turbo") || "coder" in n -> false
                else -> null
            }
            Provider.DEEPSEEK -> false
            Provider.OPENROUTER -> null                        // comes from OpenRouter's own list
        }
    }

    /**
     * One entry of a service's /models list. OpenRouter lists input modalities; Mistral lists
     * capabilities.vision. Returns null for a model that can't chat.
     */
    internal fun fromListing(p: Provider, m: JSONObject): ModelInfo? {
        val id = m.optString("id").ifBlank { return null }
        if (!isChat(id)) return null
        val listed: Boolean? = when {
            m.optJSONObject("architecture")?.optJSONArray("input_modalities") != null -> {
                val arch = m.getJSONObject("architecture")
                val inputs = arch.getJSONArray("input_modalities").let { a -> (0 until a.length()).map { a.optString(it) } }
                val outputs = arch.optJSONArray("output_modalities")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                if ("text" !in inputs || (outputs != null && "text" !in outputs)) return null
                "image" in inputs
            }
            m.optJSONObject("capabilities")?.has("vision") == true -> {
                val caps = m.getJSONObject("capabilities")
                if (caps.has("completion_chat") && !caps.optBoolean("completion_chat")) return null
                caps.optBoolean("vision")
            }
            else -> null
        }
        return ModelInfo(id, listed ?: imagesByName(p, id))
    }

    /** Saves what the model list said, for picking a photo model later. */
    fun remember(context: Context, p: Provider, models: List<ModelInfo>) {
        init(context)
        val json = JSONObject()
        models.forEach { m -> m.images?.let { json.put(m.id, it) } }
        prefs!!.edit { putString("list_${p.id}", json.toString()) }
    }

    private fun saved(p: Provider): JSONObject =
        prefs?.getString("list_${p.id}", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    private fun learnedTextOnly(p: Provider): Set<String> = prefs?.getStringSet("text_only_${p.id}", null) ?: emptySet()

    /** A photo was rejected by this model: never send it photos again. */
    fun markTextOnly(p: Provider, id: String) {
        val p0 = prefs ?: return
        p0.edit { putStringSet("text_only_${p.id}", learnedTextOnly(p) + id) }
    }

    /** True reads photos, false doesn't, null not known. */
    fun readsImages(p: Provider, id: String): Boolean? {
        if (id in learnedTextOnly(p)) return false
        val list = saved(p)
        if (list.has(id)) return list.optBoolean(id)
        return imagesByName(p, id)
    }

    /**
     * The model a photo goes to: the chosen one when it reads images, otherwise the first model in the
     * service's list known to read them. Null when there is none, and then no photo is sent.
     */
    fun photoModel(p: Provider, chosen: String?): String? {
        if (!p.photos) return null
        // The chosen model is tried unless it's known to be text-only: when support is unknown, a refusal
        // teaches us (markTextOnly), which is better than blocking photos for it
        if (chosen != null && readsImages(p, chosen) != false) return chosen
        // Otherwise only the app's own suggested models, never an arbitrary one from the account's list,
        // which could be a different or paid model the person didn't pick
        return p.suggested.firstOrNull { it != chosen && readsImages(p, it) == true && it !in learnedTextOnly(p) }
    }

    /** Status line for the model picker. */
    fun label(p: Provider, id: String): String = when (readsImages(p, id)) {
        true -> "Photos and text"
        false -> "Text only"
        null -> "Text (photo support unknown)"
    }

    internal fun resetForTests() { prefs = null }
}
