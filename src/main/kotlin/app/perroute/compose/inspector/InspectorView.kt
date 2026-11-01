package app.perroute.compose.inspector

import app.perroute.compose.*
import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import app.perroute.compose.controller.ActiveRoute
import app.perroute.compose.controller.PerRouteController
import app.perroute.compose.controller.RouteCheck
import app.perroute.compose.controller.RouteCheckState

import app.perroute.inspector.ConnectionInspector
import app.perroute.model.RouteRule
import app.perroute.service.RouteRuleEvaluator
import app.perroute.service.RoutePlanner
import app.perroute.service.XrayConfigGenerator

enum class RouteKind { PROXY, DIRECT, BLOCK, UNKNOWN }

data class ConfiguredFact(val routeLabel: String, val kind: RouteKind, val ruleLabel: String, val ruleSource: String, val certain: Boolean)

data class ObservedFact(val routeLabel: String?, val kind: RouteKind, val outboundTag: String?, val ruleLabel: String?, val sniffedDomain: String?)

data class VerifiedFact(val routeLabel: String, val passed: Boolean, val publicIp: String?, val country: String?, val atMillis: Long, val detail: String)

data class InspectorRow(
    val id: Long,
    val applicationPath: String,
    val protocol: String,
    val destinationHost: String,
    val destinationPort: Int,
    val udpDestinations: Int,
    val status: String,
    val failure: String,
    val openedAt: Long,
    val lastActivityAt: Long,
    val closedAt: Long,
    val downloadBytes: Long,
    val uploadBytes: Long,
    val downloadRate: Long,
    val uploadRate: Long,
    val viaGateway: Boolean,
    val configured: ConfiguredFact,
    val observed: ObservedFact,
    val verified: VerifiedFact?
) {
    val destination: String get() = if (destinationHost.isBlank()) "Unknown" else "$destinationHost:$destinationPort"
}

internal class InspectorContext(
    val applications: List<String>,
    val routes: List<ActiveRoute>,
    val routeTags: Map<String, String>,
    val rules: List<RouteRule>,
    val plan: RoutePlanner.Plan,
    val checks: Map<String, RouteCheck>,
    val nameOf: (app.perroute.model.ProxyNode) -> String
)

internal object InspectorView {
    fun rows(observations: List<ConnectionInspector.Observation>, context: InspectorContext): List<InspectorRow> =
        observations.mapNotNull { observation ->
            val app = observation.applicationPath() ?: applicationOfInbound(observation.inboundTag(), context.applications) ?: return@mapNotNull null
            val observedRoute = describeOutbound(observation.observedOutbound(), context)
            val observed = ObservedFact(
                routeLabel = observedRoute?.first,
                kind = observedRoute?.second ?: RouteKind.UNKNOWN,
                outboundTag = observation.observedOutbound().ifBlank { null },
                ruleLabel = if (observation.ruleObserved()) ruleLabel(observation.observedRuleTag(), context.rules) else null,
                sniffedDomain = observation.sniffedDomain().ifBlank { null }
            )
            val configured = configured(app, observation.sniffedDomain().ifBlank { observation.destinationHost() }, observation.destinationPort(), context)
            val verifiedRoute = when (observed.kind.takeIf { it != RouteKind.UNKNOWN } ?: configured.kind) {
                RouteKind.DIRECT -> PerRouteController.DIRECT_ROUTE_KEY to "Direct route"
                RouteKind.PROXY -> {
                    val route = observedRoute?.third ?: context.plan.routeFor(app)?.let { planned -> context.routes.firstOrNull { it.id == planned.id() } }
                    route?.let { it.id to context.nameOf(it.node) }
                }
                else -> null
            }
            val verified = verifiedRoute?.let { (id, label) ->
                context.checks[id]?.takeIf { it.state == RouteCheckState.PASSED || it.state == RouteCheckState.FAILED }?.let { check ->
                    VerifiedFact(label, check.state == RouteCheckState.PASSED, check.publicIp, check.country, check.atMillis, check.detail)
                }
            }
            InspectorRow(observation.id(), app, observation.protocol() ?: "", observation.destinationHost(), observation.destinationPort(),
                observation.udpDestinations(), observation.status(), observation.failure(), observation.openedAt(), observation.lastActivityAt(),
                observation.closedAt(), observation.downloadBytes(), observation.uploadBytes(), observation.downloadRate(), observation.uploadRate(),
                observation.viaGateway(), configured, observed, verified)
        }

    fun applicationOfInbound(inboundTag: String?, applications: List<String>): String? {
        val index = inboundTag?.removePrefix("perroute-app-")?.takeIf { it != inboundTag }?.toIntOrNull() ?: return null
        return applications.getOrNull(index)
    }

    fun describeOutbound(tag: String?, context: InspectorContext): Triple<String, RouteKind, ActiveRoute?>? {
        if (tag.isNullOrBlank()) return null
        if (tag == "direct" || tag.endsWith("perroute-direct") || tag.startsWith("perroute-direct")) return Triple("Direct", RouteKind.DIRECT, null)
        if (tag == "block" || tag.startsWith("perroute-block")) return Triple("Blocked", RouteKind.BLOCK, null)
        context.routeTags.forEach { (routeId, routeTag) ->
            if (tag == routeTag || tag.startsWith("$routeTag/")) {
                val route = context.routes.firstOrNull { it.id == routeId } ?: return@forEach
                return Triple(context.nameOf(route.node), RouteKind.PROXY, route)
            }
        }
        val custom = Regex("^(?:~|zz\\.)?perroute-route-(\\d+)/").find(tag)?.groupValues?.get(1)?.toIntOrNull()
        if (custom != null) {
            val route = context.routes.filter { !it.main }.getOrNull(custom)
            if (route != null) return Triple(context.nameOf(route.node), RouteKind.PROXY, route)
        }
        val main = context.routes.firstOrNull { it.main } ?: return Triple(tag, RouteKind.UNKNOWN, null)
        return Triple(context.nameOf(main.node), RouteKind.PROXY, main)
    }

    fun ruleLabel(ruleTag: String?, rules: List<RouteRule>): String {
        if (ruleTag.isNullOrBlank()) return "No rule (app's default route)"
        XrayConfigGenerator.customRuleId(ruleTag)?.let { id ->
            return rules.firstOrNull { it.id() == id }?.let { PerRouteController.ruleDisplayName(it) } ?: "A rule that was removed"
        }
        return when {
            ruleTag == "perroute-private-direct" -> "Private and LAN addresses (built in)"
            ruleTag.startsWith("perroute-app-") -> "No rule (app's default route)"
            else -> "Server profile rule ($ruleTag)"
        }
    }

    private fun configured(app: String, destination: String, port: Int, context: InspectorContext): ConfiguredFact {
        val verdict = RouteRuleEvaluator.evaluate(context.rules, context.applications, app, destination, port)
        val node = context.plan.nodeFor(app)
        val ruleLabel = verdict.rule()?.let { PerRouteController.ruleDisplayName(it) }
            ?: if (verdict.reason().startsWith("Private")) "Private and LAN addresses (built in)" else "No rule (app's default route)"
        val source = when {
            verdict.rule() == null -> "Built in"
            PerRouteController.isScenarioRule(verdict.rule()!!) -> "Preset rule"
            verdict.rule()!!.applicationPath().isBlank() -> "Your rule (all apps)"
            else -> "Your rule (this app)"
        }
        return when (verdict.action()) {
            RouteRule.Action.DIRECT -> ConfiguredFact("Direct", RouteKind.DIRECT, ruleLabel, source, verdict.certain())
            RouteRule.Action.BLOCK -> ConfiguredFact("Blocked", RouteKind.BLOCK, ruleLabel, source, verdict.certain())
            else -> ConfiguredFact(node?.let(context.nameOf) ?: "Not assigned", if (node == null) RouteKind.UNKNOWN else RouteKind.PROXY,
                ruleLabel, source, verdict.certain())
        }
    }
}
