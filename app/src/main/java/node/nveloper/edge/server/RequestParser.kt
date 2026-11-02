package node.nveloper.edge.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import node.nveloper.edge.inference.Capability
import node.nveloper.edge.inference.ChatMessage
import node.nveloper.edge.inference.ChatOptions
import node.nveloper.edge.inference.GenerationParams
import java.util.Base64

/** A request the server rejects with 400 before touching the model. */
class BadRequest(message: String) : IllegalArgumentException(message)

data class ParsedChat(
    val messages: List<ChatMessage>,
    val params: GenerationParams,
    val options: ChatOptions,
    val stream: Boolean,
    val includeUsage: Boolean,
    val n: Int,
    /** Backend features this request needs. */
    val required: Set<Capability>,
)

data class ParsedCompletion(
    val prompts: List<String>,
    val params: GenerationParams,
    val stream: Boolean,
    val includeUsage: Boolean,
    val n: Int,
    val echo: Boolean,
    val required: Set<Capability>,
)

/** Inputs of /v1/embeddings: either texts or already tokenized inputs. */
data class ParsedEmbeddings(val texts: List<String>?, val tokens: List<IntArray>?, val dimensions: Int?, val base64: Boolean) {
    val count: Int get() = texts?.size ?: tokens!!.size
}

/**
 * OpenAI request bodies → backend calls. Field handling follows llama-server where OpenAI leaves
 * room (response_format, continuation of a trailing assistant message, reasoning options).
 */
object RequestParser {
    const val MAX_N = 8
    const val MAX_STOPS = 16

    /** [fetch] downloads http(s) media URLs; data: URLs are decoded locally. */
    fun chat(body: JsonObject, mediaMarker: String, fetch: (String) -> ByteArray): ParsedChat {
        val required = mutableSetOf<Capability>()
        val messages = messages(body["messages"], mediaMarker, fetch, required)
        val params = params(body, required, chat = true)

        val explicitContinue = body.bool("continue_final_message") == true
        val last = messages.last()
        val continueFinal = last.role == ChatMessage.Role.ASSISTANT
        when {
            continueFinal -> {
                if (messages.size >= 2 && messages[messages.size - 2].role == ChatMessage.Role.ASSISTANT) {
                    throw BadRequest("cannot have 2 or more assistant messages at the end of the list")
                }
                if ((last.raw?.get("tool_calls") as? JsonArray)?.isNotEmpty() == true) {
                    throw BadRequest("cannot continue an assistant message that contains tool calls")
                }
                required += Capability.CONTINUE
            }
            explicitContinue -> throw BadRequest("continue_final_message needs an assistant message last")
            last.role != ChatMessage.Role.USER -> throw BadRequest("the last message must have role 'user', 'tool' or 'assistant'")
        }

        var tools = body["tools"].takeUnless { it is JsonNull } as? JsonArray
        if (body["tools"] != null && body["tools"] !is JsonNull && tools == null) throw BadRequest("'tools' must be an array")
        var toolChoice = "auto"
        when (val choice = body["tool_choice"]) {
            null, JsonNull -> Unit
            is JsonPrimitive -> toolChoice = choice.contentOrNull?.takeIf { it in setOf("auto", "none", "required") }
                ?: throw BadRequest("tool_choice must be \"auto\", \"none\", \"required\" or a function")
            is JsonObject -> {
                val name = ((choice["function"] as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
                    ?: throw BadRequest("tool_choice.function.name is required")
                val named = tools?.filter { tool ->
                    (((tool as? JsonObject)?.get("function") as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull == name
                }
                if (named.isNullOrEmpty()) throw BadRequest("tool_choice names an unknown function: $name")
                tools = JsonArray(named)
                toolChoice = "required"
            }
            else -> throw BadRequest("invalid tool_choice")
        }
        if (!tools.isNullOrEmpty() && toolChoice != "none") required += Capability.TOOLS

        val jsonSchema = responseFormat(body)
        if (jsonSchema != null) required += Capability.JSON_SCHEMA
        if (!tools.isNullOrEmpty() && toolChoice != "none" && jsonSchema != null) {
            throw BadRequest("response_format cannot be combined with tools")
        }

        val stream = body.bool("stream") ?: false
        if (params.logprobs != null && stream && Capability.TOOLS in required) {
            throw BadRequest("logprobs is not supported with tools + stream")
        }
        return ParsedChat(
            messages = messages,
            params = params,
            options = ChatOptions(
                tools = tools,
                toolChoice = toolChoice,
                parallelToolCalls = body.bool("parallel_tool_calls"),
                jsonSchema = jsonSchema,
                continueFinal = continueFinal,
                reasoningFormat = body.string("reasoning_format"),
                enableThinking = body.bool("enable_thinking"),
                reasoningEffort = body.string("reasoning_effort"),
                templateKwargs = body["chat_template_kwargs"] as? JsonObject,
            ),
            stream = stream,
            includeUsage = (body["stream_options"] as? JsonObject)?.bool("include_usage") ?: false,
            n = n(body),
            required = required,
        )
    }

    fun completion(body: JsonObject): ParsedCompletion {
        val required = mutableSetOf(Capability.RAW_COMPLETION)
        val prompts = when (val p = body["prompt"]) {
            is JsonPrimitive -> listOf(p.contentOrNull ?: throw BadRequest("'prompt' must be a string or an array of strings"))
            is JsonArray -> p.map { (it as? JsonPrimitive)?.takeIf { e -> e.isString }?.content ?: throw BadRequest("only text prompts are supported") }
            else -> throw BadRequest("'prompt' is required")
        }
        if (prompts.isEmpty()) throw BadRequest("'prompt' must not be empty")
        if (!body.string("suffix").isNullOrEmpty()) throw BadRequest("'suffix' (fill-in-the-middle) is not supported")
        val bestOf = body.int("best_of")
        if (bestOf != null && bestOf != n(body)) throw BadRequest("'best_of' is not supported")
        val params = params(body, required, chat = false)
        return ParsedCompletion(
            prompts = prompts,
            params = params,
            stream = body.bool("stream") ?: false,
            includeUsage = (body["stream_options"] as? JsonObject)?.bool("include_usage") ?: false,
            n = n(body),
            echo = body.bool("echo") ?: false,
            required = required,
        )
    }

    fun embeddings(body: JsonObject): ParsedEmbeddings {
        val format = body.string("encoding_format") ?: "float"
        if (format != "float" && format != "base64") throw BadRequest("encoding_format must be \"float\" or \"base64\"")
        val dimensions = body.int("dimensions")
        if (dimensions != null && dimensions < 1) throw BadRequest("dimensions must be positive")
        return when (val input = body["input"]) {
            is JsonPrimitive -> ParsedEmbeddings(listOf(input.contentOrNull ?: throw BadRequest("'input' is required")), null, dimensions, format == "base64")
            is JsonArray -> {
                if (input.isEmpty()) throw BadRequest("'input' must not be empty")
                when {
                    input.all { it is JsonPrimitive && it.isString } -> ParsedEmbeddings(input.map { it.jsonPrimitive.content }, null, dimensions, format == "base64")
                    input.all { it is JsonPrimitive && !it.isString } -> ParsedEmbeddings(null, listOf(tokens(input)), dimensions, format == "base64")
                    input.all { it is JsonArray } -> ParsedEmbeddings(null, input.map { tokens(it as JsonArray) }, dimensions, format == "base64")
                    else -> throw BadRequest("'input' must be a string, an array of strings, or token arrays")
                }
            }
            else -> throw BadRequest("'input' is required")
        }
    }

    private fun tokens(array: JsonArray): IntArray =
        array.map { (it as? JsonPrimitive)?.intOrNull ?: throw BadRequest("token ids must be integers") }.toIntArray()

    /** Sampling and output options shared by chat and text completions. */
    fun params(body: JsonObject, required: MutableSet<Capability>, chat: Boolean): GenerationParams {
        val stop = when (val s = body["stop"]) {
            null, JsonNull -> emptyList()
            is JsonPrimitive -> listOf(s.content)
            is JsonArray -> s.map { (it as? JsonPrimitive)?.contentOrNull ?: throw BadRequest("'stop' entries must be strings") }
            else -> throw BadRequest("'stop' must be a string or an array of strings")
        }.filter { it.isNotEmpty() }
        if (stop.size > MAX_STOPS) throw BadRequest("at most $MAX_STOPS stop strings are supported")
        if (stop.isNotEmpty()) required += Capability.STOP

        val frequency = body.double("frequency_penalty")?.also { range("frequency_penalty", it, -2.0, 2.0) }
        val presence = body.double("presence_penalty")?.also { range("presence_penalty", it, -2.0, 2.0) }
        if ((frequency ?: 0.0) != 0.0 || (presence ?: 0.0) != 0.0) required += Capability.PENALTIES

        val bias = when (val b = body["logit_bias"]) {
            null, JsonNull -> emptyMap()
            is JsonObject -> b.entries.associate { (key, value) ->
                val id = key.toIntOrNull() ?: throw BadRequest("logit_bias keys must be token ids")
                val v = (value as? JsonPrimitive)?.floatOrNull ?: throw BadRequest("logit_bias values must be numbers")
                range("logit_bias", v.toDouble(), -100.0, 100.0)
                id to v
            }
            else -> throw BadRequest("'logit_bias' must be an object")
        }
        if (bias.isNotEmpty()) required += Capability.LOGIT_BIAS

        val logprobs = if (chat) {
            val top = body.int("top_logprobs")
            when (body.bool("logprobs")) {
                true -> (top ?: 0).also { range("top_logprobs", it.toDouble(), 0.0, 20.0) }
                else -> {
                    if (top != null) throw BadRequest("top_logprobs requires logprobs to be set to true")
                    null
                }
            }
        } else {
            body.int("logprobs")?.also { range("logprobs", it.toDouble(), 0.0, 5.0) }
        }
        if (logprobs != null) required += Capability.LOGPROBS

        return GenerationParams(
            temperature = body.double("temperature")?.also { range("temperature", it, 0.0, 2.0) },
            topP = body.double("top_p")?.also { range("top_p", it, 0.0, 1.0) },
            topK = body.int("top_k"),
            maxTokens = (body.int("max_completion_tokens") ?: body.int("max_tokens"))?.also { if (it < 1) throw BadRequest("max_tokens must be at least 1") },
            seed = body.int("seed"),
            stop = stop,
            frequencyPenalty = frequency,
            presencePenalty = presence,
            logitBias = bias,
            logprobs = logprobs,
        )
    }

    /** JSON schema for response_format, as llama-server reads it; an empty schema means any object. */
    fun responseFormat(body: JsonObject): JsonObject? {
        val format = body["response_format"] as? JsonObject ?: return null
        val schema = when (val type = format.string("type")) {
            null, "text" -> return null
            "json_object" -> format["schema"] as? JsonObject ?: JsonObject(emptyMap())
            "json_schema" -> ((format["json_schema"] as? JsonObject)?.get("schema") as? JsonObject) ?: JsonObject(emptyMap())
            else -> throw BadRequest("response_format type must be \"text\", \"json_object\" or \"json_schema\", got \"$type\"")
        }
        return if (schema.isEmpty()) buildJsonObject { put("type", "object") } else schema
    }

    private fun messages(element: JsonElement?, marker: String, fetch: (String) -> ByteArray, required: MutableSet<Capability>): List<ChatMessage> {
        val array = element as? JsonArray ?: throw BadRequest("'messages' must be an array")
        if (array.isEmpty()) throw BadRequest("'messages' must not be empty")
        return array.map { item ->
            val obj = item as? JsonObject ?: throw BadRequest("each message must be an object")
            val roleName = obj.string("role")
            val role = when (roleName) {
                "system", "developer" -> ChatMessage.Role.SYSTEM
                "user", "tool" -> ChatMessage.Role.USER
                "assistant" -> ChatMessage.Role.ASSISTANT
                else -> throw BadRequest("unsupported role: $roleName")
            }
            if (roleName != "assistant" && "content" !in obj) throw BadRequest("all non-assistant messages must contain 'content'")
            if (roleName == "assistant" && "content" !in obj && "tool_calls" !in obj) {
                throw BadRequest("assistant message must contain either 'content' or 'tool_calls'")
            }
            val media = mutableListOf<ByteArray>()
            val text = StringBuilder()
            val content = when (val c = obj["content"]) {
                null, JsonNull -> c
                is JsonPrimitive -> c.also { text.append(it.content) }
                is JsonArray -> JsonArray(
                    c.map { part ->
                        val p = part as? JsonObject ?: throw BadRequest("content parts must be objects")
                        when (p.string("type")) {
                            "text" -> p.also {
                                if (text.isNotEmpty()) text.append('\n')
                                text.append(p.string("text").orEmpty())
                            }
                            "image_url" -> {
                                val url = ((p["image_url"] as? JsonObject)?.string("url")) ?: p.string("image_url")
                                    ?: throw BadRequest("image_url.url is required")
                                media += load(url, fetch)
                                required += Capability.IMAGES
                                markerPart(marker)
                            }
                            "input_audio" -> {
                                val data = (p["input_audio"] as? JsonObject)?.string("data") ?: throw BadRequest("input_audio.data is required")
                                media += decodeBase64(data)
                                required += Capability.AUDIO
                                markerPart(marker)
                            }
                            else -> throw BadRequest("unsupported content part type: ${p.string("type")}")
                        }
                    },
                )
                else -> throw BadRequest("'content' must be a string or an array")
            }
            // Templates know "system", not OpenAI's newer "developer".
            val raw = JsonObject(obj + mapOfNotNull("role" to JsonPrimitive(if (roleName == "developer") "system" else roleName!!), content?.let { "content" to it }))
            ChatMessage(role, text.toString(), raw, media)
        }
    }

    private fun mapOfNotNull(vararg pairs: Pair<String, JsonElement>?) = pairs.filterNotNull().toMap()

    private fun markerPart(marker: String) = buildJsonObject {
        put("type", "media_marker")
        put("text", marker)
    }

    private fun load(url: String, fetch: (String) -> ByteArray): ByteArray = when {
        url.startsWith("data:") -> {
            val comma = url.indexOf(',')
            if (comma < 0 || !url.substring(0, comma).endsWith(";base64")) throw BadRequest("data URLs must be base64-encoded")
            decodeBase64(url.substring(comma + 1))
        }
        url.startsWith("http://") || url.startsWith("https://") -> try {
            fetch(url)
        } catch (e: Exception) {
            throw BadRequest("could not download $url: ${e.message}")
        }
        else -> throw BadRequest("image URLs must be data: or http(s) URLs")
    }

    private fun decodeBase64(data: String): ByteArray = try {
        Base64.getMimeDecoder().decode(data)
    } catch (e: IllegalArgumentException) {
        throw BadRequest("invalid base64 data")
    }

    private fun n(body: JsonObject): Int {
        val n = body.int("n") ?: 1
        if (n !in 1..MAX_N) throw BadRequest("n must be between 1 and $MAX_N")
        return n
    }

    private fun range(name: String, value: Double, min: Double, max: Double) {
        if (value < min || value > max) throw BadRequest("$name must be between $min and $max")
    }

    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.double(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
