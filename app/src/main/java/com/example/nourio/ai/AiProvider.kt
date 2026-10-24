package com.example.nourio.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The AI services the app can use, each with the user's own API key. Gemini keeps its own key list and
 * automatic model choice ([GeminiKeys], [GeminiRouter]); Claude goes through the Anthropic SDK; the
 * others share the OpenAI-style chat completions format at their own address.
 */
enum class Provider(
    val id: String,
    val label: String,          // what people call it
    val company: String,        // who receives the data
    val keyUrl: String,
    val keyHint: String,
    val baseUrl: String?,       // OpenAI-compatible endpoint; null for Gemini and Claude
    val photos: Boolean,        // false when the provider's API can't read images at all
    val suggested: List<String>,
    val note: String,
) {
    GEMINI(
        "gemini", "Google Gemini", "Google", "https://aistudio.google.com/apikey", "Gemini keys usually start with AIza.",
        null, true, emptyList(),
        "Free key. Nourio picks the best free model and switches keys and models automatically when one is busy."
    ),
    GROQ(
        "groq", "Groq", "Groq", "https://console.groq.com/keys", "Groq keys start with gsk_.",
        "https://api.groq.com/openai/v1", true, emptyList(),
        "Free key, no card needed, and very fast. Fewer models; for meal photos, pick one that accepts images."
    ),
    CLAUDE(
        "claude", "Claude", "Anthropic", "https://console.anthropic.com/settings/keys", "Claude keys start with sk-ant-.",
        null, true, listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"),
        "Paid API. Opus 5.5 is the most capable; Sonnet 5.5 and Haiku 5.5 are faster and cost less."
    ),
    OPENAI(
        "openai", "ChatGPT", "OpenAI", "https://platform.openai.com/api-keys", "OpenAI keys start with sk-.",
        "https://api.openai.com/v1", true, emptyList(),
        "Paid API. Load the models your key can use and pick one; choose one that accepts images for meal photos."
    ),
    GROK(
        "grok", "Grok", "xAI", "https://console.x.ai", "xAI keys start with xai-.",
        "https://api.x.ai/v1", true, emptyList(),
        "Paid API. For meal photos, pick a Grok model that accepts images."
    ),
    DEEPSEEK(
        "deepseek", "DeepSeek", "DeepSeek", "https://platform.deepseek.com/api_keys", "DeepSeek keys start with sk-.",
        "https://api.deepseek.com/v1", false, listOf("deepseek-chat", "deepseek-reasoner"),
        "Low-cost API for text only: meal descriptions, the coach and the assistant work; meal photos don't."
    ),
    KIMI(
        "kimi", "Kimi", "Moonshot AI", "https://platform.moonshot.ai/console/api-keys", "Moonshot keys start with sk-.",
        "https://api.moonshot.ai/v1", true, emptyList(),
        "Paid API. For meal photos, pick a Kimi model with vision."
    ),
    MISTRAL(
        "mistral", "Mistral", "Mistral AI", "https://console.mistral.ai/api-keys", "Paste the key from your Mistral console.",
        "https://api.mistral.ai/v1", true, emptyList(),
        "Free tier available (Mistral may then use your requests for training). For meal photos, pick a Pixtral or other vision model."
    ),
    QWEN(
        "qwen", "Qwen", "Alibaba Cloud", "https://bailian.console.alibabacloud.com/?apiKey=1", "DashScope keys start with sk-.",
        "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", true, emptyList(),
        "Paid API. For meal photos, pick a Qwen VL (vision) model."
    ),
    OPENROUTER(
        "openrouter", "OpenRouter", "OpenRouter", "https://openrouter.ai/keys", "OpenRouter keys start with sk-or-.",
        "https://openrouter.ai/api/v1", true, emptyList(),
        "One key for hundreds of models; those ending in :free cost nothing (about 50 requests a day). Requests pass through OpenRouter."
    );

    val isOpenAiCompatible get() = baseUrl != null

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: GEMINI
    }
}

/** Which provider is in use, its model, and its key (encrypted on the phone). */
object AiSettings {
    private var prefs: SharedPreferences? = null
    private val _available = MutableStateFlow(false)

    /** True when the chosen provider has a key (and, outside Gemini, a model). The UI watches this. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("ai_provider", Context.MODE_PRIVATE)
        ModelCaps.init(context)
        refresh()
    }

    private fun p() = prefs

    val provider: Provider get() = Provider.of(p()?.getString("provider", null))

    fun key(provider: Provider = this.provider): String? =
        p()?.getString("key_${provider.id}", null)?.let { Crypto.decrypt(it) }?.takeIf { it.isNotBlank() }

    fun model(provider: Provider = this.provider): String? =
        p()?.getString("model_${provider.id}", null)?.takeIf { it.isNotBlank() } ?: provider.suggested.firstOrNull()

    /** Switching company withdraws every AI consent: the data would now go somewhere else. */
    fun choose(context: Context, provider: Provider) {
        init(context)
        if (provider == this.provider) return
        p()!!.edit { putString("provider", provider.id) }
        AiConsent.setAll(context, false)   // "always allow" was for the old company, so it goes too
        refresh()
    }

    fun setKey(context: Context, provider: Provider, key: String?) {
        init(context)
        p()!!.edit {
            if (key.isNullOrBlank()) remove("key_${provider.id}") else putString("key_${provider.id}", Crypto.encrypt(key.trim()))
        }
        refresh()
    }

    fun setModel(context: Context, provider: Provider, model: String) {
        init(context)
        p()!!.edit { putString("model_${provider.id}", model.trim()) }
        refresh()
    }

    /** Ready to send: a key (and a model) for the chosen provider. */
    fun isReady(): Boolean = when (provider) {
        Provider.GEMINI -> GeminiKeys.hasAny()
        else -> key() != null && model() != null
    }

    /** Photos need a provider whose API reads images, and a model of it that does. */
    fun photosSupported(): Boolean = when {
        !provider.photos -> false
        provider == Provider.GEMINI -> true
        else -> ModelCaps.photoModel(provider, model()) != null       // unknown support counts as yes, see photoModel
    }

    fun refresh() { _available.value = isReady() }

    /** Removes every saved key for every provider (used when consents are withdrawn). */
    fun removeAllKeys(context: Context) {
        init(context)
        p()!!.edit { Provider.entries.forEach { remove("key_${it.id}") } }
        refresh()
    }
}
