package com.example.nourio.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One request in the app's own terms; [Llm] turns it into each provider's format. */
data class LlmRequest(
    val system: String?,
    val turns: List<LlmTurn>,
    val schema: JSONObject? = null,     // Gemini-style schema (OBJECT/STRING...) for a JSON reply; null = plain text
    val temperature: Double? = null,
    val maxTokens: Int = 4096,
    // Lets the model look things up on the web: Google Search with Gemini, web search with Claude.
    // Other services answer from what their model already knows. A JSON reply is then read from the text.
    val webSearch: Boolean = false,
)

/** [role] is "user" or "model". [imageJpegBase64] attaches a photo to a user turn. */
data class LlmTurn(val role: String, val text: String, val imageJpegBase64: String? = null)

/**
 * Sends a request to the provider the user chose and returns the reply text (JSON when a schema is
 * given). Consent is checked by the callers in [FoodAnalyzer] before anything reaches here.
 */
object Llm {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun complete(req: LlmRequest): String {
        val provider = AiSettings.provider
        val photo = req.turns.any { it.imageJpegBase64 != null }
        if (photo && !provider.photos) {
            error("${provider.label} can't read photos. Describe the meal in words, or choose another AI service in Profile > Connections.")
        }
        if (provider == Provider.GEMINI) return finish(req, gemini(req))   // the router picks models itself

        val chosen = AiSettings.model(provider) ?: error("Choose a ${provider.label} model in Profile > Connections.")
        // A photo goes only to a model that reads images; if a model turns out not to, remember it and try the next
        repeat(3) {
            val model = if (photo) photoModelOrThrow(provider, chosen) else chosen
            if (photo && model != chosen) AiProgress.event("$chosen reads text only, so the photo goes to $model")
            if (!photo && !ModelCaps.isChat(model)) error("$model can't answer chat requests. Choose another model in Profile > Connections.")
            val key = AiSettings.key(provider) ?: error("No ${provider.label} key yet. Add yours in Profile > Connections.")
            AiProgress.trying(model, "…${key.takeLast(4)}")
            try {
                val out = if (provider == Provider.CLAUDE) claude(req, key, model) else openAiCompatible(provider, req, key, model)
                return finish(req, out)
            } catch (e: ImageRejected) {
                ModelCaps.markTextOnly(provider, model)
                AiProgress.event("$model doesn't read photos, trying another model")
            }
        }
        error("None of your ${provider.label} models could read the photo. Pick one marked \"Photos and text\" in Profile > Connections.")
    }

    private fun finish(req: LlmRequest, out: String) = if (req.webSearch && req.schema != null) extractJson(out) else out

    private fun photoModelOrThrow(p: Provider, chosen: String): String =
        ModelCaps.photoModel(p, chosen) ?: error(
            "$chosen can't read photos, and none of your ${p.label} models are known to. " +
                "Tap Load models in Profile > Connections and pick one marked \"Photos and text\", or describe the meal in words."
        )

    /** The model said it can't take images. */
    private class ImageRejected(message: String) : Exception(message)

    /** A 400 or 404 that is about the image itself, not the rest of the request. */
    internal fun isImageRejection(code: Int, body: String): Boolean {
        if (code != 400 && code != 404 && code != 422) return false
        val b = body.lowercase()
        return listOf("image", "vision", "multimodal", "modalit", "image_url").any { it in b } &&
            listOf("support", "not ", "invalid", "unsupported", "cannot", "can't", "does not").any { it in b }
    }

    // ------------------------------------------------------------ Gemini (unchanged routing)

    internal fun geminiBody(req: LlmRequest): JSONObject {
        val contents = JSONArray()
        req.turns.forEach { t ->
            val parts = JSONArray().put(JSONObject().put("text", t.text))
            t.imageJpegBase64?.let {
                parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", it)))
            }
            contents.put(JSONObject().put("role", t.role).put("parts", parts))
        }
        val config = JSONObject()
        req.temperature?.let { config.put("temperature", it) }
        // Search grounding can't be combined with a response schema, so a searching request gets JSON from the prompt
        if (req.schema != null && !req.webSearch) config.put("responseMimeType", "application/json").put("responseSchema", req.schema)
        else config.put("maxOutputTokens", req.maxTokens)
        return JSONObject().apply {
            req.system?.let { put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", it)))) }
            put("contents", contents)
            put("generationConfig", config)
            if (req.webSearch) put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        }
    }

    private suspend fun gemini(req: LlmRequest): String {
        val raw = GeminiRouter.generate(geminiBody(req).toString())
        val parts = JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        return buildString { for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text", "")) }.trim()
    }

    // ------------------------------------------------------------ Claude (Anthropic SDK)

    @Volatile private var claudeClient: Pair<String, AnthropicClient>? = null

    private fun claudeClient(key: String): AnthropicClient =
        claudeClient?.takeIf { it.first == key }?.second
            ?: AnthropicOkHttpClient.builder().apiKey(key).build().also { claudeClient = key to it }

    /** Gemini-style schema to JSON Schema: lower-case types, closed objects. */
    internal fun toJsonSchema(g: JSONObject): JSONObject {
        val type = g.optString("type").lowercase()
        val out = JSONObject().put("type", type)
        g.optJSONArray("enum")?.let { out.put("enum", it) }
        when (type) {
            "object" -> {
                val props = g.optJSONObject("properties") ?: JSONObject()
                val outProps = JSONObject()
                props.keys().forEach { k -> outProps.put(k, toJsonSchema(props.getJSONObject(k))) }
                out.put("properties", outProps).put("additionalProperties", false)
                g.optJSONArray("required")?.let { out.put("required", it) }
            }
            "array" -> g.optJSONObject("items")?.let { out.put("items", toJsonSchema(it)) }
        }
        return out
    }

    private fun toJava(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { toJava(v.get(it)) }
        is JSONArray -> (0 until v.length()).map { toJava(v.get(it)) }
        JSONObject.NULL -> null
        else -> v
    }

    // The two current models where the server-side refusal fallback is on by default
    private val FALLBACK_MODELS = setOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1")

    private suspend fun claude(req: LlmRequest, key: String, model: String): String {
        val b = MessageCreateParams.builder().model(model).maxTokens(16000L)
        req.system?.let { b.system(it) }
        req.turns.forEach { t ->
            if (t.role == "model") b.addAssistantMessage(t.text)
            else if (t.imageJpegBase64 == null) b.addUserMessage(t.text)
            else b.addUserMessageOfBetaContentBlockParams(
                listOf(
                    BetaContentBlockParam.ofImage(
                        BetaImageBlockParam.builder().source(
                            BetaBase64ImageSource.builder().data(t.imageJpegBase64)
                                .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG).build()
                        ).build()
                    ),
                    BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(t.text).build()),
                )
            )
        }
        // Web search results carry citations, which structured output doesn't allow: JSON then comes from the prompt
        if (req.webSearch) {
            b.putAdditionalBodyProperty("tools", JsonValue.from(listOf(mapOf("type" to "web_search_20260209", "name" to "web_search", "max_uses" to 10))))
        } else req.schema?.let { s ->
            @Suppress("UNCHECKED_CAST")
            val schema = toJava(toJsonSchema(s)) as Map<String, Any?>
            b.outputConfig(
                BetaOutputConfig.builder().format(
                    BetaJsonOutputFormat.builder().schema(
                        BetaJsonOutputFormat.Schema.builder().apply { schema.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()
                    ).build()
                ).build()
            )
        }
        // A declined request falls back to another model on the server instead of failing
        if (model in FALLBACK_MODELS) {
            b.addBeta("server-side-fallback-2026-07-01")
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        // Interruptible, so Cancel on screen stops the request (the async client would add ~28 MB to the app)
        val msg = kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
            claudeClient(key).beta().messages().create(b.build())
        }
        if (msg.stopReason().map { it.toString() }.orElse("") == "refusal") {
            error("Claude declined this request. Try rewording it.")
        }
        if (msg.stopReason().map { it.toString() }.orElse("") == "pause_turn") {
            error("Claude's web search took too long. Try again later.")
        }
        return msg.content().joinToString("") { block -> block.text().map { it.text() }.orElse("") }.trim()
    }

    // ------------------------------------------------------------ OpenAI-compatible (ChatGPT, Grok, DeepSeek, ...)

    internal fun openAiBody(model: String, req: LlmRequest): JSONObject {
        val messages = JSONArray()
        val system = listOfNotNull(
            req.system,
            req.schema?.let {
                "Reply with one JSON object only, no other text, matching this JSON schema:\n" + toJsonSchema(it).toString()
            }
        ).joinToString("\n\n")
        if (system.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", system))
        req.turns.forEach { t ->
            val role = if (t.role == "model") "assistant" else "user"
            val content: Any = if (t.imageJpegBase64 == null) t.text else JSONArray()
                .put(JSONObject().put("type", "text").put("text", t.text))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,${t.imageJpegBase64}")))
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        return JSONObject().apply {
            put("model", model)
            put("messages", messages)
            req.temperature?.let { put("temperature", it) }
            if (req.schema != null) put("response_format", JSONObject().put("type", "json_object"))
        }
    }

    /** The reply's JSON object, even when a model wraps it in a code fence or a sentence. */
    internal fun extractJson(text: String): String {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else text
    }

    private suspend fun openAiCompatible(p: Provider, req: LlmRequest, key: String, model: String): String {
        val photo = req.turns.any { it.imageJpegBase64 != null }
        val request = Request.Builder().url("${p.baseUrl}/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(openAiBody(model, req).toString().toRequestBody("application/json".toMediaType()))
            .build()
        val body = try {
            http.newCall(request).await().use { r ->
                val text = r.body.string()
                if (!r.isSuccessful && photo && isImageRejection(r.code, text)) throw ImageRejected(text.take(200))
                if (!r.isSuccessful) {
                    val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
                    error(when (r.code) {
                        401, 403 -> "${p.label} rejected the key. Check it in Profile > Connections."
                        429 -> "${p.label} is rate-limiting this key, or it's out of credit. Try again in a minute."
                        else -> "${p.label} error ${r.code}${msg?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}"
                    })
                }
                text
            }
        } catch (e: java.io.InterruptedIOException) {
            throw IOException("${p.label} took too long to answer ($model). Try again, or pick a faster model in Profile > Connections.", e)
        } catch (e: IOException) {
            throw IOException("No connection to ${p.label}. Check your internet and try again.", e)
        }
        val content = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        return if (req.schema != null) extractJson(content) else content.trim()
    }

    /** The models a key can use, for the model picker. Throws with a readable message on a bad key. */
    fun listModels(p: Provider, key: String): List<ModelInfo> {
        if (p == Provider.CLAUDE) {
            return AnthropicOkHttpClient.builder().apiKey(key).build().models().list().autoPager().map { it.id() }.toList()
                .filter { ModelCaps.isChat(it) }.map { ModelInfo(it, ModelCaps.imagesByName(p, it)) }
        }
        val request = Request.Builder().url("${p.baseUrl}/models").header("Authorization", "Bearer $key").build()
        return http.newCall(request).execute().use { r ->
            if (r.code == 401 || r.code == 403) error("That key wasn't accepted by ${p.label}.")
            if (!r.isSuccessful) error("${p.label} error ${r.code}")
            val data = JSONObject(r.body.string()).optJSONArray("data") ?: JSONArray()
            (0 until data.length()).mapNotNull { ModelCaps.fromListing(p, data.getJSONObject(it)) }.sortedBy { it.id }
        }
    }
}
