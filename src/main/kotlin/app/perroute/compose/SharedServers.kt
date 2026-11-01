package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.ProxyNode
import app.perroute.quality.QualityScore
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Timer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.nio.file.Path

internal enum class ServerSort(val label: String) { DEFAULT("Default order"), QUALITY("Quality"), GAMING("Best for gaming"), FASTEST("Lowest latency"), SLOWEST("Highest latency") }
private const val AutoSpeedTestLimit = 10

internal data class ServerMeta(val source: String, val sourceLabel: String, val tag: String, val country: String, val favorite: Boolean)

internal const val FILTER_ALL = "__all__"
internal const val FILTER_FAVORITES = "__favorites__"

internal class ServersModel(private val shell: Shell) {
    private val controller = shell.controller
    var query by mutableStateOf("")
    var sourceFilter by mutableStateOf(FILTER_ALL)
    var sort by mutableStateOf(ServerSort.QUALITY)
    var latencies by mutableStateOf(controller.cachedLatencies())
    var pinging by mutableStateOf(false)
    var pingRevision by mutableStateOf(0)
    var realTest by mutableStateOf(false)
    var hideUnavailable by mutableStateOf(false)
    var metaRevision by mutableStateOf(0)
    var speeds by mutableStateOf(controller.cachedSpeeds())
    var speedTestingKey by mutableStateOf<String?>(null)
    private var speedJob: Job? = null
    var highlightedKey by mutableStateOf(shell.selectedNodeKey)
    var expandedKey by mutableStateOf<String?>(null)
    var scores by mutableStateOf(controller.qualityScores())
    var retestingKey by mutableStateOf<String?>(null)
    var import by mutableStateOf<ImportSession?>(null)
    var visible: List<ProxyNode> = emptyList()

    val speedRunning: Boolean get() = speedTestingKey != null

    fun runSpeedTest(targets: List<ProxyNode>) {
        if (speedJob?.isActive == true || targets.isEmpty()) return
        speedJob = shell.scope.launch {
            try {
                for (node in targets) {
                    if (!isActive) break
                    speedTestingKey = node.key()
                    val speed = withContext(Dispatchers.IO) { controller.speedTestServer(node) }
                    speeds = speeds + (node.key() to speed)
                }
            } finally {
                speedTestingKey = null
            }
        }
    }

    fun stopSpeedTest() { speedJob?.cancel() }

    fun pingAll() { realTest = false; pingRevision++ }
    fun realTestAll() { realTest = true; pingRevision++ }

    suspend fun measure(snapshot: List<ProxyNode>) {
        if (snapshot.isEmpty()) { latencies = emptyMap(); return }
        pinging = true
        try {
            val measured = withContext(Dispatchers.IO) { if (realTest) controller.realTestServers(snapshot) else controller.pingServers(snapshot) }
            latencies = measured
            scores = withContext(Dispatchers.IO) { controller.qualityScores() }
            if (controller.automaticSpeedTestsNow()) {
                val known = speeds
                runSpeedTest(snapshot.filter { (measured[it.key()] ?: -1L) >= 0L && it.key() !in known }
                    .sortedBy { measured[it.key()] }.take(AutoSpeedTestLimit))
            }
        } finally {
            pinging = false
        }
    }

    fun retest(node: ProxyNode) {
        if (retestingKey != null) return
        shell.scope.launch {
            retestingKey = node.key()
            try {
                val measured = withContext(Dispatchers.IO) { controller.realTestServers(listOf(node)) }
                latencies = latencies + measured
                scores = withContext(Dispatchers.IO) { controller.qualityScores() }
            } finally { retestingKey = null }
        }
    }

    fun refreshScores() { scores = controller.qualityScores() }

    fun execute(block: () -> ActionResult) = shell.run(done = { metaRevision++ }, action = block)
    fun useAsMain(node: ProxyNode) = execute { controller.switchNode(node.key()) }
    fun updateSubscriptions() = execute { controller.updateAllSubscriptions() }
    fun toggleFavorite(node: ProxyNode) { controller.toggleFavorite(node.key()); metaRevision++ }

    fun copyConfig(node: ProxyNode, clipboard: ClipboardManager) {
        val export = node.exportText()
        if (export.isBlank()) shell.notify(ActionResult(false, "This server has no original config to copy."))
        else {
            clipboard.setText(AnnotatedString(export))
            shell.notify(ActionResult(true, if (node.option("rawProfileJson").isBlank()) "Share link copied." else "JSON config copied."))
        }
    }

    fun meta(): Map<String, ServerMeta> = shell.nodes.associate { node ->
        val source = controller.nodeSource(node.key())
        node.key() to ServerMeta(source, PerRouteController.sourceLabel(source), controller.nodeTag(node.key()), countryCode(node), controller.isFavorite(node.key()))
    }

    fun subscriptions(): List<String> = controller.subscriptions()

    fun allTags(meta: Map<String, ServerMeta>): List<String> =
        meta.values.map { it.tag }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    fun assignTag(node: ProxyNode, tag: String) { controller.setNodeTag(node.key(), tag); metaRevision++ }

    fun editTag(node: ProxyNode, allTags: List<String>) {
        val text = mutableStateOf(controller.nodeTag(node.key()))
        shell.showDialog {
            DialogCard("Tag for ${serverName(node)}", "Tags group servers so you can filter the list. A server has one tag.", shell::closeDialog,
                width = 480.dp, actions = {
                    SecondaryButton("Cancel") { shell.closeDialog() }
                    if (controller.nodeTag(node.key()).isNotBlank()) SecondaryButton("Remove tag", icon = Icons.Rounded.DeleteOutline) {
                        assignTag(node, ""); shell.closeDialog()
                    }
                    PrimaryButton("Save tag", enabled = text.value.isNotBlank()) { assignTag(node, text.value); shell.closeDialog() }
                }) {
                if (allTags.isNotEmpty()) {
                    FieldLabel("Choose an existing tag")
                    val tagScroll = rememberScrollState()
                    Row(Modifier.fillMaxWidth().wheelAndDragScroll(tagScroll).horizontalScroll(tagScroll), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        allTags.forEach { tag -> FilterChip("#$tag", text.value.equals(tag, true)) { assignTag(node, tag); shell.closeDialog() } }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                FieldLabel(if (allTags.isEmpty()) "Tag name" else "Or create a new tag")
                TextInput(text.value, { text.value = it.take(24) }, "For example: Gaming", icon = Icons.Rounded.Label)
            }
        }
    }

    fun tagMenu(node: ProxyNode, current: String, allTags: List<String>): List<MenuEntry> =
        allTags.map { tag -> MenuEntry("#$tag", Icons.Rounded.Label, selected = current.equals(tag, true)) { assignTag(node, tag) } } +
            MenuEntry("New tag…", Icons.Rounded.AddCircle, "Create a tag and put this server in it") { editTag(node, allTags) } +
            MenuEntry("No tag", Icons.Rounded.DeleteOutline, enabled = current.isNotBlank()) { assignTag(node, "") }

    fun removeServer(node: ProxyNode) {
        val assigned = shell.appRoutes.values.count { it == node.key() }
        shell.showDialog {
            ConfirmDialog("Remove ${serverName(node)}?",
                if (assigned == 0) "The server is removed from your list. You can add it again later."
                else "${plural(assigned, "app")} ${if (assigned == 1) "is" else "are"} assigned to this server. They keep the assignment and will need a replacement server before you can connect with Advanced routing.",
                "Remove server", shell::closeDialog, danger = true) {
                shell.closeDialog()
                execute { controller.removeNode(node.key()) }
            }
        }
    }

    fun removeSubscription(target: String) {
        shell.showDialog {
            ConfirmDialog("Remove ${PerRouteController.sourceLabel(target)}?",
                "The subscription and all of its servers are removed. Apps assigned to one of them will need a replacement server.",
                "Remove subscription", shell::closeDialog, danger = true) {
                shell.closeDialog(); sourceFilter = FILTER_ALL
                execute { controller.removeSubscription(target) }
            }
        }
    }

    fun rowMenu(node: ProxyNode, clipboard: ClipboardManager, tags: List<String>, tag: String): List<MenuEntry> {
        val isMain = node.key() == shell.mainNode?.key()
        val locked = shell.locked
        return listOf(
            MenuEntry("Use as main server", Icons.Rounded.CheckCircle, enabled = !isMain && !shell.busy) { useAsMain(node) },
            MenuEntry("Retest this server", Icons.Rounded.Speed, "Real tunnel test of this server", enabled = retestingKey == null) { retest(node) },
            MenuEntry("Speed test", Icons.Rounded.Download, "Bounded download through this server", enabled = !speedRunning) { runSpeedTest(listOf(node)) },
            MenuEntry("Gaming test", Icons.Rounded.SportsEsports, "Steady latency, jitter, loss and UDP") { showGamingDialog(shell, listOf(node)) },
            MenuEntry("Compare with a live route…", Icons.Rounded.CompareArrows, "Side by side, without changing the route", enabled = !isMain || !shell.connected) {
                showShadowDialog(shell, node)
            },
            MenuEntry("Use for an app for a while…", Icons.Rounded.Timer, if (shell.perApp) "One app, back to normal afterwards" else "Needs Smart per-app mode",
                enabled = shell.perApp && shell.apps.isNotEmpty()) { showTemporaryDialog(shell, server = node.key()) },
            MenuEntry("Copy config", Icons.Rounded.ContentCopy) { copyConfig(node, clipboard) },
            MenuEntry(if (tag.isBlank()) "Add a tag…" else "Change tag (#$tag)…", Icons.Rounded.Label) { editTag(node, tags) },
            MenuEntry("Remove server", Icons.Rounded.DeleteOutline, if (locked) "Disconnect first" else "", enabled = !locked, danger = true) { removeServer(node) }
        )
    }

    fun testMenu(): List<MenuEntry> = listOf(
        MenuEntry("Ping all servers", Icons.Rounded.Schedule, "TCP latency to every endpoint", enabled = !pinging) { pingAll() },
        MenuEntry("Real tunnel test", Icons.Rounded.Speed, "Starts an isolated core per server", enabled = !pinging) { realTestAll() },
        MenuEntry("Gaming test", Icons.Rounded.SportsEsports, "The main server, favorites and the best by quality") { showGamingDialog(shell, gamingCandidates(shell)) },
        if (speedRunning) MenuEntry("Stop speed test", Icons.Rounded.Stop, "Keeps the results measured so far") { stopSpeedTest() }
        else MenuEntry("Speed test listed servers", Icons.Rounded.Download, "One at a time, bounded download") {
            runSpeedTest(visible.filter { (latencies[it.key()] ?: 0L) >= 0L })
        },
        MenuEntry(if (hideUnavailable) "Show failed servers" else "Hide failed servers", Icons.Rounded.VisibilityOff,
            "Servers whose last test did not answer", selected = hideUnavailable) { hideUnavailable = !hideUnavailable }
    )

    val testLabel: String get() = if (pinging) "Testing…" else if (speedRunning) "Speed test…" else "Test servers"

    fun filtered(meta: Map<String, ServerMeta>): Pair<List<ProxyNode>, Int> {
        val snapshot = shell.nodes.toList()
        val matching = snapshot.filter { node ->
            val info = meta[node.key()] ?: return@filter false
            (when {
                sourceFilter == FILTER_ALL -> true
                sourceFilter == FILTER_FAVORITES -> info.favorite
                sourceFilter.startsWith("tag:") -> info.tag.equals(sourceFilter.removePrefix("tag:"), true)
                sourceFilter.startsWith("country:") -> info.country == sourceFilter.removePrefix("country:")
                else -> info.source == sourceFilter
            }) && (!hideUnavailable || (latencies[node.key()] ?: -1L) >= 0L) &&
                (query.isBlank() || node.name().contains(query, true) || node.endpoint().contains(query, true) ||
                    info.sourceLabel.contains(query, true) || info.tag.contains(query, true))
        }
        val sorted = when (sort) {
            ServerSort.DEFAULT -> matching
            ServerSort.QUALITY -> matching.sortedWith(
                compareByDescending<ProxyNode> { scores[it.key()]?.usable() == true }
                    .thenByDescending { scores[it.key()]?.score() ?: -1 }
                    .thenByDescending { scores[it.key()]?.confidence() ?: 0.0 })
            ServerSort.GAMING -> controller.gamingScores().let { gaming ->
                matching.sortedWith(compareByDescending<ProxyNode> { (gaming[it.key()]?.score() ?: -1) > 0 }.thenByDescending { gaming[it.key()]?.score() ?: -1 })
            }
            ServerSort.FASTEST -> matching.sortedWith(
                compareBy<ProxyNode> { (latencies[it.key()] ?: -1L) < 0L }
                    .thenBy { latencies[it.key()]?.takeIf { value -> value >= 0L } ?: Long.MAX_VALUE })
            ServerSort.SLOWEST -> matching.sortedWith(
                compareBy<ProxyNode> { (latencies[it.key()] ?: -1L) < 0L }
                    .thenByDescending { latencies[it.key()]?.takeIf { value -> value >= 0L } ?: Long.MIN_VALUE })
        }
        visible = sorted
        return sorted to snapshot.size
    }

    fun groupFilters(meta: Map<String, ServerMeta>): List<String> =
        meta.values.map { it.tag }.filter { it.isNotBlank() }.distinct().map { "tag:$it" } +
            meta.values.map { it.country }.filter { it.isNotBlank() }.distinct().map { "country:$it" }

    fun filterLabel(filter: String): String = when {
        filter == FILTER_ALL -> "All"
        filter == FILTER_FAVORITES -> "Favorites"
        filter.startsWith("tag:") -> "#${filter.removePrefix("tag:")}"
        filter.startsWith("country:") -> filter.removePrefix("country:")
        else -> PerRouteController.sourceLabel(filter)
    }

    fun usedBy(node: ProxyNode, rows: List<RouteRow>): List<String> {
        if (!shell.perApp) return emptyList()
        val routes = rows.filter { it.node.key() == node.key() }
        if (routes.isEmpty()) return if (node.key() == shell.mainNode?.key() && !shell.plan.advancedEffective()) shell.apps.toList() else emptyList()
        return routes.flatMap { it.applications }.distinctBy { it.lowercase() }
    }

    fun openImport(method: ImportMethod = ImportMethod.SUBSCRIPTION) { import = ImportSession(shell, method) }
    fun closeImport() { import = null; metaRevision++ }
}

internal fun serversModel(shell: Shell): ServersModel = shell.screen("${Tab.Servers.name}:servers") { ServersModel(shell) }

@Composable
internal fun ServersEffects(shell: Shell, model: ServersModel, listState: LazyListState?) {
    LaunchedEffect(Unit) {
        while (isActive) {
            model.scores = withContext(Dispatchers.IO) { shell.controller.qualityScores() }
            delay(15_000)
        }
    }
    val snapshot = shell.nodes.toList()
    val signature = snapshot.joinToString("|") { "${it.key()}:${it.endpoint()}" }
    LaunchedEffect(signature, model.pingRevision) { model.measure(snapshot) }
    LaunchedEffect(shell.addServersRequested) {
        if (shell.addServersRequested) { shell.addServersRequested = false; model.openImport() }
    }
    if (listState != null) {
        LaunchedEffect(model.sourceFilter, model.sort, model.hideUnavailable, model.query) { listState.scrollToItem(0) }
        if (Perf.enabled) LaunchedEffect(Perf.command.value) {
            val command = Perf.command.value
            if (command.startsWith("q:")) model.query = command.removePrefix("q:")
            if (command == "scroll") listState.animateScrollBy(60_000f, tween(3_000))
        }
    }
}

@Composable
internal fun latencyText(latencyMs: Long?, pinging: Boolean): Pair<String, Color> = when {
    pinging -> "Testing…" to Muted
    latencyMs == null -> "Untested" to Muted
    latencyMs < 0 -> "No answer" to Red
    latencyMs <= 150 -> "$latencyMs ms" to White
    latencyMs <= 350 -> "$latencyMs ms" to Amber
    else -> "$latencyMs ms" to Red
}

@Composable
internal fun speedText(bytesPerSecond: Long?, testing: Boolean): Pair<String, Color> {
    val megabits = bytesPerSecond?.takeIf { it >= 0 }?.let { it * 8 / 1_000_000.0 }
    return when {
        testing -> "Testing…" to Amber
        bytesPerSecond == null -> "Not tested" to Muted
        megabits == null -> "Failed" to Red
        megabits >= 20 -> formatMegabits(megabits) to Cyan
        megabits >= 5 -> formatMegabits(megabits) to Amber
        else -> formatMegabits(megabits) to Red
    }
}

internal fun formatMegabits(value: Double): String =
    if (value >= 100) "${value.toInt()} Mbps" else String.format(java.util.Locale.ROOT, "%.1f Mbps", value)

internal enum class ImportMethod(val label: String, val icon: ImageVector) {
    SUBSCRIPTION("Subscription URL", Icons.Rounded.Link),
    PASTE("Paste links", Icons.Rounded.Description),
    QR("QR code", Icons.Rounded.QrCodeScanner)
}

internal class ImportSession(private val shell: Shell, initialMethod: ImportMethod = ImportMethod.SUBSCRIPTION, initialOutcome: ImportOutcome? = null) {
    private val controller = shell.controller
    var method by mutableStateOf(initialMethod)
    var url by mutableStateOf("")
    var pasted by mutableStateOf("")
    var working by mutableStateOf("")
    var outcome by mutableStateOf(initialOutcome)
    val preview: ImportPreview? get() = outcome?.preview
    val scanning: Boolean get() = working.startsWith("Camera")

    fun choose(next: ImportMethod) { method = next; outcome = null }

    fun review(label: String, block: () -> ImportOutcome) {
        if (working.isNotBlank()) return
        shell.scope.launch {
            working = label
            outcome = null
            try {
                outcome = withContext(Dispatchers.IO) { runCatching(block).getOrElse { ImportOutcome(error = it.message ?: it.javaClass.simpleName) } }
            } finally { working = "" }
        }
    }

    fun fetchSubscription() { val target = url; review("Fetching the subscription…") { controller.previewSubscription(target) } }
    fun reviewPasted() { val content = pasted; review("Reading the links…") { controller.previewPasted(content) } }
    fun pasteFromClipboard() {
        runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { pasted = it; outcome = null }
    }
    fun scanCamera() = review("Camera: looking for a QR code for up to 12 seconds…") { controller.previewQrCamera() }
    fun chooseImage() {
        val owner = shell.hostWindow ?: return
        val dialog = FileDialog(owner, "Choose QR image", FileDialog.LOAD).apply { isVisible = true }
        dialog.file?.let { file -> val path = Path.of(dialog.directory, file); review("Reading the image…") { controller.previewQrImage(path) } }
    }
    fun qrFromClipboard() = review("Reading the clipboard…") { controller.previewQrClipboard() }

    fun backToSource() { outcome = null }

    val canCommit: Boolean get() = preview != null && working.isBlank() && !(preview!!.replaceSource && shell.locked)

    fun commit(done: () -> Unit) {
        val accepted = preview ?: return
        done()
        shell.run { controller.commitImport(accepted) }
    }

    fun status(): String {
        val error = outcome?.error
        val p = preview
        return when {
            working.isNotBlank() -> working
            error != null -> "Nothing was added. $error"
            p != null -> listOfNotNull(
                "${plural(p.candidates.size, "server")} found",
                "${p.newCount} new",
                (p.candidates.size - p.newCount).takeIf { it > 0 }?.let { "$it already saved" },
                p.removedCount.takeIf { it > 0 }?.let { "$it no longer in this subscription" }
            ).joinToString("  •  ")
            method == ImportMethod.QR -> "No servers scanned yet"
            else -> "Nothing to review yet"
        }
    }

    fun sourceLabel(): String {
        val p = preview ?: return ""
        return when {
            p.replaceSource -> if (controller.subscriptions().any { it.equals(p.source, true) }) "Saved subscription (update)" else "New subscription"
            method == ImportMethod.QR -> "QR code"
            else -> "Pasted links"
        }
    }
}

@Composable
internal fun ImportSourceForm(shell: Shell, session: ImportSession) {
    val locked = shell.locked
    when (session.method) {
        ImportMethod.SUBSCRIPTION -> {
            FieldLabel("Subscription URL")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextInput(session.url, { session.url = it; session.outcome = null }, "https://…", Modifier.weight(1f), Icons.Rounded.Link, enabled = !locked)
                SecondaryButton("Fetch servers", icon = Icons.Rounded.Download, enabled = session.url.isNotBlank() && session.working.isBlank() && !locked) { session.fetchSubscription() }
            }
            Spacer(Modifier.height(8.dp))
            if (locked) InfoBanner("Disconnect before adding or refreshing a subscription. Pasted links and QR codes can be added while connected.",
                Modifier.fillMaxWidth(), Amber, Icons.Rounded.WarningAmber)
            else Text("Updating a saved subscription keeps favorites, tags, the main server and per-app assignments for servers that still exist.",
                color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
        }
        ImportMethod.PASTE -> {
            FieldLabel("Share links, subscription content or Xray JSON")
            TextArea(session.pasted, { session.pasted = it; session.outcome = null }, "vless://…\nvmess://…\ntrojan://…\nss://…", Modifier.fillMaxWidth().height(110.dp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Review links", icon = Icons.Rounded.Search, enabled = session.pasted.isNotBlank() && session.working.isBlank()) { session.reviewPasted() }
                SecondaryButton("Paste from clipboard", icon = Icons.Rounded.ContentPaste) { session.pasteFromClipboard() }
            }
        }
        ImportMethod.QR -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(
                Modifier.weight(1f).height(160.dp).background(Field, RowShape).border(1.dp, Line, RowShape).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                if (session.scanning) CircularProgressIndicator(color = if (Tok.isNew) White else Violet2, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                else Icon(Icons.Rounded.CameraAlt, null, tint = Muted, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(7.dp))
                Text(if (session.scanning) "Camera is on. Hold the QR code steady." else "The camera is off until you start it.",
                    color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(9.dp))
                SecondaryButton(if (session.scanning) "Scanning…" else "Start camera", icon = Icons.Rounded.CameraAlt, enabled = session.working.isBlank()) { session.scanCamera() }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ImportOption(Icons.Rounded.Image, "Choose image", "Import a QR code from a file", session.working.isBlank()) { session.chooseImage() }
                ImportOption(Icons.Rounded.ContentPaste, "Paste from clipboard", "Use a copied QR image or link text", session.working.isBlank()) { session.qrFromClipboard() }
                Text("The camera closes as soon as a code is read or the time runs out.", color = Muted, fontSize = 10.5.sp, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
private fun ImportOption(icon: ImageVector, title: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RowShape
    Row(
        Modifier.fillMaxWidth().pressable(shape, enabled, label = "$title. $detail", onClick = onClick)
            .background(Field).border(1.dp, Line, shape).padding(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = White.copy(.8f), modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, fontSize = 11.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun ImportStatusLine(session: ImportSession) {
    val error = session.outcome?.error
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            session.working.isNotBlank() -> CircularProgressIndicator(color = if (Tok.isNew) White else Violet2, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            error != null -> Icon(Icons.Rounded.WarningAmber, null, tint = Red, modifier = Modifier.size(16.dp))
            session.preview != null -> Icon(Icons.Rounded.CheckCircle, null, tint = Cyan, modifier = Modifier.size(16.dp))
            else -> StatusDot(Muted.copy(.7f), 9.dp)
        }
        Spacer(Modifier.width(8.dp))
        Text(session.status(), color = if (error != null) Red else if (session.preview != null) White else Muted, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
internal fun ImportWarnings(preview: ImportPreview?) {
    val warnings = preview?.warnings.orEmpty()
    if (warnings.isEmpty()) return
    val shape = RowShape
    Row(Modifier.fillMaxWidth().background(Amber.copy(.10f), shape).border(1.dp, Amber.copy(.35f), shape).padding(12.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.WarningAmber, null, tint = Amber, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(11.dp))
        Column {
            Text("${plural(warnings.size, "unsupported link")} ${if (warnings.size == 1) "was" else "were"} skipped.", color = White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Text("Unrecognized or invalid links are skipped. Review the entries below.", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            warnings.take(6).forEach { Text("•  $it", color = White.copy(.8f), fontSize = 10.5.sp, lineHeight = 14.sp) }
            if (warnings.size > 6) Text("and ${warnings.size - 6} more", color = Muted, fontSize = 10.5.sp)
        }
    }
}

internal fun unmeasuredExplanation(score: QualityScore?): String =
    score?.explanation() ?: "Run a ping or a real tunnel test, or connect, to rate this server. An unmeasured server is never treated as healthy."
