package node.nveloper.edge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import node.nveloper.edge.AppContainer
import node.nveloper.edge.R
import node.nveloper.edge.log.EventLog
import node.nveloper.edge.ui.components.IconBadge
import node.nveloper.edge.ui.theme.LocalStatusColors
import node.nveloper.edge.ui.theme.MonoStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LogFilter(val label: Int) { ALL(R.string.filter_all), WARN(R.string.filter_warn), ERROR(R.string.filter_error) }

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
fun LogsScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val entries by container.log.entries.collectAsState()
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    val shown = entries.asReversed().filter {
        when (filter) {
            LogFilter.ALL -> true
            LogFilter.WARN -> it.level != EventLog.Level.INFO
            LogFilter.ERROR -> it.level == EventLog.Level.ERROR
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogFilter.entries.forEach { f ->
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(stringResource(f.label)) })
            }
            Spacer(Modifier.weight(1f))
            Text("${shown.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconBadge(
                        R.drawable.ic_logs,
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 56,
                    )
                    Text(
                        stringResource(R.string.logs_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                shape = MaterialTheme.shapes.medium,
            ) {
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(shown) { LogRow(it) }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: EventLog.Entry) {
    val status = LocalStatusColors.current
    val color = when (entry.level) {
        EventLog.Level.INFO -> MaterialTheme.colorScheme.outlineVariant
        EventLog.Level.WARN -> status.warn
        EventLog.Level.ERROR -> status.bad
    }
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(timeFormat.format(Date(entry.timeMs)), style = MonoStyle.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Text(entry.message, style = MonoStyle.copy(fontSize = 12.sp, lineHeight = 16.sp), modifier = Modifier.padding(top = 2.dp))
        }
    }
}
