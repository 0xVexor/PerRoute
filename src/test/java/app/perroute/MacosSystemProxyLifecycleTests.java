package app.perroute;

import app.perroute.backend.api.*;
import app.perroute.compose.controller.PerRouteController;
import app.perroute.model.*;
import app.perroute.persistence.AppStateStore;
import app.perroute.platform.macos.*;
import app.perroute.platform.macos.proxy.MacSystemProxyLifecycle;
import app.perroute.service.XrayRuntime;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Real owned Darwin engines and controlled loopback servers; OS settings are never touched. */
public final class MacosSystemProxyLifecycleTests {
    static int checks;
    static class Lifecycle implements MacSystemProxyLifecycle {
        MacConnectionBackend backend;
        boolean pending, failApply, refuseRestore;
        int applies, restores;
        boolean lostSession, unavailableSession;
        volatile int observations;
        public synchronized void verifySession() throws Exception {
            observations++;
            if (lostSession || unavailableSession) throw new java.io.IOException("Fixture helper session lost");
        }
        synchronized void loseSession(boolean restored) {
            lostSession = true;
            if (restored) pending = false;
        }
        synchronized void failSession() { unavailableSession = true; refuseRestore = true; }
        synchronized void resetSession() { lostSession = false; unavailableSession = false; refuseRestore = false; }
        List<String> events = new CopyOnWriteArrayList<>();
        public synchronized void apply() throws Exception {
            applies++; events.add("apply");
            check(backend.isRunning(), "Owned core runs before proxy application");
            pending = true;
            if (failApply) throw new java.io.IOException("Fixture authorization/apply failure");
        }
        public synchronized void restore() throws Exception {
            restores++;
            if (pending) {
                events.add(backend.isRunning() ? "restore-live" : "restore-stopped");
                if (refuseRestore) throw new java.io.IOException("Fixture external change requires review");
                pending = false;
            }
        }
        synchronized boolean pending() { return pending; }
    }
    public static void main(String[] args) throws Exception {
        try (var session = MacPreviewSession.open()) {
            var runtime = XrayRuntime.forPlatform(System.getProperty("os.name"), System.getProperty("os.arch"), Path.of(args[0]));
            try (var server = new RealCoreIntegrationTests.LocalServer("system", runtime.executable(), session.directory());
                 var second = new RealCoreIntegrationTests.LocalServer("second", runtime.executable(), session.directory())) {
                var life = new Lifecycle();
                var backend = new MacConnectionBackend(runtime, session.directory(), true, life); life.backend = backend;
                check(backend.capabilities().supports(ConnectionMode.SYSTEM_PROXY), "Wired backend enables System proxy");
                check(!backend.capabilities().networkRecovery() && !backend.capabilities().perApp(), "No broad recovery or per-app capabilities");
                try { backend.applySystemProxy(); throw new AssertionError("Proxy without owned engine"); } catch (java.io.IOException expected) { checks++; }
                var state = new AppState(); state.nodes().addAll(List.of(server.node(), second.node())); state.setSelectedNodeKey(server.node().key()); state.setConnectionMode(ConnectionMode.SYSTEM_PROXY);
                try (var controller = new PerRouteController(state, AppStateStore.inMemory(), isolated(backend), MacosDesktopTests.preferences())) {
                    check(life.restores == 1, "Controller startup requests narrow recovery");
                    var result = controller.connect(); check(result.getOk(), "Connect: " + result.getMessage());
                    check(life.pending(), "Connected lease stays owned");
                    check(MacosDesktopTests.request().endsWith("server-system"), "Real proxy traffic reaches controlled VLESS server");
                    check(controller.switchNode(second.node().key()).getOk(), "System mode server switch succeeds");
                    check(life.pending() && life.applies == 1, "Server switch retains the existing proxy lease");
                    check(MacosDesktopTests.request().endsWith("server-second"), "Switched core forwards through second controlled server");
                    check(controller.disconnect().getOk(), "Disconnect restores");
                    check(!life.pending() && !backend.isRunning(), "Disconnect clears ownership and stops owned core");
                    check(life.events.contains("restore-live"), "Normal restoration precedes owned core shutdown");
                    life.failApply = true;
                    check(!controller.connect().getOk(), "Failed native apply is a failed Connect");
                    check(!life.pending() && !backend.isRunning(), "Failed Connect rolls back before stopping core");
                    life.failApply = false;
                    check(controller.connect().getOk(), "Reconnect succeeds");
                    backend.stop();
                    long deadline = System.nanoTime() + 5_000_000_000L;
                    while (life.pending() && System.nanoTime() < deadline) Thread.sleep(20);
                    check(!life.pending(), "Unexpected owned core exit invokes restoration");
                    check(controller.disconnect().getOk(), "Disconnect is a recovery retry even after core exit");
                    check(controller.connect().getOk(), "Connect before recovery failure");
                    life.refuseRestore = true;
                    check(!controller.disconnect().getOk(), "Unresolved restoration is shown to user");
                    check(life.pending(), "Failed cleanup retains ownership");
                    check(!controller.connect().getOk(), "Unresolved earlier lease blocks a new core start");
                    check(!controller.disconnect().getOk(), "Already-disconnected action retries and reports pending cleanup");
                    life.refuseRestore = false;
                    check(controller.disconnect().getOk() && !life.pending(), "Recovery succeeds on explicit retry");
                    check(controller.connect().getOk(), "Connect before helper restart");
                    int beforeHealthy = life.observations;
                    await(() -> life.observations > beforeHealthy, "Connected session is observed by the periodic monitor");
                    check(controller.isConnected(), "Healthy helper does not interrupt a connection");
                    controller.setAutoReconnect(true);
                    life.loseSession(true);
                    await(() -> !controller.isConnected() && !backend.isRunning(), "Helper restart disconnects UI and owned core");
                    check(controller.connectionPhase() == app.perroute.compose.controller.ConnectionPhase.DISCONNECTED, "Lost helper exits Connected phase");
                    int appliesAfterLoss = life.applies;
                    Thread.sleep(1_200);
                    check(life.applies == appliesAfterLoss && !backend.isRunning(), "Session loss does not automatically prepare or reconnect");
                    life.resetSession();
                    check(controller.connect().getOk(), "Explicit Connect after helper recovery");
                    life.failSession();
                    await(() -> !controller.isConnected() && !backend.isRunning(), "Unavailable helper stops core even when restore fails");
                    check(life.pending(), "Failed recovery retains simulated lease");
                    check(!controller.connect().getOk(), "Pending failed recovery blocks new Connect");
                    life.resetSession();
                    check(controller.disconnect().getOk(), "Explicit recovery retry succeeds after helper returns");
                    controller.setMode(ConnectionMode.BROWSER_PROXY);
                    check(controller.connect().getOk(), "Local proxy works without helper health");
                    int beforeLocal = life.observations;
                    life.loseSession(true);
                    Thread.sleep(4_500);
                    check(controller.isConnected() && life.observations == beforeLocal, "Local proxy does not observe helper sessions");
                    check(controller.disconnect().getOk(), "Local proxy disconnects");
                    life.resetSession();
                    controller.setMode(ConnectionMode.SYSTEM_PROXY);
                    check(controller.connect().getOk(), "Connect before pending retry cancellation");
                    backend.stop();
                    check(controller.disconnect().getOk(), "Disconnect cancels a scheduled core-exit reconnect");
                    Thread.sleep(1_800);
                    check(!controller.isConnected() && !backend.isRunning(), "Sleeping reconnect cannot reopen an explicitly ended session");
                    check(controller.connect().getOk(), "Connect before close");
                }
                check(!life.pending() && !backend.isRunning(), "Close restores and stops owned engine");
                check(server.process.isAlive(), "Unrelated controlled server remains alive");
            }
        }
        System.out.println("macOS system proxy lifecycle checks passed (" + checks + " assertions; OS settings simulated)");
        System.exit(0);
    }
    static ConnectionBackend isolated(ConnectionBackend backend) {
        return (ConnectionBackend) java.lang.reflect.Proxy.newProxyInstance(ConnectionBackend.class.getClassLoader(), new Class<?>[]{ConnectionBackend.class}, (proxy, method, values) -> {
            return switch (method.getName()) {
                case "checkTunnel" -> new TunnelStatus(true, "Controlled loopback");
                case "lookupPublicAddress" -> new RouteObservation("127.0.0.1", null, null);
                case "systemDnsServers" -> List.of();
                case "publicResolver" -> null;
                case "stopOrphan", "applyPerApp", "restorePerApp", "stopPerAppSidecar", "installCore", "installPerApp", "installedApplications", "runningApplications" -> throw new AssertionError("Forbidden Windows/per-app operation");
                default -> { try { yield method.invoke(backend, values); } catch (InvocationTargetException error) { throw error.getCause(); } }
            };
        });
    }
    static void await(java.util.function.BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + 12_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        check(condition.getAsBoolean(), message);
    }
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
