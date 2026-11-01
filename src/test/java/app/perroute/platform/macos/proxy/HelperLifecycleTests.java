package app.perroute.platform.macos.proxy;

import java.io.IOException;
import java.util.*;

/** Signed-service behavior over a simulated XPC peer and inventory; never authorizes or changes the host. */
public final class HelperLifecycleTests {
    private static int checks;
    public static void main(String[] args) throws Exception {
        var fixture = new Fixture();
        fixture.service.restore();
        check(fixture.calls.equals(List.of("legacy-restore", "restore")), "Startup only retries recovery");
        fixture.calls.clear();
        fixture.service.apply();
        check(fixture.calls.equals(List.of("legacy-restore", "restore", "prepare", "apply")), "Legacy recovery precedes helper setup and apply");
        check(fixture.active, "Helper owns applied lease");
        fixture.calls.clear();
        fixture.service.restore();
        check(fixture.calls.equals(List.of("legacy-restore", "restore")), "Disconnect never requests registration");
        check(!fixture.active && fixture.writer.configured.equals(fixture.original), "Original settings restored");

        var healthy = new Fixture(); healthy.service.apply(); healthy.calls.clear();
        healthy.service.verifySession();
        check(healthy.calls.equals(List.of("status")), "Health only observes status without setup or mutation");
        check(healthy.marker.pending && healthy.active, "Healthy observation preserves recovery custody");
        for (var reply : List.of(Map.<String, Object>of("pending", false, "owned", false),
                Map.<String, Object>of("pending", true, "owned", false),
                Map.<String, Object>of("pending", false, "owned", true),
                Map.<String, Object>of("pending", true), Map.<String, Object>of(),
                Map.<String, Object>of("pending", "true", "owned", true))) {
            healthy.statusReply = reply; healthy.calls.clear();
            expectFailure(healthy.service::verifySession);
            check(healthy.calls.equals(List.of("status")) && healthy.marker.pending,
                    "Lost/malformed session cannot prepare, mutate, restore or clear recovery intent");
        }
        healthy.failStatus = true; healthy.calls.clear();
        expectFailure(healthy.service::verifySession);
        check(healthy.calls.equals(List.of("status")) && healthy.marker.pending, "Unavailable helper retains recovery marker");
        healthy.failStatus = false; healthy.statusReply = null; healthy.service.restore();
        expectFailure(healthy.service::verifySession);
        check(!healthy.marker.pending, "Observation after verified restore cannot recreate a marker");

        var denied = new Fixture(); denied.denyPrepare = true;
        expectFailure(denied.service::apply);
        check(!denied.calls.contains("apply") && !denied.active, "Approval denial cannot write settings or fall back");
        var migration = new Fixture(); migration.failLegacy = true;
        expectFailure(migration.service::apply);
        check(migration.calls.equals(List.of("legacy-restore")), "Unresolved legacy lease blocks helper migration");
        var partial = new Fixture(); partial.failApply = true;
        expectFailure(partial.service::apply);
        check(!partial.active && partial.writer.configured.equals(partial.original), "Failed helper apply rolls back");
        check(Collections.frequency(partial.calls, "restore") == 2, "Failed write triggers recovery retry");
        var malformed = new Fixture(); malformed.invalidReply = true;
        expectFailure(malformed.service::apply);
        check(!malformed.active, "Malformed read-back response rolls back");
        var lagging = new Fixture(); lagging.lagEffective = true;
        expectFailure(lagging.service::apply);
        check(!lagging.active, "Configured/effective mismatch rolls back");
        var unresolved = new Fixture(); unresolved.failRestore = true;
        expectFailure(unresolved.service::apply);
        check(!unresolved.calls.contains("prepare") && !unresolved.calls.contains("apply"), "Pending helper recovery blocks a new connection");
        var pending = new Fixture(); pending.pendingRestore = true;
        expectFailure(pending.service::restore);
        check(!pending.calls.contains("prepare"), "Explicit pending result is never successful disconnect");
        var none = new Fixture();
        var emptyInventory = new NetworkServiceInventory(List.of(), none.writer.inventory().globalProxies(), NetworkServiceInventory.Consistency.UNCHANGED);
        var noService = new HelperSystemProxyLifecycle(none.legacy, none, () -> emptyInventory);
        expectFailure(noService::apply);
        check(!none.calls.contains("prepare"), "No candidate refuses before approval");
        var unsupported = new Fixture();
        unsupported.writer.configured = new ProxySettings(Map.of("ProxyAutoConfigEnable", 1));
        unsupported.writer.setup = unsupported.writer.configured;
        expectFailure(unsupported.service::apply);
        check(!unsupported.calls.contains("prepare"), "Unsupported proxy configuration refuses before approval");
        var disabled = new Fixture(); disabled.service.apply(); disabled.unregistered = true;
        expectFailure(disabled.service::restore);
        check(disabled.marker.pending && disabled.active, "Disabled helper preserves pending recovery marker and settings");
        disabled.unregistered = false; disabled.service.restore();
        check(!disabled.marker.pending && !disabled.active, "Re-enabled helper clears verified recovery marker");
        var wrongPorts = new Fixture(); wrongPorts.wrongPorts = true;
        expectFailure(wrongPorts.service::apply);
        check(!wrongPorts.active, "Wrong requested endpoints are refused and restored");
        var markerDirectory = java.nio.file.Files.createTempDirectory("perroute-helper-marker-").toRealPath();
        var markerPath = markerDirectory.resolve("pending");
        try {
            var fileMarker = new HelperSystemProxyLifecycle.FileMarker(markerPath);
            check(!fileMarker.exists(), "Missing marker accepted");
            fileMarker.retain();
            expectFailure(() -> HelperSystemProxyLifecycle.preferred(new NativeProxyWriterTests.MemoryJournal(), markerPath));
            check(new HelperSystemProxyLifecycle.FileMarker(markerPath).exists(), "Pending marker survives reconstruction");
            check(java.nio.file.Files.getPosixFilePermissions(markerPath).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")), "Pending marker is private");
            fileMarker.clear(); check(!fileMarker.exists(), "Verified completion removes marker");
            java.nio.file.Files.write(markerPath, new byte[]{1, 2});
            expectFailure(fileMarker::retain);
            check(java.nio.file.Files.size(markerPath) == 2, "Corrupt marker preserved");
            java.nio.file.Files.delete(markerPath);
            java.nio.file.Files.createSymbolicLink(markerPath, markerDirectory.resolve("missing"));
            expectFailure(fileMarker::retain);
            check(java.nio.file.Files.isSymbolicLink(markerPath), "Symlink marker refused without replacement");
        } finally { java.nio.file.Files.deleteIfExists(markerPath); java.nio.file.Files.delete(markerDirectory); }
        var interrupted = new Fixture();
        Thread.currentThread().interrupt();
        try { interrupted.service.apply(); throw new AssertionError("Cancelled observation accepted"); }
        catch (InterruptedException expected) { check(!interrupted.active, "Cancelled verification attempts recovery"); }
        finally { Thread.interrupted(); }
        System.out.println("Proxy helper lifecycle: " + checks + " assertions passed (simulated peer; no network changes)");
    }
    private static final class Fixture implements HelperSystemProxyLifecycle.Client {
        final NativeProxyWriterTests.Fixture writer = new NativeProxyWriterTests.Fixture();
        final ProxySettings original = writer.configured;
        final String serviceId = writer.inventory().services().getFirst().id();
        final List<String> calls = new ArrayList<>();
        Map<String, Object> statusReply;
        boolean failStatus;
        boolean active, denyPrepare, failLegacy, failApply, invalidReply, lagEffective, failRestore, pendingRestore, unregistered, wrongPorts;
        final MacSystemProxyLifecycle legacy = new MacSystemProxyLifecycle() {
            @Override public void apply() { throw new AssertionError("Helper must never fall back to direct apply"); }
            @Override public void restore() throws IOException { calls.add("legacy-restore"); if (failLegacy) throw new IOException("Pending legacy lease"); }
        };
        final TrackingMarker marker = new TrackingMarker();
        final HelperSystemProxyLifecycle service = new HelperSystemProxyLifecycle(legacy, this, writer::inventory, marker);
        @Override public Map<String, Object> request(String operation, Map<String, Object> values) throws IOException {
            calls.add(operation);
            switch (operation) {
                case "status" -> {
                    if (failStatus) throw new IOException("Simulated XPC interruption");
                    return statusReply != null ? statusReply : Map.of("pending", active, "owned", active);
                }
                case "prepare" -> { if (denyPrepare) throw new IOException("Approval required"); return Map.of(); }
                case "restore" -> {
                    if (unregistered) return Map.of("pending", false, "registered", false);
                    if (failRestore) throw new IOException("External conflict");
                    if (pendingRestore) return Map.of("pending", true);
                    boolean wasActive = active;
                    active = false;
                    if (wasActive) { writer.configured = original; writer.setup = original; writer.effective = original; }
                    return wasActive ? Map.of("pending", false, "service", serviceId, "settings", original.values()) : Map.of("pending", false);
                }
                case "apply" -> {
                    check(values.get("expected").equals(original.values()), "Full expected configured dictionary sent");
                    check(values.get("service").equals(serviceId), "Stable service selected");
                    var result = new LinkedHashMap<>(original.values());
                    for (String prefix : List.of("HTTP", "HTTPS", "SOCKS")) {
                        result.put(prefix + "Enable", 1); result.put(prefix + "Proxy", "127.0.0.1");
                        result.put(prefix + "Port", values.get(prefix.equals("SOCKS") ? "socks" : "http"));
                    }
                    if (wrongPorts) result.put("HTTPPort", 9999);
                    active = true; writer.configured = new ProxySettings(result); writer.setup = writer.configured;
                    if (!lagEffective) writer.effective = writer.configured;
                    if (failApply) throw new IOException("Partial write");
                    if (invalidReply) return Map.of("service", "another-service", "settings", result);
                    return Map.of("pending", true, "service", serviceId, "settings", result);
                }
                default -> throw new AssertionError("Unexpected helper operation");
            }
        }
    }
    private static final class TrackingMarker implements HelperSystemProxyLifecycle.RecoveryMarker {
        boolean pending;
        public boolean exists() { return pending; }
        public void retain() { pending = true; }
        public void clear() { pending = false; }
    }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private static void expectFailure(Operation action) throws Exception {
        try { action.run(); throw new AssertionError("Expected explicit failure"); }
        catch (IOException expected) { check(true, "Explicit error propagated"); }
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
