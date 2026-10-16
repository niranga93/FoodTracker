package com.example.nourio.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric for Android's org.json and SharedPreferences
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LlmTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun fresh() = AiConsent.resetForTests()
    @After fun clear() = AiConsent.resetForTests()

    private val schema = JSONObject()
        .put("type", "OBJECT")
        .put("properties", JSONObject()
            .put("name", JSONObject().put("type", "STRING"))
            .put("level", JSONObject().put("type", "STRING").put("enum", JSONArray(listOf("low", "high"))))
            .put("items", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "NUMBER"))))
        .put("required", JSONArray(listOf("name")))

    @Test
    fun geminiSchemaBecomesJsonSchema() {
        val s = Llm.toJsonSchema(schema)
        assertEquals("object", s.getString("type"))
        assertEquals(false, s.getBoolean("additionalProperties"))
        assertEquals("string", s.getJSONObject("properties").getJSONObject("name").getString("type"))
        assertEquals("high", s.getJSONObject("properties").getJSONObject("level").getJSONArray("enum").getString(1))
        assertEquals("number", s.getJSONObject("properties").getJSONObject("items").getJSONObject("items").getString("type"))
        assertEquals("name", s.getJSONArray("required").getString(0))
    }

    @Test
    fun openAiRequestCarriesSystemSchemaAndPhoto() {
        val body = Llm.openAiBody(
            "some-model",
            LlmRequest("Be precise.", listOf(LlmTurn("user", "What is this?", "QUJD")), schema)
        )
        val messages = body.getJSONArray("messages")
        val system = messages.getJSONObject(0)
        assertEquals("system", system.getString("role"))
        assertTrue(system.getString("content").startsWith("Be precise."))
        assertTrue(system.getString("content").contains("\"additionalProperties\":false"))
        val parts = messages.getJSONObject(1).getJSONArray("content")
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("data:image/jpeg;base64,QUJD", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
    }

    @Test
    fun assistantTurnsKeepTheirRoles() {
        val body = Llm.openAiBody("m", LlmRequest(null, listOf(LlmTurn("user", "a"), LlmTurn("model", "{}"), LlmTurn("user", "b"))))
        val roles = (0 until body.getJSONArray("messages").length()).map { body.getJSONArray("messages").getJSONObject(it).getString("role") }
        assertEquals(listOf("user", "assistant", "user"), roles)
        assertFalse(body.has("response_format"))
    }

    @Test
    fun geminiRequestMatchesTheOldShape() {
        val body = Llm.geminiBody(LlmRequest("sys", listOf(LlmTurn("user", "photo", "QUJD")), schema))
        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals("image/jpeg", parts.getJSONObject(1).getJSONObject("inline_data").getString("mime_type"))
        assertEquals("application/json", body.getJSONObject("generationConfig").getString("responseMimeType"))
        assertEquals("sys", body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test
    fun jsonIsFoundInsideFencesAndProse() {
        assertEquals("{\"a\":1}", Llm.extractJson("Sure! ```json\n{\"a\":1}\n```"))
        assertEquals("{\"a\":{\"b\":2}}", Llm.extractJson("{\"a\":{\"b\":2}}"))
    }

    @Test
    fun switchingProviderWithdrawsEveryConsent() {
        AiSettings.init(ctx)
        AiUse.entries.forEach { AiConsent.set(ctx, it, true) }
        AiSettings.choose(ctx, Provider.CLAUDE)
        AiUse.entries.forEach { assertFalse(it.name, AiConsent.isGranted(ctx, it)) }
        assertEquals(Provider.CLAUDE, AiSettings.provider)
        AiSettings.choose(ctx, Provider.GEMINI)
    }

    @Test
    fun deepSeekCannotTakePhotos() {
        assertFalse(Provider.DEEPSEEK.photos)
        assertTrue(Provider.entries.filter { it != Provider.GEMINI && it != Provider.CLAUDE }.all { it.baseUrl!!.startsWith("https://") })
    }
}
