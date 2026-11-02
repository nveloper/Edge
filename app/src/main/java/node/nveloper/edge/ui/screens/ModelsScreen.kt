package node.nveloper.edge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import node.nveloper.edge.AppContainer
import node.nveloper.edge.R
import node.nveloper.edge.inference.InferenceManager
import node.nveloper.edge.models.CatalogModel
import node.nveloper.edge.models.DownloadState
import node.nveloper.edge.models.ImportState
import node.nveloper.edge.models.LocalModel
import node.nveloper.edge.models.ModelFormat
import node.nveloper.edge.models.formatBytes
import node.nveloper.edge.settings.BackendKind
import node.nveloper.edge.ui.LiveInfo
import node.nveloper.edge.ui.PlatformActions
import node.nveloper.edge.ui.components.Banner
import node.nveloper.edge.ui.components.IconBadge
import node.nveloper.edge.ui.components.SectionHeader
import node.nveloper.edge.ui.components.StatusPill
import node.nveloper.edge.ui.components.Tone
import node.nveloper.edge.ui.theme.LocalStatusColors
import java.io.File
import kotlin.math.roundToInt

@Composable
fun ModelsScreen(container: AppContainer, live: LiveInfo, actions: PlatformActions, modifier: Modifier = Modifier) {
    val store = container.models
    val settings by container.settings.flow.collectAsState()
    val state by container.inference.state.collectAsState()
    val local by store.local.collectAsState()
    val mmprojFiles by store.mmproj.collectAsState()
    val downloads by store.downloads.collectAsState(initial = emptyMap())
    val import by store.import.collectAsState()
    var urlDialog by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<LocalModel?>(null) }
    var browsing by rememberSaveable { mutableStateOf(false) }
    // Collapsed unless a catalog model is already on the device or downloading.
    var catalogOpen by rememberSaveable {
        mutableStateOf(store.catalog.any { c -> store.isDownloaded(c) || store.partialBytes(c.fileName) > 0 })
    }
    val deviceRamGb = live.device.totalRamMb / 1000.0 // marketing GB, roughly what spec sheets say
    val scope = rememberCoroutineScope()

    if (browsing) {
        HfBrowser(container, actions, deviceRamGb, onClose = { browsing = false; store.refresh() }, modifier = modifier)
        return
    }

    // Pick up files that appeared outside the app (adb push) or finished downloading.
    LaunchedEffect(downloads.keys) { store.refresh() }

    val activeFile = if (settings.backend == BackendKind.MOCK) null else File(settings.modelPath)
    fun activeState(file: File): ActiveState? = when {
        activeFile?.absolutePath != file.absolutePath -> null
        state is InferenceManager.State.Ready -> ActiveState.IN_USE
        state is InferenceManager.State.Loading -> ActiveState.LOADING
        state is InferenceManager.State.Failed -> ActiveState.FAILED
        else -> ActiveState.SELECTED
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ActiveModelCard(container, state, settings.backend, settings.allowCpu, activeFile, local, mmprojFiles, settings.mmproj) }

        import?.let { imp ->
            item {
                when (imp) {
                    is ImportState.Copying -> Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.importing, imp.name), style = MaterialTheme.typography.titleSmall)
                            if (imp.total > 0) {
                                LinearProgressIndicator(progress = { imp.bytes.toFloat() / imp.total }, modifier = Modifier.fillMaxWidth())
                                Text("${formatBytes(imp.bytes)} / ${formatBytes(imp.total)}", style = MaterialTheme.typography.bodySmall)
                            } else {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                    }
                    is ImportState.Done -> Banner(Tone.SUCCESS, stringResource(R.string.imported, imp.name)) {
                        TextButton(onClick = store::dismissImport) { Text(stringResource(R.string.ok)) }
                    }
                    is ImportState.Failed -> Banner(Tone.ERROR, imp.message) {
                        TextButton(onClick = store::dismissImport) { Text(stringResource(R.string.close)) }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { browsing = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(painterResource(R.drawable.ic_search), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.find_gguf))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = actions.pickModelFile, modifier = Modifier.weight(1f)) {
                        Icon(painterResource(R.drawable.ic_file), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.import_file))
                    }
                    OutlinedButton(onClick = { urlDialog = true }, modifier = Modifier.weight(1f)) {
                        Icon(painterResource(R.drawable.ic_link), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_url))
                    }
                }
            }
        }

        // Models on the device first: the point is running models the user picked themselves.
        val mine = local.filter { it.catalog == null }
        val customDownloads = downloads.filterKeys { name -> store.catalog.none { it.fileName == name } }
        item { SectionHeader(stringResource(R.string.section_my_models)) }
        if (mine.isEmpty() && customDownloads.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.my_models_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        items(customDownloads.entries.toList(), key = { "dl:" + it.key }) { (name, dl) ->
            DownloadOnlyCard(name, dl, onCancel = { store.cancelDownload(name) }, onDismiss = { store.dismissDownload(name) })
        }
        items(mine, key = { it.file.absolutePath }) { model ->
            val isEmbedding = settings.embeddingModelPath == model.file.absolutePath
            LocalModelCard(
                model = model,
                activeLabel = activeState(model.file),
                isEmbedding = isEmbedding,
                onUse = { container.useModel(model.file) },
                onEmbedding = {
                    container.settings.update { it.copy(embeddingModelPath = if (isEmbedding) "" else model.file.absolutePath) }
                    scope.launch { container.embeddings.unload() }
                },
                onDelete = { deleting = model },
            )
        }

        if (mmprojFiles.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.section_mmproj)) }
            items(mmprojFiles, key = { "mmproj:" + it.absolutePath }) { file ->
                MmprojCard(file, onDelete = {
                    container.settings.update { s -> s.copy(mmproj = s.mmproj.filterValues { it != file.absolutePath }) }
                    store.deleteFile(file)
                })
            }
        }

        item {
            SectionHeader(stringResource(R.string.section_litert)) {
                IconButton(onClick = { catalogOpen = !catalogOpen }) {
                    Icon(
                        painterResource(if (catalogOpen) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                        contentDescription = stringResource(if (catalogOpen) R.string.collapse else R.string.expand),
                    )
                }
            }
        }
        if (catalogOpen) items(store.catalog, key = { it.fileName }) { model ->
            val file = local.firstOrNull { it.file.name == model.fileName }
            CatalogCard(
                model = model,
                local = file,
                download = downloads[model.fileName],
                partialBytes = store.partialBytes(model.fileName),
                activeLabel = file?.let { activeState(it.file) },
                deviceRamGb = deviceRamGb,
                hasToken = settings.hfToken.isNotBlank(),
                onDownload = { store.download(model) },
                onCancel = { store.cancelDownload(model.fileName) },
                onDismiss = { store.dismissDownload(model.fileName) },
                onUse = { file?.let { container.useModel(it.file) } },
                onDelete = { deleting = file },
                onOpenPage = { actions.openUrl(model.pageUrl) },
            )
        }

        item { SectionHeader(stringResource(R.string.section_debug)) }
        item {
            MockCard(
                active = settings.backend == BackendKind.MOCK,
                ready = state is InferenceManager.State.Ready,
                tokensPerSecond = settings.mockTokensPerSecond,
                onSpeed = { v -> container.settings.update { it.copy(mockTokensPerSecond = v) } },
                onUse = { container.useMock() },
            )
        }
    }

    if (urlDialog) UrlDialog(onDismiss = { urlDialog = false }, onSubmit = store::downloadUrl)

    deleting?.let { model ->
        val isActive = activeFile?.absolutePath == model.file.absolutePath
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_model)) },
            text = {
                Text(
                    stringResource(R.string.delete_model_body, model.displayName, formatBytes(model.sizeBytes)) +
                        if (isActive) "\n\n" + stringResource(R.string.delete_model_active) else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isActive) container.useMock()
                    store.delete(model)
                    deleting = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveModelCard(
    container: AppContainer,
    state: InferenceManager.State,
    backend: BackendKind,
    allowCpu: Boolean,
    activeFile: File?,
    local: List<LocalModel>,
    mmprojFiles: List<File>,
    mmprojFor: Map<String, String>,
) {
    val scheme = MaterialTheme.colorScheme
    val status = LocalStatusColors.current
    val name = when {
        backend == BackendKind.MOCK -> stringResource(R.string.mock_debug)
        activeFile == null -> stringResource(R.string.no_model_selected)
        else -> local.firstOrNull { it.file.absolutePath == activeFile.absolutePath }?.displayName ?: activeFile.nameWithoutExtension
    }
    val (detail, pill) = when (state) {
        InferenceManager.State.Empty -> stringResource(R.string.not_loaded) to (stringResource(R.string.pill_idle) to status.idle)
        is InferenceManager.State.Loading -> stringResource(R.string.loading_ellipsis) to (stringResource(R.string.pill_loading) to status.warn)
        is InferenceManager.State.Ready ->
            stringResource(R.string.ready_detail, state.description, state.loadMs / 1000.0) to (stringResource(R.string.pill_ready) to status.ok)
        is InferenceManager.State.Failed -> state.error to (stringResource(R.string.pill_failed) to status.bad)
    }
    Card(colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLowest), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.serving_model), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(R.drawable.ic_model, scheme.primaryContainer, scheme.onPrimaryContainer, size = 48)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleLarge)
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
                StatusPill(pill.first, pill.second)
            }
            if (state is InferenceManager.State.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (backend != BackendKind.MOCK) {
                Text(stringResource(R.string.accelerator), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                // CPU is offered only when the user allowed it in Settings.
                val options = if (backend.isLlama) {
                    listOf(BackendKind.LLAMA_VULKAN to "Vulkan", BackendKind.LLAMA_OPENCL to "OpenCL", BackendKind.LLAMA_CPU to "CPU")
                } else {
                    listOf(BackendKind.LITERT_GPU to "GPU", BackendKind.LITERT_CPU to "CPU")
                }.filter { (kind, _) -> allowCpu || !kind.isCpu || kind == backend }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { i, (kind, label) ->
                        SegmentedButton(
                            selected = backend == kind,
                            onClick = {
                                if (backend != kind) {
                                    container.settings.update { it.copy(backend = kind) }
                                    container.reloadBackend()
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(label) }
                    }
                }
            }
            if (backend.isLlama && activeFile != null && mmprojFiles.isNotEmpty()) {
                val current = mmprojFor[activeFile.absolutePath]?.let(::File)
                var open by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.mmproj_label), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { open = true }) {
                            Text(current?.name ?: stringResource(R.string.none), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            (listOf<File?>(null) + mmprojFiles).forEach { file ->
                                DropdownMenuItem(
                                    text = { Text(file?.name ?: stringResource(R.string.none)) },
                                    onClick = {
                                        open = false
                                        container.settings.update { s ->
                                            val map = if (file == null) s.mmproj - activeFile.absolutePath else s.mmproj + (activeFile.absolutePath to file.absolutePath)
                                            s.copy(mmproj = map)
                                        }
                                        container.reloadBackend()
                                    },
                                )
                            }
                        }
                    }
                }
            }
            FilledTonalButton(
                onClick = { container.reloadBackend() },
                enabled = state !is InferenceManager.State.Loading && (backend == BackendKind.MOCK || activeFile != null),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.reload))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogCard(
    model: CatalogModel,
    local: LocalModel?,
    download: DownloadState?,
    partialBytes: Long,
    activeLabel: ActiveState?,
    deviceRamGb: Double,
    hasToken: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
    onOpenPage: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val status = LocalStatusColors.current
    Card(colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLowest), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    R.drawable.ic_model,
                    if (model.recommended) scheme.primary else scheme.surfaceContainerHigh,
                    if (model.recommended) scheme.onPrimary else scheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    // Tags wrap under long names instead of being squeezed off the edge.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(model.name, style = MaterialTheme.typography.titleMedium)
                        if (model.recommended) SmallTag(stringResource(R.string.tag_recommended), scheme.primary)
                        if (model.gated) SmallTag(stringResource(R.string.tag_token), status.warn)
                        if (deviceRamGb + 0.5 < model.minRamGb) SmallTag(stringResource(R.string.low_ram), status.bad)
                    }
                    Text(
                        "${formatBytes(model.sizeBytes)} · RAM ${model.minRamGb}GB+ · ${contextLabel(model.contextLength)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (activeLabel != null) ActivePill(activeLabel)
            }
            Text(model.localizedDescription(), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)

            when {
                download is DownloadState.Running || download is DownloadState.Queued -> {
                    val running = download as? DownloadState.Running
                    if (running != null && running.total > 0) {
                        LinearProgressIndicator(progress = { running.fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            running?.let {
                                "${(it.fraction * 100).toInt()}% · ${formatBytes(it.bytes)} / ${formatBytes(it.total)} · ${formatBytes(it.bytesPerSecond)}/s"
                            } ?: stringResource(R.string.queued),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.pause)) }
                    }
                }
                download is DownloadState.Failed -> {
                    Text(download.message, style = MaterialTheme.typography.bodySmall, color = status.bad)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onDownload) { Text(stringResource(R.string.retry)) }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                        if (model.gated) TextButton(onClick = onOpenPage) { Text(stringResource(R.string.model_page)) }
                    }
                }
                local != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (activeLabel == null) {
                        Button(onClick = onUse) { Text(stringResource(R.string.use)) }
                    } else {
                        Text(stringResource(R.string.downloaded), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDelete) {
                        Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.delete), tint = scheme.onSurfaceVariant)
                    }
                }
                else -> {
                    if (model.gated && !hasToken) {
                        Text(
                            stringResource(R.string.gated_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onDownload) {
                            Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (partialBytes > 0) stringResource(R.string.resume_percent, (partialBytes * 100 / model.sizeBytes).toInt())
                                else stringResource(R.string.download_size, formatBytes(model.sizeBytes)),
                            )
                        }
                        if (model.gated) TextButton(onClick = onOpenPage) { Text(stringResource(R.string.model_page)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalModelCard(
    model: LocalModel,
    activeLabel: ActiveState?,
    isEmbedding: Boolean,
    onUse: () -> Unit,
    onEmbedding: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLowest), shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(R.drawable.ic_file, scheme.tertiaryContainer, scheme.onTertiaryContainer)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(model.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        model.format.label,
                        formatBytes(model.sizeBytes),
                        if (model.expectedParts > 1) stringResource(R.string.parts_count, model.expectedParts) else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                if (model.missingParts > 0) {
                    Text(stringResource(R.string.missing_parts, model.missingParts), style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.bad)
                }
                if (model.format == ModelFormat.GGUF) {
                    FilterChip(selected = isEmbedding, onClick = onEmbedding, label = { Text(stringResource(R.string.embedding_chip)) })
                }
            }
            if (activeLabel != null) {
                ActivePill(activeLabel)
            } else if (model.missingParts == 0) {
                TextButton(onClick = onUse) { Text(stringResource(R.string.use)) }
            }
            IconButton(onClick = onDelete) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.delete), tint = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MmprojCard(file: File, onDelete: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLowest), shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(R.drawable.ic_file, scheme.secondaryContainer, scheme.onSecondaryContainer)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("mmproj · ${formatBytes(file.length())}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.delete), tint = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DownloadOnlyCard(name: String, download: DownloadState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLowest), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            when (download) {
                is DownloadState.Failed -> {
                    Text(download.message, style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.bad)
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                }
                is DownloadState.Running -> {
                    if (download.total > 0) {
                        LinearProgressIndicator(progress = { download.fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${formatBytes(download.bytes)}${if (download.total > 0) " / " + formatBytes(download.total) else ""} · ${formatBytes(download.bytesPerSecond)}/s",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.pause)) }
                    }
                }
                DownloadState.Queued -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

@Composable
private fun MockCard(active: Boolean, ready: Boolean, tokensPerSecond: Float, onSpeed: (Float) -> Unit, onUse: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLowest), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(R.drawable.ic_code, scheme.surfaceContainerHigh, scheme.onSurfaceVariant)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.mock_backend), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.mock_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (active) {
                    StatusPill(stringResource(if (ready) R.string.active_in_use else R.string.active_selected), LocalStatusColors.current.ok)
                } else {
                    TextButton(onClick = onUse) { Text(stringResource(R.string.use)) }
                }
            }
            if (active) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.mock_speed), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${tokensPerSecond.toInt()} tok/s", style = MaterialTheme.typography.titleSmall)
                }
                Slider(value = tokensPerSecond, onValueChange = { onSpeed(it.roundToInt().toFloat()) }, valueRange = 1f..60f)
            }
        }
    }
}

@Composable
private fun UrlDialog(onDismiss: () -> Unit, onSubmit: (String) -> String?) {
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.url_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.url_help),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    placeholder = { Text("https://huggingface.co/…/model.gguf") },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = onSubmit(url)
                if (error == null) onDismiss()
            }) { Text(stringResource(R.string.download)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SmallTag(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .padding(top = 1.dp)
            .background(color.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Where the selected model is in its load cycle, shown as a pill on its card. */
enum class ActiveState(val label: Int) {
    IN_USE(R.string.active_in_use),
    LOADING(R.string.active_loading),
    FAILED(R.string.active_failed),
    SELECTED(R.string.active_selected),
}

@Composable
private fun ActivePill(state: ActiveState) {
    val status = LocalStatusColors.current
    val color = when (state) {
        ActiveState.FAILED -> status.bad
        ActiveState.LOADING -> status.warn
        else -> status.ok
    }
    StatusPill(stringResource(state.label), color)
}

private fun contextLabel(tokens: Int) = if (tokens >= 1000) "ctx ${tokens / 1000}K" else "ctx $tokens"
