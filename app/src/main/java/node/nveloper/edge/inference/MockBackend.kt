package node.nveloper.edge.inference

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlin.math.ceil

/**
 * Model-free backend for debugging the API path on an emulator. Streams a canned reply
 * word by word at [tokensPerSecond].
 *
 * It behaves like a real engine where it matters for the API layer:
 *  - a context window of [context].maxTokens, with an overflow error when it is exceeded;
 *  - its own "tokenizer" (deliberately different from [TokenEstimator]'s heuristic) that the
 *    exact token count reports, so estimator calibration can be observed;
 *  - prefill at [prefillTokensPerSecond] over whatever the session has not seen yet, through the
 *    same [ConversationCache] as the real backend, so cache hits show up as a lower TTFT.
 *
 * Magic words in the last user message:
 *  - `/error` fails mid-stream, to exercise error handling.
 *  - `/long`  produces a long reply, to exercise max_tokens and client disconnects.
 */
class MockBackend(
    private val tokensPerSecond: Float,
    cacheSlots: Int,
    private val context: ContextConfig,
    private val prefillTokensPerSecond: Float = 250f,
    memoryLow: () -> Boolean = { false },
    log: (String) -> Unit = {},
) : InferenceBackend {
    override val modelId = "mock-echo"

    private class Session(var pendingTokens: Int) : AutoCloseable {
        var kvTokens = 0
        override fun close() = Unit
    }

    private val cache = ConversationCache<Session>(cacheSlots, context, memoryLow = memoryLow, log = log)

    override suspend fun load() {
        delay(300)
    }

    override fun generate(
        messages: List<ChatMessage>,
        params: GenerationParams,
        options: ChatOptions,
        onCache: (CacheResult) -> Unit,
    ): Flow<GenChunk> = cache.generate(
        key = params.copy(maxTokens = null),
        messages = messages,
        requestedMaxTokens = params.maxTokens,
        open = { prefix -> Session(prefix.sumOf { tokens(it.text) }) },
        send = { session, message -> reply(session, message.text) },
        abort = {},
        tokenCount = { it.kvTokens },
        onCache = onCache,
    ).map { GenChunk(listOf(Delta.Content(it)), tokens = 1) }

    private fun reply(session: Session, prompt: String): Flow<String> = flow {
        val prefill = session.pendingTokens + tokens(prompt)
        check(session.kvTokens + prefill <= context.maxTokens) {
            "MockBackend: context overflow (${session.kvTokens + prefill} > ${context.maxTokens} tokens)"
        }
        delay(50 + (prefill * 1000 / prefillTokensPerSecond).toLong())
        session.kvTokens += prefill
        session.pendingTokens = 0

        val interval = (1000f / tokensPerSecond.coerceAtLeast(0.1f)).toLong()
        words(prompt).forEachIndexed { i, word ->
            if (i > 0) delay(interval)
            if ("/error" in prompt && i == 3) error("MockBackend: simulated failure (/error)")
            check(session.kvTokens < context.maxTokens) { "MockBackend: context full while decoding" }
            session.kvTokens++
            emit(word)
        }
        session.kvTokens += TURN_OVERHEAD
    }

    private fun words(prompt: String): List<String> {
        val head = "[mock] Last input: \"${prompt.take(60)}\". "
        val filler = "This is a simulated streaming reply used to debug the OpenAI compatible API without a model. "
        val text = head + if ("/long" in prompt) filler.repeat(10) else filler
        return Regex("""\S+\s*""").findAll(text).map { it.value }.toList()
    }

    /** The mock's "tokenizer": ~3.5 chars per token plus template overhead. */
    private fun tokens(text: String) = ceil(text.length / 3.5).toInt() + TURN_OVERHEAD

    override fun cacheStats() = cache.stats()

    override fun close() = cache.clear()

    private companion object {
        const val TURN_OVERHEAD = 6
    }
}
