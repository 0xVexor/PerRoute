package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
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
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.ProxyNode

@Composable
internal fun NewServersPage(shell: Shell) {
    val model = serversModel(shell)
    val listState = rememberLazyListState()
    ServersEffects(shell, model, listState)
    val session = model.import
    if (session != null) { NewImportPage(shell, model, session); return }
    val clipboard = LocalClipboardManager.current
    val meta = remember(shell.nodes.toList(), model.metaRevision) { model.meta() }
    val tags = model.allTags(meta)
    val rows = routeRows(shell)
    val (filtered, total) = model.filtered(meta)
    val highlighted = shell.nodes.firstOrNull { it.key() == model.highlightedKey }
    NewSplitPage(panel = { NewServerPanel(shell, model, highlighted, meta, rows, tags) }) {
        val pageWidth = maxWidth
        Column(Modifier.fillMaxSize()) {
            NewHeading(listOf("Server library"), "Servers", "Test, compare and choose your main server.") {
                SecondaryButton("Update subscriptions", icon = Icons.Rounded.Refresh, enabled = !shell.locked && model.subscriptions().isNotEmpty() && !shell.busy) { model.updateSubscriptions() }
                PrimaryButton("Add servers", icon = Icons.Rounded.Add) { model.openImport() }
            }
            Spacer(Modifier.height(20.dp))
            val oneRow = pageWidth >= 860.dp
            val sortMenu: @Composable () -> Unit = {
                MenuButton(model.sort.label, ServerSort.entries.map { sort -> MenuEntry(sort.label, selected = model.sort == sort) { model.sort = sort } },
                    Modifier.width(170.dp), prefix = "Sort:", accessibleLabel = "Sort servers: ${model.sort.label}", height = if (oneRow) 40.dp else 36.dp)
            }
            val testMenu: @Composable () -> Unit = {
                MenuButton(model.testLabel, model.testMenu(), Modifier.width(150.dp), accessibleLabel = "Test servers", menuWidth = 260.dp, height = if (oneRow) 40.dp else 36.dp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("All", model.sourceFilter == FILTER_ALL) { model.sourceFilter = FILTER_ALL }
                FilterChip("Favorites", model.sourceFilter == FILTER_FAVORITES) { model.sourceFilter = FILTER_FAVORITES }
                SourceChip(model, meta)
                TextInput(model.query, { model.query = it }, "Search servers", Modifier.weight(1f), Icons.Rounded.Search)
                if (oneRow) { sortMenu(); testMenu() }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(if (oneRow) 28.dp else 38.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${filtered.size} of ${plural(total, "server")}", color = Muted, fontSize = 12.sp)
                if (model.hideUnavailable) { Spacer(Modifier.width(8.dp)); Badge("Failed hidden", Amber) }
                Spacer(Modifier.weight(1f))
                if (model.sourceFilter in model.subscriptions()) {
                    SubscriptionAlert(shell, model.sourceFilter)
                    IconAction(Icons.Rounded.Info, "Subscription details: usage, expiry and changes", size = 28.dp, bordered = false) { showSubscriptionDialog(shell, model.sourceFilter) }
                    IconAction(Icons.Rounded.ContentCopy, "Copy this subscription URL", size = 28.dp, bordered = false) {
                        clipboard.setText(AnnotatedString(model.sourceFilter))
                        shell.notify(ActionResult(true, "Subscription URL copied."))
                    }
                    IconAction(Icons.Rounded.DeleteOutline, if (shell.locked) "Disconnect to remove this subscription" else "Remove this subscription and its servers",
                        enabled = !shell.locked, size = 28.dp, bordered = false) { model.removeSubscription(model.sourceFilter) }
                }
                if (!oneRow) { sortMenu(); testMenu() }
            }
            NewTableHeader {
                HeaderText("Server", Modifier.weight(1.6f))
                HeaderText("Latency", Modifier.width(if (LocalCompact.current) 70.dp else 84.dp))
                HeaderText("Quality", Modifier.width(if (LocalCompact.current) 118.dp else 128.dp))
                if (!LocalCompact.current) HeaderText("Used by", Modifier.weight(.8f))
                Box(Modifier.width(66.dp))
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
                        items(filtered, key = { it.key() }) { node ->
                            val info = meta[node.key()]
                            NewServerRow(shell, model, node, info, model.usedBy(node, rows), model.rowMenu(node, clipboard, tags, info?.tag.orEmpty()))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceChip(model: ServersModel, meta: Map<String, ServerMeta>) {
    var open by remember { mutableStateOf(false) }
    val options = buildList {
        model.subscriptions().distinct().forEach { add(it) }
        if (meta.values.any { it.source == PerRouteController.MANUAL_SOURCE }) add(PerRouteController.MANUAL_SOURCE)
        if (meta.values.any { it.source == PerRouteController.IMPORTED_SOURCE }) add(PerRouteController.IMPORTED_SOURCE)
        addAll(model.groupFilters(meta))
    }
    val active = model.sourceFilter != FILTER_ALL && model.sourceFilter != FILTER_FAVORITES
    Box {
        FilterChip(if (active) model.filterLabel(model.sourceFilter) else "Subscription", active, enabled = options.isNotEmpty(), trailing = Icons.Rounded.KeyboardArrowDown) { open = true }
        PopupMenu(open, { open = false }, options.map { option ->
            MenuEntry(model.filterLabel(option), selected = model.sourceFilter == option,
                detail = when {
                    option.startsWith("tag:") -> "Tag"
                    option.startsWith("country:") -> "Country"
                    option == PerRouteController.MANUAL_SOURCE || option == PerRouteController.IMPORTED_SOURCE -> "Added by hand"
                    else -> "Subscription"
                }) { model.sourceFilter = option }
        }, minWidth = 240.dp)
    }
}

@Composable
private fun NewServerRow(shell: Shell, model: ServersModel, node: ProxyNode, info: ServerMeta?, usedBy: List<String>, menu: List<MenuEntry>) {
    val isMain = node.key() == shell.mainNode?.key()
    val highlighted = node.key() == model.highlightedKey
    NewTableRow(highlighted, onClick = { model.highlightedKey = node.key() }, label = "Details of ${serverName(node)}") {
        Row(Modifier.weight(1.6f).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            ServerAvatar(node, 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EllipsisText(serverName(node), White, 13.5.sp, Modifier.weight(1f, fill = false), FontWeight.SemiBold)
                    if (isMain) { Spacer(Modifier.width(8.dp)); RoutePill(RouteKindLabel.MAIN) }
                }
                EllipsisText(listOfNotNull(protocolLabel(node), info?.sourceLabel?.ifBlank { null }, info?.tag?.takeIf { it.isNotBlank() }?.let { "#$it" }).joinToString("  ·  "), Muted, 11.5.sp)
            }
        }
        val compact = LocalCompact.current
        Box(Modifier.width(if (compact) 70.dp else 84.dp)) {
            val (label, color) = latencyText(model.latencies[node.key()], model.pinging && !model.latencies.containsKey(node.key()))
            Text(label, color = color, fontSize = 12.5.sp, maxLines = 1)
        }
        Box(Modifier.width(if (compact) 118.dp else 128.dp)) { QualityPill(model.scores[node.key()], compact = true) }
        if (!compact) Row(Modifier.weight(.8f), verticalAlignment = Alignment.CenterVertically) {
            if (usedBy.isEmpty()) Text("—", color = Muted, fontSize = 12.sp)
            else Tooltip(usedBy.joinToString("\n") { appName(it) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    usedBy.take(3).forEach { path -> AppAvatar(fileName(path), path, 22.dp); Spacer(Modifier.width(3.dp)) }
                    if (usedBy.size > 3) Text("+${usedBy.size - 3}", color = Muted, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        val favorite = info?.favorite == true
        IconAction(if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            if (favorite) "Remove ${serverName(node)} from favorites" else "Add ${serverName(node)} to favorites",
            tint = if (favorite) Color(0xFFF2B01E) else White.copy(.6f), size = 30.dp, bordered = false) { model.toggleFavorite(node) }
        MenuIcon(Icons.Rounded.MoreHoriz, "More actions for ${serverName(node)}", menu, size = 30.dp, tint = White.copy(.7f), bordered = false)
    }
}

@Composable
private fun ColumnScope.NewServerPanel(shell: Shell, model: ServersModel, node: ProxyNode?, meta: Map<String, ServerMeta>, rows: List<RouteRow>, tags: List<String>) {
    val main = shell.mainNode
    if (node == null) {
        NewPanelEmpty("Server detail", "No server selected", "Select a server to see its quality, what it is measured on, and which apps use it.")
        return
    }
    val score = model.scores[node.key()]
    val info = meta[node.key()]
    NewPanelHeading("Server detail", serverName(node), listOfNotNull(protocolLabel(node), info?.sourceLabel?.ifBlank { null }).joinToString("  ·  "),
        leading = { ServerAvatar(node, 52.dp) })
    NewPanelSection {
        Row(verticalAlignment = Alignment.Bottom) {
            val measured = score != null && score.label() != app.perroute.quality.QualityLabel.UNKNOWN
            Text(if (measured) score!!.score().toString() else "—", color = White, fontSize = 44.sp, lineHeight = 46.sp, fontWeight = FontWeight.ExtraBold)
            Text(" /100", color = Muted, fontSize = 16.sp, modifier = Modifier.padding(bottom = 7.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((score?.label() ?: app.perroute.quality.QualityLabel.UNKNOWN).text(), color = qualityColor(score), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("   ·   Confidence ", color = Muted, fontSize = 12.sp)
            ConfidenceBadge(score)
        }
        Spacer(Modifier.height(6.dp))
        QualityExplanation(score)
    }
    NewPanelSection("Measurements") {
        val (speed, _) = speedText(model.speeds[node.key()], model.speedTestingKey == node.key())
        QualityMetricList(score, speed)
    }
    GamingSummary(shell, node)
    NewPanelSection("Used by") {
        val users = model.usedBy(node, rows)
        if (users.isEmpty()) Text(if (shell.perApp) "No selected app uses this server." else "Used by ${modeLabel(shell.mode)} when it is the main server.",
            color = Muted, fontSize = 12.sp)
        else users.forEach { path ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                AppAvatar(fileName(path), path, 22.dp)
                Spacer(Modifier.width(9.dp))
                EllipsisText(appName(path), White, 12.5.sp)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(if (model.retestingKey == node.key()) "Testing…" else "Retest this server", Modifier.weight(1f), icon = Icons.Rounded.Speed,
            enabled = model.retestingKey == null) { model.retest(node) }
        SecondaryButton(if (model.speedTestingKey == node.key()) "Testing…" else "Speed test", Modifier.weight(1f), icon = Icons.Rounded.Download,
            enabled = !model.speedRunning) { model.runSpeedTest(listOf(node)) }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        val current = info?.tag.orEmpty()
        LinkAction(if (current.isBlank()) "Add a tag" else "Tag: #$current", arrow = false, fontSize = 12.5.sp) { model.editTag(node, tags) }
    }
    Spacer(Modifier.height(22.dp))
    Divider()
    Spacer(Modifier.height(16.dp))
    SectionLabel("Current main", White.copy(.8f))
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        ServerAvatar(main, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            EllipsisText(main?.let(::serverName) ?: "No main server", White, 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(if (shell.connected) "Switching replaces only the main route." else "Custom app routes stay unchanged.", color = Muted, fontSize = 11.5.sp)
        }
    }
    Spacer(Modifier.height(14.dp))
    PrimaryButton(if (node.key() == main?.key()) "This is the main server" else "Use as main server", Modifier.fillMaxWidth(),
        enabled = node.key() != main?.key() && !shell.busy) { model.useAsMain(node) }
}

@Composable
private fun NewImportPage(shell: Shell, model: ServersModel, session: ImportSession) {
    val preview = session.preview
    NewSplitPage(
        panel = { NewImportSummary(session) },
        bottomBar = {
            SecondaryButton("Back", Modifier.width(150.dp), icon = Icons.AutoMirrored.Rounded.ArrowBack) {
                if (preview != null) session.backToSource() else model.closeImport()
            }
            SecondaryButton("Cancel", Modifier.width(140.dp)) { model.closeImport() }
            Spacer(Modifier.weight(1f))
            PrimaryButton(if (preview == null) "Add servers" else "Add ${plural(preview.newCount.takeIf { it > 0 } ?: preview.candidates.size, "server")}",
                Modifier.width(190.dp), enabled = session.canCommit, height = 48.dp) { session.commit { model.closeImport() } }
        }
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            NewHeading(listOf("Server library"), "Add servers", "Review what was found before adding.")
            Spacer(Modifier.height(20.dp))
            Segmented(ImportMethod.entries, session.method, { it.label }, Modifier.fillMaxWidth(.75f), icon = { it.icon }, height = 46.dp) { session.choose(it) }
            Spacer(Modifier.height(16.dp))
            if (preview == null) {
                ImportSourceForm(shell, session)
                Spacer(Modifier.height(14.dp))
                ImportStatusLine(session)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextInput(if (preview.replaceSource) preview.source else session.sourceLabel(), {}, "", Modifier.weight(1f), enabled = false)
                    Spacer(Modifier.width(14.dp))
                    LinkAction("Back to source", fontSize = 13.sp) { session.backToSource() }
                }
                Spacer(Modifier.height(14.dp))
                ImportWarnings(preview)
                if (preview.warnings.isNotEmpty()) Spacer(Modifier.height(14.dp))
                NewTableHeader {
                    HeaderText("Server", Modifier.weight(1.4f))
                    HeaderText("Protocol", Modifier.width(100.dp))
                    HeaderText("Status", Modifier.width(130.dp))
                    HeaderText("Notes", Modifier.weight(1.2f))
                }
                preview.candidates.take(200).forEach { candidate ->
                    NewTableRow(false) {
                        Row(Modifier.weight(1.4f).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            ServerAvatar(candidate.node, 30.dp)
                            Spacer(Modifier.width(11.dp))
                            EllipsisText(serverName(candidate.node), White, 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(protocolLabel(candidate.node), color = White, fontSize = 12.5.sp, modifier = Modifier.width(100.dp))
                        Box(Modifier.width(130.dp)) {
                            if (candidate.alreadySaved) Badge("Already saved", Muted) else Badge("New", if (IsDark) NewColors.Lime else Color(0xFF4F5E05))
                        }
                        Text(if (candidate.alreadySaved) "This server is already in your library." else "Will be added to your library.",
                            color = Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1.2f))
                    }
                }
                if (preview.candidates.size > 200) Text("and ${preview.candidates.size - 200} more", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun ColumnScope.NewImportSummary(session: ImportSession) {
    val preview = session.preview
    SectionLabel("Import summary", White.copy(.85f))
    Spacer(Modifier.height(16.dp))
    if (preview == null) {
        Text("Nothing found yet", color = White, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("Choose a source and read it. You see every server and every skipped link here before anything is saved.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        return
    }
    Text(plural(preview.candidates.size, "server") + " found", color = White, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(14.dp))
    SummaryCount(Cyan, "${plural(preview.newCount, "new server")}", preview.newCount)
    Divider()
    SummaryCount(Muted, "already saved", preview.candidates.size - preview.newCount)
    Divider()
    SummaryCount(Muted, "removed", preview.removedCount)
    NewPanelSection {
        Text("Source", color = Muted, fontSize = 12.5.sp)
        Spacer(Modifier.height(3.dp))
        Text(session.sourceLabel(), color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (preview.replaceSource) EllipsisText(preview.source, White.copy(.85f), 12.5.sp)
    }
    NewPanelSection {
        Text("Parser warnings", color = Muted, fontSize = 12.5.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (preview.warnings.isNotEmpty()) { Icon(Icons.Rounded.WarningAmber, null, tint = Amber, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(9.dp)) }
            Text(if (preview.warnings.isEmpty()) "No links were skipped" else "${plural(preview.warnings.size, "unsupported link")} skipped",
                color = White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("${preview.warnings.size}", color = White, fontSize = 13.sp)
        }
    }
    NewPanelSection("Review before adding.") {
        Text("The ${plural(preview.newCount, "new server")} will be added to your library. Your main server will not change.", color = White.copy(.85f), fontSize = 12.5.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text(when {
            !preview.replaceSource -> "Servers you already have are kept as they are."
            preview.removedCount > 0 -> "${plural(preview.removedCount, "server")} from the previous version of this subscription will be removed. Apps assigned to them will need a replacement."
            else -> "No existing servers will be removed."
        }, color = if (preview.removedCount > 0) Amber else White.copy(.85f), fontSize = 12.5.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text("Favorites, tags and per-app assignments are kept for servers that still exist.", color = White.copy(.85f), fontSize = 12.5.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun SummaryCount(color: Color, label: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(color, CircleShape))
        Spacer(Modifier.width(12.dp))
        Text(if (label.first().isDigit()) label else "$count $label", color = White, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
        Text("$count", color = White, fontSize = 14.5.sp)
    }
}
