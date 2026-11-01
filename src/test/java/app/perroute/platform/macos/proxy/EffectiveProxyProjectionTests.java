package app.perroute.platform.macos.proxy;

import java.util.*;
import static app.perroute.platform.macos.proxy.NetworkServiceInventory.*;
import static app.perroute.platform.macos.proxy.EffectiveProxyProjection.Outcome.*;

public final class EffectiveProxyProjectionTests {
    private static int assertions;
    private static final Observation EMPTY = present(Map.of());
    private static final Observation ABSENT = Observation.unavailable(Status.ABSENT);
    public static void main(String[] args) {
        var empty = service("one", "en0", List.of("en0"), EMPTY, EMPTY, ABSENT);
        outcome(empty, Map.of(), MATCH);
        outcome(empty, Map.of("HTTPEnable", 0, "HTTPSEnable", 0, "SOCKSEnable", 0,
                "ProxyAutoConfigEnable", 0, "ProxyAutoDiscoveryEnable", 0, "ExcludeSimpleHostnames", 0, "ExceptionsList", List.of()), MATCH);
        var stale = present(Map.of("HTTPEnable", 0, "HTTPProxy", "secret.example", "HTTPPort", 8080));
        outcome(service("one", "en0", List.of("en0"), stale, stale, ABSENT), Map.of(), MATCH);
        for (String protocol : List.of("HTTP", "HTTPS", "SOCKS")) {
            var settings = Map.<String, Object>of(protocol + "Enable", 1, protocol + "Proxy", "127.0.0.1", protocol + "Port", 18080);
            var manual = service("one", "en0", List.of("en0"), present(settings), present(settings), ABSENT);
            outcome(manual, settings, MATCH);
            outcome(manual, Map.of(), DIFFERENT);
            outcome(manual, Map.of(protocol + "Enable", 1, protocol + "Proxy", "other.example", protocol + "Port", 18080), DIFFERENT);
            outcome(manual, Map.of(protocol + "Enable", 1, protocol + "Proxy", "127.0.0.1", protocol + "Port", 18081), DIFFERENT);
            for (Object port : List.of(0, -1, 65536, 1.5, new java.math.BigDecimal("99999999999999999999999"), "8080"))
                outcome(empty, Map.of(protocol + "Enable", 1, protocol + "Proxy", "host", protocol + "Port", port), SETTINGS_UNSUPPORTED);
            outcome(empty, Map.of(protocol + "Enable", 1, protocol + "Proxy", "host"), SETTINGS_UNSUPPORTED);
            outcome(empty, Map.of(protocol + "Enable", 1, protocol + "Port", 80), SETTINGS_UNSUPPORTED);
            for (Object flag : List.of(true, "1", 2, -1, 0.5))
                outcome(empty, Map.of(protocol + "Enable", flag), SETTINGS_UNSUPPORTED);
        }
        for (var unsupported : List.<Map<String, Object>>of(
                Map.of("ProxyAutoConfigEnable", 1), Map.of("ProxyAutoDiscoveryEnable", 1),
                Map.of("ProxyAutoConfigURLString", "https://secret.example/pac"),
                Map.of("SOCKSUser", "private-user"), Map.of("Unknown", 0), Map.of("FTPEnable", 0),
                Map.of("ExceptionsList", List.of(1)), Map.of("ExceptionsList", List.of("")),
                Map.of("ExcludeSimpleHostnames", true))) {
            outcome(empty, unsupported, SETTINGS_UNSUPPORTED);
            var observation = present(unsupported);
            outcome(service("one", "en0", List.of("en0"), observation, observation, ABSENT), Map.of(), SETTINGS_UNSUPPORTED);
        }
        outcome(empty, Map.of("ExceptionsList", List.of("localhost", "*.example")), DIFFERENT);
        outcome(empty, Map.of("ExcludeSimpleHostnames", 1), DIFFERENT);
        outcome(empty, Map.of("FTPPassive", 1), DIFFERENT);
        var bypass = present(Map.of("ExceptionsList", List.of("localhost", "*.example")));
        outcome(service("one", "en0", List.of("en0"), bypass, bypass, ABSENT),
                Map.of("ExceptionsList", List.of("*.example", "localhost")), DIFFERENT);
        var mutableInterfaces = new ArrayList<>(List.of("en0"));
        var copied = service("one", "en0", mutableInterfaces, EMPTY, EMPTY, ABSENT);
        mutableInterfaces.clear();
        check(copied.activeInterfaces().equals(List.of("en0")), "Active interface provenance is copied");
        check(stale.settings().orElseThrow().values().containsKey("HTTPProxy"), "Normalization preserves original dormant endpoint for recovery");
        outcome(service("one", "en0", List.of(), EMPTY, EMPTY, ABSENT), Map.of(), UNVERIFIED_INTERFACE);
        outcome(service("one", "en0", List.of("en1"), EMPTY, EMPTY, ABSENT), Map.of(), UNVERIFIED_INTERFACE);
        outcome(service("one", "en0", List.of("en0", "en1"), EMPTY, EMPTY, ABSENT), Map.of(), UNVERIFIED_INTERFACE);
        outcome(service("one", "en0", List.of("en0"), EMPTY, ABSENT, ABSENT), Map.of(), SETUP_UNAVAILABLE);
        outcome(service("one", "en0", List.of("en0"), EMPTY, present(Map.of("HTTPEnable", 0)), ABSENT), Map.of(), SETUP_DIFFERENT);
        for (var observation : List.of(present(Map.of("HTTPEnable", 0)), Observation.unavailable(Status.UNREADABLE), Observation.unavailable(Status.UNSUPPORTED)))
            outcome(service("one", "en0", List.of("en0"), EMPTY, EMPTY, observation), Map.of(), RUNTIME_UNSUPPORTED);
        outcome(service("one", "en0", List.of("en0"), EMPTY, EMPTY, EMPTY), Map.of(), MATCH);
        var global = scoped(Map.of());
        for (Consistency consistency : List.of(Consistency.CHANGED, Consistency.UNKNOWN))
            check(project(List.of(empty), global, consistency).getFirst().outcome() == PREFERENCES_UNSTABLE, "Unstable preferences refused");
        var duplicate = service("two", "en0", List.of("en0"), EMPTY, EMPTY, ABSENT);
        check(project(List.of(empty, duplicate), global, Consistency.UNCHANGED).getFirst().outcome() == AMBIGUOUS_INTERFACE, "Same interface, distinct stable IDs refused");
        var disabledProtocol = new Service("two", "private", duplicate.interfaces(), true, true, true, false, EMPTY, EMPTY, ABSENT, List.of("en0"));
        check(project(List.of(empty, disabledProtocol), global, Consistency.UNCHANGED).getFirst().outcome() == AMBIGUOUS_INTERFACE, "Unsupported competitor cannot be filtered away");
        var inactiveCompetitor = new Service("two", "private", duplicate.interfaces(), true, true, false, true, EMPTY, EMPTY, ABSENT, List.of());
        check(project(List.of(empty, inactiveCompetitor), global, Consistency.UNCHANGED).getFirst().outcome() == MATCH, "Inactive service does not compete");
        var other = service("two", "en1", List.of("en1"), EMPTY, EMPTY, ABSENT);
        check(project(List.of(empty, other), global, Consistency.UNCHANGED).getFirst().outcome() == MATCH, "Different active interface does not collide");
        check(project(List.of(empty, service("two", "en1", List.of(""), EMPTY, EMPTY, ABSENT)), global, Consistency.UNCHANGED).getFirst().outcome() == UNVERIFIED_INTERFACE, "Unknown active competitor refused");
        for (Status status : List.of(Status.ABSENT, Status.UNREADABLE, Status.UNSUPPORTED))
            check(project(List.of(empty), Observation.unavailable(status), Consistency.UNCHANGED).getFirst().outcome() == GLOBAL_UNAVAILABLE, "Unavailable global is never empty");
        for (var aggregate : List.<Map<String, Object>>of(Map.of("__SERVICES__", Map.of()), Map.of("__SUPPLEMENTAL__", List.of()), Map.of("FutureContext", 0)))
            check(project(List.of(empty), present(aggregate), Consistency.UNCHANGED).getFirst().outcome() == CONTEXT_UNSUPPORTED, "Context selection refused");
        for (var aggregate : List.<Map<String, Object>>of(Map.of(), Map.of("__SCOPED__", Map.of("en1", Map.of()))))
            check(project(List.of(empty), present(aggregate), Consistency.UNCHANGED).getFirst().outcome() == SCOPE_MISSING, "Missing scope is never direct connection");
        for (var aggregate : List.<Map<String, Object>>of(Map.of("__SCOPED__", List.of()), Map.of("__SCOPED__", Map.of("en0", "wrong"))))
            check(project(List.of(empty), present(aggregate), Consistency.UNCHANGED).getFirst().outcome() == SETTINGS_UNSUPPORTED, "Malformed scope refused");
        var inactive = new Service("one", "secret name", empty.interfaces(), false, true, true, true, EMPTY, EMPTY, ABSENT, List.of("en0"));
        check(project(List.of(inactive), global, Consistency.UNCHANGED).getFirst().outcome() == INELIGIBLE_SERVICE, "Ineligible service refused");
        var result = project(List.of(empty), global, Consistency.UNCHANGED).getFirst();
        check(!result.toString().contains("one") && !result.toString().contains("en0"), "Report redacts identifiers");
        try { result.scoped().orElseThrow().values().put("HTTPEnable", 1); throw new AssertionError("Mutable projection"); }
        catch (UnsupportedOperationException expected) { check(true, "Projection is immutable"); }
        try { new EffectiveProxyProjection.Result("one", MATCH, Optional.empty(), Optional.empty()); throw new AssertionError("Invalid result"); }
        catch (IllegalArgumentException expected) { check(true, "Result invariant enforced"); }
        System.out.println("Effective proxy projection checks passed (" + assertions + " assertions)");
    }
    private static void outcome(Service service, Map<String, Object> values, EffectiveProxyProjection.Outcome expected) {
        var result = project(List.of(service), scoped(values), Consistency.UNCHANGED).getFirst();
        check(result.outcome() == expected, "Expected " + expected + ", got " + result.outcome());
        check(result.configured().isPresent() == (expected == MATCH || expected == DIFFERENT), "Refusal cannot expose a comparable value");
    }
    private static List<EffectiveProxyProjection.Result> project(List<Service> services, Observation global, Consistency consistency) {
        return new EffectiveProxyProjection().project(new NetworkServiceInventory(services, global, consistency));
    }
    private static Observation scoped(Map<String, Object> values) { return present(Map.of("__SCOPED__", Map.of("en0", values))); }
    private static Observation present(Map<String, Object> values) { return Observation.present(new ProxySettings(values)); }
    private static Service service(String id, String bsd, List<String> active, Observation configured, Observation setup, Observation runtime) {
        return new Service(id, "same private name", List.of(new InterfaceLayer("Ethernet", bsd)), true, true, true, true, configured, setup, runtime, active);
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}
