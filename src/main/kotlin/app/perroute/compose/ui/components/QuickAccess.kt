package app.perroute.compose.ui.components

import app.perroute.compose.*
import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import app.perroute.compose.controller.RouteCheck
import app.perroute.compose.controller.RouteCheckState
import app.perroute.compose.controller.TrafficSnapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.ProxyNode
import app.perroute.service.RoutePlanner

internal class QuickAccessState(
    val connected: Boolean,
    val busy: Boolean,
    val working: Boolean,
    val main: ProxyNode?,
    val nodes: List<ProxyNode>,
    val favorites: Set<String>,
    val traffic: TrafficSnapshot,
    val plan: RoutePlanner.Plan,
    val mainCheck: RouteCheck?,
    val onToggle: () -> Unit,
    val onFastest: () -> Unit,
    val onServer: (String) -> Unit,
    val onOpen: () -> Unit,
    val onExit: () -> Unit,
    val temporaries: List<TemporaryRoute> = emptyList(),
    val apps: List<String> = emptyList(),
    val perApp: Boolean = false,
    val onTemporary: (String, String, Int) -> Unit = { _, _, _ -> },
    val onEndTemporary: (String) -> Unit = {},
    val onShowTemporary: () -> Unit = {}
) {
    val inUse: Map<String, RouteKindLabel> = plan.routes().filter { !it.main() }.associate {
        it.node().key() to when { it.temporary() -> RouteKindLabel.TEMPORARY; it.auto() -> RouteKindLabel.AUTO; else -> RouteKindLabel.CUSTOM }
    }
    val ordered: List<ProxyNode> = nodes.sortedWith(compareBy<ProxyNode> {
        when {
            it.key() == main?.key() -> 0
            it.key() in inUse -> 1
            it.key() in favorites -> 2
            else -> 3
        }
    }.thenBy { serverName(it).lowercase() })

    fun kind(node: ProxyNode): RouteKindLabel? = if (node.key() == main?.key()) RouteKindLabel.MAIN else inUse[node.key()]

    val statusText: String get() = when {
        busy && connected -> "Disconnecting…"
        busy -> "Connecting…"
        connected -> "Connected"
        else -> "Not connected"
    }

    val buttonLabel: String get() = when {
        busy && connected -> "Disconnecting…"
        busy -> "Connecting…"
        connected -> "Disconnect"
        else -> "Connect"
    }

    fun menu(): List<MenuEntry> = listOf(
        MenuEntry("Switch to the fastest main server", Icons.Rounded.Speed, "Tests reachable servers first", enabled = nodes.size > 1 && !working, onClick = onFastest),
        MenuEntry("Use a server for a while…", Icons.Rounded.Timer, if (perApp) "One app, for a limited time" else "Needs Smart per-app mode",
            enabled = perApp && apps.isNotEmpty() && nodes.size > 1 && !working, onClick = onShowTemporary),
        MenuEntry("Quit PerRoute", Icons.Rounded.PowerSettingsNew, "Disconnects and closes the app", danger = true, onClick = onExit)
    )

    val summary: String get() = if (plan.applications().isEmpty()) "No apps selected" else
        "${plural(plan.applications().size, "application")}  ·  ${plural(plan.routes().size, "route")}"

    val missing: Int get() = plan.missing().size
}

internal fun quickCheckText(state: QuickAccessState, now: Long): Pair<String, Boolean?> {
    val check = state.mainCheck
    return when {
        !state.connected || check == null -> "Not checked yet" to null
        check.state == RouteCheckState.CHECKING -> "Checking…" to null
        check.state == RouteCheckState.FAILED -> "Check failed  ·  ${formatAgo(check.atMillis, now)}" to false
        check.stale(now) -> "Stale  ·  ${formatAgo(check.atMillis, now)}" to false
        check.state == RouteCheckState.PASSED -> "Passed" + (check.latencyMillis?.let { "  ·  $it ms" } ?: "") + "  ·  ${formatAgo(check.atMillis, now)}" to true
        else -> "Not checked yet" to null
    }
}

@Composable
internal fun QuickAccessWindow(
    connected: Boolean,
    busy: Boolean,
    working: Boolean,
    main: ProxyNode?,
    nodes: List<ProxyNode>,
    favorites: Set<String>,
    traffic: TrafficSnapshot,
    plan: RoutePlanner.Plan,
    mainCheck: RouteCheck?,
    onToggle: () -> Unit,
    onFastest: () -> Unit,
    onServer: (String) -> Unit,
    onOpen: () -> Unit,
    onExit: () -> Unit,
    temporaries: List<TemporaryRoute> = emptyList(),
    apps: List<String> = emptyList(),
    perApp: Boolean = false,
    onTemporary: (String, String, Int) -> Unit = { _, _, _ -> },
    onEndTemporary: (String) -> Unit = {}
) {
    var showTemporary by remember { mutableStateOf(false) }
    val state = QuickAccessState(connected, busy, working, main, nodes, favorites, traffic, plan, mainCheck, onToggle, onFastest, onServer, onOpen, onExit,
        temporaries, apps, perApp, { app, key, minutes -> showTemporary = false; onTemporary(app, key, minutes) }, onEndTemporary, { showTemporary = true })
    when (Tok.style) {
        UiStyle.NEW -> NewQuickAccess(state, showTemporary) { showTemporary = false }
        UiStyle.SIMPLE -> SimpleQuickAccess(state, showTemporary) { showTemporary = false }
    }
}

@Composable
private fun QuickTemporaries(state: QuickAccessState, now: Long) {
    state.temporaries.forEach { route ->
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Timer, null, tint = Amber, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            EllipsisText("${appName(route.application)} · ${state.nodes.firstOrNull { it.key() == route.nodeKey }?.let(::serverName) ?: "server"} · ${formatRemaining(route.remainingMillis(now))}",
                White.copy(.9f), 11.5.sp, Modifier.weight(1f))
            LinkAction("End", arrow = false, enabled = !state.working, fontSize = 11.5.sp) { state.onEndTemporary(route.application) }
        }
    }
}

@Composable
private fun NewQuickAccess(state: QuickAccessState, temporary: Boolean, closeTemporary: () -> Unit) {
    val now by rememberNow()
    Column(Modifier.fillMaxSize().background(Ink)) {
        CharcoalColors {
            Column(Modifier.fillMaxWidth().background(Ink).padding(horizontal = 22.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NewMark(24.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("PerRoute", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text(app.perroute.service.BuildIdentity.displayVersion(), color = White.copy(.75f), fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    MenuIcon(Icons.Rounded.MoreHoriz, "More actions", state.menu(), tint = White, bordered = false)
                }
                Spacer(Modifier.height(14.dp))
                val (label, color) = when {
                    state.busy -> state.statusText.uppercase() to Amber
                    state.connected -> "CONNECTED" to Cyan
                    else -> "NOT CONNECTED" to Muted
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color, 9.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.5.sp)
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Top) {
                    ServerAvatar(state.main, 56.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        EllipsisText(state.main?.let(::serverName) ?: "No server selected", White, 21.sp, fontWeight = FontWeight.Bold)
                        Text(if (state.main == null) "Open PerRoute to add a server" else "Main", color = White.copy(.75f), fontSize = 14.sp)
                        state.main?.let { EllipsisText(it.endpoint(), White.copy(.75f), 12.sp) }
                        Spacer(Modifier.height(6.dp))
                        val (check, passed) = quickCheckText(state, now)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(when (passed) { true -> Icons.Rounded.CheckCircle; false -> Icons.Rounded.WarningAmber; null -> Icons.Rounded.Schedule }, null,
                                tint = when (passed) { true -> Cyan; false -> Amber; null -> Muted }, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(check, color = White.copy(.9f), fontSize = 12.5.sp, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ArrowDownward, null, tint = Color(0xFF6FB6FF), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.connected) formatMbps(state.traffic.downloadBytesPerSecond) else "—", color = White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(16.dp))
                    Divider(Modifier.height(24.dp), vertical = true)
                    Spacer(Modifier.width(16.dp))
                    Icon(Icons.Rounded.ArrowUpward, null, tint = Cyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.connected) formatMbps(state.traffic.uploadBytesPerSecond) else "—", color = White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(14.dp))
                PrimaryButton(state.buttonLabel, Modifier.fillMaxWidth(), icon = Icons.Rounded.PowerSettingsNew, enabled = state.main != null,
                    busy = state.busy, height = 48.dp, onClick = state.onToggle)
                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(10.dp))
                Text(state.summary, color = White.copy(.85f), fontSize = 12.5.sp)
                QuickTemporaries(state, now)
                if (state.missing > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text("${plural(state.missing, "app")} ${if (state.missing == 1) "needs" else "need"} a replacement server. Open PerRoute to fix it.",
                        color = Amber, fontSize = 11.5.sp, lineHeight = 15.sp)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp)) {
            if (temporary) {
                QuickTemporaryPanel(state.apps, state.ordered, state.main?.key(), state.working, state.onTemporary, closeTemporary)
                Spacer(Modifier.weight(1f))
                return@Column
            }
            Text("Main server choices", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Other app routes stay unchanged.", color = Muted, fontSize = 12.5.sp)
            Spacer(Modifier.height(8.dp))
            if (state.nodes.isEmpty()) Text("No servers yet. Open PerRoute to add one.", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            else LazyColumn(Modifier.weight(1f)) {
                items(state.ordered, key = { it.key() }) { node -> QuickServerRow(state, node) }
            }
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Open PerRoute", Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.OpenInNew, height = 44.dp, onClick = state.onOpen)
            Spacer(Modifier.height(8.dp))
            Text(Credit, color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.End))
        }
    }
}

@Composable
internal fun QuickServerRow(state: QuickAccessState, node: ProxyNode) {
    val active = node.key() == state.main?.key()
    val shape = RoundedCornerShape(6.dp)
    Column {
        Row(
            Modifier.fillMaxWidth().height(58.dp).alpha(if (state.working && !active) .6f else 1f).focusRing(shape)
                .selectable(active, enabled = !state.working, role = Role.RadioButton) { state.onServer(node.key()) }
                .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioMark(active, 22.dp)
            Spacer(Modifier.width(14.dp))
            ServerAvatar(node, 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EllipsisText(serverName(node), White, 14.sp, Modifier.weight(1f, fill = false), FontWeight.SemiBold)
                    if (!Tok.isNew) state.kind(node)?.let { Spacer(Modifier.width(8.dp)); RoutePill(it) }
                }
                Text(protocolLabel(node) + if (Tok.isNew) state.kind(node)?.let { "  ·  ${it.label}" }.orEmpty() else "", color = Muted, fontSize = 12.sp)
            }
            val favorite = node.key() in state.favorites
            Icon(if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (favorite) "Favorite" else null,
                tint = if (favorite) Color(0xFFF2B01E) else Muted, modifier = Modifier.size(22.dp))
        }
        Divider()
    }
}


@Composable
internal fun SimpleQuickAccess(state: QuickAccessState, temporary: Boolean = false, closeTemporary: () -> Unit = {}) {
    val now by rememberNow()
    Column(Modifier.fillMaxSize().background(Surface).padding(horizontal = 22.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(30.dp)
            Spacer(Modifier.width(10.dp))
            Text("PerRoute", color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Text(app.perroute.service.BuildIdentity.displayVersion(), color = Muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            MenuIcon(Icons.Rounded.MoreHoriz, "More actions", state.menu(), tint = White.copy(.8f), bordered = false)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (state.busy) Amber else if (state.connected) Cyan else Muted.copy(.6f), 14.dp)
            Spacer(Modifier.width(12.dp))
            Text(state.statusText, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        val shape = RoundedCornerShape(12.dp)
        Row(Modifier.fillMaxWidth().border(1.dp, Line, shape).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ServerAvatar(state.main, 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (state.main != null) RoutePill(RouteKindLabel.MAIN)
                EllipsisText(state.main?.let(::serverName) ?: "No server selected", White, 16.sp, fontWeight = FontWeight.SemiBold)
                EllipsisText(state.main?.endpoint() ?: "Open PerRoute to add a server", Muted, 12.sp)
            }
            val (check, passed) = quickCheckText(state, now)
            if (state.connected) Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(110.dp)) {
                Icon(when (passed) { true -> Icons.Rounded.CheckCircle; false -> Icons.Rounded.WarningAmber; null -> Icons.Rounded.Schedule }, null,
                    tint = when (passed) { true -> Cyan; false -> Amber; null -> Muted }, modifier = Modifier.size(20.dp))
                Text(check, color = if (passed == true) Cyan else Muted, fontSize = 11.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(Triple(Icons.Rounded.ArrowDownward, "Download", state.traffic.downloadBytesPerSecond), Triple(Icons.Rounded.ArrowUpward, "Upload", state.traffic.uploadBytesPerSecond))
                .forEachIndexed { index, (icon, label, rate) ->
                    if (index > 0) Divider(Modifier.height(36.dp), vertical = true)
                    Row(Modifier.weight(1f).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = Violet2, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(10.dp))
                        Column { Text(label, color = Muted, fontSize = 12.sp); Text(if (state.connected) formatRate(rate) else "—", color = White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(state.buttonLabel, Modifier.fillMaxWidth(), icon = Icons.Rounded.PowerSettingsNew, enabled = state.main != null, busy = state.busy, height = 48.dp, onClick = state.onToggle)
        Spacer(Modifier.height(10.dp))
        Text(state.summary, color = White, fontSize = 13.sp)
        Text(if (state.missing > 0) "${plural(state.missing, "app")} ${if (state.missing == 1) "needs" else "need"} a replacement server. Open PerRoute to fix it." else "Other app routes stay unchanged.",
            color = if (state.missing > 0) Amber else Muted, fontSize = 12.sp)
        QuickTemporaries(state, now)
        Spacer(Modifier.height(10.dp))
        Divider()
        Spacer(Modifier.height(10.dp))
        if (temporary) {
            QuickTemporaryPanel(state.apps, state.ordered, state.main?.key(), state.working, state.onTemporary, closeTemporary)
            Spacer(Modifier.weight(1f))
            return@Column
        }
        Text("Main server choices", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (state.nodes.isEmpty()) Text("No servers yet. Open PerRoute to add one.", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        else LazyColumn(Modifier.weight(1f)) { items(state.ordered, key = { it.key() }) { node -> QuickServerRow(state, node) } }
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Open PerRoute", Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.OpenInNew, tint = Violet2, height = 44.dp, onClick = state.onOpen)
        Spacer(Modifier.height(8.dp))
        Text(Credit, color = Muted, fontSize = 11.sp)
    }
}
