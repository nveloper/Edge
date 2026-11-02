package node.nveloper.edge.models

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Locale

data class SamplerDefaults(val topK: Int, val topP: Double, val temperature: Double) {
    companion object {
        val GEMMA = SamplerDefaults(topK = 64, topP = 0.95, temperature = 1.0)
    }
}

/** A downloadable model, pinned to a Hugging Face repo and commit (see assets/model_catalog.json). */
data class CatalogModel(
    val name: String,
    val repo: String,
    val fileName: String,
    val commit: String,
    val sizeBytes: Long,
    val minRamGb: Int,
    val contextLength: Int,
    /** Needs a Hugging Face token and an accepted license on the model page. */
    val gated: Boolean,
    val recommended: Boolean,
    /** Description by language code ("en", "ko", …); see [localizedDescription]. */
    val descriptions: Map<String, String>,
    val sampler: SamplerDefaults,
) {
    val downloadUrl: String get() = "https://huggingface.co/$repo/resolve/$commit/$fileName?download=true"
    val pageUrl: String get() = "https://huggingface.co/$repo"

    /** Description in the system language, falling back to English. */
    fun localizedDescription(): String =
        descriptions[Locale.getDefault().language] ?: descriptions["en"] ?: descriptions.values.firstOrNull().orEmpty()
}

object ModelCatalog {
    fun load(context: Context): List<CatalogModel> {
        val root = context.assets.open("model_catalog.json").bufferedReader().use { Json.parseToJsonElement(it.readText()) }
        return root.jsonObject["models"]!!.jsonArray.map { it.jsonObject.toModel() }
    }

    private fun JsonObject.toModel(): CatalogModel {
        val sampler = this["sampler"]?.jsonObject
        return CatalogModel(
            name = str("name"),
            repo = str("repo"),
            fileName = str("file"),
            commit = str("commit"),
            sizeBytes = getValue("sizeBytes").jsonPrimitive.long,
            minRamGb = getValue("minRamGb").jsonPrimitive.int,
            contextLength = getValue("contextLength").jsonPrimitive.int,
            gated = getValue("gated").jsonPrimitive.boolean,
            recommended = this["recommended"]?.jsonPrimitive?.booleanOrNull ?: false,
            descriptions = getValue("description").jsonObject.mapValues { it.value.jsonPrimitive.content },
            sampler = if (sampler == null) {
                SamplerDefaults.GEMMA
            } else {
                SamplerDefaults(
                    topK = sampler.getValue("topK").jsonPrimitive.int,
                    topP = sampler.getValue("topP").jsonPrimitive.double,
                    temperature = sampler.getValue("temperature").jsonPrimitive.double,
                )
            },
        )
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
}
