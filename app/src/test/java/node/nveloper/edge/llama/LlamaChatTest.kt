package node.nveloper.edge.llama

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import node.nveloper.edge.inference.ChatMessage
import node.nveloper.edge.inference.ChatOptions
import node.nveloper.edge.inference.Delta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaChatTest {
    @Test
    fun `request carries raw messages and options`() {
        val raw = buildJsonObject { put("role", "tool"); put("tool_call_id", "c1"); put("content", "ok") }
        val text = LlamaChat.request(
            listOf(ChatMessage(ChatMessage.Role.USER, "hi"), ChatMessage(ChatMessage.Role.USER, "ok", raw)),
            ChatOptions(
                tools = buildJsonArray { add(buildJsonObject { put("type", "function") }) },
                toolChoice = "required",
                continueFinal = true,
                reasoningFormat = "none",
            ),
        )
        val obj = Json.parseToJsonElement(text).jsonObject
        val messages = obj["messages"]!!.jsonArray
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("tool", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("required", obj["tool_choice"]!!.jsonPrimitive.content)
        assertEquals("true", obj["continue_final"]!!.jsonPrimitive.content)
        assertEquals("none", obj["reasoning_format"]!!.jsonPrimitive.content)
        assertEquals(1, obj["tools"]!!.jsonArray.size)
    }

    @Test
    fun `empty tools and unset options are left out`() {
        val obj = Json.parseToJsonElement(LlamaChat.request(listOf(ChatMessage(ChatMessage.Role.USER, "a")), ChatOptions(tools = buildJsonArray {}))).jsonObject
        assertFalse("tools" in obj)
        assertFalse("json_schema" in obj)
        assertFalse("reasoning_format" in obj)
    }

    @Test
    fun `deltas parse content, reasoning and tool calls`() {
        val deltas = LlamaChat.deltas(
            """[{"reasoning_content":"think"},{"content":"Hi"},{"tool_call":{"index":1,"id":"call_x","name":"w","arguments":"{\"a\""}},{"tool_call":{"index":1,"id":"","name":"","arguments":":1}"}}]""",
        )
        assertEquals(Delta.Reasoning("think"), deltas[0])
        assertEquals(Delta.Content("Hi"), deltas[1])
        assertEquals(Delta.ToolCall(1, "call_x", "w", "{\"a\""), deltas[2])
        assertEquals(Delta.ToolCall(1, null, null, ":1}"), deltas[3])
        assertTrue(LlamaChat.deltas("[]").isEmpty())
    }
}
