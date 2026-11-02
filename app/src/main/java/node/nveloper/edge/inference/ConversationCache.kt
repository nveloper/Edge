package node.nveloper.edge.inference

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** How a request was prepared by [ConversationCache]. */
data class CacheResult(
    val hit: Boolean,
    /** Messages already in the session's KV cache and not prefilled again. */
    val reusedMessages: Int,
    /** Messages that had to be prefilled for this request. */
    val newMessages: Int,
    /** Oldest messages left out to fit the context window. */
    val truncatedMessages: Int,
    /** Estimated tokens in the context before the reply (exact KV count + estimate on a hit). */
    val promptTokens: Int,
    /** Tokens left in the context window for the reply. */
    val completionBudget: Int,
    /** Prompt tokens already in the KV cache (llama.cpp reuses at token granularity). */
    val reusedTokens: Int = 0,
)

data class CacheStats(
    val capacity: Int,
    val size: Int,
    val hits: Long,
    val misses: Long,
    val tokenFactor: Double,
    /** Sessions dropped because the system was low on memory. */
    val memoryEvictions: Long,
    val contextTokens: Int,
    val overflowPolicy: OverflowPolicy,
)

/**
 * Keeps live backend sessions (each holding a conversation in its KV cache) alive between
 * stateless OpenAI-style requests, and keeps every request inside the context window.
 *
 * Reuse: a request reuses a session when everything before its last user message equals what
 * the client sent for that session plus the reply the session produced. Matching is on the
 * client's view of the conversation, so a session that was built from a truncated history still
 * matches the full history the client keeps resending.
 *
 * Context: on a hit the exact KV token count plus the new message must leave room for the
 * reply, otherwise the session is dropped and the conversation rebuilt. A rebuilt or new
 * session is planned by [ContextPlanner], which may drop the oldest turns.
 *
 * Sessions whose generation did not complete (max_tokens cut-off, client disconnect, error)
 * are closed, since their internal state no longer matches any history a client could send.
 *
 * When full, the least valuable session is evicted: fewest reuses first, then fewest turns,
 * then least recently used. This keeps a long-running chat cached even when a client such as
 * OpenWebUI interleaves one-off requests (title/tag/follow-up generation).
 */
class ConversationCache<S : AutoCloseable>(
    val capacity: Int,
    private val context: ContextConfig,
    private val estimator: TokenEstimator = TokenEstimator(),
    /**
     * True when the system is short on memory. Every live session holds its own KV cache, so
     * before a new one is allocated the cached ones are dropped rather than risking the process
     * being killed by the low-memory killer.
     */
    private val memoryLow: () -> Boolean = { false },
    private val log: (String) -> Unit = {},
) {
    private class Entry<S>(
        val key: Any,
        /** What the client sent (untruncated) plus the reply; used for matching. */
        val history: List<ChatMessage>,
        val session: S,
        /** Tokens in the session's KV cache. */
        val tokens: Int,
        val reuses: Int,
        val lastUsed: Long,
    ) {
        val turns = history.count { it.role == ChatMessage.Role.USER }
    }

    private class Attempt<S>(
        val session: S,
        val result: CacheResult,
        /** Messages the new session was built from (null on a hit); used for calibration. */
        val plannedMessages: List<ChatMessage>?,
        val reuses: Int,
    )

    private val entries = mutableListOf<Entry<S>>()
    private var clock = 0L
    private var hits = 0L
    private var misses = 0L
    private var memoryEvictions = 0L

    /**
     * Streams the reply to [messages] (last entry must be the user turn).
     *
     * @param key sessions are only reused for an equal key (e.g. sampler settings, which are
     *   fixed when a session is created).
     * @param open creates a session that has seen `prefix`.
     * @param send prefills one user message into a session and streams the reply.
     * @param abort stops an in-flight [send] before the session is closed.
     * @param tokenCount exact number of tokens in a session's KV cache, or <= 0 if unknown.
     * @throws ContextOverflowException (from the flow) when the request cannot fit.
     */
    fun generate(
        key: Any,
        messages: List<ChatMessage>,
        requestedMaxTokens: Int?,
        open: (prefix: List<ChatMessage>) -> S,
        send: (session: S, message: ChatMessage) -> Flow<String>,
        abort: (S) -> Unit,
        tokenCount: (S) -> Int,
        onCache: (CacheResult) -> Unit,
    ): Flow<String> = flow {
        val last = messages.last()
        require(last.role == ChatMessage.Role.USER) { "the last message must have role 'user'" }
        val reserve = context.reserveFor(requestedMaxTokens)
        var retried = false

        while (true) {
            val attempt = prepare(key, messages, reserve, open)
            onCache(attempt.result)

            val reply = StringBuilder()
            var emitted = false
            var completed = false
            try {
                send(attempt.session, last).collect {
                    reply.append(it)
                    emitted = true
                    emit(it)
                }
                completed = true
            } catch (e: Exception) {
                // A failure before the first token on a large prompt most likely means the token
                // estimate was too low and the backend overflowed: estimate higher, replan once.
                val plausibleOverflow = attempt.result.promptTokens > context.maxTokens / 2
                if (e is CancellationException || emitted || retried || !plausibleOverflow) throw e
                retried = true
                estimator.penalize()
                continue
            } finally {
                if (completed) {
                    store(key, messages, reply.toString(), attempt, tokenCount)
                } else {
                    runCatching { abort(attempt.session) }
                    attempt.session.close()
                }
            }
            return@flow
        }
    }

    @Synchronized
    fun stats() =
        CacheStats(capacity, entries.size, hits, misses, estimator.factor, memoryEvictions, context.maxTokens, context.policy)

    @Synchronized
    fun clear() {
        entries.forEach { runCatching { it.session.close() } }
        entries.clear()
    }

    private fun prepare(
        key: Any,
        messages: List<ChatMessage>,
        reserve: Int,
        open: (prefix: List<ChatMessage>) -> S,
    ): Attempt<S> {
        val prefix = messages.dropLast(1)
        val cached = take(key, prefix)
        if (cached != null) {
            val prompt = cached.tokens + estimator.estimate(listOf(messages.last()))
            if (prompt + reserve <= context.maxTokens) {
                countHit()
                return Attempt(
                    cached.session,
                    CacheResult(true, prefix.size, 1, 0, prompt, context.maxTokens - prompt),
                    plannedMessages = null,
                    reuses = cached.reuses + 1,
                )
            }
            // The conversation outgrew the window: rebuild it from a truncated history below.
            runCatching { cached.session.close() }
        }
        countMiss()
        val plan = ContextPlanner.fit(messages, estimator, context, reserve)
        if (memoryLow()) evictForMemory()
        val session = open(plan.messages.dropLast(1))
        return Attempt(
            session,
            CacheResult(
                hit = false,
                reusedMessages = 0,
                newMessages = plan.messages.size,
                truncatedMessages = plan.droppedMessages,
                promptTokens = plan.promptTokens,
                completionBudget = context.maxTokens - plan.promptTokens,
            ),
            plannedMessages = plan.messages,
            reuses = 0,
        )
    }

    private fun store(
        key: Any,
        messages: List<ChatMessage>,
        reply: String,
        attempt: Attempt<S>,
        tokenCount: (S) -> Int,
    ) {
        val replyMessage = ChatMessage(ChatMessage.Role.ASSISTANT, reply)
        val exact = runCatching { tokenCount(attempt.session) }.getOrDefault(0)
        attempt.plannedMessages?.let { if (exact > 0) estimator.calibrate(it + replyMessage, exact) }
        val tokens = if (exact > 0) exact else attempt.result.promptTokens + estimator.estimate(listOf(replyMessage))
        put(key, messages + replyMessage, attempt.session, tokens, attempt.reuses)
    }

    @Synchronized
    private fun evictForMemory() {
        if (entries.isEmpty()) return
        val n = entries.size
        entries.forEach { runCatching { it.session.close() } }
        entries.clear()
        memoryEvictions += n
        log("low memory: dropped $n cached conversation(s) before opening a new one")
    }

    @Synchronized
    private fun countHit() {
        hits++
    }

    @Synchronized
    private fun countMiss() {
        misses++
    }

    @Synchronized
    private fun take(key: Any, prefix: List<ChatMessage>): Entry<S>? {
        val index = entries.indexOfFirst { it.key == key && sameHistory(it.history, prefix) }
        return if (index < 0) null else entries.removeAt(index)
    }

    @Synchronized
    private fun put(key: Any, history: List<ChatMessage>, session: S, tokens: Int, reuses: Int) {
        entries += Entry(key, history, session, tokens, reuses, ++clock)
        while (entries.size > capacity) {
            val victim = entries.minWith(compareBy<Entry<S>>({ it.reuses }, { it.turns }, { it.lastUsed }))
            entries.remove(victim)
            runCatching { victim.session.close() }
        }
    }

    private fun sameHistory(a: List<ChatMessage>, b: List<ChatMessage>): Boolean =
        a.size == b.size && a.indices.all { i ->
            // Clients commonly trim whitespace around messages they echo back.
            a[i].role == b[i].role && a[i].text.trim() == b[i].text.trim()
        }
}
