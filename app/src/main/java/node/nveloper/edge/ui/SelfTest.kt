package node.nveloper.edge.ui

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/** Calls the app's own server over loopback, the same way an external client would. */
object SelfTest {
    suspend fun run(port: Int, apiKey: String): String = withContext(Dispatchers.IO) {
        val base = "http://127.0.0.1:$port"
        val out = StringBuilder()
        try {
            val (healthCode, health) = request("GET", "$base/health", apiKey)
            out.appendLine("GET /health → $healthCode $health")
            val (modelsCode, models) = request("GET", "$base/v1/models", apiKey)
            out.appendLine("GET /v1/models → $modelsCode ${models.take(160)}")

            val body = """{"model":"local","stream":true,"messages":[{"role":"user","content":"Hello! Introduce yourself in one sentence."}]}"""
            val conn = open("POST", "$base/v1/chat/completions", apiKey)
            val start = SystemClock.elapsedRealtime()
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code != 200) {
                out.appendLine("POST /v1/chat/completions → $code ${conn.errorStream?.bufferedReader()?.readText()}")
                return@withContext out.toString()
            }
            var chunks = 0
            var ttft = -1L
            val text = StringBuilder()
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ")
                    if (data == "[DONE]") break
                    val obj = Json.parseToJsonElement(data).jsonObject
                    if (obj["error"] != null) out.appendLine("stream error: ${obj["error"]}")
                    val content = (obj["choices"]?.jsonArray?.firstOrNull() as? JsonObject)
                        ?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.content
                    if (!content.isNullOrEmpty()) {
                        if (ttft < 0) ttft = SystemClock.elapsedRealtime() - start
                        chunks++
                        text.append(content)
                    }
                }
            }
            val total = SystemClock.elapsedRealtime() - start
            out.appendLine("POST /v1/chat/completions (stream) → 200, $chunks chunks, TTFT ${ttft}ms, total ${total}ms")
            out.appendLine("reply: ${text.toString().take(300)}")
        } catch (e: Exception) {
            out.appendLine("error: ${e.javaClass.simpleName}: ${e.message}")
        }
        out.toString()
    }

    private fun open(method: String, url: String, apiKey: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3_000
            readTimeout = 120_000
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            if (method == "POST") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }

    private fun request(method: String, url: String, apiKey: String): Pair<Int, String> {
        val conn = open(method, url, apiKey)
        val code = conn.responseCode
        val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        return code to text.trim()
    }
}
