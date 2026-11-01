package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.perroute.backup.BackupCodec
import app.perroute.backup.BackupService
import app.perroute.model.ConnectionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.FileDialog
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal enum class SettingsCategory(val label: String, val icon: ImageVector, val subtitle: String) {
    General("General", Icons.Rounded.Tune, "Appearance and app information."),
    Connection("Connection", Icons.Rounded.Wifi, "Mode and routing for this device."),
    Routing("Routing", Icons.Rounded.AltRoute, "Measure quality and keep individual routes working."),
    Automation("Automation", Icons.Rounded.Sync, "Small automations for a more reliable connection."),
    Updates("Updates", Icons.Rounded.SystemUpdateAlt, "Keep the app current with signed releases."),
    Components("Components", Icons.Rounded.Inventory2, "The components behind your routes."),
    Backup("Backup & Restore", Icons.Rounded.Backup, "Keep a copy of your setup."),
    LocalNetwork("Local network", Icons.Rounded.Router, "Printers, NAS and other devices near you."),
    Recovery("Recovery", Icons.Rounded.SettingsBackupRestore, "Undo PerRoute's network changes, or go back a version."),
    ActivityLog("Activity log", Icons.AutoMirrored.Rounded.Article, "Recent events from your setup.")
}

internal inline fun <reified T : Any> settingsModel(shell: Shell, category: SettingsCategory, noinline create: () -> T): T =
    shell.screen("${Tab.Settings.name}:${category.name}") { create() }

internal val OfferedModes = ConnectionMode.values().filter { it != ConnectionMode.TUN_COMPATIBILITY }

internal fun modeTitle(mode: ConnectionMode): String = modeLabel(mode)
internal fun modeChoiceDetail(mode: ConnectionMode): String = when (mode) {
    ConnectionMode.NATIVE_PER_APP -> "Only selected apps use the proxy."
    ConnectionMode.BROWSER_PROXY -> "Configure the proxy endpoints inside your apps."
    ConnectionMode.SYSTEM_PROXY -> "Apps using the system proxy settings."
    ConnectionMode.TUN_COMPATIBILITY -> "Broad compatibility; requires Administrator."
}

internal class ConnectionSettingsModel(private val shell: Shell) {
    var environment by mutableStateOf<EnvironmentState?>(null)
    suspend fun load() {
        environment = withContext(Dispatchers.IO) { runCatching { shell.controller.environment() }.getOrDefault(EnvironmentState(false, false, false)) }
    }
    val missingRoutingDetail: String get() = if (shell.controller.capabilities().coreInstallation())
        "Needs the per-app driver from the Full Installer. See Components."
        else "Needs the matched routing helper and engine. See Components."
    fun routingStatus(missing: Boolean): String = if (shell.controller.capabilities().coreInstallation())
        if (missing) "Driver missing" else "Driver installed"
        else if (missing) "Helper unavailable" else "Helper bundled"
    fun driverMissing(mode: ConnectionMode) = mode == ConnectionMode.NATIVE_PER_APP && environment?.perAppReady == false
    fun canChoose(mode: ConnectionMode) = shell.controller.capabilities().supports(mode) && !shell.locked && !driverMissing(mode) && !shell.busy
    fun choose(mode: ConnectionMode) { if (!canChoose(mode)) return; shell.controller.setMode(mode); shell.refresh() }
    fun setAdvanced(enabled: Boolean) {
        val result = shell.controller.setAdvancedRouting(enabled)
        shell.refresh()
        shell.draft.load(shell)
        shell.notify(result)
    }
    fun assignments(): List<Pair<String, String>> {
        val auto = shell.controller.autoApps()
        val main = shell.mainNode?.let(::serverName) ?: "No main server"
        return shell.apps.map { path ->
            val key = app.perroute.model.AppRoutes.normalize(path)
            val routed = shell.advanced
            path to when {
                routed && key in auto -> {
                    val decision = shell.controller.autoDecision(path)?.decision?.nodeKey()
                    val name = decision?.let { k -> shell.nodes.firstOrNull { it.key() == k } }?.let(::serverName) ?: "Chosen when connected"
                    "$name (Auto)"
                }
                routed && shell.appRoutes.containsKey(key) -> {
                    val nodeKey = shell.appRoutes[key]
                    val name = shell.nodes.firstOrNull { it.key() == nodeKey }?.let(::serverName)
                        ?: "Missing: ${shell.controller.appRouteLabel(nodeKey.orEmpty()).ifBlank { "removed server" }}"
                    "$name (Custom)"
                }
                else -> "$main (Main)"
            }
        }
    }
    fun assignmentNote(): String = when {
        shell.locked -> "Connection is active. Disconnect to change the mode or server assignments."
        shell.appRoutes.isEmpty() -> "No custom assignments saved yet."
        else -> "${plural(shell.appRoutes.size, "custom assignment")} saved" + if (shell.advanced) "." else " and currently ignored."
    }
    fun openApp(path: String) { shell.navigate(Tab.Apps); if (shell.tab == Tab.Apps) shell.draft.focused = path }
}

internal const val QuicDetail = "Routed apps use HTTPS over TCP instead of QUIC. Downloads and YouTube are usually faster this way, because QUIC sent through a server's TCP connection slows down."
internal const val QuicNote = "Recommended. Applies from the next connection; apps switch to TCP by themselves."

internal class RoutingSettingsModel(private val shell: Shell) {
    private val controller = shell.controller
    var background by mutableStateOf(controller.qualityBackground())
    var monitoring by mutableStateOf(controller.routeMonitoring())
    var gateway by mutableStateOf(controller.trafficGateway())
    var quicBlocked by mutableStateOf(controller.quicBlocked())
    var margin by mutableStateOf(controller.autoSwitchMargin())
    var cooldown by mutableStateOf(controller.autoCooldownMinutes())
    val margins = listOf(5, 10, 15, 20)
    val cooldowns = listOf(5, 10, 20, 30)

    fun changeBackground(on: Boolean) { background = on; controller.setQualityBackground(on) }
    fun changeMonitoring(on: Boolean) { monitoring = on; controller.setRouteMonitoring(on) }
    fun changeQuic(on: Boolean) { quicBlocked = on; shell.notify(controller.setQuicBlocked(on)) }
    fun changeMargin(value: Int) { margin = value; controller.setAutoSwitchMargin(value) }
    fun changeCooldown(value: Int) { cooldown = value; controller.setAutoCooldownMinutes(value) }
    fun changeGateway(on: Boolean) {
        val result = controller.setTrafficGateway(on)
        if (result.ok) gateway = on
        shell.notify(result)
    }
    fun marginShown() = margin.takeIf { it in margins } ?: 10
    fun cooldownShown() = cooldown.takeIf { it in cooldowns } ?: 10
    fun lastPass(): String = controller.qualityLastPass().takeIf { it > 0 }?.let { "Last pass ${formatAgo(it)}." } ?: "No pass yet."
}

internal class AutomationSettingsModel(private val shell: Shell) {
    private val controller = shell.controller
    var autoReconnect by mutableStateOf(controller.autoReconnect())
    var autoFailover by mutableStateOf(controller.autoFailover())
    var killSwitch by mutableStateOf(controller.killSwitch())
    var fastestOnConnect by mutableStateOf(controller.fastestOnConnect())
    var autoSpeedTest by mutableStateOf(controller.autoSpeedTest())
    var adaptive by mutableStateOf(controller.adaptiveResources())

    class Option(val title: String, val detail: String, val value: Boolean, val set: (Boolean) -> Unit)

    fun options(): List<Option> = listOf(
        Option("Autoreconnect", "Reconnect after sleep, network changes or an unexpected core stop.", autoReconnect) { autoReconnect = it; controller.setAutoReconnect(it) },
        Option("Main server failover", "After three failed checks, switch the main server to the fastest reachable one.", autoFailover) { autoFailover = it; controller.setAutoFailover(it) },
        Option("Per-app killswitch", "Keep selected apps offline while their tunnel is unavailable.", killSwitch) { killSwitch = it; controller.setKillSwitch(it) },
        Option("Fastest main server on connect", "Measure reachable servers and use the lowest latency as the main server when connecting.", fastestOnConnect) { fastestOnConnect = it; controller.setFastestOnConnect(it) },
        Option("Automatic speed tests", "After pinging, measure the download speed of the 10 best servers (up to 10 MB each).", autoSpeedTest) { autoSpeedTest = it; controller.setAutoSpeedTest(it) },
        Option("Save resources on battery and metered networks", AdaptiveResourcesDetail + resourceNow(), adaptive) { adaptive = it; shell.notify(controller.setAdaptiveResources(it)) }
    ).filter { option ->
        (option.title != "Per-app killswitch" || controller.perAppKillSwitchSupported()) &&
            (option.title != "Save resources on battery and metered networks" || controller.capabilities().networkRecovery())
    }

    private fun resourceNow(): String {
        val now = controller.resourceConditions()
        return when {
            !now.constrained() -> ""
            adaptive -> " Now: saving resources, ${now.reason()}."
            else -> " PerRoute is ${now.reason()}."
        }
    }
}

internal const val AdaptiveResourcesDetail = "Background server checks run hourly and on fewer servers, automatic speed tests pause, and updates wait for an unmetered network. Routes, route recovery and the kill switch are never weakened."

internal const val ExplorerMenuDetail = "Right-click a program or its shortcut in Explorer and choose Route with PerRoute to route it, without opening Apps. On Windows 11 it is under Show more options."

internal class ExplorerMenuModel(private val shell: Shell) {
    val available = shell.controller.explorerMenuAvailable()
    var enabled by mutableStateOf(shell.controller.explorerMenu())

    fun change(on: Boolean) {
        enabled = on
        shell.run(silent = false) { shell.controller.setExplorerMenu(on) }
    }
}

internal fun explorerMenuModel(shell: Shell): ExplorerMenuModel = shell.screen("${Tab.Settings.name}:explorer") { ExplorerMenuModel(shell) }

internal const val AutoUpdateDetail = "New versions are downloaded and verified in the background. PerRoute asks before it restarts to install them."

internal const val UpdateFeedNote = "Leave empty to get updates from the official PerRoute releases, or enter a feed of your own. Only manifests signed with PerRoute's offline Ed25519 release key are accepted, and every download is checked by size and SHA-256 before it can be installed."

internal class UpdateSettingsModel(private val shell: Shell) {
    private val controller = shell.controller
    var feed by mutableStateOf(controller.updateFeedUrl())
    var available by mutableStateOf(controller.availableUpdateVersion())
    var downloading by mutableStateOf(controller.isUpdateDownloading())
    var progress by mutableStateOf(controller.updateDownloadProgress())
    var message by mutableStateOf(controller.updateDownloadMessage())
    var downloaded by mutableStateOf(controller.downloadedUpdatePath())
    var auto by mutableStateOf(controller.autoUpdate())

    fun poll() {
        available = controller.availableUpdateVersion()
        downloading = controller.isUpdateDownloading()
        progress = controller.updateDownloadProgress()
        message = controller.updateDownloadMessage()
        downloaded = controller.downloadedUpdatePath()
    }

    fun changeAuto(on: Boolean) { auto = on; shell.notify(controller.setAutoUpdate(on)) }
    fun changeFeed(value: String) { feed = value; controller.setUpdateFeedUrl(value) }
    fun check() = shell.run { controller.checkForUpdate() }
    fun download() = shell.notify(controller.downloadAvailableUpdateInBackground())
    fun install() {
        val result = controller.launchDownloadedUpdate()
        shell.notify(result)
        if (result.ok) shell.exitApp()
    }

    val headline: String get() = when {
        downloaded != null -> "Update ready to install"
        downloading -> "Downloading update"
        available != null -> "Update available"
        else -> "PerRoute is up to date"
    }
    val version: String get() = available ?: app.perroute.service.SecureUpdateService.CURRENT_VERSION
    val statusLine: String get() = when {
        downloading -> "Downloading in the background. You can leave this page or minimize PerRoute."
        message.isNotBlank() -> message
        available != null -> "PerRoute $available is available."
        else -> "You are running PerRoute ${app.perroute.service.SecureUpdateService.CURRENT_VERSION}."
    }
}

@Composable
internal fun UpdateEffects(model: UpdateSettingsModel) {
    LaunchedEffect(Unit) { while (isActive) { model.poll(); delay(750) } }
}

internal class ComponentSettingsModel(private val shell: Shell) {
    var environment by mutableStateOf<EnvironmentState?>(null)
    var progress by mutableStateOf("")
    var loading by mutableStateOf(false)

    fun reload() {
        shell.scope.launch {
            loading = true
            try { environment = withContext(Dispatchers.IO) { runCatching { shell.controller.environment() }.getOrDefault(EnvironmentState(false, false, false)) } }
            finally { loading = false }
        }
    }

    fun installCore() {
        progress = "Preparing…"
        shell.run(done = { progress = ""; reload() }) {
            shell.controller.installCore { update -> EventQueue.invokeLater { progress = update } }
        }
    }

    private val mac: Boolean get() = System.getProperty("os.name") == "Mac OS X"
    val routingName: String get() = if (mac) "Per-app routing helper" else "Per-app driver (ProxiFyre)"
    val routingDetail: String get() = if (mac) "Routes explicitly selected executable paths. Setup may require administrator approval." else "Required for Smart per-app mode. The Full Installer sets it up with one Administrator approval."
    val routingNote: String get() = if (mac) "Per-app routing currently supports IPv4 TCP. Safari shared WebKit, UDP and IPv6 need separate support." else "Smart per-app needs both the core and the per-app driver. Other connection modes use only the core."
    val canInstall: Boolean get() = shell.controller.capabilities().coreInstallation() && !shell.busy && !shell.locked
}

internal fun confirmReset(shell: Shell) {
    shell.showDialog {
        ConfirmDialog(
            "Reset PerRoute?",
            "This removes all servers and subscriptions, the selected apps and their routes, rules, profiles and presets, " +
                "the traffic history and every setting, and returns PerRoute to how it was when first installed. " +
                "It cannot be undone. Installed components are not affected.",
            "Reset everything", shell::closeDialog, danger = true
        ) {
            shell.closeDialog()
            shell.run(done = { result ->
                if (result.ok) {
                    shell.logs.clear()
                    Appearance.load(shell.controller)
                    shell.draft.load(shell)
                }
            }) { shell.controller.resetAll() }
        }
    }
}

internal class LogLine(val time: String, val text: String) {
    val warning: Boolean get() = Regex("(?i)\\b(fail|failed|failure|error|could not|cannot|timed out|timeout|unavailable|not enforced)\\b").containsMatchIn(text)
}

internal fun parseLog(line: String): LogLine {
    val match = Regex("^\\[(\\d{2}:\\d{2}:\\d{2})]\\s*(.*)$").find(line)
    return if (match != null) LogLine(match.groupValues[1], match.groupValues[2]) else LogLine("", line)
}

internal class BackupModel(private val shell: Shell) {
    private val controller = shell.controller
    val sections = mutableStateMapOf<BackupService.Section, Boolean>().apply { BackupService.Section.entries.forEach { put(it, true) } }
    var encrypt by mutableStateOf(true)
    var password by mutableStateOf("")
    var confirm by mutableStateOf("")
    var hasSnapshot by mutableStateOf(controller.hasRestoreSnapshot())
    var restore by mutableStateOf<RestoreSession?>(null)

    val chosen: Set<BackupService.Section> get() = sections.filterValues { it }.keys
    val passwordProblem: String? get() = when {
        !encrypt -> null
        password.length < BackupCodec.MIN_PASSWORD_LENGTH -> "Use at least ${BackupCodec.MIN_PASSWORD_LENGTH} characters."
        password != confirm -> "The passwords do not match."
        else -> null
    }
    val canCreate: Boolean get() = chosen.isNotEmpty() && passwordProblem == null && !shell.busy

    fun create() {
        val owner = shell.hostWindow ?: return
        val dialog = FileDialog(owner, "Save PerRoute backup", FileDialog.SAVE).apply {
            file = "perroute-backup-${LocalDate.now()}.prb"
            isVisible = true
        }
        val name = dialog.file ?: return
        val target = Path.of(dialog.directory, if (name.endsWith(".prb", true)) name else "$name.prb")
        val secret = if (encrypt) password.toCharArray() else null
        val sectionsToSave = chosen.toSet()
        shell.run { controller.exportBackup(target, sectionsToSave, secret) }
        password = ""; confirm = ""
    }

    fun choose() {
        val owner = shell.hostWindow ?: return
        val dialog = FileDialog(owner, "Choose a PerRoute backup", FileDialog.LOAD).apply {
            filenameFilter = java.io.FilenameFilter { _, file -> file.endsWith(".prb", true) }
            isVisible = true
        }
        val name = dialog.file ?: return
        open(Path.of(dialog.directory, name))
    }

    fun open(file: Path) {
        val header = runCatching { BackupCodec.header(Files.readAllBytes(file)) }
        if (header.isFailure) { shell.notify(ActionResult(false, header.exceptionOrNull()?.message ?: "This is not a PerRoute backup.")); return }
        fun read(password: CharArray?) {
            shell.scope.launch {
                shell.busy = true
                val (request, error) = try { withContext(Dispatchers.IO) { controller.openBackup(file, password) } } finally { shell.busy = false }
                if (request != null) restore = RestoreSession(shell, request)
                else shell.notify(ActionResult(false, error ?: "The backup could not be opened."))
            }
        }
        if (header.getOrThrow().encrypted()) {
            val text = mutableStateOf("")
            shell.showDialog {
                DialogCard("Backup password", "${file.fileName} is encrypted. Enter the password used when it was created.", shell::closeDialog, width = 460.dp,
                    actions = {
                        SecondaryButton("Cancel") { shell.closeDialog() }
                        PrimaryButton("Open", enabled = text.value.isNotEmpty()) { val secret = text.value.toCharArray(); text.value = ""; shell.closeDialog(); read(secret) }
                    }) { PasswordField(text.value, { text.value = it }, "Password") }
            }
        } else read(null)
    }

    fun undo() {
        shell.showDialog {
            ConfirmDialog("Undo the last restore?", "Your servers, apps, rules, profiles and preferences go back to how they were right before the last restore.",
                "Undo restore", shell::closeDialog) {
                shell.closeDialog()
                shell.run(done = { result -> if (result.ok) { hasSnapshot = controller.hasRestoreSnapshot(); shell.draft.load(shell); Appearance.load(controller) } }) { controller.undoRestore() }
            }
        }
    }

    fun finishRestore() { restore = null; hasSnapshot = controller.hasRestoreSnapshot() }
}

internal class RestoreSession(private val shell: Shell, val request: RestoreRequest) {
    val preview: BackupService.RestorePreview = request.preview
    var mode by mutableStateOf(BackupService.Mode.MERGE)
    val remap = mutableStateMapOf<String, String>().apply { preview.missingPaths().forEach { issue -> issue.suggestion()?.let { put(issue.originalPath(), it) } } }
    val created: String = Instant.ofEpochMilli(preview.header().createdAt()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm"))
    val size: String = runCatching { formatBytes(Files.size(request.file)) }.getOrDefault("—")
    val application: String = preview.header().application().ifBlank { "PerRoute" }
    val newServers: Int get() = (preview.counts().servers() - preview.serversAlreadyPresent()).coerceAtLeast(0)

    fun choosePath(original: String) {
        val owner = shell.hostWindow ?: return
        val dialog = FileDialog(owner, "Where is ${fileName(original)}?", FileDialog.LOAD).apply {
            filenameFilter = java.io.FilenameFilter { _, name -> name.endsWith(".exe", true) }
            isVisible = true
        }
        dialog.file?.let { remap[original] = Path.of(dialog.directory, it).toAbsolutePath().normalize().toString() }
    }

    fun skip(original: String) { remap.remove(original) }

    fun restore(done: () -> Unit) {
        val choices = HashMap(remap)
        val chosenMode = mode
        shell.run(done = { result -> if (result.ok) { shell.draft.load(shell); Appearance.load(shell.controller) }; done() }) {
            shell.controller.restoreBackup(request, chosenMode, choices)
        }
    }
}

internal fun backupModel(shell: Shell): BackupModel = settingsModel(shell, SettingsCategory.Backup) { BackupModel(shell) }

internal val IsMacDesktop: Boolean get() = app.perroute.platform.bootstrap.PlatformBootstrap.isMac(System.getProperty("os.name"))
internal val CopyShortcut: String get() = if (IsMacDesktop) "⌘C" else "Ctrl+C"
internal val WindowBehaviorDetail: String get() = if (IsMacDesktop)
    "Closing the window keeps PerRoute running. Reopen it from the Dock. Use PerRoute → Quit PerRoute to exit and restore your connection settings."
    else "Closing or minimizing keeps PerRoute in the notification area. Use Quick Access there to connect, switch the main server, or quit."

@Composable
internal fun UnavailableFeature(title: String, detail: String) {
    InfoBanner("$title is not available on this platform yet. $detail")
}
