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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.ConnectionMode
import app.perroute.model.ProxyNode
import app.perroute.routing.RouteHealthTracker
import app.perroute.service.AppProxyLauncher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (isActive) { delay(1_000); value = System.currentTimeMillis() }
}

internal class RouteRow(val node: ProxyNode, val main: Boolean, val applications: List<String>, val id: String = node.key(), val auto: Boolean = false) {
    val temporary: Boolean get() = id.startsWith(app.perroute.service.RoutePlanner.TEMPORARY_ROUTE_PREFIX)
    val kind: RouteKindLabel get() = when {
        temporary -> RouteKindLabel.TEMPORARY
        auto -> RouteKindLabel.AUTO
        main -> RouteKindLabel.MAIN
        else -> RouteKindLabel.CUSTOM
    }
}

internal fun routeRows(shell: Shell): List<RouteRow> =
    if (shell.connected) shell.activeRoutes.map { RouteRow(it.node, it.main, it.applications, it.id, it.auto) }
    else shell.plan.routes().map { RouteRow(it.node(), it.main(), if (shell.perApp) it.applications() else emptyList(), it.id(), it.auto()) }

@Composable
internal fun RouteKindBadge(row: RouteRow) = RoutePill(row.kind)

internal fun modeLabel(mode: ConnectionMode): String = when (mode) {
    ConnectionMode.NATIVE_PER_APP -> "Smart per-app"
    ConnectionMode.BROWSER_PROXY -> "Local proxy"
    ConnectionMode.SYSTEM_PROXY -> "System proxy"
    ConnectionMode.TUN_COMPATIBILITY -> "TUN"
}

internal fun modeDetail(mode: ConnectionMode): String = when (mode) {
    ConnectionMode.NATIVE_PER_APP -> "Only selected apps use the proxy."
    ConnectionMode.BROWSER_PROXY -> "Apps you point at the local proxy use it."
    ConnectionMode.SYSTEM_PROXY -> "Apps that follow system proxy settings use it."
    ConnectionMode.TUN_COMPATIBILITY -> "Network traffic goes through the compatibility tunnel."
}

internal class ConnectionView(val shell: Shell) {
    val main: ProxyNode? = shell.mainNode
    val rows: List<RouteRow> = routeRows(shell)
    val missing = shell.plan.missing()
    val needsApps = shell.perApp && shell.apps.isEmpty()
    val connecting = shell.phase == ConnectionPhase.CONNECTING
    val disconnecting = shell.phase == ConnectionPhase.DISCONNECTING
    val connected = shell.connected && !disconnecting
    val localProxy = shell.mode == ConnectionMode.BROWSER_PROXY && main != null
    val monitoring = shell.controller.routeMonitoring()
    val incomplete = main == null || needsApps || missing.isNotEmpty()
    val buttonEnabled = (!shell.busy || connecting || disconnecting) && main != null
    val title: String = when {
        connecting -> "Connecting…"
        disconnecting -> "Disconnecting…"
        shell.connected -> "Connected"
        incomplete -> "Complete your setup"
        else -> "Not connected"
    }
    val buttonLabel: String = when {
        connecting -> "Connecting…"
        disconnecting -> "Disconnecting…"
        shell.connected -> "Disconnect"
        else -> "Connect"
    }
    val routeCount: Int = rows.map { it.id }.distinct().size
    val serverCount: Int = rows.map { it.node.key() }.distinct().size
    val healthy: Int = shell.routeHealth.count { it.health?.state() == RouteHealthTracker.State.HEALTHY }
    val affected: Int = shell.routeHealth.count { it.health?.affected() == true }
    val recovering: Boolean = shell.connected && monitoring && shell.routeHealth.any { it.health?.state() == RouteHealthTracker.State.RECOVERING }

    fun health(row: RouteRow): RouteHealthTracker.Snapshot? =
        if (shell.connected) shell.routeHealth.firstOrNull { it.route.id == row.id }?.health else null

    val lastChecked: Long? = (rows.mapNotNull { shell.routeChecks[it.id]?.takeIf { c -> c.state == RouteCheckState.PASSED || c.state == RouteCheckState.FAILED }?.atMillis } +
        shell.routeHealth.mapNotNull { it.health?.let { h -> maxOf(h.lastPassedAt(), h.lastFailedAt()).takeIf { at -> at > 0 } } }).maxOrNull()
}

internal class ConnectionNotice(val text: String, val warning: Boolean, val action: String?, val onAction: (() -> Unit)?)

internal fun connectionNotice(view: ConnectionView): ConnectionNotice? {
    val shell = view.shell
    return when {
        view.main == null -> ConnectionNotice("Add a server to get started. PerRoute supports subscriptions, share links and QR codes.", true, "Add servers") {
            shell.addServersRequested = true; shell.navigate(Tab.Servers)
        }
        view.missing.isNotEmpty() -> ConnectionNotice(
            "${appName(view.missing[0].applicationPath())} is assigned to ${view.missing[0].label().ifBlank { "a custom server" }}, which is no longer in your server list" +
                (if (view.missing.size > 1) ", and ${plural(view.missing.size - 1, "more app")} has the same problem" else "") +
                ". Connecting is blocked until you choose a replacement.", true, "Fix in Apps"
        ) { shell.navigate(Tab.Apps) }
        view.needsApps -> ConnectionNotice("Choose at least one application for Smart per-app.", true, "Manage apps") { shell.navigate(Tab.Apps) }
        else -> null
    }
}

@Composable
internal fun RouteCheckStatus(check: RouteCheck?, connected: Boolean, now: Long, modifier: Modifier = Modifier, showLatency: Boolean = true, showAge: Boolean = true) {
    val state = check?.state ?: RouteCheckState.NOT_CHECKED
    val stale = check?.stale(now) == true
    val (icon, color, title) = when {
        !connected -> Triple(Icons.Rounded.RadioButtonUnchecked, Muted, "Not connected")
        state == RouteCheckState.CHECKING -> Triple(null, Muted, "Checking…")
        state == RouteCheckState.NOT_CHECKED -> Triple(Icons.Rounded.Schedule, Muted, "Not checked yet")
        stale -> Triple(Icons.Rounded.Schedule, Amber, if (state == RouteCheckState.PASSED) "Stale · last passed" else "Stale · last failed")
        state == RouteCheckState.PASSED -> Triple(Icons.Rounded.CheckCircle, Cyan,
            if (showLatency && check?.latencyMillis != null) "Passed · ${check.latencyMillis} ms" else "Passed")
        else -> Triple(Icons.Rounded.WarningAmber, Amber, "Check failed")
    }
    val detail = when {
        !connected || !showAge -> ""
        state == RouteCheckState.PASSED || state == RouteCheckState.FAILED -> formatAgo(check!!.atMillis, now)
        else -> ""
    }
    Tooltip(if (connected) check?.detail.orEmpty() else "Connect to check this server.", modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon == null) CircularProgressIndicator(color = Muted, strokeWidth = 2.dp, modifier = Modifier.size(15.dp))
            else Icon(icon, null, tint = color, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (detail.isNotBlank()) Text(detail, color = Muted, fontSize = 10.5.sp, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun RouteHealthStatus(health: RouteHealthTracker.Snapshot?, check: RouteCheck?, connected: Boolean, now: Long, modifier: Modifier = Modifier, compact: Boolean = false) {
    val state = health?.state()
    if (!connected || health == null || state == null) {
        RouteCheckStatus(check, connected, now, modifier, showAge = !compact); return
    }
    val wait = ((health.nextCheckAt() - now) / 1_000L).coerceAtLeast(0L)
    val (label, color) = when (state) {
        RouteHealthTracker.State.HEALTHY -> "Healthy" to Cyan
        RouteHealthTracker.State.RECOVERING -> "Recovering" to Amber
        RouteHealthTracker.State.CHECKING -> "Checking…" to Muted
        else -> "Route failed" to Red
    }
    Tooltip(health.lastError(), modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                RouteHealthTracker.State.HEALTHY -> Icon(Icons.Rounded.CheckCircle, null, tint = color, modifier = Modifier.size(19.dp))
                RouteHealthTracker.State.RECOVERING -> Icon(Icons.Rounded.Autorenew, null, tint = color, modifier = Modifier.size(19.dp))
                RouteHealthTracker.State.CHECKING -> CircularProgressIndicator(color = color, strokeWidth = 2.dp, modifier = Modifier.size(15.dp))
                else -> Icon(Icons.Rounded.ErrorOutline, null, tint = color, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(7.dp))
            Column {
                Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (!compact && (state == RouteHealthTracker.State.RECOVERING || state == RouteHealthTracker.State.FAILED)) Text(
                    if (state == RouteHealthTracker.State.RECOVERING) "Attempt ${health.attempt()} of ${health.maxAttempts()}  ·  Retry in ${wait}s"
                    else "Checked again in ${wait}s", color = Muted, fontSize = 10.5.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun changeServerEntries(shell: Shell, row: RouteRow, filter: String, open: Boolean): List<MenuEntry> {
    val latencies = remember(open) { if (open) shell.controller.cachedLatencies() else emptyMap() }
    val favorites = remember(open) { if (open) shell.nodes.filter { shell.controller.isFavorite(it.key()) }.mapTo(hashSetOf()) { it.key() } else emptySet() }
    return shell.nodes
        .filter { it.key() != row.node.key() }
        .filter { node -> filter.isBlank() || serverName(node).contains(filter, true) || node.endpoint().contains(filter, true) }
        .sortedByDescending { it.key() in favorites }
        .map { node ->
            MenuEntry(serverName(node), icon = if (node.key() in favorites) Icons.Rounded.Star else null,
                detail = listOfNotNull(protocolLabel(node), latencies[node.key()]?.takeIf { it >= 0 }?.let { "$it ms" },
                    if (node.key() == shell.mainNode?.key() && !row.main) "Main server" else null).joinToString("  •  ")
            ) { shell.run(done = { if (it.ok) shell.draft.load(shell) }) { shell.controller.changeRouteServer(row.id, node.key()) } }
        }
}

@Composable
internal fun ChangeServerButton(shell: Shell, row: RouteRow, modifier: Modifier = Modifier, height: Dp = 40.dp) {
    var open by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    Box(modifier) {
        SecondaryButton("Change server", Modifier.height(height), trailing = Icons.Rounded.NorthEast,
            enabled = !shell.busy && shell.nodes.size > 1) { filter = ""; open = true }
        PopupMenu(open, { open = false }, changeServerEntries(shell, row, filter, open), minWidth = 280.dp, header = if (shell.nodes.size > 8) ({
            Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                TextInput(filter, { filter = it }, "Filter servers", icon = Icons.Rounded.Search)
            }
        }) else null)
    }
}

internal fun retryRoute(shell: Shell, row: RouteRow) = shell.run { shell.controller.retryRoute(row.id) }

internal fun recoveryExplanation(shell: Shell, row: RouteRow): String {
    val killSwitch = shell.controller.killSwitch()
    val apps = joinNames(row.applications.map(::appName)).ifBlank { "Apps on this route" }
    return "$apps ${if (row.applications.size == 1) "is" else "are"} waiting for ${serverName(row.node)}. Other apps and routes keep working. " +
        (if (killSwitch) "The kill switch keeps these apps offline until the route is back." else "Their connections fail instead of leaving through your regular connection.") +
        (if (row.auto) " Auto moves the app to another working server when this one does not recover." else "")
}

@Composable
internal fun RecoveryTimeline(health: RouteHealthTracker.Snapshot, modifier: Modifier = Modifier, limit: Int = 4) {
    val events = health.events().takeLast(limit)
    Column(modifier) {
        events.forEachIndexed { index, event ->
            val color = when (event.type()) {
                RouteHealthTracker.EventType.CHECK_FAILED, RouteHealthTracker.EventType.EXHAUSTED -> Amber
                RouteHealthTracker.EventType.RECOVERED, RouteHealthTracker.EventType.CHECK_PASSED -> Cyan
                else -> Amber
            }
            Row(verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(14.dp)) {
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.size(11.dp).clip(CircleShape).background(color))
                    if (index < events.lastIndex) Box(Modifier.width(1.5.dp).height(26.dp).background(color.copy(.5f)))
                }
                Spacer(Modifier.width(10.dp))
                Text(formatTimeOfDay(event.atMillis()), color = Muted, fontSize = 11.5.sp, modifier = Modifier.width(44.dp))
                Column(Modifier.padding(bottom = 6.dp)) {
                    Text(event.title(), color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    if (event.detail().isNotBlank()) Text(event.detail(), color = Muted, fontSize = 10.5.sp, lineHeight = 14.sp)
                }
            }
        }
        if (events.isEmpty()) Text("No events yet.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
internal fun RecoveryProtection(shell: Shell) {
    val killSwitch = shell.controller.killSwitch()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Shield, null, tint = White.copy(.7f), modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (killSwitch) "Kill switch active" else "Kill switch off", color = White.copy(.85f), fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
        Text("  ·  No direct fallback.", color = Muted, fontSize = 11.5.sp)
    }
}

@Composable
internal fun RecoveryDetails(shell: Shell, row: RouteRow, health: RouteHealthTracker.Snapshot, modifier: Modifier = Modifier, stacked: Boolean = false) {
    val content: @Composable (Modifier) -> Unit = { inner ->
        Row(inner, verticalAlignment = Alignment.Top) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(Amber), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PriorityHigh, null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text("Only this route is affected.", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                RecoveryProtection(shell)
                Spacer(Modifier.height(3.dp))
                Text(recoveryExplanation(shell, row), color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
                if (health.lastError().isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text("Last error: ${health.lastError()}", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton("Retry now", icon = Icons.Rounded.Autorenew, color = Amber, contentColor = if (Tok.isNew) Color(0xFF141614) else Color.White,
                        enabled = !shell.busy) { retryRoute(shell, row) }
                    ChangeServerButton(shell, row)
                }
            }
        }
    }
    if (stacked) Column(modifier) {
        content(Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        RecoveryTimeline(health, limit = 3)
    } else Row(modifier, verticalAlignment = Alignment.Top) {
        content(Modifier.weight(1.25f))
        Spacer(Modifier.width(14.dp))
        Divider(Modifier.height(104.dp), vertical = true)
        Spacer(Modifier.width(14.dp))
        RecoveryTimeline(health, Modifier.weight(1f).widthIn(min = 160.dp), limit = 3)
    }
}

@Composable
internal fun ProxyEndpoints(shell: Shell, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("SOCKS5" to AppProxyLauncher.SOCKS_PORT, "HTTP" to AppProxyLauncher.HTTP_PORT).forEach { (label, port) ->
            val value = "127.0.0.1:$port"
            val active = shell.connected
            val shape = ControlShape
            Row(
                Modifier.weight(1f).height(48.dp).pressable(shape, label = "Copy $label endpoint $value") {
                    clipboard.setText(AnnotatedString(value))
                    shell.notify(ActionResult(true, "$label endpoint copied."))
                }.background(Field).border(1.dp, if (active) Cyan.copy(.5f) else Line, shape).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = if (active) Cyan else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(value, color = White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                Icon(Icons.Rounded.ContentCopy, null, tint = Muted, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
internal fun mainServerEntries(shell: Shell, open: Boolean): List<MenuEntry> {
    val favorites = remember(open) { if (open) shell.nodes.filter { shell.controller.isFavorite(it.key()) }.mapTo(hashSetOf()) { it.key() } else emptySet() }
    val latencies = remember(open) { if (open) shell.controller.cachedLatencies() else emptyMap() }
    val main = shell.mainNode?.key()
    return shell.nodes.sortedByDescending { it.key() in favorites }.map { node ->
        MenuEntry(serverName(node), icon = if (node.key() in favorites) Icons.Rounded.Star else null,
            detail = listOfNotNull(protocolLabel(node), latencies[node.key()]?.takeIf { it >= 0 }?.let { "$it ms" }).joinToString("  •  "),
            selected = node.key() == main, enabled = node.key() != main && !shell.busy) {
            shell.run { shell.controller.switchNode(node.key()) }
        }
    }
}

internal fun activeAppSummary(traffic: TrafficSnapshot): String {
    val active = traffic.applications.filter { it.active }
    if (active.isEmpty()) return "No selected app is using the tunnel right now"
    return "Active: " + active.joinToString(", ") { "${appName(it.path)} (${formatBytes(it.downloadBytes + it.uploadBytes)})" }
}

internal fun formatMbps(bytesPerSecond: Long): String {
    val megabits = bytesPerSecond.coerceAtLeast(0) * 8 / 1_000_000.0
    return when {
        megabits >= 100 -> "${megabits.toInt()} Mbps"
        megabits >= 0.1 || megabits == 0.0 -> String.format(java.util.Locale.ROOT, "%.1f Mbps", megabits)
        else -> String.format(java.util.Locale.ROOT, "%.0f Kbps", megabits * 1000)
    }
}
