package com.example.nourio.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiRouterTest {
    @Test
    fun ranksStableFlashNewestFirst() {
        val catalog = listOf(
            "gemini-2.0-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite", "gemini-2.5-flash",
            "gemini-2.5-flash-image", "gemini-2.5-flash-preview-tts", "gemini-flash-latest",
            "gemini-3.0-flash-preview", "gemini-2.0-flash-001", "text-embedding-004", "gemini-2.5-flash"
        )
        assertEquals(
            listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash", "gemini-2.0-flash-001"),
            GeminiRouter.rank(catalog)
        )
    }

    @Test
    fun previewsComeAfterStableModels() {
        assertEquals(
            listOf("gemini-2.5-flash", "gemini-3.0-flash-preview"),
            GeminiRouter.rank(listOf("gemini-3.0-flash-preview", "gemini-2.5-flash"))
        )
    }

    @Test
    fun keepsFlashLiteAsLastFallback() {
        // The live catalog on 14 Oct 2026: four stable Flash versions would otherwise crowd Lite out
        val catalog = listOf(
            "gemini-3.5-flash", "gemini-3.6-flash", "gemini-3.7-flash", "gemini-3.8-flash",
            "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3-flash-preview", "gemini-3.8-flash-tts"
        )
        assertEquals(
            listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite"),
            GeminiRouter.rank(catalog)
        )
    }

    @Test
    fun nothingUsableGivesEmptyList() {
        assertEquals(emptyList<String>(), GeminiRouter.rank(listOf("gemini-2.5-pro", "gemini-2.5-flash-image")))
    }
}
