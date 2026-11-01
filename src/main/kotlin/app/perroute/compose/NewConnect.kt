package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.ConnectionMode
import app.perroute.routing.RouteHealthTracker

private class ConnectView { var details by mutableStateOf(false) }

@Composable
internal fun NewConnectPage(shell: Shell) {
    val view = ConnectionView(shell)
    val now by rememberNow()
    val state = shell.screen("${Tab.Connection.name}:view") { ConnectView() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columnWidth = if (maxWidth + NewRailWidth < 980.dp) 250.dp else ((maxWidth + NewRailWidth) * .265f).coerceIn(280.dp, 420.dp)
        Row(Modifier.fillMaxSize()) {
            CharcoalColors { NewConnectColumn(shell, view, Modifier.width(columnWidth).fillMaxHeight()) }
            NewRoutesArea(shell, view, state, now, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun NewConnectColumn(shell: Shell, view: ConnectionView, modifier: Modifier) {
    BoxWithConstraints(modifier.background(Ink)) {
        val scroll = rememberScrollState()
        Column(
            Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(scroll).padding(horizontal = 28.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                val (statusLabel, statusColor) = when {
                    view.connecting -> "CONNECTING" to Amber
                    view.disconnecting -> "DISCONNECTING" to Amber
                    shell.connected -> "CONNECTED" to Cyan
                    view.incomplete -> "SETUP NEEDED" to Amber
                    else -> "NOT CONNECTED" to Muted
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(statusColor, 10.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(statusLabel, color = statusColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
                }
                Spacer(Modifier.height(26.dp))
                Text("Your apps.\nYour routes.", color = White, fontSize = 36.sp, lineHeight = 39.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.6).sp)
                Spacer(Modifier.height(26.dp))
                Text(modeLabel(shell.mode), color = White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(modeDetail(shell.mode), color = White.copy(.82f), fontSize = 13.5.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(22.dp))
                val busy = view.connecting || view.disconnecting
                PrimaryButton(view.buttonLabel, Modifier.fillMaxWidth(), icon = Icons.Rounded.PowerSettingsNew, enabled = view.buttonEnabled,
                    busy = busy, height = 52.dp, onClick = shell::toggleConnection)
                if (view.main == null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Add a server to connect.", color = Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(24.dp))
                Divider()
                Spacer(Modifier.height(20.dp))
                NewBigStat("Download", if (shell.connected) formatMbps(shell.traffic.downloadBytesPerSecond) else "—")
                Spacer(Modifier.height(16.dp))
                NewBigStat("Upload", if (shell.connected) formatMbps(shell.traffic.uploadBytesPerSecond) else "—")
                Spacer(Modifier.height(16.dp))
                NewBigStat("Session", if (shell.connected) formatClock(shell.traffic.connectedSeconds) else "—")
                if (view.localProxy) {
                    Spacer(Modifier.height(18.dp))
                    Text("Local proxy endpoints", color = Muted, fontSize = 12.5.sp)
                    Spacer(Modifier.height(8.dp))
                    ProxyEndpoints(shell, Modifier.fillMaxWidth())
                }
            }
            Column {
                Spacer(Modifier.height(22.dp))
                Divider()
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServerAvatar(view.main, 46.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        EllipsisText(view.main?.let(::serverName) ?: "No server yet", White, 14.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (view.main == null) "Add one in Servers" else "Main server", color = White.copy(.8f), fontSize = 12.5.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    LinkAction(if (view.main == null) "Add" else "Change", fontSize = 13.sp) {
                        if (view.main == null) shell.addServersRequested = true
                        shell.navigate(Tab.Servers)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewBigStat(label: String, value: String) {
    Text(label, color = White.copy(.82f), fontSize = 13.5.sp)
    Spacer(Modifier.height(2.dp))
    Text(value, color = White, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun NewRoutesArea(shell: Shell, view: ConnectionView, state: ConnectView, now: Long, modifier: Modifier) {
    BoxWithConstraints(modifier) {
    val narrow = maxWidth < 560.dp
    Column(Modifier.fillMaxSize().padding(start = if (narrow) 22.dp else 34.dp, end = if (narrow) 20.dp else 30.dp, top = if (narrow) 22.dp else 30.dp, bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                NewEyebrow("Live routing")
                Spacer(Modifier.height(10.dp))
                Text("Every route, at a glance.", color = White, fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-.6).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                val appsText = if (shell.perApp) plural(shell.apps.size, "application") else modeLabel(shell.mode)
                Text("$appsText  ·  ${plural(view.routeCount, "route")}", color = Muted, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.width(12.dp))
            if (shell.perApp) LinkAction("Manage apps", fontSize = 13.5.sp, modifier = Modifier.padding(top = 30.dp)) { shell.navigate(Tab.Apps) }
        }
        Spacer(Modifier.height(18.dp))
        connectionNotice(view)?.let { notice ->
            InfoBanner(notice.text, Modifier.fillMaxWidth(), if (notice.warning) Amber else Muted, Icons.Rounded.WarningAmber) {
                if (notice.action != null && notice.onAction != null) SecondaryButton(notice.action, height = 34.dp, onClick = notice.onAction)
            }
            Spacer(Modifier.height(12.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (view.rows.isEmpty() && view.main == null) {
                EmptyState(Icons.Rounded.Info, "No routes yet", "Add a server, then choose the apps that should use it.", Modifier.fillMaxWidth()) {
                    PrimaryButton("Add servers") { shell.addServersRequested = true; shell.navigate(Tab.Servers) }
                }
            }
            TemporaryRoutesCard(shell)
            ConnectionTools(shell)
            view.rows.forEachIndexed { index, row ->
                val health = view.health(row)
                val affected = shell.connected && view.monitoring && health?.affected() == true
                if (affected && health != null) {
                    val color = if (health.state() == RouteHealthTracker.State.FAILED) Red else Amber
                    Spacer(Modifier.height(6.dp))
                    Column(Modifier.fillMaxWidth().background(color.copy(if (IsDark) .10f else .07f), RoundedCornerShape(10.dp))
                        .border(1.5.dp, color.copy(.7f), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                        RouteGraphRow(shell, row, health, now, state.details)
                        Spacer(Modifier.height(12.dp))
                        Divider()
                        Spacer(Modifier.height(14.dp))
                        BoxWithConstraints { RecoveryDetails(shell, row, health, Modifier.fillMaxWidth(), stacked = maxWidth < 560.dp) }
                    }
                    Spacer(Modifier.height(6.dp))
                } else {
                    Box(Modifier.padding(vertical = 10.dp)) { RouteGraphRow(shell, row, health, now, state.details) }
                    if (index < view.rows.lastIndex) Divider()
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Divider()
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { RouteSummary(shell, view, now, showLast = !narrow) }
            Row(Modifier.pressable(RoundedCornerShape(4.dp), label = if (state.details) "Hide route details" else "Show route details") { state.details = !state.details }
                .padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (narrow) "Details" else "Route details", color = White, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(6.dp))
                Icon(if (state.details) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = White, modifier = Modifier.size(20.dp))
            }
        }
        Divider()
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Note(if (shell.perApp) "Other applications stay on your regular connection." else modeDetail(shell.mode), Modifier.weight(1f))
            Text(Credit, color = Muted, fontSize = 12.sp)
        }
    }
    }
}

@Composable
private fun RouteSummary(shell: Shell, view: ConnectionView, now: Long, showLast: Boolean) {
    val (icon, color, text) = when {
        !shell.connected -> Triple(Icons.Rounded.RadioButtonUnchecked, Muted, if (view.rows.isEmpty()) "No routes" else "Not connected")
        view.monitoring && view.affected > 0 -> Triple(Icons.Rounded.WarningAmber, Amber, "${view.healthy} healthy  ·  ${view.affected} recovering")
        view.monitoring -> Triple(Icons.Rounded.CheckCircle, Cyan, "${plural(view.healthy, "route")} healthy")
        else -> {
            val passed = view.rows.count { shell.routeChecks[it.id]?.state == RouteCheckState.PASSED }
            Triple(Icons.Rounded.CheckCircle, if (passed == view.rows.size && passed > 0) Cyan else Muted, "${plural(passed, "route")} responding")
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).background(color.copy(.15f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        if (shell.connected && showLast) {
            Spacer(Modifier.width(16.dp))
            Divider(Modifier.height(22.dp), vertical = true)
            Spacer(Modifier.width(16.dp))
            Text(view.lastChecked?.let { "Last checked ${formatAgo(it, now)}" } ?: "Not checked yet", color = Muted, fontSize = 13.sp)
        }
    }
}

private val AppCardHeight = 54.dp
private val AppCardGap = 10.dp
private const val MaxAppsPerRoute = 3

@Composable
private fun RouteGraphRow(shell: Shell, row: RouteRow, health: RouteHealthTracker.Snapshot?, now: Long, details: Boolean) {
    val apps = row.applications
    val shown = apps.take(MaxAppsPerRoute)
    val extra = apps.size - shown.size
    val cards = if (shell.perApp) shown.size.coerceAtLeast(1) + (if (extra > 0) 1 else 0) else 1
    val graphHeight = AppCardHeight * cards + AppCardGap * (cards - 1)
    val lineColor = White.copy(.75f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 640.dp
        val narrow = maxWidth < 520.dp
        val appWidth = if (wide) 190.dp else if (narrow) 132.dp else 150.dp
        val serverWidth = if (wide) 300.dp else if (narrow) 190.dp else 220.dp
        Column {
            val rowHeight = if (narrow && shell.connected) maxOf(graphHeight, 88.dp) else graphHeight
            Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(appWidth), verticalArrangement = Arrangement.spacedBy(AppCardGap)) {
                    when {
                        !shell.perApp -> ModeCard(shell.mode)
                        shown.isEmpty() -> PlaceholderCard("No selected app")
                        else -> {
                            val active = shell.traffic.applications.filter { it.active }.map { it.path.lowercase() }.toSet()
                            shown.forEach { path -> AppCard(path, shell.connected && path.lowercase() in active) }
                            if (extra > 0) Tooltip(apps.drop(MaxAppsPerRoute).joinToString("\n") { appName(it) }) { PlaceholderCard("+$extra more") }
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val stroke = 1.4.dp.toPx()
                        val card = AppCardHeight.toPx()
                        val gap = AppCardGap.toPx()
                        val mid = size.height / 2
                        val top = (size.height - graphHeight.toPx()) / 2
                        val mergeX = size.width * .26f
                        val pillLeft = size.width * .5f - 40.dp.toPx()
                        val pillRight = size.width * .5f + 40.dp.toPx()
                        for (i in 0 until cards) {
                            val y = top + card * i + gap * i + card / 2
                            if (cards == 1) drawLine(lineColor, Offset(0f, y), Offset(pillLeft, mid), stroke)
                            else {
                                val path = Path().apply {
                                    moveTo(0f, y)
                                    cubicTo(mergeX * .7f, y, mergeX * .45f, mid, mergeX, mid)
                                }
                                drawPath(path, lineColor, style = Stroke(stroke))
                            }
                        }
                        if (cards > 1) drawLine(lineColor, Offset(mergeX, mid), Offset(pillLeft, mid), stroke)
                        val end = size.width - 6.dp.toPx()
                        drawLine(lineColor, Offset(pillRight, mid), Offset(end, mid), stroke)
                        val head = Path().apply {
                            moveTo(end + 4.dp.toPx(), mid)
                            lineTo(end - 5.dp.toPx(), mid - 5.dp.toPx())
                            moveTo(end + 4.dp.toPx(), mid)
                            lineTo(end - 5.dp.toPx(), mid + 5.dp.toPx())
                        }
                        drawPath(head, lineColor, style = Stroke(stroke))
                    }
                    RoutePill(row.kind)
                }
                Row(Modifier.width(serverWidth).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ServerAvatar(row.node, if (narrow) 42.dp else 54.dp)
                    Spacer(Modifier.width(if (narrow) 10.dp else 14.dp))
                    Column(Modifier.weight(1f)) {
                        EllipsisText(serverName(row.node), White, 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(protocolLabel(row.node) + " server", color = Muted, fontSize = 12.5.sp, maxLines = 1)
                        Spacer(Modifier.height(5.dp))
                        val score = shell.controller.qualityScore(row.node.key())
                        val healthView: @Composable () -> Unit = { RouteHealthStatus(if (shell.controller.routeMonitoring()) health else null, shell.routeChecks[row.id], true, now, compact = true) }
                        if (narrow) {
                            QualityPill(score, compact = true)
                            if (shell.connected) { Spacer(Modifier.height(3.dp)); healthView() }
                        } else Row(verticalAlignment = Alignment.CenterVertically) {
                            QualityPill(score, compact = true)
                            if (shell.connected) {
                                Spacer(Modifier.width(10.dp))
                                Divider(Modifier.height(16.dp), vertical = true)
                                Spacer(Modifier.width(10.dp))
                                healthView()
                            }
                        }
                    }
                }
            }
            if (details) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().padding(start = appWidth), verticalAlignment = Alignment.CenterVertically) {
                    RouteCheckStatus(shell.routeChecks[row.id], shell.connected, now, Modifier.weight(1f))
                    Text(row.node.endpoint(), color = Muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AppCard(path: String, active: Boolean) {
    Row(Modifier.fillMaxWidth().height(AppCardHeight), verticalAlignment = Alignment.CenterVertically) {
        AppAvatar(fileName(path), path, 50.dp, framed = true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EllipsisText(appName(path), White, 14.5.sp, Modifier.weight(1f, fill = false), FontWeight.SemiBold)
                if (active) { Spacer(Modifier.width(6.dp)); Tooltip("Sending traffic now") { StatusDot(Cyan, 7.dp) } }
            }
            EllipsisText(fileName(path), Muted, 12.sp)
        }
    }
}

@Composable
private fun ModeCard(mode: ConnectionMode) {
    Row(Modifier.fillMaxWidth().height(AppCardHeight), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(50.dp).background(Surface, RoundedCornerShape(13.dp)).border(1.dp, Line, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.DesktopWindows, null, tint = White.copy(.8f), modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(if (mode == ConnectionMode.SYSTEM_PROXY) "Apps that follow system proxy settings" else "Apps you point at the local proxy",
            color = White, fontSize = 12.5.sp, lineHeight = 16.sp, maxLines = 3)
    }
}

@Composable
private fun PlaceholderCard(text: String) {
    Row(Modifier.fillMaxWidth().height(AppCardHeight), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(50.dp).border(1.dp, Line, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Text("•••", color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = Muted, fontSize = 13.sp)
    }
}

