package node.nveloper.edge.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import node.nveloper.edge.AppContainer
import node.nveloper.edge.R
import node.nveloper.edge.models.DownloadState
import node.nveloper.edge.models.HuggingFace
import node.nveloper.edge.models.formatBytes
import node.nveloper.edge.ui.PlatformActions
import node.nveloper.edge.ui.components.Banner
import node.nveloper.edge.ui.components.IconBadge
import node.nveloper.edge.ui.components.Tone
import node.nveloper.edge.ui.theme.LocalStatusColors

/**
 * Browse Hugging Face for GGUF models and download a single quantization, like AI Edge
 * Gallery's HF explorer but for any GGUF repo.
 */
@Composable
fun HfBrowser(
    container: AppContainer,
    actions: PlatformActions,
    deviceRamGb: Double,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by container.settings.flow.collectAsState()
    val downloads by container.models.downloads.collectAsState(initial = emptyMap())
    val local by container.models.local.collectAsState()
    val mmproj by container.models.mmproj.collectAsState()
    val localNames = remember(local, mmproj) { (local.flatMap { m -> m.parts.map { it.name } } + mmproj.map { it.name }).toSet() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var query by rememberSaveable { mutableStateOf("") }
    var repos by remember { mutableStateOf<List<HuggingFace.Repo>>(emptyList()) }
    var repo by remember { mutableStateOf<HuggingFace.Repo?>(null) }
    var files by remember { mutableStateOf<List<HuggingFace.RepoFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun search() {
        loading = true
        error = null
        scope.launch {
            runCatching { HuggingFace.search(query, settings.hfToken) }
                .onSuccess { repos = it }
                .onFailure { error = HuggingFace.message(context, it) ?: context.getString(R.string.search_failed) }
            loading = false
        }
    }

    fun open(r: HuggingFace.Repo) {
        repo = r
        files = emptyList()
        loading = true
        error = null
        scope.launch {
            runCatching { HuggingFace.ggufFiles(r.id, settings.hfToken) }
                .onSuccess { files = it }
                .onFailure { error = HuggingFace.message(context, it) ?: context.getString(R.string.files_failed) }
            loading = false
        }
    }

    LaunchedEffect(Unit) { if (repos.isEmpty()) search() }
    BackHandler { if (repo != null) repo = null else onClose() }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (repo != null) repo = null else onClose() }) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
            }
            Text(
                repo?.id ?: stringResource(R.string.find_gguf),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            repo?.let { r ->
                TextButton(onClick = { actions.openUrl(HuggingFace.pageUrl(r.id)) }) { Text(stringResource(R.string.page)) }
            }
        }

        if (repo == null) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.hf_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                trailingIcon = {
                    IconButton(onClick = { search() }) {
                        Icon(painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.search))
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        error?.let { Banner(Tone.ERROR, it, Modifier.padding(16.dp)) }

        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (repo == null) {
                    items(repos, key = { it.id }) { r -> RepoCard(r, onClick = { open(r) }) }
                } else {
                    item {
                        Text(
                            stringResource(R.string.quant_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(files, key = { it.path }) { f ->
                        val store = container.models
                        val names = f.parts.map { store.localName(repo!!.id, it.path) }
                        FileCard(
                            file = f,
                            deviceRamGb = deviceRamGb,
                            downloaded = names.all { it in localNames },
                            download = names.firstNotNullOfOrNull { downloads[it] },
                            onDownload = {
                                f.parts.zip(names).filter { (_, name) -> name !in localNames }.forEach { (part, name) ->
                                    store.downloadFromHub(repo!!.id, part.path, part.sizeBytes, name)
                                }
                            },
                            onCancel = { names.forEach(store::cancelDownload) },
                        )
                    }
                    if (!loading && files.isEmpty() && error == null) {
                        item { Text(stringResource(R.string.no_gguf), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
        }
    }
}

@Composable
private fun RepoCard(repo: HuggingFace.Repo, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLowest),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(R.drawable.ic_model, scheme.secondaryContainer, scheme.onSecondaryContainer)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(repo.id.substringAfter('/'), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${repo.id.substringBefore('/')} · ↓ ${compact(repo.downloads)} · ♥ ${compact(repo.likes)}" +
                        if (repo.gated) " · " + stringResource(R.string.gated_repo) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = scheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileCard(
    file: HuggingFace.RepoFile,
    deviceRamGb: Double,
    downloaded: Boolean,
    download: DownloadState?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val status = LocalStatusColors.current
    // Weights plus KV cache and runtime overhead must fit next to the OS and other apps.
    val needGb = file.sizeBytes / 1e9 * 1.25 + 0.5
    val fit = when {
        file.isMmproj -> null
        needGb <= deviceRamGb * 0.55 -> stringResource(R.string.fit_ok) to status.ok
        needGb <= deviceRamGb * 0.75 -> stringResource(R.string.fit_tight) to status.warn
        else -> stringResource(R.string.low_ram) to status.bad
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLowest),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(file.quant ?: "GGUF", style = MaterialTheme.typography.titleMedium)
                Tag(formatBytes(file.sizeBytes), scheme.onSurfaceVariant)
                fit?.let { Tag(it.first, it.second) }
                if (file.isMmproj) Tag("mmproj", scheme.tertiary)
                if (file.quant == "Q4_0" && !file.isMmproj) Tag(stringResource(R.string.tag_opencl), scheme.primary)
                if (file.isSplit) Tag(stringResource(R.string.parts_count, file.parts.size), scheme.onSurfaceVariant)
            }
            Text(file.path, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when {
                downloaded -> Text(stringResource(if (file.isMmproj) R.string.downloaded_attach_in_models else R.string.downloaded_use_in_models), style = MaterialTheme.typography.bodyMedium, color = status.ok)
                download is DownloadState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${(download.fraction * 100).toInt()}% · ${formatBytes(download.bytesPerSecond)}/s",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.pause)) }
                }
                download is DownloadState.Queued -> Text(stringResource(R.string.queued), style = MaterialTheme.typography.bodySmall)
                download is DownloadState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(download.message, style = MaterialTheme.typography.bodySmall, color = status.bad, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDownload) { Text(stringResource(R.string.retry)) }
                }
                else -> FilledTonalButton(onClick = onDownload) {
                    Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.download))
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun compact(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 1_000 -> "%.1fK".format(n / 1e3)
    else -> "$n"
}
