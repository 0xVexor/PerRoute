package app.perroute.compose.controller

import app.perroute.backend.api.ConnectionBackend
import app.perroute.backend.api.ApplicationDescriptor
import app.perroute.backend.api.RouteObservation
import app.perroute.compose.*
import app.perroute.compose.ui.components.joinNames
import app.perroute.compose.ui.components.plural
import app.perroute.model.AppRoutes
import app.perroute.model.ConnectionMode
import app.perroute.model.ProxyNode
import app.perroute.model.RouteRule
import app.perroute.service.RouteRuleEvaluator
import java.time.format.DateTimeFormatter

/** What the Doctor reads from the running application. It only reads: repairs stay with the controller. */
interface DoctorContext {
    val backend: ConnectionBackend
    val configuredMode: ConnectionMode
    val activeMode: ConnectionMode?
    val phase: ConnectionPhase
    val desiredConnected: Boolean
    val connectedAtMillis: Long
    val activeRoutes: List<ActiveRoute>
    val routeChecks: Map<String, RouteCheck>
    val dnsObservation: DnsObservation?
    val appliedLocalBlocking: List<String>

    fun capabilities(): app.perroute.backend.api.BackendCapabilities
    fun isConnected(): Boolean
    fun autoReconnect(): Boolean
    fun killSwitch(): Boolean
    fun trafficGateway(): Boolean
    fun environment(): EnvironmentState
    fun networkRestorePlan(): app.perroute.netstate.NetworkRestorePlan
    fun applications(): List<String>
    fun applicationPaths(): Set<String>
    fun nodes(): List<ProxyNode>
    fun selectedNode(): ProxyNode?
    fun nodeFor(application: String): ProxyNode?
    fun nodeSource(key: String): String
    fun subscriptionHealth(url: String): app.perroute.subscription.SubscriptionHealth.Report
    fun probe(node: ProxyNode, timeoutMillis: Int): app.perroute.service.ServerLatencyProbe.Result
    fun coreLogTail(count: Int): List<String>
    fun processIds(path: String): List<Long>
    fun temporaryRoute(path: String): TemporaryRoute?
    fun rules(): List<RouteRule>
    fun loadedRuleIds(): Set<String>?
    fun evaluateRoute(application: String, destination: String, port: Int): RouteRuleEvaluator.Verdict
    fun checkRoutes(): ActionResult
    fun untrustedLocalNetworks(): Map<String, String>
    fun blockedLocalRules(): List<String>
    fun workspaces(): List<WorkspaceView>
}

/** Turns what the application knows into plain-language findings: the Connection doctor and the Routing safety check. */
class ConnectionDoctor(private val context: DoctorContext) {
    private val backend get() = context.backend
    private val activeMode get() = context.activeMode
    private val phase get() = context.phase
    private val desiredConnected get() = context.desiredConnected
    private val connectedAtMillis get() = context.connectedAtMillis
    private val activeRoutes get() = context.activeRoutes
    private val routeChecks get() = context.routeChecks
    private val dnsObservation get() = context.dnsObservation
    private val appliedLocalBlocking get() = context.appliedLocalBlocking
    private val configuredMode get() = context.configuredMode

    private fun capabilities() = context.capabilities()
    private fun isConnected() = context.isConnected()
    private fun autoReconnect() = context.autoReconnect()
    private fun killSwitch() = context.killSwitch()
    private fun trafficGateway() = context.trafficGateway()
    private fun environment() = context.environment()
    private fun networkRestorePlan() = context.networkRestorePlan()
    private fun applications() = context.applications()
    private fun nodes() = context.nodes()
    private fun selectedNode() = context.selectedNode()
    private fun nodeSource(key: String) = context.nodeSource(key)
    private fun subscriptionHealth(url: String) = context.subscriptionHealth(url)
    private fun processIds(path: String) = context.processIds(path)
    private fun temporaryRoute(path: String) = context.temporaryRoute(path)
    private fun rules() = context.rules()
    private fun loadedRuleIds() = context.loadedRuleIds()
    private fun evaluateRoute(application: String, destination: String, port: Int) = context.evaluateRoute(application, destination, port)
    private fun checkRoutes() = context.checkRoutes()
    private fun workspaces() = context.workspaces()
    private fun cleanName(value: String) = PerRouteController.cleanName(value)
    private fun ruleDisplayName(rule: RouteRule) = PerRouteController.ruleDisplayName(rule)
    private fun message(error: Throwable): String = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    fun diagnose(appPath: String?, destination: String?): HealthReport {
        if (!capabilities().networkRecovery()) return unavailableHealthReport("Connection doctor")
        val findings = mutableListOf<HealthFinding>()
        val connected = isConnected()
        val mode = activeMode ?: configuredMode
        val now = System.currentTimeMillis()
        val target = appPath?.takeIf { it.isNotBlank() }
        val appName = healthAppName(target)

        val direct = runCatching { backend.lookupPublicAddress(null) }.getOrElse { RouteObservation(null, null, message(it)) }
        val offline = direct.ip() == null
        findings += if (offline) HealthFinding("network", "This computer", CheckStatus.FAIL, "Your own internet connection does not work",
            "Even without the tunnel, a public test site did not answer (${direct.error() ?: "no answer"}). Check Wi-Fi or the cable and your router first; nothing in PerRoute can work until this does.")
        else HealthFinding("network", "This computer", CheckStatus.PASS, "Your internet connection works", "Public address ${direct.ip()}${direct.country()?.let { " ($it)" } ?: ""}.")

        findings += if (!runCatching { backend.systemResolves("www.cloudflare.com") }.getOrDefault(false))
            HealthFinding("dns", "This computer", if (offline) CheckStatus.SKIPPED else CheckStatus.FAIL, "Windows cannot look up names",
                "Names such as www.cloudflare.com do not resolve through your DNS servers (${backend.systemDnsServers().joinToString(", ").ifBlank { "unknown" }}). Servers given by name cannot be reached either.")
        else HealthFinding("dns", "This computer", CheckStatus.PASS, "Name lookups work", "Windows resolves names through ${backend.systemDnsServers().joinToString(", ").ifBlank { "its DNS servers" }}.")

        if (!offline) {
            val serverTime = runCatching { backend.directServerTime() }.getOrNull()
            if (serverTime != null) {
                val skew = kotlin.math.abs(now - serverTime) / 1000
                findings += when {
                    skew > 90 -> HealthFinding("clock", "This computer", CheckStatus.FAIL, "Your clock is ${formatSkew(skew)} off",
                        "VMess servers refuse connections when the clock differs by more than 90 seconds, and secure connections can fail too. Turn on Set time automatically in Windows settings.")
                    skew > 30 -> HealthFinding("clock", "This computer", CheckStatus.WARN, "Your clock is ${formatSkew(skew)} off",
                        "Close to the 90 seconds some servers accept. Turn on Set time automatically in Windows settings.")
                    else -> HealthFinding("clock", "This computer", CheckStatus.PASS, "Your clock is correct", "Within $skew seconds of a public time source.")
                }
            }
        }

        val environment = environment()
        findings += if (!environment.coreReady) HealthFinding("core", "PerRoute", CheckStatus.FAIL, "The connection core is not installed",
            "Xray is missing, so no connection can start.", Repair.OPEN_COMPONENTS)
        else HealthFinding("core", "PerRoute", CheckStatus.PASS, "The connection core is installed", "")
        app.perroute.service.IssueLog.recent(app.perroute.service.InstalledApplicationDiscovery.SOURCE).lastOrNull()?.let { issue ->
            findings += HealthFinding("apps", "PerRoute", CheckStatus.WARN, "The app list may be incomplete",
                "Windows did not return every installed app (${issue.message()}). Apps missing from the list can still be added with Browse.")
        }
        if (mode == ConnectionMode.NATIVE_PER_APP) {
            val driver = runCatching { backend.perAppDriverState() }.getOrDefault(ConnectionBackend.PerAppDriverState.UNKNOWN)
            findings += when {
                !environment.perAppReady && driver == ConnectionBackend.PerAppDriverState.MISSING -> HealthFinding("engine", "PerRoute", CheckStatus.FAIL, "The Windows Packet Filter driver is missing",
                    "The per-app engine is there, but the driver it uses to redirect apps (${backend.perAppDriverName()}) is not installed, so selected apps cannot be routed. " +
                        "Reinstall PerRoute with the Full Installer and restart Windows if it asks. Another network filter, VPN or security tool can also stop the driver from installing.", Repair.OPEN_COMPONENTS)
                !environment.perAppReady -> HealthFinding("engine", "PerRoute", CheckStatus.FAIL, "The per-app engine is not installed",
                    "Smart per-app needs it to send the selected apps to the tunnel. Install PerRoute with the Full Installer.", Repair.OPEN_COMPONENTS)
                driver == ConnectionBackend.PerAppDriverState.STOPPED -> HealthFinding("engine", "PerRoute", CheckStatus.WARN, "The Windows Packet Filter driver is not running",
                    "The driver (${backend.perAppDriverName()}) is installed but stopped, so selected apps may go out directly. Restart Windows, or reinstall PerRoute with the Full Installer.", Repair.OPEN_COMPONENTS)
                else -> HealthFinding("engine", "PerRoute", CheckStatus.PASS, "The per-app engine and driver are installed", "")
            }
        }

        when {
            desiredConnected && !connected && phase == ConnectionPhase.DISCONNECTED -> findings += HealthFinding("connection", "Connection", CheckStatus.FAIL,
                "The connection stopped", "You connected, but the connection is not running now${if (autoReconnect()) " and is being retried" else ""}.", Repair.RECONNECT)
            !connected -> findings += HealthFinding("connection", "Connection", CheckStatus.INFO, "Not connected",
                "Routed apps only use the tunnel while PerRoute is connected." + if (killSwitch() && mode == ConnectionMode.NATIVE_PER_APP) " The kill switch keeps the selected apps offline until then." else "")
            else -> findings += HealthFinding("connection", "Connection", CheckStatus.PASS, "Connected", "Mode: ${modeLabel(mode)}.")
        }

        if (connected && mode == ConnectionMode.SYSTEM_PROXY) {
            findings += if (!runCatching { backend.perRouteProxyActive() }.getOrDefault(true))
                HealthFinding("proxy", "Windows", CheckStatus.FAIL, "Another program changed the Windows proxy",
                    "Apps that follow system proxy settings no longer go through PerRoute. A VPN or another proxy tool may have replaced the setting.", Repair.REAPPLY_SYSTEM_PROXY)
            else HealthFinding("proxy", "Windows", CheckStatus.PASS, "The Windows proxy points to PerRoute", "")
        }
        if (!connected) {
            val plan = runCatching { networkRestorePlan() }.getOrNull()
            if (plan != null && plan.needed()) findings += HealthFinding("leftovers", "Windows", CheckStatus.WARN, "PerRoute left settings behind",
                plan.actions().joinToString(" ") { it.title() + "." }, Repair.RESTORE_NETWORK)
        }

        val route = when {
            target != null && connected -> activeRoutes.firstOrNull { r -> r.applications.any { AppRoutes.normalize(it) == AppRoutes.normalize(target) } }
            connected -> activeRoutes.firstOrNull { it.main }
            else -> null
        }
        val node = route?.node ?: target?.let { context.nodeFor(it) } ?: selectedNode()
        if (node == null) {
            findings += HealthFinding("server", "Server", CheckStatus.FAIL, "There is no server", "Add a server in Servers first.")
        } else if (!offline) {
            val name = cleanName(node.name())
            if (node.protocol() != app.perroute.model.Protocol.XRAY_PROFILE) {
                val reach = context.probe(node, 3_000)
                findings += if (!reach.reachable()) HealthFinding("server", "Server", CheckStatus.FAIL, "$name does not answer",
                    "Its address ${node.address()}:${node.port()} cannot be reached (${reach.detail()}). The server may be down or blocked from your network.",
                    if (route?.main != false) Repair.SWITCH_SERVER else null)
                else HealthFinding("server", "Server", CheckStatus.PASS, "$name is reachable", "Its port answered in ${reach.milliseconds()} ms.")
            }
            val source = nodeSource(node.key())
            if (source != PerRouteController.MANUAL_SOURCE && source != PerRouteController.IMPORTED_SOURCE) {
                val health = subscriptionHealth(source)
                if (health.severity() == app.perroute.subscription.SubscriptionHealth.Severity.PROBLEM) findings += HealthFinding("subscription", "Server",
                    CheckStatus.FAIL, "Its subscription: ${health.status().lowercase()}", health.findings().first().detail(), Repair.UPDATE_SUBSCRIPTIONS)
                else if (health.severity() == app.perroute.subscription.SubscriptionHealth.Severity.WARNING) findings += HealthFinding("subscription", "Server",
                    CheckStatus.WARN, "Its subscription: ${health.status().lowercase()}", health.findings().first().detail(), Repair.UPDATE_SUBSCRIPTIONS)
            }
            if (route != null) {
                val tunnel = runCatching { backend.checkTunnel(route.checkPort) }.getOrElse { app.perroute.backend.api.TunnelStatus(false, message(it)) }
                if (tunnel.success()) findings += HealthFinding("tunnel", "Tunnel", CheckStatus.PASS, "The tunnel through $name works", tunnel.detail())
                else {
                    val hint = CoreLogHints.analyze(context.coreLogTail(120))
                    findings += HealthFinding("tunnel", "Tunnel", CheckStatus.FAIL, hint?.title ?: "The tunnel through $name does not work",
                        (hint?.detail ?: "The server is reachable but does not carry traffic: ${tunnel.detail()}") + " Other routes are not affected.",
                        if (route.main) Repair.SWITCH_SERVER else Repair.RECONNECT)
                }
            }
        }

        if (target != null) {
            val selected = applications().any { AppRoutes.normalize(it) == AppRoutes.normalize(target) }
            when {
                mode != ConnectionMode.NATIVE_PER_APP -> findings += HealthFinding("app", "App", CheckStatus.INFO, "$appName follows the ${modeLabel(mode)} mode",
                    if (mode == ConnectionMode.SYSTEM_PROXY) "It only uses the tunnel if it follows the Windows proxy settings. Many games and some apps do not; use Smart per-app for them."
                    else "It only uses the tunnel if it is set to the local proxy ports itself.")
                !selected -> findings += HealthFinding("app", "App", CheckStatus.FAIL, "$appName is not selected",
                    "Only selected apps use the tunnel in Smart per-app. Select it in Apps and save.", Repair.OPEN_APPS)
                else -> {
                    val running = processIds(target).isNotEmpty()
                    findings += HealthFinding("app", "App", if (running) CheckStatus.PASS else CheckStatus.INFO, if (running) "$appName is selected and running" else "$appName is selected but not running",
                        if (running && connected && connectedAtMillis > 0) "If it was already open before you connected, connections it opened earlier may still use the old route. Restart it to be sure." else "")
                    if (!connected && killSwitch()) findings += HealthFinding("killswitch", "App", CheckStatus.WARN, "The kill switch keeps $appName offline",
                        "This is intended while PerRoute is disconnected. Connect, or turn the kill switch off.", Repair.RECONNECT)
                    temporaryRoute(target)?.let { temporary ->
                        findings += HealthFinding("temporary", "App", CheckStatus.INFO, "$appName is on a temporary server",
                            "${nodes().firstOrNull { it.key() == temporary.nodeKey }?.let { cleanName(it.name()) } ?: "A server"} until ${formatClockTime(temporary.until)}.")
                    }
                }
            }
        }

        if (!destination.isNullOrBlank()) {
            val host = destination.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')
            val verdict = evaluateRoute(target.orEmpty(), host, 443)
            val rule = verdict.rule()
            findings += when {
                rule != null && rule.action() == RouteRule.Action.BLOCK -> HealthFinding("rule", "Rules", CheckStatus.FAIL, "A rule blocks $host",
                    "Rule \"${ruleDisplayName(rule)}\" blocks it for ${if (rule.applicationPath().isBlank()) "every app" else healthAppName(rule.applicationPath())}. Change or disable the rule in Rules.")
                rule != null && rule.action() == RouteRule.Action.DIRECT -> HealthFinding("rule", "Rules", CheckStatus.INFO, "$host goes direct",
                    "Rule \"${ruleDisplayName(rule)}\" sends it around the tunnel. If the site only works through the tunnel, change that rule.")
                else -> HealthFinding("rule", "Rules", CheckStatus.PASS, "No rule stands in the way of $host", "It takes the app's route.")
            }
        }
        return HealthReport(findings, now, target?.let { appName } ?: "Your connection")
    }

    private fun unavailableHealthReport(subject: String) = HealthReport(listOf(HealthFinding(
        "platform", "This platform", CheckStatus.SKIPPED, "This report is not supported on macOS yet",
        "The current report checks Windows network state. Use connection status and route checks for the supported macOS paths.")),
        System.currentTimeMillis(), subject)

    fun safetyCheck(): HealthReport {
        if (!capabilities().networkRecovery()) return unavailableHealthReport("Routing safety")
        val findings = mutableListOf<HealthFinding>()
        val now = System.currentTimeMillis()
        val connected = isConnected()
        val mode = activeMode ?: configuredMode
        runCatching { checkRoutes() }
        val checks = routeChecks
        val direct = checks[PerRouteController.DIRECT_ROUTE_KEY]?.publicIp
        if (!connected) findings += HealthFinding("exit", "Exit address", CheckStatus.SKIPPED, "Connect to test the routes", "Exit addresses can only be observed through a running connection.")
        else for (route in activeRoutes) {
            val check = checks[route.id]
            val name = cleanName(route.node.name())
            findings += when {
                check?.state != RouteCheckState.PASSED -> HealthFinding("exit:${route.id}", "Exit address", CheckStatus.FAIL, "$name could not be tested", check?.detail?.ifBlank { null } ?: "No answer through this route.")
                direct != null && check.publicIp == direct -> HealthFinding("exit:${route.id}", "Exit address", CheckStatus.FAIL, "$name leaves from your own address",
                    "Traffic on this route appears with $direct, the same as without the tunnel. The server may forward traffic directly, or a rule sends the test site direct.")
                else -> HealthFinding("exit:${route.id}", "Exit address", CheckStatus.PASS, "$name hides your address",
                    "Seen as ${check.publicIp}${check.country?.let { " ($it)" } ?: ""}" + (direct?.let { "; your own address is $it." } ?: "."))
            }
        }

        val dns = dnsObservation
        findings += when (mode) {
            ConnectionMode.NATIVE_PER_APP -> HealthFinding("dns", "Name lookups", CheckStatus.WARN, "Name lookups use your normal DNS",
                "Windows looks up names for the selected apps itself, through ${dns?.systemServers?.joinToString(", ")?.ifBlank { null } ?: "your DNS servers"}" +
                    (dns?.publicResolver?.let { ", which reaches the internet as $it" } ?: "") +
                    ". The traffic goes through the tunnel, but your DNS provider can see which names are looked up. Turn on secure DNS (DNS over HTTPS) in Windows or in the browser to hide them. " +
                    "For web traffic PerRoute reads the site name from the connection itself, so a fake address from your DNS provider does not break it; other traffic still needs a correct answer.")
            ConnectionMode.SYSTEM_PROXY -> HealthFinding("dns", "Name lookups", CheckStatus.INFO, "Browsers send names to the proxy",
                "Apps that use the Windows proxy hand the site name to PerRoute, so it is looked up on the far side. Other apps look names up with your normal DNS.")
            else -> HealthFinding("dns", "Name lookups", CheckStatus.INFO, "Depends on each app", "Apps set to the SOCKS port can send names to the proxy; apps set to the HTTP port always do.")
        }

        if (mode == ConnectionMode.NATIVE_PER_APP) {
            val engine = runCatching { backend.networkState() }.getOrDefault(emptyMap())
            val redirecting = engine[app.perroute.netstate.NetworkStateKeys.ENGINE_SERVICE] == app.perroute.netstate.NetworkStateKeys.RUNNING ||
                engine[app.perroute.netstate.NetworkStateKeys.ENGINE_LIMITED] == app.perroute.netstate.NetworkStateKeys.RUNNING
            val ours = engine[app.perroute.netstate.NetworkStateKeys.ENGINE_CONFIG] == app.perroute.netstate.NetworkStateKeys.CONFIG_PERROUTE ||
                engine[app.perroute.netstate.NetworkStateKeys.ENGINE_LIMITED] == app.perroute.netstate.NetworkStateKeys.RUNNING
            val count = engine[app.perroute.netstate.NetworkStateKeys.ENGINE_APPS]?.toIntOrNull()
            if (engine.isNotEmpty()) findings += when {
                connected && (!redirecting || !ours) -> HealthFinding("engine", "Per-app engine", CheckStatus.FAIL, "The per-app engine is not redirecting",
                    "PerRoute is connected, but the engine is not running with PerRoute's app list, so the selected apps may connect directly.", Repair.RECONNECT)
                connected && count != null && engine[app.perroute.netstate.NetworkStateKeys.ENGINE_LIMITED] != app.perroute.netstate.NetworkStateKeys.RUNNING && count != applications().size ->
                    HealthFinding("engine", "Per-app engine", CheckStatus.WARN, "The engine redirects ${plural(count, "app")}, not ${applications().size}",
                        "Its app list differs from your selection. Reconnect to bring them in line.", Repair.RECONNECT)
                connected && engine[app.perroute.netstate.NetworkStateKeys.ENGINE_LIMITED] == app.perroute.netstate.NetworkStateKeys.RUNNING ->
                    HealthFinding("engine", "Per-app engine", CheckStatus.PASS, "The selected apps are redirected",
                        "The engine runs as your user, which is the normal mode. It cannot attribute traffic of apps running as Administrator or as a system service, so those stay direct.")
                connected -> HealthFinding("engine", "Per-app engine", CheckStatus.PASS, "The selected apps are redirected", "The engine uses PerRoute's list of ${plural(applications().size, "app")}.")
                else -> HealthFinding("engine", "Per-app engine", CheckStatus.INFO, "Not connected", if (redirecting && ours) "The kill switch holds the selected apps." else "The selected apps connect directly until you connect.")
            }
            findings += if (killSwitch()) HealthFinding("killswitch", "Kill switch", CheckStatus.PASS, "The kill switch is on",
                "If the tunnel stops, the selected apps stay offline instead of using your direct connection." +
                    if (trafficGateway()) " The traffic gateway fails closed as well." else "")
            else HealthFinding("killswitch", "Kill switch", CheckStatus.WARN, "The kill switch is off",
                "If the tunnel stops unexpectedly, the selected apps fall back to your direct connection. Turn it on in Settings > Automation to keep them offline instead.")
        }

        val loaded = loadedRuleIds()
        val enabledRules = rules().filter { it.enabled() }
        if (loaded != null && enabledRules.isNotEmpty()) {
            val inUse = context.applicationPaths().map { AppRoutes.normalize(it) }.toSet()
            val missing = enabledRules.filter { it.id() !in loaded && (it.applicationPath().isBlank() || AppRoutes.normalize(it.applicationPath()) in inUse) }
            findings += if (missing.isEmpty()) HealthFinding("rules", "Rules", CheckStatus.PASS, "Every rule is loaded in the running core", "${plural(enabledRules.size, "rule")} checked.")
            else HealthFinding("rules", "Rules", CheckStatus.FAIL, "${plural(missing.size, "rule")} not loaded",
                "${joinNames(missing.map { ruleDisplayName(it) })} ${if (missing.size == 1) "is" else "are"} missing from the running core. Reconnect to load them.", Repair.RECONNECT)
        } else if (enabledRules.isNotEmpty()) findings += HealthFinding("rules", "Rules", CheckStatus.SKIPPED, "Connect to check the loaded rules", "Which rules the core uses can only be read while connected.")
        RuleAnalysis.shadowed(rules()).forEach { item ->
            findings += HealthFinding("shadow:${item.rule.id()}", "Rules", CheckStatus.WARN, "\"${ruleDisplayName(item.rule)}\" never applies",
                "\"${ruleDisplayName(item.by)}\" comes first and already decides every destination it lists. Remove it or move it up in Rules.")
        }

        val untrusted = context.untrustedLocalNetworks()
        if (untrusted.isNotEmpty()) {
            val blocked = context.blockedLocalRules()
            findings += if (blocked.isEmpty()) HealthFinding("local", "Local network", CheckStatus.PASS, "You are on a trusted network", "Routed apps can reach local devices.")
            else if (connected && appliedLocalBlocking == blocked) HealthFinding("local", "Local network", CheckStatus.PASS, "Local devices are blocked for routed apps",
                "This network is not trusted. Only its router and DNS servers are reachable from routed apps.")
            else HealthFinding("local", "Local network", CheckStatus.WARN, "Local protection applies after reconnecting", "This network is not trusted, but the running connection was started for another network.", Repair.RECONNECT)
        }

        findings += HealthFinding("loopback", "Local ports", CheckStatus.INFO, "Local ports are on this computer only",
            "PerRoute's ports listen on 127.0.0.1, so other devices cannot use them. On a computer shared by several signed-in users, other users can.")
        workspaces().filter { it.running }.forEach { view ->
            findings += HealthFinding("workspace:${view.workspace.id}", "Workspaces", CheckStatus.PASS, "${view.workspace.name} has its own route",
                "Its browser uses port ${view.port} through ${view.node?.let { cleanName(it.name()) } ?: "its server"}; if that stops, the window has no connection rather than a direct one.")
        }
        return HealthReport(findings, now, "Routing safety")
    }

    private fun formatSkew(seconds: Long): String = when {
        seconds < 120 -> "$seconds seconds"
        seconds < 7200 -> "${seconds / 60} minutes"
        else -> "${seconds / 3600} hours"
    }

    private fun formatClockTime(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
}
