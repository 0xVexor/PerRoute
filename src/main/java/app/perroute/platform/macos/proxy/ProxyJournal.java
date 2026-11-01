package app.perroute.platform.macos.proxy;

import java.io.IOException;
import java.util.*;

/** Access is serialized by the settings session and, in production, the persistent data-directory lock. */
public interface ProxyJournal {
    Optional<Lease> load() throws IOException;
    void save(Optional<Lease> lease) throws IOException;

    enum Group {
        HTTP("HTTPEnable", "HTTPProxy", "HTTPPort"),
        HTTPS("HTTPSEnable", "HTTPSProxy", "HTTPSPort"),
        SOCKS("SOCKSEnable", "SOCKSProxy", "SOCKSPort"),
        PAC("ProxyAutoConfigEnable", "ProxyAutoConfigURLString", "ProxyAutoConfigJavaScript"),
        DISCOVERY("ProxyAutoDiscoveryEnable");

        private final List<String> keys;
        Group(String... keys) { this.keys = List.of(keys); }
        List<String> keys() { return keys; }
    }
    record Entry(String serviceId, ProxySettings before, ProxySettings applied, Set<Group> groups) {
        public Entry {
            if (serviceId == null || serviceId.isBlank()) throw new IllegalArgumentException("Stable service ID required");
            groups = Set.copyOf(groups);
            if (groups.isEmpty()) throw new IllegalArgumentException("Empty ownership entry");
            var keys = groups.stream().flatMap(group -> group.keys().stream()).toList();
            if (!keys.containsAll(applied.values().keySet()) || !keys.containsAll(before.values().keySet())
                    || groups.stream().anyMatch(group -> applied.select(group.keys()).values().isEmpty()))
                throw new IllegalArgumentException("Ownership fields do not match groups");
            for (Group group : groups) {
                var values = applied.select(group.keys()).values();
                if (group == Group.PAC || group == Group.DISCOVERY) {
                    if (!Objects.equals(values.get(group.keys().getFirst()), java.math.BigDecimal.ZERO))
                        throw new IllegalArgumentException("Invalid automatic-proxy intent");
                    var guards = group.keys().subList(1, group.keys().size());
                    if (!before.select(guards).equals(applied.select(guards)))
                        throw new IllegalArgumentException("Automatic-proxy reference must be preserved");
                } else {
                    Object port = values.get(group.keys().get(2));
                    if (!Objects.equals(values.get(group.keys().getFirst()), java.math.BigDecimal.ONE)
                            || !Objects.equals(values.get(group.keys().get(1)), "127.0.0.1")
                            || !(port instanceof java.math.BigDecimal number)
                            || number.scale() > 0 || number.compareTo(java.math.BigDecimal.ONE) < 0
                            || number.compareTo(java.math.BigDecimal.valueOf(65535)) > 0)
                        throw new IllegalArgumentException("Invalid local-proxy intent");
                }
                if (before.select(group.keys()).equals(applied.select(group.keys())))
                    throw new IllegalArgumentException("Unchanged proxy group cannot be claimed");
            }
        }
        Entry select(Set<Group> remaining) {
            var keys = remaining.stream().flatMap(group -> group.keys().stream()).toList();
            return new Entry(serviceId, before.select(keys), applied.select(keys), remaining);
        }
    }
    record Lease(UUID owner, List<Entry> entries) {
        public Lease {
            Objects.requireNonNull(owner);
            entries = List.copyOf(entries);
            if (entries.isEmpty() || entries.stream().map(Entry::serviceId).distinct().count() != entries.size())
                throw new IllegalArgumentException("Invalid ownership lease");
        }
    }
}
