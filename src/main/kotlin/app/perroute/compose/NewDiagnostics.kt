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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.RouteRule

@Composable
internal fun NewDiagnosticsPage(shell: Shell) {
    val model = diagnosticsModel(shell)
    DiagnosticsEffects(shell, model)
    InspectorEffects(shell, model.inspector)
    val now by rememberNow()
    val tab = shell.diagnosticsTab
    NewSplitPage(panel = {
        when (tab) {
            DiagnosticsTab.Routes -> NewRouteCheckPanel(shell, model, now)
            DiagnosticsTab.Inspector -> NewConnectionPanel(shell, model.inspector)
            DiagnosticsTab.Loaded -> NewRuntimePanel(shell, model)
            DiagnosticsTab.Tester -> NewPredictionPanel(shell, model.tester)
        }
    }) {
        Column(Modifier.fillMaxSize()) {
            NewHeading(listOf("Tools", "Diagnostics"), "Diagnostics", "Check each route without guessing.") {
                PrimaryButton("Run checks", icon = Icons.Rounded.PlayArrow, busy = model.checking) { model.runChecks() }
            }
            Spacer(Modifier.height(16.dp))
            UnderlineTabs(DiagnosticsTab.entries, tab, { it.label }, Modifier.fillMaxWidth()) { shell.diagnosticsTab = it }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    DiagnosticsTab.Routes -> NewRouteChecks(shell, model, now)
                    DiagnosticsTab.Inspector -> NewInspector(shell, model.inspector)
                    DiagnosticsTab.Loaded -> NewLoadedRules(shell, model)
                    DiagnosticsTab.Tester -> NewRuleTester(shell, model.tester)
                }
            }
        }
    }
}

@Composable
private fun NewRouteChecks(shell: Shell, model: DiagnosticsModel, now: Long) {
    val entries = model.entries()
    if (model.selectedRoute == null || entries.none { it.id == model.selectedRoute }) model.selectedRoute = entries.firstOrNull { it.node != null }?.id ?: entries.first().id
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val (summary, good) = model.summary(entries, now)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val failed = entries.any { it.node != null && it.check?.state == RouteCheckState.FAILED }
            Icon(if (good) Icons.Rounded.CheckCircle else if (failed) Icons.Rounded.WarningAmber else Icons.Rounded.Shield, null, tint = if (good) Cyan else if (failed) Amber else Muted, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(summary, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            val last = entries.mapNotNull { it.check?.takeIf { c -> c.state == RouteCheckState.PASSED || c.state == RouteCheckState.FAILED }?.atMillis }.maxOrNull()
            if (last != null) Text("  ·  Last checked ${formatAgo(last, now)}", color = Muted, fontSize = 12.5.sp)
        }
        Spacer(Modifier.height(12.dp))
        NewTableHeader {
            HeaderText("Connection", Modifier.weight(1.5f))
            HeaderText("Public IP", Modifier.weight(1f))
            HeaderText("Result", Modifier.width(150.dp))
            Box(Modifier.width(80.dp))
        }
        entries.forEach { entry ->
            NewTableRow(model.selectedRoute == entry.id, onClick = { model.selectedRoute = entry.id }, label = "Details of ${entry.title}") {
                Row(Modifier.weight(1.5f).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RouteEntryAvatar(entry, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EllipsisText(entry.title, White, 13.5.sp, Modifier.weight(1f, fill = false), FontWeight.SemiBold)
                            entry.kind?.let { if (it != RouteKindLabel.MAIN) { Spacer(Modifier.width(7.dp)); RoutePill(it) } }
                        }
                        EllipsisText(entry.subtitle, Muted, 11.5.sp)
                    }
                }
                Column(Modifier.weight(1f)) {
                    val (ip, country) = publicIpText(entry)
                    Text(ip, color = White, fontSize = 12.5.sp, fontFamily = if (entry.check?.publicIp != null) FontFamily.Monospace else null, maxLines = 1)
                    if (country.isNotBlank()) Text(country, color = Muted, fontSize = 11.sp, maxLines = 1)
                }
                RouteCheckStatus(entry.check, entry.checkable, now, Modifier.width(150.dp))
                Row(Modifier.width(80.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (entry.checkable) {
                        if (entry.check == null || entry.check.state == RouteCheckState.NOT_CHECKED) SecondaryButton("Check", height = 32.dp) { model.recheck(entry.id) }
                        else IconAction(Icons.Rounded.Refresh, "Check ${entry.title} again", size = 30.dp, bordered = false, tint = White.copy(.75f)) { model.recheck(entry.id) }
                    }
                }
            }
        }
        if (!shell.connected && entries.size > 1) {
            Spacer(Modifier.height(10.dp))
            Note("Not connected: servers can only be checked through the running connection. Their last results are cleared when you disconnect.")
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().pressable(RoundedCornerShape(4.dp), label = if (model.dnsOpen) "Hide system DNS details" else "Show system DNS details") { model.dnsOpen = !model.dnsOpen }
            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (model.dnsOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("System DNS details", color = White, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("Resolvers and lookup results.", color = Muted, fontSize = 12.sp)
        }
        if (model.dnsOpen) Column(Modifier.padding(start = 30.dp, bottom = 10.dp)) {
            val dns = model.dns
            if (dns == null) Text("Not looked up yet. Select Run checks.", color = Muted, fontSize = 12.sp)
            else {
                DetailLine("System DNS servers", dns.systemServers.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "Unknown", 190.dp)
                DetailLine("Resolver seen by public services", dns.publicResolver ?: "Unknown", 190.dp)
                DetailLine("Looked up", formatAgo(dns.atMillis, now), 190.dp)
            }
            Spacer(Modifier.height(6.dp))
            Text(model.dnsExplanation(), color = Muted, fontSize = 11.5.sp, lineHeight = 16.sp)
        }
        Divider()
    }
}

@Composable
private fun ColumnScope.NewRouteCheckPanel(shell: Shell, model: DiagnosticsModel, now: Long) {
    val entry = model.entries().firstOrNull { it.id == model.selectedRoute }
    if (entry == null) { NewPanelEmpty("Route check", "No route selected", "Select a route to see what was observed on it."); return }
    val check = entry.check
    val (status, color) = when {
        !entry.checkable -> "Not connected" to Muted
        check == null || check.state == RouteCheckState.NOT_CHECKED -> "Not checked yet" to Muted
        check.state == RouteCheckState.CHECKING -> "Checking…" to Muted
        check.stale(now) -> "Stale" to Amber
        check.state == RouteCheckState.PASSED -> "Passed" to Cyan
        else -> "Check failed" to Amber
    }
    NewPanelHeading("Route check", entry.title, entry.subtitle, leading = { RouteEntryAvatar(entry, 50.dp) })
    NewPanelSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).background(color.copy(.16f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (color == Cyan) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber, null, tint = color, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(status, color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        if (check?.state == RouteCheckState.FAILED && entry.checkable) {
            Spacer(Modifier.height(8.dp))
            Text("This route did not answer: ${check.detail.ifBlank { "no response" }}", color = Amber, fontSize = 12.sp, lineHeight = 17.sp)
        }
        if (check?.stale(now) == true) {
            Spacer(Modifier.height(8.dp))
            Text("This result is older than five minutes and may no longer be true.", color = Amber, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
    NewPanelSection { RouteCheckFacts(entry, now, 110.dp) }
    Spacer(Modifier.height(16.dp))
    if (entry.checkable) {
        PrimaryButton("Check again", Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh) { model.recheck(entry.id) }
        if (check?.state == RouteCheckState.PASSED && check.publicIp == null) {
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Lookup public IP", Modifier.fillMaxWidth(), icon = Icons.Rounded.Public) { model.recheck(entry.id) }
        }
    } else Text("Connect, then select Run checks to observe this server.", color = Muted, fontSize = 12.sp)
    NewPanelSection {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Shield, null, tint = White.copy(.8f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(model.killSwitchText(), color = White.copy(.9f), fontSize = 12.5.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.height(12.dp))
        Note("These checks are not a leak test. A passed check only shows the route is working at this time.")
    }
}

@Composable
private fun NewInspector(shell: Shell, model: InspectorModel) {
    if (InspectorUnavailable(shell, Modifier.fillMaxSize())) return
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(model.query, { model.query = it }, "Search connections", Modifier.weight(1f), Icons.Rounded.Search)
            MenuButton(model.appFilter?.let(::appName) ?: "All apps", model.appEntries(), Modifier.width(120.dp), accessibleLabel = "Filter by app")
            MenuButton(model.routeFilter ?: "All routes", model.routeEntries(), Modifier.width(124.dp), accessibleLabel = "Filter by route")
            MenuButton(model.statusFilter.label, model.statusEntries(), Modifier.width(134.dp), accessibleLabel = "Filter by status")
        }
        Spacer(Modifier.height(10.dp))
        val filtered = model.filtered()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Segmented(listOf(true, false), model.live, { if (it) "Live" else "Paused" }, Modifier.width(170.dp), height = 34.dp,
                icon = { if (it) Icons.Rounded.PlayArrow else Icons.Rounded.Pause }) { model.live = it }
            Spacer(Modifier.width(12.dp))
            Text(if (model.live) "Updates every 2s  ·  ${plural(filtered.size, "connection")}" else "Paused  ·  ${plural(filtered.size, "connection")}", color = Muted, fontSize = 12.sp)
        }
        if (!shell.controller.inspectorUsesGateway()) {
            Spacer(Modifier.height(8.dp))
            InfoBanner("The traffic gateway is off, so connections come from the core's log only: no byte counts and no close events. Turn it on in Settings > Routing.",
                Modifier.fillMaxWidth(), Amber)
        }
        Spacer(Modifier.height(8.dp))
        NewTableHeader {
            HeaderText("App", Modifier.weight(1f))
            HeaderText("Destination", Modifier.weight(1.3f))
            HeaderText("Route", Modifier.weight(1.2f))
            HeaderText("Status", Modifier.width(100.dp))
            HeaderText("Live traffic", Modifier.width(90.dp))
        }
        if (filtered.isEmpty()) EmptyState(Icons.Rounded.Timeline, if (model.rows.isEmpty()) "No connections yet" else "No connections match",
            if (model.rows.isEmpty()) "Use one of the selected apps; its connections appear here within a couple of seconds." else "Nothing fits the current search and filters.",
            Modifier.fillMaxSize())
        else LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { row ->
                NewTableRow(row.id == model.selectedId, onClick = { model.selectedId = row.id; model.moreOpen = false }, label = "Details of a connection of ${appName(row.applicationPath)}") {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        AppAvatar(fileName(row.applicationPath), row.applicationPath, 28.dp)
                        Spacer(Modifier.width(9.dp))
                        Column {
                            EllipsisText(appName(row.applicationPath), White, 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(row.protocol.ifBlank { "?" }, color = Muted, fontSize = 11.sp)
                        }
                    }
                    Column(Modifier.weight(1.3f).padding(end = 6.dp)) {
                        EllipsisText(row.destination, White, 12.sp)
                        val domain = row.observed.sniffedDomain
                        if (domain != null && !domain.equals(row.destinationHost, true)) EllipsisText(domain, Muted, 10.5.sp)
                        else if (row.udpDestinations > 1) Text("${row.udpDestinations} destinations", color = Muted, fontSize = 10.5.sp)
                    }
                    Box(Modifier.weight(1.2f).padding(end = 6.dp)) { RouteFacts(row) }
                    Box(Modifier.width(100.dp)) { InspectorStatusLabel(row) }
                    Box(Modifier.width(90.dp)) { LiveTraffic(row) }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.NewConnectionPanel(shell: Shell, model: InspectorModel) {
    val clipboard = LocalClipboardManager.current
    val row = model.selected
    if (row == null) {
        NewPanelEmpty("Connection details", "No connection selected",
            "Select a connection to see what PerRoute is configured to do with it, what the core observed, and what the last route check verified. The three are kept apart.")
        Spacer(Modifier.height(18.dp))
        Note("Connection details stay in memory. Nothing is recorded or kept after you disconnect.")
        return
    }
    val pids = androidx.compose.runtime.remember(row.applicationPath) { shell.controller.processIds(row.applicationPath) }
    NewPanelHeading("Connection details", appName(row.applicationPath), row.destination + if (pids.isNotEmpty()) "  ·  PID ${pids.joinToString(", ")}" else "",
        leading = { AppAvatar(fileName(row.applicationPath), row.applicationPath, 46.dp, framed = true) })
    NewPanelSection { FactHeading(FactKind.CONFIGURED); Spacer(Modifier.height(8.dp)); FactLines(FactKind.CONFIGURED, row) }
    NewPanelSection {
        FactHeading(FactKind.OBSERVED); Spacer(Modifier.height(8.dp)); FactLines(FactKind.OBSERVED, row, more = model.moreOpen)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.pressable(RoundedCornerShape(4.dp), label = "More connection details") { model.moreOpen = !model.moreOpen }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(if (model.moreOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (model.moreOpen) "Fewer connection details" else "More connection details", color = White, fontSize = 12.5.sp)
        }
    }
    NewPanelSection { FactHeading(FactKind.VERIFIED); Spacer(Modifier.height(8.dp)); FactLines(FactKind.VERIFIED, row) }
    Spacer(Modifier.height(16.dp))
    Note("Connection details stay in memory.")
    Spacer(Modifier.height(14.dp))
    SecondaryButton("Copy details", Modifier.fillMaxWidth(), icon = Icons.Rounded.ContentCopy) { model.copy(row, clipboard) }
}

@Composable
private fun NewLoadedRules(shell: Shell, model: DiagnosticsModel) {
    val rules = shell.controller.rules()
    if (rules.isEmpty()) {
        EmptyState(Icons.Rounded.Description, "No routing rules", "Add rules in Rules or apply a preset in Profiles.", Modifier.fillMaxSize()) {
            SecondaryButton("Open Rules") { shell.navigate(Tab.Rules) }
        }
        return
    }
    if (model.selectedRule == null || rules.none { it.id() == model.selectedRule }) model.selectedRule = rules.first().id()
    val states = rules.map { loadedState(it, model.loadedRuleIds) }
    Column(Modifier.fillMaxSize()) {
        Text(if (shell.connected) "${states.count { it.tone == 1 }} active  ·  ${states.count { it.tone == 2 }} not loaded  ·  ${states.count { it.label == "Off" }} off"
            else "Not connected: nothing is loaded. This is what the next connection will load.", color = White, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        NewTableHeader {
            HeaderText("#", Modifier.width(30.dp))
            HeaderText("Rule", Modifier.weight(1.6f))
            HeaderText("Configured", Modifier.weight(1f))
            HeaderText("Runtime", Modifier.width(120.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rules.indices.toList(), key = { rules[it].id() }) { index ->
                val rule = rules[index]
                val state = states[index]
                NewTableRow(model.selectedRule == rule.id(), onClick = { model.selectedRule = rule.id() }, label = "Runtime detail of ${PerRouteController.ruleDisplayName(rule)}") {
                    Text("${index + 1}", color = Muted, fontSize = 13.sp, modifier = Modifier.width(30.dp))
                    Column(Modifier.weight(1.6f).padding(end = 12.dp)) {
                        EllipsisText(PerRouteController.ruleDisplayName(rule), White, 13.sp, fontWeight = FontWeight.SemiBold)
                        EllipsisText("${ruleTarget(rule)}  ·  ${rule.value()}", Muted, 11.5.sp)
                    }
                    Text(if (rule.enabled()) "On  ·  ${actionLabel(rule.action())}" else "Off", color = White.copy(.9f), fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                    Row(Modifier.width(120.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(loadedColor(state), 9.dp); Spacer(Modifier.width(7.dp))
                        Text(state.label, color = loadedColor(state), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.NewRuntimePanel(shell: Shell, model: DiagnosticsModel) {
    val rule = shell.controller.rules().firstOrNull { it.id() == model.selectedRule }
    if (rule == null) { NewPanelEmpty("Runtime detail", "No rule selected", "Select a rule to compare its saved setting with what the running core loaded."); return }
    val state = loadedState(rule, model.loadedRuleIds)
    NewPanelHeading("Runtime detail", PerRouteController.ruleDisplayName(rule), ruleMatchLine(rule))
    NewPanelSection {
        NewFact("Applies to", ruleTarget(rule))
        NewFact("Action", actionLabel(rule.action()))
        NewFact("Configured", if (rule.enabled()) "On" else "Off")
        NewFact("Runtime", state.label, loadedColor(state))
        NewFact("Source", if (PerRouteController.isScenarioRule(rule)) "Preset rule" else "Your rule")
    }
    NewPanelSection("Why") { Text(state.why, color = White.copy(.9f), fontSize = 12.5.sp, lineHeight = 18.sp) }
    Spacer(Modifier.height(18.dp))
    SecondaryButton("Open in Rules", Modifier.fillMaxWidth(), trailing = Icons.AutoMirrored.Rounded.ArrowForward) { shell.navigate(Tab.Rules) }
    if (shell.locked) { Spacer(Modifier.height(12.dp)); NewLockNote("Rules can be changed while disconnected.") }
    Spacer(Modifier.height(12.dp))
    Note(if (shell.connected) "Compared with the config the running core was started with." else "Not connected: nothing is loaded.")
}

@Composable
private fun NewRuleTester(shell: Shell, model: RuleTesterModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(end = 4.dp)) {
        Text("Test a destination", color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text("See which route and rule would be used. It reads your saved rules; it does not open a connection.", color = Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(18.dp))
        FieldLabel("Application")
        MenuButton(if (model.app.isBlank()) "Any routed app" else appName(model.app), model.appEntries(), Modifier.fillMaxWidth(), icon = Icons.Rounded.Apps, accessibleLabel = "Application to test")
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                FieldLabel("Destination")
                TextInput(model.destination, { model.destination = it; model.clear() }, "Domain or IP, for example github.com", icon = Icons.Rounded.Public)
            }
            Column(Modifier.width(110.dp)) {
                FieldLabel("Port")
                TextInput(model.port, { value -> model.port = value.filter(Char::isDigit).take(5); model.clear() }, "443")
            }
        }
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Check route", Modifier.width(200.dp), icon = Icons.Rounded.Search, enabled = model.destination.isNotBlank()) { model.check() }
        Spacer(Modifier.height(18.dp))
        Note("Rules are evaluated in order and the first match wins. Unselected apps are never routed, whatever the rules say.")
    }
}

@Composable
private fun ColumnScope.NewPredictionPanel(shell: Shell, model: RuleTesterModel) {
    val clipboard = LocalClipboardManager.current
    val result = model.verdict
    if (result == null) {
        NewPanelEmpty("Predicted result", "Nothing tested yet", "Choose an application and a destination, then select Check route.")
        return
    }
    val now by rememberNow()
    SectionLabel("Predicted result", White.copy(.85f))
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(actionIcon(result.action()), null, tint = if (result.action() == RouteRule.Action.BLOCK) Red else NewColors.Lime, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(12.dp))
        Text(model.headline(result), color = White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Chip(if (model.app.isBlank()) "Any routed app" else appName(model.app))
        Spacer(Modifier.width(8.dp))
        ArrowGlyph(White.copy(.7f))
        Spacer(Modifier.width(8.dp))
        Chip(model.routeLabel(result))
    }
    NewPanelSection {
        NewFact("Expected route", model.routeLabel(result))
        NewFact("Route mode", model.routeMode())
        NewFact("Matched rule", result.rule()?.let { PerRouteController.ruleDisplayName(it) } ?: "None")
        NewFact("Rule order", model.ruleOrder(result))
        NewFact("Source", model.source(result))
        NewFact("Match", if (result.certain()) "Certain" else "Probable")
    }
    if (result.rule() == null) { Spacer(Modifier.height(10.dp)); Text(result.reason(), color = Muted, fontSize = 12.sp, lineHeight = 17.sp) }
    if (!result.certain()) {
        Spacer(Modifier.height(10.dp))
        Text("Could not be evaluated locally and may match first: " + result.uncertain().joinToString(", ") { PerRouteController.ruleDisplayName(it) },
            color = Amber, fontSize = 12.sp, lineHeight = 17.sp)
    }
    if (result.action() == RouteRule.Action.PROXY && model.app.isBlank() && shell.plan.advancedEffective()) {
        Spacer(Modifier.height(10.dp))
        Text("Shown for apps on the main server. Choose an app to see its own server.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
    }
    Spacer(Modifier.height(14.dp))
    val check = model.target?.let { shell.routeChecks[it.key()] }
    Note(when {
        result.action() != RouteRule.Action.PROXY -> "Routing decision only."
        model.missing -> "Routing decision only. This app's custom server is missing, so it cannot connect until you choose a replacement."
        shell.connected && check?.state == RouteCheckState.FAILED -> "Routing decision only. This server's connection check failed ${formatAgo(check.atMillis, now)}."
        else -> "Routing decision only. It predicts the rule, not whether the server is reachable."
    })
    Spacer(Modifier.height(14.dp))
    SecondaryButton("Copy result", Modifier.fillMaxWidth(), icon = Icons.Rounded.ContentCopy) {
        clipboard.setText(AnnotatedString(model.copyText(result)))
        shell.notify(ActionResult(true, "Result copied."))
    }
}

@Composable
private fun Chip(text: String) {
    Text(text, color = White, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.border(1.dp, Line, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp))
}

