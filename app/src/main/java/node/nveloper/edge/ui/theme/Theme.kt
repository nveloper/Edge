package node.nveloper.edge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import node.nveloper.edge.R

// Google-style blue palette, close to what AI Edge Gallery uses.
private val Light = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF00639B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC2E7FF),
    onSecondaryContainer = Color(0xFF001D35),
    tertiary = Color(0xFF146C2E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC4EED0),
    onTertiaryContainer = Color(0xFF072711),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF8FAFD),
    onBackground = Color(0xFF1F1F1F),
    surface = Color(0xFFF8FAFD),
    onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFE1E3E1),
    onSurfaceVariant = Color(0xFF444746),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFD),
    surfaceContainer = Color(0xFFF0F4F9),
    surfaceContainerHigh = Color(0xFFE9EEF6),
    surfaceContainerHighest = Color(0xFFDDE3EA),
    outline = Color(0xFF747775),
    outlineVariant = Color(0xFFC4C7C5),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF7FCFFF),
    onSecondary = Color(0xFF003355),
    secondaryContainer = Color(0xFF004A77),
    onSecondaryContainer = Color(0xFFC2E7FF),
    tertiary = Color(0xFF6DD58C),
    onTertiary = Color(0xFF0A3818),
    tertiaryContainer = Color(0xFF0F5223),
    onTertiaryContainer = Color(0xFFC4EED0),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF131314),
    onBackground = Color(0xFFE3E3E3),
    surface = Color(0xFF131314),
    onSurface = Color(0xFFE3E3E3),
    surfaceVariant = Color(0xFF444746),
    onSurfaceVariant = Color(0xFFC4C7C5),
    surfaceContainerLowest = Color(0xFF0E0E0E),
    surfaceContainerLow = Color(0xFF1B1B1B),
    surfaceContainer = Color(0xFF1E1F20),
    surfaceContainerHigh = Color(0xFF282A2C),
    surfaceContainerHighest = Color(0xFF333537),
    outline = Color(0xFF8E918F),
    outlineVariant = Color(0xFF444746),
)

/** Semantic colors Material's scheme has no slot for. */
@Immutable
data class StatusColors(val ok: Color, val warn: Color, val bad: Color, val idle: Color)

val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(ok = Color(0xFF146C2E), warn = Color(0xFFB06000), bad = Color(0xFFB3261E), idle = Color(0xFF747775))
}

private val LightStatus = StatusColors(Color(0xFF146C2E), Color(0xFFB06000), Color(0xFFB3261E), Color(0xFF747775))
private val DarkStatus = StatusColors(Color(0xFF6DD58C), Color(0xFFFFB95C), Color(0xFFF2B8B5), Color(0xFF8E918F))

/**
 * Google Sans Flex (OFL, bundled in res/font so it works without Play services). It is a
 * variable font, so each weight is an instance of the same file. Hangul and other scripts it
 * lacks fall back to the system font.
 */
@OptIn(ExperimentalTextApi::class)
private val GoogleSans = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(R.font.google_sans_flex, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    },
)

private val AppTypography = Typography().let { t ->
    fun TextStyle.sans(weight: FontWeight? = null) = copy(fontFamily = GoogleSans, fontWeight = weight ?: fontWeight)
    t.copy(
        displayLarge = t.displayLarge.sans(),
        displayMedium = t.displayMedium.sans(),
        displaySmall = t.displaySmall.sans(),
        headlineLarge = t.headlineLarge.sans(),
        headlineMedium = t.headlineMedium.sans(),
        headlineSmall = t.headlineSmall.sans(FontWeight.Medium),
        titleLarge = t.titleLarge.sans(FontWeight.Medium),
        titleMedium = t.titleMedium.sans(FontWeight.Medium),
        titleSmall = t.titleSmall.sans(),
        bodyLarge = t.bodyLarge.sans(),
        bodyMedium = t.bodyMedium.sans(),
        bodySmall = t.bodySmall.sans(),
        labelLarge = t.labelLarge.sans(FontWeight.Medium),
        labelMedium = t.labelMedium.sans(),
        labelSmall = t.labelSmall.sans(),
    )
}

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)

@Composable
fun EdgeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
