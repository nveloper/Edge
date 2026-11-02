package node.nveloper.edge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import node.nveloper.edge.AppContainer
import node.nveloper.edge.R
import node.nveloper.edge.inference.InferenceManager
import node.nveloper.edge.service.InferenceService
import node.nveloper.edge.system.DeviceMonitor
import node.nveloper.edge.system.DeviceSnapshot
import node.nveloper.edge.ui.LiveInfo
import node.nveloper.edge.ui.SelfTest
import node.nveloper.edge.ui.components.Banner
import node.nveloper.edge.ui.components.GroupDivider
import node.nveloper.edge.ui.components.IconBadge
import node.nveloper.edge.ui.components.MonoBlock
import node.nveloper.edge.ui.components.SectionHeader
import node.nveloper.edge.ui.components.SettingRow
import node.nveloper.edge.ui.components.SettingsGroup
import node.nveloper.edge.ui.components.StatTile
import node.nveloper.edge.ui.components.StatusPill
import node.nveloper.edge.ui.components.Tone
import node.nveloper.edge.ui.theme.LocalStatusColors
import node.nveloper.edge.ui.theme.MonoStyle
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(
    container: AppContainer,
    live: LiveInfo,
    onCopy: (String) -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by container.settings.flow.collectAsState()
    val running by InferenceService.running.collectAsState()
    val model by container.inference.state.collectAsState()
    val stats by container.inference.stats.collectAsState()
    val active by container.inference.active.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selfTest by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ServerCard(
                running = running,
                port = settings.port,
                ips = live.ips,
                model = model,
                displayName = container::displayName,
                onCopy = onCopy,
                onToggle = { if (running) InferenceService.stop(context) else InferenceService.start(context) },
            )
        }

        (model as? InferenceManager.State.Failed)?.let { failed ->
            item {
                Banner(Tone.ERROR, failed.error) {
                    TextButton(onClick = onOpenModels) { Text(stringResource(R.string.open_models)) }
                }
            }
        }
        live.keeper.suspendedBy?.let { cause ->
            item { Banner(Tone.WARN, stringResource(R.string.suspended_banner, cause)) }
        }
        if (running && live.device.isEmulator) {
            item {
                val cmd = "adb forward tcp:${settings.port} tcp:${settings.port}"
                Banner(Tone.INFO, stringResource(R.string.emulator_hint, cmd, "http://127.0.0.1:${settings.port}/v1")) {
                    IconButton(onClick = { onCopy(cmd) }) { Icon(painterResource(R.drawable.ic_copy), contentDescription = stringResource(R.string.copy)) }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.section_performance)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    stringResource(R.string.stat_decode), if (stats.completed > 0) "%.1f".format(stats.lastTokensPerSecond) else "–", "tok/s",
                    Modifier.weight(1f), caption = stringResource(R.string.stat_decode_caption, stats.lastTokens),
                )
                StatTile(
                    stringResource(R.string.stat_ttft), if (stats.completed > 0) "${stats.lastTtftMs}" else "–", "ms",
                    Modifier.weight(1f),
                    caption = stats.lastCache?.let { stringResource(if (it.hit) R.string.cache_reused else R.string.full_prefill) } ?: "TTFT",
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    stringResource(R.string.stat_requests), "${stats.completed}", null, Modifier.weight(1f),
                    caption = stringResource(R.string.stat_requests_caption, stats.failed, stats.rejected, active),
                )
                val cache = live.cache
                val total = cache?.let { it.hits + it.misses } ?: 0
                StatTile(
                    stringResource(R.string.stat_cache_hits), if (total > 0) "${cache!!.hits * 100 / total}" else "–", if (total > 0) "%" else null,
                    Modifier.weight(1f),
                    caption = cache?.let { stringResource(R.string.stat_cache_caption, it.size, it.capacity, it.contextTokens) }
                        ?: stringResource(R.string.no_model),
                )
            }
        }

        item { SectionHeader(stringResource(R.string.section_device)) }
        item { DeviceCard(live.device) }

        item { SectionHeader(stringResource(R.string.section_self_test)) }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.self_test_help),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        enabled = running && !testing,
                        onClick = {
                            testing = true
                            scope.launch {
                                selfTest = SelfTest.run(settings.port, settings.apiKey)
                                testing = false
                            }
                        },
                    ) {
                        if (testing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(painterResource(R.drawable.ic_bolt), contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (running) R.string.run_test else R.string.start_server_first))
                    }
                    selfTest?.let { MonoBlock(it.trim()) }
                }
            }
        }
    }
}

@Composable
private fun ServerCard(
    running: Boolean,
    port: Int,
    ips: List<String>,
    model: InferenceManager.State,
    displayName: (String) -> String,
    onCopy: (String) -> Unit,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val status = LocalStatusColors.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (running) scheme.primaryContainer else scheme.surfaceContainerLowest,
            contentColor = if (running) scheme.onPrimaryContainer else scheme.onSurface,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    R.drawable.ic_server,
                    container = if (running) scheme.primary else scheme.surfaceContainerHigh,
                    content = if (running) scheme.onPrimary else scheme.onSurfaceVariant,
                    size = 48,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.api_server), style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (running) stringResource(R.string.server_on, port) else stringResource(R.string.server_off),
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryText(running),
                    )
                }
                StatusPill(stringResource(if (running) R.string.running else R.string.stopped), if (running) status.ok else status.idle)
            }

            if (running) {
                val urls = ips.map { "http://$it:$port/v1" }.ifEmpty { listOf("http://127.0.0.1:$port/v1") }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.endpoint), style = MaterialTheme.typography.labelMedium, color = secondaryText(true))
                    urls.forEach { url ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(url, style = MonoStyle, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { onCopy(url) }, modifier = Modifier.size(36.dp)) {
                                Icon(painterResource(R.drawable.ic_copy), contentDescription = stringResource(R.string.copy), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_model), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(modelText(model, displayName), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (model is InferenceManager.State.Loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }

            if (running) {
                OutlinedButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(painterResource(R.drawable.ic_stop), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.stop_server))
                }
            } else {
                Button(onClick = onToggle, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(painterResource(R.drawable.ic_play), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.start_server))
                }
            }
        }
    }
}

@Composable
private fun secondaryText(onContainer: Boolean): Color =
    if (onContainer) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun modelText(state: InferenceManager.State, displayName: (String) -> String): String = when (state) {
    InferenceManager.State.Empty -> stringResource(R.string.model_none_autoload)
    is InferenceManager.State.Loading -> stringResource(R.string.model_loading, state.description)
    is InferenceManager.State.Ready -> "${displayName(state.modelId)} · ${state.description}"
    is InferenceManager.State.Failed -> stringResource(R.string.model_load_failed)
}

@Composable
private fun DeviceCard(device: DeviceSnapshot) {
    val status = LocalStatusColors.current
    val thermalColor = when {
        device.thermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE -> status.bad
        device.thermalStatus >= android.os.PowerManager.THERMAL_STATUS_MODERATE -> status.warn
        else -> status.ok
    }
    SettingsGroup {
        SettingRow(
            stringResource(R.string.thermal),
            supporting = if (device.thermalHeadroom.isNaN()) stringResource(R.string.headroom_unknown) else "headroom ${"%.2f".format(device.thermalHeadroom)}",
            icon = R.drawable.ic_thermo,
            trailing = { StatusPill(DeviceMonitor.thermalName(device.thermalStatus), thermalColor) },
        )
        GroupDivider()
        SettingRow(
            stringResource(R.string.battery),
            supporting = listOfNotNull(
                stringResource(if (device.charging) R.string.charging else R.string.not_charging),
                if (device.powerSaveMode) stringResource(R.string.battery_saver) else null,
            ).joinToString(" · "),
            icon = R.drawable.ic_battery,
            trailing = { Text("${device.batteryPercent}%", style = MaterialTheme.typography.titleMedium) },
        )
        GroupDivider()
        SettingRow(
            stringResource(R.string.memory),
            supporting = stringResource(R.string.memory_value, device.availRamMb / 1024f, device.totalRamMb / 1024f),
            icon = R.drawable.ic_model,
        )
        GroupDivider()
        SettingRow(
            device.model,
            supporting = device.soc + if (device.isEmulator) " · " + stringResource(R.string.emulator) else "",
            icon = R.drawable.ic_info,
        )
    }
}
