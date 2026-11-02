package node.nveloper.edge.models

import android.content.Context
import node.nveloper.edge.R
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import node.nveloper.edge.log.EventLog
import java.io.File
import java.util.concurrent.TimeUnit

enum class ModelFormat(val extension: String, val label: String) {
    GGUF("gguf", "GGUF"),
    LITERT("litertlm", "LiteRT-LM"),
}

/**
 * A model on disk, matched to its catalog entry when it came from the catalog. A split GGUF
 * (`name-00001-of-00003.gguf`, …) is one model: [file] is the first part, which llama.cpp opens
 * and reads the rest from.
 */
data class LocalModel(
    val file: File,
    val catalog: CatalogModel?,
    val parts: List<File> = listOf(file),
    val expectedParts: Int = 1,
) {
    val displayName: String get() = catalog?.name ?: SPLIT_NAME.find(file.name)?.groupValues?.get(1) ?: file.nameWithoutExtension
    val format: ModelFormat get() = if (file.extension == "gguf") ModelFormat.GGUF else ModelFormat.LITERT
    val sizeBytes: Long get() = parts.sumOf { it.length() }
    val missingParts: Int get() = expectedParts - parts.size
}

private val MODEL_EXTENSIONS = ModelFormat.entries.map { it.extension }.toSet()

/** `name-00001-of-00003.gguf` → name, part, total. */
internal val SPLIT_NAME = Regex("""^(.*)-(\d{5})-of-(\d{5})\.gguf$""")

/** GGUF multimodal projectors (mmproj): loaded next to a chat model for image/audio input. */
internal fun isMmprojName(name: String): Boolean = name.endsWith(".gguf", ignoreCase = true) && name.contains("mmproj", ignoreCase = true)

/** Any file this app keeps: models, split parts, projectors. */
internal fun isModelFileName(name: String): Boolean = name.substringAfterLast('.', "") in MODEL_EXTENSIONS

/** Chat models we can serve: not projectors, and only the first part of a split GGUF. */
internal fun isServableModelName(name: String): Boolean =
    name.substringAfterLast('.', "") in MODEL_EXTENSIONS && !isMmprojName(name) &&
        SPLIT_NAME.find(name)?.groupValues?.get(2)?.toInt().let { it == null || it == 1 }

sealed interface DownloadState {
    data object Queued : DownloadState
    data class Running(val bytes: Long, val total: Long, val bytesPerSecond: Long) : DownloadState {
        val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    data class Failed(val message: String) : DownloadState
}

sealed interface ImportState {
    data class Copying(val name: String, val bytes: Long, val total: Long) : ImportState
    data class Done(val name: String) : ImportState
    data class Failed(val message: String) : ImportState
}

/**
 * Owns the model directory: Hugging Face and catalog downloads (WorkManager), imports picked
 * with the system file picker, custom URL downloads, and deletion. Files pushed with adb into the
 * same directory show up after [refresh].
 */
class ModelStore(private val context: Context, private val log: EventLog, private val scope: CoroutineScope) {
    val catalog: List<CatalogModel> = ModelCatalog.load(context)

    /**
     * Models (GGUF, LiteRT-LM) live in app-specific external storage: no permission needed,
     * reachable with `adb push`.
     */
    val dir: File = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }

    /** Also scanned: internal storage, the target of the `run-as` fallback in scripts/push-model.ps1. */
    private val internalDir = File(context.filesDir, "models")

    private val _local = MutableStateFlow(scan())
    val local: StateFlow<List<LocalModel>> = _local

    private val _mmproj = MutableStateFlow(scanMmproj())
    /** Multimodal projector files on the device. */
    val mmproj: StateFlow<List<File>> = _mmproj

    private val _import = MutableStateFlow<ImportState?>(null)
    val import: StateFlow<ImportState?> = _import

    private val work = WorkManager.getInstance(context)

    /** Active or failed downloads, keyed by file name. */
    val downloads: Flow<Map<String, DownloadState>> = work.getWorkInfosByTagFlow(TAG).map { infos ->
        infos.mapNotNull { info ->
            val file = info.tags.firstOrNull { it.startsWith(FILE_TAG) }?.removePrefix(FILE_TAG) ?: return@mapNotNull null
            val state = when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadState.Queued
                WorkInfo.State.RUNNING -> DownloadState.Running(
                    bytes = info.progress.getLong(DownloadWorker.KEY_BYTES, 0),
                    total = info.progress.getLong(DownloadWorker.KEY_TOTAL, 0),
                    bytesPerSecond = info.progress.getLong(DownloadWorker.KEY_SPEED, 0),
                )
                WorkInfo.State.FAILED -> DownloadState.Failed(
                    info.outputData.getString(DownloadWorker.KEY_ERROR) ?: context.getString(R.string.err_download),
                )
                else -> return@mapNotNull null // succeeded or cancelled
            }
            file to state
        }.toMap()
    }

    fun refresh() {
        _local.value = scan()
        _mmproj.value = scanMmproj()
    }

    fun catalogFor(file: File): CatalogModel? = catalog.firstOrNull { it.fileName == file.name }

    fun isDownloaded(model: CatalogModel): Boolean = _local.value.any { it.file.name == model.fileName }

    fun fileFor(model: CatalogModel): File? = _local.value.firstOrNull { it.file.name == model.fileName }?.file

    /** Bytes of a partial download that the next attempt will resume from. */
    fun partialBytes(fileName: String): Long = File(dir, "$fileName.part").let { if (it.isFile) it.length() else 0 }

    fun download(model: CatalogModel) = enqueue(model.downloadUrl, model.fileName, model.name, model.sizeBytes)

    /** Downloads one file of a Hugging Face repo (picked in the HF browser), saved as [fileName]. */
    fun downloadFromHub(repo: String, path: String, sizeBytes: Long, fileName: String = path.substringAfterLast('/')) {
        enqueue(HuggingFace.resolveUrl(repo, path), fileName, fileName.substringBeforeLast('.'), sizeBytes)
    }

    /**
     * Local name for a Hub file. Projectors are often called just `mmproj-F16.gguf` in every repo,
     * so they get the repo name in front to stay apart.
     */
    fun localName(repo: String, path: String): String {
        val name = path.substringAfterLast('/')
        return if (isMmprojName(name)) repo.substringAfter('/') + "." + name else name
    }

    fun deleteFile(file: File) {
        file.delete()
        log.i("models", "deleted ${file.name}")
        refresh()
    }

    /**
     * Adds a model by URL. Hugging Face page links (`/blob/`) are turned into download links.
     * @return an error message, or null when the download was queued.
     */
    fun downloadUrl(rawUrl: String): String? {
        val url = rawUrl.trim().replace("/blob/", "/resolve/")
        if (!url.startsWith("https://")) return context.getString(R.string.err_https)
        val fileName = Uri.parse(url).lastPathSegment.orEmpty()
        if (!isModelFileName(fileName)) return context.getString(R.string.err_model_ext)
        enqueue(url, fileName, fileName.substringBeforeLast('.'), expected = -1)
        return null
    }

    private fun enqueue(url: String, fileName: String, title: String, expected: Long) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_URL to url,
                    DownloadWorker.KEY_FILE to fileName,
                    DownloadWorker.KEY_TITLE to title,
                    DownloadWorker.KEY_EXPECTED to expected,
                ),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(FILE_TAG + fileName)
            .build()
        work.enqueueUniqueWork(WORK_PREFIX + fileName, ExistingWorkPolicy.REPLACE, request)
        log.i("models", "download queued: $fileName")
    }

    fun cancelDownload(fileName: String) {
        work.cancelUniqueWork(WORK_PREFIX + fileName)
        log.i("models", "download cancelled: $fileName (partial data kept for resume)")
    }

    /** Clears a failed download from the list and removes its partial data. */
    fun dismissDownload(fileName: String) {
        work.cancelUniqueWork(WORK_PREFIX + fileName)
        work.pruneWork()
        File(dir, "$fileName.part").delete()
    }

    fun delete(model: LocalModel) {
        model.parts.forEach { part ->
            part.delete()
            File(dir, "${part.name}.part").delete()
        }
        log.i("models", "deleted ${model.file.name}" + if (model.parts.size > 1) " (${model.parts.size} parts)" else "")
        refresh()
    }

    /** Copies a file chosen with the system picker (Downloads, SD card, Drive, …) into [dir]. */
    fun importFrom(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val (name, size) = queryNameAndSize(uri)
            if (name == null || !isModelFileName(name)) {
                _import.value = ImportState.Failed(context.getString(R.string.err_import_ext, name ?: context.getString(R.string.unknown_file)))
                return@launch
            }
            if (size > 0 && dir.usableSpace < size + 64L * 1024 * 1024) {
                _import.value = ImportState.Failed(context.getString(R.string.err_import_space, formatBytes(size)))
                return@launch
            }
            val target = File(dir, name)
            val temp = File(dir, "$name.importing")
            try {
                _import.value = ImportState.Copying(name, 0, size)
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var copied = 0L
                        var lastReport = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            copied += n
                            if (copied - lastReport >= 16L * 1024 * 1024) {
                                lastReport = copied
                                _import.value = ImportState.Copying(name, copied, size)
                            }
                        }
                    }
                }
                if (target.exists()) target.delete()
                temp.renameTo(target)
                log.i("models", "imported $name (${formatBytes(target.length())})")
                refresh()
                _import.value = ImportState.Done(name)
            } catch (e: Exception) {
                temp.delete()
                log.e("models", "import of $name failed", e)
                _import.value = ImportState.Failed(context.getString(R.string.err_import, e.message))
            }
        }
    }

    fun dismissImport() {
        _import.value = null
    }

    private fun queryNameAndSize(uri: Uri): Pair<String?, Long> =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (!c.moveToFirst()) return@use null to -1L
                val name = c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                name to if (sizeIndex >= 0 && !c.isNull(sizeIndex)) c.getLong(sizeIndex) else -1L
            } ?: (null to -1L)

    private fun files(): List<File> =
        listOf(dir, internalDir).distinct()
            .flatMap { it.listFiles()?.toList().orEmpty() }
            .filter { it.isFile }
            .distinctBy { it.name }

    private fun scan(): List<LocalModel> {
        val all = files()
        return all.filter { isServableModelName(it.name) }
            .map { file ->
                val split = SPLIT_NAME.find(file.name)
                if (split == null) {
                    LocalModel(file, catalogFor(file))
                } else {
                    val (prefix, _, total) = split.destructured
                    val parts = all.filter { f -> SPLIT_NAME.find(f.name)?.groupValues?.let { it[1] == prefix && it[3] == total } == true }
                        .sortedBy { it.name }
                    LocalModel(file, null, parts, total.toInt())
                }
            }
            .sortedWith(compareBy({ it.catalog == null }, { it.displayName }))
    }

    private fun scanMmproj(): List<File> = files().filter { isMmprojName(it.name) }.sortedBy { it.name }

    private companion object {
        const val TAG = "model-download"
        const val FILE_TAG = "file:"
        const val WORK_PREFIX = "download:"
    }
}
