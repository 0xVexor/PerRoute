package app.perroute;

import app.perroute.backend.api.ConnectionBackend;
import app.perroute.backend.api.RouteObservation;
import app.perroute.backend.api.TunnelStatus;
import app.perroute.compose.controller.ActiveRoute;
import app.perroute.compose.controller.PerRouteController;
import app.perroute.model.*;
import app.perroute.persistence.AppStateStore;
import app.perroute.platform.macos.MacConnectionBackend;
import app.perroute.platform.macos.MacApplicationCatalog;
import app.perroute.platform.macos.MacPreviewSession;
import app.perroute.platform.macos.proxy.HelperSystemProxyLifecycle;
import app.perroute.platform.macos.proxy.MacSystemProxyLifecycle;
import app.perroute.platform.macos.routing.MacTunSessionClient;
import app.perroute.quality.QualitySample;
import app.perroute.routing.RouteHealthTracker;
import app.perroute.service.XrayConfigGenerator;
import app.perroute.service.XrayRuntime;
import app.perroute.util.Json;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Real Darwin Xray/controller traffic with simulated capture. VLESS servers redirect to local HTTP
 * fixtures, and a test-only freedom sink traps direct traffic locally. The synthetic numeric
 * destination avoids the normal private-network bypass without contacting a public endpoint.
 */
public final class MacAdvancedRoutingTests {
    private static int checks;

    public static void main(String[] args) throws Exception {
        var runtime = XrayRuntime.forPlatform(System.getProperty("os.name"), System.getProperty("os.arch"), Path.of(args[0]));
        Path xray = runtime.requireExecutable(false);
        try (var storage = MacPreviewSession.open(); var direct = new DirectSink()) {
            Path directory = storage.directory().toRealPath();
            try (var a = new RealCoreIntegrationTests.LocalServer("a", xray, directory);
                 var b = new RealCoreIntegrationTests.LocalServer("b", xray, directory);
                 var c = new RealCoreIntegrationTests.LocalServer("c", xray, directory);
                 var d = new RealCoreIntegrationTests.LocalServer("d", xray, directory)) {
                scenario(runtime, directory, direct, a, b, c, d);
            }
        }
        System.out.println("macOS advanced routing: " + checks + " checks passed; real Darwin Xray/gateway/controller, simulated helper; native capture NOT tested");
        System.exit(0);
    }

    private static void scenario(XrayRuntime runtime, Path directory, DirectSink direct, RealCoreIntegrationTests.LocalServer a,
            RealCoreIntegrationTests.LocalServer b, RealCoreIntegrationTests.LocalServer c,
            RealCoreIntegrationTests.LocalServer d) throws Exception {
        String browser = executable(directory, "Browser"), chat = executable(directory, "Chat"), game = executable(directory, "Game");
        var peer = new Peer();
        Path marker = directory.resolve("routing.pending");
        var proxy = new MacSystemProxyLifecycle() {
            @Override public void apply() { throw new AssertionError("No host proxy changes in this fixture"); }
            @Override public void restore() { }
        };
        var catalog = new MacApplicationCatalog(operation -> Json.stringify(Map.of("version", 1, "applications", List.of(), "warnings", List.of())));
        var backend = new MacConnectionBackend(runtime, directory.resolve("core"), true, proxy, new MacTunSessionClient(peer, marker), catalog);
        var starts = new AtomicInteger();
        var mutations = new AtomicInteger();
        var isolated = (ConnectionBackend) java.lang.reflect.Proxy.newProxyInstance(ConnectionBackend.class.getClassLoader(),
                new Class<?>[]{ConnectionBackend.class}, (unused, method, values) -> {
                    // A mistaken direct route must hit a local failure marker, never a public destination.
                    // Keep generated routing rules intact and change only the fixture's freedom destination.
                    if (method.getName().equals("validate") || method.getName().equals("start"))
                        values[0] = direct.redirect((String) values[0]);
                    if (method.getName().equals("applyRuntimeChange")) values[3] = direct.redirect((String) values[3]);
                    switch (method.getName()) {
                        case "start" -> starts.incrementAndGet();
                        case "applyRuntimeChange", "applyRuntimePlan" -> mutations.incrementAndGet();
                        case "checkTunnel" -> {
                            try (var probe = new Exchange((int) values[0], a.http.getLocalPort())) {
                                String response = probe.get("/");
                                return new TunnelStatus(response.startsWith("server-"), "Local fixture response");
                            } catch (IOException failure) { return new TunnelStatus(false, "Local fixture unavailable"); }
                        }
                        case "lookupPublicAddress" -> { return new RouteObservation(null, null, "Local fixture; no public address lookup"); }
                        case "systemDnsServers" -> { return List.of(); }
                        case "publicResolver" -> { return null; }
                    }
                    try { return method.invoke(backend, values); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
        var state = new AppState();
        state.setConnectionMode(ConnectionMode.NATIVE_PER_APP);
        state.appPaths().addAll(List.of(browser, chat, game));
        for (var server : List.of(a, b, c, d)) state.nodes().add(server.node());
        state.setSelectedNodeKey(a.node().key());
        var preferences = new ControllerTests.MemoryPreferences();
        preferences.putBoolean("v3.initialized", true);
        preferences.putBoolean("v3.routeMonitoring", true);
        preferences.putBoolean("v3.qualityBackground", false);
        preferences.putBoolean("autoFailover", false);
        preferences.putBoolean("autoReconnect", false);
        var policy = new RouteHealthTracker.Policy(60_000L, 100L, 200L, 2, 2, 1_000L);
        try (var controller = new PerRouteController(state, AppStateStore.inMemory(), isolated, preferences, policy, 60_000L)) {
            seed(controller, b.node(), 10); seed(controller, c.node(), 80); seed(controller, d.node(), 140);
            var saved = controller.saveApplicationSetup(state.appPaths(), Map.of(game, c.node().key()), true, Set.of(chat), Map.of());
            check(saved.getOk(), "Save dedicated and Auto selections: " + saved.getMessage());
            var connected = controller.connect();
            check(connected.getOk(), "Connect: " + connected.getMessage());
            equal(b.node().key(), auto(controller, chat).getNode().key());
            equal("server-a", request(0, a)); equal("server-b", request(1, a)); equal("server-c", request(2, a));
            check(Files.exists(marker) && peer.starts.size() == 1, "Capture intent belongs to one simulated helper generation");
            int initialStarts = starts.get(); long initialPid = peer.starts.getLast();
            try (var stable = new Exchange(XrayConfigGenerator.perAppPort(2), a.http.getLocalPort())) {
                equal("server-c", stable.get("/"));
                check(controller.switchNode(d.node().key()).getOk(), "Live main switch");
                equal(initialStarts, starts.get()); equal(initialPid, peer.starts.getLast());
                equal("server-d", request(0, a)); equal("server-b", request(1, a)); equal("server-c", stable.get("/"));
                check(mutations.get() > 0, "The actual runtime API changed the main outbound");
                b.kill();
                check(controller.retryRoute(auto(controller, chat).getId()).getOk(), "Check the failed Auto server");
                await(() -> !auto(controller, chat).getNode().key().equals(b.node().key()), "Auto moves away from the stopped server");
                String selected = auto(controller, chat).getNode().key();
                equal("server-" + selected.substring("key-".length()), request(1, a));
                equal("server-d", request(0, a)); equal("server-c", stable.get("/"));
                equal(initialStarts, starts.get()); equal(initialPid, peer.starts.getLast());
            }
            String dedicatedId = controller.activeRoutes().stream().filter(route -> !route.getMain() && !route.getAuto()).findFirst().orElseThrow().getId();
            String autoMarker = request(1, a);
            try (var stableAuto = new Exchange(XrayConfigGenerator.perAppPort(1), a.http.getLocalPort())) {
                equal(autoMarker, stableAuto.get("/"));
                check(controller.changeRouteServer(dedicatedId, a.node().key()).getOk(), "Replace a dedicated route");
                equal(autoMarker, stableAuto.get("/"));
            }
            equal("server-a", request(2, a)); equal("server-d", request(0, a));
            equal(initialStarts, starts.get());
            var profile = new ProxyNode("profile", "Local profile", Protocol.XRAY_PROFILE, "embedded", 1, "fixture",
                    Map.of("rawProfileJson", Json.stringify(Map.of("outbounds", List.of(Map.of(
                            "tag", "proxy", "protocol", "vless", "settings", Map.of("vnext", List.of(Map.of(
                                    "address", "127.0.0.1", "port", a.vlessPort, "users", List.of(Map.of("id", a.uuid, "encryption", "none")))))))))), "");
            state.nodes().add(profile);
            check(controller.switchNode(profile.key()).getOk(), "Connect a supported imported profile");
            equal("server-a", request(0, a)); equal("server-a", request(2, a));
            long profilePid = peer.starts.getLast();
            peer.refuseStarts = 1;
            check(!controller.switchNode(d.node().key()).getOk(), "Native resume refusal triggers complete connection rollback");
            check(controller.isConnected() && peer.starts.getLast() != profilePid, "Rollback rebinds the prior profile to its new owned core");
            equal(profile.key(), state.selectedNodeKey());
            equal("server-a", request(0, a)); equal("server-a", request(2, a));
            // Invalid imported outbounds must fail validation before mutating the running connection.
            var invalid = new ProxyNode("invalid", "Invalid fixture", Protocol.XRAY_PROFILE, "embedded", 1, "fixture",
                    Map.of("rawProfileJson", "{\"outbounds\":[{\"tag\":\"proxy\",\"protocol\":\"not-a-protocol\"}]}"), "");
            state.nodes().add(invalid);
            int beforeRejected = mutations.get();
            int beforeRejectedStarts = starts.get();
            check(!controller.switchNode(invalid.key()).getOk(), "Reject an incompatible imported profile");
            equal(beforeRejected, mutations.get()); equal(beforeRejectedStarts, starts.get());
            equal(profile.key(), state.selectedNodeKey());
            equal("server-a", request(0, a)); equal("server-a", request(2, a));
            countersAndLimits(controller, backend, browser, a);
            check(controller.disconnect().getOk(), "Disconnect");
            check(!backend.isRunning() && !backend.gatewayRunning() && !Files.exists(marker), "Disconnect clears owned resources and recovery marker");
            equal(0, direct.requests.get());
        } finally {
            backend.restorePerApp(); backend.stop(); backend.stopGateway();
        }
    }

    private static void countersAndLimits(PerRouteController controller, MacConnectionBackend backend,
            String browser, RealCoreIntegrationTests.LocalServer destination) throws Exception {
        await(() -> backend.gatewayCounters().values().stream().allMatch(counter -> counter.openConnections() == 0), "Finished requests release gateway connections");
        long before = backend.gatewayCounters().get(browser).downloadBytes();
        controller.setBandwidthPolicy(browser, new BandwidthPolicy(4_000_000, 0));
        long started = System.nanoTime();
        try (var socket = new Exchange(XrayConfigGenerator.perAppPort(0), destination.http.getLocalPort())) {
            equal(1_536 * 1024, socket.body("/big").length);
        }
        check(System.nanoTime() - started >= 2_000_000_000L, "Actual bytes obey the configured application download limit");
        long aggregateStarted = System.nanoTime();
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 2; i++) results.add(workers.submit(() -> {
                try (var socket = new Exchange(XrayConfigGenerator.perAppPort(0), destination.http.getLocalPort())) {
                    return socket.body("/big").length;
                }
            }));
            for (var result : results) equal(1_536 * 1024, result.get(15, java.util.concurrent.TimeUnit.SECONDS));
        }
        check(System.nanoTime() - aggregateStarted >= 4_000_000_000L, "Concurrent connections share the application's aggregate limit");
        await(() -> backend.gatewayCounters().get(browser).downloadBytes() > before + 1_536 * 1024, "Gateway accounts payload and headers under the exact identity");
        await(() -> controller.inspectorRows().stream().anyMatch(row -> row.getApplicationPath().equals(browser)), "Inspector correlates the exact application identity");
        controller.setBandwidthPolicy(browser, BandwidthPolicy.UNLIMITED);
    }

    private static ActiveRoute auto(PerRouteController controller, String identity) {
        return controller.activeRoutes().stream().filter(route -> route.getAuto() && route.getApplications().contains(identity)).findFirst().orElseThrow();
    }
    private static void seed(PerRouteController controller, ProxyNode node, long latency) {
        long now = System.currentTimeMillis();
        for (int i = 6; i >= 1; i--) {
            controller.recordQualitySample(node.key(), QualitySample.reachability(now - i * 30_000L, latency));
            controller.recordQualitySample(node.key(), QualitySample.tunnel(now - i * 30_000L + 500, true, latency * 3, "fixture"));
        }
    }
    private static String executable(Path directory, String name) throws IOException {
        Path file = Files.writeString(directory.resolve(name), "fixture; never executed");
        check(file.toFile().setExecutable(true, true), "Fixture executable permission");
        return ApplicationIdentity.macExecutable(file.toRealPath().toString());
    }
    private static String request(int index, RealCoreIntegrationTests.LocalServer destination) throws IOException {
        try (var exchange = new Exchange(XrayConfigGenerator.perAppPort(index), destination.http.getLocalPort())) { return exchange.get("/"); }
    }
    private static final class Exchange implements AutoCloseable {
        private final Socket socket;
        Exchange(int port, int destination) throws IOException {
            socket = new Socket(new java.net.Proxy(java.net.Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", port)));
            try {
                socket.connect(new InetSocketAddress("8.8.4.4", destination), 2_000);
                socket.setSoTimeout(6_000);
            } catch (IOException failure) { socket.close(); throw failure; }
        }
        String get(String path) throws IOException { return new String(body(path), StandardCharsets.US_ASCII); }
        byte[] body(String path) throws IOException {
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: 8.8.4.4\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            var head = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4 && head.size() < 8_192) {
                int next = socket.getInputStream().read();
                if (next < 0) throw new EOFException("No fixture response");
                head.write(next); matched = next == '\r' || next == '\n' ? matched + 1 : 0;
            }
            if (matched != 4) throw new IOException("Oversized fixture header");
            int length = -1;
            for (String line : head.toString(StandardCharsets.US_ASCII).split("\r\n"))
                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
            if (length < 0 || length > 2 * 1024 * 1024) throw new IOException("Invalid fixture response size");
            byte[] result = socket.getInputStream().readNBytes(length);
            if (result.length != length) throw new EOFException("Incomplete fixture payload");
            return result;
        }
        @Override public void close() throws IOException { socket.close(); }
    }
    private static final class DirectSink implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger();
        final com.sun.net.httpserver.HttpServer server;
        DirectSink() throws IOException {
            server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                byte[] body = "unexpected-direct".getBytes(StandardCharsets.US_ASCII);
                try (exchange) { exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); }
            });
            server.start();
        }
        @SuppressWarnings("unchecked")
        String redirect(String config) {
            var root = Json.object(config);
            for (Object raw : (List<?>) root.get("outbounds")) {
                var outbound = (Map<String, Object>) raw;
                if ("freedom".equals(outbound.get("protocol"))) outbound.put("settings", Map.of("redirect", "127.0.0.1:" + server.getAddress().getPort()));
            }
            return Json.stringify(root);
        }
        @Override public void close() { server.stop(0); }
    }
    private static final class Peer implements HelperSystemProxyLifecycle.Client {
        final List<Long> starts = new CopyOnWriteArrayList<>();
        int refuseStarts;
        String generation;
        @Override public Map<String, Object> request(String operation, Map<String, Object> values) throws IOException {
            return switch (operation) {
                case "prepare", "prepare-existing" -> Map.of();
                case "tun-capabilities" -> Map.of("protocol", 1, "endpointProtocol", 2, "euid", 0, "payloadAvailable", true);
                case "start-tun" -> {
                    if (refuseStarts > 0) { refuseStarts--; throw new IOException("Simulated native resume refusal"); }
                    long pid = ((Number) values.get("corePid")).longValue();
                    check(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "Helper receives a live owned core PID");
                    starts.add(pid); generation = UUID.randomUUID().toString(); yield state();
                }
                case "tun-status" -> state();
                case "stop-tun" -> Map.of("pending", false, "owned", false, "phase", "stopped");
                default -> throw new IOException("Unexpected helper operation");
            };
        }
        private Map<String, Object> state() { return Map.of("pending", true, "owned", true, "phase", "ready", "session", generation); }
    }
    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError(message);
            Thread.sleep(25);
        }
        checks++;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static void equal(Object expected, Object actual) { check(Objects.equals(expected, actual), "Expected " + expected + " but received " + actual); }
}
