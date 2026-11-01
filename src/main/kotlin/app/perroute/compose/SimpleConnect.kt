package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Schedule
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.ConnectionMode

private class SimpleConnectView { var details by mutableStateOf(false) }

@Composable
internal fun SimpleConnectionPage(shell: Shell) {
    val view = ConnectionView(shell)
    val now by rememberNow()
    val state = shell.screen("${Tab.Connection.name}:simple") { SimpleConnectView() }
    SimplePage("Connection", modeDetail(shell.mode), pill = modeLabel(shell.mode)) {
        SimpleCard(padding = 0.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                val color = when {
                    view.connecting || view.disconnecting -> Amber
                    shell.connected -> Cyan
                    view.incomplete -> Amber
                    else -> Muted.copy(.6f)
                }
                StatusDot(color, 16.dp)
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(view.title, color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(if (shell.perApp) "${plural(shell.apps.size, "app")}  ·  ${plural(view.routeCount, "route")}" else modeLabel(shell.mode),
                        color = Muted, fontSize = 15.sp)
                }
                PrimaryButton(view.buttonLabel, Modifier.width(220.dp), icon = Icons.Rounded.PowerSettingsNew, enabled = view.buttonEnabled,
                    busy = view.connecting || view.disconnecting, height = 50.dp, onClick = shell::toggleConnection)
            }
            Divider(Modifier.padding(horizontal = 28.dp))
            Column(Modifier.padding(horizontal = 28.dp, vertical = 20.dp)) {
                SectionLabel("Main server")
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServerAvatar(view.main, 58.dp)
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        EllipsisText(view.main?.let(::serverName) ?: "No server yet", White, 19.sp, fontWeight = FontWeight.SemiBold)
                        Text(view.main?.let { "${protocolLabel(it)}  ·  ${PerRouteController.sourceLabel(shell.controller.nodeSource(it.key()))}" } ?: "Add a subscription, share link or QR code",
                            color = Muted, fontSize = 14.sp, maxLines = 1)
                    }
                    if (view.main != null && shell.connected) {
                        val check = shell.routeChecks[view.rows.firstOrNull { it.main }?.id ?: view.main.key()]
                        RouteCheckStatus(check, true, now, showAge = false)
                        Spacer(Modifier.width(24.dp))
                    }
                    if (view.main == null) PrimaryButton("Add servers") { shell.addServersRequested = true; shell.navigate(Tab.Servers) }
                    else ChangeMainButton(shell)
                }
                if (view.localProxy) {
                    Spacer(Modifier.height(16.dp))
                    ProxyEndpoints(shell, Modifier.fillMaxWidth())
                }
                connectionNotice(view)?.takeIf { view.main != null }?.let { notice ->
                    Spacer(Modifier.height(16.dp))
                    InfoBanner(notice.text, Modifier.fillMaxWidth(), Amber, Icons.Rounded.WarningAmber) {
                        if (notice.action != null && notice.onAction != null) SecondaryButton(notice.action, height = 34.dp, onClick = notice.onAction)
                    }
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (shell.perApp) "Applications" else "Applications in ${modeLabel(shell.mode)}", color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (shell.perApp) LinkAction("Manage apps", fontSize = 14.sp) { shell.navigate(Tab.Apps) }
        }
        Spacer(Modifier.height(14.dp))
        if (!shell.perApp) Text(when (shell.mode) {
            ConnectionMode.BROWSER_PROXY -> "Enter either endpoint in an application's own proxy settings. Everything else stays direct."
            ConnectionMode.SYSTEM_PROXY -> "Applications that follow system proxy settings use the main server."
            else -> "Network traffic is routed by the compatibility tunnel."
        }, color = Muted, fontSize = 14.sp)
        else if (shell.apps.isEmpty()) Text("No apps selected yet. Choose the applications that should use the proxy.", color = Muted, fontSize = 14.sp)
        else {
            val scroll = rememberScrollState()
            val active = shell.traffic.applications.filter { it.active }.map { it.path.lowercase() }.toSet()
            Row(Modifier.fillMaxWidth().wheelAndDragScroll(scroll).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
                shell.apps.forEachIndexed { index, path ->
                    if (index > 0) { Spacer(Modifier.width(22.dp)); Divider(Modifier.height(40.dp), vertical = true); Spacer(Modifier.width(22.dp)) }
                    AppAvatar(fileName(path), path, 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(appName(path), color = White, fontSize = 15.sp, maxLines = 1)
                    if (shell.connected && path.lowercase() in active) { Spacer(Modifier.width(7.dp)); Tooltip("Sending traffic now") { StatusDot(Cyan, 8.dp) } }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Everything else stays on your regular connection.", color = Muted, fontSize = 14.sp)
        }
        Spacer(Modifier.height(10.dp))
        TemporaryRoutesCard(shell)
        ConnectionTools(shell)
        Spacer(Modifier.height(12.dp))
        Divider()
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            SimpleStat(Icons.Rounded.ArrowDownward, "Download", if (shell.connected) formatRate(shell.traffic.downloadBytesPerSecond) else "—")
            Divider(Modifier.height(48.dp), vertical = true)
            SimpleStat(Icons.Rounded.ArrowUpward, "Upload", if (shell.connected) formatRate(shell.traffic.uploadBytesPerSecond) else "—")
            Divider(Modifier.height(48.dp), vertical = true)
            SimpleStat(Icons.Rounded.Schedule, "Session", if (shell.connected) sessionWords(shell.traffic.connectedSeconds) else "—")
        }
        Divider()
        val open = state.details || view.recovering
        Row(Modifier.fillMaxWidth().pressable(RoundedCornerShape(8.dp), label = if (open) "Hide connection details" else "Show connection details") { state.details = !open }
            .padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ChevronRight, null, tint = White, modifier = Modifier.size(22.dp).rotate(if (open) 90f else 0f))
            Spacer(Modifier.width(14.dp))
            Text("Connection details", color = White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            if (shell.connected) {
                if (view.affected > 0 && view.monitoring) {
                    Icon(Icons.Rounded.WarningAmber, null, tint = Amber, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("${view.healthy} healthy  ·  ${view.affected} recovering", color = Amber, fontSize = 14.sp)
                } else if (view.lastChecked != null) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Cyan, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Checked ${formatAgo(view.lastChecked, now)}", color = Muted, fontSize = 14.sp)
                } else Text("Not checked yet", color = Muted, fontSize = 14.sp)
            }
        }
        if (open) SimpleRouteDetails(shell, view, now)
    }
}

private fun sessionWords(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "${seconds / 60} min"
    else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
}

@Composable
private fun RowScope.SimpleStat(icon: ImageVector, label: String, value: String) {
    Row(Modifier.weight(1f).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Violet2, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(18.dp))
        Column {
            Text(label, color = Muted, fontSize = 13.sp)
            Text(value, color = White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun ChangeMainButton(shell: Shell) {
    var open by remember { mutableStateOf(false) }
    Box {
        SecondaryButton("Change server", height = 44.dp, enabled = !shell.busy) { open = true }
        PopupMenu(open, { open = false }, mainServerEntries(shell, open) +
            MenuEntry("Open the server list", Icons.Rounded.ChevronRight, "Tests, quality and more servers") { shell.navigate(Tab.Servers) },
            minWidth = 300.dp)
    }
}

@Composable
private fun SimpleRouteDetails(shell: Shell, view: ConnectionView, now: Long) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (view.rows.isEmpty()) Text("No routes yet. Add a server and choose apps.", color = Muted, fontSize = 13.sp)
        view.rows.forEach { row ->
            val health = view.health(row)
            val affected = shell.connected && view.monitoring && health?.affected() == true
            val shape = RoundedCornerShape(14.dp)
            Column(Modifier.fillMaxWidth().background(if (affected) Amber.copy(.08f) else Surface, shape)
                .border(1.dp, if (affected) Amber.copy(.6f) else Line, shape).padding(16.dp)) {
                BoxWithConstraints {
                    val wide = maxWidth > 640.dp
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ServerAvatar(row.node, 40.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                EllipsisText(serverName(row.node), White, 15.sp, Modifier.weight(1f, fill = false), FontWeight.SemiBold)
                                Spacer(Modifier.width(8.dp)); RoutePill(row.kind)
                            }
                            Text(when {
                                !shell.perApp -> if (shell.mode == ConnectionMode.SYSTEM_PROXY) "Apps that follow system proxy settings" else "Apps you point at the local proxy"
                                row.applications.isEmpty() -> "No selected app"
                                else -> joinNames(row.applications.map(::appName), 4)
                            }, color = Muted, fontSize = 13.sp, maxLines = 1)
                        }
                        if (wide) Text(row.node.endpoint(), color = Muted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.width(180.dp))
                        RouteHealthStatus(if (view.monitoring) health else null, shell.routeChecks[row.id], shell.connected, now, Modifier.width(190.dp))
                    }
                }
                if (affected && health != null) {
                    Spacer(Modifier.height(14.dp))
                    Divider()
                    Spacer(Modifier.height(14.dp))
                    BoxWithConstraints { RecoveryDetails(shell, row, health, Modifier.fillMaxWidth(), stacked = maxWidth < 600.dp) }
                }
            }
        }
        if (shell.connected && view.monitoring && view.rows.size > 1) Note("Each route is checked and recovered on its own; the others keep running.")
        if (shell.plan.advancedEffective() && view.rows.size > 1) Note("Manual servers are never switched automatically; changing the main server replaces only the main route.")
    }
}

