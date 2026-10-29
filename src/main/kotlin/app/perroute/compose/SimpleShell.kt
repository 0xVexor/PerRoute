package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Web
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SimpleWindow(shell: Shell, chrome: WindowChrome) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
        val sidebar = if (maxWidth >= 1000.dp) 244.dp else 204.dp
        Row(Modifier.fillMaxSize()) {
            SimpleSidebar(shell, chrome, Modifier.width(sidebar).fillMaxHeight(), showVersion = sidebar > 220.dp)
            Divider(Modifier.fillMaxHeight(), vertical = true)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    WindowDragArea(chrome.hostWindow, Modifier.weight(1f).fillMaxHeight())
                    WindowButtons(chrome, White.copy(.85f), height = 40.dp, width = 48.dp)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (shell.tab) {
                        Tab.Connection -> SimpleConnectionPage(shell)
                        Tab.Apps -> SimpleAppsPage(shell)
                        Tab.Servers -> SimpleServersPage(shell)
                        Tab.Rules -> SimpleRulesPage(shell)
                        Tab.Profiles -> SimpleProfilesPage(shell)
                        Tab.Workspaces -> WorkspacesPage(shell)
                        Tab.Bridge -> BridgePage(shell)
                        Tab.Diagnostics -> SimpleDiagnosticsPage(shell)
                        Tab.Health -> HealthPage(shell)
                        Tab.Traffic -> SimpleTrafficPage(shell)
                        Tab.Settings -> SimpleSettingsPage(shell)
                    }
                }
            }
        }
    }
}

private fun simpleIcon(tab: Tab): ImageVector = when (tab) {
    Tab.Connection -> Icons.Rounded.Link
    Tab.Apps -> Icons.Rounded.GridView
    Tab.Servers -> Icons.Rounded.Dns
    Tab.Rules -> Icons.Outlined.Description
    Tab.Profiles -> Icons.Outlined.Folder
    Tab.Workspaces -> Icons.Rounded.Web
    Tab.Bridge -> Icons.Rounded.Devices
    Tab.Diagnostics -> Icons.Rounded.MonitorHeart
    Tab.Health -> Icons.Rounded.HealthAndSafety
    Tab.Traffic -> Icons.Rounded.BarChart
    Tab.Settings -> Icons.Outlined.Settings
}

@Composable
private fun SimpleSidebar(shell: Shell, chrome: WindowChrome, modifier: Modifier, showVersion: Boolean) {
    Column(modifier.background(SimpleColors.sidebar(IsDark))) {
        WindowDragArea(chrome.hostWindow, Modifier.fillMaxWidth().height(96.dp)) {
            Row(Modifier.fillMaxSize().padding(start = 24.dp, end = 16.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(34.dp)
                Spacer(Modifier.width(12.dp))
                Text("PerRoute", color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                if (showVersion) {
                    Spacer(Modifier.width(12.dp))
                    Text(app.perroute.service.BuildIdentity.displayVersion(), color = Muted, fontSize = 12.5.sp)
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
        val short = maxHeight < (Tab.entries.size * 56 + 16).dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = if (short) 2.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (short) 2.dp else 8.dp)) {
            Tab.entries.forEach { tab ->
                val selected = shell.tab == tab
                val shape = RoundedCornerShape(10.dp)
                Row(
                    Modifier.fillMaxWidth().height(if (short) 40.dp else 48.dp).focusRing(shape).background(if (selected) Violet else Color.Transparent, shape)
                        .selectable(selected, role = Role.Tab) { shell.navigate(tab) }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(simpleIcon(tab), null, tint = if (selected) Color.White else White.copy(.75f), modifier = Modifier.size(23.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(tab.label, color = if (selected) Color.White else White.copy(.85f), fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
        }
        Text(Credit, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 26.dp, bottom = 22.dp, top = 8.dp))
    }
}

@Composable
internal fun SimplePage(
    title: String,
    subtitle: String,
    pill: String? = null,
    scroll: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxWidth < 760.dp
    androidx.compose.runtime.CompositionLocalProvider(LocalCompact provides compact) {
    Column(Modifier.fillMaxSize().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
        .padding(start = if (compact) 22.dp else 32.dp, end = if (compact) 20.dp else 28.dp, top = 6.dp, bottom = 22.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (pill != null) {
                        Spacer(Modifier.width(16.dp))
                        Text(pill, color = Violet2, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.background(Selection, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = Muted, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (actions != null) {
                Spacer(Modifier.width(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
        Spacer(Modifier.height(20.dp))
        Column(if (scroll) Modifier.fillMaxWidth() else Modifier.weight(1f).fillMaxWidth(), content = content)
    }
    }
    }
}

@Composable
internal fun SimpleCard(modifier: Modifier = Modifier, padding: Dp = 22.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth().background(Surface, shape).border(1.dp, Line, shape).padding(padding), content = content)
}

@Composable
internal fun SimpleTableHeader(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    Divider()
}

@Composable
internal fun SimpleTableRow(selected: Boolean = false, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, label: String? = null, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().background(if (selected) Selection else Color.Transparent, RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.pressable(RoundedCornerShape(8.dp), label = label, onClick = onClick) else Modifier)
            .heightIn(min = 58.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, content = content
    )
    Divider()
}

@Composable
internal fun SimpleSettingRow(title: String, detail: String, note: String = "", divider: Boolean = true, control: (@Composable () -> Unit)? = null) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 520.dp
        val text: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier) {
                Text(title, color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (detail.isNotBlank()) { Spacer(Modifier.height(3.dp)); Text(detail, color = Muted, fontSize = 13.sp, lineHeight = 18.sp) }
            }
        }
        if (narrow && control != null) {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                text(Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                control()
                if (note.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(note, color = Muted, fontSize = 12.sp) }
            }
        } else Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            text(Modifier.weight(1f))
            if (control != null) {
                Spacer(Modifier.width(18.dp))
                Column(horizontalAlignment = Alignment.End) {
                    control()
                    if (note.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(note, color = Muted, fontSize = 12.sp) }
                }
            }
        }
    }
    if (divider) Divider()
}

@Composable
internal fun SimpleSectionTitle(title: String, detail: String = "", trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            if (detail.isNotBlank()) Text(detail, color = Muted, fontSize = 13.sp)
        }
        if (trailing != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = trailing)
    }
}

@Composable
internal fun SimpleLockNote(text: String, modifier: Modifier = Modifier.fillMaxWidth()) =
    InfoBanner(text, modifier, Amber, Icons.Rounded.Lock)

