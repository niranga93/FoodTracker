package com.example.nourio.ai

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiConsentTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @org.junit.Before fun fresh() = AiConsent.resetForTests()

    @Test
    fun everyUseStartsOff() {
        AiUse.entries.forEach { assertFalse(it.name, AiConsent.isGranted(ctx, it)) }
    }

    @Test
    fun nothingIsSentWithoutConsent() = runBlocking {
        AiConsent.init(ctx)
        // Each request fails with AiConsentRequired before any network call is made
        val results = listOf(
            AiUse.TEXT to FoodAnalyzer.analyzeText("two eggs and toast"),
            AiUse.COACH to FoodAnalyzer.askText("system", "user"),
            AiUse.ASSISTANT to FoodAnalyzer.askJson("system", listOf("user" to "hi"), JSONObject()),
        )
        results.forEach { (use, r) ->
            val e = r.exceptionOrNull()
            assertTrue("$use: $e", e is AiConsentRequired && e.use == use)
        }
    }

    @Test
    fun consentCanBeGivenAndWithdrawn() {
        AiConsent.set(ctx, AiUse.PHOTO, true)
        assertTrue(AiConsent.isGranted(ctx, AiUse.PHOTO))
        AiConsent.set(ctx, AiUse.PHOTO, false)
        assertFalse(AiConsent.isGranted(ctx, AiUse.PHOTO))
    }

    @Test
    fun alwaysAllowCoversEveryUse() {
        AiConsent.setAll(ctx, true)
        AiUse.entries.forEach { assertTrue(it.name, AiConsent.isGranted(ctx, it)) }
    }

    @Test
    fun turningAlwaysAllowOffWithdrawsEverything() {
        AiConsent.set(ctx, AiUse.PHOTO, true)
        AiConsent.setAll(ctx, true)
        AiConsent.setAll(ctx, false)
        AiUse.entries.forEach { assertFalse(it.name, AiConsent.isGranted(ctx, it)) }
    }

    @Test
    fun switchingOneOffKeepsTheOthers() {
        AiConsent.setAll(ctx, true)
        AiConsent.set(ctx, AiUse.COACH, false)
        assertFalse(AiConsent.isAllGranted(ctx))
        assertFalse(AiConsent.isGranted(ctx, AiUse.COACH))
        AiUse.entries.filter { it != AiUse.COACH }.forEach { assertTrue(it.name, AiConsent.isGranted(ctx, it)) }
    }
}

/** A fresh process, so the one-time read of the old coach consent happens with it already set. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiConsentMigrationTest {
    @org.junit.After fun clear() = AiConsent.resetForTests()

    @Test
    fun earlierCoachConsentIsKept() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("coach", Context.MODE_PRIVATE).edit { putBoolean("consent", true) }
        AiConsent.resetForTests()
        assertTrue(AiConsent.isGranted(ctx, AiUse.COACH))
        assertFalse(AiConsent.isGranted(ctx, AiUse.PHOTO))
    }
}
