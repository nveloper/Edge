package node.nveloper.edge.settings

import android.content.Context
import android.os.PowerManager
import node.nveloper.edge.inference.ContextConfig
import node.nveloper.edge.inference.OverflowPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class BackendKind {
    MOCK,
    LITERT_CPU,
    LITERT_GPU,
    LLAMA_VULKAN,
    LLAMA_OPENCL,
    LLAMA_CPU;

    val isLlama: Boolean get() = this == LLAMA_VULKAN || this == LLAMA_OPENCL || this == LLAMA_CPU
    val isLiteRt: Boolean get() = this == LITERT_CPU || this == LITERT_GPU
    val isCpu: Boolean get() = this == LITERT_CPU || this == LLAMA_CPU
}

enum class WakeLockMode {
    ALWAYS,
    DURING_REQUEST,
}

data class Settings(
    val port: Int = 8080,
    val apiKey: String = "",
    /** Hugging Face access token, only needed for gated models (Gemma 3n, Gemma 3 1B). */
    val hfToken: String = "",
    val backend: BackendKind = BackendKind.MOCK,
    val modelPath: String = "",
    /** Default mirrors a typical phone-CPU decode rate (~7 tok/s). */
    val mockTokensPerSecond: Float = 7f,
    /** Conversations kept alive for KV-cache reuse across requests (0 disables reuse). */
    val cacheSlots: Int = 3,
    /** Context window (prompt + reply tokens) the engine is created with. */
    val contextTokens: Int = 4096,
    val overflowPolicy: OverflowPolicy = OverflowPolicy.TRUNCATE_OLDEST,
    /** Requests allowed to wait while one is generating. */
    val maxQueue: Int = 4,
    val wakeLockMode: WakeLockMode = WakeLockMode.ALWAYS,
    /** Bring the server back after a reboot, an app update or an OS kill (see ServerKeeper). */
    val autoRestart: Boolean = true,
    /** Same-cause crashes within an hour before auto restart is suspended. */
    val crashLimit: Int = 3,
    /** CPU inference is opt-in: off means GPU only, with no CPU fallback. */
    val allowCpu: Boolean = false,
    /** Q8_0 KV cache for GGUF (Vulkan/CPU), about half the memory of F16. */
    val kvQ8: Boolean = false,
    /** GGUF embedding model for /v1/embeddings, empty for none. */
    val embeddingModelPath: String = "",
    /** Multimodal projector (mmproj) chosen for each GGUF model, by model path. */
    val mmproj: Map<String, String> = emptyMap(),
    /** Reject new requests with 503 at or above this PowerManager.THERMAL_STATUS_* level. */
    val rejectAtThermalStatus: Int = PowerManager.THERMAL_STATUS_SEVERE,
) {
    val context: ContextConfig get() = ContextConfig(maxTokens = contextTokens, policy = overflowPolicy)

    companion object {
        val CONTEXT_SIZES = listOf(1024, 2048, 4096, 8192, 16384, 32768)
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(read())
    val flow: StateFlow<Settings> = _flow
    val current: Settings get() = _flow.value

    fun update(transform: (Settings) -> Settings) {
        write(_flow.updateAndGet(transform))
    }

    private fun read(): Settings {
        val d = Settings()
        return Settings(
            port = prefs.getInt("port", d.port),
            apiKey = prefs.getString("apiKey", d.apiKey).orEmpty(),
            hfToken = prefs.getString("hfToken", d.hfToken).orEmpty(),
            backend = enumOr(prefs.getString("backend", null), d.backend),
            modelPath = prefs.getString("modelPath", d.modelPath).orEmpty(),
            mockTokensPerSecond = prefs.getFloat("mockTps", d.mockTokensPerSecond),
            cacheSlots = prefs.getInt("cacheSlots", d.cacheSlots),
            contextTokens = prefs.getInt("contextTokens", d.contextTokens),
            overflowPolicy = enumOr(prefs.getString("overflowPolicy", null), d.overflowPolicy),
            maxQueue = prefs.getInt("maxQueue", d.maxQueue),
            wakeLockMode = enumOr(prefs.getString("wakeLockMode", null), d.wakeLockMode),
            autoRestart = prefs.getBoolean("autoRestart", d.autoRestart),
            crashLimit = prefs.getInt("crashLimit", d.crashLimit),
            allowCpu = prefs.getBoolean("allowCpu", d.allowCpu),
            kvQ8 = prefs.getBoolean("kvQ8", d.kvQ8),
            embeddingModelPath = prefs.getString("embeddingModelPath", d.embeddingModelPath).orEmpty(),
            mmproj = prefs.getString("mmproj", null)?.let { decodeMap(it) } ?: d.mmproj,
            rejectAtThermalStatus = prefs.getInt("rejectAtThermal", d.rejectAtThermalStatus),
        )
    }

    private fun write(s: Settings) {
        prefs.edit()
            .putInt("port", s.port)
            .putString("apiKey", s.apiKey)
            .putString("hfToken", s.hfToken)
            .putString("backend", s.backend.name)
            .putString("modelPath", s.modelPath)
            .putFloat("mockTps", s.mockTokensPerSecond)
            .putInt("cacheSlots", s.cacheSlots)
            .putInt("contextTokens", s.contextTokens)
            .putString("overflowPolicy", s.overflowPolicy.name)
            .putInt("maxQueue", s.maxQueue)
            .putString("wakeLockMode", s.wakeLockMode.name)
            .putBoolean("autoRestart", s.autoRestart)
            .putInt("crashLimit", s.crashLimit)
            .putBoolean("allowCpu", s.allowCpu)
            .putBoolean("kvQ8", s.kvQ8)
            .putString("embeddingModelPath", s.embeddingModelPath)
            .putString("mmproj", encodeMap(s.mmproj))
            .putInt("rejectAtThermal", s.rejectAtThermalStatus)
            .apply()
    }

    private fun encodeMap(map: Map<String, String>) = JsonObject(map.mapValues { JsonPrimitive(it.value) }).toString()

    private fun decodeMap(text: String): Map<String, String> = runCatching {
        Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }.getOrDefault(emptyMap())

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
