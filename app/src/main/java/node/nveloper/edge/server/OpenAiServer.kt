package node.nveloper.edge.server

import android.os.SystemClock
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import node.nveloper.edge.AppContainer
import node.nveloper.edge.inference.CacheResult
import node.nveloper.edge.inference.Capability
import node.nveloper.edge.inference.ContextOverflowException
import node.nveloper.edge.inference.Delta
import node.nveloper.edge.inference.GenChunk
import node.nveloper.edge.inference.GpuLoadException
import node.nveloper.edge.inference.InferenceBackend
import node.nveloper.edge.inference.InferenceManager
import node.nveloper.edge.inference.TokenLogprob
import node.nveloper.edge.system.DeviceMonitor
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.UUID

/**
 * OpenAI-compatible HTTP API:
 *
 *  GET  /health               llama-server style readiness (200 ok / 503 loading)
 *  GET  /v1/models
 *  POST /v1/chat/completions  stream / non-stream, n, stop, penalties, logit_bias, logprobs,
 *                             response_format, tools, reasoning_content, images/audio, continuation
 *  POST /v1/completions       raw text completion
 *  POST /v1/embeddings        with an embedding model selected in the app
 *  GET  /debug/status         server, model, queue, device and thermal state
 *
 * What a request needs is checked against the loaded backend's capabilities up front (400).
 */
class OpenAiServer(private val container: AppContainer) {
    private var server: EmbeddedServer<*, *>? = null
    private var startedAtMs = 0L
    private var port = 0

    val isRunning: Boolean get() = server != null

    @Synchronized
    fun start(port: Int) {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") { configure() }.start(wait = false)
        this.port = port
        startedAtMs = SystemClock.elapsedRealtime()
        container.log.i(TAG, "listening on 0.0.0.0:$port")
    }

    @Synchronized
    fun stop() {
        val s = server ?: return
        server = null
        s.stop(gracePeriodMillis = 200, timeoutMillis = 1_000)
        container.log.i(TAG, "stopped")
    }

    private fun Application.configure() {
        intercept(ApplicationCallPipeline.Monitoring) {
            val start = SystemClock.elapsedRealtime()
            try {
                proceed()
            } finally {
                val ms = SystemClock.elapsedRealtime() - start
                val status = call.response.status()?.value ?: "-"
                container.log.i(
                    "http",
                    "${call.request.httpMethod.value} ${call.request.path()} → $status (${ms}ms) from ${call.request.origin.remoteHost}",
                )
            }
        }

        intercept(ApplicationCallPipeline.Plugins) {
            val key = container.settings.current.apiKey
            if (key.isNotBlank() && call.request.path().startsWith("/v1")) {
                if (call.request.headers[HttpHeaders.Authorization] != "Bearer $key") {
                    call.respondError(HttpStatusCode.Unauthorized, "invalid or missing API key", "invalid_api_key")
                    finish()
                }
            }
        }

        routing {
            get("/") { call.respondText("Edge is running. Try GET /v1/models or /debug/status\n") }
            get("/health") { handleHealth(call) }
            get("/v1/models") { handleModels(call) }
            post("/v1/chat/completions") { handleChat(call) }
            post("/v1/completions") { handleCompletion(call) }
            post("/v1/embeddings") { handleEmbeddings(call) }
            get("/debug/status") { handleStatus(call) }
        }
    }

    private suspend fun handleHealth(call: ApplicationCall) {
        val ready = container.inference.state.value is InferenceManager.State.Ready
        val body = buildJsonObject { put("status", if (ready) "ok" else "loading model") }
        call.respondJson(body, if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable)
    }

    private suspend fun handleModels(call: ApplicationCall) {
        val state = container.inference.state.value
        call.respondJson(buildJsonObject {
            put("object", "list")
            putJsonArray("data") {
                if (state is InferenceManager.State.Ready) addJsonObject { model(state.modelId) }
                container.embeddings.modelId?.let { id -> addJsonObject { model(id) } }
            }
        })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.model(id: String) {
        put("id", id)
        put("object", "model")
        put("created", 0)
        put("owned_by", "edge")
    }

    private suspend fun handleStatus(call: ApplicationCall) {
        val s = container.settings.current
        val stats = container.inference.stats.value
        call.respondJson(buildJsonObject {
            putJsonObject("server") {
                put("port", port)
                put("uptime_s", (SystemClock.elapsedRealtime() - startedAtMs) / 1000)
                put("auth_enabled", s.apiKey.isNotBlank())
                put("max_queue", s.maxQueue)
                put("wake_lock_mode", s.wakeLockMode.name)
                putJsonArray("ipv4") { DeviceMonitor.ipv4Addresses().forEach { add(JsonPrimitive(it)) } }
            }
            putJsonObject("model") {
                when (val st = container.inference.state.value) {
                    InferenceManager.State.Empty -> put("state", "empty")
                    is InferenceManager.State.Loading -> { put("state", "loading"); put("description", st.description) }
                    is InferenceManager.State.Ready -> {
                        put("state", "ready"); put("id", st.modelId)
                        put("description", st.description); put("load_ms", st.loadMs)
                    }
                    is InferenceManager.State.Failed -> {
                        put("state", "failed"); put("description", st.description); put("error", st.error)
                    }
                }
                container.inference.capabilities()?.let { caps -> putJsonArray("capabilities") { caps.forEach { add(JsonPrimitive(it.name.lowercase())) } } }
            }
            container.embeddings.modelId?.let { putJsonObject("embedding_model") { put("id", it) } }
            putJsonObject("requests") {
                put("in_flight", container.inference.active.value)
                put("completed", stats.completed)
                put("failed", stats.failed)
                put("rejected", stats.rejected)
                put("total_tokens", stats.totalTokens)
                put("last_tokens", stats.lastTokens)
                put("last_ttft_ms", stats.lastTtftMs)
                put("last_tokens_per_second", stats.lastTokensPerSecond)
                put("last_cache", stats.lastCache?.let { if (it.hit) "hit" else "miss" })
            }
            container.inference.cacheStats()?.let { c ->
                putJsonObject("conversation_cache") {
                    put("slots", c.capacity)
                    put("used", c.size)
                    put("hits", c.hits)
                    put("misses", c.misses)
                    put("memory_evictions", c.memoryEvictions)
                }
                putJsonObject("context") {
                    put("max_tokens", c.contextTokens)
                    put("overflow_policy", c.overflowPolicy.name)
                    put("token_estimate_factor", c.tokenFactor)
                }
            }
            put("device", container.device.snapshot().toJson())
        })
    }

    // ---- /v1/chat/completions ----

    private suspend fun handleChat(call: ApplicationCall) {
        val body = receiveJson(call) ?: return
        val parsed = try {
            withContext(Dispatchers.IO) { RequestParser.chat(body, MEDIA_MARKER, ::fetchMedia) }
        } catch (e: BadRequest) {
            return call.respondError(HttpStatusCode.BadRequest, e.message ?: "invalid request")
        }
        if (rejectIfHot(call)) return
        val created = System.currentTimeMillis() / 1000
        val id = newId("chatcmpl-")
        runGeneration(call, parsed.required) { backend ->
            val choices = (0 until parsed.n).map { i ->
                Choice(i) { meter ->
                    val params = parsed.params.copy(seed = parsed.params.seed?.plus(i))
                    limited(backend.generate(parsed.messages, params, parsed.options) { meter.cache = it }, params.maxTokens, meter)
                }
            }
            if (parsed.stream) {
                val usage = if (parsed.includeUsage) usageEvent(id, "chat.completion.chunk", created, backend.modelId) else null
                stream(call, choices, usage) { send, choice, chunk, state -> sendChatChunk(send, id, created, backend.modelId, choice, chunk, state) }
            } else {
                full(call, choices) { results ->
                    buildJsonObject {
                        put("id", id)
                        put("object", "chat.completion")
                        put("created", created)
                        put("model", backend.modelId)
                        putJsonArray("choices") { results.forEach { add(it.chatChoice()) } }
                        put("usage", usage(results.map { it.meter }))
                        results.firstOrNull()?.let { put(EXTRAS_KEY, extras(it.meter)) }
                    }
                }
            }
        }
    }

    /** One SSE event per delta; the role goes first and finish_reason last for each choice. */
    private fun sendChatChunk(
        send: (JsonElement) -> Unit,
        id: String,
        created: Long,
        model: String,
        choice: Choice,
        chunk: GenChunk,
        state: ChoiceState,
    ) {
        fun event(delta: JsonObject, finish: String? = null, logprobs: List<TokenLogprob> = emptyList(), extras: JsonObject? = null) =
            send(buildJsonObject {
                put("id", id)
                put("object", "chat.completion.chunk")
                put("created", created)
                put("model", model)
                putJsonArray("choices") {
                    addJsonObject {
                        put("index", choice.index)
                        put("delta", delta)
                        put("logprobs", if (logprobs.isEmpty()) JsonNull else chatLogprobs(logprobs))
                        put("finish_reason", finish?.let { JsonPrimitive(it) } ?: JsonNull)
                    }
                }
                extras?.let { put(EXTRAS_KEY, it) }
            })

        if (!state.started) {
            state.started = true
            event(buildJsonObject { put("role", "assistant"); put("content", "") })
        }
        state.pendingLogprobs += chunk.logprobs
        for (delta in chunk.deltas) {
            if (delta is Delta.ToolCall) state.toolCalls = true
            event(deltaJson(delta), logprobs = state.pendingLogprobs.toList())
            state.pendingLogprobs.clear()
        }
        chunk.finish?.let { finish ->
            event(JsonObject(emptyMap()), if (state.toolCalls) "tool_calls" else finish, state.pendingLogprobs.toList(), extras(choice.meter))
            state.pendingLogprobs.clear()
        }
    }

    private fun deltaJson(delta: Delta): JsonObject = when (delta) {
        is Delta.Content -> buildJsonObject { put("content", delta.text) }
        is Delta.Reasoning -> buildJsonObject { put("reasoning_content", delta.text) }
        is Delta.ToolCall -> buildJsonObject {
            putJsonArray("tool_calls") {
                addJsonObject {
                    put("index", delta.index)
                    delta.id?.let { put("id", it); put("type", "function") }
                    putJsonObject("function") {
                        delta.name?.let { put("name", it) }
                        put("arguments", delta.arguments)
                    }
                }
            }
        }
    }

    // ---- /v1/completions ----

    private suspend fun handleCompletion(call: ApplicationCall) {
        val body = receiveJson(call) ?: return
        val parsed = try {
            RequestParser.completion(body)
        } catch (e: BadRequest) {
            return call.respondError(HttpStatusCode.BadRequest, e.message ?: "invalid request")
        }
        if (rejectIfHot(call)) return
        val created = System.currentTimeMillis() / 1000
        val id = newId("cmpl-")
        runGeneration(call, parsed.required) { backend ->
            val choices = parsed.prompts.flatMapIndexed { p, prompt ->
                (0 until parsed.n).map { j ->
                    Choice(p * parsed.n + j, prompt) { meter ->
                        val params = parsed.params.copy(seed = parsed.params.seed?.plus(j))
                        limited(backend.complete(prompt, params) { meter.cache = it }, params.maxTokens, meter)
                    }
                }
            }
            fun choiceJson(index: Int, text: String, logprobs: JsonElement, finish: String?) = buildJsonObject {
                put("text", text)
                put("index", index)
                put("logprobs", logprobs)
                put("finish_reason", finish?.let { JsonPrimitive(it) } ?: JsonNull)
            }
            fun envelope(choice: JsonObject) = buildJsonObject {
                put("id", id)
                put("object", "text_completion")
                put("created", created)
                put("model", backend.modelId)
                putJsonArray("choices") { add(choice) }
            }
            if (parsed.stream) {
                val usage = if (parsed.includeUsage) usageEvent(id, "text_completion", created, backend.modelId) else null
                stream(call, choices, usage) { send, choice, chunk, state ->
                    if (!state.started) {
                        state.started = true
                        if (parsed.echo) send(envelope(choiceJson(choice.index, choice.prompt.orEmpty(), JsonNull, null)))
                    }
                    val text = chunk.deltas.filterIsInstance<Delta.Content>().joinToString("") { it.text }
                    if (text.isNotEmpty() || chunk.logprobs.isNotEmpty()) {
                        val lp = if (parsed.params.logprobs != null) completionLogprobs(chunk.logprobs, state.textOffset) else JsonNull
                        state.textOffset += text.length
                        send(envelope(choiceJson(choice.index, text, lp, null)))
                    }
                    chunk.finish?.let { send(envelope(choiceJson(choice.index, "", JsonNull, it))) }
                }
            } else {
                full(call, choices) { results ->
                    buildJsonObject {
                        put("id", id)
                        put("object", "text_completion")
                        put("created", created)
                        put("model", backend.modelId)
                        putJsonArray("choices") {
                            results.forEach { r ->
                                val prefix = if (parsed.echo) r.choice.prompt.orEmpty() else ""
                                val lp = if (parsed.params.logprobs != null) completionLogprobs(r.logprobs, prefix.length) else JsonNull
                                add(choiceJson(r.choice.index, prefix + r.content, lp, r.finish))
                            }
                        }
                        put("usage", usage(results.map { it.meter }))
                    }
                }
            }
        }
    }

    // ---- /v1/embeddings ----

    private suspend fun handleEmbeddings(call: ApplicationCall) {
        val body = receiveJson(call) ?: return
        val parsed = try {
            RequestParser.embeddings(body)
        } catch (e: BadRequest) {
            return call.respondError(HttpStatusCode.BadRequest, e.message ?: "invalid request")
        }
        try {
            val response = container.inference.exclusive(container.settings.current.maxQueue) {
                val embedder = container.embeddings.get()
                val inputs = parsed.tokens ?: parsed.texts!!.map { embedder.tokenize(it) }
                var tokens = 0
                val data = buildJsonArray {
                    inputs.forEachIndexed { i, input ->
                        tokens += input.size
                        val vector = embedder.embed(input, parsed.dimensions)
                        addJsonObject {
                            put("object", "embedding")
                            put("index", i)
                            if (parsed.base64) {
                                val buf = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                                vector.forEach { buf.putFloat(it) }
                                put("embedding", Base64.getEncoder().encodeToString(buf.array()))
                            } else {
                                put("embedding", JsonArray(vector.map { JsonPrimitive(it) }))
                            }
                        }
                    }
                }
                buildJsonObject {
                    put("object", "list")
                    put("data", data)
                    put("model", embedder.modelId)
                    putJsonObject("usage") { put("prompt_tokens", tokens); put("total_tokens", tokens) }
                }
            }
            call.respondJson(response)
        } catch (e: InferenceManager.BusyException) {
            call.response.header(HttpHeaders.RetryAfter, "5")
            call.respondError(HttpStatusCode.TooManyRequests, "server busy: request queue is full", "rate_limit_exceeded")
        } catch (e: EmbeddingsNotConfigured) {
            call.respondError(HttpStatusCode.BadRequest, e.message!!, "invalid_request_error", "model_not_found")
        } catch (e: GpuLoadException) {
            call.respondError(HttpStatusCode.ServiceUnavailable, "embedding model could not load on ${e.accelerator}: ${e.reason}", "model_not_ready")
        } catch (e: IllegalArgumentException) {
            call.respondError(HttpStatusCode.BadRequest, e.message ?: "invalid input")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            container.log.e(TAG, "embeddings failed", e)
            call.respondError(HttpStatusCode.InternalServerError, "embeddings failed: ${e.message}", "server_error")
        }
    }

    // ---- shared generation plumbing ----

    /** One output choice: its index, the prompt it answers (text completions), and how to generate it. */
    private inner class Choice(val index: Int, val prompt: String? = null, val start: (Meter) -> Flow<GenChunk>) {
        val meter = Meter()
        fun run(): Flow<GenChunk> = start(meter)
    }

    /** Per-choice state while streaming. */
    private class ChoiceState {
        var started = false
        var toolCalls = false
        var textOffset = 0
        val pendingLogprobs = mutableListOf<TokenLogprob>()
    }

    /** A finished choice for non-streaming responses. */
    private class Result(val choice: Choice) {
        val meter get() = choice.meter
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = sortedMapOf<Int, ToolCallBuilder>()
        val logprobs = mutableListOf<TokenLogprob>()
        var finish: String = "stop"

        fun add(chunk: GenChunk) {
            logprobs += chunk.logprobs
            chunk.deltas.forEach { d ->
                when (d) {
                    is Delta.Content -> content.append(d.text)
                    is Delta.Reasoning -> reasoning.append(d.text)
                    is Delta.ToolCall -> toolCalls.getOrPut(d.index) { ToolCallBuilder() }.apply {
                        d.id?.let { id = it }
                        d.name?.let { name = it }
                        arguments.append(d.arguments)
                    }
                }
            }
            chunk.finish?.let { finish = it }
        }

        fun chatChoice() = buildJsonObject {
            put("index", choice.index)
            putJsonObject("message") {
                put("role", "assistant")
                put("content", if (content.isEmpty() && toolCalls.isNotEmpty()) JsonNull else JsonPrimitive(content.toString()))
                if (reasoning.isNotEmpty()) put("reasoning_content", reasoning.toString())
                if (toolCalls.isNotEmpty()) {
                    putJsonArray("tool_calls") {
                        toolCalls.values.forEach { call ->
                            addJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", call.name)
                                    put("arguments", call.arguments.toString())
                                }
                            }
                        }
                    }
                }
            }
            put("logprobs", if (logprobs.isEmpty()) JsonNull else chatLogprobs(logprobs))
            put("finish_reason", if (toolCalls.isNotEmpty()) "tool_calls" else finish)
        }
    }

    private class ToolCallBuilder {
        var id = ""
        var name = ""
        val arguments = StringBuilder()
    }

    /** Validates capabilities, takes the queue, and maps failures to HTTP errors. */
    private suspend fun runGeneration(call: ApplicationCall, required: Set<Capability>, block: suspend (InferenceBackend) -> Unit) {
        try {
            container.inference.runExclusive(container.settings.current.maxQueue) { backend ->
                val missing = required - backend.capabilities
                if (missing.isNotEmpty()) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "not supported by the loaded model: " + missing.joinToString { it.field } +
                            if (Capability.IMAGES in missing || Capability.AUDIO in missing) " (select an mmproj file for this model in the app)" else "",
                    )
                    return@runExclusive
                }
                block(backend)
            }
        } catch (e: InferenceManager.BusyException) {
            call.response.header(HttpHeaders.RetryAfter, "5")
            call.respondError(HttpStatusCode.TooManyRequests, "server busy: request queue is full", "rate_limit_exceeded")
        } catch (e: InferenceManager.NotReadyException) {
            call.respondError(HttpStatusCode.ServiceUnavailable, e.message ?: "model not ready", "model_not_ready")
        }
    }

    /** Stops at the client's max_tokens or a full context window for backends that don't (finish "length"). */
    private fun limited(source: Flow<GenChunk>, maxTokens: Int?, meter: Meter): Flow<GenChunk> {
        var done = false
        return source.transformWhile { chunk ->
            val limit = listOfNotNull(maxTokens, meter.cache?.completionBudget).minOrNull()
            if (limit != null && chunk.tokens > 0 && meter.count + chunk.tokens > limit) {
                if (!done) emit(GenChunk(finish = "length"))
                done = true
                return@transformWhile false
            }
            repeat(chunk.tokens) { meter.onToken() }
            val last = chunk.finish != null
            emit(chunk)
            !last
        }
    }

    private sealed interface Event {
        class Chunk(val choice: Choice, val chunk: GenChunk) : Event
        class Failure(val error: Throwable) : Event
    }

    /**
     * Streams [choices] one after another as SSE. The response is held until the first chunk so
     * that anything failing before generation starts still gets a proper HTTP status.
     */
    private suspend fun stream(
        call: ApplicationCall,
        choices: List<Choice>,
        usage: ((List<Meter>) -> JsonElement)?,
        render: (send: (JsonElement) -> Unit, choice: Choice, chunk: GenChunk, state: ChoiceState) -> Unit,
    ) = coroutineScope {
        val events = flow<Event> {
            for (choice in choices) {
                var finished = false
                choice.run().collect {
                    if (it.finish != null) finished = true
                    emit(Event.Chunk(choice, it))
                }
                if (!finished) emit(Event.Chunk(choice, GenChunk(finish = "stop")))
            }
        }
            .catch { emit(Event.Failure(it)) }
            .buffer(Channel.RENDEZVOUS) // don't generate ahead of what the client has received
            .produceIn(this)
        try {
            val first = events.receiveCatching().getOrNull()
            if (first is Event.Failure) {
                container.inference.recordFailure()
                respondGenerationError(call, first.error)
                return@coroutineScope
            }
            val states = choices.associateWith { ChoiceState() }
            var ok = false
            call.response.cacheControl(CacheControl.NoCache(null))
            call.response.header("X-Accel-Buffering", "no")
            call.respondTextWriter(ContentType.Text.EventStream) {
                fun send(data: JsonElement) {
                    write("data: ")
                    write(data.toString())
                    write("\n\n")
                    flush()
                }
                try {
                    var event = first
                    while (event != null) {
                        when (event) {
                            is Event.Chunk -> render(::send, event.choice, event.chunk, states.getValue(event.choice))
                            is Event.Failure -> throw event.error
                        }
                        event = events.receiveCatching().getOrNull()
                    }
                    usage?.let { send(it(choices.map { c -> c.meter })) }
                    write("data: [DONE]\n\n")
                    flush()
                    ok = true
                } catch (e: CancellationException) {
                    container.inference.recordFailure()
                    throw e
                } catch (e: IOException) {
                    container.log.w(TAG, "client disconnected after ${choices.sumOf { it.meter.count }} tokens")
                    container.inference.recordFailure()
                } catch (e: Exception) {
                    container.log.e(TAG, "generation failed after ${choices.sumOf { it.meter.count }} tokens", e)
                    container.inference.recordFailure()
                    runCatching {
                        send(errorBody(e.message ?: e.javaClass.simpleName, "server_error", 500))
                        write("data: [DONE]\n\n")
                        flush()
                    }
                }
            }
            if (ok) recordSuccess(choices.map { it.meter })
        } finally {
            events.cancel() // stops generation if the client went away mid-stream
        }
    }

    /** Final SSE event with token usage (stream_options.include_usage). */
    private fun usageEvent(id: String, objectType: String, created: Long, model: String): (List<Meter>) -> JsonElement = { meters ->
        buildJsonObject {
            put("id", id)
            put("object", objectType)
            put("created", created)
            put("model", model)
            putJsonArray("choices") {}
            put("usage", usage(meters))
        }
    }

    private suspend fun full(call: ApplicationCall, choices: List<Choice>, respond: (List<Result>) -> JsonObject) {
        val results = choices.map { Result(it) }
        try {
            results.forEach { r -> r.choice.run().collect { r.add(it) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            container.inference.recordFailure()
            return respondGenerationError(call, e)
        }
        recordSuccess(choices.map { it.meter })
        call.respondJson(respond(results))
    }

    private suspend fun respondGenerationError(call: ApplicationCall, error: Throwable) {
        when (error) {
            is ContextOverflowException -> {
                container.log.w(TAG, "rejected: ${error.message}")
                call.respondError(HttpStatusCode.BadRequest, error.message!!, "invalid_request_error", "context_length_exceeded")
            }
            is IllegalArgumentException -> {
                container.log.w(TAG, "rejected: ${error.message}")
                call.respondError(HttpStatusCode.BadRequest, error.message ?: "invalid request")
            }
            else -> {
                container.log.e(TAG, "generation failed", error)
                call.respondError(HttpStatusCode.InternalServerError, "generation failed: ${error.message}", "server_error")
            }
        }
    }

    private suspend fun receiveJson(call: ApplicationCall): JsonObject? = try {
        Json.parseToJsonElement(call.receiveText()).jsonObject
    } catch (e: Exception) {
        call.respondError(HttpStatusCode.BadRequest, "invalid JSON body: ${e.message}")
        null
    }

    private suspend fun rejectIfHot(call: ApplicationCall): Boolean {
        val thermal = container.device.thermalStatus()
        if (thermal < container.settings.current.rejectAtThermalStatus) return false
        call.response.header(HttpHeaders.RetryAfter, "30")
        call.respondError(HttpStatusCode.ServiceUnavailable, "device is too hot (thermal=${DeviceMonitor.thermalName(thermal)})", "server_overloaded")
        return true
    }

    /** http(s) image URLs in chat messages; capped so a URL can't exhaust memory. */
    private fun fetchMedia(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
        }
        check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
        return connection.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                check(out.size() <= MAX_MEDIA_BYTES) { "larger than ${MAX_MEDIA_BYTES / (1024 * 1024)} MB" }
            }
            out.toByteArray()
        }
    }

    private fun recordSuccess(meters: List<Meter>) {
        val tokens = meters.sumOf { it.count }
        val first = meters.firstOrNull() ?: return
        val tps = first.decodeTokensPerSecond()
        container.inference.recordSuccess(tokens, first.ttftMs(), tps, first.cache)
        val cache = first.cache?.let {
            (if (it.hit) "cache hit (reused ${it.reusedTokens} tok)" else "cache miss") + ", prompt ${it.promptTokens} tok"
        } ?: "cache n/a"
        container.log.i(TAG, "generated $tokens tok in ${meters.size} choice(s), ttft ${first.ttftMs()}ms, ${"%.1f".format(tps)} tok/s, $cache")
    }

    /** Non-standard diagnostics attached to responses; OpenAI clients ignore unknown fields. */
    private fun extras(meter: Meter) = buildJsonObject {
        meter.cache?.let {
            put("cache", if (it.hit) "hit" else "miss")
            put("reused_tokens", it.reusedTokens)
            put("truncated_messages", it.truncatedMessages)
            put("prompt_tokens", it.promptTokens)
            put("completion_budget", it.completionBudget)
        }
        put("ttft_ms", meter.ttftMs())
    }

    class Meter {
        private val start = SystemClock.elapsedRealtime()
        private var firstAt = -1L
        var cache: CacheResult? = null
        var count = 0
            private set

        fun onToken() {
            if (count == 0) firstAt = SystemClock.elapsedRealtime()
            count++
        }

        fun ttftMs(): Long = if (firstAt < 0) 0 else firstAt - start

        fun decodeTokensPerSecond(): Double {
            val elapsed = SystemClock.elapsedRealtime() - firstAt
            return if (count > 1 && elapsed > 0) (count - 1) * 1000.0 / elapsed else 0.0
        }
    }

    private companion object {
        const val TAG = "api"
        const val EXTRAS_KEY = "x_edge"

        /** mtmd's default media marker; the llama.cpp backend expects it in place of each image/audio part. */
        const val MEDIA_MARKER = "<__media__>"
        const val MAX_MEDIA_BYTES = 20 * 1024 * 1024

        fun newId(prefix: String) = prefix + UUID.randomUUID().toString().replace("-", "").take(24)

        /** prompt_tokens counts each distinct prompt once (exact for GGUF, estimated otherwise). */
        fun usage(meters: List<Meter>) = buildJsonObject {
            val prompt = meters.mapNotNull { it.cache?.promptTokens }.distinct().sum()
            val completion = meters.sumOf { it.count }
            put("prompt_tokens", prompt)
            put("completion_tokens", completion)
            put("total_tokens", prompt + completion)
        }

        fun tokenJson(t: TokenLogprob) = buildJsonObject {
            put("token", t.token)
            put("logprob", t.logprob)
            putJsonArray("bytes") { t.bytes.forEach { add(JsonPrimitive(it.toInt() and 0xFF)) } }
            putJsonArray("top_logprobs") {
                t.top.forEach { top ->
                    addJsonObject {
                        put("token", top.token)
                        put("logprob", top.logprob)
                        putJsonArray("bytes") { top.bytes.forEach { add(JsonPrimitive(it.toInt() and 0xFF)) } }
                    }
                }
            }
        }

        fun chatLogprobs(tokens: List<TokenLogprob>) = buildJsonObject {
            putJsonArray("content") { tokens.forEach { add(tokenJson(it)) } }
            put("refusal", JsonNull)
        }

        /** Legacy completions format: parallel arrays plus text offsets. */
        fun completionLogprobs(tokens: List<TokenLogprob>, offset: Int) = buildJsonObject {
            putJsonArray("tokens") { tokens.forEach { add(JsonPrimitive(it.token)) } }
            putJsonArray("token_logprobs") { tokens.forEach { add(JsonPrimitive(it.logprob)) } }
            putJsonArray("top_logprobs") {
                tokens.forEach { t -> add(JsonObject(t.top.associate { it.token to JsonPrimitive(it.logprob) })) }
            }
            putJsonArray("text_offset") {
                var o = offset
                tokens.forEach { t ->
                    add(JsonPrimitive(o))
                    o += t.token.length
                }
            }
        }

        fun errorBody(message: String, type: String, code: Any) = buildJsonObject {
            putJsonObject("error") {
                put("message", message)
                put("type", type)
                put("code", if (code is Int) JsonPrimitive(code) else JsonPrimitive(code.toString()))
            }
        }

        suspend fun ApplicationCall.respondJson(body: JsonElement, status: HttpStatusCode = HttpStatusCode.OK) =
            respondText(body.toString(), ContentType.Application.Json, status)

        suspend fun ApplicationCall.respondError(
            status: HttpStatusCode,
            message: String,
            type: String = "invalid_request_error",
            code: String? = null,
        ) = respondJson(errorBody(message, type, code ?: status.value), status)
    }
}

/** /v1/embeddings was called but no embedding model is selected in the app. */
class EmbeddingsNotConfigured : IllegalStateException("no embedding model selected: pick a GGUF embedding model in the app's Models tab")
