package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
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
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Web
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val NewRailWidth = 76.dp

@Composable
internal fun NewWindow(shell: Shell, chrome: WindowChrome) {
    Column(Modifier.fillMaxSize().background(NewColors.Chrome)) {
        NewTitleBar(chrome)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            CharcoalColors { NewRail(shell) }
            Box(Modifier.weight(1f).fillMaxHeight().background(Ink)) {
                when (shell.tab) {
                    Tab.Connection -> NewConnectPage(shell)
                    Tab.Apps -> NewAppsPage(shell)
                    Tab.Servers -> NewServersPage(shell)
                    Tab.Rules -> NewRulesPage(shell)
                    Tab.Profiles -> NewProfilesPage(shell)
                    Tab.Workspaces -> WorkspacesPage(shell)
                    Tab.Bridge -> BridgePage(shell)
                    Tab.Diagnostics -> NewDiagnosticsPage(shell)
                    Tab.Health -> HealthPage(shell)
                    Tab.Traffic -> NewTrafficPage(shell)
                    Tab.Settings -> NewSettingsPage(shell)
                }
            }
        }
    }
}

@Composable
private fun NewTitleBar(chrome: WindowChrome) {
    CharcoalColors {
        Row(Modifier.fillMaxWidth().height(46.dp).background(NewColors.Chrome), verticalAlignment = Alignment.CenterVertically) {
            WindowDragArea(chrome.hostWindow, Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxSize().padding(start = (NewRailWidth - 25.dp) / 2), verticalAlignment = Alignment.CenterVertically) {
                    NewMark(25.dp)
                    Spacer(Modifier.width(11.dp))
                    Text("PerRoute", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(app.perroute.service.BuildIdentity.displayVersion(), color = White.copy(.82f), fontSize = 12.sp)
                    Spacer(Modifier.width(20.dp))
                }
            }
            Divider(Modifier.fillMaxHeight(), vertical = true)
            WindowButtons(chrome, White, height = 46.dp, width = 52.dp)
        }
    }
}

private val ToolTabs = listOf(Tab.Rules, Tab.Profiles, Tab.Workspaces, Tab.Bridge, Tab.Diagnostics, Tab.Health, Tab.Traffic)

@Composable
private fun NewRail(shell: Shell) {
    Column(Modifier.width(NewRailWidth).fillMaxHeight().background(NewColors.Chrome)) {
        Divider()
        RailItem("Connect", null, shell.tab == Tab.Connection) { shell.navigate(Tab.Connection) }
        RailItem("Apps", Icons.Rounded.GridView, shell.tab == Tab.Apps) { shell.navigate(Tab.Apps) }
        RailItem("Servers", Icons.Rounded.Dns, shell.tab == Tab.Servers) { shell.navigate(Tab.Servers) }
        Spacer(Modifier.weight(1f))
        Divider()
        var toolsOpen by remember { mutableStateOf(false) }
        Box {
            RailItem("Tools", Icons.Rounded.Tune, shell.tab.isTool, toolsLabel(shell)) { toolsOpen = true }
            PopupMenu(toolsOpen, { toolsOpen = false }, ToolTabs.map { tab ->
                MenuEntry(tab.label, toolIcon(tab), toolDetail(tab), selected = shell.tab == tab) { shell.navigate(tab) }
            }, minWidth = 230.dp)
        }
        Divider()
        RailItem("Settings", Icons.Outlined.Settings, shell.tab == Tab.Settings, selectedIcon = Icons.Rounded.Settings) { shell.navigate(Tab.Settings) }
        Spacer(Modifier.height(10.dp))
    }
}

private fun toolsLabel(shell: Shell): String = if (shell.tab.isTool) "Tools, now showing ${shell.tab.label}" else "Tools: Rules, Profiles, Workspaces, LAN Bridge, Diagnostics, Health and Traffic"

internal fun toolIcon(tab: Tab): ImageVector = when (tab) {
    Tab.Rules -> Icons.Rounded.Description
    Tab.Profiles -> Icons.Rounded.Folder
    Tab.Workspaces -> Icons.Rounded.Web
    Tab.Bridge -> Icons.Rounded.Devices
    Tab.Diagnostics -> Icons.Rounded.MonitorHeart
    Tab.Health -> Icons.Rounded.HealthAndSafety
    Tab.Traffic -> Icons.Rounded.BarChart
    else -> Icons.Rounded.Apps
}

private fun toolDetail(tab: Tab): String = when (tab) {
    Tab.Rules -> "Which connections use a proxy"
    Tab.Profiles -> "Presets, saved setups and recipes"
    Tab.Workspaces -> "Browser profiles with their own route"
    Tab.Bridge -> "Share this connection with your phone"
    Tab.Diagnostics -> "Route checks, inspector, rule tester"
    Tab.Health -> "Connection doctor and safety checks"
    Tab.Traffic -> "Usage, history and limits"
    else -> ""
}


@Composable
private fun RailItem(label: String, icon: ImageVector?, selected: Boolean, accessible: String = label, selectedIcon: ImageVector? = null, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(84.dp).focusRing(RoundedCornerShape(4.dp))
            .selectable(selected, role = Role.Tab, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .semantics { contentDescription = accessible }
            .background(if (selected) Color.White.copy(.05f) else Color.Transparent)
    ) {
        if (selected) Box(Modifier.width(4.dp).fillMaxHeight().background(NewColors.Lime))
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val tint = if (selected) NewColors.Lime else Color.White.copy(.9f)
            if (icon == null) NewMark(26.dp, tint) else Icon(if (selected && selectedIcon != null) selectedIcon else icon, null, tint = tint, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, color = Color.White.copy(if (selected) 1f else .88f), fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
        }
    }
}

@Composable
internal fun NewEyebrow(vararg parts: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { index, part ->
            if (index > 0) Text("  /  ", color = Muted.copy(.8f), fontSize = 11.5.sp, letterSpacing = 1.sp)
            Text(part.uppercase(), color = if (index == parts.lastIndex) White.copy(.72f) else Muted, fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 2.2.sp, maxLines = 1)
        }
    }
}

@Composable
internal fun NewHeading(eyebrow: List<String>, title: String, subtitle: String, modifier: Modifier = Modifier, actions: (@Composable RowScope.() -> Unit)? = null) {
    val compact = LocalCompact.current
    val heading: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            NewEyebrow(*eyebrow.toTypedArray())
            Spacer(Modifier.height(10.dp))
            Text(title, color = White, fontSize = if (compact) 32.sp else 38.sp, lineHeight = if (compact) 36.sp else 42.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.6).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Muted, fontSize = if (compact) 16.sp else 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    if (compact) Column(modifier.fillMaxWidth()) {
        heading(Modifier.fillMaxWidth())
        if (actions != null) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    } else Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        heading(Modifier.weight(1f))
        if (actions != null) {
            Spacer(Modifier.width(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

@Composable
internal fun NewConnectionChip(shell: Shell) {
    val (label, color) = when {
        shell.phase == ConnectionPhase.CONNECTING -> "Connecting…" to Amber
        shell.phase == ConnectionPhase.DISCONNECTING -> "Disconnecting…" to Amber
        shell.connected -> "Connected" to Cyan
        else -> "Not connected" to Muted
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color, 11.dp)
        Spacer(Modifier.width(9.dp))
        Text(label, color = White.copy(.8f), fontSize = 13.sp)
    }
}

internal fun newPanelWidth(total: Dp): Dp = if (total < 980.dp) 260.dp else (total * .34f).coerceIn(290.dp, 470.dp)

internal val LocalCompact = androidx.compose.runtime.compositionLocalOf { false }

@Composable
internal fun NewSplitPage(
    panel: @Composable ColumnScope.() -> Unit,
    panelScroll: Boolean = true,
    bottomBar: (@Composable RowScope.() -> Unit)? = null,
    mainBar: (@Composable RowScope.() -> Unit)? = null,
    main: @Composable BoxWithConstraintsScope.() -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelWidth = newPanelWidth(maxWidth + NewRailWidth)
        val narrow = maxWidth - panelWidth < 620.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(start = if (narrow) 22.dp else 34.dp, end = if (narrow) 20.dp else 30.dp, top = if (narrow) 22.dp else 30.dp, bottom = if (mainBar == null) 18.dp else 0.dp)) {
                        androidx.compose.runtime.CompositionLocalProvider(LocalCompact provides (maxWidth < 600.dp)) { main() }
                    }
                    if (mainBar != null) androidx.compose.runtime.CompositionLocalProvider(LocalCompact provides narrow) { NewBottomBar(content = mainBar) }
                }
                CharcoalColors {
                    Column(
                        Modifier.width(panelWidth).fillMaxHeight().background(Ink)
                            .then(if (panelScroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                            .padding(horizontal = 26.dp, vertical = 28.dp),
                        content = panel
                    )
                }
            }
            if (bottomBar != null) CharcoalColors { NewBottomBar(Modifier.background(NewColors.Chrome), bottomBar) }
        }
    }
}

@Composable
internal fun NewBottomBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Divider()
        Row(Modifier.fillMaxWidth().heightIn(min = 70.dp).padding(horizontal = 30.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
internal fun NewPanelHeading(eyebrow: String, title: String, subtitle: String = "", leading: (@Composable () -> Unit)? = null) {
    SectionLabel(eyebrow, White.copy(.85f))
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) { leading(); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(subtitle, color = White.copy(.8f), fontSize = 14.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun NewPanelSection(title: String? = null, divider: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(18.dp))
    if (divider) { Divider(); Spacer(Modifier.height(16.dp)) }
    if (title != null) {
        Text(title, color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
    }
    Column(content = content)
}

@Composable
internal fun NewFact(label: String, value: String, valueColor: Color = White) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Text(value, color = valueColor, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1.3f, fill = false))
    }
}

@Composable
internal fun NewTableRow(selected: Boolean, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, label: String? = null, content: @Composable RowScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().background(if (selected) Selection else Color.Transparent)
            .then(if (onClick != null) Modifier.pressable(RoundedCornerShape(0.dp), label = label, onClick = onClick) else Modifier)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
            if (selected) Box(Modifier.matchParentSize()) { Box(Modifier.width(4.dp).fillMaxHeight().background(NewColors.Lime)) }
        }
        Divider()
    }
}

@Composable
internal fun NewTableHeader(content: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, content = content)
        Divider()
    }
}


@Composable
internal fun NewPanelEmpty(eyebrow: String, title: String, detail: String, action: (@Composable () -> Unit)? = null) {
    NewPanelHeading(eyebrow, title)
    Spacer(Modifier.height(12.dp))
    Text(detail, color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
    if (action != null) { Spacer(Modifier.height(18.dp)); action() }
}

@Composable
internal fun NewLockNote(text: String, modifier: Modifier = Modifier) {
    Note(text, modifier, icon = Icons.Rounded.Lock, color = Amber)
}

