package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class UiStyle(val key: String, val label: String) {
    NEW(PerRouteController.UI_STYLE_NEW, "New"),
    SIMPLE(PerRouteController.UI_STYLE_SIMPLE, "Simple");

    companion object {
        fun of(key: String?): UiStyle = if (key == SIMPLE.key) SIMPLE else NEW
    }
}

internal data class Palette(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val foreground: Color,
    val muted: Color,
    val primary: Color,
    val primaryBright: Color,
    val onPrimary: Color,
    val success: Color,
    val danger: Color,
    val warning: Color,
    val line: Color,
    val field: Color,
    val selection: Color,
    val isDark: Boolean
)

internal class Tokens(
    val style: UiStyle,
    val palette: Palette,
    val controlRadius: Dp,
    val cardRadius: Dp,
    val rowRadius: Dp,
    val switchStateText: Boolean
) {
    val controlShape: Shape = RoundedCornerShape(controlRadius)
    val cardShape: Shape = RoundedCornerShape(cardRadius)
    val rowShape: Shape = RoundedCornerShape(rowRadius)
    val isNew: Boolean get() = style == UiStyle.NEW
    fun with(palette: Palette) = Tokens(style, palette, controlRadius, cardRadius, rowRadius, switchStateText)
}

internal object NewColors {
    val Lime = Color(0xFFD8F24A)
    val Charcoal = Color(0xFF1E2020)
    val Chrome = Color(0xFF171919)

    val Light = Palette(
        background = Color(0xFFF4F4EF), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFFFFFFF),
        foreground = Color(0xFF131514), muted = Color(0xFF676B7C),
        primary = Lime, primaryBright = Color(0xFF4F5E05), onPrimary = Color(0xFF141614),
        success = Color(0xFF1F9A48), danger = Color(0xFFD33A35), warning = Color(0xFFC77F00),
        line = Color(0xFFE0E0D8), field = Color(0xFFFFFFFF), selection = Color(0xFFF1F6D9), isDark = false
    )
    val Dark = Palette(
        background = Color(0xFF151716), surface = Color(0xFF1D201F), surfaceHigh = Color(0xFF262928),
        foreground = Color(0xFFEDEDE7), muted = Color(0xFF9CA0A4),
        primary = Lime, primaryBright = Lime, onPrimary = Color(0xFF141614),
        success = Color(0xFF53C47A), danger = Color(0xFFF07070), warning = Color(0xFFF0B440),
        line = Color(0xFF2B2F2E), field = Color(0xFF1A1C1B), selection = Color(0xFF232914), isDark = true
    )
    val Panel = Palette(
        background = Charcoal, surface = Color(0xFF272A29), surfaceHigh = Color(0xFF2D3130),
        foreground = Color(0xFFF3F3EE), muted = Color(0xFFA5A9A8),
        primary = Lime, primaryBright = Lime, onPrimary = Color(0xFF141614),
        success = Color(0xFF53C47A), danger = Color(0xFFF07A74), warning = Color(0xFFF2B544),
        line = Color(0xFF343837), field = Color(0xFF181A1A), selection = Color(0xFF2B3318), isDark = true
    )
}

internal object SimpleColors {
    val Light = Palette(
        background = Color(0xFFF7F7FA), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFFFFFFF),
        foreground = Color(0xFF16152B), muted = Color(0xFF6A6A84),
        primary = Color(0xFF5B3FE4), primaryBright = Color(0xFF5438DC), onPrimary = Color(0xFFFFFFFF),
        success = Color(0xFF0F9F5A), danger = Color(0xFFD9302F), warning = Color(0xFFB97400),
        line = Color(0xFFE5E5EE), field = Color(0xFFFFFFFF), selection = Color(0xFFF0EDFF), isDark = false
    )
    val Dark = Palette(
        background = Color(0xFF17171E), surface = Color(0xFF1F1F28), surfaceHigh = Color(0xFF282832),
        foreground = Color(0xFFF1F1F6), muted = Color(0xFFA1A1B5),
        primary = Color(0xFF7457F2), primaryBright = Color(0xFFA08BFF), onPrimary = Color(0xFFFFFFFF),
        success = Color(0xFF45D08D), danger = Color(0xFFF27575), warning = Color(0xFFEDB24E),
        line = Color(0xFF30303C), field = Color(0xFF1B1B23), selection = Color(0xFF2A2548), isDark = true
    )
    fun sidebar(dark: Boolean) = if (dark) Color(0xFF1B1B23) else Color(0xFFFBFBFD)
}

internal fun tokensFor(style: UiStyle, dark: Boolean): Tokens = when (style) {
    UiStyle.NEW -> Tokens(style, if (dark) NewColors.Dark else NewColors.Light, 6.dp, 10.dp, 6.dp, switchStateText = true)
    UiStyle.SIMPLE -> Tokens(style, if (dark) SimpleColors.Dark else SimpleColors.Light, 10.dp, 16.dp, 12.dp, switchStateText = false)
}

internal object Appearance {
    var style by mutableStateOf(UiStyle.NEW)
    var dark by mutableStateOf(true)

    fun load(controller: PerRouteController) {
        style = UiStyle.of(controller.uiStyle())
        dark = controller.isDarkTheme()
    }
}

internal val LocalTokens = staticCompositionLocalOf { tokensFor(UiStyle.NEW, true) }

internal val Tok: Tokens @Composable @ReadOnlyComposable get() = LocalTokens.current
internal val Ink: Color @Composable @ReadOnlyComposable get() = LocalTokens.current.palette.background
internal val Surface: Color @Composable @ReadOnlyComposable get() = LocalTokens.current.palette.surface
internal val SurfaceHigh: Color @Composable @ReadOnlyComposable get() = LocalTokens.current.palette.surfaceHigh
internal val White: Color @Composable @ReadOnlyComposable get() = LocalTokens.current.palette.foreground
internal val Muted: Color @Composable @ReadOnlyComposable get() = LocalTokens.current.palette.muted
internal val Violet: Color @Composable @ReadOnlyComposable get() = LocalTokens.current.palette.primary
internal val Violet2: Color @Composable @ReadOnlyComposable get() = LocalTokens.current.palette.primaryBright
internal val OnPrimary: Color @Composable @ReadOnlyComposable get() = LocalTokens.current.palette.onPrimary
internal val Cyan: Color @Composable @ReadOnlyComposable get() = LocalTokens.current.palette.success
internal val Red: Color @Composable @ReadOnlyComposable get() = LocalTokens.current.palette.danger
internal val Amber: Color @Composable @ReadOnlyComposable get() = LocalTokens.current.palette.warning
internal val Line: Color @Composable @ReadOnlyComposable get() = LocalTokens.current.palette.line
internal val Field: Color @Composable @ReadOnlyComposable get() = LocalTokens.current.palette.field
internal val Selection: Color @Composable @ReadOnlyComposable get() = LocalTokens.current.palette.selection
internal val IsDark: Boolean @Composable @ReadOnlyComposable get() = LocalTokens.current.palette.isDark
internal val NeutralOverlay: Color @Composable @ReadOnlyComposable get() = if (LocalTokens.current.palette.isDark) Color.White else Color.Black
internal val ControlShape: Shape @Composable @ReadOnlyComposable get() = LocalTokens.current.controlShape
internal val CardShape: Shape @Composable @ReadOnlyComposable get() = LocalTokens.current.cardShape
internal val RowShape: Shape @Composable @ReadOnlyComposable get() = LocalTokens.current.rowShape

internal val UiFont = FontFamily(
    Font("fonts/Inter-Variable.ttf", FontWeight.Normal),
    Font("fonts/Inter-Variable.ttf", FontWeight.Medium),
    Font("fonts/Inter-Variable.ttf", FontWeight.SemiBold),
    Font("fonts/Inter-Variable.ttf", FontWeight.Bold),
    Font("fonts/Inter-Variable.ttf", FontWeight.ExtraBold)
)

@Composable
internal fun PerRouteTheme(style: UiStyle = Appearance.style, dark: Boolean = Appearance.dark, content: @Composable () -> Unit) {
    val tokens = tokensFor(style, dark)
    ProvideTokens(tokens) {
        val p = tokens.palette
        MaterialTheme(
            colorScheme = if (p.isDark)
                darkColorScheme(primary = p.primary, onPrimary = p.onPrimary, secondary = p.success, background = p.background, surface = p.surfaceHigh,
                    surfaceContainer = p.surfaceHigh, onSurface = p.foreground, onSurfaceVariant = p.muted)
            else lightColorScheme(primary = p.primary, onPrimary = p.onPrimary, secondary = p.success, background = p.background, surface = p.surfaceHigh,
                surfaceContainer = p.surfaceHigh, onSurface = p.foreground, onSurfaceVariant = p.muted),
            typography = MaterialTheme.typography.copy(
                bodyLarge = TextStyle(fontFamily = UiFont),
                bodyMedium = TextStyle(fontFamily = UiFont),
                labelLarge = TextStyle(fontFamily = UiFont),
                titleLarge = TextStyle(fontFamily = UiFont)
            )
        ) { CompositionLtr(content) }
    }
}

@Composable
internal fun ProvideTokens(tokens: Tokens, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalTokens provides tokens, content = content)

@Composable
internal fun CharcoalColors(content: @Composable () -> Unit) {
    val tokens = LocalTokens.current
    ProvideTokens(tokens.with(NewColors.Panel), content)
}
