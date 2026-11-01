package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.foundation.border
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.ApplicationIdentity
import app.perroute.model.AppRoutes
import app.perroute.model.BandwidthPolicy
import app.perroute.model.ProxyNode
import app.perroute.routing.AppRouteMode
import app.perroute.routing.AutoCandidatePool
import app.perroute.routing.AutoServerSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.nio.file.Path

internal enum class AppListSource(val label: String) { INSTALLED("Installed"), RUNNING("Running") }

internal data class AppChoice(val name: String, val path: String) {
    val title: String get() = name.removeSuffix(".exe")
}

internal val RouteModeOrder = listOf(AppRouteMode.MAIN, AppRouteMode.MANUAL, AppRouteMode.AUTO)

internal class DraftRoute(val mode: AppRouteMode, val assignedKey: String?, val node: ProxyNode?, val label: String, val pending: Boolean) {
    val followsMain get() = mode == AppRouteMode.MAIN
    val missing get() = mode == AppRouteMode.MANUAL && assignedKey != null && node == null
}

internal fun draftRoute(shell: Shell, path: String): DraftRoute {
    val key = shell.draft.routes[AppRoutes.normalize(path)]
    val node = key?.let { wanted -> shell.nodes.firstOrNull { it.key() == wanted } }
    val mode = shell.draft.mode(path)
    return DraftRoute(mode, key, node, node?.let(::serverName) ?: key?.let(shell.controller::appRouteLabel).orEmpty(),
        mode == AppRouteMode.MANUAL && key == null)
}

@Composable
internal fun rememberAutoDecision(shell: Shell, path: String): Pair<AutoServerSelector.Decision, AutoCandidatePool> {
    val pool = AutoCandidatePool.decode(shell.draft.pools[AppRoutes.normalize(path)])
    val decision = remember(path, pool, shell.nodes.size, shell.activeRoutes) { shell.controller.previewAutoDecision(path, pool) }
    return decision to pool
}

internal class RouteCellText(val mode: String, val server: String, val warning: Boolean, val node: ProxyNode?, val kind: RouteKindLabel?)

@Composable
internal fun routeCellText(shell: Shell, path: String): RouteCellText {
    if (!shell.draft.isSelected(path)) return RouteCellText("Direct", "", false, null, null)
    val main = shell.mainNode
    if (!shell.draft.advanced) return RouteCellText("Main server", main?.let(::serverName) ?: "No main server", main == null, main, RouteKindLabel.MAIN)
    val route = draftRoute(shell, path)
    return when (route.mode) {
        AppRouteMode.MAIN -> RouteCellText("Main server", main?.let(::serverName) ?: "No main server", main == null, main, RouteKindLabel.MAIN)
        AppRouteMode.MANUAL -> when {
            route.missing -> RouteCellText("Missing", route.label.ifBlank { "Removed server" }, true, null, RouteKindLabel.CUSTOM)
            route.pending -> RouteCellText("Custom", "Choose a server", true, null, RouteKindLabel.CUSTOM)
            else -> RouteCellText("Custom", route.node?.let(::serverName) ?: route.label, false, route.node, RouteKindLabel.CUSTOM)
        }
        AppRouteMode.AUTO -> {
            val (decision, _) = rememberAutoDecision(shell, path)
            val node = decision.nodeKey()?.let { key -> shell.nodes.firstOrNull { it.key() == key } }
            RouteCellText("Auto", node?.let(::serverName) ?: "No server", node == null, node, RouteKindLabel.AUTO)
        }
    }
}

internal class AppsModel(private val shell: Shell) {
    var query by mutableStateOf("")
    var source by mutableStateOf(AppListSource.INSTALLED)
    var runningLoading by mutableStateOf(true)
    var installedLoading by mutableStateOf(true)
    var running by mutableStateOf<List<AppChoice>>(emptyList())
    var installed by mutableStateOf<List<AppChoice>>(emptyList())
    val manual = mutableStateListOf<String>()
    private var loaded = false
    private var pinned: Set<String> = emptySet()
    private var pinnedFor: Pair<List<AppChoice>, List<String>>? = null

    fun ensureLoaded() { if (!loaded) { loaded = true; refresh() } }

    fun refresh(force: Boolean = false) {
        shell.scope.launch {
            runningLoading = true
            installedLoading = true
            val runningTask = async(Dispatchers.IO) {
                runCatching { shell.controller.runningApplications(force).map { AppChoice(it.name(), it.path()) } }.getOrDefault(emptyList())
            }
            val installedTask = async(Dispatchers.IO) {
                runCatching { shell.controller.installedApplications(force).map { AppChoice(it.name(), it.path()) } }.getOrDefault(emptyList())
            }
            try { running = runningTask.await() } finally { runningLoading = false }
            try { installed = installedTask.await() } finally { installedLoading = false }
        }
    }

    val loading: Boolean get() = if (source == AppListSource.RUNNING) runningLoading else installedLoading

    fun visible(): List<AppChoice> {
        (shell.apps + shell.draft.selected).forEach { path -> if (manual.none { ApplicationIdentity.same(it, path) }) manual += path }
        val discovered = if (source == AppListSource.RUNNING) running else installed
        val moved = manual.filter(app.perroute.service.StoreAppPaths::isStorePath).mapNotNull { saved ->
            app.perroute.service.StoreAppPaths.current(saved).takeIf { !it.equals(saved, true) }?.let { it.lowercase() to saved }
        }.toMap()
        val available = if (moved.isEmpty()) discovered else discovered.map { choice -> moved[choice.path.lowercase()]?.let { AppChoice(choice.name, it) } ?: choice }
        val extra = manual.filter { path -> available.none { ApplicationIdentity.same(it.path, path) } }.map { AppChoice(fileName(it), it) }
        val key = available to shell.apps.toList()
        if (pinnedFor != key) { pinnedFor = key; pinned = shell.apps.mapTo(hashSetOf()) { ApplicationIdentity.comparisonKey(it) } }
        return (extra + available).filter { query.isBlank() || it.name.contains(query, true) || it.path.contains(query, true) }
            .sortedByDescending { ApplicationIdentity.comparisonKey(it.path) in pinned }
    }

    fun selectedRoutes(): List<Pair<String, DraftRoute>> = shell.draft.selected.map { it to draftRoute(shell, it) }

    fun unresolved(): List<Pair<String, DraftRoute>> = if (shell.draft.advanced) selectedRoutes().filter { it.second.missing } else emptyList()

    fun routeCount(): Int {
        if (!shell.draft.advanced) return if (shell.draft.selected.isEmpty()) 0 else 1
        val mainKey = shell.mainNode?.key()
        return (selectedRoutes().mapNotNull { (path, route) ->
            when {
                route.mode == AppRouteMode.AUTO -> "auto:${AppRoutes.normalize(path)}"
                route.followsMain || route.missing || route.pending -> mainKey
                else -> route.assignedKey
            }
        }).distinct().size
    }

    fun toggle(path: String) {
        if (shell.locked) return
        val draft = shell.draft
        draft.toggle(path)
        draft.focused = if (draft.isSelected(path)) path else draft.focused?.takeUnless { ApplicationIdentity.same(it, path) }
    }

    fun focus(path: String) { shell.draft.focused = path }

    fun browseExe() {
        val owner = shell.hostWindow ?: return
        val dialog = FileDialog(owner, "Choose application", FileDialog.LOAD).apply {
            filenameFilter = java.io.FilenameFilter { directory, name ->
                if (System.getProperty("os.name") == "Mac OS X") java.io.File(directory,name).canExecute() else name.endsWith(".exe", true)
            }
            isVisible = true
        }
        val file = dialog.file ?: return
        val raw = Path.of(dialog.directory, file).toAbsolutePath().normalize().toString()
        val path = runCatching { shell.controller.selectedApplicationIdentity(raw) }.getOrElse {
            shell.notify(ActionResult(false,it.message ?: "Select an executable from the application list.")); return
        }
        if (!shell.draft.isSelected(path)) shell.draft.selected += path
        shell.draft.focused = path
    }
}

internal fun appsModel(shell: Shell): AppsModel = shell.screen("${Tab.Apps.name}:apps") { AppsModel(shell) }

@Composable
internal fun manualServerEntries(shell: Shell, path: String, filter: String, open: Boolean): List<MenuEntry> {
    val route = draftRoute(shell, path)
    val latencies = remember(open) { shell.controller.cachedLatencies() }
    val scores = remember(open) { if (open) shell.controller.qualityScores() else emptyMap() }
    val favorites = remember(open) { shell.nodes.filter { shell.controller.isFavorite(it.key()) }.mapTo(hashSetOf()) { it.key() } }
    return shell.nodes
        .filter { node ->
            filter.isBlank() || serverName(node).contains(filter, true) || node.endpoint().contains(filter, true) ||
                shell.controller.nodeTag(node.key()).contains(filter, true)
        }
        .sortedByDescending { it.key() in favorites }
        .map { node ->
            val favorite = node.key() in favorites
            val tag = shell.controller.nodeTag(node.key())
            val latency = latencies[node.key()]?.takeIf { it >= 0 }?.let { "$it ms" }
            MenuEntry(serverName(node), icon = if (favorite) Icons.Rounded.Star else null,
                detail = listOfNotNull(protocolLabel(node), PerRouteController.sourceLabel(shell.controller.nodeSource(node.key())),
                    tag.takeIf { it.isNotBlank() }?.let { "#$it" }, latency, scores[node.key()]?.let { qualitySummary(it) })
                    .joinToString("  •  "),
                selected = route.assignedKey == node.key()) { shell.draft.assign(path, node.key()) }
        }
}

@Composable
internal fun RoutePicker(shell: Shell, path: String, enabled: Boolean, modifier: Modifier = Modifier.fillMaxWidth()) {
    var open by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val route = draftRoute(shell, path)
    val warn = route.missing || route.pending
    Box(modifier) {
        val shape = ControlShape
        Row(
            Modifier.fillMaxWidth().height(40.dp).alpha(if (enabled) 1f else .6f)
                .pressable(shape, enabled, label = "Server for ${appName(path)}: ${if (route.missing) "missing" else route.node?.let(::serverName) ?: "not chosen"}") {
                    filter = ""; open = true
                }
                .background(if (warn) Amber.copy(.12f) else Field)
                .border(1.dp, if (warn) Amber.copy(.5f) else Line, shape)
                .padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                route.missing -> {
                    Icon(Icons.Rounded.WarningAmber, null, tint = Amber, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(7.dp))
                    EllipsisText("Missing: ${route.label.ifBlank { "removed server" }}", Amber, 12.sp, Modifier.weight(1f), FontWeight.SemiBold)
                }
                route.pending -> EllipsisText("Choose a server", Amber, 12.sp, Modifier.weight(1f), FontWeight.SemiBold)
                else -> {
                    ServerAvatar(route.node, 22.dp)
                    Spacer(Modifier.width(8.dp))
                    EllipsisText(route.node?.let(::serverName) ?: "No server", White, 12.sp, Modifier.weight(1f), FontWeight.Medium)
                }
            }
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = White.copy(.7f), modifier = Modifier.size(18.dp))
        }
        PopupMenu(open, { open = false }, manualServerEntries(shell, path, filter, open), minWidth = 300.dp, header = if (shell.nodes.size > 8) ({
            Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                TextInput(filter, { filter = it }, "Filter servers", icon = Icons.Rounded.Search)
            }
        }) else null)
    }
}

@Composable
internal fun AutoPoolMenu(shell: Shell, path: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    val pool = AutoCandidatePool.decode(shell.draft.pools[AppRoutes.normalize(path)])
    val tags = shell.nodes.map { shell.controller.nodeTag(it.key()) }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
    MenuButton(pool.describe(),
        listOf(MenuEntry("All servers", selected = pool.kind() == AutoCandidatePool.Kind.ALL) { shell.draft.setPool(path, AutoCandidatePool.ALL) },
            MenuEntry("Favorites", selected = pool.kind() == AutoCandidatePool.Kind.FAVORITES) {
                shell.draft.setPool(path, AutoCandidatePool(AutoCandidatePool.Kind.FAVORITES, ""))
            }) + tags.map { tag ->
            MenuEntry("#$tag", selected = pool.kind() == AutoCandidatePool.Kind.TAG && pool.tag().equals(tag, true)) {
                shell.draft.setPool(path, AutoCandidatePool(AutoCandidatePool.Kind.TAG, tag))
            }
        }, modifier, accessibleLabel = "Servers Auto may choose for ${appName(path)}: ${pool.describe()}", enabled = !shell.locked)
}

@Composable
internal fun DecisionSummary(shell: Shell, path: String) {
    val route = draftRoute(shell, path)
    val node: ProxyNode?
    val explanation: String
    var autoSummary: AutoServerSelector.Decision? = null
    when (route.mode) {
        AppRouteMode.MAIN -> {
            node = shell.mainNode
            explanation = "Follows the main server and changes with it."
        }
        AppRouteMode.MANUAL -> {
            node = route.node
            explanation = if (route.pending) "Choose a server. Manual choices are never changed automatically."
                else if (route.missing) "The chosen server is no longer in your list. Choose a replacement."
                else "You chose this server. PerRoute never changes it automatically."
        }
        AppRouteMode.AUTO -> {
            val (decision, _) = rememberAutoDecision(shell, path)
            node = decision.nodeKey()?.let { key -> shell.nodes.firstOrNull { it.key() == key } }
            explanation = decision.explanation()
            autoSummary = decision
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ServerAvatar(node, 34.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            EllipsisText(node?.let(::serverName) ?: "No server", White, 13.sp, fontWeight = FontWeight.SemiBold)
            if (node != null) {
                val score = remember(node.key(), shell.activeRoutes) { shell.controller.qualityScore(node.key()) }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QualityPill(score, compact = true)
                    Spacer(Modifier.width(7.dp))
                    EllipsisText(score.headline(), Muted, 10.5.sp)
                }
            }
        }
    }
    Spacer(Modifier.height(7.dp))
    autoSummary?.let { decision ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = if (decision.reason() == AutoServerSelector.Reason.PROVISIONAL_MAIN) Amber else Cyan, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            EllipsisText(decision.summary(), if (decision.reason() == AutoServerSelector.Reason.PROVISIONAL_MAIN) Amber else Cyan, 11.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(4.dp))
    }
    Text(explanation, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
}

internal fun advancedDetail(shell: Shell): String = when {
    !shell.perApp -> "Per-app servers apply in Smart per-app only. Current mode: ${modeLabel(shell.mode)}."
    shell.draft.advanced -> "Each app follows Main, a server you pick, or Auto."
    else -> "Off: every selected app uses the main server."
}

internal class BandwidthForm(private val shell: Shell, val path: String) {
    private val controller = shell.controller
    var policy by mutableStateOf(controller.bandwidthPolicy(path))
    var down by mutableStateOf(policy.downloadBitsPerSecond().takeIf { it > 0 }?.let { formatMbpsInput(it) } ?: "")
    var up by mutableStateOf(policy.uploadBitsPerSecond().takeIf { it > 0 }?.let { formatMbpsInput(it) } ?: "")
    var status by mutableStateOf(controller.bandwidthStatus(path))

    val unlimited: Boolean get() = policy.unlimited() && down.isBlank() && up.isBlank()
    private fun parsed(text: String): Long? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1_000_000).toLong() }
    val valid: Boolean get() = (down.isBlank() || parsed(down) != null) && (up.isBlank() || parsed(up) != null)
    val next: BandwidthPolicy get() = BandwidthPolicy(parsed(down) ?: 0L, parsed(up) ?: 0L)
    val changed: Boolean get() = next != policy

    fun refreshStatus() {
        val saved = controller.bandwidthPolicy(path)
        if (saved != policy && !changed) {
            policy = saved
            down = saved.downloadBitsPerSecond().takeIf { it > 0 }?.let { formatMbpsInput(it) } ?: ""
            up = saved.uploadBitsPerSecond().takeIf { it > 0 }?.let { formatMbpsInput(it) } ?: ""
        }
        status = controller.bandwidthStatus(path)
    }

    fun setUnlimited(on: Boolean) {
        if (on) {
            down = ""; up = ""
            shell.run(silent = true) { controller.setBandwidthPolicy(path, BandwidthPolicy.UNLIMITED).also { policy = BandwidthPolicy.UNLIMITED; status = controller.bandwidthStatus(path) } }
        } else if (down.isBlank() && up.isBlank()) down = "10"
    }

    fun apply() {
        val value = next
        shell.run { controller.setBandwidthPolicy(path, value).also { policy = controller.bandwidthPolicy(path); status = controller.bandwidthStatus(path) } }
    }

    fun clean(text: String) = text.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(7)
}

internal fun formatMbpsInput(bits: Long): String {
    val mbps = bits / 1_000_000.0
    return if (mbps == Math.floor(mbps)) mbps.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, mbps).trimEnd('0').trimEnd('.')
}

@Composable
internal fun rememberBandwidthForm(shell: Shell, path: String): BandwidthForm {
    val form = shell.screen("${shell.tab.name}:bandwidth:${ApplicationIdentity.comparisonKey(path)}") { BandwidthForm(shell, path) }
    LaunchedEffect(path, shell.traffic, shell.connected) { form.refreshStatus() }
    return form
}

@Composable
internal fun BandwidthEditor(shell: Shell, path: String, sideBySide: Boolean = true, showTitle: Boolean = true) {
    val form = rememberBandwidthForm(shell, path)
    if (showTitle) {
        Text("Bandwidth limits", color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Unlimited (no limits)", color = White.copy(.9f), fontSize = 12.sp, modifier = Modifier.weight(1f))
        ToggleSwitch(form.unlimited, "Unlimited bandwidth for ${appName(path)}") { form.setUnlimited(it) }
    }
    Spacer(Modifier.height(10.dp))
    val download: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            FieldLabel("Download limit")
            TextInput(form.down, { form.down = form.clean(it) }, "Unlimited", trailing = { Text("Mbps", color = Muted, fontSize = 11.5.sp) })
        }
    }
    val upload: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            FieldLabel("Upload limit")
            TextInput(form.up, { form.up = form.clean(it) }, "Unlimited", trailing = { Text("Mbps", color = Muted, fontSize = 11.5.sp) })
        }
    }
    if (sideBySide) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { download(Modifier.weight(1f)); upload(Modifier.weight(1f)) }
    else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { download(Modifier.fillMaxWidth()); upload(Modifier.fillMaxWidth()) }
    Spacer(Modifier.height(10.dp))
    PrimaryButton(if (form.changed) "Apply limits" else "Limits applied", Modifier.fillMaxWidth(), enabled = form.changed && form.valid && !shell.busy) { form.apply() }
    if (!form.valid) {
        Spacer(Modifier.height(5.dp))
        Text("Enter a number in Mbps, for example 2.5.", color = Red, fontSize = 11.sp)
    }
    Spacer(Modifier.height(8.dp))
    Note("Limits save immediately, even while connected.", icon = Icons.Rounded.Info)
    Spacer(Modifier.height(10.dp))
    BandwidthStatusLine(form.status)
}

@Composable
internal fun bandwidthStateText(state: BandwidthState, idle: Boolean = false): Pair<String, Color> = when (state) {
    BandwidthState.UNLIMITED -> (if (idle) "Idle" else "Unlimited") to Muted
    BandwidthState.ENFORCED -> "Enforced" to Cyan
    BandwidthState.THROTTLING -> "Throttling" to Amber
    BandwidthState.PENDING -> "Pending" to Violet2
    BandwidthState.NOT_ENFORCED -> "Not enforced" to Red
}

@Composable
internal fun BandwidthStatusLine(status: BandwidthStatus, large: Boolean = false) {
    val (label, color) = bandwidthStateText(status.state)
    Row(verticalAlignment = Alignment.Top) {
        StatusDot(color, if (large) 12.dp else 9.dp)
        Spacer(Modifier.width(9.dp))
        Column {
            Text(if (large) label else "Status: $label", color = if (large) White else color, fontSize = if (large) 15.sp else 12.sp, fontWeight = FontWeight.SemiBold)
            if (status.state != BandwidthState.UNLIMITED) Text(status.detail, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}
