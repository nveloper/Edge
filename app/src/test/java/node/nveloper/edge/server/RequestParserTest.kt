package node.nveloper.edge.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import node.nveloper.edge.inference.Capability
import node.nveloper.edge.inference.ChatMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RequestParserTest {
    private val marker = "<__media__>"
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun chat(text: String, fetch: (String) -> ByteArray = { error("no network in tests") }) = RequestParser.chat(json(text), marker, fetch)

    private fun assertBadRequest(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected BadRequest")
        } catch (_: BadRequest) {
        }
    }

    @Test
    fun `plain chat needs no special capability`() {
        val p = chat("""{"messages":[{"role":"system","content":"s"},{"role":"user","content":"hi"}]}""")
        assertEquals(listOf(ChatMessage.Role.SYSTEM, ChatMessage.Role.USER), p.messages.map { it.role })
        assertTrue(p.required.isEmpty())
        assertEquals(1, p.n)
        assertFalse(p.options.continueFinal)
    }

    @Test
    fun `stop accepts a string or an array`() {
        assertEquals(listOf("x"), chat("""{"messages":[{"role":"user","content":"a"}],"stop":"x"}""").params.stop)
        val p = chat("""{"messages":[{"role":"user","content":"a"}],"stop":["x","y",""]}""")
        assertEquals(listOf("x", "y"), p.params.stop)
        assertTrue(Capability.STOP in p.required)
    }

    @Test
    fun `penalties and logit bias`() {
        val p = chat("""{"messages":[{"role":"user","content":"a"}],"frequency_penalty":0.5,"presence_penalty":-1,"logit_bias":{"42":-100,"7":5.5}}""")
        assertEquals(0.5, p.params.frequencyPenalty!!, 0.0)
        assertEquals(-1.0, p.params.presencePenalty!!, 0.0)
        assertEquals(mapOf(42 to -100f, 7 to 5.5f), p.params.logitBias)
        assertTrue(p.required.containsAll(listOf(Capability.PENALTIES, Capability.LOGIT_BIAS)))
        assertBadRequest { chat("""{"messages":[{"role":"user","content":"a"}],"frequency_penalty":3}""") }
        assertBadRequest { chat("""{"messages":[{"role":"user","content":"a"}],"logit_bias":{"x":1}}""") }
    }

    @Test
    fun `logprobs need logprobs true for top_logprobs`() {
        assertEquals(5, chat("""{"messages":[{"role":"user","content":"a"}],"logprobs":true,"top_logprobs":5}""").params.logprobs)
        assertEquals(0, chat("""{"messages":[{"role":"user","content":"a"}],"logprobs":true}""").params.logprobs)
        assertNull(chat("""{"messages":[{"role":"user","content":"a"}]}""").params.logprobs)
        assertBadRequest { chat("""{"messages":[{"role":"user","content":"a"}],"top_logprobs":3}""") }
    }

    @Test
    fun `response_format follows llama-server`() {
        assertNull(chat("""{"messages":[{"role":"user","content":"a"}],"response_format":{"type":"text"}}""").options.jsonSchema)
        val any = chat("""{"messages":[{"role":"user","content":"a"}],"response_format":{"type":"json_object"}}""")
        assertEquals("object", any.options.jsonSchema!!["type"]!!.jsonPrimitive.content)
        assertTrue(Capability.JSON_SCHEMA in any.required)
        val schema = chat(
            """{"messages":[{"role":"user","content":"a"}],"response_format":{"type":"json_schema","json_schema":{"name":"x","schema":{"type":"array"}}}}""",
        )
        assertEquals("array", schema.options.jsonSchema!!["type"]!!.jsonPrimitive.content)
        assertBadRequest { chat("""{"messages":[{"role":"user","content":"a"}],"response_format":{"type":"yaml"}}""") }
    }

    @Test
    fun `tool_choice naming a function keeps only that tool and requires a call`() {
        val p = chat(
            """{"messages":[{"role":"user","content":"a"}],
               "tools":[{"type":"function","function":{"name":"a"}},{"type":"function","function":{"name":"b"}}],
               "tool_choice":{"type":"function","function":{"name":"b"}}}""",
        )
        assertEquals("required", p.options.toolChoice)
        assertEquals(1, p.options.tools!!.size)
        assertTrue(Capability.TOOLS in p.required)
        assertBadRequest { chat("""{"messages":[{"role":"user","content":"a"}],"tools":[],"tool_choice":{"type":"function","function":{"name":"zz"}}}""") }
    }

    @Test
    fun `tool_choice none needs no tool support`() {
        val p = chat("""{"messages":[{"role":"user","content":"a"}],"tools":[{"type":"function","function":{"name":"a"}}],"tool_choice":"none"}""")
        assertFalse(Capability.TOOLS in p.required)
    }

    @Test
    fun `tool results and assistant tool calls are kept raw`() {
        val p = chat(
            """{"messages":[
                {"role":"user","content":"weather?"},
                {"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"w","arguments":"{}"}}]},
                {"role":"tool","tool_call_id":"c1","content":"sunny"}]}""",
        )
        val tool = p.messages.last()
        assertEquals(ChatMessage.Role.USER, tool.role)
        assertFalse(tool.isUserTurn)
        assertEquals("tool", tool.raw!!["role"]!!.jsonPrimitive.content)
        assertEquals("c1", tool.raw!!["tool_call_id"]!!.jsonPrimitive.content)
        assertTrue(p.messages[1].raw!!["tool_calls"] is JsonArray)
    }

    @Test
    fun `trailing assistant message is a continuation`() {
        val p = chat("""{"messages":[{"role":"user","content":"a"},{"role":"assistant","content":"Once upon"}]}""")
        assertTrue(p.options.continueFinal)
        assertTrue(Capability.CONTINUE in p.required)
        assertBadRequest { chat("""{"messages":[{"role":"user","content":"a"},{"role":"assistant","content":"x"},{"role":"assistant","content":"y"}]}""") }
    }

    @Test
    fun `developer role becomes system for templates`() {
        val p = chat("""{"messages":[{"role":"developer","content":"rules"},{"role":"user","content":"a"}]}""")
        assertEquals(ChatMessage.Role.SYSTEM, p.messages[0].role)
        assertEquals("system", p.messages[0].raw!!["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `data URL images become media markers`() {
        val png = byteArrayOf(1, 2, 3, 4)
        val url = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
        val p = chat(
            """{"messages":[{"role":"user","content":[{"type":"text","text":"what is this"},{"type":"image_url","image_url":{"url":"$url"}}]}]}""",
        )
        val msg = p.messages.single()
        assertEquals("what is this", msg.text)
        assertEquals(1, msg.media.size)
        assertArrayEquals(png, msg.media[0])
        val parts = msg.raw!!["content"]!!.jsonArray
        assertEquals("media_marker", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(marker, parts[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(Capability.IMAGES in p.required)
    }

    @Test
    fun `http images are fetched`() {
        val p = chat(
            """{"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"https://example.com/a.jpg"}}]}]}""",
            fetch = { byteArrayOf(9) },
        )
        assertArrayEquals(byteArrayOf(9), p.messages.single().media.single())
    }

    @Test
    fun `audio input is decoded`() {
        val data = Base64.getEncoder().encodeToString(byteArrayOf(5, 6))
        val p = chat("""{"messages":[{"role":"user","content":[{"type":"input_audio","input_audio":{"data":"$data","format":"wav"}}]}]}""")
        assertArrayEquals(byteArrayOf(5, 6), p.messages.single().media.single())
        assertTrue(Capability.AUDIO in p.required)
    }

    @Test
    fun `n is bounded`() {
        assertEquals(3, chat("""{"messages":[{"role":"user","content":"a"}],"n":3}""").n)
        assertBadRequest { chat("""{"messages":[{"role":"user","content":"a"}],"n":0}""") }
        assertBadRequest { chat("""{"messages":[{"role":"user","content":"a"}],"n":99}""") }
    }

    @Test
    fun `invalid messages are rejected`() {
        assertBadRequest { chat("""{"messages":[]}""") }
        assertBadRequest { chat("""{"messages":[{"role":"wizard","content":"a"}]}""") }
        assertBadRequest { chat("""{"messages":[{"role":"user"}]}""") }
        assertBadRequest { chat("""{"messages":[{"role":"user","content":[{"type":"video","x":1}]}]}""") }
    }

    @Test
    fun `completion prompts and options`() {
        val p = RequestParser.completion(json("""{"prompt":["a","b"],"n":2,"echo":true,"logprobs":3,"stop":"\n"}"""))
        assertEquals(listOf("a", "b"), p.prompts)
        assertEquals(2, p.n)
        assertTrue(p.echo)
        assertEquals(3, p.params.logprobs)
        assertTrue(p.required.containsAll(listOf(Capability.RAW_COMPLETION, Capability.LOGPROBS, Capability.STOP)))
        assertBadRequest { RequestParser.completion(json("""{"prompt":"a","suffix":"b"}""")) }
        assertBadRequest { RequestParser.completion(json("""{"prompt":[[1,2]]}""")) }
    }

    @Test
    fun `embedding inputs`() {
        assertEquals(listOf("a"), RequestParser.embeddings(json("""{"input":"a"}""")).texts)
        assertEquals(2, RequestParser.embeddings(json("""{"input":["a","b"]}""")).count)
        assertArrayEquals(intArrayOf(1, 2), RequestParser.embeddings(json("""{"input":[1,2]}""")).tokens!!.single())
        assertEquals(2, RequestParser.embeddings(json("""{"input":[[1],[2,3]]}""")).tokens!!.size)
        assertTrue(RequestParser.embeddings(json("""{"input":"a","encoding_format":"base64"}""")).base64)
        assertBadRequest { RequestParser.embeddings(json("""{"input":"a","encoding_format":"int8"}""")) }
        assertBadRequest { RequestParser.embeddings(json("""{"input":[]}""")) }
    }

    @Test
    fun `json schema is passed as an object`() {
        val p = chat("""{"messages":[{"role":"user","content":"a"}],"response_format":{"type":"json_object","schema":{"type":"object","properties":{"x":{"type":"integer"}}}}}""")
        assertTrue(p.options.jsonSchema!!["properties"] is JsonObject)
    }
}
