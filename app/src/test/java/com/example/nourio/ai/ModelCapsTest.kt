package com.example.nourio.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelCapsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun fresh() {
        ModelCaps.resetForTests()
        ModelCaps.init(ctx)
    }

    @Test
    fun nonChatModelsAreDropped() {
        listOf("text-embedding-3-large", "whisper-1", "tts-1", "dall-e-3", "gpt-image-1", "omni-moderation-latest", "mistral-ocr-latest")
            .forEach { assertFalse(it, ModelCaps.isChat(it)) }
        listOf("gpt-5", "claude-sonnet-5-5", "gemini-3.7-flash", "pixtral-large-latest").forEach { assertTrue(it, ModelCaps.isChat(it)) }
    }

    @Test
    fun namesTellPhotoModels() {
        assertEquals(true, ModelCaps.imagesByName(Provider.CLAUDE, "claude-haiku-5-5"))
        assertEquals(true, ModelCaps.imagesByName(Provider.OPENAI, "gpt-4o-mini"))
        assertEquals(false, ModelCaps.imagesByName(Provider.OPENAI, "gpt-3.5-turbo"))
        assertEquals(true, ModelCaps.imagesByName(Provider.GROQ, "meta-llama/llama-4-scout-17b-16e-instruct"))
        assertEquals(false, ModelCaps.imagesByName(Provider.GROQ, "llama-3.3-70b-versatile"))
        assertEquals(true, ModelCaps.imagesByName(Provider.QWEN, "qwen-vl-max"))
        assertEquals(false, ModelCaps.imagesByName(Provider.DEEPSEEK, "deepseek-chat"))
        assertNull(ModelCaps.imagesByName(Provider.OPENROUTER, "some/model"))
    }

    @Test
    fun openRouterListingDecides() {
        fun m(id: String, inputs: List<String>, outputs: List<String> = listOf("text")) = JSONObject().put("id", id)
            .put("architecture", JSONObject().put("input_modalities", JSONArray(inputs)).put("output_modalities", JSONArray(outputs)))
        assertEquals(true, ModelCaps.fromListing(Provider.OPENROUTER, m("a/vision", listOf("text", "image")))?.images)
        assertEquals(false, ModelCaps.fromListing(Provider.OPENROUTER, m("a/text", listOf("text")))?.images)
        assertNull(ModelCaps.fromListing(Provider.OPENROUTER, m("a/painter", listOf("text"), listOf("image"))))
    }

    @Test
    fun mistralListingDecides() {
        val m = JSONObject().put("id", "mistral-something").put("capabilities", JSONObject().put("completion_chat", true).put("vision", true))
        assertEquals(true, ModelCaps.fromListing(Provider.MISTRAL, m)?.images)
    }

    @Test
    fun aTextOnlyChoiceNeverSendsPhotosToAnArbitraryModel() {
        // x/vision is on the account but wasn't chosen and isn't one of the app's suggestions (it could be paid)
        ModelCaps.remember(ctx, Provider.OPENROUTER, listOf(ModelInfo("x/text", false), ModelInfo("x/vision", true)))
        assertNull(ModelCaps.photoModel(Provider.OPENROUTER, "x/text"))
        assertEquals("x/vision", ModelCaps.photoModel(Provider.OPENROUTER, "x/vision"))
    }

    @Test
    fun aModelWithUnknownPhotoSupportIsTried() {
        // Upgraded users who never loaded the model list keep photos; a refusal teaches the app
        assertEquals("openai/gpt-4o", ModelCaps.photoModel(Provider.OPENROUTER, "openai/gpt-4o"))
    }

    @Test
    fun noVisionModelMeansNoPhotoIsSent() {
        ModelCaps.remember(ctx, Provider.OPENROUTER, listOf(ModelInfo("x/text", false)))
        assertNull(ModelCaps.photoModel(Provider.OPENROUTER, "x/text"))
        assertNull(ModelCaps.photoModel(Provider.DEEPSEEK, "deepseek-chat"))
    }

    @Test
    fun aRejectedPhotoIsRemembered() {
        ModelCaps.remember(ctx, Provider.OPENROUTER, listOf(ModelInfo("x/a", true), ModelInfo("x/b", true)))
        ModelCaps.markTextOnly(Provider.OPENROUTER, "x/a")
        assertEquals(false, ModelCaps.readsImages(Provider.OPENROUTER, "x/a"))
        assertNull(ModelCaps.photoModel(Provider.OPENROUTER, "x/a"))
    }

    @Test
    fun imageRejectionsAreRecognised() {
        assertTrue(Llm.isImageRejection(400, """{"error":{"message":"This model does not support image input"}}"""))
        assertFalse(Llm.isImageRejection(400, """{"error":{"message":"max_tokens is too large"}}"""))
        assertFalse(Llm.isImageRejection(500, "image not supported"))
        // A problem with this photo, not the model: must not mark it text-only for good
        assertFalse(Llm.isImageRejection(400, """{"error":{"message":"Invalid image: image exceeds 20MB"}}"""))
        assertFalse(Llm.isImageRejection(400, """{"error":{"message":"image_url is not a valid base64 string"}}"""))
    }
}
