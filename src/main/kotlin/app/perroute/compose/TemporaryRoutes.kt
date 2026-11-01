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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Timer
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.AppRoutes
import app.perroute.model.ConnectionMode
import app.perroute.model.ProxyNode

internal val TemporaryDurations = listOf(15, 30, 60, 120, 240)

internal fun durationLabel(minutes: Int): String = if (minutes < 60) "$minutes min" else "${minutes / 60} h"

@Composable
internal fun ServerChooser(shell: Shell, selectedKey: String?, label: String, modifier: Modifier = Modifier.fillMaxWidth(),
                           exclude: String? = null, enabled: Boolean = true, onChoose: (ProxyNode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val selected = shell.nodes.firstOrNull { it.key() == selectedKey }
    Box(modifier) {
        val shape = ControlShape
        Row(
            Modifier.fillMaxWidth().height(40.dp).alpha(if (enabled) 1f else .6f)
                .pressable(shape, enabled, label = "$label: ${selected?.let(::serverName) ?: "not chosen"}") { filter = ""; open = true }
                .background(Field).border(1.dp, Line, shape).padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected != null) { ServerAvatar(selected, 22.dp); Spacer(Modifier.width(8.dp)) }
            EllipsisText(selected?.let(::serverName) ?: "Choose a server", if (selected == null) Muted else White, 12.sp, Modifier.weight(1f), FontWeight.Medium)
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = White.copy(.7f), modifier = Modifier.size(18.dp))
        }
        val favorites = shell.nodes.filter { shell.controller.isFavorite(it.key()) }.mapTo(hashSetOf()) { it.key() }
        val entries = shell.nodes.filter { it.key() != exclude }
            .filter { filter.isBlank() || serverName(it).contains(filter, ignoreCase = true) || it.address().contains(filter, ignoreCase = true) }
            .sortedWith(compareByDescending<ProxyNode> { it.key() in favorites }.thenBy { serverName(it).lowercase() })
            .take(200)
            .map { node ->
                MenuEntry(serverName(node), if (node.key() in favorites) Icons.Rounded.Star else null,
                    listOfNotNull(protocolLabel(node), if (node.key() == shell.selectedNodeKey) "main server" else null).joinToString("  ·  "),
                    selected = node.key() == selectedKey) { onChoose(node) }
            }
        PopupMenu(open, { open = false }, entries.ifEmpty { listOf(MenuEntry("No server matches", enabled = false) {}) }, minWidth = 320.dp,
            header = if (shell.nodes.size > 8) ({
                Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) { TextInput(filter, { filter = it }, "Filter servers", icon = Icons.Rounded.Search) }
            }) else null)
    }
}

internal fun showTemporaryDialog(shell: Shell, app: String? = null, server: String? = null) {
    val chosenApp = mutableStateOf(app ?: shell.apps.firstOrNull())
    val chosenServer = mutableStateOf(server)
    val minutes = mutableStateOf(30)
    shell.showDialog {
        val perApp = shell.mode == ConnectionMode.NATIVE_PER_APP
        DialogCard("Use a server for a while", "The app goes back to its usual route by itself when the time is up. Its saved route and your rules stay as they are.",
            shell::closeDialog, width = 520.dp, actions = {
                SecondaryButton("Cancel") { shell.closeDialog() }
                PrimaryButton("Start", icon = Icons.Rounded.Timer, enabled = perApp && chosenApp.value != null && chosenServer.value != null && !shell.busy) {
                    val path = chosenApp.value ?: return@PrimaryButton
                    val key = chosenServer.value ?: return@PrimaryButton
                    shell.closeDialog()
                    shell.run { shell.controller.setTemporaryRoute(path, key, minutes.value) }
                }
            }) {
            if (!perApp) {
                InfoBanner("Temporary servers need Smart per-app mode, where every app has a route of its own. Change the mode in Settings > Connection.", color = Amber)
                Spacer(Modifier.height(12.dp))
            }
            FieldLabel("App")
            if (shell.apps.isEmpty()) Text("Select apps in Apps first.", color = Muted, fontSize = 12.sp)
            else MenuButton(chosenApp.value?.let(::appName) ?: "Choose an app", shell.apps.map { path ->
                MenuEntry(appName(path), selected = chosenApp.value.equals(path, ignoreCase = true)) { chosenApp.value = path }
            }, Modifier.fillMaxWidth(), accessibleLabel = "App for the temporary server", menuWidth = 300.dp)
            Spacer(Modifier.height(12.dp))
            FieldLabel("Server")
            ServerChooser(shell, chosenServer.value, "Temporary server") { chosenServer.value = it.key() }
            Spacer(Modifier.height(12.dp))
            FieldLabel("For")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TemporaryDurations.forEach { value -> FilterChip(durationLabel(value), minutes.value == value) { minutes.value = value } }
            }
            Spacer(Modifier.height(12.dp))
            val current = chosenApp.value?.let { shell.controller.temporaryRoute(it) }
            if (current != null) DialogText("${appName(current.application)} already uses ${shell.nodes.firstOrNull { it.key() == current.nodeKey }?.let(::serverName) ?: "a server"} for another ${formatRemaining(current.remainingMillis())}. Starting replaces it.", Amber)
            DialogText(if (shell.connected) "Only this app's route changes. The other routes keep running and keep their connections when the core allows it."
                else "It starts counting now and applies as soon as you connect.", Muted)
        }
    }
}

@Composable
internal fun ConnectionTools(shell: Shell) {
    if (shell.nodes.size < 2 || shell.mainNode == null) return
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        if (shell.perApp && shell.apps.isNotEmpty()) LinkAction("Use a server for a while", arrow = false, fontSize = 12.5.sp, enabled = !shell.busy) { showTemporaryDialog(shell) }
        LinkAction(if (shell.connected) "Compare a route with another server" else "Compare the main server with another", arrow = false, fontSize = 12.5.sp, enabled = !shell.busy) {
            showShadowDialog(shell)
        }
    }
}

@Composable
internal fun TemporaryAppAction(shell: Shell, path: String) {
    val active = shell.controller.temporaryRoute(path)
    TemporaryRoutesCard(shell, only = path)
    if (active == null) {
        Text("Try another server for this app without changing its saved route. It comes back by itself.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(8.dp))
    }
    SecondaryButton(if (active == null) "Use another server for a while…" else "Change the temporary server…", Modifier.fillMaxWidth(), icon = Icons.Rounded.Timer,
        enabled = shell.perApp && !shell.busy) { showTemporaryDialog(shell, app = path) }
    if (!shell.perApp) { Spacer(Modifier.height(6.dp)); Text("Needs Smart per-app mode.", color = Muted, fontSize = 11.5.sp) }
}

@Composable
internal fun TemporaryRoutesCard(shell: Shell, modifier: Modifier = Modifier.fillMaxWidth(), only: String? = null) {
    val now by rememberNow()
    val routes = shell.controller.temporaryRoutes(now).filter { only == null || AppRoutes.normalize(it.application) == AppRoutes.normalize(only) }
    if (routes.isEmpty()) return
    Column(modifier) {
        routes.forEach { route ->
            val node = shell.nodes.firstOrNull { it.key() == route.nodeKey }
            val shape = RowShape
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Amber.copy(if (IsDark) .10f else .08f), shape).border(1.dp, Amber.copy(.35f), shape)
                .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Timer, null, tint = Amber, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    EllipsisText("${appName(route.application)} on ${node?.let(::serverName) ?: "a removed server"}", White, 12.5.sp, fontWeight = FontWeight.SemiBold)
                    Text("${formatRemaining(route.remainingMillis(now))} left, then back to its usual route", color = Muted, fontSize = 11.5.sp)
                }
                LinkAction("End now", arrow = false, enabled = !shell.busy) { shell.run { shell.controller.cancelTemporaryRoute(route.application) } }
            }
        }
    }
}

@Composable
internal fun QuickTemporaryPanel(apps: List<String>, nodes: List<ProxyNode>, mainKey: String?, working: Boolean,
                                 onStart: (String, String, Int) -> Unit, onClose: () -> Unit) {
    var app by remember { mutableStateOf(apps.firstOrNull()) }
    var minutes by remember { mutableStateOf(30) }
    var server by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use a server for a while", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            LinkAction("Back", arrow = false, onClick = onClose)
        }
        Text("The app returns to its usual route when the time is up.", color = Muted, fontSize = 11.5.sp)
        Spacer(Modifier.height(10.dp))
        if (apps.isEmpty()) { Text("Select apps in PerRoute first.", color = Muted, fontSize = 12.sp); return@Column }
        MenuButton(app?.let(::appName) ?: "Choose an app", apps.map { path -> MenuEntry(appName(path), selected = app.equals(path, true)) { app = path } },
            Modifier.fillMaxWidth(), accessibleLabel = "App", menuWidth = 300.dp, height = 38.dp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.wrapContentWidth()) {
            TemporaryDurations.take(4).forEach { value -> FilterChip(durationLabel(value), minutes == value) { minutes = value } }
        }
        Spacer(Modifier.height(8.dp))
        Text("Server", color = Muted, fontSize = 11.5.sp)
        Column(Modifier.fillMaxWidth()) {
            nodes.filter { it.key() != mainKey }.take(6).forEach { node ->
                Row(Modifier.fillMaxWidth().pressable(RowShape, !working, label = "Use ${serverName(node)}") { server = node.key() }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioMark(server == node.key(), 18.dp)
                    Spacer(Modifier.width(10.dp))
                    ServerAvatar(node, 24.dp)
                    Spacer(Modifier.width(8.dp))
                    EllipsisText(serverName(node), White, 12.5.sp, Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Start for ${durationLabel(minutes)}", Modifier.fillMaxWidth(), icon = Icons.Rounded.Timer,
            enabled = app != null && server != null && !working, height = 42.dp) { onStart(app!!, server!!, minutes) }
    }
}
