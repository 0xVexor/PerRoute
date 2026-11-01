package app.perroute.platform.macos.proxy;

import java.math.BigDecimal;
import java.util.*;
import static app.perroute.platform.macos.proxy.NetworkServiceInventory.*;

/**
 * Diagnostic comparison of a restricted manual-proxy schema with a published interface scope.
 * A match is observational, not an atomic read-back, traffic proof or permission to write settings.
 * Keep this separate from SystemProxySettings.State, which requires a stronger native contract.
 */
public final class EffectiveProxyProjection {
    public enum Outcome {
        MATCH, DIFFERENT, PREFERENCES_UNSTABLE, INELIGIBLE_SERVICE, UNVERIFIED_INTERFACE,
        AMBIGUOUS_INTERFACE, SETUP_UNAVAILABLE, SETUP_DIFFERENT, RUNTIME_UNSUPPORTED,
        GLOBAL_UNAVAILABLE, CONTEXT_UNSUPPORTED, SCOPE_MISSING, SETTINGS_UNSUPPORTED
    }
    public record Result(String serviceId, Outcome outcome, Optional<ProxySettings> configured,
                         Optional<ProxySettings> scoped) {
        public Result {
            Objects.requireNonNull(serviceId); Objects.requireNonNull(outcome);
            Objects.requireNonNull(configured); Objects.requireNonNull(scoped);
            boolean comparable = outcome == Outcome.MATCH || outcome == Outcome.DIFFERENT;
            if (comparable != configured.isPresent() || comparable != scoped.isPresent())
                throw new IllegalArgumentException("Projection values require a comparable outcome");
        }
        @Override public String toString() { return "EffectiveProxyProjection[" + outcome + ", redacted]"; }
    }

    public List<Result> project(NetworkServiceInventory inventory) {
        Objects.requireNonNull(inventory);
        return project(inventory, true);
    }

    List<Result> project(NetworkServiceInventory inventory, boolean requireSetupAgreement) {
        return inventory.services().stream().map(service -> project(inventory, service, requireSetupAgreement)).toList();
    }

    private Result project(NetworkServiceInventory inventory, Service service, boolean requireSetupAgreement) {
        if (inventory.preferences() != Consistency.UNCHANGED) return refused(service, Outcome.PREFERENCES_UNSTABLE);
        if (service.scope() != Scope.CANDIDATE) return refused(service, Outcome.INELIGIBLE_SERVICE);
        String bsd = service.interfaces().getFirst().bsdName();
        if (!service.activeInterfaces().equals(List.of(bsd))) return refused(service, Outcome.UNVERIFIED_INTERFACE);
        if (inventory.services().stream().filter(other -> other.enabled() && other.currentSet() && other.activeIp())
                .anyMatch(other -> other.activeInterfaces().isEmpty() || other.activeInterfaces().stream().anyMatch(String::isBlank)))
            return refused(service, Outcome.UNVERIFIED_INTERFACE);
        // Count potential competitors even when their proxy protocol or interface type is unsupported.
        // configd selects one service per interface in service order, which this inventory does not capture.
        long competitors = inventory.services().stream().filter(other -> other.enabled() && other.currentSet() && other.activeIp())
                .filter(other -> other.activeInterfaces().contains(bsd)
                        || other.interfaces().stream().anyMatch(layer -> layer.bsdName().equals(bsd))).count();
        if (competitors != 1) return refused(service, Outcome.AMBIGUOUS_INTERFACE);
        if (service.publishedSetup().status() != Status.PRESENT) return refused(service, Outcome.SETUP_UNAVAILABLE);
        if (requireSetupAgreement && !service.configured().settings().equals(service.publishedSetup().settings())) return refused(service, Outcome.SETUP_DIFFERENT);
        // State can override Setup. A nonempty override needs a separate merge/schema proof.
        if (service.runtimeState().status() != Status.ABSENT
                && (service.runtimeState().status() != Status.PRESENT || !service.runtimeState().settings().orElseThrow().values().isEmpty()))
            return refused(service, Outcome.RUNTIME_UNSUPPORTED);
        if (inventory.globalProxies().status() != Status.PRESENT) return refused(service, Outcome.GLOBAL_UNAVAILABLE);
        var global = inventory.globalProxies().settings().orElseThrow().values();
        // __SERVICES__ is app-layer VPN context, not a stable network-service-ID lookup.
        // Supplemental/domain and unknown context may change selection for a particular request.
        if (global.keySet().stream().anyMatch(key -> !ManualProxySemantics.KEYS.contains(key) && !key.equals("__SCOPED__")))
            return refused(service, Outcome.CONTEXT_UNSUPPORTED);
        Object scopes = global.get("__SCOPED__");
        if (scopes == null) return refused(service, Outcome.SCOPE_MISSING);
        if (!(scopes instanceof Map<?, ?> map)) return refused(service, Outcome.SETTINGS_UNSUPPORTED);
        Object selected = map.get(bsd);
        if (selected == null) return refused(service, Outcome.SCOPE_MISSING);
        if (!(selected instanceof Map<?, ?> dictionary)) return refused(service, Outcome.SETTINGS_UNSUPPORTED);
        try {
            var values = new LinkedHashMap<String, Object>();
            for (var entry : dictionary.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException();
                values.put(key, entry.getValue());
            }
            var configured = ManualProxySemantics.normalize(service.configured().settings().orElseThrow());
            var scoped = ManualProxySemantics.normalize(new ProxySettings(values));
            return new Result(service.id(), configured.equals(scoped) ? Outcome.MATCH : Outcome.DIFFERENT,
                    Optional.of(configured), Optional.of(scoped));
        } catch (IllegalArgumentException | ArithmeticException unsupported) { return refused(service, Outcome.SETTINGS_UNSUPPORTED); }
    }

    private static Result refused(Service service, Outcome outcome) {
        return new Result(service.id(), outcome, Optional.empty(), Optional.empty());
    }


}
