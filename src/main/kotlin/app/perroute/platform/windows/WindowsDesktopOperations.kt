package app.perroute.platform.windows

import app.perroute.service.*
import app.perroute.backend.api.IsolatedTunnel
import app.perroute.localnet.LocalNetwork
import app.perroute.localnet.LocalNetworkScanner
import java.nio.file.Path
import java.util.function.Consumer

private const val BRIDGE_FIREWALL_RULE = "PerRoute LAN Bridge"

/** Preserves upstream Windows desktop operations behind the Java platform adapter. */
class WindowsDesktopOperations(private val systemProxy: WindowsSystemProxyManager,
    private val proxiFyre: ProxiFyreManager, private val xray: XrayProcessManager, private val dataDirectory: Path) {
    private val networkReader by lazy { WindowsNetworkState(systemProxy, proxiFyre, xray, dataDirectory) }
    fun networkState(): Map<String, String> = networkReader.read(app.perroute.backend.core.NetworkDiagnostics().systemDnsServers())
    fun resetSystemProxyLeftover(baseline: Map<String, String>?) = systemProxy.resetLeftover(baseline)
    fun removeRecoveryEntry() = systemProxy.removeStaleRecoveryEntry()
    fun perRouteProxyActive(): Boolean = runCatching { systemProxy.perRouteProxyActive() }.getOrDefault(false)

    fun startIsolatedCore(config: String, socksPort: Int): IsolatedTunnel {
        val core = IsolatedCore.start(config, socksPort)
        return object : IsolatedTunnel {
            override fun socksPort(): Int = core.socksPort()
            override fun alive(): Boolean = core.alive()
            override fun lastLines(): List<String> = core.lastLines()
            override fun close() = core.close()
        }
    }

    fun startIsolatedCore(config: String, socksPort: Int, onLine: Consumer<String>): IsolatedTunnel {
        val core = IsolatedCore.start(config, socksPort) { line -> onLine.accept(line) }
        return object : IsolatedTunnel {
            override fun socksPort(): Int = core.socksPort()
            override fun alive(): Boolean = core.alive()
            override fun lastLines(): List<String> = core.lastLines()
            override fun close() = core.close()
        }
    }

    fun resourceConditions() = app.perroute.service.ResourceConditions.read()

    fun resolveShortcut(shortcut: Path): Path? {
        val script = "(New-Object -ComObject WScript.Shell).CreateShortcut('" + shortcut.toString().replace("'", "''") + "').TargetPath"
        val output = powerShell(script) ?: return null
        return output.lines().lastOrNull { it.isNotBlank() }?.trim()?.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it) }.getOrNull() }
    }

    fun explorerMenuRegistered(executable: Path): Boolean = app.perroute.service.ExplorerMenu.registeredFor(executable)

    fun setExplorerMenu(executable: Path?, enabled: Boolean) {
        if (enabled && executable != null) app.perroute.service.ExplorerMenu.register(executable) else app.perroute.service.ExplorerMenu.unregister()
    }

    fun networkCategory(address: String): String {
        val script = "\$i=(Get-NetIPAddress -IPAddress '" + address.replace("'", "") + "' -ErrorAction Stop | Select-Object -First 1).InterfaceIndex;" +
            "(Get-NetConnectionProfile -InterfaceIndex \$i -ErrorAction Stop).NetworkCategory.ToString()"
        return powerShell(script)?.lines()?.lastOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    fun bridgeFirewallReady(core: Path, firstPort: Int, lastPort: Int): Boolean {
        val process = ProcessBuilder("netsh.exe", "advfirewall", "firewall", "show", "rule", "name=$BRIDGE_FIREWALL_RULE", "verbose")
            .redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); return false }
        if (process.exitValue() != 0) return false
        val ports = "$firstPort-$lastPort"
        val text = output.lowercase()
        return Regex(Regex.escape(ports)).findAll(output).count() >= 2 && Regex(Regex.escape(core.toString().lowercase())).findAll(text).count() >= 2 &&
            Regex("\\btcp\\b").containsMatchIn(text) && Regex("\\budp\\b").containsMatchIn(text)
    }

    fun allowBridgeFirewall(core: Path, firstPort: Int, lastPort: Int) {
        val program = core.toString().replace("'", "''")
        val rule = { protocol: String ->
            "netsh advfirewall firewall add rule name=\"$BRIDGE_FIREWALL_RULE\" dir=in action=allow program=\"\$core\" protocol=$protocol localport=$firstPort-$lastPort profile=private,domain | Out-Null; if (\$LASTEXITCODE -ne 0) { exit 2 }"
        }
        val inner = "\$core='$program'; netsh advfirewall firewall delete rule name=\"$BRIDGE_FIREWALL_RULE\" | Out-Null; ${rule("TCP")}; ${rule("UDP")}; exit 0"
        val encoded = java.util.Base64.getEncoder().encodeToString(inner.toByteArray(Charsets.UTF_16LE))
        val outer = "\$p = Start-Process -FilePath powershell.exe -ArgumentList '-NoProfile','-NonInteractive','-EncodedCommand','$encoded' " +
            "-Verb RunAs -Wait -PassThru -WindowStyle Hidden; exit \$p.ExitCode"
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", outer).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); error("Windows did not answer in time.") }
        if (process.exitValue() != 0) {
            val cancelled = output.contains("canceled by the user", ignoreCase = true) || output.contains("cancelled by the user", ignoreCase = true)
            error(if (cancelled) "The Administrator prompt was cancelled." else "Windows Firewall did not accept the rule.")
        }
    }

    private fun powerShell(script: String): String? = runCatching {
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); return@runCatching null }
        if (process.exitValue() != 0) null else output
    }.getOrNull()

    fun startProgram(command: List<String>): Process {
        val executable = Path.of(command.first())
        return ProcessBuilder(command).directory(executable.parent?.toFile()).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    }

    fun profileInUse(profile: Path): Boolean {
        val lock = profile.resolve("lockfile")
        if (!java.nio.file.Files.exists(lock)) return false
        return runCatching { java.nio.file.Files.delete(lock); false }.getOrDefault(true)
    }

    fun shellOpen(file: Path, arguments: List<String>, elevated: Boolean) {
        val quoted = arguments.joinToString(",") { "'" + it.replace("'", "''") + "'" }
        val command = buildString {
            append("Start-Process -FilePath '").append(file.toString().replace("'", "''")).append("'")
            if (arguments.isNotEmpty()) append(" -ArgumentList @(").append(quoted).append(")")
            if (elevated) append(" -Verb RunAs")
        }
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
            .redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); error("Windows did not start ${file.fileName} in time.") }
        if (process.exitValue() != 0) {
            val cancelled = output.contains("canceled by the user", ignoreCase = true) || output.contains("cancelled by the user", ignoreCase = true)
            error(if (cancelled) "The Administrator prompt was cancelled." else "Windows could not start ${file.fileName}: ${output.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()}")
        }
    }

    private val scanner by lazy { LocalNetworkScanner() }
    fun localNetworks(resolveNames: Boolean): List<LocalNetwork> =
        scanner.scan(WindowsNetworkState.defaultGateways(), app.perroute.backend.core.NetworkDiagnostics().systemDnsServers(), resolveNames)

    fun systemResolves(host: String): Boolean = runCatching { java.net.InetAddress.getByName(host); true }.getOrDefault(false)

    fun directServerTime(): Long? = runCatching {
        val client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).proxy(java.net.ProxySelector.of(null)).build()
        val response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://www.cloudflare.com/cdn-cgi/trace"))
            .timeout(java.time.Duration.ofSeconds(6)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString())
        response.headers().firstValue("date").map { java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.orElse(null)
    }.getOrNull()

}
