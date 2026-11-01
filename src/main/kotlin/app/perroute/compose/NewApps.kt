package app.perroute.compose

import app.perroute.model.ApplicationIdentity

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.routing.AppRouteMode

@Composable
internal fun NewAppsPage(shell: Shell) {
    val model = appsModel(shell)
    LaunchedEffect(Unit) { model.ensureLoaded() }
    val draft = shell.draft
    val locked = shell.locked
    val dirty = draft.dirty(shell)
    NewSplitPage(
        panel = {
            val focused = draft.focused
            if (focused != null) NewAppPanel(shell, focused) else NewSelectionPanel(shell, model)
        },
        mainBar = {
            Text("${draft.selected.size} selected" + if (draft.selected.isNotEmpty()) "  ·  ${plural(model.routeCount(), "route")}" else "",
                color = White, fontSize = 13.sp)
            if (locked) Text("  ·  Routes can be edited while disconnected.", color = Amber, fontSize = 13.sp)
            else if (dirty) Text("  ·  Unsaved changes", color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (LocalCompact.current) IconAction(Icons.Rounded.FolderOpen, "Browse for an application", size = 40.dp, enabled = !locked) { model.browseExe() }
            else SecondaryButton("Browse for an application", icon = Icons.Rounded.FolderOpen, enabled = !locked) { model.browseExe() }
            SecondaryButton("Cancel", enabled = dirty) { shell.discardDraft() }
            PrimaryButton("Save changes", enabled = dirty && !locked, busy = shell.busy) { shell.saveDraft() }
        }
    ) {
        val compact = LocalCompact.current
        Column(Modifier.fillMaxSize()) {
            NewHeading(listOf("Selective routing"), "Applications", "Choose which apps use a proxy.")
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Segmented(AppListSource.entries, model.source, { it.label }, Modifier.width(if (compact) 180.dp else 210.dp), height = 40.dp) { model.source = it }
                if (!compact) TextInput(model.query, { model.query = it }, "Search applications", Modifier.weight(1f), Icons.Rounded.Search)
                IconAction(Icons.Rounded.Refresh, "Refresh the application list", size = 40.dp) { model.refresh(true) }
                if (compact) Spacer(Modifier.weight(1f)) else Spacer(Modifier.width(6.dp))
                Text("Advanced routing", color = White, fontSize = 13.sp, maxLines = 1)
                ToggleSwitch(draft.advanced, "Advanced routing", enabled = !locked, showState = false) { draft.advanced = it }
            }
            if (compact) {
                Spacer(Modifier.height(10.dp))
                TextInput(model.query, { model.query = it }, "Search applications", Modifier.fillMaxWidth(), Icons.Rounded.Search)
            }
            val unresolved = model.unresolved()
            when {
                locked -> { Spacer(Modifier.height(12.dp)); NewLockNote("Disconnect to change the selection, app routes or Advanced routing. Bandwidth limits can be changed now.") }
                !shell.perApp -> {
                    Spacer(Modifier.height(12.dp))
                    InfoBanner("The app selection is used by Smart per-app. You are in ${modeLabel(shell.mode)} mode.", Modifier.fillMaxWidth()) {
                        SecondaryButton("Change mode", height = 32.dp) { shell.openSettings(SettingsCategory.Connection) }
                    }
                }
                unresolved.isNotEmpty() -> {
                    Spacer(Modifier.height(12.dp))
                    InfoBanner("${plural(unresolved.size, "app")} ${if (unresolved.size == 1) "needs" else "need"} a replacement server before you can connect: " +
                        joinNames(unresolved.map { appName(it.first) }) + ".", Modifier.fillMaxWidth(), Amber, Icons.Rounded.WarningAmber)
                }
            }
            Spacer(Modifier.height(16.dp))
            NewTableHeader {
                Box(Modifier.width(34.dp))
                HeaderText("Application", Modifier.weight(1.35f))
                HeaderText(if (compact) "Route and server" else "Route", if (compact) Modifier.weight(1f) else Modifier.width(118.dp))
                if (!compact) { Box(Modifier.width(30.dp)); HeaderText("Server", Modifier.weight(1f)) }
                Box(Modifier.width(24.dp))
            }
            val listState = rememberLazyListState()
            LaunchedEffect(model.source, model.query) { listState.scrollToItem(0) }
            val apps = model.visible()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    model.loading -> LoadingState("Looking for applications…", Modifier.fillMaxSize())
                    apps.isEmpty() && model.query.isNotBlank() -> EmptyState(Icons.Rounded.SearchOff, "No applications match \"${model.query}\"",
                        "Try another name, switch between installed and running apps, or browse for the executable.", Modifier.fillMaxSize()) {
                        SecondaryButton("Clear search") { model.query = "" }
                    }
                    apps.isEmpty() -> EmptyState(Icons.Rounded.Apps, "No applications found",
                        if (model.source == AppListSource.RUNNING) "Start the application first, or pick it from installed apps." else "Browse for the executable to add an application manually.",
                        Modifier.fillMaxSize()) { SecondaryButton("Refresh", icon = Icons.Rounded.Refresh) { model.refresh(true) } }
                    else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        items(apps, key = { ApplicationIdentity.comparisonKey(it.path) }) { app -> NewAppRow(shell, model, app) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewAppRow(shell: Shell, model: AppsModel, app: AppChoice) {
    val draft = shell.draft
    val selected = draft.isSelected(app.path)
    val focused = ApplicationIdentity.same(draft.focused, app.path)
    val locked = shell.locked
    val cell = routeCellText(shell, app.path)
    NewTableRow(focused, onClick = { model.focus(app.path) }, label = "Configure ${app.title}") {
        Box(
            Modifier.width(34.dp).alpha(if (locked) .6f else 1f).toggleable(selected, enabled = !locked, role = Role.Checkbox) { model.toggle(app.path) }
                .semantics { contentDescription = "Route ${app.title} through the proxy" }.padding(vertical = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) { CheckMark(selected) }
        Row(Modifier.weight(1.35f).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppAvatar(app.name, app.path, 34.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                EllipsisText(app.title, White, 13.5.sp, fontWeight = FontWeight.SemiBold)
                EllipsisText(fileName(app.path), Muted, 11.5.sp)
            }
        }
        if (LocalCompact.current) {
            Column(Modifier.weight(1f)) {
                val kind = cell.kind
                if (kind == null) Text("Direct", color = Muted, fontSize = 12.5.sp)
                else {
                    RoutePill(kind)
                    Spacer(Modifier.height(3.dp))
                    EllipsisText(cell.server, if (cell.warning) Amber else White, 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = White.copy(.55f), modifier = Modifier.size(20.dp))
            return@NewTableRow
        }
        Box(Modifier.width(118.dp)) {
            val kind = cell.kind
            if (kind == null) Text("Direct", color = Muted, fontSize = 12.5.sp) else RoutePill(kind)
        }
        Box(Modifier.width(30.dp)) { if (cell.kind != null) ArrowGlyph(White.copy(.6f)) }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (cell.kind != null) {
                if (cell.warning) Icon(Icons.Rounded.WarningAmber, null, tint = Amber, modifier = Modifier.size(16.dp))
                else ServerAvatar(cell.node, 24.dp)
                Spacer(Modifier.width(9.dp))
                EllipsisText(cell.server, if (cell.warning) Amber else White, 12.5.sp, fontWeight = FontWeight.Medium)
            } else Text("Regular connection", color = Muted, fontSize = 12.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = White.copy(.55f), modifier = Modifier.size(20.dp))
    }
}


@Composable
private fun ColumnScope.NewAppPanel(shell: Shell, path: String) {
    val draft = shell.draft
    val locked = shell.locked
    val selected = draft.isSelected(path)
    NewPanelHeading("App configuration", appName(path), fileName(path), leading = { AppAvatar(fileName(path), path, 56.dp, framed = true) })
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        LinkAction("Back to the selection", arrow = false, fontSize = 12.sp) { draft.focused = null }
    }
    if (!selected) {
        NewPanelSection("Not routed") {
            Text("This app uses your regular connection. Select it to route it through a proxy.", color = Muted, fontSize = 12.5.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Route this app", Modifier.fillMaxWidth(), enabled = !locked) { draft.toggle(path); draft.focused = path }
        }
    } else {
        NewPanelSection("Route selection") {
            if (!draft.advanced) {
                Text("Turn on Advanced routing to choose Main, Manual or Auto for each app. Now it uses the main server.",
                    color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Turn on Advanced routing", Modifier.fillMaxWidth(), enabled = !locked) { draft.advanced = true }
            } else {
                Segmented(RouteModeOrder, draft.mode(path), { it.label() }, Modifier.fillMaxWidth(), enabled = !locked, height = 42.dp) { draft.setMode(path, it) }
                when (draft.mode(path)) {
                    AppRouteMode.MANUAL -> { Spacer(Modifier.height(12.dp)); FieldLabel("Server"); RoutePicker(shell, path, !locked) }
                    AppRouteMode.AUTO -> { Spacer(Modifier.height(12.dp)); FieldLabel("Choose from"); AutoPoolMenu(shell, path) }
                    AppRouteMode.MAIN -> {}
                }
            }
        }
        NewPanelSection("Current decision") { DecisionSummary(shell, path) }
    }
    NewPanelSection { BandwidthEditor(shell, path) }
    if (shell.apps.any { ApplicationIdentity.same(it, path) }) NewPanelSection("For a while") { TemporaryAppAction(shell, path) }
    if (selected) {
        Spacer(Modifier.height(16.dp))
        LinkAction("Remove from selection", arrow = false, enabled = !locked, color = Red, fontSize = 12.5.sp) {
            draft.selected.removeAll { ApplicationIdentity.same(it, path) }; draft.focused = null
        }
    }
    if (locked) { Spacer(Modifier.height(14.dp)); NewLockNote("Route modes change while disconnected. Limits apply right away.") }
}

@Composable
private fun ColumnScope.NewSelectionPanel(shell: Shell, model: AppsModel) {
    val draft = shell.draft
    val main = shell.mainNode
    val routes = model.selectedRoutes()
    NewPanelHeading("App configuration", plural(routes.size, "app") + " selected",
        if (draft.advanced) "${plural(model.routeCount(), "route")}  ·  Advanced routing on" else "All selected apps use the main server")
    NewPanelSection("Selected apps") {
        if (routes.isEmpty()) Text("Nothing selected yet. Tick an application to route it through the proxy.", color = Muted, fontSize = 12.5.sp, lineHeight = 18.sp)
        routes.forEach { (path, _) ->
            val cell = routeCellText(shell, path)
            Row(Modifier.fillMaxWidth().pressable(RoundedCornerShape(6.dp), label = "Configure ${appName(path)}") { model.focus(path) }.padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                AppAvatar(fileName(path), path, 30.dp)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    EllipsisText(appName(path), White, 13.sp, fontWeight = FontWeight.SemiBold)
                    EllipsisText("${cell.mode}  ·  ${cell.server}", if (cell.warning) Amber else Muted, 11.5.sp)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
    NewPanelSection("Main server") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ServerAvatar(main, 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                EllipsisText(main?.let(::serverName) ?: "No server yet", White, 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(if (main == null) "Add one in Servers" else "Used by every app set to Main", color = Muted, fontSize = 11.5.sp)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Note(if (draft.advanced) "Select an app to see its decision and set bandwidth limits. Turning Advanced off keeps every choice."
        else "Select an app to set bandwidth limits, or turn on Advanced routing to give apps their own server.")
}
