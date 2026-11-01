package app.perroute.platform.macos.proxy;

import app.perroute.service.AppProxyLauncher;
import app.perroute.util.Json;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Signed desktop requests cross authenticated XPC; the helper exclusively owns its root recovery record. */
public final class HelperSystemProxyLifecycle implements MacSystemProxyLifecycle {
    @FunctionalInterface public interface Client {
        Map<String, Object> request(String operation, Map<String, Object> values) throws IOException;
    }
    private final MacSystemProxyLifecycle legacy;
    private final Client client;
    private final StableProxyReadback.Inspector inspector;
    private final StableProxyReadback readback;
    private final RecoveryMarker marker;

    public HelperSystemProxyLifecycle(MacSystemProxyLifecycle legacy, Client client, StableProxyReadback.Inspector inspector) {
        this(legacy, client, inspector, new MemoryMarker());
    }
    HelperSystemProxyLifecycle(MacSystemProxyLifecycle legacy, Client client, StableProxyReadback.Inspector inspector, RecoveryMarker marker) {
        this.marker = Objects.requireNonNull(marker);
        this.legacy = Objects.requireNonNull(legacy);
        this.client = Objects.requireNonNull(client);
        this.inspector = Objects.requireNonNull(inspector);
        this.readback = new StableProxyReadback(inspector);
    }

    /** Both lifecycles share one authenticated native connection; neither starts routing during construction. */
    public record RoutingServices(MacSystemProxyLifecycle systemProxy,
            app.perroute.platform.macos.routing.MacTunSessionClient perApp) { }

    public static MacSystemProxyLifecycle preferred(ProxyJournal journal, Path recoveryMarker) throws IOException {
        return preferredServices(journal,recoveryMarker).systemProxy();
    }
    public static RoutingServices preferredServices(ProxyJournal journal, Path recoveryMarker) throws IOException {
        var legacy = new MacSystemProxyService(journal);
        Path tunMarker = recoveryMarker.resolveSibling("helper-tun.pending");
        String resources = System.getProperty("compose.application.resources.dir");
        if (resources == null || resources.isBlank()) return directDevelopment(legacy,recoveryMarker,tunMarker);
        String major = System.getProperty("os.version", "0").split("\\.")[0];
        try { if (Integer.parseInt(major) < 13) return directDevelopment(legacy,recoveryMarker,tunMarker); }
        catch (NumberFormatException failure) { throw new IOException("Cannot determine macOS helper availability", failure); }
        Path library = Path.of(resources).resolve("../../Frameworks/libPerRouteProxyBridge.dylib").normalize();
        if (!Files.isRegularFile(library)) return directDevelopment(legacy,recoveryMarker,tunMarker);
        var client = new NativeClient(library);
        if (!Boolean.TRUE.equals(client.request("availability", Map.of()).get("managed")))
            return directDevelopment(legacy,recoveryMarker,tunMarker);
        var proxy = new HelperSystemProxyLifecycle(legacy,client,new MacNetworkServiceInspector()::inspect,new FileMarker(recoveryMarker));
        Path engine = Path.of(resources).resolve("../../Library/Engines/sing-box").normalize();
        // Bundle presence enables composition, not trust or readiness. Native start independently
        // verifies payload signatures, architecture-matched Xray and actual listener ownership.
        if (Boolean.getBoolean("perroute.macos.perAppCandidate") && Files.isRegularFile(engine) && Files.isExecutable(engine))
            return new RoutingServices(proxy,new app.perroute.platform.macos.routing.MacTunSessionClient(client,tunMarker));
        requireNoTunRecovery(tunMarker);
        return new RoutingServices(proxy,null);
    }
    private static void requireNoTunRecovery(Path marker) throws IOException {
        if (app.perroute.platform.macos.routing.MacTunSessionClient.pendingRecovery(marker))
            throw new IOException("Per-app recovery is pending. Reopen the matching PerRoute routing installation before using this build.");
    }
    private static RoutingServices directDevelopment(MacSystemProxyLifecycle legacy, Path marker, Path tunMarker) throws IOException {
        requireNoTunRecovery(tunMarker);
        if (new FileMarker(marker).exists())
            throw new IOException("Proxy helper recovery is pending. Reopen the signed PerRoute installation and enable its background service before using this development build.");
        return new RoutingServices(legacy,null);
    }

    @Override public synchronized void apply() throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Connection cancelled");
        restore();
        var inventory = inspector.inspect();
        var candidates = inventory.services().stream()
                .filter(value -> value.scope() == NetworkServiceInventory.Scope.CANDIDATE).toList();
        if (candidates.size() != 1) throw new IOException("System proxy requires exactly one supported active network service. Use Local proxy or resolve the network configuration.");
        var service = candidates.getFirst();
        var projection = new EffectiveProxyProjection().project(inventory).stream()
                .filter(value -> value.serviceId().equals(service.id())).findFirst().orElseThrow();
        if (projection.outcome() != EffectiveProxyProjection.Outcome.MATCH)
            throw new IOException("The active network service has unsupported or unsettled proxy settings. Use Local proxy or retry later.");
        // Preparation may open System Settings for the one-time background-service approval.
        // Neither startup nor Disconnect calls prepare or collects an administrator credential.
        client.request("prepare", Map.of());
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Connection cancelled");
        marker.retain(); // Persist only an opaque pending flag; proxy snapshots remain root-owned.
        try {
            var response = client.request("apply", Map.of("service", service.id(), "http", AppProxyLauncher.HTTP_PORT,
                    "socks", AppProxyLauncher.SOCKS_PORT, "expected", service.configured().settings().orElseThrow().values()));
            var expected = new LinkedHashMap<>(service.configured().settings().orElseThrow().values());
            for (String prefix : java.util.List.of("HTTP", "HTTPS", "SOCKS")) {
                expected.put(prefix + "Enable", 1); expected.put(prefix + "Proxy", "127.0.0.1");
                expected.put(prefix + "Port", prefix.equals("SOCKS") ? AppProxyLauncher.SOCKS_PORT : AppProxyLauncher.HTTP_PORT);
            }
            verify(response, service.id(), new ProxySettings(expected));
        } catch (Exception failure) {
            try { restore(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @Override public synchronized void restore() throws Exception {
        // A prior direct-writer lease must be resolved before migrating custody to the helper.
        legacy.restore();
        var response = client.request("restore", Map.of());
        if (marker.exists() && Boolean.FALSE.equals(response.get("registered")))
            throw new IOException("Proxy recovery is pending. Re-enable the PerRoute background service before retrying Disconnect.");
        if (Boolean.TRUE.equals(response.get("pending"))) throw new IOException("Background proxy recovery remains pending. Retry Disconnect or check the service approval.");
        if (response.containsKey("service")) verify(response, null, null);
        marker.clear();
    }

    @Override public synchronized void verifySession() throws IOException {
        // A surviving recovery journal alone is not a live lease. After a daemon restart it may still
        // be awaiting recovery, so require ownership by this exact authenticated XPC connection.
        var response = client.request("status", Map.of());
        if (!Boolean.TRUE.equals(response.get("pending")) || !Boolean.TRUE.equals(response.get("owned")))
            throw new IOException("The background proxy session was lost. Disconnecting; proxy recovery will be retried. Connect again after checking the service approval.");
        // Observation never clears the pending marker or prepares/reapplies a connection.
    }

    private void verify(Map<String, Object> response, String expectedId, ProxySettings expected) throws IOException, InterruptedException {
        Object service = response.get("service"), values = response.get("settings");
        if (!(service instanceof String id) || (expectedId != null && !expectedId.equals(id)) || !(values instanceof Map<?, ?> map))
            throw new IOException("The background proxy service returned an invalid read-back response");
        final ProxySettings desired;
        try {
            var dictionary = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException();
                dictionary.put(key, entry.getValue());
            }
            desired = new ProxySettings(dictionary);
        } catch (IllegalArgumentException failure) { throw new IOException("Invalid background proxy settings response", failure); }
        if (expected != null && !expected.equals(desired))
            throw new IOException("The background proxy service did not apply the requested loopback endpoints");
        var observed = readback.read(id);
        if (!ManualProxySemantics.equivalent(desired, observed.configured()) || !ManualProxySemantics.equivalent(desired, observed.effective()))
            throw new IOException("Background proxy settings could not be verified. Recovery is required before reconnecting.");
    }


    interface RecoveryMarker {
        boolean exists() throws IOException;
        void retain() throws IOException;
        void clear() throws IOException;
    }
    private static final class MemoryMarker implements RecoveryMarker {
        private boolean pending;
        public boolean exists() { return pending; }
        public void retain() { pending = true; }
        public void clear() { pending = false; }
    }
    static final class FileMarker implements RecoveryMarker {
        private final Path path;
        FileMarker(Path path) { this.path = Objects.requireNonNull(path); }
        @Override public boolean exists() throws IOException {
            try {
                var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile() || attributes.size() != 1 || Files.readAllBytes(path)[0] != 1)
                    throw new IOException("Invalid helper recovery marker; existing data was preserved");
                app.perroute.persistence.PrivateStateFiles.requireRegular(path);
                return true;
            } catch (java.nio.file.NoSuchFileException missing) { return false; }
        }
        @Override public void retain() throws IOException {
            if (!exists()) app.perroute.persistence.PrivateStateFiles.write(path, new byte[]{1});
        }
        @Override public void clear() throws IOException { if (exists()) Files.delete(path); }
    }

    private interface Bridge extends Library {
        Pointer perroute_helper_request(byte[] request);
        void perroute_helper_free(Pointer value);
    }
    static final class NativeClient implements Client {
        private final Bridge bridge;
        NativeClient(Path path) throws IOException {
            try { bridge = Native.load(path.toAbsolutePath().toString(), Bridge.class); }
            catch (LinkageError failure) { throw new IOException("Could not load the macOS proxy service bridge", failure); }
        }
        @Override public synchronized Map<String, Object> request(String operation, Map<String, Object> values) throws IOException {
            var request = new LinkedHashMap<>(values);
            request.put("version", 1); request.put("operation", operation);
            byte[] encoded = (Json.stringify(request) + '\0').getBytes(StandardCharsets.UTF_8);
            if (encoded.length > 65537) throw new IOException("Proxy service request is too large");
            Pointer output = bridge.perroute_helper_request(encoded);
            if (output == null) throw new IOException("The background proxy service returned no response");
            try {
                var response = Json.object(output.getString(0, StandardCharsets.UTF_8.name()));
                if (!(response.get("version") instanceof Number version) || version.doubleValue() != 1.0) throw new IOException("Unsupported background proxy service version");
                if (!Boolean.TRUE.equals(response.get("ok")))
                    throw new IOException(response.get("error") instanceof String message ? message : "Background proxy service failed");
                if (!(response.get("result") instanceof Map<?, ?> result)) throw new IOException("Invalid background proxy service result");
                var copy = new LinkedHashMap<String, Object>();
                for (var entry : result.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) throw new IOException("Invalid background proxy service result key");
                    copy.put(key, entry.getValue());
                }
                return Map.copyOf(copy);
            } catch (IllegalArgumentException failure) { throw new IOException("Invalid background proxy service response", failure); }
            finally { bridge.perroute_helper_free(output); }
        }
    }
}
