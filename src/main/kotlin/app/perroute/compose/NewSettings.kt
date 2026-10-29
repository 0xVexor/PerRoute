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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.backup.BackupService
import app.perroute.model.ConnectionMode

@Composable
internal fun NewSettingsPage(shell: Shell) {
    val category = shell.settingsCategory
    val backup = if (category == SettingsCategory.Backup) backupModel(shell) else null
    val restore = backup?.restore
    if (backup != null && restore != null) { NewRestoreReview(shell, backup, restore); return }
    NewSplitPage(panel = { NewPreferences(shell) }, panelScroll = false) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    NewEyebrow("Settings", category.label)
                    Spacer(Modifier.height(10.dp))
                    Text(category.label, color = White, fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.6).sp)
                    Spacer(Modifier.height(4.dp))
                    Text(category.subtitle, color = Muted, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
                if (category != SettingsCategory.ActivityLog) NewConnectionChip(shell)
                else {
                    val clipboard = LocalClipboardManager.current
                    SecondaryButton("Copy all", icon = Icons.Rounded.ContentCopy, enabled = shell.logs.isNotEmpty()) {
                        clipboard.setText(AnnotatedString(shell.logs.takeLast(200).joinToString("\n")))
                        shell.notify(ActionResult(true, "Activity log copied."))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            when (category) {
                SettingsCategory.General -> NewGeneral(shell)
                SettingsCategory.Connection -> NewConnectionSettings(shell)
                SettingsCategory.Routing -> NewRoutingSettings(shell)
                SettingsCategory.Automation -> NewAutomation(shell)
                SettingsCategory.Updates -> NewUpdates(shell)
                SettingsCategory.Components -> NewComponents(shell)
                SettingsCategory.Backup -> NewBackup(shell, backup!!)
                SettingsCategory.LocalNetwork -> LocalNetworkSettings(shell)
                SettingsCategory.Recovery -> RecoverySettings(shell)
                SettingsCategory.ActivityLog -> NewActivityLog(shell)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ColumnScope.NewPreferences(shell: Shell) {
    SectionLabel("Settings", White.copy(.85f))
    Spacer(Modifier.height(14.dp))
    Text("Preferences", color = White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
    Text("Choose a category.", color = White.copy(.8f), fontSize = 15.sp)
    Spacer(Modifier.height(24.dp))
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Divider()
        SettingsCategory.entries.forEach { category ->
            val selected = shell.settingsCategory == category
            Box(Modifier.fillMaxWidth().height(52.dp).focusRing(RoundedCornerShape(4.dp))
                .selectable(selected, role = Role.Tab) { shell.selectSettings(category) }.pointerHoverIcon(PointerIcon.Hand)
                .background(if (selected) Color.White.copy(.05f) else Color.Transparent)) {
                if (selected) Box(Modifier.width(4.dp).fillMaxHeight().background(NewColors.Lime))
                Text(category.label, color = if (selected) NewColors.Lime else White, fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp))
            }
            Divider()
        }
    }
    Divider()
    Spacer(Modifier.height(14.dp))
    Note("Settings apply immediately.")
}

@Composable
private fun SettingBlock(title: String, subtitle: String = "", first: Boolean = false, trailing: (@Composable RowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit = {}) {
    if (!first) { Divider(); Spacer(Modifier.height(18.dp)) }
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        if (trailing != null) { Spacer(Modifier.width(14.dp)); Row(verticalAlignment = Alignment.CenterVertically, content = trailing) }
    }
    Column(content = content)
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun RowScope.RadioCard(title: String, detail: String, selected: Boolean, enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null, onSelect: () -> Unit) {
    RadioCardBody(Modifier.weight(1f), title, detail, selected, enabled, trailing, onSelect)
}

@Composable
private fun RadioCardBody(modifier: Modifier, title: String, detail: String, selected: Boolean, enabled: Boolean, trailing: (@Composable () -> Unit)?, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier.alpha(if (enabled || selected) 1f else .55f).focusRing(shape)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect).pointerHoverIcon(PointerIcon.Hand)
            .background(if (selected) Selection else Surface, shape).border(1.dp, if (selected) Color(0xFFD9E870) else Line, shape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioMark(selected, 24.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
    }
}

@Composable
private fun NewGeneral(shell: Shell) {
    SettingBlock("Appearance", "Choose how PerRoute looks on your device.", first = true) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(if (LocalCompact.current) 1f else .85f).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioCard("Light", "Use the light theme for PerRoute.", !Appearance.dark) { shell.setDarkMode(false) }
            RadioCard("Dark", "Use the dark theme for PerRoute.", Appearance.dark) { shell.setDarkMode(true) }
        }
    }
    SettingBlock("Interface style", "Choose the design of PerRoute. Only the look changes: your connection, routes, settings and unsaved changes stay as they are.") {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(if (LocalCompact.current) 1f else .85f).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioCard("Simple", "Light pages with a violet accent and a wide menu.", Appearance.style == UiStyle.SIMPLE) { shell.setUiStyle(UiStyle.SIMPLE) }
            RadioCard("New", "Charcoal, bone and lime, with a detail panel on each page.", Appearance.style == UiStyle.NEW) { shell.setUiStyle(UiStyle.NEW) }
        }
    }
    if (shell.controller.explorerMenuSupported()) {
        val explorer = explorerMenuModel(shell)
        ToggleBlock("Route with PerRoute in Explorer", ExplorerMenuDetail, explorer.enabled, enabled = explorer.available,
            note = if (explorer.available) "" else "Available in the installed or portable PerRoute.") { explorer.change(it) }
    }
    SettingBlock("Window behavior", if (IsMacDesktop) "Reopen PerRoute from the Dock." else "Closing or minimizing keeps PerRoute in the tray.") {
        Spacer(Modifier.height(8.dp))
        Text(WindowBehaviorDetail,
            color = White.copy(.8f), fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.fillMaxWidth(if (LocalCompact.current) 1f else .85f))
    }
    SettingBlock("About", "Version and application information.") {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Top) {
            BrandMark(44.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text("PerRoute", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(app.perroute.service.BuildIdentity.displayVersion(), color = White.copy(.85f), fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text(Slogan, color = White.copy(.85f), fontSize = 14.sp)
                Text(Credit, color = Muted, fontSize = 12.sp)
            }
        }
    }
    SettingBlock("Reset settings", "Remove all saved servers, apps, rules and preferences.") {
        Spacer(Modifier.height(12.dp))
        SecondaryButton("Reset everything…", icon = Icons.Rounded.DeleteOutline, tint = Red, enabled = !shell.locked && !shell.busy, height = 44.dp) { confirmReset(shell) }
        if (shell.locked) { Spacer(Modifier.height(8.dp)); NewLockNote("Disconnect first. A reset is only possible while disconnected.") }
    }
}

@Composable
private fun NewConnectionSettings(shell: Shell) {
    val model = settingsModel(shell, SettingsCategory.Connection) { ConnectionSettingsModel(shell) }
    LaunchedEffect(Unit) { model.load() }
    val locked = shell.locked
    SettingBlock("Connection mode", "Choose how PerRoute connects on this device.", first = true) {
        if (locked) { Spacer(Modifier.height(8.dp)); NewLockNote("Disconnect before changing connection settings.") }
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OfferedModes.filter { shell.controller.capabilities().supports(it) }.forEach { mode ->
                val missing = model.driverMissing(mode)
                RadioCardBody(Modifier.fillMaxWidth(), modeTitle(mode), if (missing) model.missingRoutingDetail else modeChoiceDetail(mode),
                    mode == shell.mode, model.canChoose(mode), trailing = if (mode == ConnectionMode.NATIVE_PER_APP && model.environment != null) ({
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Divider(Modifier.height(34.dp), vertical = true)
                            Spacer(Modifier.width(16.dp))
                            Icon(if (missing) Icons.Rounded.WarningAmber else Icons.Rounded.CheckCircle, null, tint = if (missing) Amber else Cyan, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(model.routingStatus(missing), color = White.copy(.85f), fontSize = 13.sp)
                        }
                    }) else null) { model.choose(mode) }
            }
        }
    }
    SettingBlock("Advanced routing", "Each selected app can use its own server." + if (!shell.perApp) " Applies in Smart per-app only." else "", trailing = {
        ToggleSwitch(shell.advanced, "Advanced routing", enabled = !locked && !shell.busy, showState = false) { model.setAdvanced(it) }
    }) {
        Spacer(Modifier.height(14.dp))
        val assignments = model.assignments()
        Text("Saved app assignments (${assignments.size})", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Divider()
        assignments.forEach { (path, route) ->
            Row(Modifier.fillMaxWidth().pressable(RoundedCornerShape(0.dp), label = "Open ${appName(path)} in Apps") { model.openApp(path) }.padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                AppAvatar(fileName(path), path, 26.dp)
                Spacer(Modifier.width(12.dp))
                EllipsisText(appName(path), White, 13.sp, Modifier.weight(1f))
                EllipsisText(route, Muted, 12.5.sp, Modifier.weight(1f))
                Icon(Icons.Rounded.ChevronRight, null, tint = White.copy(.6f), modifier = Modifier.size(20.dp))
            }
            Divider()
        }
        Spacer(Modifier.height(12.dp))
        LinkAction("Manage app routes", fontSize = 13.sp) { shell.navigate(Tab.Apps) }
        Text("Route choices are configured in the Applications workspace. " + model.assignmentNote(), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun ToggleBlock(title: String, detail: String, checked: Boolean, first: Boolean = false, enabled: Boolean = true, note: String = "", onToggle: (Boolean) -> Unit) {
    SettingBlock(title, detail, first, trailing = { ToggleSwitch(checked, title, enabled = enabled, onToggle = onToggle) }) {
        if (note.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(note, color = White.copy(.75f), fontSize = 12.5.sp, lineHeight = 17.sp) }
    }
}

@Composable
private fun NewRoutingSettings(shell: Shell) {
    val model = settingsModel(shell, SettingsCategory.Routing) { RoutingSettingsModel(shell) }
    ToggleBlock("Background quality checks", "Measure latency, jitter and tunnel reliability: up to 24 servers every 10 minutes, four at a time. Always on while an app uses Auto.",
        model.background, first = true, note = model.lastPass()) { model.changeBackground(it) }
    SettingBlock("Automatic server selection", "Only apps set to Auto are affected.") {
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                FieldLabel("Switch when better by")
                MenuButton("${model.marginShown()} points", model.margins.map { value -> MenuEntry("$value points", selected = model.marginShown() == value) { model.changeMargin(value) } },
                    Modifier.fillMaxWidth(), accessibleLabel = "Switch when another server is better by")
            }
            Column(Modifier.weight(1f)) {
                FieldLabel("Wait between changes")
                MenuButton("${model.cooldownShown()} minutes", model.cooldowns.map { value -> MenuEntry("$value minutes", selected = model.cooldownShown() == value) { model.changeCooldown(value) } },
                    Modifier.fillMaxWidth(), accessibleLabel = "Wait between changes for one app")
            }
            Spacer(Modifier.weight(.3f))
        }
        Spacer(Modifier.height(10.dp))
        Text("Stable routes are kept until a better choice is worth the switch. A failing server is replaced right away, at most once a minute. Manual choices are never changed, and no app is ever moved to the direct route.",
            color = White.copy(.75f), fontSize = 12.5.sp, lineHeight = 17.sp)
    }
    ToggleBlock("Route recovery", "Recover each route independently. Other routes keep running.", model.monitoring,
        note = "Every live route is checked through its own port once a minute. A failing route retries with growing delays (2 to 32 s), is rebuilt in place, and is checked every two minutes after five failures.") { model.changeMonitoring(it) }
    ToggleBlock("Traffic gateway", "Enables per-app bandwidth limits and detailed traffic. It relays each app's connections on this computer and never reads their contents. Smart per-app only.",
        model.gateway, enabled = !shell.locked && !shell.controller.gatewayRequired(), note = if (shell.controller.gatewayRequired()) "Required for macOS per-app routing." else if (shell.locked) "Disconnect to change. It sits in the data path." else "Change only while disconnected.") { model.changeGateway(it) }
    if (IsMacDesktop) Note("macOS per-app routing handles IPv4 TCP. It does not block UDP or QUIC; a browser may use those connections directly.")
    else ToggleBlock("Block QUIC", QuicDetail, model.quicBlocked, note = QuicNote) { model.changeQuic(it) }
}

@Composable
private fun NewAutomation(shell: Shell) {
    val model = settingsModel(shell, SettingsCategory.Automation) { AutomationSettingsModel(shell) }
    model.options().forEachIndexed { index, option -> ToggleBlock(option.title, option.detail, option.value, first = index == 0) { option.set(it) } }
    Text("Custom servers do not switch automatically. Failover and fastest-on-connect only change the main server, and a main-server switch restarts every route briefly.",
        color = Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
}

@Composable
private fun NewUpdates(shell: Shell) {
    if (!shell.controller.capabilities().updates()) {
        UnavailableFeature("Updates", "Install a newer signed app manually.")
        return
    }
    val model = settingsModel(shell, SettingsCategory.Updates) { UpdateSettingsModel(shell) }
    UpdateEffects(model)
    SettingBlock(model.headline, first = true) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val ready = model.available != null || model.downloaded != null
            Box(Modifier.size(64.dp).background(if (ready) Cyan else NeutralOverlay.copy(.08f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (ready) Icons.Rounded.Download else Icons.Rounded.CheckCircle, null, tint = if (ready) Color.White else Cyan, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(18.dp))
            Column {
                Text(model.version, color = White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                Text("Installed: ${app.perroute.service.BuildIdentity.displayVersion()}", color = Muted, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (model.available != null && model.downloaded == null) PrimaryButton(if (model.downloading) "Downloading…" else "Download ${model.available}",
                Modifier.width(220.dp), icon = Icons.Rounded.Download, enabled = !model.downloading, height = 44.dp) { model.download() }
            SecondaryButton(if (shell.busy) "Checking…" else "Check for updates", icon = Icons.Rounded.Refresh, enabled = !shell.busy, height = 44.dp) { model.check() }
        }
        Spacer(Modifier.height(12.dp))
        Note("Only signed HTTPS updates are accepted. Every download is checked by size and SHA-256 before it can be installed.")
        Spacer(Modifier.height(6.dp))
        Text(model.statusLine, color = if (model.downloading) Cyan else Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
        RollbackNote(shell)
    }
    ToggleBlock("Update automatically", AutoUpdateDetail, model.auto) { model.changeAuto(it) }
    SettingBlock("Signed update feed") {
        Spacer(Modifier.height(10.dp))
        TextInput(model.feed, { model.changeFeed(it) }, "Official PerRoute releases", height = 46.dp)
        Spacer(Modifier.height(6.dp))
        Text(UpdateFeedNote, color = Muted, fontSize = 12.5.sp)
    }
    if (model.downloading || model.downloaded != null) SettingBlock(if (model.downloaded != null) "Downloaded" else "Downloading", trailing = {
        Text(if (model.downloaded != null) "100%" else "${model.progress}%", color = White, fontSize = 15.sp)
    }) {
        Spacer(Modifier.height(10.dp))
        ProgressBar(if (model.downloaded != null) 1f else model.progress / 100f, NewColors.Lime.copy(alpha = 1f).let { if (IsDark) it else Color(0xFF9DBB12) }, Modifier.fillMaxWidth(), 8.dp)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Install update", Modifier.width(220.dp), enabled = model.downloaded != null, height = 46.dp) { model.install() }
            Spacer(Modifier.width(16.dp))
            if (model.downloaded == null) Text("Download must finish first.", color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun NewComponents(shell: Shell) {
    val model = settingsModel(shell, SettingsCategory.Components) { ComponentSettingsModel(shell) }
    LaunchedEffect(Unit) { if (model.environment == null) model.reload() }
    SettingBlock("Component status", "Check that all components are installed and working correctly.", first = true) {
        Spacer(Modifier.height(14.dp))
        val state = model.environment
        if (state == null) LoadingState("Checking installed components…", Modifier.fillMaxWidth())
        else {
            Divider()
            ComponentRow("Xray core", state.coreReady, "Powers the proxy connections.", if (state.coreReady || !shell.controller.capabilities().coreInstallation()) null else "Install", model.canInstall, model) { model.installCore() }
            Divider()
            ComponentRow(model.routingName, state.perAppReady, model.routingDetail, null, false, model) {}
        }
        if (model.progress.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(model.progress, color = Amber, fontSize = 12.5.sp) }
    }
    Note(model.routingNote)
}

@Composable
private fun ComponentRow(title: String, ready: Boolean, detail: String, action: String?, actionEnabled: Boolean, model: ComponentSettingsModel, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(42.dp).background(if (ready) Cyan else Amber, CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (ready) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber, null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(if (ready) "Installed" else "Not installed", color = if (ready) Cyan else Amber, fontSize = 14.sp)
            Text(detail, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        if (action != null) PrimaryButton(action, enabled = actionEnabled, icon = Icons.Rounded.Download, onClick = onAction)
        else SecondaryButton(if (model.loading) "Checking…" else "Check again", Modifier.width(160.dp), enabled = !model.loading) { model.reload() }
    }
}

@Composable
private fun NewBackup(shell: Shell, model: BackupModel) {
    if (!shell.controller.capabilities().backupRestore()) {
        UnavailableFeature("Backup and restore", "Portable backup and restore needs platform-aware application mapping.")
        return
    }
    SettingBlock("Create a backup", "Select what to include in the backup file.", first = true) {
        Spacer(Modifier.height(10.dp))
        val sections = BackupService.Section.entries
        Row(Modifier.fillMaxWidth(if (LocalCompact.current) 1f else .85f)) {
            listOf(sections.take(3), sections.drop(3)).forEach { column ->
                Column(Modifier.weight(1f)) {
                    column.forEach { section ->
                        val checked = model.sections[section] == true
                        Tooltip(section.detail()) {
                            Row(Modifier.toggleable(checked, role = Role.Checkbox) { model.sections[section] = it }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                CheckMark(checked)
                                Spacer(Modifier.width(12.dp))
                                Text(section.title(), color = White, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    SettingBlock("Encrypt with password", if (model.encrypt) "Required to restore this file. AES-256-GCM with a key derived from the password (PBKDF2, 600,000 rounds); the password cannot be recovered."
        else "Not encrypted: anyone with the file can read and use your server credentials.", trailing = {
        ToggleSwitch(model.encrypt, "Encrypt the backup with a password") { model.encrypt = it }
    }) {
        if (model.encrypt) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f)) { FieldLabel("Password"); PasswordField(model.password, { model.password = it }, "Password") }
                Column(Modifier.weight(1f)) { FieldLabel("Repeat password"); PasswordField(model.confirm, { model.confirm = it }, "Repeat password") }
            }
            model.passwordProblem?.takeIf { model.password.isNotEmpty() || model.confirm.isNotEmpty() }?.let {
                Spacer(Modifier.height(6.dp)); Text(it, color = Amber, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        PrimaryButton("Create backup", Modifier.width(260.dp), enabled = model.canCreate, height = 46.dp) { model.create() }
        Spacer(Modifier.height(6.dp))
        Text("A .prb file will be created with your selected settings.", color = Muted, fontSize = 12.5.sp)
    }
    SettingBlock("Restore a backup", "Choose a file, then review changes. Nothing changes until you confirm.") {
        if (shell.locked) { Spacer(Modifier.height(8.dp)); NewLockNote("Disconnect to restore a backup. Creating one works at any time.") }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            SecondaryButton("Choose backup…", Modifier.width(220.dp), enabled = !shell.locked && !shell.busy, height = 46.dp) { model.choose() }
            Column {
                SecondaryButton("Undo last restore", Modifier.width(220.dp), enabled = model.hasSnapshot && !shell.locked && !shell.busy, height = 46.dp) { model.undo() }
                if (!model.hasSnapshot) { Spacer(Modifier.height(4.dp)); Text("No restore to undo.", color = Muted, fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("A rollback copy is created before restore, and the restore is applied in one step: if it fails, everything is put back.", color = Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun NewActivityLog(shell: Shell) {
    Text("Up to 200 recent events. Select text and press $CopyShortcut, or copy everything.", color = Muted, fontSize = 13.sp)
    Spacer(Modifier.height(12.dp))
    if (shell.logs.isEmpty()) { EmptyState(Icons.Rounded.Description, "Nothing logged yet", "Connection events, test results and parser warnings appear here.", Modifier.fillMaxWidth()); return }
    NewTableHeader {
        HeaderText("Time", Modifier.width(120.dp))
        HeaderText("Event", Modifier.weight(1f))
    }
    SelectionContainer {
        Column {
            shell.logs.takeLast(200).asReversed().forEach { raw ->
                val line = parseLog(raw)
                Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(line.time.ifBlank { "—" }, color = White, fontSize = 14.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(92.dp))
                    Box(Modifier.width(28.dp)) { if (line.warning) Icon(Icons.Rounded.WarningAmber, "Problem", tint = Amber, modifier = Modifier.size(19.dp)) }
                    Text(line.text, color = White, fontSize = 13.5.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
                }
                Divider()
            }
        }
    }
}

@Composable
private fun NewRestoreReview(shell: Shell, backup: BackupModel, session: RestoreSession) {
    val preview = session.preview
    NewSplitPage(
        panel = { NewRestorePlan(session) },
        bottomBar = {
            SecondaryButton("Back", Modifier.width(160.dp), icon = Icons.AutoMirrored.Rounded.ArrowBack, height = 48.dp) { backup.finishRestore() }
            Spacer(Modifier.weight(1f))
            SecondaryButton("Cancel", Modifier.width(170.dp), height = 48.dp) { backup.finishRestore() }
            PrimaryButton("Restore backup", Modifier.width(240.dp), icon = Icons.Rounded.Restore, enabled = !shell.locked && !shell.busy, height = 48.dp) {
                session.restore { backup.finishRestore() }
            }
        }
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            NewHeading(listOf("Settings", "Backup & Restore", "Review"), "Review backup", "Review the contents of this backup before restoring.")
            if (shell.locked) { Spacer(Modifier.height(10.dp)); NewLockNote("Disconnect to restore a backup.") }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(6.dp)).border(1.dp, Line, RoundedCornerShape(6.dp)).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Description, null, tint = White, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1.3f)) {
                    EllipsisText(session.request.file.fileName.toString(), White, 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(session.application, color = Muted, fontSize = 12.5.sp)
                }
                Column(Modifier.weight(1f)) { Text("Backup created", color = Muted, fontSize = 12.sp); Text(session.created, color = White, fontSize = 13.5.sp) }
                Column(Modifier.weight(.7f)) { Text("Backup size", color = Muted, fontSize = 12.sp); Text(session.size, color = White, fontSize = 13.5.sp) }
            }
            Spacer(Modifier.height(22.dp))
            Text("What's included", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("This backup contains the following items.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            val counts = preview.counts()
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                listOf(counts.subscriptions() to "Subscriptions", counts.servers() to "Servers", counts.applications() to "App assignments",
                    counts.rules() to "Rules", counts.profiles() to "Profiles").forEachIndexed { index, (count, label) ->
                    if (index > 0) Divider(Modifier.fillMaxHeight().padding(horizontal = 0.dp), vertical = true)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("$count", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(label, color = Muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Divider(Modifier.fillMaxHeight(), vertical = true)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(if (counts.preferences() > 0) "Included" else "Not included", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Preferences", color = Muted, fontSize = 12.5.sp)
                }
            }
            preview.warnings().forEach { warning -> Spacer(Modifier.height(10.dp)); InfoBanner(warning, Modifier.fillMaxWidth(), Amber, Icons.Rounded.WarningAmber) }
            if (preview.missingPaths().isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Divider()
                Spacer(Modifier.height(18.dp))
                Text("App path remapping", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Some application paths from this backup were not found on this system. Choose where they are, or they are skipped.", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                NewTableHeader {
                    HeaderText("Application", Modifier.weight(.8f))
                    HeaderText("Original path (from backup)", Modifier.weight(1.4f))
                    HeaderText("Status", Modifier.width(80.dp))
                    HeaderText("New path (on this system)", Modifier.weight(1.6f))
                }
                preview.missingPaths().forEach { issue ->
                    val replacement = session.remap[issue.originalPath()]
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(.8f), verticalAlignment = Alignment.CenterVertically) {
                            AppAvatar(fileName(issue.originalPath()), "", 24.dp); Spacer(Modifier.width(8.dp))
                            EllipsisText(appName(issue.originalPath()), White, 13.sp, fontWeight = FontWeight.Medium)
                        }
                        EllipsisText(issue.originalPath(), White.copy(.85f), 12.sp, Modifier.weight(1.4f))
                        Text("Missing", color = Amber, fontSize = 12.5.sp, modifier = Modifier.width(80.dp))
                        Column(Modifier.weight(1.6f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                EllipsisText(replacement ?: "Will be skipped", if (replacement != null) White else Muted, 11.5.sp, Modifier.weight(1f))
                                if (replacement != null) { Spacer(Modifier.width(6.dp)); Icon(Icons.Rounded.CheckCircle, null, tint = Cyan, modifier = Modifier.size(15.dp)); Text(" Remapped", color = Cyan, fontSize = 11.5.sp) }
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryButton("Choose path…", height = 32.dp) { session.choosePath(issue.originalPath()) }
                                SecondaryButton("Skip", height = 32.dp, enabled = replacement != null) { session.skip(issue.originalPath()) }
                            }
                        }
                    }
                    Divider()
                }
            }
            Spacer(Modifier.height(22.dp))
            Divider()
            Spacer(Modifier.height(18.dp))
            Text("Restore options", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Choose how to apply this backup to your current setup.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            RestoreOption(session.mode == BackupService.Mode.MERGE, "Merge (recommended)",
                "Combine backup with this setup. Everything you already have is kept, including your preferences; new items are added.") { session.mode = BackupService.Mode.MERGE }
            RestoreOption(session.mode == BackupService.Mode.REPLACE, "Replace",
                "Replace the parts this backup contains with its versions. Parts it does not contain stay as they are.") { session.mode = BackupService.Mode.REPLACE }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun RestoreOption(selected: Boolean, title: String, detail: String, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().focusRing(RoundedCornerShape(4.dp)).selectable(selected, role = Role.RadioButton, onClick = onSelect)
        .pointerHoverIcon(PointerIcon.Hand).padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        RadioMark(selected, 22.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = White, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun ColumnScope.NewRestorePlan(session: RestoreSession) {
    val preview = session.preview
    SectionLabel("Restore plan", White.copy(.85f))
    Spacer(Modifier.height(18.dp))
    PlanItem(Icons.Rounded.CheckCircle, NewColors.Lime, if (preview.compatibility() == BackupService.Compatibility.COMPATIBLE) "Compatible version" else "Upgraded from an older backup",
        "Backup was created with ${session.application}" + if (preview.compatibility() == BackupService.Compatibility.COMPATIBLE) " and is compatible." else " and was converted for this version.")
    PlanItem(Icons.Rounded.Lock, NewColors.Lime, if (preview.header().encrypted()) "Encrypted and unlocked" else "Not encrypted",
        if (preview.header().encrypted()) "This backup is encrypted and was successfully unlocked." else "Anyone with this file could read it.")
    PlanItem(Icons.Rounded.CheckCircle, NewColors.Lime, "Checksum valid", "The backup file is intact and has passed verification.")
    Spacer(Modifier.height(4.dp))
    Divider()
    Spacer(Modifier.height(14.dp))
    PlanItem(Icons.Rounded.Dns, White, plural(session.newServers, "new server"), "These servers will be added to your setup.")
    PlanItem(Icons.Rounded.Dns, White, "${plural(preview.serversAlreadyPresent(), "server")} already present", "These servers already exist and will be kept.")
    if (preview.missingPaths().isNotEmpty()) {
        val remapped = session.remap.size
        PlanItem(Icons.Rounded.GridView, White, "${plural(remapped, "app path")} remapped", if (preview.missingPaths().size > remapped)
            "${preview.missingPaths().size - remapped} not found and will be skipped." else "Every missing application has a new path.")
    }
    if (preview.profilesWithSameName() > 0) PlanItem(Icons.Rounded.Apps, White, "${plural(preview.profilesWithSameName(), "profile")} with a name in use", "Kept as they are.")
    Spacer(Modifier.height(4.dp))
    Divider()
    Spacer(Modifier.height(14.dp))
    PlanItem(Icons.Rounded.Info, White, "A rollback snapshot will be created.", "An automatic snapshot of your current setup is taken before restoring. Undo is available after restore.")
}

@Composable
private fun PlanItem(icon: ImageVector, tint: Color, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = White.copy(.8f), fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}
