package app.perroute.compose.ui.components

import app.perroute.compose.*
import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import app.perroute.model.ApplicationIdentity

import app.perroute.compose.Shell
import app.perroute.compose.controller.PerRouteController

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.ProxyNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.Collections
import java.util.LinkedHashMap
import app.perroute.platform.macos.MacApplicationIcons

internal val KeyboardNavigation = mutableStateOf(false)

internal val OpenMenus = mutableStateOf(0)

internal val FocusColor: Color
    @Composable get() = if (Tok.isNew) (if (IsDark) NewColors.Lime else Color(0xFF131514)) else Violet2

internal fun Modifier.focusRing(shape: Shape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = FocusColor
    onFocusChanged { focused = it.isFocused }
        .then(if (focused && KeyboardNavigation.value) Modifier.border(2.dp, color, shape) else Modifier)
}

internal fun Modifier.pressable(
    shape: Shape,
    enabled: Boolean = true,
    role: Role = Role.Button,
    label: String? = null,
    onClick: () -> Unit
): Modifier = this
    .focusRing(shape)
    .clip(shape)
    .clickable(enabled = enabled, onClickLabel = label, role = role, onClick = onClick)
    .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
    .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Tooltip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (text.isBlank()) { Box(modifier) { content() }; return }
    val tokens = Tok
    TooltipArea(
        tooltip = {
            ProvideTokens(tokens) {
                Text(
                    text, color = White, fontSize = 10.5.sp, lineHeight = 14.sp,
                    modifier = Modifier.widthIn(max = 360.dp).shadow(8.dp, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp)).background(SurfaceHigh)
                        .border(1.dp, Line, RoundedCornerShape(8.dp))
                        .padding(horizontal = 9.dp, vertical = 6.dp)
                )
            }
        },
        modifier = modifier,
        delayMillis = 450,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp))
    ) { content() }
}

@Composable
internal fun EllipsisText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    fontFamily: FontFamily? = null,
    textDecoration: TextDecoration? = null
) {
    var clipped by remember(text) { mutableStateOf(false) }
    Tooltip(if (clipped) text else "", modifier) {
        Text(text, color = color, fontSize = fontSize, fontWeight = fontWeight, fontFamily = fontFamily, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textDecoration = textDecoration, onTextLayout = { clipped = it.hasVisualOverflow })
    }
}

@Composable
internal fun SurfaceCard(modifier: Modifier = Modifier, shape: Shape = CardShape, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clip(shape).background(Surface).border(1.dp, Line, shape), content = content)
}

@Composable
internal fun Divider(modifier: Modifier = Modifier, vertical: Boolean = false) {
    if (vertical) Box(modifier.width(1.dp).fillMaxHeight().background(Line))
    else Box(modifier.fillMaxWidth().height(1.dp).background(Line))
}

@Composable
internal fun PrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    height: Dp = 40.dp,
    color: Color? = null,
    contentColor: Color? = null,
    onClick: () -> Unit
) {
    val fill = if (enabled) color ?: Violet else NeutralOverlay.copy(if (IsDark) .10f else .09f)
    val content = if (enabled) contentColor ?: OnPrimary else Muted.copy(.85f)
    val shape = ControlShape
    val large = height >= 50.dp
    val roomy = !Tok.isNew && height >= 44.dp
    Row(
        modifier.height(height)
            .pressable(shape, enabled && !busy, label = label, onClick = onClick)
            .background(fill)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        if (busy) {
            CircularProgressIndicator(color = content, strokeWidth = 2.dp, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(if (large) 21.dp else 17.dp))
            Spacer(Modifier.width(if (large) 10.dp else 8.dp))
        }
        Text(label, color = content, fontSize = if (large) 15.5.sp else if (roomy) 14.sp else 12.5.sp, fontWeight = if (Tok.isNew) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun SecondaryButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    trailing: ImageVector? = null,
    height: Dp = 40.dp,
    tint: Color? = null,
    onClick: () -> Unit
) {
    val shape = ControlShape
    val text = tint ?: White
    Row(
        modifier.height(height).alpha(if (enabled) 1f else .45f)
            .pressable(shape, enabled, label = label, onClick = onClick)
            .background(if (Tok.isNew && IsDark) Color.Transparent else Field)
            .border(1.dp, if (tint != null) tint.copy(.6f) else if (Tok.isNew) White.copy(if (IsDark) .45f else .32f) else Line, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint ?: White.copy(.8f), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = text, fontSize = if (!Tok.isNew && height >= 44.dp) 14.sp else 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            Icon(trailing, null, tint = text.copy(.8f), modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
internal fun LinkAction(
    label: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    arrow: Boolean = true,
    enabled: Boolean = true,
    fontSize: TextUnit = 12.sp,
    onClick: () -> Unit
) {
    val tint = color ?: if (Tok.isNew) White else Violet2
    Row(
        modifier.alpha(if (enabled) 1f else .45f).pressable(RoundedCornerShape(4.dp), enabled, label = label, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = tint, fontSize = fontSize, fontWeight = FontWeight.Medium, maxLines = 1,
            textDecoration = if (Tok.isNew) TextDecoration.Underline else null)
        if (arrow) {
            Spacer(Modifier.width(5.dp))
            Icon(Icons.Rounded.NorthEast, null, tint = tint, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
internal fun IconAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = Muted,
    enabled: Boolean = true,
    size: Dp = 32.dp,
    bordered: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(if (Tok.isNew) 6.dp else 9.dp)
    Tooltip(label, modifier) {
        Box(
            Modifier.size(size).alpha(if (enabled) 1f else .4f)
                .pressable(shape, enabled, label = label, onClick = onClick)
                .then(if (bordered) Modifier.background(if (Tok.isNew && IsDark) Color.Transparent else Field).border(1.dp, Line, shape) else Modifier),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(size * .54f)) }
    }
}

@Composable
internal fun ToggleSwitch(checked: Boolean, label: String, enabled: Boolean = true, showState: Boolean = Tok.switchStateText, onToggle: (Boolean) -> Unit) {
    val track = if (checked) Violet else if (IsDark) Color.White.copy(.18f) else Color(0xFFD5D6D0)
    Row(
        Modifier.alpha(if (enabled) 1f else .45f).focusRing(CircleShape).clip(CircleShape)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .semantics { contentDescription = label; stateDescription = if (checked) "On" else "Off" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(width = 44.dp, height = 24.dp).clip(CircleShape).background(track).padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(Modifier.size(18.dp).shadow(1.dp, CircleShape).clip(CircleShape)
                .background(if (checked && Tok.isNew) Color(0xFF141614) else Color.White))
        }
        if (showState) {
            Spacer(Modifier.width(8.dp))
            Text(if (checked) "On" else "Off", color = White.copy(.85f), fontSize = 12.sp, modifier = Modifier.width(26.dp))
        }
    }
}

@Composable
internal fun CheckMark(selected: Boolean) {
    val shape = RoundedCornerShape(if (Tok.isNew) 4.dp else 5.dp)
    Box(
        Modifier.size(19.dp).clip(shape)
            .background(if (selected) Violet else Field)
            .border(1.5.dp, if (selected) Violet else White.copy(.38f), shape),
        contentAlignment = Alignment.Center
    ) { if (selected) Icon(Icons.Rounded.Check, null, tint = OnPrimary, modifier = Modifier.size(14.dp)) }
}

@Composable
internal fun RadioMark(selected: Boolean, size: Dp = 20.dp) {
    if (Tok.isNew) {
        Box(
            Modifier.size(size).clip(CircleShape).background(if (selected) Violet else Color.Transparent)
                .border(1.5.dp, if (selected) Violet else White.copy(.45f), CircleShape),
            contentAlignment = Alignment.Center
        ) { if (selected) Box(Modifier.size(size * .5f).clip(CircleShape).background(Color(0xFF141614))) }
    } else {
        Box(
            Modifier.size(size).clip(CircleShape).border(1.5.dp, if (selected) Violet2 else White.copy(.38f), CircleShape),
            contentAlignment = Alignment.Center
        ) { if (selected) Box(Modifier.size(size * .5f).clip(CircleShape).background(Violet2)) }
    }
}

@Composable
internal fun Badge(label: String, color: Color, filled: Boolean = false, icon: ImageVector? = null, outlined: Boolean = false) {
    val shape = RoundedCornerShape(if (Tok.isNew) 5.dp else 7.dp)
    val content = if (filled) (if (color == Violet) OnPrimary else Color.White) else color
    Row(
        Modifier.clip(shape).background(if (filled) color else if (outlined) Color.Transparent else color.copy(.12f))
            .then(if (outlined) Modifier.border(1.dp, color.copy(.55f), shape) else Modifier)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(label, color = content, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
internal fun RoutePill(kind: RouteKindLabel, modifier: Modifier = Modifier) {
    if (Tok.isNew) {
        Box(
            modifier.clip(CircleShape).background(Surface).border(1.dp, White.copy(.42f), CircleShape).padding(horizontal = 12.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) { Text(kind.label, color = White, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
    } else when (kind) {
        RouteKindLabel.MAIN -> Badge("Main", Violet, filled = true)
        RouteKindLabel.CUSTOM -> Badge("Custom", White.copy(.8f), outlined = true)
        RouteKindLabel.AUTO -> Badge("Auto", Cyan)
        RouteKindLabel.TEMPORARY -> Badge("Temporary", Amber)
    }
}

internal enum class RouteKindLabel(val label: String) { MAIN("Main"), CUSTOM("Custom"), AUTO("Auto"), TEMPORARY("Temporary") }

@Composable
internal fun RouteBadge(main: Boolean, long: Boolean = false) {
    if (main && long && !Tok.isNew) Badge("Main server", Violet, filled = true)
    else RoutePill(if (main) RouteKindLabel.MAIN else RouteKindLabel.CUSTOM)
}

@Composable
internal fun FilterChip(label: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, trailing: ImageVector? = null, onClick: () -> Unit) {
    val shape = ControlShape
    Row(
        modifier.height(34.dp).alpha(if (enabled) 1f else .45f).focusRing(shape).clip(shape)
            .selectable(selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .background(if (selected) (if (Tok.isNew) Violet else Selection) else Field)
            .border(1.dp, if (selected) (if (Tok.isNew) Violet else Violet2.copy(.4f)) else Line, shape)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (selected) (if (Tok.isNew) OnPrimary else Violet2) else White.copy(.85f), fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
        if (trailing != null) {
            Spacer(Modifier.width(5.dp))
            Icon(trailing, null, tint = if (selected && Tok.isNew) OnPrimary else Muted, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
internal fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector?)? = null,
    enabled: Boolean = true,
    height: Dp = 36.dp,
    onSelect: (T) -> Unit
) {
    val outer = ControlShape
    Row(
        modifier.height(height).clip(outer).background(Field).border(1.dp, Line, outer).padding(if (Tok.isNew) 0.dp else 3.dp),
        horizontalArrangement = Arrangement.spacedBy(if (Tok.isNew) 0.dp else 3.dp)
    ) {
        options.forEachIndexed { index, option ->
            val active = option == selected
            val shape = if (Tok.isNew) RoundedCornerShape(0.dp) else RoundedCornerShape(8.dp)
            if (Tok.isNew && index > 0 && !active && options.getOrNull(index - 1) != selected) Divider(Modifier.padding(vertical = 7.dp), vertical = true)
            Row(
                Modifier.weight(1f).fillMaxHeight().alpha(if (enabled || active) 1f else .5f).focusRing(shape).clip(shape)
                    .selectable(active, enabled = enabled, role = Role.Tab) { onSelect(option) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .background(if (active) (if (Tok.isNew) Violet else Selection) else Color.Transparent).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
            ) {
                val color = if (active) (if (Tok.isNew) OnPrimary else Violet2) else White.copy(.75f)
                icon?.invoke(option)?.let {
                    Icon(it, null, tint = color, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(label(option), color = color, fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun <T> UnderlineTabs(options: List<T>, selected: T, label: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val accent = if (Tok.isNew) White else Violet2
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(if (Tok.isNew) 30.dp else 28.dp)) {
            options.forEach { option ->
                val active = option == selected
                val shape = RoundedCornerShape(4.dp)
                Column(
                    Modifier.width(IntrinsicSize.Max).focusRing(shape).clip(shape).selectable(active, role = Role.Tab) { onSelect(option) }
                        .pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Text(label(option), color = if (active) accent else White.copy(.66f), fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                    Box(Modifier.fillMaxWidth().height(if (active) 2.5.dp else 0.dp).background(if (Tok.isNew) NewColors.Lime else Violet2))
                }
            }
        }
        Divider()
    }
}

@Composable
internal fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    icon: ImageVector? = null,
    enabled: Boolean = true,
    monospace: Boolean = false,
    height: Dp = 40.dp,
    password: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    val shape = ControlShape
    val cursor = if (Tok.isNew) White else Violet2
    Row(
        modifier.height(height).alpha(if (enabled) 1f else .5f).focusRing(shape).clip(shape)
            .background(Field).border(1.dp, if (Tok.isNew && !IsDark) Color(0xFFD3D4CC) else Line, shape).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Muted, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(9.dp))
        }
        BasicTextField(
            value, onValueChange, singleLine = true, enabled = enabled,
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            textStyle = TextStyle(color = White, fontSize = 12.5.sp, fontFamily = if (monospace) FontFamily.Monospace else UiFont),
            cursorBrush = SolidColor(cursor),
            modifier = Modifier.weight(1f).semantics { contentDescription = hint },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(hint, color = Muted.copy(.8f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            }
        )
        if (trailing != null) { Spacer(Modifier.width(6.dp)); trailing() }
    }
}

@Composable
internal fun PasswordField(value: String, onValueChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier.fillMaxWidth()) =
    TextInput(value, { onValueChange(it.take(128)) }, hint, modifier, password = true)

@Composable
internal fun TextArea(value: String, onValueChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier.fillMaxWidth().height(96.dp), enabled: Boolean = true) {
    val shape = ControlShape
    BasicTextField(
        value, onValueChange, enabled = enabled,
        textStyle = TextStyle(color = White, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(if (Tok.isNew) White else Violet2),
        modifier = modifier.alpha(if (enabled) 1f else .5f).focusRing(shape).clip(shape)
            .background(Field).border(1.dp, if (Tok.isNew && !IsDark) Color(0xFFD3D4CC) else Line, shape).padding(11.dp)
            .semantics { contentDescription = hint },
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, color = Muted.copy(.75f), fontSize = 11.sp); inner() } }
    )
}

internal class MenuEntry(
    val label: String,
    val icon: ImageVector? = null,
    val detail: String = "",
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

@Composable
internal fun PopupMenu(expanded: Boolean, onDismiss: () -> Unit, entries: List<MenuEntry>, minWidth: Dp = 200.dp, header: (@Composable () -> Unit)? = null) {
    DisposableEffect(expanded) {
        if (expanded) OpenMenus.value++
        onDispose { if (expanded) OpenMenus.value-- }
    }
    val page = tokensFor(Tok.style, IsDark)
    ProvideTokens(if (Tok.isNew && IsDark) Tok else page) {
        val selectedColor = if (Tok.isNew) (if (IsDark) NewColors.Lime else White) else Violet2
        DropdownMenu(
            expanded = expanded, onDismissRequest = onDismiss,
            modifier = Modifier.widthIn(min = minWidth, max = 400.dp).heightIn(max = 360.dp)
                .background(SurfaceHigh).border(1.dp, Line, RoundedCornerShape(4.dp))
        ) {
            header?.invoke()
            entries.forEach { entry ->
                val color = when {
                    entry.danger -> Red
                    entry.selected -> selectedColor
                    else -> White
                }
                DropdownMenuItem(
                    enabled = entry.enabled,
                    onClick = { onDismiss(); entry.onClick() },
                    leadingIcon = entry.icon?.let { icon -> { Icon(icon, null, tint = if (entry.enabled) color else Muted, modifier = Modifier.size(16.dp)) } },
                    trailingIcon = if (entry.selected) ({ Icon(Icons.Rounded.Check, "Selected", tint = selectedColor, modifier = Modifier.size(15.dp)) }) else null,
                    text = {
                        Column {
                            Text(entry.label, color = if (entry.enabled) color else Muted, fontSize = 12.sp,
                                fontWeight = if (entry.selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (entry.detail.isNotBlank()) Text(entry.detail, color = Muted, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                )
            }
        }
    }
}

@Composable
internal fun MenuButton(
    label: String,
    entries: List<MenuEntry>,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accessibleLabel: String = label,
    enabled: Boolean = true,
    height: Dp = 40.dp,
    prefix: String = "",
    header: (@Composable () -> Unit)? = null,
    menuWidth: Dp = 220.dp
) {
    var open by remember { mutableStateOf(false) }
    val shape = ControlShape
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().height(height).alpha(if (enabled) 1f else .45f)
                .pressable(shape, enabled, label = accessibleLabel) { open = true }
                .background(Field).border(1.dp, if (Tok.isNew && !IsDark) Color(0xFFD3D4CC) else Line, shape)
                .padding(start = 12.dp, end = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, null, tint = Muted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            if (prefix.isNotBlank()) {
                Text(prefix, color = Muted, fontSize = 12.sp, maxLines = 1)
                Spacer(Modifier.width(5.dp))
            }
            Text(label, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = White.copy(.7f), modifier = Modifier.size(18.dp))
        }
        PopupMenu(open, { open = false }, entries, minWidth = menuWidth, header = header)
    }
}

@Composable
internal fun MenuIcon(icon: ImageVector, label: String, entries: List<MenuEntry>, modifier: Modifier = Modifier, size: Dp = 32.dp, tint: Color = Muted, bordered: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconAction(icon, label, tint = tint, size = size, bordered = bordered) { open = true }
        PopupMenu(open, { open = false }, entries)
    }
}

@Composable
internal fun InfoBanner(text: String, modifier: Modifier = Modifier, color: Color = Muted, icon: ImageVector = Icons.Rounded.Info, action: (@Composable RowScope.() -> Unit)? = null) {
    val tinted = color != Muted
    val shape = RowShape
    Row(
        modifier.clip(shape).background(if (tinted) color.copy(if (IsDark) .12f else .09f) else NeutralOverlay.copy(if (IsDark) .05f else .03f))
            .border(1.dp, if (tinted) color.copy(.32f) else Line, shape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (tinted) color else White.copy(.7f), modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = White.copy(.82f), fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.weight(1f))
        if (action != null) { Spacer(Modifier.width(9.dp)); action() }
    }
}

@Composable
internal fun Note(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Rounded.Info, color: Color = Muted) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(9.dp))
        Text(text, color = color, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable
internal fun EmptyState(icon: ImageVector, title: String, detail: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(if (Tok.isNew) 10.dp else 16.dp))
            .background(if (Tok.isNew) (if (IsDark) Color.White.copy(.07f) else NewColors.Charcoal) else Selection), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (Tok.isNew) NewColors.Lime else Violet2, modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(5.dp))
        Text(detail, color = Muted, fontSize = 11.5.sp, lineHeight = 16.sp, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 400.dp))
        if (action != null) { Spacer(Modifier.height(14.dp)); action() }
    }
}

@Composable
internal fun LoadingState(text: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = if (Tok.isNew) White else Violet2, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(11.dp))
        Text(text, color = Muted, fontSize = 11.5.sp)
    }
}

@Composable
internal fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
internal fun SectionLabel(text: String, color: Color = Muted) {
    Text(text.uppercase(), color = color, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, maxLines = 1)
}

@Composable
internal fun FieldLabel(text: String) {
    Text(text, color = White.copy(.8f), fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
}

@Composable
internal fun DetailLine(label: String, value: String, labelWidth: Dp = 150.dp, valueColor: Color = White) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.width(labelWidth))
        Text(value, color = valueColor, fontSize = 11.5.sp, lineHeight = 15.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun HeaderText(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

@Composable
internal fun DialogCard(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    width: Dp = 520.dp,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(if (Tok.isNew) 10.dp else 18.dp)
    Box(
        Modifier.padding(22.dp).widthIn(max = width).fillMaxWidth().shadow(30.dp, shape).clip(shape)
            .background(Surface).border(1.dp, Line, shape)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = White, fontSize = 17.sp, fontWeight = if (Tok.isNew) FontWeight.ExtraBold else FontWeight.Bold)
                    if (subtitle.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(subtitle, color = Muted, fontSize = 11.5.sp, lineHeight = 16.sp)
                    }
                }
                Spacer(Modifier.width(10.dp))
                IconAction(Icons.Rounded.Close, "Close", bordered = false, onClick = onClose)
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

@Composable
internal fun ConfirmDialog(title: String, message: String, confirm: String, onCancel: () -> Unit, danger: Boolean = false, onConfirm: () -> Unit) {
    DialogCard(title, "", onCancel, width = 440.dp, actions = {
        SecondaryButton("Cancel", onClick = onCancel)
        if (danger) PrimaryButton(confirm, color = Red, contentColor = Color.White, onClick = onConfirm)
        else PrimaryButton(confirm, onClick = onConfirm)
    }) {
        Text(message, color = White.copy(.85f), fontSize = 12.sp, lineHeight = 17.sp)
    }
}

private class AppIcon(val bitmap: ImageBitmap, val darkGlyph: Boolean)

private val appIconCache = Collections.synchronizedMap(
    object : LinkedHashMap<String, java.util.Optional<AppIcon>>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, java.util.Optional<AppIcon>>?) = size > 256
    }
)
private val macApplicationIcons by lazy { MacApplicationIcons.system() }

private val StoreAppRevision = mutableStateOf(0).also { state -> app.perroute.service.StoreAppPaths.onChange { state.value++ } }

@Composable
private fun rememberAppIcon(path: String): AppIcon? {
    val revision = StoreAppRevision.value
    val source = remember(path, revision) { if (ApplicationIdentity.isMac(path)) ApplicationIdentity.path(path) else app.perroute.service.StoreAppPaths.current(path) }
    val key = if (ApplicationIdentity.isMac(path)) source else source.lowercase()
    val state = produceState(appIconCache[key]?.orElse(null), key) {
        val cached = appIconCache[key]
        if (cached != null) { value = cached.orElse(null); return@produceState }
        value = withContext(Dispatchers.IO) {
            val loaded = runCatching {
                if (System.getProperty("os.name") == "Mac OS X") {
                    val png = macApplicationIcons.png(path) ?: return@runCatching null
                    val image = png.inputStream().use { javax.imageio.ImageIO.read(it) } ?: return@runCatching null
                    return@runCatching AppIcon(image.toComposeImageBitmap(),darkGlyph(image))
                }
                val file = java.io.File(source)
                if (!file.isFile) return@runCatching null
                val icon = javax.swing.filechooser.FileSystemView.getFileSystemView().getSystemIcon(file, 64, 64) ?: return@runCatching null
                val image = java.awt.image.BufferedImage(icon.iconWidth.coerceAtLeast(1), icon.iconHeight.coerceAtLeast(1), java.awt.image.BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try {
                    graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    icon.paintIcon(null, graphics, 0, 0)
                } finally { graphics.dispose() }
                AppIcon(image.toComposeImageBitmap(), darkGlyph(image))
            }.getOrNull()
            if (loaded != null || java.io.File(source).isFile) appIconCache[key] = java.util.Optional.ofNullable(loaded)
            loaded
        }
    }
    return state.value
}

private fun darkGlyph(image: java.awt.image.BufferedImage): Boolean {
    var visible = 0
    var luminance = 0.0
    for (y in 0 until image.height) for (x in 0 until image.width) {
        val argb = image.getRGB(x, y)
        if ((argb ushr 24) < 128) continue
        visible++
        luminance += .2126 * ((argb shr 16) and 0xFF) + .7152 * ((argb shr 8) and 0xFF) + .0722 * (argb and 0xFF)
    }
    return visible > 0 && luminance / visible < 70.0
}

@Composable
internal fun AppAvatar(name: String, path: String = "", size: Dp = 34.dp, framed: Boolean = false) {
    val icon = if (path.isBlank()) null else rememberAppIcon(path)
    val shape = RoundedCornerShape(size * .26f)
    val frame = if (framed) Modifier.border(1.dp, Line, shape) else Modifier
    if (icon != null) {
        val tiled = IsDark && icon.darkGlyph
        Box(Modifier.size(size).clip(shape).background(if (tiled) Color(0xFFEDEDE8) else if (framed) Surface else Color.Transparent).then(frame),
            contentAlignment = Alignment.Center) {
            Image(icon.bitmap, null, Modifier.size(size * if (framed || tiled) .62f else .82f))
        }
        return
    }
    val colors = listOf(Color(0xFF2F9E6B), Color(0xFF6C55E0), Color(0xFFC8861A), Color(0xFFD9566A), Color(0xFF3F7FD9))
    val color = colors[Math.floorMod(name.hashCode(), colors.size)]
    Box(Modifier.size(size).clip(shape).background(color.copy(if (IsDark) .22f else .13f)).then(frame), contentAlignment = Alignment.Center) {
        Text(name.firstOrNull()?.uppercase() ?: "A", color = if (IsDark) color.copy(alpha = 1f).let { Color(it.red * .6f + .4f, it.green * .6f + .4f, it.blue * .6f + .4f) } else color,
            fontSize = (size.value * .42f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun ServerAvatar(node: ProxyNode?, size: Dp = 34.dp) {
    val code = node?.let(::avatarCode).orEmpty()
    val shape = RoundedCornerShape(size * if (Tok.isNew) .16f else .24f)
    val (back, front) = if (Tok.isNew) (if (IsDark && Ink != NewColors.Charcoal) Color(0xFF0E0F0F) else Color(0xFF131514)) to Color.White
        else (if (IsDark) Color.White.copy(.08f) else Color(0xFFEEEEF4)) to White
    Box(Modifier.size(size).clip(shape).background(back)
        .then(if (Tok.isNew) Modifier.border(1.dp, Color.White.copy(.14f), shape) else Modifier), contentAlignment = Alignment.Center) {
        if (code.isBlank()) Icon(Icons.Rounded.Public, null, tint = front.copy(.85f), modifier = Modifier.size(size * .5f))
        else Text(code, color = front, fontSize = (size.value * .36f).sp, fontWeight = FontWeight.Bold)
    }
}

internal fun countryCode(node: ProxyNode): String {
    val name = PerRouteController.cleanName(node.name()).trim()
    val codePoints = name.codePoints().limit(2).toArray()
    if (codePoints.size == 2 && codePoints.all { it in 0x1F1E6..0x1F1FF }) {
        return String(codePoints.map { 'A'.code + (it - 0x1F1E6) }.toIntArray(), 0, 2)
    }
    val suffix = node.address().substringAfterLast('.', "").uppercase()
    return suffix.takeIf { it.length == 2 && it.all(Char::isLetter) } ?: ""
}

private fun avatarCode(node: ProxyNode): String =
    countryCode(node).ifBlank { Regex("(?<![A-Za-z0-9])([A-Z]{2})(?![A-Za-z])").find(PerRouteController.cleanName(node.name()))?.groupValues?.get(1).orEmpty() }

internal fun serverName(node: ProxyNode): String {
    val name = PerRouteController.cleanName(node.name())
    val stripped = name.dropWhile { it.isSurrogate() || it.isWhitespace() }
    val points = name.codePoints().limit(2).toArray()
    return if (points.size == 2 && points.all { it in 0x1F1E6..0x1F1FF } && stripped.isNotBlank()) stripped.trim() else name
}

internal fun protocolLabel(node: ProxyNode): String =
    if (node.protocol() == app.perroute.model.Protocol.XRAY_PROFILE) "Xray JSON" else node.protocol().label()

internal fun appName(path: String): String = PerRouteController.displayName(path)

internal fun fileName(path: String): String = runCatching { Path.of(ApplicationIdentity.path(path)).fileName.toString() }.getOrDefault(path)

internal fun joinNames(names: List<String>, limit: Int = 3): String = when {
    names.isEmpty() -> ""
    names.size == 1 -> names[0]
    names.size <= limit -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    else -> names.take(limit).joinToString(", ") + " and ${names.size - limit} more"
}

internal fun plural(count: Int, singular: String, plural: String = singular + "s"): String = "$count ${if (count == 1) singular else plural}"

internal fun formatRate(bytes: Long): String = "${formatBytes(bytes)}/s"

internal fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    return when {
        value >= 1024.0 * 1024.0 * 1024.0 -> String.format(java.util.Locale.ROOT, "%.1f GB", value / (1024.0 * 1024.0 * 1024.0))
        value >= 1024.0 * 1024.0 -> String.format(java.util.Locale.ROOT, "%.1f MB", value / (1024.0 * 1024.0))
        value >= 1024.0 -> String.format(java.util.Locale.ROOT, "%.1f KB", value / 1024.0)
        else -> "${value.toLong()} B"
    }
}

internal fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remaining = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remaining)
    else "%02d:%02d".format(minutes, remaining)
}

internal fun formatClock(seconds: Long): String = "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)

internal fun formatAgo(atMillis: Long, now: Long = System.currentTimeMillis()): String {
    val seconds = ((now - atMillis) / 1_000L).coerceAtLeast(0L)
    return when {
        seconds < 5 -> "just now"
        seconds < 60 -> "$seconds seconds ago"
        seconds < 120 -> "1 minute ago"
        seconds < 3_600 -> "${seconds / 60} minutes ago"
        else -> "${seconds / 3_600} h ago"
    }
}

internal fun formatTimeOfDay(atMillis: Long): String =
    java.time.Instant.ofEpochMilli(atMillis).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0).toString().take(5)

internal fun Modifier.wheelAndDragScroll(state: ScrollState): Modifier = this
    .pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                if (change.isConsumed) continue
                val delta = if (change.scrollDelta.x != 0f) change.scrollDelta.x else change.scrollDelta.y
                state.dispatchRawDelta(delta * 48.dp.toPx())
                change.consume()
            }
        }
    }
    .pointerInput(state) {
        detectHorizontalDragGestures { change, dragAmount ->
            change.consume()
            state.dispatchRawDelta(-dragAmount)
        }
    }

@Composable
internal fun CompositionLtr(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)

@Composable
internal fun NewMark(size: Dp = 26.dp, color: Color = NewColors.Lime) {
    Canvas(Modifier.size(size).semantics { contentDescription = "PerRoute" }) {
        val w = this.size.width
        val stroke = w * .085f
        val dot = w * .105f
        val cx = w * .5f
        drawLine(color, Offset(cx, w * .14f), Offset(cx, w * .86f), stroke, StrokeCap.Round)
        drawLine(color, Offset(cx, w * .46f), Offset(w * .2f, w * .46f), stroke, StrokeCap.Round)
        drawLine(color, Offset(cx, w * .62f), Offset(w * .8f, w * .62f), stroke, StrokeCap.Round)
        drawCircle(color, dot, Offset(cx, w * .12f))
        drawCircle(color, dot, Offset(cx, w * .88f))
        drawCircle(color, dot, Offset(w * .14f, w * .46f))
        drawCircle(color, dot, Offset(w * .86f, w * .62f))
    }
}


@Composable
internal fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    Box(modifier.height(height).clip(CircleShape).background(NeutralOverlay.copy(if (IsDark) .12f else .08f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).clip(CircleShape).background(color))
    }
}

@Composable
internal fun ArrowGlyph(color: Color = Muted, size: Dp = 16.dp) {
    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = color, modifier = Modifier.size(size).offset(y = 0.dp))
}
