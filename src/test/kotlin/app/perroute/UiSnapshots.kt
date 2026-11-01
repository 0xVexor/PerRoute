package app.perroute

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import app.perroute.compose.Appearance
import app.perroute.backend.api.ConnectionBackend
import app.perroute.compose.DiagnosticsTab
import app.perroute.compose.ImportMethod
import app.perroute.compose.ImportSession
import app.perroute.compose.RestoreSession
import app.perroute.compose.UiStyle
import app.perroute.compose.backupModel
import app.perroute.compose.serversModel
import app.perroute.compose.rulesModel
import app.perroute.compose.RuleEditor
import app.perroute.compose.ui.components.KeyboardNavigation
import app.perroute.compose.controller.PerRouteController
import app.perroute.compose.PerRouteWindow
import app.perroute.compose.ui.components.QuickAccessWindow
import app.perroute.backend.api.RouteObservation
import app.perroute.compose.SettingsCategory
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.Shell
import app.perroute.compose.Tab
import app.perroute.model.AppRoutes
import app.perroute.model.AppState
import app.perroute.model.ConnectionMode
import app.perroute.model.ConnectionProfile
import app.perroute.model.Protocol
import app.perroute.model.ProxyNode
import app.perroute.model.RouteRule
import app.perroute.parser.SubscriptionParser
import app.perroute.persistence.AppStateStore
import app.perroute.service.AppProxyLauncher
import app.perroute.service.InstalledApplicationDiscovery
import app.perroute.service.ProcessDiscovery
import app.perroute.service.ProxyHealthCheck
import app.perroute.service.XrayConfigGenerator
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.skia.EncodedImageFormat
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.function.Consumer
import java.util.prefs.AbstractPreferences
import kotlin.coroutines.CoroutineContext

private const val SUBSCRIPTION = "https://subscription.example/api/v1/client/subscribe?token=demo"
private const val CHROME = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"
private const val TELEGRAM = "C:\\Users\\demo\\AppData\\Roaming\\Telegram Desktop\\Telegram.exe"
private const val DISCORD = "C:\\Users\\demo\\AppData\\Local\\Discord\\app-1.0.9\\Discord.exe"
private const val SPOTIFY = "C:\\Users\\demo\\AppData\\Roaming\\Spotify\\Spotify.exe"

private class QueueDispatcher : CoroutineDispatcher() {
    private val queue = ConcurrentLinkedQueue<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
    fun drain() { while (true) (queue.poll() ?: return).run() }
}

private class MemoryPreferences : AbstractPreferences(null, "") {
    private val values = LinkedHashMap<String, String>()
    override fun putSpi(key: String, value: String) { values[key] = value }
    override fun getSpi(key: String): String? = values[key]
    override fun removeSpi(key: String) { values.remove(key) }
    override fun removeNodeSpi() {}
    override fun keysSpi(): Array<String> = values.keys.toTypedArray()
    override fun childrenNamesSpi(): Array<String> = emptyArray()
    override fun childSpi(name: String): AbstractPreferences = MemoryPreferences()
    override fun syncSpi() {}
    override fun flushSpi() {}
}

private class DemoBackend(private val perAppDriver: Boolean = true) : ConnectionBackend {
    override fun capabilities() = app.perroute.backend.api.BackendCapabilities.windows()
    override fun installCore(progress: Consumer<String>) { error("Fixture") }
    override fun installPerApp(progress: Consumer<String>) { error("Fixture") }
    override fun checkForUpdate(feed: String): app.perroute.backend.api.UpdateDescriptor = error("Fixture")
    override fun downloadUpdate(update: app.perroute.backend.api.UpdateDescriptor, progress: Consumer<Int>): java.nio.file.Path = error("Fixture")
    override fun launchUpdate(installer: java.nio.file.Path) { error("Fixture") }
    override fun setGatewayLimits(path: String, down: Long, up: Long) {}
    override fun addGatewayListener(listener: Consumer<app.perroute.gateway.GatewayConnection>) {}
    override fun applyRuntimeChange(change: XrayConfigGenerator.RuntimeChange, newTag: String, oldTag: String, config: String) { error("Fixture") }

    @Volatile private var running = false
    @Volatile private var startedAt = 0L
    @Volatile private var gatewayOn = false
    private val logListeners = java.util.concurrent.CopyOnWriteArrayList<Consumer<String>>()
    override fun coreReady() = true
    override fun perAppReady() = perAppDriver
    override fun elevated() = false
    override fun validate(config: String, tun: Boolean) {}
    override fun start(config: String, tun: Boolean) { running = true; startedAt = System.currentTimeMillis() }
    override fun stop() { running = false }
    override fun isRunning() = running
    override fun stopOrphan() {}
    override fun addLogListener(listener: Consumer<String>) { logListeners += listener }
    fun emit(line: String) = logListeners.forEach { it.accept(line) }
    override fun startGateway(routes: List<app.perroute.gateway.AppGateway.Route>) { gatewayOn = true }
    override fun stopGateway() { gatewayOn = false }
    override fun gatewayRunning() = gatewayOn
    override fun gatewayCounters(): Map<String, app.perroute.gateway.AppGateway.Counters> {
        val seconds = (System.currentTimeMillis() - startedAt) / 1_000.0
        return listOf(CHROME to (1_550_000L to 390_000L), TELEGRAM to (1_230_000L to 150_000L), DISCORD to (0L to 0L), SPOTIFY to (40_000L to 12_000L))
            .associate { (path, rates) -> path to app.perroute.gateway.AppGateway.Counters((rates.first * seconds).toLong(), (rates.second * seconds).toLong(), 3, 0) }
    }
    override fun addRunningListener(listener: Consumer<Boolean>) {}
    override fun applyPerApp(applications: List<String>) {}
    override fun restorePerApp() {}
    override fun stopPerAppSidecar() {}
    override fun applySystemProxy() {}
    override fun restoreSystemProxy() {}
    override fun checkTunnel(socksPort: Int): app.perroute.backend.api.TunnelStatus =
        if (socksPort == XrayConfigGenerator.routeCheckPort(1)) app.perroute.backend.api.TunnelStatus(false, "The proxy could not reach the test endpoint (SOCKS 4).")
        else app.perroute.backend.api.TunnelStatus(true, "Tunnel answered from cp.cloudflare.com (HTTP 204)")
    override fun readInboundCounters(): Map<String, Long> {
        val seconds = (System.currentTimeMillis() - startedAt) / 250.0
        val counters = LinkedHashMap<String, Long>()
        listOf(4_600_000L to 420_000L, 900_000L to 150_000L, 0L to 0L, 300_000L to 27_000L).forEachIndexed { index, (down, up) ->
            counters["inbound>>>${XrayConfigGenerator.perAppTag(index)}>>>traffic>>>downlink"] = (down * seconds / 4).toLong()
            counters["inbound>>>${XrayConfigGenerator.perAppTag(index)}>>>traffic>>>uplink"] = (up * seconds / 4).toLong()
        }
        return counters
    }
    override fun lookupPublicAddress(socksPort: Int?): RouteObservation = when (socksPort) {
        null -> RouteObservation("198.51.100.24", "IR", null)
        AppProxyLauncher.SOCKS_PORT -> RouteObservation("203.0.113.10", "DE", null)
        XrayConfigGenerator.routeCheckPort(0) -> RouteObservation("203.0.113.20", "NL", null)
        else -> RouteObservation(null, null, "The proxy could not reach www.cloudflare.com (SOCKS 4).")
    }
    override fun systemDnsServers() = listOf("192.0.2.53", "192.0.2.54")
    override fun publicResolver(): String? = "192.0.2.80"
    override fun installedApplications() = listOf(
        app.perroute.backend.api.ApplicationDescriptor("Google Chrome", CHROME),
        app.perroute.backend.api.ApplicationDescriptor("Telegram", TELEGRAM),
        app.perroute.backend.api.ApplicationDescriptor("Discord", DISCORD),
        app.perroute.backend.api.ApplicationDescriptor("Spotify", SPOTIFY),
        app.perroute.backend.api.ApplicationDescriptor("Visual Studio Code", "C:\\Program Files\\Microsoft VS Code\\Code.exe"),
        app.perroute.backend.api.ApplicationDescriptor("Mozilla Firefox", "C:\\Program Files\\Mozilla Firefox\\firefox.exe"),
        app.perroute.backend.api.ApplicationDescriptor("Steam", "C:\\Program Files (x86)\\Steam\\steam.exe"),
        app.perroute.backend.api.ApplicationDescriptor("A media player with a remarkably long product name, Professional Edition",
            "C:\\Program Files\\Example Software Company\\Remarkably Long Product Folder\\Professional Edition\\bin\\x64\\player.exe")
    )
    override fun bridgeFirewallReady(core: java.nio.file.Path, firstPort: Int, lastPort: Int) = true
    override fun startIsolatedCore(config: String, socksPort: Int, onLine: Consumer<String>): app.perroute.backend.api.IsolatedTunnel {
        onLine.accept("2026/10/19 16:12:01.000000 from 192.168.1.42:51000 accepted tcp:www.youtube.com:443 [perroute-bridge-0 -> proxy]")
        onLine.accept("2026/10/19 16:12:02.000000 from 192.168.1.42:51001 accepted tcp:i.ytimg.com:443 [perroute-bridge-0 -> proxy]")
        return object : app.perroute.backend.api.IsolatedTunnel {
            override fun socksPort() = socksPort
            override fun alive() = true
            override fun lastLines() = emptyList<String>()
            override fun close() {}
        }
    }
    override fun runningApplications() = listOf(
        app.perroute.backend.api.ApplicationDescriptor("chrome.exe", CHROME), app.perroute.backend.api.ApplicationDescriptor("Telegram.exe", TELEGRAM))
    override fun localNetworks(resolveNames: Boolean) = listOf(app.perroute.localnet.LocalNetwork("home-net", "Wi-Fi", "192.168.1.20", "192.168.1.0/24", 24,
        "192.168.1.1", "aa-bb-cc-dd-ee-ff", listOf("192.168.1.1"), listOf(
            app.perroute.localnet.LocalNetwork.Device("192.168.1.1", "aa-bb-cc-dd-ee-ff", "router.home", true),
            app.perroute.localnet.LocalNetwork.Device("192.168.1.40", "11-22-33-44-55-66", "nas.home", false),
            app.perroute.localnet.LocalNetwork.Device("192.168.1.77", "22-33-44-55-66-77", "", false))))
    override fun networkState(): Map<String, String> = mapOf(
        "wininet.ProxyEnable" to "0x1", "wininet.ProxyServer" to app.perroute.service.WindowsSystemProxyManager.perRouteProxyServer(),
        "wininet.ProxyOverride" to "<local>;localhost;127.*;[::1]", "wininet.backupPending" to "yes", "startup.recoveryEntry" to "yes",
        "engine.installed" to "yes", "engine.service" to "running", "engine.config" to "perroute", "engine.configApps" to "4", "engine.backupPending" to "yes",
        "engine.limited" to "stopped", "core.processes" to "0", "perroute.session" to "yes", "context.winhttpProxy" to "Direct",
        "context.dnsServers" to "192.0.2.53", "context.gateways" to "192.168.1.1", "context.adapters" to "Wi-Fi 192.168.1.20/24")
}

private class Demo(
    val advanced: Boolean = false,
    val connected: Boolean = false,
    val missing: Boolean = false,
    val empty: Boolean = false,
    val mode: ConnectionMode = ConnectionMode.NATIVE_PER_APP,
    val dark: Boolean = true,
    val auto: Boolean = false,
    val gateway: Boolean = true,
    val style: UiStyle = UiStyle.NEW
)

private fun seedQuality(controller: PerRouteController) {
    val now = System.currentTimeMillis()
    fun samples(key: String, reach: Long, tunnel: Long, count: Int = 6, ageMinutes: Long = 0, failures: Int = 0) {
        for (i in count downTo 1) {
            val at = now - ageMinutes * 60_000L - i * 90_000L
            controller.recordQualitySample(key, app.perroute.quality.QualitySample.reachability(at, reach + i % 3))
            controller.recordQualitySample(key, app.perroute.quality.QualitySample.tunnel(at + 500, i <= count - failures, tunnel + i * 4, ""))
        }
    }
    samples("de-1", 18, 95)
    samples("de-2", 60, 380, failures = 1)
    samples("nl-1", 170, 900, failures = 2)
    samples("us-3", 110, 450, ageMinutes = 180)
    (1..3).forEach { controller.recordQualitySample("uk-1", app.perroute.quality.QualitySample.tunnel(now - it * 60_000L, false, -1, "timeout")) }
}

private fun emitDemoConnections(backend: DemoBackend) {
    val lines = listOf(
        "[Info] [11] proxy/socks: TCP Connect request to tcp:142.250.185.78:443", "[Info] [11] app/dispatcher: sniffed domain: www.youtube.com",
        "[Info] [11] app/dispatcher: Hit route rule: [perroute-custom-r2#route0] so taking detour [perroute-route-0] for [tcp:www.youtube.com:443]",
        "from tcp:127.0.0.1:61001 accepted tcp:142.250.185.78:443 [perroute-app-0 -> perroute-route-0]",
        "[Info] [12] proxy/socks: TCP Connect request to tcp:149.154.167.220:443",
        "[Info] [12] app/dispatcher: taking detour [perroute-route-1] for [tcp:149.154.167.220:443]",
        "from tcp:127.0.0.1:61002 accepted tcp:149.154.167.220:443 [perroute-app-1 -> perroute-route-1]",
        "[Info] [13] proxy/socks: TCP Connect request to tcp:162.159.135.232:443", "[Info] [13] app/dispatcher: sniffed domain: discord.com",
        "[Info] [13] app/dispatcher: taking detour [proxy] for [tcp:discord.com:443]",
        "from tcp:127.0.0.1:61003 accepted tcp:162.159.135.232:443 [perroute-app-2 -> proxy]",
        "[Info] [14] proxy/socks: TCP Connect request to tcp:185.143.233.120:443", "[Info] [14] app/dispatcher: sniffed domain: bank.example.ir",
        "[Info] [14] app/dispatcher: Hit route rule: [perroute-custom-r1] so taking detour [direct] for [tcp:bank.example.ir:443]",
        "from tcp:127.0.0.1:61004 accepted tcp:185.143.233.120:443 [perroute-app-0 -> direct]",
        "[Info] [15] proxy/socks: TCP Connect request to tcp:35.186.224.25:443", "[Info] [15] app/dispatcher: sniffed domain: tracker.ads.example",
        "[Info] [15] app/dispatcher: Hit route rule: [perroute-custom-r3] so taking detour [block] for [tcp:tracker.ads.example:443]",
        "from tcp:127.0.0.1:61005 accepted tcp:35.186.224.25:443 [perroute-app-0 -> block]"
    )
    lines.forEach { backend.emit("2026/10/14 16:42:10.000000 $it") }
}

private val listeners = List(3) { ServerSocket(0, 16, InetAddress.getLoopbackAddress()) }.onEach { server ->
    Thread.ofPlatform().daemon().start { while (true) runCatching { server.accept().close() } }
}
private val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

private fun demoState(demo: Demo): AppState {
    val state = AppState()
    state.setConnectionMode(demo.mode)
    if (demo.empty) return state
    val main = ProxyNode("de-1", "\uD83C\uDDE9\uD83C\uDDEA Germany DE-01", Protocol.VLESS, "127.0.0.1", listeners[0].localPort, "id-de-1", mapOf("security" to "tls"), "vless://id-de-1@de1.example.net:443#Germany%20DE-01")
    val second = ProxyNode("de-2", "Germany DE-02", Protocol.VLESS, "127.0.0.1", listeners[1].localPort, "id-de-2", mapOf("security" to "tls"), "")
    val third = ProxyNode("nl-1", "Netherlands NL-01", Protocol.VLESS, "127.0.0.1", listeners[2].localPort, "id-nl-1", mapOf("security" to "tls"), "")
    val london = ProxyNode("uk-1", "London UK-01", Protocol.VMESS, "127.0.0.1", closedPort, "id-uk-1", emptyMap(), "")
    val long = ProxyNode("us-3", "US | New York premium gateway with an exceptionally long descriptive name 03", Protocol.TROJAN, "127.0.0.1", listeners[0].localPort, "id-us-3", emptyMap(), "")
    val profileJson = """{"remarks":"Balanced profile","outbounds":[{"tag":"proxy-a","protocol":"vless","settings":{"vnext":[{"address":"127.0.0.1","port":${listeners[1].localPort},"users":[{"id":"33333333-3333-3333-3333-333333333333","encryption":"none"}]}]}},{"tag":"direct","protocol":"freedom","settings":{}}]}"""
    val profile = SubscriptionParser().parse(profileJson).nodes()[0].let { ProxyNode("xp-1", it.name(), it.protocol(), it.address(), it.port(), it.credential(), it.options(), it.sourceLink()) }
    state.nodes().addAll(listOf(main, second, third, london, long, profile))
    listOf(main, second, third, long, profile).forEach { state.nodeSources()[it.key()] = SUBSCRIPTION }
    state.nodeSources()[london.key()] = PerRouteController.MANUAL_SOURCE
    state.subscriptionUrls().add(SUBSCRIPTION)
    state.favoriteNodeKeys().addAll(listOf("de-1", "nl-1"))
    state.nodeTags()["de-2"] = "Work"
    state.setSelectedNodeKey("de-1")
    state.appPaths().addAll(listOf(CHROME, TELEGRAM, DISCORD, SPOTIFY))
    state.setAdvancedRouting(demo.advanced)
    state.appRoutes()[AppRoutes.normalize(CHROME)] = "nl-1"
    state.appRoutes()[AppRoutes.normalize(TELEGRAM)] = if (demo.missing) "jp-4" else "de-2"
    state.appRouteLabels().putAll(mapOf("nl-1" to "Netherlands NL-01", "de-2" to "Germany DE-02", "jp-4" to "Japan JP-04"))
    if (demo.auto) {
        state.appRoutes().remove(AppRoutes.normalize(TELEGRAM))
        state.autoApps().add(AppRoutes.normalize(TELEGRAM))
    }
    state.bandwidthPolicies()[AppRoutes.normalize(CHROME)] = app.perroute.model.BandwidthPolicy(100_000_000, 20_000_000)
    state.bandwidthPolicies()[AppRoutes.normalize(TELEGRAM)] = app.perroute.model.BandwidthPolicy(10_000_000, 5_000_000)
    val rules = listOf(
        RouteRule("r1", "Local banking", "", RouteRule.MatchType.DOMAIN, "bank.example.ir", RouteRule.Action.DIRECT, true),
        RouteRule("r2", "Work tools", "", RouteRule.MatchType.DOMAIN, "github.com, slack.com", RouteRule.Action.PROXY, true),
        RouteRule("r3", "Block trackers", CHROME, RouteRule.MatchType.DOMAIN, "keyword:tracker", RouteRule.Action.BLOCK, true),
        RouteRule("r4", "Office network", "", RouteRule.MatchType.IP, "203.0.113.0/24", RouteRule.Action.DIRECT, false)
    )
    state.routeRules().addAll(rules)
    state.profiles().add(ConnectionProfile("p1", "Daily browsing", state.appPaths(), "de-1", ConnectionMode.NATIVE_PER_APP, rules, false, emptyMap()))
    state.profiles().add(ConnectionProfile("p2", "Office", state.appPaths(), "de-1", ConnectionMode.NATIVE_PER_APP, rules, true, HashMap(state.appRoutes())))
    state.profiles().add(ConnectionProfile("p3", "Travel", emptyList(), "nl-1", ConnectionMode.SYSTEM_PROXY, emptyList(), false, emptyMap()))
    state.setActiveProfileId(if (demo.advanced) "p2" else "p1")
    return state
}

private fun seedHistory(directory: Path) {
    val today = LocalDate.now()
    fun day(date: LocalDate, scale: Long) = """"$date":{"${CHROME.replace("\\", "\\\\")}":{"down":${3_500_000_00L * scale / 10},"up":${26_900_000L * scale / 10}},""" +
        """"${TELEGRAM.replace("\\", "\\\\")}":{"down":${1_400_000_00L * scale / 10},"up":${12_200_000L * scale / 10}},""" +
        """"${DISCORD.replace("\\", "\\\\")}":{"down":${81_300_000L * scale / 10},"up":${7_300_000L * scale / 10}}}"""
    val days = (0L until minOf(6L, today.dayOfMonth.toLong())).joinToString(",") { day(today.minusDays(it), 10 - it) }
    Files.createDirectories(directory)
    Files.writeString(directory.resolve("traffic-history.json"), "{\"days\":{$days}}")
}

private class Scene(
    val name: String,
    val demo: Demo = Demo(),
    val width: Int = 1180,
    val height: Int = 790,
    val scale: Float = 1f,
    val tab: Tab = Tab.Connection,
    val runChecks: Boolean = false,
    val settleMillis: Long = 1_600,
    val clicks: List<Pair<Int, Int>> = emptyList(),
    val tabs: Int = 0,
    val prepare: (Shell) -> Unit = {},
    val quick: Boolean = false,
    val setup: (PerRouteController) -> Unit = {}
)

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
private fun render(scene: Scene, output: Path) {
    val data = Files.createTempDirectory("perroute-ui-")
    System.setProperty("perroute.data", data.toString())
    seedHistory(data)
    val preferences = MemoryPreferences().apply {
        putBoolean("darkTheme", scene.demo.dark); putBoolean("autoSpeedTest", false); putBoolean("autoFailover", false); putBoolean("autoReconnect", false)
        putBoolean("v3.initialized", true); putBoolean("v3.trafficGateway", scene.demo.gateway); putBoolean("v3.routeMonitoring", true)
        putBoolean("v3.qualityBackground", true)
        put("ui.style", scene.demo.style.key)
    }
    val backend = DemoBackend()
    val controller = PerRouteController(demoState(scene.demo), AppStateStore(data.resolve("state.bin")), backend, preferences)
    if (!scene.demo.empty) seedQuality(controller)
    Appearance.style = scene.demo.style
    Appearance.dark = scene.demo.dark
    if (scene.demo.connected) {
        val result = controller.connect()
        check(result.ok) { "${scene.name}: ${result.message}" }
        Thread.sleep(2_600)
        emitDemoConnections(backend)
    }
    if (scene.runChecks) controller.checkRoutes()
    scene.setup(controller)
    val dispatcher = QueueDispatcher()
    val content: @Composable () -> Unit = {
        if (scene.quick) app.perroute.compose.PerRouteTheme(scene.demo.style, scene.demo.dark) {
            val nodes = controller.nodes()
            QuickAccessWindow(
                connected = controller.isConnected(), busy = false, working = false,
                main = nodes.firstOrNull { it.key() == controller.selectedNodeKey() }, nodes = nodes,
                favorites = nodes.filter { controller.isFavorite(it.key()) }.mapTo(hashSetOf()) { it.key() },
                traffic = controller.trafficSnapshot(), plan = controller.routePlan(), mainCheck = controller.routeChecks()[controller.selectedNodeKey()],
                onToggle = {}, onFastest = {}, onServer = {}, onOpen = {}, onExit = {},
                temporaries = controller.temporaryRoutes(), apps = controller.applications(), perApp = true
            )
        } else PerRouteWindow(controller, null, exitApp = {}, initialTab = scene.tab, prepare = scene.prepare)
    }
    val pixelWidth = (scene.width * scene.scale).toInt()
    val pixelHeight = (scene.height * scene.scale).toInt()
    val compose = ImageComposeScene(pixelWidth, pixelHeight, Density(scene.scale), dispatcher, content)
    try {
        fun settle(millis: Long) {
            val until = System.currentTimeMillis() + millis
            while (System.currentTimeMillis() < until) { dispatcher.drain(); compose.render(System.nanoTime()); Thread.sleep(40) }
        }
        settle(scene.settleMillis)
        scene.clicks.forEach { (x, y) ->
            val position = Offset(x * scene.scale, y * scene.scale)
            compose.sendPointerEvent(PointerEventType.Move, position)
            compose.sendPointerEvent(PointerEventType.Press, position)
            compose.sendPointerEvent(PointerEventType.Release, position)
            settle(500)
        }
        if (scene.tabs > 0) {
            KeyboardNavigation.value = true
            repeat(scene.tabs) {
                compose.sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyDown))
                compose.sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyUp))
                settle(120)
            }
        }
        dispatcher.drain()
        val image = compose.render(System.nanoTime())
        Files.write(output.resolve("${scene.name}.png"), image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        println("[snapshot] ${scene.name}  ${pixelWidth}x$pixelHeight")
    } finally {
        compose.close()
        controller.close()
        System.clearProperty("perroute.data")
        runCatching { Files.walk(data).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}

fun main(args: Array<String>) {
    val output = Path.of(args.getOrElse(0) { "build/ui-snapshots" }).toAbsolutePath()
    val only = args.drop(1).toSet()
    Files.createDirectories(output)
    val pastePreview: (Shell) -> Unit = { shell ->
        val outcome = shell.controller.previewPasted(
            "vless://11111111-1111-1111-1111-111111111111@fr1.example.net:443?security=tls#France FR-01\n" +
                "trojan://secret@sg1.example.net:443?security=tls#Singapore SG-02\nwireguard://not-supported\nvless://broken")
        serversModel(shell).import = ImportSession(shell, ImportMethod.PASTE, outcome)
    }
    val addServersSource: (Shell) -> Unit = { shell -> serversModel(shell).import = ImportSession(shell, ImportMethod.SUBSCRIPTION) }
    val restorePreview: (Shell) -> Unit = { shell ->
        shell.settingsCategory = SettingsCategory.Backup
        val file = Files.createTempFile("perroute-demo-", ".prb")
        shell.controller.exportBackup(file, app.perroute.backup.BackupService.Section.entries.toSet(), null)
        val request = shell.controller.openBackup(file, null).first!!
        backupModel(shell).restore = RestoreSession(shell, request)
    }
    val ruleEditor: (Shell) -> Unit = { shell -> rulesModel(shell).let { it.editor = RuleEditor(it.rules.first()) } }

    fun scenes(style: UiStyle): List<Scene> {
        val p = if (style == UiStyle.NEW) "new" else "simple"
        fun d(advanced: Boolean = false, connected: Boolean = false, missing: Boolean = false, empty: Boolean = false,
              mode: ConnectionMode = ConnectionMode.NATIVE_PER_APP, dark: Boolean = false, auto: Boolean = false, gateway: Boolean = true) =
            Demo(advanced, connected, missing, empty, mode, dark, auto, gateway, style)
        val advanced = d(advanced = true, auto = true)
        val advancedOn = d(advanced = true, connected = true, auto = true)
        return listOf(
            Scene("$p-01-connection-connected", advancedOn, settleMillis = 1_800),
            Scene("$p-02-connection-disconnected", advanced),
            Scene("$p-03-connection-empty", d(empty = true)),
            Scene("$p-04-connection-missing", d(advanced = true, missing = true)),
            Scene("$p-05-connection-local-proxy", d(connected = true, mode = ConnectionMode.BROWSER_PROXY)),
            Scene("$p-06-connection-dark", d(advanced = true, connected = true, auto = true, dark = true), settleMillis = 1_800),
            Scene("$p-10-apps", advanced, tab = Tab.Apps, settleMillis = 3_000),
            Scene("$p-11-apps-focused", advanced, tab = Tab.Apps, settleMillis = 3_000, prepare = { it.draft.focused = TELEGRAM }),
            Scene("$p-12-apps-locked", advancedOn, tab = Tab.Apps, settleMillis = 3_000, prepare = { it.draft.focused = CHROME }),
            Scene("$p-13-apps-unsaved", advanced, tab = Tab.Apps, settleMillis = 3_000, prepare = { it.draft.advanced = false }),
            Scene("$p-14-apps-missing", d(advanced = true, missing = true), tab = Tab.Apps, settleMillis = 3_000, prepare = { it.draft.focused = TELEGRAM }),
            Scene("$p-20-servers", advanced, tab = Tab.Servers, settleMillis = 2_500),
            Scene("$p-24-servers-long-main", advanced, tab = Tab.Servers, settleMillis = 2_500, prepare = { it.controller.selectNode("us-3"); it.refresh() }),
            Scene("$p-25-min-servers-long-main", advanced, 820, 620, tab = Tab.Servers, settleMillis = 2_500, prepare = { it.controller.selectNode("us-3"); it.refresh() }),
            Scene("$p-21-servers-empty", d(empty = true), tab = Tab.Servers),
            Scene("$p-22-add-servers-source", advanced, tab = Tab.Servers, prepare = addServersSource),
            Scene("$p-23-add-servers-review", advanced, tab = Tab.Servers, prepare = pastePreview, settleMillis = 2_000),
            Scene("$p-30-rules", advanced, tab = Tab.Rules),
            Scene("$p-31-rules-editor", advanced, tab = Tab.Rules, prepare = ruleEditor),
            Scene("$p-32-rules-locked", advancedOn, tab = Tab.Rules),
            Scene("$p-33-rules-empty", d(empty = true), tab = Tab.Rules),
            Scene("$p-40-profiles", advanced, tab = Tab.Profiles),
            Scene("$p-41-profiles-preview", advanced, tab = Tab.Profiles, prepare = { shell ->
                val model = app.perroute.compose.profilesModel(shell)
                if (style == UiStyle.NEW) model.preview(PerRouteController.SCENARIOS[0])
                else app.perroute.compose.showPresetDialog(shell, model, PerRouteController.SCENARIOS[0])
            }),
            Scene("$p-50-diagnostics-routes", advancedOn, tab = Tab.Diagnostics, runChecks = true),
            Scene("$p-51-diagnostics-inspector", d(advanced = true, connected = true, auto = true, gateway = false), tab = Tab.Diagnostics, settleMillis = 3_000, prepare = { it.diagnosticsTab = DiagnosticsTab.Inspector }),
            Scene("$p-52-diagnostics-loaded", advancedOn, tab = Tab.Diagnostics, prepare = { it.diagnosticsTab = DiagnosticsTab.Loaded }),
            Scene("$p-53-diagnostics-tester", advancedOn, tab = Tab.Diagnostics, prepare = { shell ->
                shell.diagnosticsTab = DiagnosticsTab.Tester
                app.perroute.compose.diagnosticsModel(shell).tester.let { it.app = CHROME; it.destination = "github.com"; it.check() }
            }),
            Scene("$p-54-diagnostics-disconnected", advanced, tab = Tab.Diagnostics),
            Scene("$p-60-traffic", advancedOn, tab = Tab.Traffic, settleMillis = 3_200),
            Scene("$p-61-traffic-empty", d(empty = true), tab = Tab.Traffic),
            Scene("$p-70-settings-general", d(), tab = Tab.Settings),
            Scene("$p-71-settings-connection", advanced, tab = Tab.Settings, prepare = { it.settingsCategory = SettingsCategory.Connection }),
            Scene("$p-72-settings-routing", d(), tab = Tab.Settings, prepare = { it.settingsCategory = SettingsCategory.Routing }),
            Scene("$p-73-settings-automation", d(), tab = Tab.Settings, prepare = { it.settingsCategory = SettingsCategory.Automation }),
            Scene("$p-74-settings-updates", d(), tab = Tab.Settings, prepare = { it.settingsCategory = SettingsCategory.Updates }),
            Scene("$p-75-settings-components", d(), tab = Tab.Settings, prepare = { it.settingsCategory = SettingsCategory.Components }),
            Scene("$p-76-settings-backup", d(), tab = Tab.Settings, prepare = { it.settingsCategory = SettingsCategory.Backup }),
            Scene("$p-77-restore-review", advanced, tab = Tab.Settings, prepare = restorePreview),
            Scene("$p-78-settings-log", advancedOn, tab = Tab.Settings, prepare = { it.settingsCategory = SettingsCategory.ActivityLog }),
            Scene("$p-79-settings-connection-locked", advancedOn, tab = Tab.Settings, prepare = { it.settingsCategory = SettingsCategory.Connection }),
            Scene("$p-80-quick-connected", advancedOn, 390, 640, quick = true),
            Scene("$p-81-quick-disconnected", d(), 390, 640, quick = true),
            Scene("$p-82-quick-dark", d(advanced = true, connected = true, auto = true, dark = true), 390, 640, quick = true),
            Scene("$p-90-min-connection", advancedOn, 820, 620),
            Scene("$p-91-min-apps", advanced, 820, 620, tab = Tab.Apps, settleMillis = 3_000, prepare = { it.draft.focused = TELEGRAM }),
            Scene("$p-92-min-servers", advanced, 820, 620, tab = Tab.Servers, settleMillis = 2_500),
            Scene("$p-93-min-settings", d(), 820, 620, tab = Tab.Settings),
            Scene("$p-94-scaled150-connection", advancedOn, 1180, 790, scale = 1.5f),
            Scene("$p-95-scaled150-servers", advanced, 1180, 790, scale = 1.5f, tab = Tab.Servers, settleMillis = 2_500),
            Scene("$p-96-dark-apps", d(advanced = true, auto = true, dark = true), tab = Tab.Apps, settleMillis = 3_000, prepare = { it.draft.focused = TELEGRAM }),
            Scene("$p-97-dark-servers", d(advanced = true, dark = true), tab = Tab.Servers, settleMillis = 2_500),
            Scene("$p-98-dark-settings", d(dark = true), tab = Tab.Settings),
            Scene("$p-99-keyboard-focus", advanced, tab = Tab.Rules, tabs = 6),
            Scene("$p-A0-workspaces", advanced, tab = Tab.Workspaces, settleMillis = 2_000, prepare = { shell ->
                val browser = Files.writeString(Path.of(System.getProperty("perroute.data")).resolve("chrome.exe"), "x").toString()
                shell.controller.createWorkspace("Work", browser, "")
                shell.controller.createWorkspace("Personal", browser, "nl-1")
            }),
            Scene("$p-A1-health-doctor", advancedOn, tab = Tab.Health, settleMillis = 2_000, prepare = { shell ->
                app.perroute.compose.healthModel(shell).let { it.app = TELEGRAM; it.doctor = shell.controller.diagnose(TELEGRAM, "web.telegram.org"); it.selected = it.doctor?.cause?.id }
            }),
            Scene("$p-A2-health-safety", advancedOn, tab = Tab.Health, settleMillis = 2_000, prepare = { shell ->
                app.perroute.compose.healthModel(shell).let { it.tab = app.perroute.compose.HealthTab.Safety; it.safety = shell.controller.safetyCheck(); it.selected = it.safety?.cause?.id }
            }),
            Scene("$p-A3-settings-local-network", d(), tab = Tab.Settings, settleMillis = 2_000, prepare = { it.settingsCategory = SettingsCategory.LocalNetwork }),
            Scene("$p-A4-settings-recovery", d(), tab = Tab.Settings, settleMillis = 2_500, prepare = { shell ->
                shell.controller.takeNetworkSnapshot(app.perroute.netstate.NetworkSnapshot.Reason.CONNECTED)
                shell.settingsCategory = SettingsCategory.Recovery
            }),
            Scene("$p-A5-connection-temporary", advancedOn, settleMillis = 2_000, prepare = { shell -> shell.controller.setTemporaryRoute(SPOTIFY, "de-2", 30); shell.refresh() }),
            Scene("$p-A6-temporary-dialog", advancedOn, tab = Tab.Apps, settleMillis = 2_500, prepare = { shell -> app.perroute.compose.showTemporaryDialog(shell, TELEGRAM, "nl-1") }),
            Scene("$p-A7-recipe-share", advanced, tab = Tab.Profiles, prepare = { shell -> app.perroute.compose.showRecipeExport(shell) }),
            Scene("$p-A8-profiles-recipes", advanced, tab = Tab.Profiles, prepare = { shell ->
                shell.controller.saveProfile("Gaming night")
            }),
            Scene("$p-A9-quick-temporary", advancedOn, 390, 640, quick = true, setup = { controller -> controller.setTemporaryRoute(SPOTIFY, "de-2", 45) }),
            Scene("$p-B0-bridge-off", advanced, tab = Tab.Bridge, settleMillis = 2_000, prepare = { shell ->
                shell.controller.addBridgeDevice("My phone"); shell.controller.addBridgeDevice("Living room TV")
            }),
            Scene("$p-B1-bridge-on", advanced, tab = Tab.Bridge, settleMillis = 2_500, prepare = { shell ->
                shell.controller.addBridgeDevice("My phone"); shell.controller.addBridgeDevice("Living room TV")
                shell.controller.startBridge("192.168.1.20", 60, false)
            }),
            Scene("$p-B2-bridge-setup", advanced, tab = Tab.Bridge, settleMillis = 2_500, prepare = { shell ->
                shell.controller.addBridgeDevice("My phone")
                shell.controller.startBridge("192.168.1.20", 0, false)
                app.perroute.compose.bridgeModel(shell).let { model -> model.refresh(); model.setup(model.view.devices.first()) }
            }),
            Scene("$p-B3-bridge-empty", advanced, tab = Tab.Bridge, settleMillis = 2_000),
            Scene("$p-B4-quick-route", advanced, tab = Tab.Connection, settleMillis = 2_000, prepare = { shell ->
                val slack = Files.writeString(Path.of(System.getProperty("perroute.data")).resolve("slack.exe"), "x").toString()
                app.perroute.compose.openQuickRoute(shell, slack)
            }),
            Scene("$p-B5-quick-route-connected", advancedOn, tab = Tab.Connection, settleMillis = 2_000, prepare = { shell ->
                app.perroute.compose.openQuickRoute(shell, Files.writeString(Path.of(System.getProperty("perroute.data")).resolve("slack.exe"), "x").toString())
            })
        )
    }
    val all = scenes(UiStyle.NEW) + scenes(UiStyle.SIMPLE)
    all.filter { scene -> only.isEmpty() || only.any { scene.name == it || (it.endsWith("*") && scene.name.startsWith(it.dropLast(1))) } }.forEach { render(it, output) }
    println("Snapshots written to $output")
    kotlin.system.exitProcess(0)
}
