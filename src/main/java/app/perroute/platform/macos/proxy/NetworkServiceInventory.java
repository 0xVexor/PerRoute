package app.perroute.platform.macos.proxy;

import java.util.*;

/** Read-only observations, deliberately separate from the authorized settings mutation port. */
public record NetworkServiceInventory(List<Service> services, Observation globalProxies, Consistency preferences) {
    public NetworkServiceInventory {
        services = List.copyOf(services);
        Objects.requireNonNull(globalProxies); Objects.requireNonNull(preferences);
        if (services.stream().map(Service::id).distinct().count() != services.size())
            throw new IllegalArgumentException("Duplicate network-service IDs");
    }
    public enum Consistency { UNCHANGED, CHANGED, UNKNOWN }
    public enum Status { PRESENT, ABSENT, UNREADABLE, UNSUPPORTED }
    public enum Agreement { MATCH, DIFFERENT, UNAVAILABLE }
    public enum Scope { CANDIDATE, DISABLED, OUTSIDE_CURRENT_SET, NO_ACTIVE_IP, OTHER_INTERFACE, PROXIES_UNAVAILABLE }
    public record Observation(Status status, Optional<ProxySettings> settings) {
        public Observation {
            Objects.requireNonNull(status); Objects.requireNonNull(settings);
            if ((status == Status.PRESENT) != settings.isPresent()) throw new IllegalArgumentException("Invalid proxy observation");
        }
        public static Observation present(ProxySettings settings) { return new Observation(Status.PRESENT, Optional.of(settings)); }
        public static Observation unavailable(Status status) { return new Observation(status, Optional.empty()); }
    }
    public record InterfaceLayer(String type, String bsdName) {
        public InterfaceLayer { Objects.requireNonNull(type); Objects.requireNonNull(bsdName); }
    }
    public record Service(String id, String name, List<InterfaceLayer> interfaces, boolean enabled,
                          boolean currentSet, boolean activeIp, boolean proxyProtocolEnabled,
                          Observation configured, Observation publishedSetup, Observation runtimeState,
                          List<String> activeInterfaces) {
        public Service {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Stable service ID required");
            Objects.requireNonNull(name);
            interfaces = List.copyOf(interfaces);
            activeInterfaces = List.copyOf(activeInterfaces);
            Objects.requireNonNull(configured); Objects.requireNonNull(publishedSetup); Objects.requireNonNull(runtimeState);
        }
        public Service(String id, String name, List<InterfaceLayer> interfaces, boolean enabled,
                       boolean currentSet, boolean activeIp, boolean proxyProtocolEnabled,
                       Observation configured, Observation publishedSetup, Observation runtimeState) {
            this(id, name, interfaces, enabled, currentSet, activeIp, proxyProtocolEnabled,
                    configured, publishedSetup, runtimeState, List.of());
        }
        /** A conservative inspection candidate, never permission to mutate or proof of physical hardware. */
        public Scope scope() {
            if (!enabled) return Scope.DISABLED;
            if (!currentSet) return Scope.OUTSIDE_CURRENT_SET;
            if (!activeIp) return Scope.NO_ACTIVE_IP;
            if (interfaces.size() != 1 || !Set.of("Ethernet", "IEEE80211").contains(interfaces.getFirst().type())
                    || !interfaces.getFirst().bsdName().matches("en[0-9]+")) return Scope.OTHER_INTERFACE;
            if (!proxyProtocolEnabled || configured.status() != Status.PRESENT) return Scope.PROXIES_UNAVAILABLE;
            return Scope.CANDIDATE;
        }
        public Agreement proxyAgreement() {
            if (configured.status() != Status.PRESENT || runtimeState.status() != Status.PRESENT) return Agreement.UNAVAILABLE;
            return configured.settings().equals(runtimeState.settings()) ? Agreement.MATCH : Agreement.DIFFERENT;
        }
        @Override public String toString() { return "NetworkService[redacted]"; }
    }
}
