package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.ConnectionMode
import app.perroute.routing.AppRouteMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun openQuickRoute(shell: Shell, raw: String) {
    shell.scope.launch {
        val target = withContext(Dispatchers.IO) { shell.controller.quickRouteTarget(raw) }
        if (target.problem.isNotBlank()) {
            shell.showDialog {
                DialogCard("Route with PerRoute", target.name, shell::closeDialog, width = 460.dp, actions = {
                    PrimaryButton("OK") { shell.closeDialog() }
                }) { DialogText(target.problem) }
            }
            return@launch
        }
        quickRouteDialog(shell, target)
    }
}

private fun quickRouteDialog(shell: Shell, target: QuickRouteTarget) {
    val mode = mutableStateOf<AppRouteMode?>(if (target.selected) target.mode else AppRouteMode.MAIN)
    val server = mutableStateOf(target.nodeKey.ifBlank { shell.nodes.firstOrNull { it.key() != shell.selectedNodeKey }?.key() ?: shell.selectedNodeKey })
    val title = appName(target.path)
    shell.showDialog {
        val locked = shell.locked
        val choice = mode.value
        val canSave = !shell.busy && (choice != AppRouteMode.MANUAL || shell.nodes.any { it.key() == server.value }) &&
            (choice != null || target.selected) && shell.nodes.isNotEmpty()
        DialogCard("Route $title", target.path, shell::closeDialog, width = 620.dp, actions = {
            SecondaryButton("Cancel") { shell.closeDialog() }
            PrimaryButton(if (locked) "Save and reconnect" else "Save", enabled = canSave) {
                shell.closeDialog()
                val (m, s) = choice to server.value
                val draftWasDirty = shell.draft.dirty(shell)
                shell.run(done = { result ->
                    shell.refresh()
                    if (result.ok && !draftWasDirty) shell.draft.load(shell)
                }) { shell.controller.quickRoute(target.path, m, s, reconnect = locked) }
            }
        }) {
            if (target.selected) {
                Text("$title is routed now: ${routeWord(target.mode)}.", color = Muted, fontSize = 12.5.sp)
                Spacer(Modifier.height(12.dp))
            }
            FieldLabel("Connection")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip("Main server", choice == AppRouteMode.MAIN) { mode.value = AppRouteMode.MAIN }
                FilterChip("A server I choose", choice == AppRouteMode.MANUAL, enabled = shell.nodes.isNotEmpty()) { mode.value = AppRouteMode.MANUAL }
                FilterChip("Best server (Auto)", choice == AppRouteMode.AUTO, enabled = shell.nodes.isNotEmpty()) { mode.value = AppRouteMode.AUTO }
                if (target.selected) FilterChip("Do not route", choice == null) { mode.value = null }
            }
            Spacer(Modifier.height(10.dp))
            when (choice) {
                AppRouteMode.MAIN -> DialogText("It uses the main server${shell.mainNode?.let { ", " + serverName(it) } ?: ""}, like your other routed apps.", Muted)
                AppRouteMode.MANUAL -> ServerChooser(shell, server.value, "Server for $title") { server.value = it.key() }
                AppRouteMode.AUTO -> DialogText("PerRoute keeps it on the best measured server and moves it when that server stops working. It never falls back to your direct connection.", Muted)
                null -> DialogText("It goes back to your regular connection. Its settings, such as a bandwidth limit, are kept for when you route it again.", Muted)
            }
            if (shell.nodes.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Add a server first, in Servers.", color = Amber, fontSize = 12.5.sp)
            }
            if ((choice == AppRouteMode.MANUAL || choice == AppRouteMode.AUTO) && !shell.advanced) {
                Spacer(Modifier.height(10.dp))
                DialogText("This turns on Advanced routing, so apps can have servers of their own. Other apps keep the main server.", Muted)
            }
            if (choice != null && shell.mode != ConnectionMode.NATIVE_PER_APP) {
                Spacer(Modifier.height(10.dp))
                Text("Apps are routed in Smart per-app mode. You are using ${modeTitle(shell.mode)}; switch in Settings > Connection.", color = Amber, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
            if (locked) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Connected.", color = White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Text("Saving reconnects, so routed apps pause for a moment.", color = Muted, fontSize = 12.5.sp)
                }
            }
        }
    }
}

private fun routeWord(mode: AppRouteMode): String = when (mode) {
    AppRouteMode.MAIN -> "main server"
    AppRouteMode.MANUAL -> "a server of its own"
    AppRouteMode.AUTO -> "best server (Auto)"
}
