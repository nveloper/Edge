package node.nveloper.edge.llama

import android.content.res.Resources
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import node.nveloper.edge.R
import node.nveloper.edge.inference.CacheResult
import node.nveloper.edge.inference.Capability
import node.nveloper.edge.inference.ChatOptions
import node.nveloper.edge.inference.Delta
import node.nveloper.edge.inference.GenChunk
import node.nveloper.edge.inference.StopMatcher
import node.nveloper.edge.inference.TokenLogprob
import node.nveloper.edge.inference.TopLogprob
import node.nveloper.edge.inference.CacheStats
import node.nveloper.edge.inference.ChatMessage
import node.nveloper.edge.inference.ContextConfig
import node.nveloper.edge.inference.ContextOverflowException
import node.nveloper.edge.inference.GenerationParams
import node.nveloper.edge.inference.GpuLoadException
import node.nveloper.edge.inference.InferenceBackend
import node.nveloper.edge.inference.OverflowPolicy
import java.io.File
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * GGUF models through llama.cpp, on Vulkan (default), OpenCL or CPU.
 *
 * Unlike LiteRT-LM, llama.cpp exposes the tokenizer and lets the KV cache be cut at any
 * position, so reuse works on tokens like llama-server's prompt cache: each slot remembers the
 * tokens in its KV cache, a request goes to the slot sharing the longest prefix with its prompt,
 * and only the remainder is prefilled. Editing or regenerating a turn still reuses everything
 * before the change, and a cancelled generation leaves the slot usable. Token counts and
 * context fitting are exact.
 */
class LlamaCppBackend(
    private val modelFile: File,
    private val accelerator: Accelerator,
    private val nativeLibDir: String,
    private val slotCount: Int,
    private val context: ContextConfig,
    /** For user-facing error and fallback messages. */
    private val res: Resources,
    /** Multimodal projector for image/audio input, or null. */
    private val mmprojFile: File? = null,
    /** Q8_0 KV cache. Not on OpenCL: Adreno 7xx runs quantized-KV attention on the CPU there. */
    kvQ8: Boolean = false,
    private val memoryLow: () -> Boolean = { false },
    private val log: (String) -> Unit = {},
) : InferenceBackend {
    enum class Accelerator(val ggmlBackend: String, val label: String) {
        VULKAN("Vulkan", "Vulkan"),
        OPENCL("OpenCL", "OpenCL"),
        CPU("", "CPU"),
    }

    private class Slot(val ctx: Long) {
        var tokens = IntArray(0)
        var used = 0L
    }

    override val modelId: String = modelFile.nameWithoutExtension

    // llama.cpp handles are used from this one thread only.
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "llama").apply { isDaemon = true } }
    private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()

    private var model = 0L
    private var mtmd = 0L
    private var audio = false
    private var hasTemplate = false
    private var nSwa = 0
    private val kvQ8 = kvQ8 && accelerator != Accelerator.OPENCL
    private var nCtx = context.maxTokens
    private var threads = 4
    private val slots = mutableListOf<Slot>()
    private var clock = 0L
    private var hits = 0L
    private var misses = 0L
    private var memoryEvictions = 0L

    /** Device actually used, plus why if it is not the one asked for. */
    @Volatile
    override var detail: String? = null
        private set

    override suspend fun load() = withContext(dispatcher) {
        LlamaNative.ensureInit(nativeLibDir)
        val devices = LlamaNative.deviceList()
        log("ggml devices: " + devices.joinToString { "${it.backend}/${it.name} (${it.description})" })

        if (accelerator != Accelerator.CPU) {
            model = LlamaNative.loadModel(modelFile.absolutePath, accelerator.ggmlBackend, ALL_LAYERS, true)
            if (model == 0L) {
                // No silent CPU fallback: report what failed and let the user decide.
                val reason = if (devices.none { it.backend == accelerator.ggmlBackend }) {
                    res.getString(R.string.err_no_device, accelerator.label)
                } else {
                    LlamaNative.lastError().ifBlank { res.getString(R.string.err_device_failed, accelerator.label) }
                }
                log("${accelerator.label} load failed: $reason")
                throw GpuLoadException(accelerator.label, reason)
            }
        } else {
            model = LlamaNative.loadModel(modelFile.absolutePath, "", 0, true)
        }
        check(model != 0L) { res.getString(R.string.err_gguf_load, modelFile.name) }

        val info = LlamaNative.modelInfo(model).split('\t')
        hasTemplate = info.getOrNull(5) == "1" || probeTemplate()
        nSwa = LlamaNative.modelNSwa(model)
        val trainCtx = info.getOrNull(3)?.toIntOrNull() ?: 0
        nCtx = if (trainCtx > 0) minOf(context.maxTokens, trainCtx) else context.maxTokens
        val gpu = accelerator != Accelerator.CPU
        val cores = Runtime.getRuntime().availableProcessors()
        threads = if (gpu) 2 else (cores - 2).coerceIn(1, 4)

        // Allocate the first context now so a too-large KV cache fails at load, not on first request.
        slots += Slot(newContext())
        mmprojFile?.let { file ->
            mtmd = LlamaNative.mtmdLoad(model, file.absolutePath, accelerator != Accelerator.CPU, threads)
            check(mtmd != 0L) { res.getString(R.string.err_mmproj_load, file.name, LlamaNative.lastError()) }
            audio = LlamaNative.mtmdSupportsAudio(mtmd)
        }
        val device = info.getOrNull(4) ?: "CPU"
        detail = buildString {
            append(device)
            append(" · ctx $nCtx · ${info.getOrNull(0).orEmpty()}")
            if (kvQ8) append(" · KV q8_0")
            if (mtmd != 0L) append(" · mmproj")
        }
        log("loaded ${modelFile.name}: $detail, $threads threads")
    }

    override val capabilities: Set<Capability>
        get() = buildSet {
            addAll(listOf(Capability.STOP, Capability.PENALTIES, Capability.LOGIT_BIAS, Capability.LOGPROBS, Capability.RAW_COMPLETION))
            if (hasTemplate) addAll(listOf(Capability.JSON_SCHEMA, Capability.TOOLS, Capability.REASONING, Capability.CONTINUE))
            if (mtmd != 0L) {
                add(Capability.IMAGES)
                if (audio) add(Capability.AUDIO)
            }
        }

    /** A rendered request: the chat handle (0 without a template), and its prompt as tokens or media chunks. */
    private class Prepared(val chat: Long, val tokens: IntArray, val chunks: Long, val nPos: Int, val dropped: Int) {
        fun free() {
            if (chat != 0L) LlamaNative.chatFree(chat)
            if (chunks != 0L) LlamaNative.chunksFree(chunks)
        }
    }

    override fun generate(
        messages: List<ChatMessage>,
        params: GenerationParams,
        options: ChatOptions,
        onCache: (CacheResult) -> Unit,
    ): Flow<GenChunk> = flow {
        check(model != 0L) { "model not loaded" }
        val last = messages.lastOrNull()
        require(last?.role == ChatMessage.Role.USER || (options.continueFinal && last?.role == ChatMessage.Role.ASSISTANT)) {
            "the last message must have role 'user'"
        }
        val reserve = context.reserveFor(params.maxTokens).coerceAtMost(nCtx / 2)
        val prepared = fit(messages, options, reserve)
        try {
            run(prepared, messages.size, params, onCache)
        } finally {
            prepared.free()
        }
    }.flowOn(dispatcher)

    override fun complete(prompt: String, params: GenerationParams, onCache: (CacheResult) -> Unit): Flow<GenChunk> = flow {
        check(model != 0L) { "model not loaded" }
        val tokens = LlamaNative.tokenize(model, prompt, true)
        require(tokens.isNotEmpty()) { "prompt is empty" }
        val reserve = context.reserveFor(params.maxTokens).coerceAtMost(nCtx / 2)
        if (tokens.size + reserve > nCtx) throw ContextOverflowException(tokens.size + reserve, nCtx)
        run(Prepared(0L, tokens, 0L, tokens.size, 0), 1, params, onCache)
    }.flowOn(dispatcher)

    /** Prefill (reusing the best slot's KV prefix), then sample until EOG, a stop string or a limit. */
    private suspend fun FlowCollector<GenChunk>.run(
        prepared: Prepared,
        messageCount: Int,
        params: GenerationParams,
        onCache: (CacheResult) -> Unit,
    ) {
        val media = prepared.chunks != 0L
        // With media only the text before the first image/audio chunk can come from the cache.
        val reusable = if (media) LlamaNative.chunksPrefixTokens(prepared.chunks) else prepared.tokens
        val slot = pickSlot(reusable)
        var keep = if (slotCount > 0) commonPrefix(slot.tokens, reusable) else 0
        if (!media && keep >= reusable.size) keep = reusable.size - 1 // at least one token must be decoded for logits
        // Sliding-window layers (Gemma) only keep the last nSwa positions: a cut further back than
        // that has lost KV it needs, so start over (same rule as llama-server).
        if (keep > 0 && nSwa > 0 && LlamaNative.memoryPosMin(slot.ctx) > maxOf(0, keep - nSwa)) keep = 0
        if (!LlamaNative.memoryRemoveFrom(slot.ctx, keep)) {
            keep = 0 // e.g. recurrent models cannot drop a suffix
            LlamaNative.memoryRemoveFrom(slot.ctx, 0)
        }
        slot.tokens = reusable.copyOf(keep)
        if (keep > 0) hits++ else misses++
        onCache(
            CacheResult(
                hit = keep > 0,
                reusedMessages = 0,
                newMessages = messageCount,
                truncatedMessages = prepared.dropped,
                promptTokens = prepared.nPos,
                completionBudget = nCtx - prepared.nPos,
                reusedTokens = keep,
            ),
        )

        val rc = if (media) {
            LlamaNative.chunksEval(mtmd, slot.ctx, prepared.chunks, keep, BATCH)
        } else {
            LlamaNative.decode(slot.ctx, prepared.tokens, keep, prepared.tokens.size - keep, BATCH)
        }
        if (rc != 0) {
            LlamaNative.memoryRemoveFrom(slot.ctx, 0)
            slot.tokens = IntArray(0)
            error("prefill failed (rc=$rc)")
        }
        slot.tokens = reusable
        var pos = prepared.nPos

        val bias = params.logitBias.entries.toList()
        val sampler = LlamaNative.chatSampler(
            model, prepared.chat,
            temperature = (params.temperature ?: 0.8).toFloat(),
            topK = params.topK ?: 40,
            topP = (params.topP ?: 0.95).toFloat(),
            minP = 0.05f,
            frequencyPenalty = (params.frequencyPenalty ?: 0.0).toFloat(),
            presencePenalty = (params.presencePenalty ?: 0.0).toFloat(),
            seed = params.seed ?: Random.nextInt(),
            biasIds = bias.map { it.key }.toIntArray(),
            biasValues = bias.map { it.value }.toFloatArray(),
        )
        require(sampler != 0L) { LlamaNative.lastError().ifBlank { "invalid sampling parameters" } }
        val extraStops = if (prepared.chat != 0L) LlamaNative.chatStops(prepared.chat).toList() else emptyList()
        val stops = StopMatcher(params.stop + extraStops)
        val utf8 = Utf8Assembler()
        fun parse(text: String, partial: Boolean): List<Delta> = when {
            prepared.chat != 0L -> LlamaChat.deltas(LlamaNative.chatUpdate(prepared.chat, text, partial))
            text.isEmpty() -> emptyList()
            else -> listOf(Delta.Content(text))
        }
        var generated = 0
        var finish = "stop"
        try {
            while (true) {
                if (pos >= nCtx || (params.maxTokens != null && generated >= params.maxTokens)) {
                    finish = "length"
                    break
                }
                val token = LlamaNative.samplerSample(sampler, slot.ctx)
                val logprob = params.logprobs?.let { logprobOf(slot.ctx, token, it) }
                if (LlamaNative.isEog(model, token)) break
                generated++
                val safe = stops.push(utf8.push(LlamaNative.tokenPiece(model, token)))
                // Cancellation lands here; the token is not in the KV yet.
                emit(GenChunk(parse(safe, partial = true), tokens = 1, logprobs = listOfNotNull(logprob)))
                if (stops.stopped) break
                val step = LlamaNative.decode(slot.ctx, intArrayOf(token), 0, 1, 1)
                check(step == 0) { "decode failed (llama_decode=$step)" }
                pos++
                if (!media) slot.tokens += token
            }
            val rest = if (stops.stopped) "" else stops.push(utf8.flush()) + stops.flush()
            emit(GenChunk(parse(rest, partial = false), finish = finish))
        } finally {
            LlamaNative.samplerFree(sampler)
            slot.used = ++clock
        }
    }

    private fun logprobOf(ctx: Long, token: Int, nTop: Int): TokenLogprob {
        val packed = LlamaNative.tokenLogprobs(ctx, model, token, nTop)
        fun piece(id: Int) = LlamaNative.tokenPiece(model, id)
        val top = (0 until (packed.size - 1) / 2).map { i ->
            val id = packed[1 + 2 * i].toInt()
            val bytes = piece(id)
            TopLogprob(bytes.decodeToString(), bytes, packed[2 + 2 * i].toDouble())
        }
        val bytes = piece(token)
        return TokenLogprob(bytes.decodeToString(), bytes, packed[0].toDouble(), top)
    }

    /** Exact fitting of the conversation into the window, dropping the oldest turns if allowed. */
    private fun fit(messages: List<ChatMessage>, options: ChatOptions, reserve: Int): Prepared {
        val full = prepare(messages, options, 0)
        if (full.nPos + reserve <= nCtx) return full
        full.free()
        if (context.policy == OverflowPolicy.ERROR) throw ContextOverflowException(full.nPos + reserve, nCtx)

        val system = messages.filter { it.role == ChatMessage.Role.SYSTEM }
        val turns = messages.filter { it.role != ChatMessage.Role.SYSTEM }
        val target = (nCtx * context.lowWatermark).toInt() - reserve
        var start = 0
        var attempt: Prepared? = null
        while (start < turns.lastIndex) {
            start++
            while (start < turns.lastIndex && !turns[start].isUserTurn) start++
            attempt?.free()
            attempt = prepare(system + turns.drop(start), options, start)
            if (attempt.nPos <= target) break
        }
        val fitted = attempt ?: throw ContextOverflowException(full.nPos + reserve, nCtx)
        if (fitted.nPos + reserve > nCtx) {
            fitted.free()
            throw ContextOverflowException(fitted.nPos + reserve, nCtx)
        }
        return fitted
    }

    private fun prepare(messages: List<ChatMessage>, options: ChatOptions, dropped: Int): Prepared {
        val chat = if (hasTemplate) LlamaNative.chatPrepare(model, LlamaChat.request(messages, options)) else 0L
        if (hasTemplate && chat == 0L) throw IllegalArgumentException(LlamaNative.lastError().ifBlank { "invalid chat request" })
        val prompt = if (chat != 0L) LlamaNative.chatPrompt(chat) else chatMlFallback(messages)
        val media = messages.flatMap { it.media }
        if (media.isEmpty()) {
            val tokens = LlamaNative.tokenize(model, prompt, true)
            return Prepared(chat, tokens, 0L, tokens.size, dropped)
        }
        if (mtmd == 0L) {
            if (chat != 0L) LlamaNative.chatFree(chat)
            throw IllegalArgumentException("image/audio input needs a multimodal projector (mmproj) for this model")
        }
        val chunks = LlamaNative.mtmdTokenize(mtmd, prompt, media.toTypedArray())
        if (chunks == 0L) {
            if (chat != 0L) LlamaNative.chatFree(chat)
            throw IllegalArgumentException(LlamaNative.lastError().ifBlank { "media preprocessing failed" })
        }
        return Prepared(chat, IntArray(0), chunks, LlamaNative.chunksNPos(chunks), dropped)
    }

    /** Models without a usable template: ChatML, text only. */
    private fun chatMlFallback(messages: List<ChatMessage>) = buildString {
        messages.forEach {
            val role = when (it.role) {
                ChatMessage.Role.SYSTEM -> "system"
                ChatMessage.Role.USER -> "user"
                ChatMessage.Role.ASSISTANT -> "assistant"
            }
            append("<|im_start|>$role\n${it.text}<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }

    /** Slot with the longest shared prefix; otherwise a new slot, or the least recently used one. */
    private fun pickSlot(prompt: IntArray): Slot {
        val best = slots.maxByOrNull { commonPrefix(it.tokens, prompt) }!!
        if (commonPrefix(best.tokens, prompt) > 0) return best
        val lru = slots.minByOrNull { it.used }!!
        if (slots.size < slotCount.coerceAtLeast(1)) {
            if (memoryLow()) {
                val others = slots.filter { it !== lru }
                others.forEach { LlamaNative.freeContext(it.ctx) }
                slots.removeAll(others)
                memoryEvictions += others.size
                if (others.isNotEmpty()) log("low memory: freed ${others.size} cached context(s)")
                return lru
            }
            return Slot(newContext()).also { slots += it }
        }
        return lru
    }

    private fun newContext(): Long {
        val ctx = LlamaNative.newContext(model, nCtx, BATCH, threads, kvQ8)
        check(ctx != 0L) { res.getString(R.string.err_context, nCtx) }
        return ctx
    }

    private fun commonPrefix(a: IntArray, b: IntArray): Int {
        val n = minOf(a.size, b.size)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    override fun cacheStats() = CacheStats(
        capacity = slotCount.coerceAtLeast(1),
        size = slots.size,
        hits = hits,
        misses = misses,
        tokenFactor = 1.0,
        memoryEvictions = memoryEvictions,
        contextTokens = nCtx,
        overflowPolicy = context.policy,
    )

    /** Models without their own template still get llama.cpp's default (ChatML) if this renders. */
    private fun probeTemplate(): Boolean {
        val chat = LlamaNative.chatPrepare(model, LlamaChat.request(listOf(ChatMessage(ChatMessage.Role.USER, "hi")), ChatOptions()))
        if (chat != 0L) LlamaNative.chatFree(chat)
        return chat != 0L
    }

    override fun close() {
        runBlocking(dispatcher) {
            if (mtmd != 0L) LlamaNative.mtmdFree(mtmd)
            mtmd = 0L
            slots.forEach { LlamaNative.freeContext(it.ctx) }
            slots.clear()
            if (model != 0L) LlamaNative.freeModel(model)
            model = 0L
        }
        dispatcher.close()
    }

    private companion object {
        const val ALL_LAYERS = 999
        const val BATCH = 512
    }
}
