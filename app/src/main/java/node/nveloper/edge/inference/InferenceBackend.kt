package node.nveloper.edge.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * One message of a conversation. [text] is what text-only backends see; [raw] is the original
 * OpenAI message (tool calls, tool results, media parts replaced by media markers) and [media]
 * the decoded images/audio in marker order, both used by the llama.cpp backend.
 */
data class ChatMessage(
    val role: Role,
    val text: String,
    val raw: JsonObject? = null,
    val media: List<ByteArray> = emptyList(),
) {
    enum class Role { SYSTEM, USER, ASSISTANT }

    /** A user turn (not a tool result): where a truncated conversation may start. */
    val isUserTurn: Boolean
        get() = role == Role.USER && (raw?.get("role") as? kotlinx.serialization.json.JsonPrimitive)?.content != "tool"
}

/**
 * The model could not be put on the requested GPU accelerator on this device. Never answered by
 * silently switching to CPU: the UI asks the user what to do instead.
 */
class GpuLoadException(
    /** Accelerator that failed, as shown to the user ("Vulkan", "OpenCL", "GPU"). */
    val accelerator: String,
    /** Why, as far as the runtime tells us. */
    val reason: String,
) : Exception(reason)

data class GenerationParams(
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val maxTokens: Int? = null,
    val seed: Int? = null,
    val stop: List<String> = emptyList(),
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    /** Token id → bias added to its logit (-100 bans, +100 forces). */
    val logitBias: Map<Int, Float> = emptyMap(),
    /** Null: no logprobs. Otherwise the number of top alternatives per token (0–20). */
    val logprobs: Int? = null,
)

/**
 * One loaded model. Implementations are only ever called by [InferenceManager],
 * which guarantees at most one [generate] collection at a time.
 */
interface InferenceBackend : AutoCloseable {
    /** Returned as the model id in `/v1/models` and responses. */
    val modelId: String

    /** What actually got loaded (device, fallbacks, …), known after [load]. */
    val detail: String? get() = null

    /** Request features beyond plain chat that this backend implements. */
    val capabilities: Set<Capability> get() = emptySet()

    /** Heavy initialization (model load). Must not block the caller's thread. */
    suspend fun load()

    /**
     * Emits the reply to the conversation in [messages], whose last entry is the user turn (or an
     * assistant prefix with [ChatOptions.continueFinal]). Cancelling the collection must stop
     * generation. [onCache] is called once, before the first chunk, with how much context was reused.
     */
    fun generate(
        messages: List<ChatMessage>,
        params: GenerationParams,
        options: ChatOptions = ChatOptions(),
        onCache: (CacheResult) -> Unit = {},
    ): Flow<GenChunk>

    /** Raw text completion of [prompt] without a chat template (needs [Capability.RAW_COMPLETION]). */
    fun complete(prompt: String, params: GenerationParams, onCache: (CacheResult) -> Unit = {}): Flow<GenChunk> =
        throw UnsupportedOperationException("raw completion is not supported by this backend")

    fun cacheStats(): CacheStats

    override fun close()
}
