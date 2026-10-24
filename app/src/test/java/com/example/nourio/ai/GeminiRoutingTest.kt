package com.example.nourio.ai

import com.example.nourio.ai.GeminiRouter.Attempt
import com.example.nourio.ai.GeminiRouter.Failure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The key and model order of [GeminiRouter.route], with a fake Gemini instead of the network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GeminiRoutingTest {
    private val keys = listOf("k1", "k2", "k3")
    private val models = listOf("flash", "flash-old", "flash-lite")
    private val calls = mutableListOf<String>()

    @Before fun setUp() = GeminiRouter.reset()

    /** Sends one request; [fails] maps "model|key" to the failure that call returns. */
    private fun request(fails: Map<String, Failure> = emptyMap()): String = runBlocking {
        GeminiRouter.route(keys, models, { model, key, _ ->
            calls += "$model|$key"
            fails["$model|$key"]?.let { Attempt.Fail(it, "$model|$key $it") } ?: Attempt.Ok("$model|$key")
        }, { })
    }

    @Test
    fun onlyTheLastOptionWaitsLong() = runBlocking {
        // A stuck model shouldn't hold up the others: every attempt but the last is a quick one
        val patient = mutableListOf<Boolean>()
        GeminiRouter.route(listOf("k1"), models, { model, _, p ->
            patient += p
            if (model == "flash-lite") Attempt.Ok("ok") else Attempt.Fail(Failure.OVERLOADED, "slow")
        }, { })
        assertEquals(listOf(false, false, true), patient)
    }

    @Test
    fun keepsTheFirstKeyWhileItWorks() {
        repeat(3) { assertEquals("flash|k1", request()) }
        assertEquals(listOf("flash|k1", "flash|k1", "flash|k1"), calls)
    }

    @Test
    fun triesEveryModelBeforeTheNextKey() {
        val result = request(mapOf("flash|k1" to Failure.OVERLOADED, "flash-old|k1" to Failure.OVERLOADED))
        assertEquals("flash-lite|k1", result)
        assertEquals(listOf("flash|k1", "flash-old|k1", "flash-lite|k1"), calls)
    }

    @Test
    fun movesToTheNextKeyOnlyWhenAllModelsFailAndStaysThere() {
        val k1Down = models.associate { "$it|k1" to Failure.RATE_LIMITED }
        assertEquals("flash|k2", request(k1Down))
        assertEquals(listOf("flash|k1", "flash-old|k1", "flash-lite|k1", "flash|k2"), calls)
        calls.clear()
        // k1 works again, but k2 is now the current key and is used until it fails
        assertEquals("flash|k2", request())
        assertEquals(listOf("flash|k2"), calls)
    }

    @Test
    fun aBadKeyGoesStraightToTheNextKey() {
        assertEquals("flash|k2", request(mapOf("flash|k1" to Failure.BAD_KEY)))
        assertEquals(listOf("flash|k1", "flash|k2"), calls)
    }

    @Test
    fun wrapsAroundFromTheLastKey() {
        val down = (models.map { "$it|k1" } + models.map { "$it|k2" }).associateWith { Failure.OVERLOADED }
        assertEquals("flash|k3", request(down))
        calls.clear()
        val k3Down = models.associate { "$it|k3" to Failure.OVERLOADED }
        assertEquals("flash|k1", request(k3Down))
    }

    @Test
    fun aMalformedRequestFailsAtOnce() {
        assertThrows(IllegalStateException::class.java) { request(mapOf("flash|k1" to Failure.REJECTED)) }
        assertEquals(listOf("flash|k1"), calls)
    }

    @Test
    fun noConnectionKeepsTheKey() {
        assertThrows(IllegalStateException::class.java) {
            request(keys.flatMap { k -> models.map { "$it|$k" } }.associateWith { Failure.NETWORK })
        }
        assertEquals(listOf("flash|k1", "flash|k1", "flash|k1"), calls)   // one try per round, never another key
    }
}
