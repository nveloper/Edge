package node.nveloper.edge.llama

/**
 * JNI surface of app/src/main/cpp/edge_llama.cpp (llama.cpp). Handles are raw pointers.
 * Not thread-safe: [LlamaCppBackend] calls everything from one dedicated thread.
 */
object LlamaNative {
    init {
        System.loadLibrary("edge_llama")
    }

    @Volatile
    private var initialized = false

    /** Loads the ggml backends (Vulkan, OpenCL, CPU variants) shipped in [nativeLibDir], once. */
    @Synchronized
    fun ensureInit(nativeLibDir: String) {
        if (!initialized) {
            init(nativeLibDir)
            initialized = true
        }
    }

    fun deviceList(): List<GgmlDevice> = devices().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 6) null else GgmlDevice(f[0], f[1], f[2], f[3], f[4].toLongOrNull() ?: 0, f[5].toLongOrNull() ?: 0)
    }

    private external fun init(nativeLibDir: String)
    private external fun devices(): Array<String>

    /** [backend] is "Vulkan", "OpenCL" or "" for CPU only. Returns 0 on failure. */
    external fun loadModel(path: String, backend: String, nGpuLayers: Int, useMmap: Boolean): Long

    /** What llama.cpp last logged as an error, empty if nothing; used to explain failed loads. */
    external fun lastError(): String
    external fun freeModel(model: Long)
    external fun modelInfo(model: Long): String
    external fun applyTemplate(model: Long, roles: Array<String>, contents: Array<String>, addAssistant: Boolean): String?
    external fun tokenize(model: Long, text: String, addSpecial: Boolean): IntArray

    /** [kvQ8]: Q8_0 KV cache (half the memory of F16). */
    external fun newContext(model: Long, nCtx: Int, nBatch: Int, nThreads: Int, kvQ8: Boolean): Long
    external fun freeContext(ctx: Long)
    external fun memoryRemoveFrom(ctx: Long, pos: Int): Boolean
    external fun memoryPosMin(ctx: Long): Int
    external fun modelNSwa(model: Long): Int
    external fun decode(ctx: Long, tokens: IntArray, offset: Int, count: Int, nBatch: Int): Int

    // Chat requests (edge_chat.cpp): template + tools/schema/reasoning, parsing of the output.
    /** Renders an OpenAI chat request (see [LlamaChat.request]); 0 on invalid input ([lastError] says why). */
    external fun chatPrepare(model: Long, request: String): Long
    external fun chatFree(chat: Long)
    external fun chatPrompt(chat: Long): String
    external fun chatStops(chat: Long): Array<String>
    /** Feeds generated text; returns the new message deltas as a JSON array. */
    external fun chatUpdate(chat: Long, text: String, partial: Boolean): String

    /** common_sampler with the request's grammar (from [chat], 0 for none); 0 on failure. */
    external fun chatSampler(
        model: Long, chat: Long, temperature: Float, topK: Int, topP: Float, minP: Float,
        frequencyPenalty: Float, presencePenalty: Float, seed: Int, biasIds: IntArray, biasValues: FloatArray,
    ): Long
    external fun samplerFree(sampler: Long)
    /** Samples at the last decoded position and accepts the token. */
    external fun samplerSample(sampler: Long, ctx: Long): Int
    /** [logprob(token), id1, lp1, id2, lp2, …] over the raw logits at the last position. */
    external fun tokenLogprobs(ctx: Long, model: Long, token: Int, nTop: Int): FloatArray

    // Multimodal input through an mmproj file.
    external fun mediaMarker(): String
    external fun mtmdLoad(model: Long, path: String, useGpu: Boolean, threads: Int): Long
    external fun mtmdFree(mtmd: Long)
    external fun mtmdSupportsAudio(mtmd: Long): Boolean
    /** Prompt with media markers + media bytes in marker order → chunks handle, 0 on failure. */
    external fun mtmdTokenize(mtmd: Long, prompt: String, media: Array<ByteArray>): Long
    external fun chunksFree(chunks: Long)
    external fun chunksPrefixTokens(chunks: Long): IntArray
    external fun chunksNPos(chunks: Long): Int
    external fun chunksEval(mtmd: Long, ctx: Long, chunks: Long, keep: Int, nBatch: Int): Int

    // Embeddings.
    external fun newEmbeddingContext(model: Long, nCtx: Int, nThreads: Int): Long
    external fun embed(ctx: Long, model: Long, tokens: IntArray): FloatArray?
    external fun isEog(model: Long, token: Int): Boolean
    external fun tokenPiece(model: Long, token: Int): ByteArray
}

data class GgmlDevice(
    val backend: String,
    val name: String,
    val type: String,
    val description: String,
    val freeBytes: Long,
    val totalBytes: Long,
)
