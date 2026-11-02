package node.nveloper.edge.llama

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import node.nveloper.edge.inference.ChatMessage
import node.nveloper.edge.inference.ChatOptions
import node.nveloper.edge.inference.Delta

/** JSON exchanged with edge_chat.cpp (chatPrepare in, chatUpdate out). */
object LlamaChat {
    /** Request for [LlamaNative.chatPrepare]: the OpenAI messages plus the template options. */
    fun request(messages: List<ChatMessage>, options: ChatOptions): String = buildJsonObject {
        put("messages", JsonArray(messages.map { it.raw ?: plain(it) }))
        options.tools?.takeIf { it.isNotEmpty() }?.let { put("tools", it) }
        put("tool_choice", options.toolChoice)
        options.parallelToolCalls?.let { put("parallel_tool_calls", it) }
        options.jsonSchema?.let { put("json_schema", it) }
        put("continue_final", options.continueFinal)
        options.reasoningFormat?.let { put("reasoning_format", it) }
        options.enableThinking?.let { put("enable_thinking", it) }
        options.reasoningEffort?.let { put("reasoning_effort", it) }
        options.templateKwargs?.let { put("chat_template_kwargs", it) }
    }.toString()

    private fun plain(message: ChatMessage) = buildJsonObject {
        put(
            "role",
            when (message.role) {
                ChatMessage.Role.SYSTEM -> "system"
                ChatMessage.Role.USER -> "user"
                ChatMessage.Role.ASSISTANT -> "assistant"
            },
        )
        put("content", message.text)
    }

    /** Parses chatUpdate's JSON array into deltas. */
    fun deltas(json: String): List<Delta> {
        if (json == "[]") return emptyList()
        return Json.parseToJsonElement(json).jsonArray.mapNotNull { element ->
            val obj = element.jsonObject
            when {
                "content" in obj -> Delta.Content(obj.string("content").orEmpty())
                "reasoning_content" in obj -> Delta.Reasoning(obj.string("reasoning_content").orEmpty())
                "tool_call" in obj -> obj.getValue("tool_call").jsonObject.let { call ->
                    Delta.ToolCall(
                        index = call["index"]?.jsonPrimitive?.intOrNull ?: 0,
                        id = call.string("id")?.ifEmpty { null },
                        name = call.string("name")?.ifEmpty { null },
                        arguments = call.string("arguments").orEmpty(),
                    )
                }
                else -> null
            }
        }
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
