package app.perroute.platform.macos.proxy;

import app.perroute.service.AppProxyLauncher;
import java.io.IOException;
import java.util.Objects;

/** Serializes desktop operations and refuses ambiguous services or unresolved prior ownership. */
public final class MacSystemProxyService implements MacSystemProxyLifecycle {
    private final ProxyJournal journal;
    private final SystemProxyOwnership ownership;
    private final StableProxyReadback.Inspector inspector;
    public MacSystemProxyService(ProxyJournal journal) {
        this(journal, new MacSystemProxySettings(), new MacNetworkServiceInspector()::inspect);
    }
    public MacSystemProxyService(ProxyJournal journal, SystemProxySettings settings, StableProxyReadback.Inspector inspector) {
        this.journal = Objects.requireNonNull(journal);
        this.ownership = new SystemProxyOwnership(settings, journal);
        this.inspector = Objects.requireNonNull(inspector);
    }
    @Override public synchronized void apply() throws IOException, InterruptedException {
        restore();
        var observed = inspector.inspect();
        var candidates = observed.services().stream()
                .filter(service -> service.scope() == NetworkServiceInventory.Scope.CANDIDATE).toList();
        // Do not guess which service should own a global user action, or enroll future interfaces.
        if (candidates.size() != 1) throw new IOException("System proxy requires exactly one supported active network service. Disconnect other network services or use Local proxy.");
        String id = candidates.getFirst().id();
        var projection = new EffectiveProxyProjection().project(observed).stream()
                .filter(value -> value.serviceId().equals(id)).findFirst().orElseThrow();
        if (projection.outcome() != EffectiveProxyProjection.Outcome.MATCH)
            throw new IOException("The active network service has unsupported or unsettled proxy settings. Use Local proxy or resolve the configuration before retrying.");
        ownership.apply(java.util.List.of(id), AppProxyLauncher.HTTP_PORT, AppProxyLauncher.SOCKS_PORT);
    }
    @Override public synchronized void restore() throws IOException, InterruptedException {
        // A routine startup/disconnect with no ownership must never trigger an authorization prompt.
        if (journal.load().isEmpty()) return;
        var result = ownership.recover();
        if (!result.complete() || journal.load().isPresent())
            throw new IOException("System proxy recovery is pending. Existing settings were preserved; reconnect the original network service and retry Disconnect, or reopen PerRoute. External changes require manual review.");
    }
}
