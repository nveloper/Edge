package node.nveloper.edge.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import node.nveloper.edge.R
import node.nveloper.edge.ui.theme.LocalStatusColors
import node.nveloper.edge.ui.theme.MonoStyle

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

/** Rounded icon on a tinted circle, as used on Edge Gallery's task tiles. */
@Composable
fun IconBadge(@DrawableRes icon: Int, container: Color, content: Color, size: Int = 40) {
    Box(
        modifier = Modifier.size(size.dp).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size((size * 0.55f).dp))
    }
}

@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
fun StatTile(label: String, value: String, unit: String?, modifier: Modifier = Modifier, caption: String? = null) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
                Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                if (unit != null) {
                    Text(
                        " $unit",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
            if (caption != null) {
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

enum class Tone { INFO, WARN, ERROR, SUCCESS }

@Composable
fun Banner(tone: Tone, text: String, modifier: Modifier = Modifier, action: @Composable (RowScope.() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    val (container, content, icon) = when (tone) {
        Tone.INFO -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, R.drawable.ic_info)
        Tone.WARN -> Triple(LocalStatusColors.current.warn.copy(alpha = 0.16f), scheme.onSurface, R.drawable.ic_error)
        Tone.ERROR -> Triple(scheme.errorContainer, scheme.onErrorContainer, R.drawable.ic_error)
        Tone.SUCCESS -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, R.drawable.ic_check_circle)
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            action?.invoke(this)
        }
    }
}

/** A rounded group of settings rows, like the grouped lists in Edge Gallery / Android settings. */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
fun GroupDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** Large selectable option card (backend choice). */
@Composable
fun ChoiceCard(
    title: String,
    description: String,
    @DrawableRes icon: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    OutlinedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) scheme.primaryContainer.copy(alpha = 0.35f) else scheme.surfaceContainerLowest,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon,
                container = if (selected) scheme.primary else scheme.surfaceContainerHigh,
                content = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSecondaryContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(scheme.secondaryContainer)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(description, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun MonoBlock(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(text, style = MonoStyle, modifier = Modifier.padding(12.dp))
    }
}

@Composable
fun ContentColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
