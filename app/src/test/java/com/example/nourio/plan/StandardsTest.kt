package com.example.nourio.plan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.nourio.ai.AiConsent
import com.example.nourio.ai.AiConsentRequired
import com.example.nourio.plan.Guidelines.Person
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StandardsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before @After fun reset() {
        Standards.resetForTests()
        ctx.getSharedPreferences("standards", Context.MODE_PRIVATE).edit().clear().commit()
        AiConsent.resetForTests()
    }

    private fun p(key: String, vararg v: Double, url: String = "https://www.who.int/x") =
        Proposal(key, v.toList(), "WHO guideline", url, "2026-09", "Reduce sodium to below 2 g a day.", "Lower limit.")

    /** What an AI service might answer, good and bad suggestions mixed. */
    private val reply = """
        Here is what I found:
        {"changes":[
          {"key":"sodium_mg","proposed":[2000],"source_title":"WHO","source_url":"https://www.who.int/s","published":"2026-09","quote":"q","reason":"r"},
          {"key":"sodium_mg","proposed":[9000],"source_title":"Blog","source_url":"https://x","published":"","quote":"","reason":""},
          {"key":"fat_share","proposed":[30],"source_title":"WHO","source_url":"https://www.who.int/f","published":"","quote":"","reason":"percent, not a share"},
          {"key":"made_up","proposed":[1],"source_title":"?","source_url":"https://x","published":"","quote":"","reason":""},
          {"key":"amdr_fat","proposed":[35,20],"source_title":"DRI","source_url":"https://nap.edu","published":"","quote":"","reason":""},
          {"key":"fiber_min_g","proposed":[25],"source_title":"DRI","source_url":"http://insecure","published":"","quote":"","reason":""},
          {"key":"sleep_goal_h","proposed":[7],"source_title":"AASM","source_url":"https://aasm.org","published":"","quote":"","reason":"same as now"}
        ]}
    """.trimIndent()

    @Test fun onlySensibleSourcedChangesAreShown() {
        val shown = Standards.addPending(ctx, StandardsChecker.parse(com.example.nourio.ai.Llm.extractJson(reply)))
        assertEquals(listOf("sodium_mg" to listOf(2000.0)), shown.map { it.key to it.proposed })
        assertEquals(shown, Standards.pending(ctx))
    }

    @Test fun nothingChangesUntilApproved() {
        Standards.addPending(ctx, listOf(p("sodium_mg", 2000.0)))
        assertEquals(2300.0, Guidelines.compute(2000.0, 0.0, Person()).sodiumMg, 0.0)
        assertEquals("Sodium limit", Standards.label("sodium_mg"))
        assertEquals("2,300 mg a day", Standards.currentText("sodium_mg"))
        assertEquals("2,000 mg a day", Standards.proposedText(Standards.pending(ctx).single()))
    }

    @Test fun approvingUpdatesTheStandardAndSurvivesARestart() {
        Standards.addPending(ctx, listOf(p("sodium_mg", 2000.0), p("amdr_fat", 20.0, 30.0)))
        assertTrue(Standards.approve(ctx, Standards.pending(ctx).first { it.key == "sodium_mg" }))
        assertEquals(2000.0, Guidelines.compute(2000.0, 0.0, Person()).sodiumMg, 0.0)
        assertEquals(listOf("amdr_fat"), Standards.pending(ctx).map { it.key })   // the other still waits

        Standards.resetForTests(); Standards.init(ctx)
        assertEquals(2000.0, Standards.current.sodiumMg, 0.0)
        assertEquals(1, Standards.history(ctx).size)
    }

    @Test fun rejectingKeepsTheValueAndIsNotAskedAgain() {
        Standards.addPending(ctx, listOf(p("sodium_mg", 2000.0)))
        Standards.reject(ctx, Standards.pending(ctx).single())
        assertEquals(2300.0, Standards.current.sodiumMg, 0.0)
        assertTrue(Standards.pending(ctx).isEmpty())
        assertTrue(Standards.addPending(ctx, listOf(p("sodium_mg", 2000.0))).isEmpty())     // same suggestion: hidden
        assertEquals(1, Standards.addPending(ctx, listOf(p("sodium_mg", 1800.0))).size)    // a different one: shown
    }

    @Test fun aChangeThatBreaksAnotherGuidelineIsNotShown() {
        // Fat range 20-27% would exclude the 30% fat limit in use
        assertTrue(Standards.addPending(ctx, listOf(p("amdr_fat", 20.0, 27.0))).isEmpty())
    }

    @Test fun resetGoesBackToBuiltInValues() {
        Standards.addPending(ctx, listOf(p("sodium_mg", 2000.0)))
        Standards.approve(ctx, Standards.pending(ctx).single())
        Standards.resetToBuiltIn(ctx)
        assertEquals(Standards.DEFAULTS, Standards.current)
    }

    @Test fun theCheckNeedsConsent() = runBlocking {
        AiConsent.init(ctx)
        val e = runCatching { StandardsChecker.check(ctx) }.exceptionOrNull()
        assertTrue(e is AiConsentRequired)
        assertFalse(Standards.lastChecked(ctx) > 0)
    }

    @Test fun thePromptListsEveryStandardAndNothingPersonal() {
        val prompt = StandardsChecker.prompt()
        Standards.KEYS.forEach { assertTrue(it, prompt.contains(it)) }
        listOf("weight", "name", "age:").forEach { assertFalse(it, prompt.lowercase().contains("your $it")) }
    }
}
