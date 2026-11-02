package node.nveloper.edge.llama

import android.app.Application
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import node.nveloper.edge.server.EmbeddingsNotConfigured
import node.nveloper.edge.settings.BackendKind
import node.nveloper.edge.settings.SettingsStore
import java.io.File

/**
 * Loads the embedding model chosen in the app on the first /v1/embeddings request and keeps it
 * while the choice (and accelerator) stays the same. It follows the chat model's GGUF accelerator;
 * CPU only when that is what the user picked and allowed.
 */
class EmbeddingService(
    private val app: Application,
    private val settings: SettingsStore,
    private val log: (String) -> Unit,
) {
    private val mutex = Mutex()
    private var loaded: LlamaEmbedder? = null
    private var loadedKey: String? = null

    /** Id of the selected embedding model, null when none is selected. */
    val modelId: String?
        get() = settings.current.embeddingModelPath.takeIf { it.isNotBlank() && File(it).isFile }?.let { File(it).nameWithoutExtension }

    suspend fun get(): LlamaEmbedder = mutex.withLock {
        val s = settings.current
        val file = File(s.embeddingModelPath)
        if (s.embeddingModelPath.isBlank() || !file.isFile) throw EmbeddingsNotConfigured()
        val accelerator = when {
            s.backend == BackendKind.LLAMA_OPENCL -> LlamaCppBackend.Accelerator.OPENCL
            s.backend == BackendKind.LLAMA_CPU && s.allowCpu -> LlamaCppBackend.Accelerator.CPU
            else -> LlamaCppBackend.Accelerator.VULKAN
        }
        val key = "${file.absolutePath}|$accelerator"
        loaded?.let { if (loadedKey == key) return it }
        unloadLocked()
        val embedder = LlamaEmbedder(file, accelerator, app.applicationInfo.nativeLibraryDir, app.resources, log)
        try {
            embedder.load()
        } catch (t: Throwable) {
            embedder.close()
            throw t
        }
        loaded = embedder
        loadedKey = key
        embedder
    }

    /** Frees the model (e.g. when the selection changes). */
    suspend fun unload() = mutex.withLock { unloadLocked() }

    private fun unloadLocked() {
        loaded?.close()
        loaded = null
        loadedKey = null
    }
}
