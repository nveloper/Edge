package node.nveloper.edge.inference

import node.nveloper.edge.inference.ChatMessage.Role.ASSISTANT
import node.nveloper.edge.inference.ChatMessage.Role.SYSTEM
import node.nveloper.edge.inference.ChatMessage.Role.USER
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConversationCacheTest {
    private class FakeSession(val id: Int, val prefix: List<ChatMessage>) : AutoCloseable {
        val sent = mutableListOf<String>()
        var tokens = 0
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private val opened = mutableListOf<FakeSession>()
    private var lastResult: CacheResult? = null
    private val bigWindow = ContextConfig(maxTokens = 100_000)

    /** Fake "tokenizer" for session token counts: 1 token per 4 chars + 5 per message. */
    private fun fakeTokens(text: String) = text.length / 4 + 5

    private fun ConversationCache<FakeSession>.ask(
        messages: List<ChatMessage>,
        key: Any = "k",
        failAfter: Int? = null,
        takeOnly: Int? = null,
        maxTokens: Int? = null,
    ): String = runBlocking {
        val flow = generate(
            key = key,
            messages = messages,
            requestedMaxTokens = maxTokens,
            open = { prefix ->
                FakeSession(opened.size, prefix).also {
                    it.tokens = prefix.sumOf { m -> fakeTokens(m.text) }
                    opened += it
                }
            },
            send = { session, message ->
                flow {
                    session.sent += message.text
                    session.tokens += fakeTokens(message.text)
                    listOf("re:", message.text).forEachIndexed { i, part ->
                        if (failAfter == i) error("boom")
                        session.tokens += 1
                        emit(part)
                    }
                }
            },
            abort = {},
            tokenCount = { it.tokens },
            onCache = { lastResult = it },
        )
        (if (takeOnly != null) flow.take(takeOnly) else flow).toList().joinToString("")
    }

    private fun u(text: String) = ChatMessage(USER, text)
    private fun a(text: String) = ChatMessage(ASSISTANT, text)
    private fun s(text: String) = ChatMessage(SYSTEM, text)

    // --- KV reuse -------------------------------------------------------------------------------

    @Test
    fun followUpTurnReusesSessionAndOnlySendsNewMessage() {
        val cache = ConversationCache<FakeSession>(2, bigWindow)
        val r1 = cache.ask(listOf(s("sys"), u("hi")))
        assertFalse(lastResult!!.hit)

        cache.ask(listOf(s("sys"), u("hi"), a(r1), u("more")))
        assertTrue(lastResult!!.hit)
        assertEquals(3, lastResult!!.reusedMessages)
        assertEquals(1, lastResult!!.newMessages)
        assertEquals(1, opened.size)
        assertEquals(listOf("hi", "more"), opened[0].sent)
        val stats = cache.stats()
        assertEquals(1L, stats.hits)
        assertEquals(1L, stats.misses)
        assertEquals(1, stats.size)
        assertEquals(100_000, stats.contextTokens)
    }

    @Test
    fun echoedReplyWithTrimmedWhitespaceStillHits() {
        val cache = ConversationCache<FakeSession>(1, bigWindow)
        val r1 = cache.ask(listOf(u("hi")))
        cache.ask(listOf(u(" hi\n"), a("  $r1  "), u("next")))
        assertTrue(lastResult!!.hit)
    }

    @Test
    fun editedHistoryOrDifferentKeyMisses() {
        val cache = ConversationCache<FakeSession>(4, bigWindow)
        val r1 = cache.ask(listOf(u("hi")))

        cache.ask(listOf(u("hi"), a(r1 + " edited"), u("next")))
        assertFalse(lastResult!!.hit)
        assertEquals(listOf(u("hi"), a("$r1 edited")), opened.last().prefix)

        cache.ask(listOf(u("hi"), a(r1), u("next")), key = "other-sampler")
        assertFalse(lastResult!!.hit)
    }

    @Test
    fun regenerateWithSamePrefixMisses() {
        val cache = ConversationCache<FakeSession>(2, bigWindow)
        val r1 = cache.ask(listOf(u("hi")))
        cache.ask(listOf(u("hi"), a(r1), u("q2")))
        // Regenerating the answer to q2 resends the history without the old answer.
        cache.ask(listOf(u("hi"), a(r1), u("q2")))
        assertFalse(lastResult!!.hit)
    }

    @Test
    fun cutOffGenerationIsNotCachedAndSessionIsClosed() {
        val cache = ConversationCache<FakeSession>(2, bigWindow)
        val partial = cache.ask(listOf(u("hi")), takeOnly = 1)
        assertEquals("re:", partial)
        assertTrue(opened[0].closed)
        assertEquals(0, cache.stats().size)

        cache.ask(listOf(u("hi"), a(partial), u("next")))
        assertFalse(lastResult!!.hit)
    }

    @Test
    fun failedGenerationClosesReusedSession() {
        val cache = ConversationCache<FakeSession>(2, bigWindow)
        val r1 = cache.ask(listOf(u("hi")))
        try {
            cache.ask(listOf(u("hi"), a(r1), u("next")), failAfter = 1)
            fail("expected failure")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertTrue(opened[0].closed)
        assertEquals(0, cache.stats().size)
    }

    @Test
    fun oneOffRequestsDoNotEvictAnActiveConversation() {
        val cache = ConversationCache<FakeSession>(2, bigWindow)
        val r1 = cache.ask(listOf(u("chat 1")))
        val r2 = cache.ask(listOf(u("chat 1"), a(r1), u("chat 2")))
        // OpenWebUI-style background tasks: unique single-turn prompts.
        cache.ask(listOf(u("### Task: title")))
        cache.ask(listOf(u("### Task: tags")))
        cache.ask(listOf(u("### Task: follow-ups")))

        cache.ask(listOf(u("chat 1"), a(r1), u("chat 2"), a(r2), u("chat 3")))
        assertTrue(lastResult!!.hit)
        assertEquals(4, lastResult!!.reusedMessages)
        assertFalse(opened[0].closed)
        assertEquals(2, cache.stats().size)
    }

    @Test
    fun zeroCapacityNeverCaches() {
        val cache = ConversationCache<FakeSession>(0, bigWindow)
        val r1 = cache.ask(listOf(u("hi")))
        assertTrue(opened[0].closed)
        cache.ask(listOf(u("hi"), a(r1), u("next")))
        assertFalse(lastResult!!.hit)
    }

    @Test
    fun clearClosesEverything() {
        val cache = ConversationCache<FakeSession>(3, bigWindow)
        cache.ask(listOf(u("a")))
        cache.ask(listOf(u("b")))
        cache.clear()
        assertTrue(opened.all { it.closed })
        assertEquals(0, cache.stats().size)
    }

    // --- Context window -------------------------------------------------------------------------

    private fun longText(n: Int) = "x".repeat(n)

    @Test
    fun overflowingRequestIsTruncatedFromTheOldestTurnKeepingSystemAndLastMessage() {
        val cache = ConversationCache<FakeSession>(2, ContextConfig(maxTokens = 400, defaultReserve = 50))
        val history = listOf(
            s("sys"),
            u(longText(600)), a(longText(600)),
            u(longText(600)), a(longText(600)),
            u("latest question"),
        )
        cache.ask(history)
        val r = lastResult!!
        assertFalse(r.hit)
        assertEquals(4, r.truncatedMessages)
        assertEquals(listOf(s("sys")), opened[0].prefix)
        assertEquals(listOf("latest question"), opened[0].sent)
        assertTrue(r.promptTokens + 50 <= 400)
        assertEquals(400 - r.promptTokens, r.completionBudget)
    }

    @Test
    fun truncatedConversationStillHitsOnTheNextTurn() {
        val cache = ConversationCache<FakeSession>(2, ContextConfig(maxTokens = 400, defaultReserve = 50))
        val base = listOf(u(longText(800)), a(longText(800)), u("q2"))
        val r2 = cache.ask(base)
        assertEquals(2, lastResult!!.truncatedMessages)

        // The client resends the full, untruncated history plus a new question.
        cache.ask(base + a(r2) + u("q3"))
        assertTrue(lastResult!!.hit)
        assertEquals(1, opened.size)
        assertEquals(listOf("q2", "q3"), opened[0].sent)
    }

    @Test
    fun cachedSessionThatOutgrowsTheWindowIsRebuiltTruncated() {
        val cache = ConversationCache<FakeSession>(2, ContextConfig(maxTokens = 400, defaultReserve = 50))
        val r1 = cache.ask(listOf(u(longText(500))))
        assertFalse(lastResult!!.hit)
        assertEquals(0, lastResult!!.truncatedMessages)

        // ~130 tokens cached + ~300 new + 50 reserve > 400: the old session cannot take it.
        cache.ask(listOf(u(longText(500)), a(r1), u(longText(1400))))
        val r = lastResult!!
        assertFalse(r.hit)
        assertEquals(2, r.truncatedMessages)
        assertTrue(opened[0].closed)
        assertEquals(2, opened.size)
    }

    @Test
    fun errorPolicyRejectsInsteadOfTruncating() {
        val cache = ConversationCache<FakeSession>(
            2,
            ContextConfig(maxTokens = 400, policy = OverflowPolicy.ERROR, defaultReserve = 50),
        )
        try {
            cache.ask(listOf(u(longText(800)), a(longText(800)), u("q")))
            fail("expected overflow")
        } catch (e: ContextOverflowException) {
            assertEquals(400, e.maxTokens)
        }
        assertTrue(opened.isEmpty())
    }

    @Test
    fun lastMessageAloneTooLargeIsRejectedEvenWhenTruncating() {
        val cache = ConversationCache<FakeSession>(2, ContextConfig(maxTokens = 400, defaultReserve = 50))
        try {
            cache.ask(listOf(u("short"), a("ok"), u(longText(4000))))
            fail("expected overflow")
        } catch (e: ContextOverflowException) {
            assertTrue(e.requiredTokens > 400)
        }
    }

    @Test
    fun requestedMaxTokensIsReservedAndCappedAtHalfTheWindow() {
        val config = ContextConfig(maxTokens = 1000, defaultReserve = 100)
        assertEquals(100, config.reserveFor(null))
        assertEquals(300, config.reserveFor(300))
        assertEquals(500, config.reserveFor(5000))
    }

    @Test
    fun earlyBackendFailureOnLargePromptRetriesOnceWithHigherEstimate() {
        val estimator = TokenEstimator()
        val cache = ConversationCache<FakeSession>(2, ContextConfig(maxTokens = 600, defaultReserve = 50), estimator)
        var attempts = 0
        val result = runBlocking {
            cache.generate(
                key = "k",
                // Fits by estimate (~465 + 50 <= 600), but the "backend" overflows on the first try.
                messages = listOf(u(longText(400)), a(longText(400)), u(longText(1000))),
                requestedMaxTokens = null,
                open = { prefix -> FakeSession(opened.size, prefix).also { opened += it } },
                send = { _, _ ->
                    flow {
                        attempts++
                        if (attempts == 1) error("native: context overflow")
                        emit("ok")
                    }
                },
                abort = {},
                tokenCount = { 0 },
                onCache = { lastResult = it },
            ).toList()
        }
        assertEquals(listOf("ok"), result)
        assertEquals(2, attempts)
        assertTrue(estimator.factor > 1.0)
        assertTrue(opened[0].closed)
        // The replan with the higher estimate no longer fits untruncated.
        assertEquals(2, lastResult!!.truncatedMessages)
    }

    @Test
    fun lowMemoryDropsCachedSessionsBeforeOpeningANewOne() {
        var low = false
        val cache = ConversationCache<FakeSession>(3, bigWindow, memoryLow = { low })
        cache.ask(listOf(u("chat A")))
        cache.ask(listOf(u("chat B")))
        assertEquals(2, cache.stats().size)

        low = true
        cache.ask(listOf(u("chat C")))
        assertTrue(opened[0].closed)
        assertTrue(opened[1].closed)
        assertFalse(opened[2].closed)
        assertEquals(1, cache.stats().size)
        assertEquals(2L, cache.stats().memoryEvictions)
    }

    @Test
    fun lowMemoryDoesNotAffectACacheHit() {
        var low = false
        val cache = ConversationCache<FakeSession>(3, bigWindow, memoryLow = { low })
        val r1 = cache.ask(listOf(u("hi")))
        low = true
        cache.ask(listOf(u("hi"), a(r1), u("next")))
        assertTrue(lastResult!!.hit)
        assertFalse(opened[0].closed)
    }

    @Test
    fun estimatorCalibratesTowardsExactCounts() {
        val estimator = TokenEstimator()
        val msgs = listOf(u(longText(400)))
        val before = estimator.estimate(msgs)
        repeat(10) { estimator.calibrate(msgs, before * 2) }
        val after = estimator.estimate(msgs)
        assertTrue("estimate $before -> $after", after in (before * 19 / 10)..(before * 21 / 10))
    }

    @Test
    fun hangulIsEstimatedDenserThanAscii() {
        val estimator = TokenEstimator()
        val ascii = estimator.estimate(listOf(u("a".repeat(300))))
        val hangul = estimator.estimate(listOf(u("가".repeat(300))))
        assertTrue(hangul > ascii * 2)
    }
}
