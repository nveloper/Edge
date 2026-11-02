package node.nveloper.edge.ui.screens

import android.app.usage.UsageStatsManager
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import node.nveloper.edge.AppContainer
import node.nveloper.edge.R
import node.nveloper.edge.inference.OverflowPolicy
import node.nveloper.edge.service.InferenceService
import node.nveloper.edge.service.ServerKeeper
import node.nveloper.edge.settings.Settings
import node.nveloper.edge.settings.WakeLockMode
import node.nveloper.edge.system.OemPowerGuide
import node.nveloper.edge.ui.LiveInfo
import node.nveloper.edge.ui.PlatformActions
import node.nveloper.edge.ui.components.Banner
import node.nveloper.edge.ui.components.GroupDivider
import node.nveloper.edge.ui.components.SectionHeader
import node.nveloper.edge.ui.components.SettingRow
import node.nveloper.edge.ui.components.SettingsGroup
import node.nveloper.edge.ui.components.StatusPill
import node.nveloper.edge.ui.components.Tone
import node.nveloper.edge.ui.theme.LocalStatusColors
import java.text.DateFormat
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, live: LiveInfo, actions: PlatformActions, modifier: Modifier = Modifier) {
    val settings by container.settings.flow.collectAsState()
    val running by InferenceService.running.collectAsState()
    val status = LocalStatusColors.current
    val update = container.settings::update
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SectionHeader(stringResource(R.string.section_server)) }
        if (running) {
            item { Banner(Tone.INFO, stringResource(R.string.stop_to_edit)) }
        }
        item {
            SettingsGroup {
                var portText by remember(settings.port) { mutableStateOf(settings.port.toString()) }
                SettingRow(stringResource(R.string.port), supporting = "1024–65535", icon = R.drawable.ic_server, enabled = !running) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { text ->
                            portText = text.filter(Char::isDigit).take(5)
                            portText.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { p -> update { it.copy(port = p) } }
                        },
                        singleLine = true,
                        enabled = !running,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.width(110.dp),
                    )
                }
                GroupDivider()
                var reveal by remember { mutableStateOf(false) }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    OutlinedTextField(
                        value = settings.apiKey,
                        onValueChange = { key -> update { it.copy(apiKey = key.trim()) } },
                        label = { Text(stringResource(R.string.api_key)) },
                        placeholder = { Text(stringResource(R.string.api_key_placeholder)) },
                        singleLine = true,
                        enabled = !running,
                        leadingIcon = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { reveal = !reveal }) {
                                Icon(
                                    painterResource(R.drawable.ic_visibility),
                                    contentDescription = stringResource(if (reveal) R.string.hide else R.string.show),
                                    tint = if (reveal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.api_key_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                GroupDivider()
                LabeledSlider(
                    title = stringResource(R.string.queue_size),
                    value = settings.maxQueue,
                    range = 0..8,
                    valueLabel = "${settings.maxQueue}",
                    supporting = stringResource(R.string.queue_help),
                    onChange = { n -> update { it.copy(maxQueue = n) } },
                )
            }
        }

        item { SectionHeader(stringResource(R.string.section_context)) }
        item {
            SettingsGroup {
                SettingRow(
                    stringResource(R.string.allow_cpu),
                    supporting = stringResource(R.string.allow_cpu_help),
                    trailing = { Switch(checked = settings.allowCpu, onCheckedChange = { on -> update { it.copy(allowCpu = on) } }) },
                )
                GroupDivider()
                var menu by remember { mutableStateOf(false) }
                SettingRow(stringResource(R.string.context_length), supporting = stringResource(R.string.context_help)) {
                    Box {
                        FilledTonalButton(onClick = { menu = true }) { Text("${settings.contextTokens}") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            Settings.CONTEXT_SIZES.forEach { size ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.tokens_value, size)) },
                                    onClick = {
                                        update { it.copy(contextTokens = size) }
                                        menu = false
                                    },
                                )
                            }
                        }
                    }
                }
                GroupDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(stringResource(R.string.kv_cache), style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        listOf(false, true).forEachIndexed { i, q8 ->
                            SegmentedButton(
                                selected = settings.kvQ8 == q8,
                                onClick = { update { it.copy(kvQ8 = q8) } },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                            ) { Text(if (q8) stringResource(R.string.kv_q8) else "F16") }
                        }
                    }
                    Text(
                        stringResource(R.string.kv_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                GroupDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(stringResource(R.string.on_overflow), style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        OverflowPolicy.entries.forEachIndexed { i, policy ->
                            SegmentedButton(
                                selected = settings.overflowPolicy == policy,
                                onClick = { update { it.copy(overflowPolicy = policy) } },
                                shape = SegmentedButtonDefaults.itemShape(i, OverflowPolicy.entries.size),
                            ) {
                                Text(stringResource(if (policy == OverflowPolicy.TRUNCATE_OLDEST) R.string.overflow_truncate else R.string.overflow_error))
                            }
                        }
                    }
                }
                GroupDivider()
                LabeledSlider(
                    title = stringResource(R.string.cache_slots),
                    value = settings.cacheSlots,
                    range = 0..4,
                    valueLabel = if (settings.cacheSlots == 0) stringResource(R.string.off) else "${settings.cacheSlots}",
                    supporting = stringResource(R.string.cache_help),
                    onChange = { n -> update { it.copy(cacheSlots = n) } },
                )
            }
        }
        item { Banner(Tone.INFO, stringResource(R.string.reload_to_apply)) }

        item { SectionHeader(stringResource(R.string.section_power)) }
        item {
            SettingsGroup {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(stringResource(R.string.wake_lock), style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        WakeLockMode.entries.forEachIndexed { i, mode ->
                            SegmentedButton(
                                selected = settings.wakeLockMode == mode,
                                onClick = { update { it.copy(wakeLockMode = mode) } },
                                enabled = !running,
                                shape = SegmentedButtonDefaults.itemShape(i, WakeLockMode.entries.size),
                            ) { Text(stringResource(if (mode == WakeLockMode.ALWAYS) R.string.wake_always else R.string.wake_during_request)) }
                        }
                    }
                }
                GroupDivider()
                val keeper = live.keeper
                SettingRow(
                    stringResource(R.string.auto_restart),
                    supporting = if (keeper.restarts == 0 || keeper.lastReason == null) {
                        stringResource(R.string.auto_restart_help)
                    } else {
                        stringResource(
                            R.string.auto_restart_stats,
                            keeper.restarts,
                            stringResource(reasonLabel(keeper.lastReason)),
                            DateUtils.formatSameDayTime(keeper.lastRestartMs, System.currentTimeMillis(), DateFormat.SHORT, DateFormat.SHORT),
                        )
                    },
                    icon = R.drawable.ic_refresh,
                    trailing = {
                        Switch(
                            checked = settings.autoRestart,
                            onCheckedChange = { on ->
                                update { it.copy(autoRestart = on) }
                                ServerKeeper.refresh(context)
                            },
                        )
                    },
                )
                if (settings.autoRestart) {
                    LabeledSlider(
                        title = stringResource(R.string.crash_limit),
                        value = settings.crashLimit,
                        range = 1..10,
                        valueLabel = "${settings.crashLimit}",
                        supporting = stringResource(R.string.crash_limit_help),
                        onChange = { n -> update { it.copy(crashLimit = n) } },
                    )
                }
                val guide = remember { OemPowerGuide.forThisDevice() }
                GroupDivider()
                Text(
                    guide.title(context),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
                guide.steps.forEach { step ->
                    val state = guide.state(step, live.device)
                    val requestExemption = step.check == OemPowerGuide.Check.BATTERY_UNRESTRICTED && !live.device.ignoringBatteryOptimizations
                    SettingRow(
                        stringResource(step.label),
                        icon = if (step.check == OemPowerGuide.Check.BATTERY_UNRESTRICTED) R.drawable.ic_battery else R.drawable.ic_settings,
                        onClick = when {
                            requestExemption -> actions.requestBatteryExemption
                            step.screens.isNotEmpty() || step.check != null -> { -> actions.openPowerScreens(step.screens) }
                            else -> null
                        },
                        trailing = {
                            when (state) {
                                true -> StatusPill(stringResource(R.string.pill_on), status.ok)
                                false -> StatusPill(stringResource(R.string.pill_needed), status.warn)
                                null -> StatusPill(stringResource(R.string.pill_manual), status.idle)
                            }
                        },
                    )
                }
                if (live.device.standbyBucket >= UsageStatsManager.STANDBY_BUCKET_RARE) {
                    Banner(Tone.WARN, stringResource(R.string.standby_rare), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                guide.infoUrl?.let { url ->
                    GroupDivider()
                    SettingRow(
                        stringResource(R.string.oem_info),
                        supporting = url.removePrefix("https://"),
                        icon = R.drawable.ic_info,
                        onClick = { actions.openUrl(url) },
                        trailing = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
                    )
                }
            }
        }

        item { SectionHeader("Hugging Face") }
        item {
            SettingsGroup {
                var reveal by remember { mutableStateOf(false) }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    OutlinedTextField(
                        value = settings.hfToken,
                        onValueChange = { token -> update { it.copy(hfToken = token.trim()) } },
                        label = { Text(stringResource(R.string.hf_token)) },
                        placeholder = { Text("hf_…") },
                        singleLine = true,
                        leadingIcon = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { reveal = !reveal }) {
                                Icon(
                                    painterResource(R.drawable.ic_visibility),
                                    contentDescription = stringResource(if (reveal) R.string.hide else R.string.show),
                                    tint = if (reveal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.hf_token_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                GroupDivider()
                SettingRow(
                    stringResource(R.string.hf_get_token),
                    supporting = "huggingface.co/settings/tokens",
                    icon = R.drawable.ic_link,
                    onClick = { actions.openUrl("https://huggingface.co/settings/tokens") },
                    trailing = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
                )
            }
        }

        item { SectionHeader(stringResource(R.string.section_about)) }
        item {
            SettingsGroup {
                val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
                SettingRow(stringResource(R.string.app_name), supporting = stringResource(R.string.about_version, version), icon = R.drawable.ic_info)
                GroupDivider()
                SettingRow(live.device.model, supporting = "${live.device.soc} · ${live.device.abis}", icon = R.drawable.ic_model)
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    title: String,
    value: Int,
    range: IntRange,
    valueLabel: String,
    supporting: String,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
        )
        Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun reasonLabel(reason: ServerKeeper.Reason): Int = when (reason) {
    ServerKeeper.Reason.USER -> R.string.reason_user
    ServerKeeper.Reason.STICKY -> R.string.reason_sticky
    ServerKeeper.Reason.BOOT -> R.string.reason_boot
    ServerKeeper.Reason.UPDATE -> R.string.reason_update
    ServerKeeper.Reason.WATCHDOG -> R.string.reason_watchdog
    ServerKeeper.Reason.NOTIFICATION -> R.string.reason_notification
}
