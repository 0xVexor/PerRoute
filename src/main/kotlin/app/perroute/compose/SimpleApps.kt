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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ChevronRight
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
internal fun SimpleAppsPage(shell: Shell) {
    val model = appsModel(shell)
    LaunchedEffect(Unit) { model.ensureLoaded() }
    val draft = shell.draft
    val locked = shell.locked
    val dirty = draft.dirty(shell)
    SimplePage("Applications", "Choose which apps use a proxy.", scroll = false, actions = {
        Text("Advanced routing", color = White, fontSize = 14.sp)
        ToggleSwitch(draft.advanced, "Advanced routing", enabled = !locked) { draft.advanced = it }
    }) {
        SimpleCard(Modifier.weight(1f), padding = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UnderlineTabs(AppListSource.entries, model.source, { "${it.label} apps" }, Modifier.weight(1f)) { model.source = it }
                Spacer(Modifier.width(16.dp))
                TextInput(model.query, { model.query = it }, "Search applications", Modifier.width(if (LocalCompact.current) 190.dp else 300.dp), Icons.Rounded.Search, height = 44.dp)
                Spacer(Modifier.width(10.dp))
                IconAction(Icons.Rounded.Refresh, "Refresh the application list", size = 44.dp) { model.refresh(true) }
            }
            val unresolved = model.unresolved()
            when {
                locked -> { Spacer(Modifier.height(12.dp)); SimpleLockNote("Disconnect to change the selection, app routes or Advanced routing. Bandwidth limits can be changed now.") }
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
            Spacer(Modifier.height(10.dp))
            SimpleTableHeader {
                Box(Modifier.width(42.dp))
                HeaderText("Application", Modifier.weight(1.3f))
                HeaderText("Route", Modifier.weight(1f))
                Box(Modifier.width(28.dp))
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
                        items(apps, key = { ApplicationIdentity.comparisonKey(it.path) }) { app -> SimpleAppRow(shell, model, app) }
                    }
                }
            }
            Text("Unselected apps use your regular connection.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, start = 10.dp))
            Spacer(Modifier.height(12.dp))
            Divider()
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${draft.selected.size} selected" + if (draft.selected.isNotEmpty()) "  ·  ${plural(model.routeCount(), "route")}" else "", color = White.copy(.85f), fontSize = 14.sp)
                if (locked) Text("Routes can be edited while disconnected.", color = Amber, fontSize = 13.sp)
                else if (dirty) Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(Amber, 10.dp); Spacer(Modifier.width(8.dp)); Text("Unsaved changes", color = Amber, fontSize = 14.sp)
                }
                Spacer(Modifier.weight(1f))
                SecondaryButton("Browse for an application", enabled = !locked, height = 46.dp) { model.browseExe() }
                SecondaryButton("Cancel", enabled = dirty, height = 46.dp) { shell.discardDraft() }
                PrimaryButton("Save changes", enabled = dirty && !locked, busy = shell.busy, height = 46.dp) { shell.saveDraft() }
            }
        }
    }
    val focused = draft.focused
    LaunchedEffect(focused) {
        if (focused != null && shell.dialog == null) shell.showDialog(onDismiss = { draft.focused = null; shell.closeDialog() }) { SimpleAppDialog(shell) }
    }
}

@Composable
private fun SimpleAppRow(shell: Shell, model: AppsModel, app: AppChoice) {
    val draft = shell.draft
    val selected = draft.isSelected(app.path)
    val locked = shell.locked
    val cell = routeCellText(shell, app.path)
    SimpleTableRow(onClick = { model.focus(app.path) }, label = "Details of ${app.title}") {
        Box(
            Modifier.width(42.dp).alpha(if (locked) .6f else 1f).toggleable(selected, enabled = !locked, role = Role.Checkbox) { draft.toggle(app.path) }
                .semantics { contentDescription = "Route ${app.title} through the proxy" }.padding(vertical = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) { CheckMark(selected) }
        Row(Modifier.weight(1.3f).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppAvatar(app.name, app.path, 42.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                EllipsisText(app.title, White, 15.sp, fontWeight = FontWeight.Medium)
                EllipsisText(fileName(app.path), Muted, 13.sp)
            }
        }
        Column(Modifier.weight(1f)) {
            if (cell.kind == null) Text("Direct", color = Muted, fontSize = 14.sp)
            else {
                Text(cell.mode, color = if (cell.warning) Amber else Cyan, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                EllipsisText(cell.server, if (cell.warning) Amber else Muted, 13.sp)
            }
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = White.copy(.7f), modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun SimpleAppDialog(shell: Shell) {
    val draft = shell.draft
    val path = draft.focused ?: return
    val locked = shell.locked
    val selected = draft.isSelected(path)
    val close = { draft.focused = null; shell.closeDialog() }
    DialogCard(appName(path), path, close, width = 600.dp, actions = {
        if (selected) SecondaryButton("Remove from selection", enabled = !locked, tint = Red) { draft.selected.removeAll { ApplicationIdentity.same(it, path) }; close() }
        else PrimaryButton("Route this app", enabled = !locked) { draft.toggle(path) }
        PrimaryButton("Done", onClick = close)
    }) {
        if (locked) { SimpleLockNote("Route modes change while disconnected. Limits apply right away."); Spacer(Modifier.height(12.dp)) }
        if (!selected) {
            Text("This app uses your regular connection. Route it to choose how it picks its server.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        } else {
            Text("Route mode", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (!draft.advanced) {
                Text("Turn on Advanced routing to choose Main, Manual or Auto for each app. Now it uses the main server.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
            } else {
                Segmented(RouteModeOrder, draft.mode(path), { it.label() }, Modifier.fillMaxWidth(), enabled = !locked, height = 42.dp) { draft.setMode(path, it) }
                when (draft.mode(path)) {
                    AppRouteMode.MANUAL -> { Spacer(Modifier.height(12.dp)); FieldLabel("Server"); RoutePicker(shell, path, !locked) }
                    AppRouteMode.AUTO -> { Spacer(Modifier.height(12.dp)); FieldLabel("Choose from"); AutoPoolMenu(shell, path) }
                    AppRouteMode.MAIN -> {}
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Current decision", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            DecisionSummary(shell, path)
            Spacer(Modifier.height(10.dp))
            Note("Route changes are kept until you select Save changes.")
        }
        Spacer(Modifier.height(16.dp))
        Divider()
        Spacer(Modifier.height(16.dp))
        BandwidthEditor(shell, path)
        if (shell.apps.any { ApplicationIdentity.same(it, path) }) {
            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(16.dp))
            Text("For a while", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            TemporaryAppAction(shell, path)
        }
    }
}
