package app.perroute.platform.macos.proxy;

import java.io.IOException;
import java.util.*;
import static app.perroute.platform.macos.proxy.ProxyJournal.*;

/** Ownership and recovery policy over an injected settings session and durable intent journal. */
public final class SystemProxyOwnership {
    private final SystemProxySettings settings;
    private final ProxyJournal journal;

    public SystemProxyOwnership(SystemProxySettings settings, ProxyJournal journal) {
        this.settings = Objects.requireNonNull(settings);
        this.journal = Objects.requireNonNull(journal);
    }

    public enum Problem { EXTERNAL_CHANGE, UNREADABLE_SERVICE, RESTORE_FAILED, VERIFICATION_FAILED }
    public record Issue(String serviceId, Group group, Problem problem) { }
    public record Recovery(List<Issue> issues) {
        public Recovery { issues = List.copyOf(issues); }
        public boolean complete() { return issues.isEmpty(); }
    }

    /** The caller must explicitly select stable service IDs; new services are never implicitly enrolled. */
    public synchronized void apply(List<String> serviceIds, int httpPort, int socksPort) throws IOException, InterruptedException {
        List<String> targets = List.copyOf(serviceIds);
        if (targets.isEmpty() || targets.stream().anyMatch(id -> id == null || id.isBlank()) || new HashSet<>(targets).size() != targets.size())
            throw new IllegalArgumentException("Choose unique stable network-service IDs");
        ProxySettings local = local(httpPort, socksPort);
        checkInterrupted();
        try (var session = settings.lock()) {
            if (journal.load().isPresent()) throw new IOException("Resolve the existing proxy recovery journal before applying settings");
            var entries = new ArrayList<Entry>();
            var snapshots = new LinkedHashMap<String, ProxySettings>();
            for (String id : targets) {
                var state = session.read(id);
                if (!session.equivalent(state.configured(), state.effective())) throw new IOException("Network-service configuration is not settled");
                var proposed = state.configured().replacing(local.values().keySet(), local);
                var groups = EnumSet.noneOf(Group.class);
                for (Group group : Group.values()) {
                    if (!state.configured().select(group.keys()).equals(proposed.select(group.keys()))) groups.add(group);
                }
                if (!groups.isEmpty()) {
                    var keys = keys(groups);
                    entries.add(new Entry(id, state.configured().select(keys), proposed.select(keys), groups));
                    snapshots.put(id, state.configured());
                }
            }
            if (entries.isEmpty()) return;
            Lease lease = new Lease(UUID.randomUUID(), entries);
            journal.save(Optional.of(lease));
            try {
                for (Entry entry : entries) {
                    checkInterrupted();
                    ProxySettings before = snapshots.get(entry.serviceId());
                    ProxySettings applied = before.replacing(keys(entry.groups()), entry.applied());
                    session.replace(entry.serviceId(), before, applied);
                    var observed = session.read(entry.serviceId());
                    if (!observed.configured().equals(applied) || !session.equivalent(applied, observed.effective()))
                        throw new IOException("Proxy configuration failed read-back verification");
                }
            } catch (IOException | InterruptedException error) {
                // Cancellation can arrive after commit. Temporarily clear it so owned rollback can finish.
                boolean interrupted = Thread.interrupted() || error instanceof InterruptedException;
                try {
                    Recovery recovery = recover(session, lease);
                    if (!recovery.complete()) error.addSuppressed(new IOException("Proxy recovery remains unresolved"));
                } catch (IOException | InterruptedException cleanup) {
                    interrupted |= cleanup instanceof InterruptedException;
                    error.addSuppressed(cleanup);
                } finally { if (interrupted) Thread.currentThread().interrupt(); }
                throw error;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        }
    }

    /** Safe to retry after restart. Unknown or externally changed groups stay in the journal. */
    public synchronized Recovery recover() throws IOException, InterruptedException {
        checkInterrupted();
        try (var session = settings.lock()) {
            Optional<Lease> lease = journal.load();
            return lease.isEmpty() ? new Recovery(List.of()) : recover(session, lease.get());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        }
    }

    private Recovery recover(SystemProxySettings.Session session, Lease lease) throws IOException, InterruptedException {
        var pending = new ArrayList<Entry>();
        var issues = new ArrayList<Issue>();
        var reverse = new ArrayList<>(lease.entries());
        Collections.reverse(reverse);
        for (Entry entry : reverse) {
            checkInterrupted();
            var remaining = EnumSet.copyOf(entry.groups());
            try {
                var current = session.read(entry.serviceId());
                ProxySettings replacement = current.configured();
                var restoring = EnumSet.noneOf(Group.class);
                for (Group group : entry.groups()) {
                    ProxySettings before = entry.before().select(group.keys());
                    ProxySettings applied = entry.applied().select(group.keys());
                    ProxySettings configured = current.configured().select(group.keys());
                    ProxySettings effective = current.effective().select(group.keys());
                    if (configured.equals(before) && session.equivalent(before, effective)) remaining.remove(group);
                    else if (known(configured, before, applied) && (session.equivalent(before, effective) || session.equivalent(applied, effective))) {
                        // Restore endpoint/port/enable as a unit; never construct a mixed external proxy tuple.
                        replacement = replacement.replacing(group.keys(), before);
                        restoring.add(group);
                    } else issues.add(new Issue(entry.serviceId(), group, Problem.EXTERNAL_CHANGE));
                }
                if (!restoring.isEmpty()) {
                    try {
                        session.replace(entry.serviceId(), current.configured(), replacement);
                        var verified = session.read(entry.serviceId());
                        for (Group group : restoring) {
                            ProxySettings before = entry.before().select(group.keys());
                            if (verified.configured().select(group.keys()).equals(before) && session.equivalent(before, verified.effective().select(group.keys())))
                                remaining.remove(group);
                            else issues.add(new Issue(entry.serviceId(), group, Problem.VERIFICATION_FAILED));
                        }
                    } catch (IOException failure) {
                        restoring.forEach(group -> issues.add(new Issue(entry.serviceId(), group, Problem.RESTORE_FAILED)));
                    }
                }
            } catch (IOException failure) {
                remaining.forEach(group -> issues.add(new Issue(entry.serviceId(), group, Problem.UNREADABLE_SERVICE)));
            }
            if (!remaining.isEmpty()) pending.add(entry.select(remaining));
        }
        // A failed atomic write leaves the previous journal available for an idempotent retry.
        journal.save(pending.isEmpty() ? Optional.empty() : Optional.of(new Lease(lease.owner(), pending)));
        return new Recovery(issues);
    }

    private static boolean known(ProxySettings value, ProxySettings before, ProxySettings applied) {
        return value.equals(before) || value.equals(applied);
    }
    private static List<String> keys(Set<Group> groups) { return groups.stream().flatMap(group -> group.keys().stream()).toList(); }
    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Proxy operation cancelled");
    }
    private static ProxySettings local(int http, int socks) {
        if (http < 1 || http > 65535 || socks < 1 || socks > 65535) throw new IllegalArgumentException("Invalid loopback proxy port");
        var result = new LinkedHashMap<String, Object>();
        for (String protocol : List.of("HTTP", "HTTPS", "SOCKS")) {
            result.put(protocol + "Enable", 1);
            result.put(protocol + "Proxy", "127.0.0.1");
            result.put(protocol + "Port", protocol.equals("SOCKS") ? socks : http);
        }
        result.put("ProxyAutoConfigEnable", 0);
        result.put("ProxyAutoDiscoveryEnable", 0);
        return new ProxySettings(result);
    }
}
