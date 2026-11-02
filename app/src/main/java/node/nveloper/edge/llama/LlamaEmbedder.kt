package node.nveloper.edge.llama

import android.content.res.Resources
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import node.nveloper.edge.R
import node.nveloper.edge.inference.GpuLoadException
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * A GGUF embedding model (nomic-embed, bge, e5, …) for /v1/embeddings, loaded next to the chat
 * model. Same accelerator rules as chat: GPU, CPU only when chosen explicitly.
 */
class LlamaEmbedder(
    private val modelFile: File,
    private val accelerator: LlamaCppBackend.Accelerator,
    private val nativeLibDir: String,
    private val res: Resources,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    val modelId: String = modelFile.nameWithoutExtension

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "llama-embed").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private var model = 0L
    private var ctx = 0L

    /** Longest input in tokens. */
    var maxTokens = 0
        private set

    suspend fun load() = withContext(dispatcher) {
        LlamaNative.ensureInit(nativeLibDir)
        val gpu = accelerator != LlamaCppBackend.Accelerator.CPU
        model = LlamaNative.loadModel(modelFile.absolutePath, accelerator.ggmlBackend, if (gpu) 999 else 0, true)
        if (model == 0L) {
            if (gpu) throw GpuLoadException(accelerator.label, LlamaNative.lastError().ifBlank { res.getString(R.string.err_device_failed, accelerator.label) })
            error(res.getString(R.string.err_gguf_load, modelFile.name))
        }
        val trainCtx = LlamaNative.modelInfo(model).split('\t').getOrNull(3)?.toIntOrNull() ?: 0
        maxTokens = if (trainCtx > 0) minOf(trainCtx, MAX_CTX) else 2048
        ctx = LlamaNative.newEmbeddingContext(model, maxTokens, if (gpu) 2 else 4)
        check(ctx != 0L) { res.getString(R.string.err_context, maxTokens) }
        log("embedding model ${modelFile.name} loaded (max $maxTokens tokens)")
    }

    fun tokenize(text: String): IntArray = runBlocking(dispatcher) { LlamaNative.tokenize(model, text, true) }

    /** L2-normalised embedding of [tokens], optionally truncated to [dimensions] (Matryoshka models). */
    suspend fun embed(tokens: IntArray, dimensions: Int?): FloatArray = withContext(dispatcher) {
        require(tokens.isNotEmpty()) { "input is empty" }
        require(tokens.size <= maxTokens) { "input is ${tokens.size} tokens; this embedding model accepts at most $maxTokens" }
        val raw = LlamaNative.embed(ctx, model, tokens) ?: error("embedding failed")
        val cut = if (dimensions != null && dimensions in 1 until raw.size) raw.copyOf(dimensions) else raw
        val norm = sqrt(cut.sumOf { it.toDouble() * it }).toFloat().takeIf { it > 0f } ?: 1f
        FloatArray(cut.size) { cut[it] / norm }
    }

    override fun close() {
        runBlocking(dispatcher) {
            if (ctx != 0L) LlamaNative.freeContext(ctx)
            if (model != 0L) LlamaNative.freeModel(model)
            ctx = 0L
            model = 0L
        }
        dispatcher.close()
    }

    private companion object {
        const val MAX_CTX = 8192
    }
}
