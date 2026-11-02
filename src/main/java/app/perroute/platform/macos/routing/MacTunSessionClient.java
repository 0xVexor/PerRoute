package app.perroute.platform.macos.routing;

import app.perroute.persistence.PrivateStateFiles;
import app.perroute.platform.macos.proxy.HelperSystemProxyLifecycle.Client;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import app.perroute.platform.macos.routing.MacPerAppRoutingPlan.ExecutableRoute;

/** A ready session belongs to this exact helper connection and native generation. */
public final class MacTunSessionClient {
    @FunctionalInterface interface Sleeper { void sleep(long milliseconds) throws InterruptedException; }
    private final Client client;
    private final Path recoveryMarker;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private String session;

    public MacTunSessionClient(Client client, Path recoveryMarker) {
        this(client, recoveryMarker, System::nanoTime, Thread::sleep);
    }
    MacTunSessionClient(Client client, Path recoveryMarker, LongSupplier clock, Sleeper sleeper) {
        this.client = Objects.requireNonNull(client);
        this.recoveryMarker = Objects.requireNonNull(recoveryMarker);
        this.clock = Objects.requireNonNull(clock);
        this.sleeper = Objects.requireNonNull(sleeper);
    }
    public synchronized void start(List<ExecutableRoute> selected, long corePid, List<Integer> upstreamPorts) throws Exception {
        start(selected,corePid,upstreamPorts,true);
    }
    public synchronized void start(List<ExecutableRoute> selected, long corePid, List<Integer> upstreamPorts, boolean allowSetup) throws Exception {
        var request = new MacTunStartRequest(selected, corePid, upstreamPorts);
        interrupted();
        client.request(allowSetup ? "prepare" : "prepare-existing", Map.of());
        interrupted();
        if (!available()) throw new IOException("Install the matching signed PerRoute helper, routing engine and Xray bundle before using per-app mode.");
        if (selected.stream().anyMatch(binding -> binding.scope() == MacPerAppRoutingPlan.Scope.SAFARI_APPLICATION)) {
            var capabilities = client.request("tun-capabilities", Map.of());
            if (!(capabilities.get("safariProtocol") instanceof Number protocol) || protocol.intValue() != 1)
                throw new IOException("Safari routing requires the matching Safari-enabled app, helper and engine. Install the new PKG before connecting.");
        }
        stop();
        if (!pendingRecovery()) PrivateStateFiles.write(recoveryMarker, new byte[]{1});
        try {
            var state = client.request("start-tun", request.values());
            interrupted();
            session = identity(state);
            long deadline = clock.getAsLong() + 15_000_000_000L;
            for (;;) {
                interrupted();
                if (ready(state)) return;
                if (clock.getAsLong() - deadline >= 0) throw new IOException("The per-app tunnel did not become ready before its deadline.");
                sleeper.sleep(100);
                state = client.request("tun-status", Map.of());
            }
        } catch (Exception failure) {
            try { stop(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    public synchronized boolean available() throws IOException {
        var state = client.request("tun-capabilities", Map.of());
        return state.get("protocol") instanceof Number version && version.doubleValue() == 1.0 &&
                state.get("endpointProtocol") instanceof Number endpoints && endpoints.doubleValue() == 2.0 &&
                state.get("euid") instanceof Number user && user.doubleValue() == 0.0 &&
                Boolean.TRUE.equals(state.get("payloadAvailable"));
    }
    public synchronized void verifySession() throws IOException {
        if (session == null) throw new IOException("There is no owned per-app routing session.");
        var state = client.request("tun-status", Map.of());
        // Only an owner-bound terminal reply for this exact generation permits a network retry.
        // Keep the durable marker until explicit stop confirms recovery independently.
        if ("network-changed".equals(state.get("phase")) && Boolean.FALSE.equals(state.get("pending")) &&
                Boolean.FALSE.equals(state.get("owned")) && session.equals(state.get("session")))
            throw new app.perroute.backend.api.RoutingNetworkChangedException();
        if (!ready(state))
            throw new IOException("The background per-app routing session was lost. Disconnect and connect again after recovery.");
    }
    public synchronized void stop() throws IOException {
        var state = client.request("stop-tun", Map.of());
        if (Boolean.FALSE.equals(state.get("registered"))) {
            if (pendingRecovery()) throw new IOException("Per-app recovery is pending. Reopen the matching signed PerRoute installation and restore its helper.");
        } else if (!Boolean.FALSE.equals(state.get("pending")) || !Boolean.FALSE.equals(state.get("owned")) || !"stopped".equals(state.get("phase"))) {
            throw new IOException("Background per-app recovery remains pending; retry Disconnect before reconnecting.");
        }
        if (pendingRecovery()) Files.delete(recoveryMarker);
        session = null;
    }
    public synchronized boolean pendingRecovery() throws IOException { return pendingRecovery(recoveryMarker); }
    public static boolean pendingRecovery(Path recoveryMarker) throws IOException {
        try {
            var attributes = Files.readAttributes(recoveryMarker, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() != 1)
                throw new IOException("Invalid per-app recovery marker; existing data was preserved.");
            PrivateStateFiles.requireRegular(recoveryMarker);
            if (Files.readAllBytes(recoveryMarker)[0] != 1)
                throw new IOException("Invalid per-app recovery marker; existing data was preserved.");
            return true;
        } catch (NoSuchFileException missing) { return false; }
    }
    private boolean ready(Map<String, Object> state) throws IOException {
        if (!Objects.equals(session, identity(state))) throw new IOException("The background per-app session generation changed; recovery is required.");
        return "ready".equals(state.get("phase"));
    }
    private static String identity(Map<String, Object> state) throws IOException {
        // Name the rejected field without logging native tokens, paths or arbitrary reply contents.
        String rejected = !Boolean.TRUE.equals(state.get("pending")) ? "pending flag" :
                !Boolean.TRUE.equals(state.get("owned")) ? "owned flag" :
                !("ready".equals(state.get("phase")) || "starting".equals(state.get("phase"))) ? "phase" :
                !(state.get("session") instanceof String value) || value.length() != 36 ? "session identifier" : null;
        if (rejected != null)
            throw new IOException("The background service did not confirm ownership of the per-app session: invalid " + rejected + ".");
        String identifier = (String) state.get("session");
        try {
            if (!UUID.fromString(identifier).toString().equalsIgnoreCase(identifier)) throw new IllegalArgumentException();
            return identifier;
        } catch (IllegalArgumentException failure) { throw new IOException("Invalid background per-app session identity.", failure); }
    }
    private static void interrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Per-app connection cancelled");
    }
}
