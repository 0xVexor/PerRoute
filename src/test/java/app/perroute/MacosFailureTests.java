package app.perroute;

import app.perroute.backend.api.ConnectionBackend;
import app.perroute.compose.controller.PerRouteController;
import app.perroute.compose.controller.ActionResult;
import app.perroute.compose.controller.ConnectionPhase;
import app.perroute.model.*;
import app.perroute.persistence.AppStateStore;
import app.perroute.platform.macos.*;
import app.perroute.service.*;

import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

/** All port collisions belong to this test. No OS proxy or external endpoint is used. */
public final class MacosFailureTests {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        var runtime = XrayRuntime.forPlatform(System.getProperty("os.name"), System.getProperty("os.arch"), Path.of(args[0]));
        try (var session = MacPreviewSession.open();
             var first = new RealCoreIntegrationTests.LocalServer("one", runtime.executable(), session.directory());
             var second = new RealCoreIntegrationTests.LocalServer("two", runtime.executable(), session.directory())) {
            for (int port : List.of(AppProxyLauncher.SOCKS_PORT, AppProxyLauncher.HTTP_PORT, XrayConfigGenerator.STATS_API_PORT)) {
                try (var f = new Fixture(runtime, session.directory(), first, second); var occupied = occupy(port)) {
                    var result = f.controller.connect();
                    check(!result.getOk(), "Occupied port must reject connection: " + port);
                    check(result.getMessage().contains("startup"), "Early exit includes startup diagnosis: " + result.getMessage());
                    f.disconnected();
                    check(!occupied.isClosed(), "Port owner remains untouched");
                }
            }
            try (var f = new Fixture(runtime, session.directory(), first, second)) {
                f.connect();
                var bad = new ProxyNode("bad", "Invalid UUID", Protocol.VLESS, "127.0.0.1", 1, "invalid-uuid-that-is-longer-than-thirty-characters", Map.of("security", "none"), "");
                f.state.nodes().add(bad);
                int starts = f.starts;
                check(!f.controller.switchNode("bad").getOk(), "Invalid target is rejected");
                check(f.starts == starts, "Invalid target never restarts the active core");
                f.restored(first);
            }
            try (var f = new Fixture(runtime, session.directory(), first, second)) {
                f.connect();
                f.failStarts = 1;
                var result = f.controller.switchNode(second.node().key());
                check(!result.getOk() && result.getMessage().contains("restored"), "Failed start restores previous connection: " + result.getMessage());
                f.restored(first);
            }
            try (var f = new Fixture(runtime, session.directory(), first, second)) {
                f.connect();
                f.failStarts = 2;
                check(!f.controller.switchNode(second.node().key()).getOk(), "Failed target and rollback report failure");
                f.disconnected();
                check(f.state.selectedNodeKey().equals(first.node().key()), "Failed rollback preserves previous selection");
                f.connect();
                f.restored(first);
            }
            try (var f = new Fixture(runtime, session.directory(), first, second)) {
                f.pause = "validate";
                cancelled(f, () -> f.controller.connect());
                f.disconnected();
                f.connect();
            }
            try (var f = new Fixture(runtime, session.directory(), first, second)) {
                f.connect();
                f.pause = "validate";
                cancelled(f, () -> f.controller.switchNode(second.node().key()));
                f.restored(first);
            }
            try (var f = new Fixture(runtime, session.directory(), first, second, true)) {
                f.connect();
                f.pause = "validate";
                f.pauseSkips = 1;
                int starts = f.starts;
                cancelled(f, () -> f.controller.switchNode(second.node().key()));
                check(f.starts == starts, "Cancellation during live-route validation never starts a fallback connection");
                f.restored(first);
            }
            try (var f = new Fixture(runtime, session.directory(), first, second)) {
                f.connect();
                f.pause = "start";
                cancelled(f, () -> f.controller.switchNode(second.node().key()));
                f.restored(first);
            }
            check(first.process.isAlive() && second.process.isAlive(), "All failure paths leave unrelated Xray servers alive");
            MacosDesktopTests.assertIsolated();
        }
        System.out.println("macOS runtime failure checks passed (" + assertions + " assertions)");
        System.exit(0);
    }

    @FunctionalInterface private interface Operation { ActionResult run(); }
    private static void cancelled(Fixture fixture, Operation operation) throws Exception {
        var result = new AtomicReference<ActionResult>();
        var error = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        Thread worker = Thread.ofPlatform().start(() -> {
            try { result.set(operation.run()); }
            catch (Throwable failure) { error.set(failure); }
            finally { interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        try {
            check(fixture.entered.await(5, TimeUnit.SECONDS), "Worker reaches cancellation point");
            worker.interrupt();
            worker.join(10_000);
            check(!worker.isAlive(), "Cancelled operation finishes after bounded cleanup");
            check(error.get() == null, "Controller returns cancellation result: " + error.get());
            check(result.get() != null && !result.get().getOk() && result.get().getMessage().contains("cancelled"), "Cancellation is explicit");
            check(interrupted.get(), "Controller preserves caller interruption");
        } finally { worker.interrupt(); worker.join(10_000); }
    }

    private static final class Fixture implements AutoCloseable {
        final MacConnectionBackend real;
        final AppState state = new AppState();
        final PerRouteController controller;
        final CountDownLatch entered = new CountDownLatch(1);
        volatile String pause;
        int pauseSkips;
        int failStarts;
        int starts;

        Fixture(XrayRuntime runtime, Path data, RealCoreIntegrationTests.LocalServer first, RealCoreIntegrationTests.LocalServer second) {
            this(runtime, data, first, second, false);
        }
        Fixture(XrayRuntime runtime, Path data, RealCoreIntegrationTests.LocalServer first, RealCoreIntegrationTests.LocalServer second, boolean liveRouteValidation) {
            real = new MacConnectionBackend(runtime, data);
            state.setConnectionMode(ConnectionMode.BROWSER_PROXY);
            state.nodes().addAll(List.of(first.node(), second.node()));
            state.setSelectedNodeKey(first.node().key());
            var fault = (ConnectionBackend) java.lang.reflect.Proxy.newProxyInstance(ConnectionBackend.class.getClassLoader(), new Class<?>[]{ConnectionBackend.class}, (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals(pause) && pauseSkips-- <= 0) {
                    pause = null;
                    entered.countDown();
                    Thread.sleep(60_000);
                    throw new AssertionError("Cancellation fixture was not interrupted");
                }
                try {
                    if (name.equals("start")) {
                        starts++;
                        if (failStarts > 0) {
                            failStarts--;
                            try (var occupied = occupy(AppProxyLauncher.HTTP_PORT)) { return method.invoke(real, args); }
                        }
                    }
                    return method.invoke(real, args);
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
            var preferences = MacosDesktopTests.preferences();
            // Enable runtime control for this case; isolated diagnostics still prevent external probes.
            preferences.putBoolean("v3.routeMonitoring", liveRouteValidation);
            controller = new PerRouteController(state, AppStateStore.inMemory(), MacosDesktopTests.isolated(fault), preferences);
        }
        void connect() {
            var result = controller.connect();
            check(result.getOk(), "Connect succeeds: " + result.getMessage());
        }
        void disconnected() {
            check(!controller.isConnected(), "Controller reports disconnected");
            check(controller.connectionPhase() == ConnectionPhase.DISCONNECTED, "Phase is disconnected");
            check(!real.isRunning(), "No owned core remains running");
        }
        void restored(RealCoreIntegrationTests.LocalServer previous) throws Exception {
            check(controller.isConnected() && controller.connectionPhase() == ConnectionPhase.CONNECTED, "Previous connection remains ready");
            check(state.selectedNodeKey().equals(previous.node().key()), "Previous server stays selected");
            check(MacosDesktopTests.request().endsWith("server-" + previous.name), "Traffic reaches the previous local server");
        }
        @Override public void close() {
            controller.close();
            check(!real.isRunning(), "Close reaps the owned core after each failure case");
        }
    }
    private static ServerSocket occupy(int port) throws Exception { return new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1")); }
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
