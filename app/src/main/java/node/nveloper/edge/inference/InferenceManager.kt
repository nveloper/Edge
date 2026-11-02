package node.nveloper.edge.inference

import android.os.SystemClock
import node.nveloper.edge.log.EventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the loaded backend and serializes generation: a phone can only run one decode at a time,
 * so extra requests wait in a bounded queue and anything beyond it is rejected.
 */
class InferenceManager(
    private val log: EventLog,
    /** Breadcrumb for the crash-loop guard: "load" / "generate" while native code runs, null after. */
    private val onPhase: (String?) -> Unit = {},
) {
    sealed interface State {
        data object Empty : State
        data class Loading(val description: String) : State
        data class Ready(val modelId: String, val description: String, val loadMs: Long) : State
        /** [gpu] is set when the model could not be put on the GPU (the UI then asks what to do). */
        data class Failed(val description: String, val error: String, val gpu: GpuLoadException? = null) : State
    }

    data class Stats(
        val completed: Long = 0,
        val failed: Long = 0,
        val rejected: Long = 0,
        val totalTokens: Long = 0,
        val lastTokens: Int = 0,
        val lastTtftMs: Long = 0,
        val lastTokensPerSecond: Double = 0.0,
        val lastCache: CacheResult? = null,
    )

    class BusyException : Exception("queue is full")
    class NotReadyException(message: String) : Exception(message)

    private val _state = MutableStateFlow<State>(State.Empty)
    val state: StateFlow<State> = _state

    /** Requests currently generating or waiting. */
    private val _active = MutableStateFlow(0)
    val active: StateFlow<Int> = _active

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    private val loadMutex = Mutex()
    private val runMutex = Mutex()

    @Volatile
    private var backend: InferenceBackend? = null

    suspend fun load(description: String, factory: () -> InferenceBackend) = loadMutex.withLock {
        _state.value = State.Loading(description)
        log.i(TAG, "loading $description")
        // Let an in-flight generation finish before tearing the old model down.
        runMutex.withLock {
            backend?.close()
            backend = null
        }
        val start = SystemClock.elapsedRealtime()
        try {
            val next = factory()
            onPhase("load")
            try {
                next.load()
            } finally {
                onPhase(null)
            }
            backend = next
            val ms = SystemClock.elapsedRealtime() - start
            _state.value = State.Ready(next.modelId, next.detail ?: description, ms)
            log.i(TAG, "ready: ${next.modelId} in ${ms}ms")
        } catch (t: Throwable) {
            _state.value = State.Failed(description, t.message ?: t.javaClass.simpleName, t as? GpuLoadException)
            log.e(TAG, "load failed for $description", t)
        }
    }

    suspend fun unload() = loadMutex.withLock {
        runMutex.withLock {
            backend?.close()
            backend = null
        }
        _state.value = State.Empty
    }

    /**
     * Runs [block] with exclusive use of the backend.
     * @throws BusyException when more than [maxQueue] requests are already waiting.
     * @throws NotReadyException when no model is loaded.
     */
    suspend fun <T> runExclusive(maxQueue: Int, block: suspend (InferenceBackend) -> T): T = exclusive(maxQueue) {
        val b = backend ?: throw NotReadyException(notReadyReason())
        onPhase("generate")
        try {
            block(b)
        } finally {
            onPhase(null)
        }
    }

    /** Runs [block] in the same queue as generation (the GPU does one thing at a time), loaded model or not. */
    suspend fun <T> exclusive(maxQueue: Int, block: suspend () -> T): T {
        val inFlight = _active.updateAndGet { it + 1 }
        try {
            if (inFlight > maxQueue + 1) {
                _stats.update { it.copy(rejected = it.rejected + 1) }
                throw BusyException()
            }
            return runMutex.withLock { block() }
        } finally {
            _active.update { it - 1 }
        }
    }

    fun recordSuccess(tokens: Int, ttftMs: Long, tokensPerSecond: Double, cache: CacheResult?) = _stats.update {
        it.copy(
            completed = it.completed + 1,
            totalTokens = it.totalTokens + tokens,
            lastTokens = tokens,
            lastTtftMs = ttftMs,
            lastTokensPerSecond = tokensPerSecond,
            lastCache = cache,
        )
    }

    /** Null when no model is loaded. */
    fun cacheStats(): CacheStats? = backend?.cacheStats()

    /** Null when no model is loaded. */
    fun capabilities(): Set<Capability>? = backend?.capabilities

    fun recordFailure() = _stats.update { it.copy(failed = it.failed + 1) }

    private fun notReadyReason(): String = when (val s = _state.value) {
        State.Empty -> "no model loaded"
        is State.Loading -> "model is loading: ${s.description}"
        is State.Failed -> "model failed to load: ${s.error}"
        is State.Ready -> "model not available"
    }

    private companion object {
        const val TAG = "inference"
    }
}
