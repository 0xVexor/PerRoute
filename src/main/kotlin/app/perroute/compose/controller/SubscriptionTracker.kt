package app.perroute.compose.controller

import app.perroute.model.AppState
import app.perroute.model.ProxyNode
import app.perroute.service.SubscriptionService
import app.perroute.subscription.SubscriptionChange
import app.perroute.subscription.SubscriptionHealth
import app.perroute.subscription.SubscriptionInsight
import app.perroute.subscription.SubscriptionInsightStore
import java.util.Locale

/**
 * Remembers how each subscription behaved on every update (what changed, what it reports about itself, whether it
 * failed) and turns that into a health report. Recording is best effort and never makes an update fail.
 */
class SubscriptionTracker(
    private val state: AppState,
    private val store: SubscriptionInsightStore,
    private val log: (String) -> Unit,
    private val label: (String) -> String,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun insight(url: String): SubscriptionInsight? = store.get(url)

    fun health(url: String): SubscriptionHealth.Report = SubscriptionHealth.analyze(store.get(url), clock())

    /** How many of these subscriptions need the user's attention. */
    fun attention(urls: List<String>): Int = urls.count { health(it).severity() >= SubscriptionHealth.Severity.WARNING }

    /** The servers a subscription currently provides, to compare against what its next update brings. */
    fun serversOf(url: String): List<ProxyNode> = synchronized(state) {
        state.nodes().filter { node -> state.nodeSources()[node.key()].equals(url, ignoreCase = true) }
    }

    fun recordSuccess(url: String, fetched: SubscriptionService.Fetched, previous: List<ProxyNode>) {
        runCatching {
            val now = clock()
            val incoming = fetched.result().nodes()
            val identities = incoming.map { it.identity() }
            val within = identities.size - identities.toSet().size
            val (elsewhere, shared) = synchronized(state) {
                val others = state.nodes().filter { node -> !state.nodeSources()[node.key()].equals(url, ignoreCase = true) }
                val otherIdentities = others.mapTo(hashSetOf()) { it.identity() }
                val otherEndpoints = others.mapTo(hashSetOf()) { endpoint(it) }
                identities.toSet().count { it in otherIdentities } to
                    incoming.distinctBy { it.identity() }.count { it.identity() !in otherIdentities && endpoint(it) in otherEndpoints }
            }
            val change = if (previous.isEmpty() && (store.get(url)?.lastSuccessAt() ?: 0L) == 0L) null
                else SubscriptionChange.between(previous.map(::entry), incoming.distinctBy { it.identity() }.map(::entry), now)
            val current = store.get(url) ?: SubscriptionInsight.empty(now)
            store.put(url, current.succeeded(now, fetched.title(), fetched.userInfo(), fetched.updateIntervalHours(),
                incoming.distinctBy { it.identity() }.size, within, elsewhere, shared, change))
            if (change?.unexpected() == true) log("${label(url)} changed a lot in this update (${change.summary()}). Check that the link still belongs to your provider.")
            fetched.userInfo().takeIf { it.hasExpiry() && it.expireAtMillis() <= now }?.let { log("${label(url)} reports that it has expired.") }
        }
    }

    fun recordFailure(url: String, error: String) {
        runCatching {
            val now = clock()
            store.put(url, (store.get(url) ?: SubscriptionInsight.empty(now)).failed(now, error))
        }
    }

    fun forget(url: String) { runCatching { store.remove(url) } }

    fun forgetAll() { runCatching { store.clear() } }

    private fun endpoint(node: ProxyNode) = "${node.address().lowercase(Locale.ROOT)}:${node.port()}"

    private fun entry(node: ProxyNode) = SubscriptionChange.Entry(node.identity(), PerRouteController.cleanName(node.name()))
}
