package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.gaming.GamingScore
import app.perroute.model.ProxyNode
import app.perroute.shadow.ShadowExperiment
import app.perroute.subscription.SubscriptionHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal fun showShadowDialog(shell: Shell, candidate: ProxyNode? = null, routeId: String? = null) {
    val chosen = mutableStateOf(candidate?.key())
    val route = mutableStateOf(routeId ?: shell.activeRoutes.firstOrNull { it.main }?.id)
    val running = mutableStateOf(false)
    val result = mutableStateOf<ShadowResult?>(null)
    val destinations = mutableStateOf(shell.controller.shadowDestinations(route.value))
    val cancelled = AtomicBoolean(false)
    fun start() {
        val key = chosen.value ?: return
        cancelled.set(false)
        running.value = true
        result.value = null
        shell.scope.launch {
            try {
                result.value = withContext(Dispatchers.IO) { shell.controller.runShadowExperiment(route.value, key, destinations.value) { cancelled.get() } }
            } finally { running.value = false }
        }
    }
    shell.showDialog(onDismiss = { cancelled.set(true); shell.closeDialog() }) {
        val live = shell.activeRoutes.firstOrNull { it.id == route.value }
        DialogCard("Compare with a live route", "Requests go through both sides at the same time. The live route is never changed.",
            { cancelled.set(true); shell.closeDialog() }, width = 620.dp, actions = {
                SecondaryButton("Close") { cancelled.set(true); shell.closeDialog() }
                val outcome = result.value?.report?.verdict()?.outcome()
                val node = shell.nodes.firstOrNull { it.key() == chosen.value }
                if (outcome == ShadowExperiment.Outcome.BETTER && node != null && live != null) {
                    if (!live.main && live.applications.size == 1) SecondaryButton("Try for 30 min", icon = Icons.Rounded.Timer) {
                        shell.closeDialog(); shell.run { shell.controller.setTemporaryRoute(live.applications.first(), node.key(), 30) }
                    }
                    PrimaryButton("Use ${serverName(node)}") { shell.closeDialog(); shell.run { shell.controller.changeRouteServer(live.id, node.key()) } }
                } else PrimaryButton(if (running.value) "Comparing…" else if (result.value == null) "Compare" else "Compare again", icon = Icons.Rounded.CompareArrows,
                    busy = running.value, enabled = !running.value && chosen.value != null) { start() }
            }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("Live route")
                    if (shell.connected && shell.activeRoutes.isNotEmpty()) MenuButton(live?.let { routeTitle(it) } ?: "Main route",
                        shell.activeRoutes.map { item -> MenuEntry(routeTitle(item), detail = serverName(item.node), selected = item.id == route.value) {
                            route.value = item.id; destinations.value = shell.controller.shadowDestinations(item.id); result.value = null } },
                        Modifier.fillMaxWidth(), accessibleLabel = "Live route to compare", menuWidth = 340.dp)
                    else Text("Not connected: the current main server is tested on a core of its own.", color = Muted, fontSize = 11.5.sp, lineHeight = 16.sp)
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("Candidate")
                    ServerChooser(shell, chosen.value, "Candidate server", exclude = live?.node?.key() ?: shell.selectedNodeKey) { chosen.value = it.key(); result.value = null }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Destinations: " + destinations.value.joinToString(", "), color = Muted, fontSize = 11.5.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(10.dp))
            val outcome = result.value
            when {
                running.value -> LoadingState("Sending ${ShadowExperiment.ROUNDS} requests to each destination on both sides…", Modifier.fillMaxWidth())
                outcome == null -> DialogText("The test uses only the destinations above, over HTTPS. It shows how the two paths compare right now; it cannot promise how every connection of an app will behave.", Muted)
                outcome.error.isNotBlank() -> InfoBanner(outcome.error, Modifier.fillMaxWidth(), Red)
                outcome.report != null -> ShadowReport(outcome)
            }
        }
    }
}

private fun routeTitle(route: ActiveRoute): String = when {
    route.main -> "Main route"
    route.applications.isEmpty() -> serverName(route.node)
    else -> joinNames(route.applications.map(::appName))
}

@Composable
private fun ShadowReport(result: ShadowResult) {
    val report = result.report ?: return
    val verdict = report.verdict()
    val color = when (verdict.outcome()) {
        ShadowExperiment.Outcome.BETTER -> Cyan
        ShadowExperiment.Outcome.WORSE, ShadowExperiment.Outcome.CANDIDATE_FAILED -> Amber
        else -> White.copy(.7f)
    }
    InfoBanner(verdict.outcome().headline() + ". " + verdict.detail(), Modifier.fillMaxWidth(), color)
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth()) {
        HeaderText("Destination", Modifier.weight(1.4f))
        HeaderText("Live", Modifier.weight(1f))
        HeaderText("Candidate", Modifier.weight(1f))
    }
    Divider()
    report.destinations().forEach { destination ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            EllipsisText(destination.host(), White, 12.sp, Modifier.weight(1.4f))
            Text(millis(destination.liveMedian(), destination.liveFailures(), destination.live().size), color = White.copy(.85f), fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            val better = destination.candidateMedian() in 0 until (destination.liveMedian().takeIf { it >= 0 } ?: Long.MAX_VALUE)
            Text(millis(destination.candidateMedian(), destination.candidateFailures(), destination.candidate().size), color = if (better) Cyan else White.copy(.85f),
                fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        }
        Divider()
    }
    Spacer(Modifier.height(8.dp))
    if (result.liveExit.isNotBlank() || result.candidateExit.isNotBlank()) {
        Text("Exit address: live ${result.liveExit.ifBlank { "unknown" }}  ·  candidate ${result.candidateExit.ifBlank { "unknown" }}", color = Muted, fontSize = 11.5.sp)
    }
    if (report.cancelled()) Text("Stopped before the end; the result covers what finished.", color = Amber, fontSize = 11.5.sp)
}

private fun millis(value: Long, failures: Int, total: Int): String =
    (if (value < 0) "—" else "$value ms") + if (failures > 0) "  ($failures/$total failed)" else ""

internal fun showGamingDialog(shell: Shell, targets: List<ProxyNode>) {
    val results = mutableStateOf<Map<String, GamingScore>>(emptyMap())
    val running = mutableStateOf(true)
    val cancelled = AtomicBoolean(false)
    shell.scope.launch {
        try {
            withContext(Dispatchers.IO) {
                shell.controller.gamingTest(targets, { cancelled.get() }) { node, score -> results.value = results.value + (node.key() to score) }
            }
        } finally { running.value = false }
    }
    shell.showDialog(onDismiss = { cancelled.set(true); shell.closeDialog() }) {
        DialogCard("Gaming test", "Steady latency, jitter, lost packets and UDP, measured through each server on a core of its own. About 5 seconds per server.",
            { cancelled.set(true); shell.closeDialog() }, width = 640.dp, actions = {
                if (running.value) SecondaryButton("Stop") { cancelled.set(true) }
                val best = results.value.entries.filter { it.value.score() > 0 }.maxByOrNull { it.value.score() }
                val bestNode = best?.let { entry -> shell.nodes.firstOrNull { it.key() == entry.key } }
                if (!running.value && bestNode != null && bestNode.key() != shell.selectedNodeKey) PrimaryButton("Use ${serverName(bestNode)} as main") {
                    shell.closeDialog(); shell.run { shell.controller.switchNode(bestNode.key()) }
                } else PrimaryButton("Close", enabled = !running.value) { shell.closeDialog() }
            }) {
            Row(Modifier.fillMaxWidth()) {
                HeaderText("Server", Modifier.weight(1.6f))
                HeaderText("Gaming", Modifier.weight(.9f))
                HeaderText("Latency", Modifier.weight(.8f))
                HeaderText("Jitter", Modifier.weight(.7f))
                HeaderText("Loss", Modifier.weight(.7f))
                HeaderText("UDP", Modifier.weight(.6f))
            }
            Divider()
            targets.forEach { node ->
                val score = results.value[node.key()]
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1.6f), verticalAlignment = Alignment.CenterVertically) {
                        ServerAvatar(node, 24.dp); Spacer(Modifier.width(8.dp)); EllipsisText(serverName(node), White, 12.sp, Modifier.weight(1f))
                    }
                    if (score == null) Text(if (running.value) "Waiting…" else "Not tested", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(3.7f))
                    else {
                        Row(Modifier.weight(.9f), verticalAlignment = Alignment.CenterVertically) { GamingBadge(score) }
                        Text(if (score.latencyMillis() < 0) "—" else "${score.latencyMillis()} ms", color = White, fontSize = 12.sp, modifier = Modifier.weight(.8f))
                        Text(if (score.jitterMillis() < 0) "—" else "${score.jitterMillis()} ms", color = White, fontSize = 12.sp, modifier = Modifier.weight(.7f))
                        Text(String.format(java.util.Locale.ROOT, "%.0f%%", score.lossPercent()), color = if (score.lossPercent() > 0) Amber else White, fontSize = 12.sp, modifier = Modifier.weight(.7f))
                        Text(if (score.udpSupported()) "Yes" else "No", color = if (score.udpSupported()) Cyan else Amber, fontSize = 12.sp, modifier = Modifier.weight(.6f))
                    }
                }
                score?.note()?.takeIf { it.isNotBlank() && score.score() < 70 }?.let { Text(it, color = Muted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(start = 32.dp, bottom = 4.dp)) }
                Divider()
            }
            Spacer(Modifier.height(8.dp))
            DialogText("UDP is measured with name lookups sent to a public resolver through the server, which is what most game traffic looks like to it. The score favours steadiness over the best single result.", Muted)
        }
    }
}

@Composable
internal fun GamingBadge(score: GamingScore) {
    val color = when {
        score.score() >= 85 -> Cyan
        score.score() >= 70 -> Cyan.copy(.8f)
        score.score() >= 50 -> Amber
        else -> Red
    }
    Badge(if (score.score() > 0) "${score.score()} ${score.label()}" else score.label(), color, icon = Icons.Rounded.SportsEsports)
}

@Composable
internal fun GamingSummary(shell: Shell, node: ProxyNode) {
    val score = shell.controller.gamingScore(node.key())
    PanelSection("For games") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (score == null) Text("Not tested for games yet.", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            else {
                GamingBadge(score)
                Spacer(Modifier.width(10.dp))
                Text(listOfNotNull(score.latencyMillis().takeIf { it >= 0 }?.let { "$it ms" }, score.jitterMillis().takeIf { it >= 0 }?.let { "jitter $it ms" },
                    String.format(java.util.Locale.ROOT, "%.0f%% lost", score.lossPercent()), if (score.udpSupported()) "UDP" else "no UDP",
                    formatAgo(score.measuredAt())).joinToString("  ·  "), color = Muted, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
            }
            LinkAction(if (score == null) "Test" else "Test again", arrow = false) { showGamingDialog(shell, listOf(node)) }
        }
        if (score != null && score.note().isNotBlank() && score.score() < 70) Text(score.note(), color = Muted, fontSize = 11.5.sp, lineHeight = 16.sp)
    }
}

internal fun gamingCandidates(shell: Shell): List<ProxyNode> {
    val scores = shell.controller.qualityScores()
    val favorites = shell.nodes.filter { shell.controller.isFavorite(it.key()) }.mapTo(hashSetOf()) { it.key() }
    return shell.nodes.sortedWith(compareByDescending<ProxyNode> { it.key() == shell.selectedNodeKey }
        .thenByDescending { it.key() in favorites }
        .thenByDescending { scores[it.key()]?.score() ?: -1 }).take(5)
}

internal fun showSubscriptionDialog(shell: Shell, url: String) {
    shell.showDialog {
        val insight = shell.controller.subscriptionInsight(url)
        val report = shell.controller.subscriptionHealth(url)
        DialogCard(insight?.title()?.ifBlank { null } ?: PerRouteController.sourceLabel(url), "Subscription details. The link itself is never stored outside PerRoute's encrypted settings.",
            shell::closeDialog, width = 560.dp, actions = {
                SecondaryButton("Close") { shell.closeDialog() }
                PrimaryButton("Update now", enabled = !shell.locked && !shell.busy) { shell.closeDialog(); shell.run { shell.controller.fetchSubscription(url) } }
            }) {
            InfoBanner(report.status(), Modifier.fillMaxWidth(), when (report.severity()) {
                SubscriptionHealth.Severity.PROBLEM -> Red
                SubscriptionHealth.Severity.WARNING -> Amber
                SubscriptionHealth.Severity.OK -> Cyan
                else -> Muted
            })
            Spacer(Modifier.height(10.dp))
            if (insight != null) {
                val info = insight.userInfo()
                if (info.hasQuota()) {
                    Text("Data: ${SubscriptionHealth.bytes(info.usedBytes())} of ${SubscriptionHealth.bytes(info.totalBytes())}", color = White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    ProgressBar(info.usedShare().toFloat(), if (info.usedShare() >= .9) Amber else Violet, Modifier.fillMaxWidth(), 6.dp)
                    Spacer(Modifier.height(8.dp))
                } else if (info.known()) DetailLine("Data", "Unlimited or not reported")
                DetailLine("Expires", when {
                    info.hasExpiry() -> formatSnapshotTime(info.expireAtMillis()) + if (info.expireAtMillis() < System.currentTimeMillis()) " (expired)" else ""
                    info.known() -> "Never, or not reported"
                    else -> "Not reported by the provider"
                })
                DetailLine("Servers", insight.serverCount().toString())
                DetailLine("Last update", if (insight.lastSuccessAt() > 0) formatAgo(insight.lastSuccessAt()) else "Never")
                if (insight.updateIntervalHours() > 0) DetailLine("Provider suggests", "Updating every ${plural(insight.updateIntervalHours(), "hour")}")
                if (insight.history().isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Recent updates", color = White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    insight.history().reversed().take(5).forEach { change ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(formatSnapshotTime(change.at()), color = Muted, fontSize = 11.5.sp, modifier = Modifier.width(110.dp))
                            Text(change.summary() + "  (${change.after()} servers)", color = if (change.unexpected()) Amber else White.copy(.85f), fontSize = 11.5.sp)
                        }
                    }
                }
            }
            if (report.findings().isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                report.findings().forEach { finding ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        val status = when (finding.severity()) {
                            SubscriptionHealth.Severity.PROBLEM -> CheckStatus.FAIL
                            SubscriptionHealth.Severity.WARNING -> CheckStatus.WARN
                            SubscriptionHealth.Severity.OK -> CheckStatus.PASS
                            else -> CheckStatus.INFO
                        }
                        Icon(statusIcon(status), null, tint = statusColor(status), modifier = Modifier.size(16.dp).padding(top = 2.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(finding.title(), color = White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(finding.detail(), color = Muted, fontSize = 11.5.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SubscriptionAlert(shell: Shell, url: String) {
    val report = shell.controller.subscriptionHealth(url)
    if (report.severity() < SubscriptionHealth.Severity.WARNING) return
    Badge(report.status(), if (report.severity() == SubscriptionHealth.Severity.PROBLEM) Red else Amber)
}
