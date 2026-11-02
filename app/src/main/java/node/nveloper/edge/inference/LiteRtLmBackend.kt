package node.nveloper.edge.inference

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import node.nveloper.edge.models.SamplerDefaults
import java.io.File

/**
 * LiteRT-LM (the runtime behind Google AI Edge Gallery). Loads a `.litertlm` model and runs it
 * on CPU or GPU.
 *
 * The OpenAI API is stateless, but a LiteRT-LM Conversation keeps its KV cache across
 * sendMessage calls. [ConversationCache] keeps up to `cacheSlots` Conversations alive so a
 * follow-up turn only prefills the new user message. Each live Conversation holds its own KV
 * cache in memory, so slots cost RAM.
 */
class LiteRtLmBackend(
    private val modelFile: File,
    private val accelerator: Accelerator,
    private val cacheDir: File,
    cacheSlots: Int,
    private val context: ContextConfig,
    /** Sampling used when a request does not set temperature/top_p/top_k. */
    private val sampler: SamplerDefaults = SamplerDefaults.GEMMA,
    memoryLow: () -> Boolean = { false },
    log: (String) -> Unit = {},
) : InferenceBackend {
    enum class Accelerator { CPU, GPU }

    override val modelId: String = modelFile.nameWithoutExtension

    // The engine and its conversations are touched from a single thread only.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    @Volatile
    private var engine: Engine? = null

    private val cache = ConversationCache<Conversation>(cacheSlots, context, memoryLow = memoryLow, log = log)

    override suspend fun load() = withContext(dispatcher) {
        val config = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = when (accelerator) {
                Accelerator.CPU -> Backend.CPU()
                Accelerator.GPU -> Backend.GPU()
            },
            maxNumTokens = context.maxTokens,
            cacheDir = cacheDir.absolutePath,
        )
        engine = try {
            Engine(config).also { it.initialize() }
        } catch (e: Exception) {
            if (accelerator == Accelerator.GPU) throw GpuLoadException("GPU", e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    override fun generate(
        messages: List<ChatMessage>,
        params: GenerationParams,
        options: ChatOptions,
        onCache: (CacheResult) -> Unit,
    ): Flow<GenChunk> = cache.generate(
        // Sampler settings are fixed per Conversation, so they are part of the cache key.
        key = params.copy(maxTokens = null),
        messages = messages,
        requestedMaxTokens = params.maxTokens,
        open = { prefix -> openConversation(prefix, params) },
        send = { conversation, message -> conversation.sendMessageAsync(message.text).map { it.toString() } },
        abort = { it.cancelProcess() },
        tokenCount = { it.getTokenCount() },
        onCache = onCache,
    ).map { GenChunk(listOf(Delta.Content(it)), tokens = 1) }.flowOn(dispatcher)

    private fun openConversation(prefix: List<ChatMessage>, params: GenerationParams): Conversation {
        val engine = checkNotNull(engine) { "engine not loaded" }
        val system = prefix.filter { it.role == ChatMessage.Role.SYSTEM }.joinToString("\n\n") { it.text }
        val history = prefix.filter { it.role != ChatMessage.Role.SYSTEM }.map {
            if (it.role == ChatMessage.Role.USER) Message.user(it.text) else Message.model(it.text)
        }
        return engine.createConversation(
            ConversationConfig(
                systemInstruction = if (system.isBlank()) null else Contents.of(system),
                initialMessages = history,
                samplerConfig = SamplerConfig(
                    topK = params.topK ?: sampler.topK,
                    topP = params.topP ?: sampler.topP,
                    temperature = params.temperature ?: sampler.temperature,
                    seed = params.seed ?: 0,
                ),
            ),
        )
    }

    override fun cacheStats() = cache.stats()

    override fun close() {
        cache.clear()
        engine?.close()
        engine = null
    }
}
