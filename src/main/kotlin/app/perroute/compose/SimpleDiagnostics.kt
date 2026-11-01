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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.RouteRule

@Composable
internal fun SimpleDiagnosticsPage(shell: Shell) {
    val model = diagnosticsModel(shell)
    DiagnosticsEffects(shell, model)
    InspectorEffects(shell, model.inspector)
    val now by rememberNow()
    SimplePage("Diagnostics", "Check each route without guessing.", actions = {
        PrimaryButton("Run checks", icon = Icons.Rounded.PlayArrow, busy = model.checking, height = 50.dp) { model.runChecks() }
    }) {
        UnderlineTabs(DiagnosticsTab.entries, shell.diagnosticsTab, { it.label }, Modifier.fillMaxWidth()) { shell.diagnosticsTab = it }
        Spacer(Modifier.height(20.dp))
        when (shell.diagnosticsTab) {
            DiagnosticsTab.Routes -> SimpleRouteChecks(shell, model, now)
            DiagnosticsTab.Inspector -> SimpleInspector(shell, model.inspector)
            DiagnosticsTab.Loaded -> SimpleLoadedRules(shell, model)
            DiagnosticsTab.Tester -> SimpleTester(shell, model.tester, now)
        }
    }
}

@Composable
private fun SimpleRouteChecks(shell: Shell, model: DiagnosticsModel, now: Long) {
    val entries = model.entries()
    SimpleCard(padding = 20.dp) {
        val (summary, good) = model.summary(entries, now)
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (good) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber, null, tint = if (good) Cyan else Amber, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(14.dp))
            Text(summary, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            val last = entries.mapNotNull { it.check?.takeIf { c -> c.state == RouteCheckState.PASSED || c.state == RouteCheckState.FAILED }?.atMillis }.maxOrNull()
            if (last != null) Text("  ·  Last checked ${formatAgo(last, now)}", color = Muted, fontSize = 14.sp)
        }
        Spacer(Modifier.height(14.dp))
        SimpleTableHeader {
            HeaderText("Connection", Modifier.weight(1.5f))
            HeaderText("Public IP", Modifier.weight(1f))
            HeaderText("Result", Modifier.weight(1f))
            Box(Modifier.width(130.dp))
        }
        entries.forEach { entry ->
            val open = model.selectedRoute == entry.id
            SimpleTableRow {
                Row(Modifier.weight(1.5f).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RouteEntryAvatar(entry, 46.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EllipsisText(entry.title, if (entry.node != null) Violet2 else White, 15.sp, Modifier.weight(1f, fill = false), FontWeight.SemiBold)
                            entry.kind?.let { if (it != RouteKindLabel.MAIN) { Spacer(Modifier.width(9.dp)); RoutePill(it) } }
                        }
                        EllipsisText(entry.subtitle, Muted, 13.sp)
                    }
                }
                Column(Modifier.weight(1f)) {
                    val (ip, country) = publicIpText(entry)
                    Text(ip, color = White, fontSize = 14.sp, maxLines = 1)
                    if (country.isNotBlank()) Text(country, color = Muted, fontSize = 13.sp, maxLines = 1)
                }
                RouteCheckStatus(entry.check, entry.checkable, now, Modifier.weight(1f))
                Row(Modifier.width(130.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (entry.checkable) {
                        if (entry.check == null || entry.check.state == RouteCheckState.NOT_CHECKED) SecondaryButton("Check", height = 38.dp) { model.recheck(entry.id) }
                        else IconAction(Icons.Rounded.Refresh, "Check ${entry.title} again", size = 36.dp, bordered = false, tint = White.copy(.8f)) { model.recheck(entry.id) }
                    }
                    IconAction(Icons.Rounded.ChevronRight, if (open) "Hide details for ${entry.title}" else "Show details for ${entry.title}",
                        Modifier.rotate(if (open) 90f else 0f), size = 36.dp, bordered = false, tint = White.copy(.8f)) { model.selectedRoute = if (open) null else entry.id }
                }
            }
            if (open) Column(Modifier.fillMaxWidth().background(NeutralOverlay.copy(if (IsDark) .04f else .02f)).padding(horizontal = 24.dp, vertical = 14.dp)) {
                RouteCheckFacts(entry, now, 150.dp)
                if (entry.checkable && entry.check?.state == RouteCheckState.PASSED && entry.check.publicIp == null) {
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Look up public IP", icon = Icons.Rounded.Public) { model.recheck(entry.id) }
                }
            }
        }
        if (!shell.connected && entries.size > 1) {
            Spacer(Modifier.height(10.dp))
            Note("Not connected: servers can only be checked through the running connection. Their last results are cleared when you disconnect.", Modifier.padding(horizontal = 10.dp))
        }
        Row(Modifier.fillMaxWidth().pressable(RoundedCornerShape(8.dp), label = if (model.dnsOpen) "Hide system DNS details" else "Show system DNS details") { model.dnsOpen = !model.dnsOpen }
            .padding(horizontal = 10.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ChevronRight, null, tint = White, modifier = Modifier.size(22.dp).rotate(if (model.dnsOpen) 90f else 0f))
            Spacer(Modifier.width(12.dp))
            Text("System DNS details", color = White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("Resolvers and lookup results.", color = Muted, fontSize = 13.sp)
        }
        if (model.dnsOpen) Column(Modifier.padding(start = 44.dp, end = 10.dp, bottom = 10.dp)) {
            val dns = model.dns
            if (dns == null) Text("Not looked up yet. Select Run checks.", color = Muted, fontSize = 13.sp)
            else {
                DetailLine("System DNS servers", dns.systemServers.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "Unknown", 220.dp)
                DetailLine("Resolver seen by public services", dns.publicResolver ?: "Unknown", 220.dp)
                DetailLine("Looked up", formatAgo(dns.atMillis, now), 220.dp)
            }
            Spacer(Modifier.height(6.dp))
            Text(model.dnsExplanation(), color = Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
    }
    Spacer(Modifier.height(18.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Shield, null, tint = Cyan, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Text(model.killSwitchText(), color = White, fontSize = 14.sp)
    }
    Spacer(Modifier.height(10.dp))
    Note("These checks are not a leak test. A passed check only shows the route is working at this time.")
}

@Composable
private fun SimpleInspector(shell: Shell, model: InspectorModel) {
    val clipboard = LocalClipboardManager.current
    SimpleCard(padding = 20.dp) {
        if (InspectorUnavailable(shell, Modifier.fillMaxWidth().padding(vertical = 30.dp))) return@SimpleCard
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextInput(model.query, { model.query = it }, "Search connections…", Modifier.weight(1f), Icons.Rounded.Search, height = 44.dp)
            MenuButton(model.appFilter?.let(::appName) ?: "All apps", model.appEntries(), Modifier.width(150.dp), accessibleLabel = "Filter by app", height = 44.dp)
            MenuButton(model.routeFilter ?: "All routes", model.routeEntries(), Modifier.width(150.dp), accessibleLabel = "Filter by route", height = 44.dp)
            MenuButton(model.statusFilter.label, model.statusEntries(), Modifier.width(160.dp), accessibleLabel = "Filter by status", height = 44.dp)
            Row(Modifier.background(if (model.live) Cyan.copy(.12f) else NeutralOverlay.copy(.06f), RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (model.live) Cyan else Muted, 10.dp); Spacer(Modifier.width(8.dp))
                Text(if (model.live) "Live" else "Paused", color = if (model.live) Cyan else Muted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            IconAction(if (model.live) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (model.live) "Pause updates" else "Resume live updates", size = 44.dp) { model.live = !model.live }
        }
        val filtered = model.filtered()
        Text((if (model.live) "Updates every 2s" else "Paused") + "  ·  ${plural(filtered.size, "connection")}", color = Muted, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.End).padding(top = 8.dp))
        if (!shell.controller.inspectorUsesGateway()) {
            Spacer(Modifier.height(8.dp))
            InfoBanner("The traffic gateway is off, so connections come from the core's log only: no byte counts and no close events. Turn it on in Settings > Routing.",
                Modifier.fillMaxWidth(), Amber)
        }
        Spacer(Modifier.height(6.dp))
        SimpleTableHeader {
            HeaderText("App", Modifier.weight(1f))
            HeaderText("Destination", Modifier.weight(1.3f))
            HeaderText("Route", Modifier.weight(1.2f))
            HeaderText("Status", Modifier.width(110.dp))
            HeaderText("Live traffic", Modifier.width(110.dp))
            Box(Modifier.width(28.dp))
        }
        if (filtered.isEmpty()) EmptyState(Icons.Rounded.Timeline, if (model.rows.isEmpty()) "No connections yet" else "No connections match",
            if (model.rows.isEmpty()) "Use one of the selected apps; its connections appear here within a couple of seconds." else "Nothing fits the current search and filters.",
            Modifier.fillMaxWidth())
        filtered.take(300).forEach { row ->
            SimpleTableRow(row.id == model.selectedId, onClick = { model.selectedId = if (model.selectedId == row.id) null else row.id; model.moreOpen = false },
                label = "Details of a connection of ${appName(row.applicationPath)}") {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    AppAvatar(fileName(row.applicationPath), row.applicationPath, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column { EllipsisText(appName(row.applicationPath), White, 14.sp, fontWeight = FontWeight.Medium); Text(row.protocol.ifBlank { "?" }, color = Muted, fontSize = 12.sp) }
                }
                Column(Modifier.weight(1.3f).padding(end = 8.dp)) {
                    EllipsisText(row.destination, White, 13.5.sp)
                    row.observed.sniffedDomain?.takeIf { !it.equals(row.destinationHost, true) }?.let { EllipsisText(it, Muted, 12.sp) }
                }
                Box(Modifier.weight(1.2f)) { RouteFacts(row) }
                Box(Modifier.width(110.dp)) { InspectorStatusLabel(row) }
                Box(Modifier.width(110.dp)) { LiveTraffic(row) }
                Icon(Icons.Rounded.ChevronRight, null, tint = White.copy(.7f), modifier = Modifier.size(22.dp))
            }
        }
        model.selected?.let { row ->
            Spacer(Modifier.height(14.dp))
            val shape = RoundedCornerShape(14.dp)
            Column(Modifier.fillMaxWidth().border(1.dp, Line, shape).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppAvatar(fileName(row.applicationPath), row.applicationPath, 38.dp)
                    Spacer(Modifier.width(14.dp))
                    Text("${appName(row.applicationPath)}  ·  ${row.destination}", color = White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    LinkAction("Copy details", arrow = false, fontSize = 14.sp) { model.copy(row, clipboard) }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    FactKind.entries.forEachIndexed { index, kind ->
                        if (index > 0) { Spacer(Modifier.width(16.dp)); Divider(Modifier.fillMaxHeight(), vertical = true); Spacer(Modifier.width(16.dp)) }
                        Column(Modifier.weight(1f)) {
                            FactHeading(kind)
                            Spacer(Modifier.height(10.dp))
                            FactLines(kind, row, 100.dp, more = kind == FactKind.OBSERVED && model.moreOpen)
                            Spacer(Modifier.weight(1f))
                            Spacer(Modifier.height(10.dp))
                            if (kind == FactKind.OBSERVED) Row(Modifier.pressable(RoundedCornerShape(6.dp), label = "More connection details") { model.moreOpen = !model.moreOpen }.padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.ChevronRight, null, tint = White, modifier = Modifier.size(18.dp).rotate(if (model.moreOpen) 90f else 0f))
                                Spacer(Modifier.width(6.dp))
                                Text(if (model.moreOpen) "Fewer connection details" else "More connection details", color = White, fontSize = 13.sp)
                            } else Note(factNote(kind))
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Note("Connection details stay in memory. Nothing is recorded or kept after you disconnect.")
}

@Composable
private fun SimpleLoadedRules(shell: Shell, model: DiagnosticsModel) {
    val rules = remember(shell.connected, model.loadedRuleIds) { shell.controller.rules() }
    SimpleCard(padding = 20.dp) {
        if (rules.isEmpty()) {
            EmptyState(Icons.Rounded.Description, "No routing rules", "Add rules in Rules or apply a preset in Profiles.", Modifier.fillMaxWidth()) {
                SecondaryButton("Open Rules") { shell.navigate(Tab.Rules) }
            }
            return@SimpleCard
        }
        val states = rules.map { loadedState(it, model.loadedRuleIds) }
        Text(if (shell.connected) "${states.count { it.tone == 1 }} active  ·  ${states.count { it.tone == 2 }} not loaded  ·  ${states.count { it.label == "Off" }} off"
            else "Not connected: nothing is loaded. This is the saved list the next connection will load.", color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp))
        Spacer(Modifier.height(12.dp))
        SimpleTableHeader {
            HeaderText("#", Modifier.width(36.dp))
            HeaderText("Rule", Modifier.weight(1.6f))
            HeaderText("Configured", Modifier.weight(.8f))
            HeaderText("Runtime", Modifier.weight(1.4f))
        }
        rules.forEachIndexed { index, rule ->
            val state = states[index]
            SimpleTableRow {
                Text("${index + 1}", color = White.copy(.85f), fontSize = 14.sp, modifier = Modifier.width(36.dp))
                Column(Modifier.weight(1.6f).padding(end = 12.dp)) {
                    EllipsisText(PerRouteController.ruleDisplayName(rule), White, 14.sp, fontWeight = FontWeight.SemiBold)
                    EllipsisText("${ruleTarget(rule)}  ·  ${rule.value()}  →  ${actionLabel(rule.action())}", Muted, 12.5.sp)
                }
                Text(if (rule.enabled()) "On" else "Off", color = White, fontSize = 14.sp, modifier = Modifier.weight(.8f))
                Column(Modifier.weight(1.4f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(loadedColor(state), 9.dp); Spacer(Modifier.width(8.dp))
                        Text(state.label, color = loadedColor(state), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(state.why, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Note(if (shell.connected) "Compared with the config the running core was started with." else "Rules are loaded when you connect.", Modifier.weight(1f))
            LinkAction("Open Rules", fontSize = 14.sp) { shell.navigate(Tab.Rules) }
        }
    }
}

@Composable
private fun SimpleTester(shell: Shell, model: RuleTesterModel, now: Long) {
    val clipboard = LocalClipboardManager.current
    SimpleCard(padding = 24.dp) {
        BoxWithConstraints {
            val wide = maxWidth >= 760.dp
            val form: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    Text("Test a destination", color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("See which route and rule would be used. It reads your saved rules; it does not open a connection.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(16.dp))
                    FieldLabel("Application")
                    MenuButton(if (model.app.isBlank()) "Any routed app" else appName(model.app), model.appEntries(), Modifier.fillMaxWidth(), accessibleLabel = "Application to test", height = 44.dp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) { FieldLabel("Domain or IP"); TextInput(model.destination, { model.destination = it; model.clear() }, "github.com", icon = Icons.Rounded.Public, height = 44.dp) }
                        Column(Modifier.width(110.dp)) { FieldLabel("Port"); TextInput(model.port, { v -> model.port = v.filter(Char::isDigit).take(5); model.clear() }, "443", height = 44.dp) }
                    }
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("Check route", icon = Icons.Rounded.Search, enabled = model.destination.isNotBlank(), height = 46.dp) { model.check() }
                    Spacer(Modifier.height(14.dp))
                    Note("The first matching rule wins. Unselected apps are never routed, whatever the rules say.")
                }
            }
            val result: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    val verdict = model.verdict
                    if (verdict == null) {
                        Text("Predicted result", color = Muted, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Choose an application and a destination, then select Check route.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
                    } else {
                        val shape = RoundedCornerShape(14.dp)
                        Column(Modifier.fillMaxWidth().background(Selection, shape).padding(18.dp)) {
                            Text("Predicted result", color = Muted, fontSize = 13.sp)
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(actionIcon(verdict.action()), null, tint = if (verdict.action() == RouteRule.Action.BLOCK) Red else Violet2, modifier = Modifier.size(26.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(model.headline(verdict) + if (verdict.action() == RouteRule.Action.PROXY) " → ${model.routeLabel(verdict)}" else "",
                                    color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(12.dp))
                            DetailLine("Route mode", model.routeMode(), 120.dp)
                            DetailLine("Matched rule", verdict.rule()?.let { PerRouteController.ruleDisplayName(it) } ?: verdict.reason(), 120.dp)
                            DetailLine("Rule order", model.ruleOrder(verdict), 120.dp)
                            DetailLine("Source", model.source(verdict), 120.dp)
                            DetailLine("Match", if (verdict.certain()) "Certain" else "Probable", 120.dp)
                            if (!verdict.certain()) Text("Could not be evaluated locally and may match first: " + verdict.uncertain().joinToString(", ") { PerRouteController.ruleDisplayName(it) },
                                color = Amber, fontSize = 12.5.sp, lineHeight = 17.sp)
                            if (verdict.action() == RouteRule.Action.PROXY && model.app.isBlank() && shell.plan.advancedEffective())
                                Text("Shown for apps on the main server. Choose an app to see its own server.", color = Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        val check = model.target?.let { shell.routeChecks[it.key()] }
                        Note(when {
                            verdict.action() != RouteRule.Action.PROXY -> "Routing decision only."
                            model.missing -> "Routing decision only. This app's custom server is missing, so it cannot connect until you choose a replacement."
                            shell.connected && check?.state == RouteCheckState.FAILED -> "Routing decision only. This server's connection check failed ${formatAgo(check.atMillis, now)}."
                            else -> "Routing decision only. It predicts the rule, not whether the server is reachable."
                        })
                        Spacer(Modifier.height(10.dp))
                        SecondaryButton("Copy result", icon = Icons.Rounded.ContentCopy) {
                            clipboard.setText(AnnotatedString(model.copyText(verdict))); shell.notify(ActionResult(true, "Result copied."))
                        }
                    }
                }
            }
            if (wide) Row { form(Modifier.weight(1f)); Spacer(Modifier.width(28.dp)); result(Modifier.weight(1f)) }
            else Column { form(Modifier.fillMaxWidth()); Spacer(Modifier.height(20.dp)); result(Modifier.fillMaxWidth()) }
        }
    }
}

