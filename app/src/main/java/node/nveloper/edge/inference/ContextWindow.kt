package node.nveloper.edge.inference

import kotlin.math.ceil

enum class OverflowPolicy {
    TRUNCATE_OLDEST,
    ERROR,
}

data class ContextConfig(
    /** Context size of the engine (prompt + generated tokens), passed as maxNumTokens. */
    val maxTokens: Int,
    val policy: OverflowPolicy = OverflowPolicy.TRUNCATE_OLDEST,
    /** Room left for the reply when the client does not send max_tokens. */
    val defaultReserve: Int = 512,
    /**
     * When truncating, cut down to this fraction of the window instead of just under the limit,
     * so the next few turns fit and can reuse the cached session instead of re-truncating.
     */
    val lowWatermark: Double = 0.75,
) {
    /** Tokens to keep free for the reply. */
    fun reserveFor(requestedMaxTokens: Int?): Int = minOf(requestedMaxTokens ?: defaultReserve, maxTokens / 2)
}

class ContextOverflowException(val requiredTokens: Int, val maxTokens: Int) :
    Exception("this request needs about $requiredTokens tokens but the context window is $maxTokens tokens")

/**
 * Estimates token counts without a tokenizer (LiteRT-LM does not expose one): a per-character
 * heuristic, scaled by a factor learned from exact KV-cache token counts reported after each
 * generation.
 */
class TokenEstimator(initialFactor: Double = 1.0) {
    @Volatile
    var factor: Double = initialFactor
        private set

    fun estimate(messages: List<ChatMessage>): Int = ceil(messages.sumOf { raw(it) } * factor).toInt()

    /** Learns from a session that processed exactly [messages] and reports [exactTokens]. */
    @Synchronized
    fun calibrate(messages: List<ChatMessage>, exactTokens: Int) {
        val raw = messages.sumOf { raw(it) }
        if (raw < MIN_CALIBRATION_TOKENS || exactTokens <= 0) return
        factor = (factor * 0.7 + exactTokens / raw * 0.3).coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    /** Called when the estimate proved too low (the backend overflowed). */
    @Synchronized
    fun penalize() {
        factor = (factor * 1.5).coerceAtMost(MAX_FACTOR)
    }

    private fun raw(message: ChatMessage): Double {
        var ascii = 0
        var other = 0
        for (c in message.text) if (c.code < 128) ascii++ else other++
        // Hangul/CJK take far more tokens per character than ASCII text.
        return ascii / 4.0 + other / 1.5 + PER_MESSAGE_OVERHEAD
    }

    private companion object {
        const val PER_MESSAGE_OVERHEAD = 5.0 // chat template tokens around each turn
        const val MIN_CALIBRATION_TOKENS = 32.0
        const val MIN_FACTOR = 0.3
        const val MAX_FACTOR = 4.0
    }
}

object ContextPlanner {
    data class Plan(val messages: List<ChatMessage>, val droppedMessages: Int, val promptTokens: Int)

    /**
     * Fits [messages] (last one is the user turn) into the window, leaving [reserve] tokens for
     * the reply. System messages and the last user message are always kept; the oldest turns are
     * dropped first, and the remaining conversation always starts with a user message.
     *
     * @throws ContextOverflowException when the policy is [OverflowPolicy.ERROR] and the request
     *   does not fit, or when even the system prompt plus the last message do not fit.
     */
    fun fit(messages: List<ChatMessage>, estimator: TokenEstimator, config: ContextConfig, reserve: Int): Plan {
        val full = estimator.estimate(messages)
        if (full + reserve <= config.maxTokens) return Plan(messages, 0, full)
        if (config.policy == OverflowPolicy.ERROR) throw ContextOverflowException(full + reserve, config.maxTokens)

        val system = messages.filter { it.role == ChatMessage.Role.SYSTEM }
        val turns = messages.filter { it.role != ChatMessage.Role.SYSTEM }
        val target = (config.maxTokens * config.lowWatermark).toInt() - reserve
        var start = 0
        var estimate = full
        while (start < turns.lastIndex) {
            start++
            while (start < turns.lastIndex && turns[start].role != ChatMessage.Role.USER) start++
            estimate = estimator.estimate(system + turns.drop(start))
            if (estimate <= target) break
        }
        if (estimate + reserve > config.maxTokens) throw ContextOverflowException(estimate + reserve, config.maxTokens)
        return Plan(system + turns.drop(start), start, estimate)
    }
}
