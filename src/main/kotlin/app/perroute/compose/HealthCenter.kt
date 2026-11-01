package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import app.perroute.model.DomainEntries
import app.perroute.model.RouteRule
import java.nio.file.Path
import java.util.Locale

enum class CheckStatus { PASS, WARN, FAIL, INFO, SKIPPED }

enum class Repair(val label: String, val detail: String) {
    RECONNECT("Reconnect", "Stops the connection and starts it again with your current setup."),
    RESTORE_NETWORK("Restore network settings", "Removes what PerRoute left behind, and nothing that Windows or another program set."),
    REAPPLY_SYSTEM_PROXY("Set the Windows proxy again", "Writes PerRoute's proxy into the Windows settings again. The settings another program wrote are saved and put back when you disconnect."),
    SWITCH_SERVER("Switch to a working server", "Tests your servers and makes the fastest one that works the main server."),
    STOP_LEFTOVER_CORES("Stop leftover cores", "Stops Xray processes left over from an earlier session."),
    UPDATE_SUBSCRIPTIONS("Update subscriptions", "Downloads the current server lists from your providers."),
    TURN_OFF_KILL_SWITCH("Turn off the kill switch", "Lets the selected apps use your direct connection while PerRoute is disconnected."),
    OPEN_COMPONENTS("Open Components", "Shows what is installed and lets you install the missing part."),
    OPEN_APPS("Open Apps", "Lets you select the app or change its route.")
}

data class HealthFinding(
    val id: String,
    val area: String,
    val status: CheckStatus,
    val title: String,
    val detail: String,
    val repair: Repair? = null
)

data class HealthReport(val findings: List<HealthFinding>, val atMillis: Long, val subject: String) {
    val cause: HealthFinding? get() = findings.firstOrNull { it.status == CheckStatus.FAIL } ?: findings.firstOrNull { it.status == CheckStatus.WARN }
    val passed: Int get() = findings.count { it.status == CheckStatus.PASS }
    val problems: Int get() = findings.count { it.status == CheckStatus.FAIL || it.status == CheckStatus.WARN }
}

internal object CoreLogHints {
    data class Hint(val title: String, val detail: String)

    private val patterns: List<Pair<Regex, Hint>> = listOf(
        Regex("(?i)x509|certificate|tls: (bad|unknown) certificate") to Hint("The server's TLS certificate is not accepted",
            "The certificate does not match the server name in its settings (SNI), or it expired. Update the subscription; if it persists, the provider has to fix the server."),
        Regex("(?i)reality.*(invalid|failed|verify)|(invalid|failed).*reality") to Hint("The Reality handshake is refused",
            "The server's public key or short ID in your settings does not match the server. Update the subscription to get the current settings."),
        Regex("(?i)invalid (request )?user|not a valid user|user not found|invalid uuid|unknown user|authentication failed|bad password|invalid password") to Hint("The server does not accept your account",
            "Your user ID or password is not accepted. The subscription may have expired or been reset by the provider."),
        Regex("(?i)connection refused|actively refused") to Hint("The server refuses connections",
            "Nothing answers on the server's port. It is probably down or has moved; try another server."),
        Regex("(?i)i/o timeout|deadline exceeded|timed out|timeout") to Hint("Connections to the server time out",
            "The server does not answer in time. It may be blocked from your network, overloaded or down; another server or transport may work."),
        Regex("(?i)no such host|lookup .* (failed|no such)") to Hint("The server's address cannot be looked up",
            "Windows cannot resolve the server's host name. Check your DNS, or the server name changed; update the subscription."),
        Regex("(?i)connection reset|forcibly closed|broken pipe|unexpected eof") to Hint("The connection to the server is cut off",
            "Something between you and the server resets the connection. This is typical of filtering; a different server or transport may work.")
    )

    fun analyze(lines: List<String>): Hint? {
        val failing = lines.filter { line -> line.contains("fail", true) || line.contains("error", true) || line.contains("rejected", true) || line.contains("refused", true) }
        if (failing.isEmpty()) return null
        return patterns.map { (pattern, hint) -> hint to failing.count { pattern.containsMatchIn(it) } }
            .filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }
}

internal object RuleAnalysis {
    data class Shadowed(val rule: RouteRule, val by: RouteRule)

    fun shadowed(rules: List<RouteRule>): List<Shadowed> {
        val enabled = rules.filter { it.enabled() }
        val result = mutableListOf<Shadowed>()
        for ((index, later) in enabled.withIndex()) {
            val laterValues = values(later)
            if (laterValues.isEmpty() || laterValues.any { it.startsWith("geosite:") || it.startsWith("geoip:") || it.startsWith("regexp:") || it.startsWith("keyword:") }) continue
            for (earlier in enabled.subList(0, index)) {
                if (earlier.matchType() != later.matchType()) continue
                if (earlier.applicationPath().isNotBlank() && !earlier.applicationPath().equals(later.applicationPath(), ignoreCase = true)) continue
                val earlierValues = values(earlier)
                if (laterValues.all { value -> earlierValues.any { covers(later.matchType(), it, value) } }) { result += Shadowed(later, earlier); break }
            }
        }
        return result
    }

    private fun values(rule: RouteRule): List<String> {
        val text = if (rule.matchType() == RouteRule.MatchType.DOMAIN) DomainEntries.normalizeList(rule.value()) else rule.value()
        return text.split(',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
    }

    private fun covers(type: RouteRule.MatchType, earlier: String, later: String): Boolean = when (type) {
        RouteRule.MatchType.DOMAIN -> {
            val e = earlier.removePrefix("domain:")
            val l = later.removePrefix("domain:").removePrefix("full:")
            when {
                earlier.startsWith("full:") -> later.startsWith("full:") && earlier == later
                earlier.startsWith("keyword:") -> l.contains(earlier.removePrefix("keyword:"))
                earlier.startsWith("geosite:") || earlier.startsWith("regexp:") -> false
                else -> l == e || l.endsWith(".$e")
            }
        }
        RouteRule.MatchType.IP -> earlier == later || cidrCovers(earlier, later)
        RouteRule.MatchType.PORT -> portsCover(earlier, later)
    }

    private fun cidrCovers(earlier: String, later: String): Boolean = runCatching {
        if (!earlier.contains('/') || earlier.contains(':') || later.contains(':')) return false
        val (network, prefix) = earlier.split('/').let { it[0] to it[1].toInt() }
        val laterPrefix = if (later.contains('/')) later.substringAfter('/').toInt() else 32
        laterPrefix >= prefix && app.perroute.localnet.LocalNetwork.contains("$network/$prefix", later.substringBefore('/'))
    }.getOrDefault(false)

    private fun portsCover(earlier: String, later: String): Boolean = runCatching {
        fun ranges(text: String) = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { part ->
            val bounds = part.split('-').map { it.trim().toInt() }
            bounds.first()..bounds.last()
        }
        val covering = ranges(earlier)
        ranges(later).all { range -> covering.any { it.first <= range.first && it.last >= range.last } }
    }.getOrDefault(false)
}

internal fun healthAppName(path: String?): String =
    path?.let { runCatching { Path.of(it).fileName.toString().removeSuffix(".exe").removeSuffix(".EXE") }.getOrDefault(it) }.orEmpty()
