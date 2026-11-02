package node.nveloper.edge

import android.app.Application
import node.nveloper.edge.inference.InferenceBackend
import node.nveloper.edge.inference.InferenceManager
import node.nveloper.edge.inference.LiteRtLmBackend
import node.nveloper.edge.inference.MockBackend
import node.nveloper.edge.llama.EmbeddingService
import node.nveloper.edge.llama.LlamaCppBackend
import node.nveloper.edge.log.EventLog
import node.nveloper.edge.models.ModelStore
import node.nveloper.edge.models.SamplerDefaults
import node.nveloper.edge.server.OpenAiServer
import node.nveloper.edge.service.ServerKeeper
import node.nveloper.edge.settings.BackendKind
import node.nveloper.edge.settings.Settings
import node.nveloper.edge.settings.SettingsStore
import node.nveloper.edge.system.DeviceMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Process-wide singletons. The service and the UI both talk to the same instances. */
class AppContainer(private val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val log = EventLog()
    val settings = SettingsStore(app)
    val device = DeviceMonitor(app)
    val models = ModelStore(app, log, appScope)
    val inference = InferenceManager(log) { phase ->
        ServerKeeper.setPhase(app, phase?.let { "$it · ${settings.current.backend} · ${File(settings.current.modelPath).name}" })
    }
    val server = OpenAiServer(this)
    val embeddings = EmbeddingService(app, settings) { log.i("embed", it) }

    /**
     * Makes [file] the served model and loads it. GGUF goes through llama.cpp, `.litertlm` through
     * LiteRT-LM, on the GPU unless the user allowed CPU and picked it for this format.
     */
    fun useModel(file: File): Job {
        settings.update {
            val keep = it.allowCpu || !it.backend.isCpu
            val backend = if (file.extension == "gguf") {
                if (it.backend.isLlama && keep) it.backend else BackendKind.LLAMA_VULKAN
            } else {
                if (it.backend.isLiteRt && keep) it.backend else BackendKind.LITERT_GPU
            }
            it.copy(backend = backend, modelPath = file.absolutePath)
        }
        return reloadBackend()
    }

    fun useMock(): Job {
        settings.update { it.copy(backend = BackendKind.MOCK) }
        return reloadBackend()
    }

    fun reloadBackend(): Job {
        val s = settings.current
        return appScope.launch {
            inference.load(describe(s)) { createBackend(s) }
            // Headless (auto restart, boot): the dialog can't show, so tell the user to choose.
            (inference.state.value as? InferenceManager.State.Failed)?.gpu?.let { ServerKeeper.notifyGpuFailure(app, it) }
        }
    }

    fun ensureBackendLoaded() {
        when (inference.state.value) {
            is InferenceManager.State.Empty, is InferenceManager.State.Failed -> reloadBackend()
            else -> Unit
        }
    }

    private fun describe(s: Settings): String = when (s.backend) {
        BackendKind.MOCK -> "Mock ${s.mockTokensPerSecond.toInt()} tok/s"
        BackendKind.LITERT_GPU -> "GPU"
        BackendKind.LITERT_CPU -> "CPU"
        BackendKind.LLAMA_VULKAN -> "Vulkan"
        BackendKind.LLAMA_OPENCL -> "OpenCL"
        BackendKind.LLAMA_CPU -> "CPU"
    } + " · ctx ${effectiveContext(s).maxTokens} · cache ${s.cacheSlots}"

    /** Human name for a loaded model id (the file name without extension). */
    fun displayName(modelId: String): String =
        models.catalog.firstOrNull { it.fileName.substringBeforeLast('.') == modelId }?.name ?: modelId

    /** The configured context, capped at what the selected model supports. */
    private fun effectiveContext(s: Settings) = s.context.let { ctx ->
        val limit = if (s.backend == BackendKind.MOCK) null else models.catalogFor(File(s.modelPath))?.contextLength
        if (limit != null && limit < ctx.maxTokens) ctx.copy(maxTokens = limit) else ctx
    }

    private fun createBackend(s: Settings): InferenceBackend {
        require(s.allowCpu || !s.backend.isCpu) { app.getString(R.string.err_cpu_disabled) }
        return createBackendFor(s)
    }

    private fun createBackendFor(s: Settings): InferenceBackend = when (s.backend) {
        BackendKind.LLAMA_VULKAN, BackendKind.LLAMA_OPENCL, BackendKind.LLAMA_CPU -> {
            val file = File(s.modelPath)
            require(file.isFile && file.extension == "gguf") {
                app.getString(R.string.err_no_gguf)
            }
            val accelerator = when (s.backend) {
                BackendKind.LLAMA_VULKAN -> LlamaCppBackend.Accelerator.VULKAN
                BackendKind.LLAMA_OPENCL -> LlamaCppBackend.Accelerator.OPENCL
                else -> LlamaCppBackend.Accelerator.CPU
            }
            LlamaCppBackend(
                file, accelerator, app.applicationInfo.nativeLibraryDir, s.cacheSlots, s.context, app.resources,
                mmprojFile = s.mmproj[file.absolutePath]?.let(::File)?.takeIf { it.isFile },
                kvQ8 = s.kvQ8,
                memoryLow = device::memoryLow, log = { log.i("llama", it) },
            )
        }
        BackendKind.MOCK -> MockBackend(
            s.mockTokensPerSecond, s.cacheSlots, s.context,
            memoryLow = device::memoryLow, log = { log.w("cache", it) },
        )
        BackendKind.LITERT_CPU, BackendKind.LITERT_GPU -> {
            val file = File(s.modelPath)
            require(file.isFile && file.extension == "litertlm") {
                app.getString(R.string.err_no_model)
            }
            val accelerator = if (s.backend == BackendKind.LITERT_GPU) {
                LiteRtLmBackend.Accelerator.GPU
            } else {
                LiteRtLmBackend.Accelerator.CPU
            }
            val sampler = models.catalogFor(file)?.sampler ?: SamplerDefaults.GEMMA
            LiteRtLmBackend(
                file, accelerator, app.cacheDir, s.cacheSlots, effectiveContext(s), sampler,
                memoryLow = device::memoryLow, log = { log.w("cache", it) },
            )
        }
    }
}
