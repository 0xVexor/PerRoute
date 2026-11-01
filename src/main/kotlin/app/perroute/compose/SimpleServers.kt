package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.ProxyNode

@Composable
internal fun SimpleServersPage(shell: Shell) {
    val model = serversModel(shell)
    val listState = rememberLazyListState()
    ServersEffects(shell, model, listState)
    val clipboard = LocalClipboardManager.current
    val meta = remember(shell.nodes.toList(), model.metaRevision) { model.meta() }
    val tags = model.allTags(meta)
    val rows = routeRows(shell)
    val (filtered, total) = model.filtered(meta)
    val session = model.import
    LaunchedEffect(session) {
        if (session != null) shell.showDialog(onDismiss = { model.closeImport(); shell.closeDialog() }) { SimpleImportDialog(shell, model, session) }
    }
    SimplePage("Servers", "Find a good route. Make it your main.", scroll = false, actions = {
        SecondaryButton("Update subscriptions", icon = Icons.Rounded.Refresh, enabled = !shell.locked && model.subscriptions().isNotEmpty() && !shell.busy, height = 48.dp) { model.updateSubscriptions() }
        PrimaryButton("Add servers", icon = Icons.Rounded.Add, height = 48.dp) { model.openImport() }
    }) {
        SimpleCard(Modifier.weight(1f), padding = 20.dp) {
            val sources = buildList {
                add(FILTER_ALL); add(FILTER_FAVORITES)
                model.subscriptions().distinct().forEach { add(it) }
                if (meta.values.any { it.source == PerRouteController.MANUAL_SOURCE }) add(PerRouteController.MANUAL_SOURCE)
                if (meta.values.any { it.source == PerRouteController.IMPORTED_SOURCE }) add(PerRouteController.IMPORTED_SOURCE)
            }
            val tabScroll = rememberScrollState()
            Box(Modifier.fillMaxWidth().horizontalScroll(tabScroll)) {
                UnderlineTabs(sources, model.sourceFilter.takeIf { it in sources } ?: "", { model.filterLabel(it) }) { model.sourceFilter = it }
            }
            val groups = model.groupFilters(meta)
            if (groups.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                val scroll = rememberScrollState()
                Row(Modifier.fillMaxWidth().wheelAndDragScroll(scroll).horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tags and countries", color = Muted, fontSize = 12.sp)
                    groups.forEach { filter -> FilterChip(model.filterLabel(filter), model.sourceFilter == filter) { model.sourceFilter = if (model.sourceFilter == filter) FILTER_ALL else filter } }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextInput(model.query, { model.query = it }, "Search servers", Modifier.weight(1f), Icons.Rounded.Search, height = 44.dp)
                if (model.sourceFilter in model.subscriptions()) {
                    SubscriptionAlert(shell, model.sourceFilter)
                    IconAction(Icons.Rounded.Info, "Subscription details: usage, expiry and changes", size = 40.dp) { showSubscriptionDialog(shell, model.sourceFilter) }
                    IconAction(Icons.Rounded.ContentCopy, "Copy this subscription URL", size = 40.dp) {
                        clipboard.setText(AnnotatedString(model.sourceFilter)); shell.notify(ActionResult(true, "Subscription URL copied."))
                    }
                    IconAction(Icons.Rounded.DeleteOutline, if (shell.locked) "Disconnect to remove this subscription" else "Remove this subscription and its servers",
                        enabled = !shell.locked, size = 40.dp) { model.removeSubscription(model.sourceFilter) }
                }
                SortMenu(model)
                MenuButton(model.testLabel, model.testMenu(), Modifier.width(170.dp), accessibleLabel = "Test servers", height = 44.dp, menuWidth = 260.dp)
            }
            Spacer(Modifier.height(10.dp))
            SimpleTableHeader {
                HeaderText("Server", Modifier.weight(1.6f))
                if (!LocalCompact.current) HeaderText("Latency", Modifier.width(100.dp))
                HeaderText("Quality", Modifier.width(150.dp))
                Box(Modifier.width(110.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    shell.nodes.isEmpty() -> EmptyState(Icons.Rounded.Storage, "No servers yet",
                        "Add a subscription URL, paste share links, or scan a QR code. You review everything before it is saved.", Modifier.fillMaxSize()) {
                        PrimaryButton("Add servers", icon = Icons.Rounded.Add) { model.openImport() }
                    }
                    filtered.isEmpty() -> EmptyState(Icons.Rounded.SearchOff, "No servers match", "Nothing fits the current search and filters.", Modifier.fillMaxSize()) {
                        SecondaryButton("Clear search and filters") { model.query = ""; model.sourceFilter = FILTER_ALL; model.hideUnavailable = false }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        items(filtered, key = { it.key() }) { node -> SimpleServerRow(shell, model, node, meta[node.key()], rows, model.rowMenu(node, clipboard, tags, meta[node.key()]?.tag.orEmpty())) }
                    }
                }
            }
            Row(Modifier.padding(top = 10.dp, start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${filtered.size} of ${plural(total, "server")}  ·  Open a row for quality details and speed tests.", color = Muted, fontSize = 13.sp)
                if (model.hideUnavailable) { Spacer(Modifier.width(8.dp)); Badge("Failed hidden", Amber) }
            }
            Spacer(Modifier.height(12.dp))
            Divider()
            Spacer(Modifier.height(14.dp))
            val main = shell.mainNode
            val highlighted = shell.nodes.firstOrNull { it.key() == model.highlightedKey }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ServerAvatar(main, 46.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Main server", color = Muted, fontSize = 13.sp)
                    EllipsisText(main?.let(::serverName) ?: "No main server", White, 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (shell.connected) "Switching replaces only the main route; custom and Auto routes keep running." else "Custom app routes stay unchanged.", color = Muted, fontSize = 13.sp)
                }
                PrimaryButton(if (highlighted != null && highlighted.key() != main?.key()) "Use ${serverName(highlighted).take(18)} as main" else "Use as main server",
                    enabled = highlighted != null && highlighted.key() != main?.key() && !shell.busy, height = 48.dp) { highlighted?.let(model::useAsMain) }
            }
        }
    }
}

@Composable
private fun SortMenu(model: ServersModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.pressable(RoundedCornerShape(8.dp), label = "Sort servers: ${model.sort.label}") { open = true }.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Sort:", color = Muted, fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
            Text(model.sort.label, color = White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = White, modifier = Modifier.size(20.dp))
        }
        PopupMenu(open, { open = false }, ServerSort.entries.map { sort -> MenuEntry(sort.label, selected = model.sort == sort) { model.sort = sort } })
    }
}

@Composable
private fun SimpleServerRow(shell: Shell, model: ServersModel, node: ProxyNode, info: ServerMeta?, rows: List<RouteRow>, menu: List<MenuEntry>) {
    val isMain = node.key() == shell.mainNode?.key()
    val expanded = model.expandedKey == node.key()
    val custom = rows.any { it.node.key() == node.key() && !it.main }
    val compact = LocalCompact.current
    SimpleTableRow(model.highlightedKey == node.key(), onClick = { model.highlightedKey = node.key() }, label = "Choose ${serverName(node)}") {
        Row(Modifier.weight(1.6f).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            ServerAvatar(node, 46.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EllipsisText(serverName(node), White, 15.sp, Modifier.weight(1f, fill = false), FontWeight.SemiBold)
                    if (isMain) { Spacer(Modifier.width(10.dp)); RoutePill(RouteKindLabel.MAIN) }
                    else if (custom) { Spacer(Modifier.width(10.dp)); RoutePill(if (rows.any { it.node.key() == node.key() && it.auto }) RouteKindLabel.AUTO else RouteKindLabel.CUSTOM) }
                }
                val (latency, latencyColor) = latencyText(model.latencies[node.key()], model.pinging && !model.latencies.containsKey(node.key()))
                if (compact) EllipsisText(listOfNotNull(latency, protocolLabel(node), info?.sourceLabel?.ifBlank { null }).joinToString("  ·  "), Muted, 13.sp)
                else EllipsisText(listOfNotNull(protocolLabel(node), info?.sourceLabel?.ifBlank { null }, info?.tag?.takeIf { it.isNotBlank() }?.let { "#$it" }).joinToString("  ·  "), Muted, 13.sp)
            }
        }
        if (!compact) Box(Modifier.width(100.dp)) {
            val (label, color) = latencyText(model.latencies[node.key()], model.pinging && !model.latencies.containsKey(node.key()))
            Text(label, color = color, fontSize = 14.sp, maxLines = 1)
        }
        Box(Modifier.width(150.dp)) { QualityPill(model.scores[node.key()], fontSize = 14.sp) }
        Row(Modifier.width(110.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            val favorite = info?.favorite == true
            IconAction(if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (favorite) "Remove ${serverName(node)} from favorites" else "Add ${serverName(node)} to favorites",
                tint = if (favorite) Color(0xFFF2B01E) else White.copy(.65f), size = 34.dp, bordered = false) { model.toggleFavorite(node) }
            MenuIcon(Icons.Rounded.MoreHoriz, "More actions for ${serverName(node)}", menu, size = 34.dp, tint = White.copy(.75f), bordered = false)
            IconAction(Icons.Rounded.ChevronRight, if (expanded) "Hide the quality details of ${serverName(node)}" else "Show the quality details of ${serverName(node)}",
                Modifier.rotate(if (expanded) 90f else 0f), tint = White.copy(.75f), size = 34.dp, bordered = false) { model.expandedKey = if (expanded) null else node.key() }
        }
    }
    if (expanded) Column(Modifier.fillMaxWidth().background(NeutralOverlay.copy(if (IsDark) .04f else .02f)).padding(horizontal = 20.dp, vertical = 16.dp)) {
        val score = model.scores[node.key()]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Network quality", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp)); QualityPill(score)
            Spacer(Modifier.width(12.dp)); ConfidenceBadge(score)
            Spacer(Modifier.weight(1f))
            Text("Measured by pings, real tunnel tests and checks of live routes", color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
        QualityExplanation(score)
        Spacer(Modifier.height(10.dp))
        val (speed, _) = speedText(model.speeds[node.key()], model.speedTestingKey == node.key())
        QualityMetricList(score, speed, columns = 3, labelWidth = 100.dp)
        GamingSummary(shell, node)
        Spacer(Modifier.height(10.dp))
        val users = model.usedBy(node, rows)
        Text(if (users.isEmpty()) "Not used by a selected app." else "Used by ${joinNames(users.map(::appName), 4)}", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(if (model.retestingKey == node.key()) "Testing…" else "Retest this server", icon = Icons.Rounded.Speed, enabled = model.retestingKey == null) { model.retest(node) }
            SecondaryButton(if (model.speedTestingKey == node.key()) "Testing…" else "Speed test", icon = Icons.Rounded.Download, enabled = !model.speedRunning) { model.runSpeedTest(listOf(node)) }
            PrimaryButton(if (isMain) "This is the main server" else "Use as main server", enabled = !isMain && !shell.busy) { model.useAsMain(node) }
        }
    }
}

@Composable
private fun SimpleImportDialog(shell: Shell, model: ServersModel, session: ImportSession) {
    val preview = session.preview
    val close = { model.closeImport(); shell.closeDialog() }
    DialogCard("Add servers", "Choose how to import your connection details. You review everything before it is saved.", close, width = 680.dp, actions = {
        if (preview != null) SecondaryButton("Back to source") { session.backToSource() }
        SecondaryButton("Cancel", onClick = close)
        PrimaryButton(if (preview == null) "Add servers" else "Add ${plural(preview.newCount.takeIf { it > 0 } ?: preview.candidates.size, "server")}",
            enabled = session.canCommit) { session.commit { close() } }
    }) {
        Segmented(ImportMethod.entries, session.method, { it.label }, Modifier.fillMaxWidth(), icon = { it.icon }, height = 42.dp) { session.choose(it) }
        Spacer(Modifier.height(14.dp))
        if (preview == null) ImportSourceForm(shell, session)
        Spacer(Modifier.height(14.dp))
        SectionLabel("Review")
        Spacer(Modifier.height(8.dp))
        ImportStatusLine(session)
        if (preview != null) {
            Spacer(Modifier.height(10.dp))
            ImportWarnings(preview)
            Spacer(Modifier.height(8.dp))
            preview.candidates.take(60).forEach { candidate ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    ServerAvatar(candidate.node, 34.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        EllipsisText(serverName(candidate.node), White, 14.sp, fontWeight = FontWeight.SemiBold)
                        EllipsisText("${protocolLabel(candidate.node)}  ·  ${candidate.node.endpoint()}", Muted, 12.sp)
                    }
                    if (candidate.alreadySaved) Badge("Already saved", Muted) else Badge("New", Cyan)
                }
                Divider()
            }
            if (preview.candidates.size > 60) Text("and ${preview.candidates.size - 60} more", color = Muted, fontSize = 12.sp)
            if (preview.removedCount > 0) {
                Spacer(Modifier.height(10.dp))
                InfoBanner("${plural(preview.removedCount, "server")} from the previous version of this subscription will be removed. Apps assigned to them will need a replacement.",
                    Modifier.fillMaxWidth(), Amber)
            }
            Spacer(Modifier.height(10.dp))
            Note("Your main server does not change. Favorites, tags and per-app assignments are kept for servers that still exist.")
        }
    }
}
