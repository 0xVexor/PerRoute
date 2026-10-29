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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.backup.BackupService
import app.perroute.model.ConnectionMode

@Composable
internal fun SimpleSettingsPage(shell: Shell) {
    val category = shell.settingsCategory
    val backup = if (category == SettingsCategory.Backup) backupModel(shell) else null
    val restore = backup?.restore
    LaunchedEffect(restore) {
        if (backup != null && restore != null) shell.showDialog(onDismiss = { backup.finishRestore(); shell.closeDialog() }) { SimpleRestoreDialog(shell, backup, restore) }
    }
    SimplePage("Settings", "Make PerRoute work your way.", scroll = false) {
        val shape = RoundedCornerShape(16.dp)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val navWidth = if (maxWidth >= 800.dp) 250.dp else 200.dp
            Row(Modifier.fillMaxSize().background(Surface, shape).border(1.dp, Line, shape)) {
                Column(Modifier.width(navWidth).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsCategory.entries.forEach { item ->
                        val selected = item == category
                        val itemShape = RoundedCornerShape(10.dp)
                        Box(Modifier.fillMaxWidth().height(50.dp).focusRing(itemShape).background(if (selected) Selection else Color.Transparent, itemShape)
                            .selectable(selected, role = Role.Tab) { shell.selectSettings(item) }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart) {
                            Text(item.label, color = if (selected) Violet2 else White.copy(.85f), fontSize = 15.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                        }
                    }
                }
                Divider(Modifier.fillMaxHeight(), vertical = true)
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 30.dp, vertical = 26.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(category.label, color = White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            if (category != SettingsCategory.General) Text(category.subtitle, color = Muted, fontSize = 15.sp)
                        }
                        if (category == SettingsCategory.ActivityLog) {
                            val clipboard = LocalClipboardManager.current
                            SecondaryButton("Copy all", icon = Icons.Rounded.ContentCopy, enabled = shell.logs.isNotEmpty()) {
                                clipboard.setText(AnnotatedString(shell.logs.takeLast(200).joinToString("\n"))); shell.notify(ActionResult(true, "Activity log copied."))
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    when (category) {
                        SettingsCategory.General -> SimpleGeneral(shell)
                        SettingsCategory.Connection -> SimpleConnectionSettings(shell)
                        SettingsCategory.Routing -> SimpleRoutingSettings(shell)
                        SettingsCategory.Automation -> SimpleAutomation(shell)
                        SettingsCategory.Updates -> SimpleUpdates(shell)
                        SettingsCategory.Components -> SimpleComponents(shell)
                        SettingsCategory.Backup -> SimpleBackup(shell, backup!!)
                        SettingsCategory.LocalNetwork -> LocalNetworkSettings(shell)
                        SettingsCategory.Recovery -> RecoverySettings(shell)
                        SettingsCategory.ActivityLog -> SimpleActivityLog(shell)
                    }
                }
            }
        }
    }
}

@Composable
private fun SimpleGeneral(shell: Shell) {
    SimpleSettingRow("Appearance", "Choose a color theme.") {
        Segmented(listOf(true, false), Appearance.dark, { if (it) "Dark" else "Light" }, Modifier.width(300.dp), height = 48.dp,
            icon = { if (it) Icons.Rounded.DarkMode else Icons.Rounded.LightMode }) { shell.setDarkMode(it) }
    }
    SimpleSettingRow("Interface style", "Simple or New. Only the look changes: your connection, routes, settings and unsaved changes stay as they are.") {
        Segmented(listOf(UiStyle.SIMPLE, UiStyle.NEW), Appearance.style, { it.label }, Modifier.width(300.dp), height = 48.dp) { shell.setUiStyle(it) }
    }
    if (shell.controller.explorerMenuSupported()) {
        val explorer = explorerMenuModel(shell)
        SimpleSettingRow("Route with PerRoute in Explorer", ExplorerMenuDetail, note = if (explorer.available) "" else "Available in the installed or portable PerRoute.") {
            ToggleSwitch(explorer.enabled, "Route with PerRoute in Explorer", enabled = explorer.available) { explorer.change(it) }
        }
    }
    SimpleSettingRow("Window behavior", WindowBehaviorDetail) {
        Icon(Icons.Rounded.DesktopWindows, null, tint = White.copy(.8f), modifier = Modifier.size(28.dp))
    }
    Column(Modifier.padding(vertical = 18.dp)) {
        Text("About", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(64.dp)
            Spacer(Modifier.width(20.dp))
            Column {
                Text("PerRoute ${app.perroute.service.BuildIdentity.displayVersion()}", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(Slogan, color = White.copy(.8f), fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text(Credit, color = Muted, fontSize = 13.sp)
            }
        }
    }
    Divider()
    SimpleSettingRow("Reset settings", if (shell.locked) "Disconnect first. A reset is only possible while disconnected." else "Remove saved servers, apps, rules and preferences.", divider = false) {
        LinkAction("Reset everything…", color = Red, arrow = false, enabled = !shell.locked && !shell.busy, fontSize = 15.sp) { confirmReset(shell) }
    }
}

@Composable
private fun SimpleConnectionSettings(shell: Shell) {
    val model = settingsModel(shell, SettingsCategory.Connection) { ConnectionSettingsModel(shell) }
    LaunchedEffect(Unit) { model.load() }
    val locked = shell.locked
    if (locked) { Spacer(Modifier.height(6.dp)); SimpleLockNote("Disconnect before changing connection settings.") }
    Spacer(Modifier.height(10.dp))
    Text("Connection mode", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    OfferedModes.filter { shell.controller.capabilities().supports(it) }.forEach { mode ->
        val missing = model.driverMissing(mode)
        val selected = mode == shell.mode
        val shape = RoundedCornerShape(12.dp)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).alpha(if (model.canChoose(mode) || selected) 1f else .55f).focusRing(shape)
            .background(if (selected) Selection else Color.Transparent, shape).border(1.dp, if (selected) Violet2.copy(.4f) else Line, shape)
            .selectable(selected, enabled = model.canChoose(mode), role = Role.RadioButton) { model.choose(mode) }.pointerHoverIcon(PointerIcon.Hand).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RadioMark(selected, 22.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(modeTitle(mode), color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(if (missing) model.missingRoutingDetail else modeChoiceDetail(mode), color = if (missing) Amber else Muted, fontSize = 13.sp)
            }
            if (mode == ConnectionMode.NATIVE_PER_APP && model.environment != null) Badge(model.routingStatus(missing), if (missing) Amber else Cyan)
        }
    }
    Spacer(Modifier.height(8.dp))
    SimpleSettingRow("Advanced routing", "Each selected app can use its own server." + if (!shell.perApp) " Applies in Smart per-app only." else "") {
        ToggleSwitch(shell.advanced, "Advanced routing", enabled = !locked && !shell.busy) { model.setAdvanced(it) }
    }
    val assignments = model.assignments()
    Spacer(Modifier.height(14.dp))
    Text("Saved app assignments (${assignments.size})", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    assignments.forEach { (path, route) ->
        Row(Modifier.fillMaxWidth().pressable(RoundedCornerShape(8.dp), label = "Open ${appName(path)} in Apps") { model.openApp(path) }.padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            AppAvatar(fileName(path), path, 30.dp)
            Spacer(Modifier.width(14.dp))
            EllipsisText(appName(path), White, 14.sp, Modifier.weight(1f))
            EllipsisText(route, Muted, 13.sp, Modifier.weight(1f))
            Icon(Icons.Rounded.ChevronRight, null, tint = Muted, modifier = Modifier.size(20.dp))
        }
        Divider()
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(model.assignmentNote(), color = if (locked) Amber else Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        LinkAction("Manage app routes", fontSize = 14.sp) { shell.navigate(Tab.Apps) }
    }
}

@Composable
private fun SimpleRoutingSettings(shell: Shell) {
    val model = settingsModel(shell, SettingsCategory.Routing) { RoutingSettingsModel(shell) }
    SimpleSettingRow("Background quality checks", "Measure server quality in the background: up to 24 servers every 10 minutes. Always on while an app uses Auto.", note = model.lastPass()) {
        ToggleSwitch(model.background, "Background quality checks") { model.changeBackground(it) }
    }
    Column(Modifier.padding(vertical = 16.dp)) {
        Text("Automatic server selection", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text("Only for apps set to Auto.", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Switch when better by", color = White.copy(.85f), fontSize = 14.sp, modifier = Modifier.weight(1f))
            MenuButton("${model.marginShown()} points", model.margins.map { v -> MenuEntry("$v points", selected = model.marginShown() == v) { model.changeMargin(v) } },
                Modifier.width(220.dp), accessibleLabel = "Switch when another server is better by", height = 44.dp)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wait between changes", color = White.copy(.85f), fontSize = 14.sp, modifier = Modifier.weight(1f))
            MenuButton("${model.cooldownShown()} min", model.cooldowns.map { v -> MenuEntry("$v min", selected = model.cooldownShown() == v) { model.changeCooldown(v) } },
                Modifier.width(220.dp), accessibleLabel = "Wait between changes for one app", height = 44.dp)
        }
        Spacer(Modifier.height(10.dp))
        Text("Keep stable routes until a better server is worth the switch. A failing server is replaced right away, at most once a minute. Manual choices are never changed, and no app is ever moved to the direct route.",
            color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
    }
    Divider()
    SimpleSettingRow("Route recovery", "Recover each route independently. A failing route retries with growing delays (2 to 32 s) and is rebuilt in place.", note = "Other routes keep running.") {
        ToggleSwitch(model.monitoring, "Route recovery") { model.changeMonitoring(it) }
    }
    SimpleSettingRow("Traffic gateway", "Enables app limits and detailed traffic. It never reads the contents of connections. Smart per-app only.",
        note = if (shell.controller.gatewayRequired()) "Required for macOS per-app routing." else if (shell.locked) "Disconnect to change." else "Change only while disconnected.") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (shell.locked) { Icon(Icons.Rounded.Lock, null, tint = White.copy(.8f), modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(if (model.gateway) "On" else "Off", color = White, fontSize = 14.sp); Spacer(Modifier.width(12.dp)) }
            ToggleSwitch(model.gateway, "Traffic gateway", enabled = !shell.locked && !shell.controller.gatewayRequired()) { model.changeGateway(it) }
        }
    }
    if (IsMacDesktop) Note("macOS per-app routing handles IPv4 TCP. It does not block UDP or QUIC; a browser may use those connections directly.")
    else SimpleSettingRow("Block QUIC", QuicDetail, note = QuicNote, divider = false) {
        ToggleSwitch(model.quicBlocked, "Block QUIC") { model.changeQuic(it) }
    }
}

@Composable
private fun SimpleAutomation(shell: Shell) {
    val model = settingsModel(shell, SettingsCategory.Automation) { AutomationSettingsModel(shell) }
    model.options().forEach { option -> SimpleSettingRow(option.title, option.detail) { ToggleSwitch(option.value, option.title) { option.set(it) } } }
    Spacer(Modifier.height(14.dp))
    Note("Custom servers do not switch automatically. Failover and fastest-on-connect only change the main server, and a main-server switch restarts every route briefly.")
}

@Composable
private fun SimpleUpdates(shell: Shell) {
    if (!shell.controller.capabilities().updates()) {
        UnavailableFeature("Updates", "Install a newer signed app manually.")
        return
    }
    val model = settingsModel(shell, SettingsCategory.Updates) { UpdateSettingsModel(shell) }
    UpdateEffects(model)
    SimpleSettingRow(model.headline, "Installed: ${app.perroute.service.BuildIdentity.displayVersion()}" + (model.available?.let { "  ·  Available: $it" } ?: "")) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(if (shell.busy) "Checking…" else "Check for updates", icon = Icons.Rounded.Refresh, enabled = !shell.busy) { model.check() }
            when {
                model.downloaded != null -> PrimaryButton("Install update", icon = Icons.Rounded.Download) { model.install() }
                model.downloading -> PrimaryButton("Downloading ${model.progress}%", enabled = false) {}
                model.available != null -> PrimaryButton("Download ${model.available}", icon = Icons.Rounded.Download) { model.download() }
            }
        }
    }
    if (model.downloading) { Spacer(Modifier.height(12.dp)); ProgressBar(model.progress / 100f, Violet, Modifier.fillMaxWidth(), 8.dp) }
    Spacer(Modifier.height(12.dp))
    Text(model.statusLine, color = if (model.downloading) Cyan else Muted, fontSize = 13.sp, lineHeight = 18.sp)
    RollbackNote(shell)
    Spacer(Modifier.height(18.dp))
    SimpleSettingRow("Update automatically", AutoUpdateDetail) { ToggleSwitch(model.auto, "Update automatically") { model.changeAuto(it) } }
    FieldLabel("Signed update feed")
    TextInput(model.feed, { model.changeFeed(it) }, "Official PerRoute releases", height = 46.dp)
    Spacer(Modifier.height(14.dp))
    Note(UpdateFeedNote)
}

@Composable
private fun SimpleComponents(shell: Shell) {
    val model = settingsModel(shell, SettingsCategory.Components) { ComponentSettingsModel(shell) }
    LaunchedEffect(Unit) { if (model.environment == null) model.reload() }
    val state = model.environment
    if (state == null) { LoadingState("Checking installed components…", Modifier.fillMaxWidth()); return }
    SimpleSettingRow("Xray core", "Connection and protocol engine.") {
        if (state.coreReady) Badge("Installed", Cyan, icon = Icons.Rounded.CheckCircle)
        else if (shell.controller.capabilities().coreInstallation()) PrimaryButton("Install", icon = Icons.Rounded.Download, enabled = model.canInstall) { model.installCore() }
        else Badge("Not bundled", Amber, icon = Icons.Rounded.WarningAmber)
    }
    SimpleSettingRow(model.routingName, model.routingDetail) {
        Badge(if (state.perAppReady) "Installed" else "Not installed", if (state.perAppReady) Cyan else Amber, icon = if (state.perAppReady) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber)
    }
    if (model.progress.isNotBlank()) Text(model.progress, color = Amber, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
    Spacer(Modifier.height(16.dp))
    SecondaryButton(if (model.loading) "Checking…" else "Check again", icon = Icons.Rounded.Refresh, enabled = !model.loading) { model.reload() }
    Spacer(Modifier.height(14.dp))
    Note(model.routingNote)
}

@Composable
private fun SimpleBackup(shell: Shell, model: BackupModel) {
    if (!shell.controller.capabilities().backupRestore()) {
        UnavailableFeature("Backup and restore", "Portable backup and restore needs platform-aware application mapping.")
        return
    }
    Spacer(Modifier.height(8.dp))
    Divider()
    Spacer(Modifier.height(16.dp))
    Text("Create a backup", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    Text("Choose what to include in the backup file.", color = Muted, fontSize = 13.sp)
    Spacer(Modifier.height(10.dp))
    val sections = BackupService.Section.entries
    Row(Modifier.fillMaxWidth()) {
        listOf(sections.take(3), sections.drop(3)).forEach { column ->
            Column(Modifier.weight(1f)) {
                column.forEach { section ->
                    val checked = model.sections[section] == true
                    Tooltip(section.detail()) {
                        Row(Modifier.toggleable(checked, role = Role.Checkbox) { model.sections[section] = it }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            CheckMark(checked); Spacer(Modifier.width(12.dp)); Text(section.title(), color = White, fontSize = 14.5.sp)
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Lock, null, tint = White.copy(.8f), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Encrypt with password", color = White, fontSize = 14.5.sp)
            Text(if (model.encrypt) "Required to restore this file. The password cannot be recovered." else "Not encrypted: anyone with the file can read and use your server credentials.",
                color = if (model.encrypt) Muted else Amber, fontSize = 12.5.sp)
        }
        ToggleSwitch(model.encrypt, "Encrypt the backup with a password") { model.encrypt = it }
    }
    if (model.encrypt) {
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) { FieldLabel("Password"); PasswordField(model.password, { model.password = it }, "Password") }
            Column(Modifier.weight(1f)) { FieldLabel("Repeat password"); PasswordField(model.confirm, { model.confirm = it }, "Repeat password") }
        }
        model.passwordProblem?.takeIf { model.password.isNotEmpty() || model.confirm.isNotEmpty() }?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Amber, fontSize = 12.5.sp) }
    }
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton("Create backup", enabled = model.canCreate, height = 48.dp) { model.create() }
        Spacer(Modifier.width(18.dp))
        Icon(Icons.Rounded.Description, null, tint = Muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Saves a .prb file to your computer.", color = White.copy(.8f), fontSize = 13.5.sp)
    }
    Spacer(Modifier.height(18.dp))
    Divider()
    Spacer(Modifier.height(16.dp))
    Text("Restore a backup", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    Text("Choose a file, then review the changes. Nothing changes until you confirm.", color = Muted, fontSize = 13.sp)
    if (shell.locked) { Spacer(Modifier.height(10.dp)); SimpleLockNote("Disconnect to restore a backup. Creating one works at any time.") }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        SecondaryButton("Choose backup…", icon = Icons.Outlined.Folder, enabled = !shell.locked && !shell.busy, height = 48.dp) { model.choose() }
        Column {
            SecondaryButton("Undo last restore action", icon = Icons.Rounded.Undo, enabled = model.hasSnapshot && !shell.locked && !shell.busy, height = 48.dp) { model.undo() }
            if (!model.hasSnapshot) Text("No restore to undo.", color = Muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 5.dp, start = 4.dp))
        }
    }
    Spacer(Modifier.height(12.dp))
    Note("A rollback copy is created before restore, and the restore is applied in one step: if it fails, everything is put back.")
}

@Composable
private fun SimpleActivityLog(shell: Shell) {
    Text("The last 200 events of this session, newest first. Select text and press $CopyShortcut, or copy everything.", color = Muted, fontSize = 13.sp)
    Spacer(Modifier.height(12.dp))
    if (shell.logs.isEmpty()) { EmptyState(Icons.Rounded.Description, "Nothing logged yet", "Connection events, test results and parser warnings appear here.", Modifier.fillMaxWidth()); return }
    SelectionContainer {
        Column {
            shell.logs.takeLast(200).asReversed().forEach { raw ->
                val line = parseLog(raw)
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(line.time.ifBlank { "—" }, color = Muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(84.dp))
                    Box(Modifier.width(26.dp)) { if (line.warning) Icon(Icons.Rounded.WarningAmber, "Problem", tint = Amber, modifier = Modifier.size(18.dp)) }
                    Text(line.text, color = White, fontSize = 13.5.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
                }
                Divider()
            }
        }
    }
}

@Composable
private fun SimpleRestoreDialog(shell: Shell, backup: BackupModel, session: RestoreSession) {
    val preview = session.preview
    val close = { backup.finishRestore(); shell.closeDialog() }
    DialogCard("Review backup", "${session.request.file.fileName}  ·  Created ${session.created}  ·  ${session.size}  ·  ${session.application}", close, width = 680.dp, actions = {
        SecondaryButton("Cancel", onClick = close)
        PrimaryButton("Restore backup", icon = Icons.Rounded.Restore, enabled = !shell.locked && !shell.busy) { shell.closeDialog(); session.restore { backup.finishRestore() } }
    }) {
        if (shell.locked) { SimpleLockNote("Disconnect to restore a backup."); Spacer(Modifier.height(10.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge(if (preview.compatibility() == BackupService.Compatibility.COMPATIBLE) "Compatible version" else "Upgraded from an older backup", Cyan, icon = Icons.Rounded.CheckCircle)
            Badge(if (preview.header().encrypted()) "Encrypted and unlocked" else "Not encrypted", if (preview.header().encrypted()) Cyan else Amber, icon = Icons.Rounded.Lock)
            Badge("Checksum valid", Cyan, icon = Icons.Rounded.CheckCircle)
        }
        Spacer(Modifier.height(16.dp))
        Text("This backup contains", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        val counts = preview.counts()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(counts.subscriptions() to "Subscriptions", counts.servers() to "Servers", counts.applications() to "App assignments",
                counts.rules() to "Rules", counts.profiles() to "Profiles", counts.preferences() to "Preferences").forEach { (count, label) ->
                Column(Modifier.weight(1f).background(Selection, RoundedCornerShape(10.dp)).padding(10.dp)) {
                    Text("$count", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(label, color = Muted, fontSize = 11.5.sp, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("${plural(session.newServers, "new server")} will be added  ·  ${plural(preview.serversAlreadyPresent(), "server")} already present" +
            if (preview.profilesWithSameName() > 0) "  ·  ${plural(preview.profilesWithSameName(), "profile")} with a name in use (kept as is)" else "", color = Muted, fontSize = 12.5.sp)
        preview.warnings().forEach { warning -> Spacer(Modifier.height(8.dp)); InfoBanner(warning, Modifier.fillMaxWidth(), Amber, Icons.Rounded.WarningAmber) }
        if (preview.missingPaths().isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("App path remapping", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("These applications are not on this computer. Choose where they are, or they are skipped.", color = Muted, fontSize = 12.5.sp)
            Spacer(Modifier.height(6.dp))
            preview.missingPaths().forEach { issue ->
                val replacement = session.remap[issue.originalPath()]
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        EllipsisText(issue.originalPath(), White, 12.5.sp)
                        EllipsisText(replacement?.let { "→ $it" } ?: "Will be skipped", if (replacement != null) Cyan else Muted, 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton("Choose path…", height = 34.dp) { session.choosePath(issue.originalPath()) }
                    if (replacement != null) { Spacer(Modifier.width(6.dp)); SecondaryButton("Skip", height = 34.dp) { session.skip(issue.originalPath()) } }
                }
                Divider()
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Restore options", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        listOf(BackupService.Mode.MERGE to ("Merge (recommended)" to "Adds what this backup has to your setup. Everything you already have is kept, including your preferences."),
            BackupService.Mode.REPLACE to ("Replace" to "Replaces the parts this backup contains with its versions. Parts it does not contain stay as they are.")).forEach { (mode, text) ->
            Row(Modifier.fillMaxWidth().focusRing(RoundedCornerShape(8.dp)).selectable(session.mode == mode, role = Role.RadioButton) { session.mode = mode }
                .pointerHoverIcon(PointerIcon.Hand).padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                RadioMark(session.mode == mode)
                Spacer(Modifier.width(12.dp))
                Column { Text(text.first, color = White, fontSize = 14.sp, fontWeight = FontWeight.Medium); Text(text.second, color = Muted, fontSize = 12.5.sp, lineHeight = 17.sp) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Note("A rollback snapshot is written before anything changes, and Undo last restore brings it back.", icon = Icons.Rounded.Info)
    }
}
