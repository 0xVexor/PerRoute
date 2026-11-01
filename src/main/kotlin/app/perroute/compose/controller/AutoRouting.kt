package app.perroute.compose.controller

import app.perroute.compose.*
import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import app.perroute.compose.ui.components.serverName

import app.perroute.model.ProxyNode
import app.perroute.routing.AutoCandidatePool
import app.perroute.routing.AutoSelection
import app.perroute.routing.AutoServerSelector
import app.perroute.runtime.QualityService
import java.util.concurrent.ConcurrentHashMap

data class AutoDecisionView(val decision: AutoServerSelector.Decision, val atMillis: Long, val applied: Boolean, val note: String = "")

internal class AutoRouting(private val quality: QualityService, private val clock: () -> Long = System::currentTimeMillis) {
    private val decisions = ConcurrentHashMap<String, AutoDecisionView>()

    fun decide(
        pool: AutoCandidatePool,
        current: AutoSelection?,
        nodes: List<ProxyNode>,
        favorites: Set<String>,
        tags: Map<String, String>,
        main: ProxyNode?,
        failingNodeKeys: Set<String>,
        policy: AutoServerSelector.Policy
    ): AutoServerSelector.Decision {
        val candidates = nodes
            .filter { pool.includes(it.key(), it.key() in favorites, tags[it.key()]) }
            .map { node ->
                AutoServerSelector.Candidate(node.key(), serverName(node), quality.score(node.key()), node.key() in failingNodeKeys)
            }
        val decision = AutoServerSelector.decide(
            candidates,
            current?.takeIf { it.nodeKey().isNotBlank() }?.let { AutoServerSelector.Current(it.nodeKey(), it.changedAt()) },
            main?.key().orEmpty(), main?.let { serverName(it) }.orEmpty(),
            policy, clock()
        )
        return decision
    }

    fun remember(app: String, decision: AutoServerSelector.Decision, applied: Boolean, note: String = "") {
        decisions[app] = AutoDecisionView(decision, clock(), applied, note)
    }

    fun last(app: String): AutoDecisionView? = decisions[app]

    fun forget(apps: Collection<String>) { decisions.keys.retainAll(apps.toSet()) }

    fun clear() = decisions.clear()
}
