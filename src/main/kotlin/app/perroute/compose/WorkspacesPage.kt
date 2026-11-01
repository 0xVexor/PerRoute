package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Web
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.workspace.WorkspaceBrowsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class WorkspacesModel(private val shell: Shell) {
    private val controller = shell.controller
    var views by mutableStateOf(controller.workspaces())
    var browsers by mutableStateOf<List<WorkspaceBrowsers.Browser>>(emptyList())
    var selected by mutableStateOf<String?>(null)

    fun refresh() { views = controller.workspaces() }

    suspend fun loadBrowsers() { browsers = withContext(Dispatchers.IO) { controller.workspaceBrowsers() } }

    fun open(id: String) = shell.run(done = { refresh() }) { controller.openWorkspace(id) }
    fun stop(id: String) = shell.run(done = { refresh() }) { controller.stopWorkspace(id) }

    fun remove(view: WorkspaceView) {
        val deleteData = mutableStateOf(false)
        shell.showDialog {
            DialogCard("Remove ${view.workspace.name}?", "The workspace goes away. Its window, if open, loses its connection.", shell::closeDialog, width = 480.dp, actions = {
                SecondaryButton("Cancel") { shell.closeDialog() }
                PrimaryButton(if (deleteData.value) "Remove and delete data" else "Remove", color = if (deleteData.value) Red else null) {
                    shell.closeDialog()
                    val delete = deleteData.value
                    shell.run(done = { refresh(); if (selected == view.workspace.id) selected = null }) { controller.removeWorkspace(view.workspace.id, delete) }
                }
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CheckMark(deleteData.value)
                    Spacer(Modifier.width(10.dp))
                    LinkAction("Also delete its browsing data (cookies, sign-ins, history)", arrow = false, color = White) { deleteData.value = !deleteData.value }
                }
                Spacer(Modifier.height(6.dp))
                DialogText(if (deleteData.value) "This cannot be undone." else "The browsing data stays in PerRoute's data folder, under workspaces.", Muted)
            }
        }
    }

    fun edit(view: WorkspaceView?) {
        val name = mutableStateOf(view?.workspace?.name ?: "")
        val browser = mutableStateOf(view?.workspace?.browserPath ?: browsers.firstOrNull()?.path.orEmpty())
        val server = mutableStateOf(view?.workspace?.nodeKey ?: "")
        shell.showDialog {
            DialogCard(if (view == null) "New workspace" else "Edit ${view.workspace.name}",
                "A browser profile of its own that always takes one route, whatever the rest of the computer does.", shell::closeDialog, width = 520.dp, actions = {
                    SecondaryButton("Cancel") { shell.closeDialog() }
                    PrimaryButton(if (view == null) "Create" else "Save", enabled = name.value.isNotBlank() && browser.value.isNotBlank() && !shell.busy) {
                        shell.closeDialog()
                        val (n, b, s) = Triple(name.value, browser.value, server.value)
                        shell.run(done = { refresh() }) { if (view == null) controller.createWorkspace(n, b, s) else controller.updateWorkspace(view.workspace.id, n, b, s) }
                    }
                }) {
                FieldLabel("Name")
                TextInput(name.value, { name.value = it.take(40) }, "Work, Personal, Shopping…")
                Spacer(Modifier.height(12.dp))
                FieldLabel("Browser")
                if (browsers.isEmpty()) Text("No Chromium browser (Chrome, Edge, Brave, Vivaldi) was found.", color = Amber, fontSize = 12.sp)
                else MenuButton(browsers.firstOrNull { it.path.equals(browser.value, true) }?.name ?: WorkspaceBrowsers.displayName(browser.value).ifBlank { "Choose a browser" },
                    browsers.map { item -> MenuEntry(item.name, detail = item.path, selected = item.path.equals(browser.value, true)) { browser.value = item.path } },
                    Modifier.fillMaxWidth(), accessibleLabel = "Browser", menuWidth = 420.dp)
                Spacer(Modifier.height(12.dp))
                FieldLabel("Route")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip("Main server", server.value.isBlank()) { server.value = "" }
                    FilterChip("A server of its own", server.value.isNotBlank()) { if (server.value.isBlank()) server.value = shell.nodes.firstOrNull { it.key() != shell.selectedNodeKey }?.key() ?: shell.selectedNodeKey }
                }
                if (server.value.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    ServerChooser(shell, server.value, "Workspace server") { server.value = it.key() }
                }
                Spacer(Modifier.height(12.dp))
                DialogText("It opens in a separate window with its own cookies and sign-ins, so you sign in to your accounts there once. Name lookups, QUIC and WebRTC are kept inside its route.", Muted)
            }
        }
    }
}

internal fun workspacesModel(shell: Shell): WorkspacesModel = shell.screen("${Tab.Workspaces.name}:model") { WorkspacesModel(shell) }

@Composable
internal fun WorkspacesPage(shell: Shell) {
    if (!shell.controller.workspacesSupported()) {
        UnavailableFeature("Browser workspaces", "Isolated browser launch and profile routing have not been ported.")
        return
    }
    val model = workspacesModel(shell)
    LaunchedEffect(Unit) {
        model.loadBrowsers()
        while (isActive) { model.refresh(); delay(2_000) }
    }
    val views = model.views
    if (model.selected == null || views.none { it.workspace.id == model.selected }) model.selected = views.firstOrNull()?.workspace?.id
    FeaturePage(listOf("Tools", "Workspaces"), "Workspaces", "A browser of its own for work, personal use or anything else, each on its own route.",
        actions = { PrimaryButton("New workspace", icon = Icons.Rounded.Add, enabled = !shell.busy) { shell.scope.launch { model.loadBrowsers(); model.edit(null) } } },
        panel = if (Tok.isNew) ({ WorkspacePanel(shell, model) }) else null) {
        if (views.isEmpty()) {
            EmptyState(Icons.Rounded.Web, "No workspaces yet", "A workspace is a separate browser profile, for example Work on one server and Personal on another, " +
                "while every other app keeps its own route. It works whether or not PerRoute is connected.", Modifier.fillMaxWidth()) {
                PrimaryButton("New workspace", icon = Icons.Rounded.Add) { shell.scope.launch { model.loadBrowsers(); model.edit(null) } }
            }
            return@FeaturePage
        }
        views.forEach { view ->
            FeatureRow(model.selected == view.workspace.id && Tok.isNew, onClick = { model.selected = view.workspace.id }, label = view.workspace.name) {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                    AppAvatar(WorkspaceBrowsers.displayName(view.workspace.browserPath), view.workspace.browserPath, 34.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EllipsisText(view.workspace.name, White, 14.sp, Modifier.weight(1f, fill = false), FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        if (view.running) Badge("Open", Cyan) else if (view.problem.isNotBlank()) Badge("Needs attention", Amber)
                    }
                    Text("${WorkspaceBrowsers.displayName(view.workspace.browserPath)}  ·  " +
                        (if (view.workspace.followsMain()) "Main server" else "Own server") + ": " + (view.node?.let(::serverName) ?: "none"),
                        color = Muted, fontSize = 12.sp, maxLines = 1)
                    if (!Tok.isNew && view.problem.isNotBlank()) Text(view.problem, color = Amber, fontSize = 11.5.sp)
                }
                Spacer(Modifier.width(10.dp))
                if (view.running) SecondaryButton("Stop", icon = Icons.Rounded.Stop, enabled = !shell.busy, height = 34.dp) { model.stop(view.workspace.id) }
                Spacer(Modifier.width(8.dp))
                PrimaryButton(if (view.running) "Open window" else "Open", icon = Icons.Rounded.OpenInBrowser, enabled = !shell.busy && view.problem.isBlank() || view.running, height = 34.dp) { model.open(view.workspace.id) }
                if (!Tok.isNew) {
                    Spacer(Modifier.width(4.dp))
                    MenuIcon(Icons.Rounded.Edit, "More for ${view.workspace.name}", listOf(
                        MenuEntry("Edit") { shell.scope.launch { model.loadBrowsers(); model.edit(view) } },
                        MenuEntry("Remove", danger = true) { model.remove(view) }))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Note("Each workspace has its own loopback port and core. If its route stops, its window has no connection instead of a direct one.")
    }
}

@Composable
private fun ColumnScope.WorkspacePanel(shell: Shell, model: WorkspacesModel) {
    val view = model.views.firstOrNull { it.workspace.id == model.selected }
    if (view == null) { NewPanelEmpty("Workspace", "No workspace selected", "Create a workspace to give one browser profile a route of its own."); return }
    PanelHeading("Workspace", view.workspace.name, WorkspaceBrowsers.displayName(view.workspace.browserPath))
    PanelSection {
        FactLine("Status", if (view.running) "Open since ${formatTimeOfDay(view.runningSince)}" else "Closed", if (view.running) Cyan else White)
        FactLine("Route", if (view.workspace.followsMain()) "Main server" else "Its own server")
        FactLine("Server", view.node?.let(::serverName) ?: "Removed")
        FactLine("Local port", view.port.toString())
        if (view.problem.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(view.problem, color = Amber, fontSize = 12.5.sp, lineHeight = 17.sp) }
    }
    PanelSection("Protection") {
        Text("Separate profile folder, so its cookies and sign-ins never mix with your other browsing.", color = White.copy(.8f), fontSize = 12.5.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text("Names are looked up on the far side of the route; QUIC is off and WebRTC may only use the route.", color = White.copy(.8f), fontSize = 12.5.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text("The window cannot fall back to your direct connection.", color = White.copy(.8f), fontSize = 12.5.sp, lineHeight = 18.sp)
    }
    Spacer(Modifier.height(18.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton("Edit", Modifier.weight(1f), icon = Icons.Rounded.Edit, enabled = !shell.busy) { shell.scope.launch { model.loadBrowsers(); model.edit(view) } }
        SecondaryButton("Remove", Modifier.weight(1f), tint = Red, enabled = !shell.busy) { model.remove(view) }
    }
}
