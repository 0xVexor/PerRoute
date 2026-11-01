package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Visibility
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.AppRoutes
import app.perroute.model.ConnectionMode
import app.perroute.model.ProxyNode
import app.perroute.model.RouteRule
import app.perroute.service.RouteRuleEvaluator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class DiagnosticsTab(val label: String) { Routes("Route checks"), Inspector("Inspector"), Loaded("Loaded rules"), Tester("Rule tester") }

internal class RouteCheckEntry(
    val id: String,
    val node: ProxyNode?,
    val title: String,
    val subtitle: String,
    val kind: RouteKindLabel?,
    val applications: List<String>,
    val check: RouteCheck?,
    val checkable: Boolean
)

internal class DiagnosticsModel(private val shell: Shell) {
    private val controller = shell.controller
    var checking by mutableStateOf(false)
    var dns by mutableStateOf(controller.dnsObservation())
    var loadedRuleIds by mutableStateOf<Set<String>?>(null)
    var dnsOpen by mutableStateOf(false)
    var selectedRoute by mutableStateOf<String?>(null)
    var selectedRule by mutableStateOf<String?>(null)
    val tester = RuleTesterModel(shell)
    val inspector = InspectorModel(shell)

    fun runChecks(announce: Boolean = true) {
        if (checking) return
        shell.scope.launch {
            checking = true
            try {
                val result = withContext(Dispatchers.IO) { controller.checkRoutes() }
                dns = controller.dnsObservation()
                shell.routeChecks = controller.routeChecks()
                if (announce) shell.notify(result)
            } finally { checking = false }
        }
    }

    fun recheck(key: String) {
        shell.scope.launch {
            val result = withContext(Dispatchers.IO) { controller.checkRoute(key) }
            shell.routeChecks = controller.routeChecks()
            if (!result.ok) shell.notify(result)
        }
    }

    suspend fun onOpen() {
        loadedRuleIds = withContext(Dispatchers.IO) { controller.loadedRuleIds() }
        if (controller.routeChecks()[PerRouteController.DIRECT_ROUTE_KEY] == null) recheck(PerRouteController.DIRECT_ROUTE_KEY)
    }

    fun entries(): List<RouteCheckEntry> {
        val rows = routeRows(shell)
        val direct = RouteCheckEntry(
            PerRouteController.DIRECT_ROUTE_KEY, null, "Direct connection",
            if (shell.perApp) "Your regular connection" else "Everything PerRoute does not route",
            null, emptyList(), shell.routeChecks[PerRouteController.DIRECT_ROUTE_KEY], true
        )
        return listOf(direct) + rows.map { row ->
            RouteCheckEntry(row.id, row.node, serverName(row.node),
                when {
                    !shell.perApp -> if (shell.mode == ConnectionMode.SYSTEM_PROXY) "Apps that follow system proxy settings" else "Apps you point at the local proxy"
                    row.applications.isEmpty() -> "No selected app uses this server"
                    else -> joinNames(row.applications.map(::appName), 4)
                },
                row.kind, row.applications, shell.routeChecks[row.id], shell.connected)
        }
    }

    fun summary(entries: List<RouteCheckEntry>, now: Long): Pair<String, Boolean> {
        val proxies = entries.filter { it.node != null }
        if (!shell.connected) return "Not connected: servers are checked through the running connection" to false
        val passed = proxies.count { it.check?.state == RouteCheckState.PASSED && it.check.stale(now).not() }
        val failed = proxies.count { it.check?.state == RouteCheckState.FAILED }
        val unchecked = proxies.count { it.check == null || it.check.state == RouteCheckState.NOT_CHECKED }
        return when {
            proxies.isEmpty() -> "No proxy route to check" to false
            failed > 0 -> ("${plural(failed, "proxy route")} failed" + if (passed > 0) "  ·  $passed passed" else "") to false
            passed == proxies.size -> "${plural(passed, "proxy route")} passed" to true
            unchecked == proxies.size -> "Not checked yet" to false
            else -> "$passed of ${plural(proxies.size, "proxy route")} passed" to false
        }
    }

    fun killSwitchText(): String {
        val killSwitch = controller.killSwitch()
        return when {
            killSwitch && shell.perApp -> "Kill switch is on. Selected apps wait if their route fails."
            killSwitch -> "Kill switch is on, but it only acts in Smart per-app mode."
            else -> "Kill switch is off. If the tunnel drops, selected apps fall back to the direct route."
        }
    }

    fun dnsExplanation(): String = when (shell.mode) {
        ConnectionMode.NATIVE_PER_APP -> "In Smart per-app mode selected apps usually ask the operating system for names first, so lookups use the resolvers above and only the connection itself is tunnelled. PerRoute does not measure DNS separately for each server."
        ConnectionMode.TUN_COMPATIBILITY -> "In TUN mode DNS is carried inside the tunnel. PerRoute does not measure DNS separately for each server."
        else -> "Apps using the HTTP proxy hand names to the server; SOCKS clients may still resolve locally first. PerRoute does not measure DNS separately for each server."
    }
}

internal fun diagnosticsModel(shell: Shell): DiagnosticsModel = shell.screen("${Tab.Diagnostics.name}:diagnostics") { DiagnosticsModel(shell) }

@Composable
internal fun DiagnosticsEffects(shell: Shell, model: DiagnosticsModel) {
    LaunchedEffect(shell.connected) { model.onOpen() }
}

internal fun publicIpText(entry: RouteCheckEntry): Pair<String, String> {
    val check = entry.check
    return when {
        !entry.checkable -> "—" to ""
        check?.publicIp != null -> check.publicIp to (check.country?.takeIf { it.isNotBlank() }.orEmpty())
        check?.state == RouteCheckState.CHECKING -> "Checking…" to ""
        check?.state == RouteCheckState.PASSED -> "—" to "Not looked up yet"
        else -> "—" to "No IP detected"
    }
}

@Composable
internal fun RouteCheckFacts(entry: RouteCheckEntry, now: Long, labelWidth: Dp = 120.dp) {
    val check = entry.check
    val stale = check?.stale(now) == true
    if (!entry.checkable) {
        Text("Connect, then select Run checks to observe this server.", color = Muted, fontSize = 11.5.sp, lineHeight = 16.sp)
        return
    }
    val (ip, country) = publicIpText(entry)
    DetailLine("Public IP", if (country.isNotBlank() && check?.publicIp != null) "$ip  ($country)" else ip, labelWidth)
    DetailLine("Status", when (check?.state ?: RouteCheckState.NOT_CHECKED) {
        RouteCheckState.NOT_CHECKED -> "Not checked yet"
        RouteCheckState.CHECKING -> "Checking now"
        RouteCheckState.PASSED -> "Passed" + if (stale) " (stale)" else ""
        RouteCheckState.FAILED -> "Failed" + if (stale) " (stale)" else ""
    }, labelWidth)
    check?.latencyMillis?.let { DetailLine("RTT", "$it ms", labelWidth) }
    if (check != null && (check.state == RouteCheckState.PASSED || check.state == RouteCheckState.FAILED)) DetailLine("Checked", formatAgo(check.atMillis, now), labelWidth)
    if (entry.node != null) DetailLine("Server", "${protocolLabel(entry.node)}  ·  ${entry.node.endpoint()}", labelWidth)
    if (entry.applications.isNotEmpty()) DetailLine("Used by", joinNames(entry.applications.map(::appName), 4), labelWidth)
    if (!check?.detail.isNullOrBlank()) DetailLine("Detail", check!!.detail, labelWidth)
}

internal class LoadedState(val label: String, val tone: Int, val why: String)

internal fun loadedState(rule: RouteRule, loadedRuleIds: Set<String>?): LoadedState = when {
    !rule.enabled() -> LoadedState("Off", 0, "Switched off, so it is not sent to the core.")
    loadedRuleIds == null -> LoadedState("Saved", 0, "Will be loaded when you connect.")
    rule.id() in loadedRuleIds -> LoadedState("Loaded", 1, "Active in the running core.")
    else -> LoadedState("Not loaded", 2, "It targets an app that is not in the running selection, or it was added after connecting.")
}

@Composable
internal fun loadedColor(state: LoadedState): Color = when (state.tone) { 1 -> Cyan; 2 -> Amber; else -> Muted }

internal class RuleTesterModel(private val shell: Shell) {
    var destination by mutableStateOf("")
    var port by mutableStateOf("443")
    var app by mutableStateOf("")
    var verdict by mutableStateOf<RouteRuleEvaluator.Verdict?>(null)

    fun check() { verdict = shell.controller.evaluateRoute(app, destination, port.toIntOrNull() ?: 0) }
    fun clear() { verdict = null }

    fun appEntries(): List<MenuEntry> = listOf(MenuEntry("Any routed app", selected = app.isBlank()) { app = ""; verdict = null }) +
        shell.apps.map { path -> MenuEntry(appName(path), selected = app.equals(path, true)) { app = path; verdict = null } }

    val target: ProxyNode? get() = if (app.isBlank()) shell.mainNode else shell.plan.nodeFor(app) ?: shell.mainNode
    val missing: Boolean get() = app.isNotBlank() && shell.plan.missing().any { it.applicationPath().equals(app, true) }

    fun headline(result: RouteRuleEvaluator.Verdict): String = (if (result.certain()) "" else "Probably ") + when (result.action()) {
        RouteRule.Action.PROXY -> "Proxy"
        RouteRule.Action.DIRECT -> "Direct"
        RouteRule.Action.BLOCK -> "Blocked"
    }

    fun routeLabel(result: RouteRuleEvaluator.Verdict): String = when (result.action()) {
        RouteRule.Action.PROXY -> when {
            missing -> "Missing custom server"
            target != null -> serverName(target!!)
            else -> "No server selected"
        }
        RouteRule.Action.DIRECT -> "Your regular connection"
        RouteRule.Action.BLOCK -> "Dropped"
    }

    fun routeMode(): String {
        if (app.isBlank()) return if (shell.plan.advancedEffective()) "Main server (apps without their own)" else "Main server"
        if (!shell.plan.advancedEffective()) return "Main server"
        val key = AppRoutes.normalize(app)
        return when {
            key in shell.controller.autoApps() -> "Auto"
            shell.appRoutes.containsKey(key) -> "Custom server"
            else -> "Main server"
        }
    }

    fun ruleOrder(result: RouteRuleEvaluator.Verdict): String {
        val rule = result.rule() ?: return "No rule matched"
        val rules = shell.controller.rules()
        val index = rules.indexOfFirst { it.id() == rule.id() }
        return if (index < 0) "—" else "Rule ${index + 1} of ${rules.size}"
    }

    fun source(result: RouteRuleEvaluator.Verdict): String {
        val rule = result.rule() ?: return "Built in"
        return when {
            PerRouteController.isScenarioRule(rule) -> "Preset rule"
            rule.applicationPath().isBlank() -> "Your rule (all apps)"
            else -> "Your rule (this app)"
        }
    }

    fun copyText(result: RouteRuleEvaluator.Verdict): String = buildString {
        appendLine("App: ${if (app.isBlank()) "Any routed app" else appName(app)}")
        appendLine("Destination: $destination:${port.ifBlank { "0" }}")
        appendLine("Result: ${headline(result)} (${routeLabel(result)})")
        appendLine("Route mode: ${routeMode()}")
        appendLine("Matched rule: ${result.rule()?.let { PerRouteController.ruleDisplayName(it) } ?: result.reason()}")
        appendLine("Rule order: ${ruleOrder(result)}")
        append("Source: ${source(result)}")
    }
}

internal enum class InspectorStatusFilter(val label: String) { ALL("All statuses"), ACTIVE("Active"), PROBLEMS("Failed or blocked"), CLOSED("Closed") }

internal class InspectorModel(private val shell: Shell) {
    var rows by mutableStateOf<List<InspectorRow>>(emptyList())
    var live by mutableStateOf(true)
    var query by mutableStateOf("")
    var appFilter by mutableStateOf<String?>(null)
    var routeFilter by mutableStateOf<String?>(null)
    var statusFilter by mutableStateOf(InspectorStatusFilter.ALL)
    var selectedId by mutableStateOf<Long?>(null)
    var moreOpen by mutableStateOf(false)

    val available: Boolean get() = shell.connected && shell.controller.inspectorAvailable()

    fun filtered(): List<InspectorRow> = rows.filter { row ->
        (appFilter == null || row.applicationPath.equals(appFilter, true)) &&
            (routeFilter == null || (row.observed.routeLabel ?: row.configured.routeLabel) == routeFilter) &&
            when (statusFilter) {
                InspectorStatusFilter.ALL -> true
                InspectorStatusFilter.ACTIVE -> row.status == "ACTIVE" || row.status == "CONNECTING" || row.status == "SEEN"
                InspectorStatusFilter.PROBLEMS -> row.status == "FAILED" || row.status == "BLOCKED" || row.status == "NO_RESPONSE"
                InspectorStatusFilter.CLOSED -> row.status == "CLOSED"
            } &&
            (query.isBlank() || appName(row.applicationPath).contains(query, true) || row.destination.contains(query, true) ||
                row.observed.sniffedDomain.orEmpty().contains(query, true))
    }

    val selected: InspectorRow? get() = rows.firstOrNull { it.id == selectedId }

    fun appEntries(): List<MenuEntry> = listOf(MenuEntry("All apps", selected = appFilter == null) { appFilter = null }) +
        rows.map { it.applicationPath }.distinctBy { it.lowercase() }.map { path -> MenuEntry(appName(path), selected = appFilter.equals(path, true)) { appFilter = path } }

    fun routeEntries(): List<MenuEntry> = listOf(MenuEntry("All routes", selected = routeFilter == null) { routeFilter = null }) +
        rows.mapNotNull { it.observed.routeLabel ?: it.configured.routeLabel }.distinct().map { label -> MenuEntry(label, selected = routeFilter == label) { routeFilter = label } }

    fun statusEntries(): List<MenuEntry> = InspectorStatusFilter.entries.map { filter -> MenuEntry(filter.label, selected = statusFilter == filter) { statusFilter = filter } }

    fun copy(row: InspectorRow, clipboard: ClipboardManager) {
        clipboard.setText(AnnotatedString(describe(row)))
        shell.notify(ActionResult(true, "Connection details copied."))
    }
}

@Composable
internal fun InspectorEffects(shell: Shell, model: InspectorModel) {
    val available = model.available
    LaunchedEffect(available, model.live) {
        if (!available) { model.rows = emptyList(); return@LaunchedEffect }
        while (isActive) {
            model.rows = withContext(Dispatchers.IO) { shell.controller.inspectorRows() }
            if (!model.live) break
            delay(2_000)
        }
    }
}

internal enum class FactKind(val label: String, val subtitle: String, val icon: ImageVector) {
    CONFIGURED("Configured", "Predicted from settings", Icons.Rounded.Info),
    OBSERVED("Observed", "Seen by the core", Icons.Rounded.Visibility),
    VERIFIED("Verified", "External route check", Icons.Rounded.CheckCircle)
}

@Composable
internal fun factColor(kind: FactKind): Color = when (kind) {
    FactKind.CONFIGURED -> if (Tok.isNew) White else Violet2
    FactKind.OBSERVED -> Amber
    FactKind.VERIFIED -> Cyan
}

@Composable
internal fun inspectorStatus(row: InspectorRow): Pair<String, Color> = when (row.status) {
    "ACTIVE" -> "Active" to Cyan
    "CONNECTING" -> "Opening" to Amber
    "SEEN" -> "Seen" to Muted
    "CLOSED" -> "Closed" to Muted
    "BLOCKED" -> "Blocked" to Red
    "NO_RESPONSE" -> "No response" to Amber
    else -> "Failed" to Red
}

@Composable
internal fun InspectorStatusLabel(row: InspectorRow) {
    val (label, color) = inspectorStatus(row)
    Tooltip(row.failure) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color, 9.dp)
            Spacer(Modifier.width(7.dp))
            Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
internal fun RouteFacts(row: InspectorRow) {
    Column {
        Row {
            Text("Configured: ", color = Muted, fontSize = 11.sp)
            EllipsisText(row.configured.routeLabel, White, 11.sp)
        }
        Row {
            Text("Observed: ", color = Muted, fontSize = 11.sp)
            EllipsisText(row.observed.routeLabel ?: "—", White, 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
internal fun FactLines(kind: FactKind, row: InspectorRow, labelWidth: Dp = 110.dp, more: Boolean = false) {
    when (kind) {
        FactKind.CONFIGURED -> {
            DetailLine("Expected route", row.configured.routeLabel, labelWidth)
            DetailLine("Rule", row.configured.ruleLabel, labelWidth)
            DetailLine("Source", row.configured.ruleSource, labelWidth)
            if (!row.configured.certain) Text("Some rules depend on details only the core knows (for example a domain list); the observed result is authoritative.",
                color = Muted, fontSize = 10.5.sp, lineHeight = 14.sp)
        }
        FactKind.OBSERVED -> {
            DetailLine("Effective route", row.observed.routeLabel ?: "Not observed yet", labelWidth)
            DetailLine("Outbound", row.observed.outboundTag ?: "Not observed yet", labelWidth)
            DetailLine("Protocol", row.protocol.ifBlank { "Unknown" }, labelWidth)
            if (row.viaGateway) DetailLine("Downloaded", formatBytes(row.downloadBytes), labelWidth)
            if (more) {
                DetailLine("Process path", row.applicationPath, labelWidth)
                DetailLine("Destination", row.destination, labelWidth)
                DetailLine("Sniffed domain", row.observed.sniffedDomain ?: "Not observed", labelWidth)
                DetailLine("Matched rule", row.observed.ruleLabel ?: "Not observed", labelWidth)
                if (row.viaGateway) {
                    DetailLine("Live traffic", "↓ ${formatRate(row.downloadRate)}   ↑ ${formatRate(row.uploadRate)}", labelWidth)
                    DetailLine("Uploaded", formatBytes(row.uploadBytes), labelWidth)
                }
                DetailLine("First seen", formatAgo(row.openedAt), labelWidth)
                if (row.closedAt > 0) DetailLine("Closed", formatAgo(row.closedAt), labelWidth)
                if (row.failure.isNotBlank()) DetailLine("Failure", row.failure, labelWidth)
            }
        }
        FactKind.VERIFIED -> {
            val verified = row.verified
            if (verified == null) Text("Not verified yet. Run checks in Route checks to look up the exit address of this route.", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
            else {
                DetailLine("Route", verified.routeLabel, labelWidth)
                DetailLine("IP", verified.publicIp?.let { ip -> ip + (verified.country?.let { "  ($it)" } ?: "") } ?: "—", labelWidth)
                DetailLine("Route test", if (verified.passed) "Passed" else "Failed: ${verified.detail}", labelWidth,
                    if (verified.passed) Cyan else Amber)
                DetailLine("Tested", formatAgo(verified.atMillis), labelWidth)
            }
        }
    }
}

@Composable
internal fun FactHeading(kind: FactKind) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(factColor(kind).copy(.14f)), contentAlignment = Alignment.Center) {
            Icon(kind.icon, null, tint = factColor(kind), modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(kind.label, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(kind.subtitle, color = Muted, fontSize = 11.sp)
        }
    }
}

internal fun factNote(kind: FactKind): String = when (kind) {
    FactKind.CONFIGURED -> "Prediction, not measurement."
    FactKind.OBSERVED -> "What the running core reported."
    FactKind.VERIFIED -> "Confirms the route, not the destination."
}

internal fun describe(row: InspectorRow): String = buildString {
    appendLine("${appName(row.applicationPath)} (${row.applicationPath})")
    appendLine("Destination: ${row.destination} ${row.protocol}" + (row.observed.sniffedDomain?.let { " ($it)" } ?: ""))
    appendLine("Configured: ${row.configured.routeLabel}; rule: ${row.configured.ruleLabel} (${row.configured.ruleSource})")
    appendLine("Observed: ${row.observed.routeLabel ?: "not observed"}; outbound: ${row.observed.outboundTag ?: "-"}; rule: ${row.observed.ruleLabel ?: "not observed"}")
    appendLine("Status: ${row.status}" + if (row.failure.isNotBlank()) " (${row.failure})" else "")
    if (row.viaGateway) appendLine("Traffic: ${formatBytes(row.downloadBytes)} down, ${formatBytes(row.uploadBytes)} up")
    val verified = row.verified
    append("Verified: " + if (verified == null) "not verified" else "${verified.routeLabel} exit ${verified.publicIp ?: "-"} (${if (verified.passed) "passed" else "failed"}, ${formatAgo(verified.atMillis)})")
}

@Composable
internal fun LiveTraffic(row: InspectorRow) {
    if (row.viaGateway) Column {
        Text("↓ ${formatRate(row.downloadRate)}", color = White, fontSize = 11.sp, maxLines = 1)
        Text("↑ ${formatRate(row.uploadRate)}", color = White, fontSize = 11.sp, maxLines = 1)
    } else Text("—", color = Muted, fontSize = 12.sp)
}

@Composable
internal fun InspectorUnavailable(shell: Shell, modifier: Modifier): Boolean {
    if (shell.connected && shell.perApp) return false
    EmptyState(Icons.Rounded.Timeline, if (shell.perApp) "Connect to inspect connections" else "Available in Smart per-app",
        if (shell.perApp) "The inspector lists each selected app's connections while PerRoute is connected. Nothing is recorded or kept afterwards."
        else "Only Smart per-app gives every selected app its own path through the core, which is what the inspector observes.",
        modifier)
    return true
}

@Composable
internal fun RouteEntryAvatar(entry: RouteCheckEntry, size: Dp = 36.dp) {
    if (entry.node != null) ServerAvatar(entry.node, size)
    else Box(Modifier.size(size).clip(androidx.compose.foundation.shape.RoundedCornerShape(size * .24f))
        .background(if (Tok.isNew) Color.Transparent else NeutralOverlay.copy(.05f))
        .then(if (Tok.isNew) Modifier.border(1.dp, Line, androidx.compose.foundation.shape.RoundedCornerShape(size * .24f)) else Modifier),
        contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Language, null, tint = White.copy(.75f), modifier = Modifier.size(size * .55f))
    }
}


