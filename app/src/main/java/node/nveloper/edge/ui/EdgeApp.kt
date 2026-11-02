package node.nveloper.edge.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import node.nveloper.edge.AppContainer
import node.nveloper.edge.R
import node.nveloper.edge.inference.CacheStats
import node.nveloper.edge.inference.InferenceManager
import node.nveloper.edge.settings.BackendKind
import node.nveloper.edge.system.DeviceMonitor
import node.nveloper.edge.system.DeviceSnapshot
import node.nveloper.edge.system.OemPowerGuide
import node.nveloper.edge.service.ServerKeeper
import node.nveloper.edge.ui.screens.DashboardScreen
import node.nveloper.edge.ui.screens.LogsScreen
import node.nveloper.edge.ui.screens.ModelsScreen
import node.nveloper.edge.ui.screens.SettingsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Tab(@StringRes val label: Int, @DrawableRes val icon: Int) {
    SERVER(R.string.tab_server, R.drawable.ic_server),
    MODELS(R.string.tab_models, R.drawable.ic_model),
    SETTINGS(R.string.tab_settings, R.drawable.ic_settings),
    LOGS(R.string.tab_logs, R.drawable.ic_logs),
}

/** Things only the Activity can do. */
class PlatformActions(
    val requestBatteryExemption: () -> Unit,
    /** Opens the first vendor settings screen that exists (app details as the fallback). */
    val openPowerScreens: (List<OemPowerGuide.Screen>) -> Unit,
    val copyToClipboard: (String) -> Unit,
    /** Opens the system file picker; the chosen file is imported into the model directory. */
    val pickModelFile: () -> Unit,
    val openUrl: (String) -> Unit,
)

/** Values that have no Flow and are polled while the UI is visible. */
data class LiveInfo(val device: DeviceSnapshot, val ips: List<String>, val cache: CacheStats?, val keeper: ServerKeeper.Stats)

@Composable
private fun rememberLiveInfo(container: AppContainer): State<LiveInfo> {
    val context = LocalContext.current
    fun read() = LiveInfo(
        container.device.snapshot(), DeviceMonitor.ipv4Addresses(), container.inference.cacheStats(), ServerKeeper.stats(context),
    )
    return produceState(initialValue = remember { read() }) {
        while (true) {
            delay(2_000)
            value = read()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdgeApp(container: AppContainer, actions: PlatformActions) {
    var tab by rememberSaveable { mutableStateOf(Tab.SERVER) }
    val live by rememberLiveInfo(container)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedText = stringResource(R.string.copied)
    val copy: (String) -> Unit = { text ->
        actions.copyToClipboard(text)
        scope.launch { snackbar.showSnackbar(copiedText) }
    }
    val background = MaterialTheme.colorScheme.surfaceContainer

    Scaffold(
        containerColor = background,
        topBar = {
            TopAppBar(
                title = { Text(if (tab == Tab.SERVER) stringResource(R.string.app_name) else stringResource(tab.label), style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background),
                actions = {
                    when (tab) {
                        Tab.MODELS -> IconButton(onClick = { container.models.refresh() }) {
                            Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.refresh))
                        }
                        Tab.LOGS -> IconButton(onClick = { container.log.clear() }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.clear_logs))
                        }
                        else -> Unit
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(painterResource(t.icon), contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        GpuFailureDialog(container)
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.SERVER -> DashboardScreen(container, live, copy, onOpenModels = { tab = Tab.MODELS }, modifier)
            Tab.MODELS -> ModelsScreen(container, live, actions, modifier)
            Tab.SETTINGS -> SettingsScreen(container, live, actions, modifier)
            Tab.LOGS -> LogsScreen(container, modifier)
        }
    }
}

/**
 * Asks what to do when the model could not be put on the GPU: another GPU accelerator, CPU (only
 * if the user allowed it in Settings), or nothing. There is never an automatic CPU fallback.
 */
@Composable
fun GpuFailureDialog(container: AppContainer) {
    val state by container.inference.state.collectAsState()
    val settings by container.settings.flow.collectAsState()
    val context = LocalContext.current
    val failed = state as? InferenceManager.State.Failed
    val gpu = failed?.gpu ?: return
    var dismissed by remember { mutableStateOf<InferenceManager.State?>(null) }
    if (failed === dismissed) return

    fun switchTo(kind: BackendKind) {
        dismissed = failed
        ServerKeeper.dismissGpuFailure(context)
        container.settings.update { it.copy(backend = kind) }
        container.reloadBackend()
    }

    val backend = settings.backend
    val otherGpus = if (backend.isLlama) {
        listOf(BackendKind.LLAMA_VULKAN to "Vulkan", BackendKind.LLAMA_OPENCL to "OpenCL").filter { it.first != backend }
    } else {
        emptyList()
    }
    val cpu = if (backend.isLlama) BackendKind.LLAMA_CPU else BackendKind.LITERT_CPU
    AlertDialog(
        onDismissRequest = { dismissed = failed },
        title = { Text(stringResource(R.string.gpu_failed_title)) },
        text = { Text(stringResource(R.string.gpu_failed_body, gpu.accelerator, gpu.reason)) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                otherGpus.forEach { (kind, label) ->
                    TextButton(onClick = { switchTo(kind) }) { Text(stringResource(R.string.try_accelerator, label)) }
                }
                if (settings.allowCpu) TextButton(onClick = { switchTo(cpu) }) { Text(stringResource(R.string.run_on_cpu)) }
                TextButton(onClick = { dismissed = failed }) { Text(stringResource(R.string.close)) }
            }
        },
    )
}
