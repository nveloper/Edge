package node.nveloper.edge.models

import android.content.Context
import android.net.Uri
import node.nveloper.edge.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal Hugging Face Hub client for finding and listing GGUF files (public REST API). */
object HuggingFace {
    data class Repo(val id: String, val downloads: Long, val likes: Long, val gated: Boolean)

    /** A downloadable GGUF; a split model lists all its [parts] (first part's path as [path]). */
    data class RepoFile(val path: String, val sizeBytes: Long, val parts: List<Part> = listOf(Part(path, sizeBytes))) {
        data class Part(val path: String, val sizeBytes: Long)

        val name: String get() = path.substringAfterLast('/')

        /** Quantization tag parsed from the file name, e.g. "Q4_K_M", "Q8_0", "F16". */
        val quant: String?
            get() = QUANT.find(name)?.value?.uppercase()

        val isSplit: Boolean get() = parts.size > 1

        /** Multimodal projector, used next to a chat model for image/audio input. */
        val isMmproj: Boolean get() = name.contains("mmproj", ignoreCase = true)
    }

    /** A Hub error whose text comes from string resources; see [message]. */
    class HubException(val res: Int, val code: Int) : IOException("HTTP $code")

    /** Localized text for errors thrown by this client, null for anything else. */
    fun message(context: Context, error: Throwable): String? = when (error) {
        is HubException -> context.getString(error.res, error.code)
        else -> error.message
    }

    private val QUANT = Regex("""(?i)(IQ\d_[A-Z0-9_]+|Q\d_K(_[SML])?|Q\d_\d|Q\d_K|BF16|F16|F32|TQ\d_\d)""")
    private val SPLIT = Regex("""^(.*)-(\d{5})-of-(\d{5})\.gguf$""")

    fun resolveUrl(repo: String, path: String) =
        "https://huggingface.co/$repo/resolve/main/${path.split('/').joinToString("/") { Uri.encode(it) }}?download=true"

    fun pageUrl(repo: String) = "https://huggingface.co/$repo"

    /** Repos with GGUF files matching [query], most downloaded first. */
    suspend fun search(query: String, token: String): List<Repo> = withContext(Dispatchers.IO) {
        val url = "https://huggingface.co/api/models?filter=gguf&sort=downloads&direction=-1&limit=40" +
            if (query.isBlank()) "" else "&search=" + Uri.encode(query.trim())
        (get(url, token) as JsonArray).map { el ->
            val o = el.jsonObject
            Repo(
                id = o.str("id"),
                downloads = o["downloads"]?.jsonPrimitive?.longOrNull ?: 0,
                likes = o["likes"]?.jsonPrimitive?.longOrNull ?: 0,
                gated = o["gated"]?.jsonPrimitive?.let { it.booleanOrNull ?: (it.contentOrNull != null && it.contentOrNull != "false") } ?: false,
            )
        }
    }

    /** GGUF files in a repo (recursively): models smallest first with split parts grouped, then projectors. */
    suspend fun ggufFiles(repo: String, token: String): List<RepoFile> = withContext(Dispatchers.IO) {
        val files = (get("https://huggingface.co/api/models/$repo/tree/main?recursive=true", token) as JsonArray)
            .map { it.jsonObject }
            .filter { it.str("type") == "file" && it.str("path").endsWith(".gguf", ignoreCase = true) }
            .map { RepoFile.Part(it.str("path"), it["size"]?.jsonPrimitive?.longOrNull ?: 0) }
        val (split, single) = files.partition { SPLIT.matches(it.path.substringAfterLast('/')) }
        val grouped = split
            .groupBy { part -> SPLIT.find(part.path.substringAfterLast('/'))!!.let { part.path.substringBeforeLast('/', "") + "/" + it.groupValues[1] + "/" + it.groupValues[3] } }
            .map { (_, parts) ->
                val sorted = parts.sortedBy { it.path }
                RepoFile(sorted.first().path, sorted.sumOf { it.sizeBytes }, sorted)
            }
        (single.map { RepoFile(it.path, it.sizeBytes) } + grouped).sortedWith(compareBy({ it.isMmproj }, { it.sizeBytes }))
    }

    private fun get(url: String, token: String): kotlinx.serialization.json.JsonElement {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        val code = connection.responseCode
        if (code == 401 || code == 403) {
            throw HubException(if (token.isBlank()) R.string.err_hub_gated else R.string.err_hub_denied, code)
        }
        if (code != 200) throw HubException(R.string.err_hub_http, code)
        return connection.inputStream.bufferedReader().use { Json.parseToJsonElement(it.readText()) }
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}
