package node.nveloper.edge.inference

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Part of the assistant message produced by one generation step. */
sealed interface Delta {
    data class Content(val text: String) : Delta
    data class Reasoning(val text: String) : Delta

    /** A tool call is streamed as its id and name first, then argument fragments, keyed by [index]. */
    data class ToolCall(val index: Int, val id: String?, val name: String?, val arguments: String) : Delta
}

class TopLogprob(val token: String, val bytes: ByteArray, val logprob: Double)

class TokenLogprob(val token: String, val bytes: ByteArray, val logprob: Double, val top: List<TopLogprob>)

/**
 * One generation step. [tokens] is how many tokens it consumed (deltas may be empty while the
 * parser holds back a partial tool call or stop string). The last chunk carries [finish]
 * ("stop" or "length").
 */
data class GenChunk(
    val deltas: List<Delta> = emptyList(),
    val tokens: Int = 0,
    val logprobs: List<TokenLogprob> = emptyList(),
    val finish: String? = null,
)

/** Request features a backend implements; the server rejects requests needing others. */
enum class Capability(val field: String) {
    STOP("stop"),
    PENALTIES("frequency_penalty/presence_penalty"),
    LOGIT_BIAS("logit_bias"),
    LOGPROBS("logprobs"),
    JSON_SCHEMA("response_format"),
    TOOLS("tools"),
    REASONING("reasoning_content"),
    CONTINUE("assistant message last (continuation)"),
    IMAGES("image input"),
    AUDIO("audio input"),
    RAW_COMPLETION("/v1/completions"),
}

/**
 * Chat-template level options of an OpenAI chat request (llama.cpp backend). [tools] and
 * [jsonSchema] become a sampling grammar; [reasoningFormat] decides whether thinking is split
 * into `reasoning_content` ("deepseek", default) or left inline ("none").
 */
data class ChatOptions(
    val tools: JsonArray? = null,
    /** "auto", "none" or "required". */
    val toolChoice: String = "auto",
    val parallelToolCalls: Boolean? = null,
    val jsonSchema: JsonObject? = null,
    /** The last message is an assistant prefix to continue rather than a new turn. */
    val continueFinal: Boolean = false,
    val reasoningFormat: String? = null,
    val enableThinking: Boolean? = null,
    val reasoningEffort: String? = null,
    val templateKwargs: JsonObject? = null,
)

/**
 * Holds back text that could be the start of a stop string and cuts output at the first full
 * match, the way llama-server handles `stop`.
 */
class StopMatcher(stops: List<String>) {
    private val stops = stops.filter { it.isNotEmpty() }.distinct()
    private val buffer = StringBuilder()
    private var emitted = 0

    var stopped = false
        private set

    /** Adds generated text; returns the part that is safe to send. Sets [stopped] on a match. */
    fun push(text: String): String {
        if (stopped) return ""
        buffer.append(text)
        if (stops.isEmpty()) return take(buffer.length)
        val hit = stops.mapNotNull { stop -> buffer.indexOf(stop, emitted).takeIf { it >= 0 } }.minOrNull()
        if (hit != null) {
            stopped = true
            return take(hit)
        }
        return take(buffer.length - heldBack())
    }

    /** What is still held back once generation ended without a stop string. */
    fun flush(): String = if (stopped) "" else take(buffer.length)

    /** Length of the longest buffer suffix that is a proper prefix of a stop string. */
    private fun heldBack(): Int {
        val max = minOf(stops.maxOf { it.length } - 1, buffer.length - emitted)
        for (n in max downTo 1) {
            val start = buffer.length - n
            if (stops.any { it.length > n && buffer.regionMatches(start, it, 0, n) }) return n
        }
        return 0
    }

    private fun take(end: Int): String {
        val until = maxOf(end, emitted)
        return buffer.substring(emitted, until).also { emitted = until }
    }

    private fun StringBuilder.regionMatches(start: Int, other: String, otherStart: Int, length: Int): Boolean {
        for (i in 0 until length) if (this[start + i] != other[otherStart + i]) return false
        return true
    }
}
