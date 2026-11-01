package app.perroute;

import app.perroute.compose.controller.ActiveRoute;
import app.perroute.backend.api.ConnectionBackend;
import app.perroute.compose.inspector.InspectorRow;
import app.perroute.compose.controller.PerRouteController;
import app.perroute.compose.inspector.RouteKind;
import app.perroute.backend.api.RouteObservation;
import app.perroute.platform.windows.WindowsConnectionBackend;
import app.perroute.gateway.AppGateway;
import app.perroute.gateway.GatewayConnection;
import app.perroute.model.AppRoutes;
import app.perroute.model.AppState;
import app.perroute.model.BandwidthPolicy;
import app.perroute.model.ConnectionMode;
import app.perroute.model.Protocol;
import app.perroute.model.ProxyNode;
import app.perroute.persistence.AppStateStore;
import app.perroute.quality.QualitySample;
import app.perroute.routing.RouteHealthTracker;
import app.perroute.service.InstalledApplicationDiscovery;
import app.perroute.service.PortOffset;
import app.perroute.service.ProcessDiscovery;
import app.perroute.service.ProxyHealthCheck;
import app.perroute.service.XrayConfigGenerator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

final class RealCoreIntegrationTests {
    private static int assertions;
    private RealCoreIntegrationTests() {}

    static int assertions() { return assertions; }

    static void runAll() throws Exception {
        Path xray = Path.of("tools", "xray", "xray.exe").toAbsolutePath();
        if (!Files.isRegularFile(xray)) {
            System.out.println("[skip] Real core integration: tools/xray/xray.exe is not present");
            return;
        }
        if (PortOffset.VALUE == 0) {
            System.out.println("[skip] Real core integration: run with -Dperroute.portOffset so the standard ports stay free");
            return;
        }
        System.out.println("[test] Real core: per-app routes, in-place switch, Auto failover, limits and inspector");
        new RealCoreIntegrationTests().scenario(xray);
    }

    static final class LocalServer implements AutoCloseable {
        final String name;
        final String uuid = UUID.randomUUID().toString();
        final ServerSocket http;
        final java.net.DatagramSocket echo;
        final int vlessPort;
        Process process;

        LocalServer(String name, Path xray, Path directory) throws Exception {
            this.name = name;
            this.http = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            this.echo = new java.net.DatagramSocket(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), http.getLocalPort()));
            this.vlessPort = freePort();
            Thread.ofVirtual().start(this::serveHttp);
            Thread.ofVirtual().start(this::serveEcho);
            Path config = directory.resolve("server-" + name + ".json");
            Files.writeString(config, """
                    {"log":{"loglevel":"warning"},
                     "inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"vless","settings":{"clients":[{"id":"%s"}],"decryption":"none"}}],
                     "outbounds":[{"protocol":"freedom","settings":{"redirect":"127.0.0.1:%d"}}]}
                    """.formatted(vlessPort, uuid, http.getLocalPort()));
            start(xray, config);
        }

        void start(Path xray, Path config) throws Exception {
            process = new ProcessBuilder(xray.toString(), "run", "-config", config.toString())
                    .directory(xray.getParent().toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                try (Socket probe = new Socket(InetAddress.getLoopbackAddress(), vlessPort)) { return; }
                catch (IOException notYet) { Thread.sleep(50); }
            }
            throw new IllegalStateException("Local server " + name + " did not start");
        }

        ProxyNode node() {
            return new ProxyNode("key-" + name, "Server " + name, Protocol.VLESS, "127.0.0.1", vlessPort, uuid,
                    Map.of("type", "tcp", "security", "none", "encryption", "none"), "");
        }

        private void serveHttp() {
            while (!http.isClosed()) {
                Socket client;
                try { client = http.accept(); } catch (IOException closed) { return; }
                Thread.ofVirtual().start(() -> {
                    try (client) {
                        InputStream in = client.getInputStream();
                        OutputStream out = client.getOutputStream();
                        while (true) {
                            String head = readHead(in);
                            if (head == null) return;
                            byte[] body = head.startsWith("GET /big") ? new byte[1_536 * 1024] : ("server-" + name).getBytes(StandardCharsets.US_ASCII);
                            boolean headOnly = head.startsWith("HEAD");
                            out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\nConnection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                            if (!headOnly) out.write(body);
                            out.flush();
                        }
                    } catch (IOException ignored) { }
                });
            }
        }

        private void serveEcho() {
            byte[] buffer = new byte[2048];
            while (!echo.isClosed()) {
                try {
                    java.net.DatagramPacket packet = new java.net.DatagramPacket(buffer, buffer.length);
                    echo.receive(packet);
                    echo.send(new java.net.DatagramPacket(packet.getData(), packet.getLength(), packet.getSocketAddress()));
                } catch (IOException closed) { return; }
            }
        }

        void kill() { if (process != null) process.destroyForcibly(); }

        @Override public void close() throws IOException {
            kill();
            http.close();
            echo.close();
        }
    }

    private static final class LoopbackBackend implements ConnectionBackend {
        @Override public app.perroute.backend.api.BackendCapabilities capabilities() { return app.perroute.backend.api.BackendCapabilities.windows(); }
        @Override public void installCore(java.util.function.Consumer<String> progress) { throw new UnsupportedOperationException("Fixture"); }
        @Override public void installPerApp(java.util.function.Consumer<String> progress) { throw new UnsupportedOperationException("Fixture"); }
        @Override public app.perroute.backend.api.UpdateDescriptor checkForUpdate(String feed) { throw new UnsupportedOperationException("Fixture"); }
        @Override public java.nio.file.Path downloadUpdate(app.perroute.backend.api.UpdateDescriptor update, java.util.function.Consumer<Integer> progress) { throw new UnsupportedOperationException("Fixture"); }
        @Override public void launchUpdate(java.nio.file.Path installer) { throw new UnsupportedOperationException("Fixture"); }

        final WindowsConnectionBackend real = new WindowsConnectionBackend(app.perroute.service.AppDirectories.dataDirectory());
        volatile int starts;

        @Override public boolean coreReady() { return true; }
        @Override public boolean perAppReady() { return true; }
        @Override public boolean elevated() { return false; }
        @Override public void validate(String config, boolean tun) throws Exception { real.validate(config, tun); }
        @Override public void start(String config, boolean tun) throws Exception { starts++; real.start(config, tun); }
        @Override public void stop() { real.stop(); }
        @Override public boolean isRunning() { return real.isRunning(); }
        @Override public void stopOrphan() { }
        @Override public void addLogListener(Consumer<String> listener) { real.addLogListener(listener); }
        @Override public void addRunningListener(Consumer<Boolean> listener) { real.addRunningListener(listener); }
        @Override public void applyPerApp(List<String> applications) { }
        @Override public void restorePerApp() { }
        @Override public void stopPerAppSidecar() { }
        @Override public void applySystemProxy() { }
        @Override public void restoreSystemProxy() { }
        @Override public app.perroute.backend.api.TunnelStatus checkTunnel(int socksPort) { return real.checkTunnel(socksPort); }
        @Override public Map<String, Long> readInboundCounters() throws Exception { return real.readInboundCounters(); }
        @Override public RouteObservation lookupPublicAddress(Integer socksPort) { return new RouteObservation(null, null, "not used"); }
        @Override public List<String> systemDnsServers() { return List.of(); }
        @Override public String publicResolver() { return null; }
        @Override public List<app.perroute.backend.api.ApplicationDescriptor> installedApplications() { return List.of(); }
        @Override public List<app.perroute.backend.api.ApplicationDescriptor> runningApplications() { return List.of(); }
        @Override public void applyRuntimeChange(XrayConfigGenerator.RuntimeChange change, String newTag, String oldTag, String config) throws Exception {
            real.applyRuntimeChange(change, newTag, oldTag, config);
        }
        @Override public void applyRuntimePlan(XrayConfigGenerator.RuntimePlan plan) throws Exception { real.applyRuntimePlan(plan); }
        @Override public app.perroute.backend.api.IsolatedTunnel startIsolatedCore(String config, int socksPort) { return real.startIsolatedCore(config, socksPort); }
        @Override public void startGateway(List<AppGateway.Route> routes) throws Exception { real.startGateway(routes); }
        @Override public void stopGateway() { real.stopGateway(); }
        @Override public void setGatewayLimits(String path, long down, long up) { real.setGatewayLimits(path, down, up); }
        @Override public Map<String, AppGateway.Counters> gatewayCounters() { return real.gatewayCounters(); }
        @Override public boolean gatewayRunning() { return real.gatewayRunning(); }
        @Override public void addGatewayListener(Consumer<GatewayConnection> listener) { real.addGatewayListener(listener); }
    }

    private void scenario(Path xray) throws Exception {
        Path directory = Files.createTempDirectory("perroute-real-");
        System.setProperty("perroute.data", directory.resolve("data").toString());
        List<LocalServer> servers = new ArrayList<>();
        PerRouteController controller = null;
        LoopbackBackend backend = new LoopbackBackend();
        List<String> logs = new CopyOnWriteArrayList<>();
        try {
            for (String name : List.of("a", "b", "c", "d")) servers.add(new LocalServer(name, xray, directory));
            LocalServer a = servers.get(0), b = servers.get(1), c = servers.get(2), d = servers.get(3);
            String browser = Files.writeString(directory.resolve("Browser.exe"), "x").toString();
            String chat = Files.writeString(directory.resolve("Chat.exe"), "x").toString();
            String game = Files.writeString(directory.resolve("Game.exe"), "x").toString();

            AppState state = new AppState();
            servers.forEach(server -> state.nodes().add(server.node()));
            state.setSelectedNodeKey(a.node().key());
            state.setConnectionMode(ConnectionMode.NATIVE_PER_APP);
            state.appPaths().addAll(List.of(browser, chat, game));
            var preferences = new ControllerTests.MemoryPreferences();
            preferences.putBoolean("autoFailover", false);
            preferences.putBoolean("autoReconnect", false);
            var policy = new RouteHealthTracker.Policy(60_000L, 200L, 400L, 3, 2, 5_000L);
            PerRouteController live = new PerRouteController(state, new AppStateStore(directory.resolve("data").resolve("state.bin")),
                    backend, preferences, policy, 60_000L);
            controller = live;
            live.onLog(line -> { logs.add(line); return kotlin.Unit.INSTANCE; });
            check(live.trafficGateway() && live.routeMonitoring(), "A fresh installation uses the gateway and route monitoring");

            seed(live, b, 15);
            seed(live, c, 60);
            seed(live, d, 90);
            check(live.saveApplicationSetup(List.of(browser, chat, game), Map.of(game, c.node().key()), true,
                    Set.of(chat), Map.of()).getOk(), "Save routes");
            var connected = live.connect();
            check(connected.getOk(), "Connect with the real core: " + connected.getMessage());
            check(backend.real.gatewayRunning(), "The real gateway is listening");
            equal("server-a", request(0));
            equal("server-b", request(1));
            equal("server-c", request(2));
            waitFor(() -> live.routeHealth().stream().allMatch(view -> view.getHealth() != null
                    && view.getHealth().state() == RouteHealthTracker.State.HEALTHY), 30_000);

            try (KeepAlive chatConnection = new KeepAlive(XrayConfigGenerator.perAppPort(1))) {
                equal("server-b", chatConnection.get("/"));
                int starts = backend.starts;
                var switched = live.switchNode(d.node().key());
                check(switched.getOk(), "Switch the main server: " + switched.getMessage());
                equal(starts, backend.starts);
                check(logs.stream().anyMatch(line -> line.contains("Custom and Auto routes kept running")), "The switch happened in place");
                equal("server-d", request(0));
                equal("server-b", chatConnection.get("/"));
                equal("server-c", request(2));
                System.out.println("    main switched A -> D in place; Chat's open connection kept working");
            }

            int starts = backend.starts;
            b.kill();
            live.retryRoute(autoRoute(live, chat).getId());
            try {
                waitFor(() -> !autoRoute(live, chat).getNode().key().equals(b.node().key()), 90_000);
            } catch (AssertionError timeout) {
                logs.stream().filter(line -> !line.contains(" accepted ") && !line.contains("[Info]")).forEach(line -> System.out.println("      log: " + line));
                throw timeout;
            }
            String moved = autoRoute(live, chat).getNode().name().replace("Server ", "");
            equal(starts, backend.starts);
            equal("server-" + moved, request(1));
            equal("server-d", request(0));
            equal("server-c", request(2));
            check(logs.stream().anyMatch(line -> line.contains("Auto moved") && line.contains("Other routes were not touched")), "The failover is logged");
            System.out.println("    server B stopped; Chat failed over to " + moved.toUpperCase() + " in place, Browser and Game unaffected");

            live.setBandwidthPolicy(browser, new BandwidthPolicy(4_000_000, 0));
            long started = System.nanoTime();
            int size = download(0, "/big");
            double seconds = (System.nanoTime() - started) / 1e9;
            double rate = size / seconds;
            System.out.printf("    Browser limited to 4 Mbps (500 KB/s): %d KB in %.2f s = %.0f KB/s%n", size / 1024, seconds, rate / 1024);
            check(rate <= 500_000 * 1.2, "The limit must hold end to end: " + rate);
            check(rate >= 500_000 * 0.6, "The limit must not throttle far below the setting: " + rate);
            live.setBandwidthPolicy(browser, BandwidthPolicy.UNLIMITED);
            started = System.nanoTime();
            download(0, "/big");
            double unlimited = size / ((System.nanoTime() - started) / 1e9);
            System.out.printf("    unlimited: %.0f KB/s%n", unlimited / 1024);
            check(unlimited > rate * 2, "Removing the limit must speed the download up");

            request(1);
            waitFor(() -> live.inspectorRows().stream().anyMatch(row -> row.getApplicationPath().equals(chat)
                    && row.getObserved().getRouteLabel() != null), 10_000);
            InspectorRow chatRow = live.inspectorRows().stream()
                    .filter(row -> row.getApplicationPath().equals(chat) && row.getObserved().getRouteLabel() != null)
                    .findFirst().orElseThrow();
            equal("integration.test", chatRow.getDestinationHost());
            equal(RouteKind.PROXY, chatRow.getObserved().getKind());
            equal("Server " + moved, chatRow.getObserved().getRouteLabel());
            equal("Server " + moved, chatRow.getConfigured().getRouteLabel());
            check(chatRow.getViaGateway() && chatRow.getDownloadBytes() > 0, "Gateway rows carry byte counts");
            System.out.println("    inspector: Chat -> integration.test observed on " + chatRow.getObserved().getRouteLabel()
                    + " via outbound " + chatRow.getObserved().getOutboundTag());

            try (KeepAlive gameConnection = new KeepAlive(XrayConfigGenerator.perAppPort(2))) {
                equal("server-c", gameConnection.get("/"));
                int before = backend.starts;
                var temporary = live.setTemporaryRoute(browser, c.node().key(), 30);
                check(temporary.getOk(), "Start a temporary server: " + temporary.getMessage());
                equal(before, backend.starts);
                equal("server-c", request(0));
                equal("server-c", gameConnection.get("/"));
                check(live.activeRoutes().stream().anyMatch(route -> route.getId().startsWith(app.perroute.service.RoutePlanner.TEMPORARY_ROUTE_PREFIX)),
                        "The temporary route is a route of its own");
                var ended = live.cancelTemporaryRoute(browser);
                check(ended.getOk(), "End the temporary server: " + ended.getMessage());
                equal(before, backend.starts);
                equal("server-d", request(0));
                equal("server-c", gameConnection.get("/"));
                System.out.println("    temporary server for Browser started and ended in place; Game's open connection kept working");
            }

            var gaming = live.gamingTest(List.of(c.node()), () -> false, (node, score) -> kotlin.Unit.INSTANCE).get(c.node().key());
            check(gaming != null && gaming.udpSupported() && gaming.lossPercent() == 0 && gaming.score() > 0,
                    "The gaming test must measure TCP and UDP through the real core: " + (gaming == null ? "no result" : gaming.note()));
            System.out.printf("    gaming test through server C: score %d, %d ms, jitter %d ms, UDP %s%n", gaming.score(), gaming.latencyMillis(), gaming.jitterMillis(),
                    gaming.udpSupported() ? "yes" : "no");

            live.disconnect();
            check(!backend.real.gatewayRunning() && !backend.real.isRunning(), "Disconnect stops the gateway and the core");
            try (ServerSocket free = new ServerSocket(XrayConfigGenerator.perAppPort(0), 1, InetAddress.getLoopbackAddress())) {
                check(free.isBound(), "The gateway port is released");
            }
        } finally {
            if (controller != null) controller.close();
            for (LocalServer server : servers) try { server.close(); } catch (Exception ignored) { }
            System.clearProperty("perroute.data");
            V3DomainTests.deleteTree(directory);
        }
    }

    private static void seed(PerRouteController controller, LocalServer server, long latency) {
        long now = System.currentTimeMillis();
        for (int i = 6; i >= 1; i--) {
            controller.recordQualitySample(server.node().key(), QualitySample.reachability(now - i * 30_000L, latency));
            controller.recordQualitySample(server.node().key(), QualitySample.tunnel(now - i * 30_000L + 500, true, latency * 3, ""));
        }
    }

    private static ActiveRoute autoRoute(PerRouteController controller, String path) {
        return controller.activeRoutes().stream()
                .filter(route -> route.getAuto() && AppRoutes.normalize(route.getApplications().get(0)).equals(AppRoutes.normalize(path)))
                .findFirst().orElseThrow();
    }

    private static Socket open(int index) throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), XrayConfigGenerator.perAppPort(index));
        socket.setSoTimeout(15_000);
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(new byte[]{5, 1, 0});
        in.readNBytes(2);
        byte[] host = "integration.test".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.writeBytes(new byte[]{5, 1, 0, 3, (byte) host.length});
        request.writeBytes(host);
        request.writeBytes(new byte[]{0, 80});
        out.write(request.toByteArray());
        byte[] reply = in.readNBytes(10);
        if (reply.length < 2 || reply[1] != 0) throw new IOException("SOCKS refused");
        return socket;
    }

    private static String request(int index) throws IOException {
        try (Socket socket = open(index)) {
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: integration.test\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            return new String(body(socket.getInputStream()), StandardCharsets.US_ASCII);
        }
    }

    private static int download(int index, String path) throws IOException {
        try (Socket socket = open(index)) {
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: integration.test\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            return body(socket.getInputStream()).length;
        }
    }

    private static final class KeepAlive implements AutoCloseable {
        final Socket socket;
        KeepAlive(int port) throws IOException { socket = open(port - XrayConfigGenerator.PER_APP_PORT_BASE); }
        String get(String path) throws IOException {
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: integration.test\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            return new String(body(socket.getInputStream()), StandardCharsets.US_ASCII);
        }
        @Override public void close() throws IOException { socket.close(); }
    }

    private static byte[] body(InputStream in) throws IOException {
        String head = readHead(in);
        if (head == null) throw new IOException("No response");
        int length = 0;
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
        }
        return in.readNBytes(length);
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0, value;
        while ((value = in.read()) >= 0) {
            head.write(value);
            matched = (value == '\r' || value == '\n') ? matched + 1 : 0;
            if (matched == 4) return head.toString(StandardCharsets.US_ASCII);
        }
        return head.size() == 0 ? null : head.toString(StandardCharsets.US_ASCII);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        check(condition.getAsBoolean(), "Timed out waiting for a condition");
    }

    private static void equal(Object expected, Object actual) {
        assertions++;
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("Expected <" + expected + "> but got <" + actual + ">");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
