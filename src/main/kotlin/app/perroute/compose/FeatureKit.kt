package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun BrandMark(size: Dp = 30.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * .24f)).background(NewColors.Charcoal).semantics { contentDescription = "PerRoute" },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * .74f)) {
            val w = this.size.width
            val stroke = w * .1f
            val dot = w * .115f
            val cx = w * .5f
            val color = NewColors.Lime
            drawLine(color, Offset(cx, w * .14f), Offset(cx, w * .86f), stroke, StrokeCap.Round)
            drawLine(color, Offset(cx, w * .46f), Offset(w * .2f, w * .46f), stroke, StrokeCap.Round)
            drawLine(color, Offset(cx, w * .62f), Offset(w * .8f, w * .62f), stroke, StrokeCap.Round)
            drawCircle(color, dot, Offset(cx, w * .12f))
            drawCircle(color, dot, Offset(cx, w * .88f))
            drawCircle(color, dot, Offset(w * .14f, w * .46f))
            drawCircle(color, dot, Offset(w * .86f, w * .62f))
        }
    }
}

@Composable
internal fun statusColor(status: CheckStatus): Color = when (status) {
    CheckStatus.PASS -> Cyan
    CheckStatus.WARN -> Amber
    CheckStatus.FAIL -> Red
    CheckStatus.INFO -> White.copy(.7f)
    CheckStatus.SKIPPED -> Muted
}

internal fun statusIcon(status: CheckStatus): ImageVector = when (status) {
    CheckStatus.PASS -> Icons.Rounded.CheckCircle
    CheckStatus.WARN -> Icons.Rounded.WarningAmber
    CheckStatus.FAIL -> Icons.Rounded.ErrorOutline
    CheckStatus.INFO -> Icons.Rounded.Info
    CheckStatus.SKIPPED -> Icons.Rounded.Block
}

internal fun statusWord(status: CheckStatus): String = when (status) {
    CheckStatus.PASS -> "Passed"
    CheckStatus.WARN -> "Attention"
    CheckStatus.FAIL -> "Problem"
    CheckStatus.INFO -> "Note"
    CheckStatus.SKIPPED -> "Not tested"
}

@Composable
internal fun StatusMark(status: CheckStatus, size: Dp = 30.dp) {
    val color = statusColor(status)
    Box(Modifier.size(size).background(color.copy(.15f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(statusIcon(status), statusWord(status), tint = color, modifier = Modifier.size(size * .62f))
    }
}

@Composable
internal fun FeaturePage(
    eyebrow: List<String>,
    title: String,
    subtitle: String,
    actions: (@Composable RowScope.() -> Unit)? = null,
    panel: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (Tok.isNew) {
        NewSplitPage(panel = panel ?: { NewPanelEmpty(eyebrow.last(), title, subtitle) }) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                NewHeading(eyebrow, title, subtitle, actions = actions)
                Spacer(Modifier.height(18.dp))
                content()
                Spacer(Modifier.height(12.dp))
            }
        }
    } else {
        SimplePage(title, subtitle, actions = actions) {
            SimpleCard { content() }
            if (panel != null) {
                Spacer(Modifier.height(16.dp))
                SimpleCard(content = panel)
            }
        }
    }
}

@Composable
internal fun FeatureBlock(
    title: String,
    subtitle: String = "",
    first: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    if (!first) {
        if (Tok.isNew) { Divider(); Spacer(Modifier.height(18.dp)) } else Spacer(Modifier.height(16.dp))
    } else if (!Tok.isNew) Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontSize = if (Tok.isNew) 17.sp else 15.sp, fontWeight = if (Tok.isNew) FontWeight.Bold else FontWeight.SemiBold)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = trailing)
        }
    }
    Column(Modifier.fillMaxWidth(), content = content)
    Spacer(Modifier.height(if (Tok.isNew) 18.dp else 16.dp))
    if (!Tok.isNew) Divider()
}

@Composable
internal fun PanelHeading(eyebrow: String, title: String, subtitle: String = "", leading: (@Composable () -> Unit)? = null) {
    if (Tok.isNew) NewPanelHeading(eyebrow, title, subtitle, leading)
    else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) { leading(); Spacer(Modifier.width(14.dp)) }
            Column(Modifier.weight(1f)) {
                Text(eyebrow.uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
                Text(title, color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
internal fun PanelSection(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    if (Tok.isNew) NewPanelSection(title, content = content)
    else {
        Spacer(Modifier.height(14.dp))
        if (title != null) { Text(title, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)) }
        Column(content = content)
    }
}

@Composable
internal fun FactLine(label: String, value: String, valueColor: Color = White) {
    if (Tok.isNew) NewFact(label, value, valueColor) else DetailLine(label, value, 170.dp, valueColor)
}

@Composable
internal fun FeatureRow(selected: Boolean = false, onClick: (() -> Unit)? = null, label: String? = null, content: @Composable RowScope.() -> Unit) {
    if (Tok.isNew) NewTableRow(selected, onClick = onClick, label = label, content = content)
    else SimpleTableRow(selected, onClick = onClick, label = label, content = content)
}

internal fun formatRemaining(millis: Long): String {
    val minutes = ((millis + 59_999) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "less than a minute"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h" + if (minutes % 60 == 0L) "" else " ${minutes % 60} min"
    }
}

@Composable
internal fun DialogText(text: String, color: Color = White.copy(.85f)) {
    Text(text, color = color, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 6.dp))
}
