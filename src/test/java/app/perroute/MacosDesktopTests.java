package app.perroute;

import app.perroute.backend.api.*;
import app.perroute.compose.controller.PerRouteController;
import app.perroute.model.*;
import app.perroute.persistence.AppStateStore;
import app.perroute.platform.macos.*;
import app.perroute.service.AppProxyLauncher;
import app.perroute.service.XrayRuntime;

import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public final class MacosDesktopTests {
    private static int assertions;
    private static final Set<String> FORBIDDEN = Set.of("stopOrphan", "applySystemProxy", "restoreSystemProxy",
            "applyPerApp", "restorePerApp", "stopPerAppSidecar", "installCore", "installPerApp",
            "checkForUpdate", "downloadUpdate", "launchUpdate", "startGateway", "setGatewayLimits",
            "runningApplications", "installedApplications");
    private static final List<String> attempted = new CopyOnWriteArrayList<>();

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        capabilityGuards();
        realLocalLifecycle(root);
        assertIsolated();
        System.out.println("macOS desktop checks passed (" + assertions + " assertions)");
        System.exit(0);
    }

    static MemoryPreferences preferences() {
        var p = new MemoryPreferences();
        p.putBoolean("v3.initialized", true);
        for (String key : List.of("v3.qualityBackground", "v3.routeMonitoring", "v3.trafficGateway", "autoReconnect", "autoFailover", "autoSpeedTest")) p.putBoolean(key, false);
        return p;
    }

    /** Make any accidental platform mutation observable even if the controller catches its exception. */
    static ConnectionBackend isolated(ConnectionBackend delegate) {
        return (ConnectionBackend) java.lang.reflect.Proxy.newProxyInstance(ConnectionBackend.class.getClassLoader(), new Class<?>[]{ConnectionBackend.class}, (proxy, method, args) -> {
            String name = method.getName();
            if (FORBIDDEN.contains(name)) {
                attempted.add(name);
                throw new AssertionError("Forbidden operation in local preview: " + name);
            }
            if (name.equals("capabilities")) return BackendCapabilities.macosLocalPreview();
            if (name.equals("checkTunnel")) return new TunnelStatus(true, "Loopback test");
            if (name.equals("lookupPublicAddress")) return new RouteObservation("127.0.0.1", null, null);
            if (name.equals("systemDnsServers")) return List.of();
            if (name.equals("publicResolver")) return null;
            try { return method.invoke(delegate, args); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        });
    }

    private static void capabilityGuards() throws Exception {
        String previous = System.getProperty("perroute.data");
        Path directory;
        try (var session = MacPreviewSession.open()) {
            directory = session.directory();
            check(Files.getPosixFilePermissions(directory).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")), "Preview directory is owner-only");
            var state = new AppState();
            state.nodes().add(node("one", 15001));
            state.setSelectedNodeKey("one");
            state.setConnectionMode(ConnectionMode.SYSTEM_PROXY);
            state.profiles().add(new ConnectionProfile("foreign", "Windows", List.of(), "one", ConnectionMode.NATIVE_PER_APP, List.of()));
            var store = AppStateStore.inMemory();
            try (var controller = new PerRouteController(state, store, isolated(new ControllerTests.FakeBackend()), preferences())) {
                check(!controller.connect().getOk(), "An unsupported saved mode is refused");
                controller.setMode(ConnectionMode.NATIVE_PER_APP);
                check(controller.connectionMode() == ConnectionMode.SYSTEM_PROXY, "Unsupported selection does not overwrite saved mode");
                check(!controller.applyProfile("foreign").getOk(), "Unsupported profile is rejected before mutation");
                check(state.connectionMode() == ConnectionMode.SYSTEM_PROXY, "Rejected profile leaves state intact");
                check(!controller.setAdvancedRouting(true).getOk(), "Advanced per-app routing is unavailable");
                controller.setKillSwitch(true);
                check(!controller.killSwitch(), "Per-app kill switch remains unavailable");
                check(!controller.installCore(text -> kotlin.Unit.INSTANCE).getOk(), "Windows core installation is blocked");
                check(!controller.installPerApp(text -> kotlin.Unit.INSTANCE).getOk(), "Driver installation is blocked");
                check(!controller.checkForUpdate().getOk(), "Updater is blocked");
                controller.setMode(ConnectionMode.BROWSER_PROXY);
                check(controller.connect().getOk(), "Local fake-backed connection remains usable");
                check(controller.disconnect().getOk(), "Local fake-backed disconnect succeeds");
            }
            check(store.load().selectedNodeKey().equals("one"), "Memory-only store round-trips state");
            try (var paths = Files.walk(directory)) {
                check(paths.noneMatch(p -> p.getFileName().toString().endsWith(".key") || p.getFileName().toString().equals("state.bin")), "No fallback encryption key or persistent state is written");
            }
        }
        check(!Files.exists(directory), "Normal close removes the preview directory");
        check(Objects.equals(previous, System.getProperty("perroute.data")), "Preview restores the prior data property");
    }

    private static void realLocalLifecycle(Path root) throws Exception {
        try (var session = MacPreviewSession.open()) {
            Path directory = session.directory();
            var runtime = XrayRuntime.forPlatform(System.getProperty("os.name"), System.getProperty("os.arch"), root);
            var real = new MacConnectionBackend(runtime, directory);
            check(real.coreReady(), "Native Darwin core is ready");
            try (var first = new RealCoreIntegrationTests.LocalServer("one", runtime.executable(), directory);
                 var second = new RealCoreIntegrationTests.LocalServer("two", runtime.executable(), directory)) {
                var state = new AppState();
                state.setConnectionMode(ConnectionMode.BROWSER_PROXY);
                state.nodes().addAll(List.of(first.node(), second.node()));
                state.setSelectedNodeKey(first.node().key());
                var logs = new CopyOnWriteArrayList<String>();
                try (var controller = new PerRouteController(state, AppStateStore.inMemory(), isolated(real), preferences())) {
                    controller.onLog(text -> { logs.add(text); return kotlin.Unit.INSTANCE; });
                    var connected = controller.connect();
                    check(connected.getOk(), "Connect succeeds: " + connected.getMessage());
                    check(request().endsWith("server-one"), "SOCKS request reaches the first local server");
                    var switched = controller.switchNode(second.node().key());
                    check(switched.getOk(), "Switch succeeds: " + switched.getMessage());
                    check(request().endsWith("server-two"), "New requests reach the second local server");
                    check(controller.disconnect().getOk(), "Disconnect succeeds");
                    check(!real.isRunning(), "Disconnect stops only the owned client core");
                    check(first.process.isAlive() && second.process.isAlive(), "Other Xray processes remain alive");
                    check(controller.connect().getOk(), "Reconnect succeeds");
                } catch (Exception | AssertionError error) {
                    logs.forEach(System.err::println);
                    throw error;
                }
                check(!real.isRunning(), "Controller close stops its owned client core");
                check(first.process.isAlive() && second.process.isAlive(), "Controller close does not kill other Xray processes");
            }
        }
    }

    private static ProxyNode node(String key, int port) {
        return new ProxyNode(key, key, Protocol.VLESS, "127.0.0.1", port, UUID.randomUUID().toString(), Map.of("security", "none"), "");
    }
    static String request() throws Exception {
        try (Socket socket = new Socket(new java.net.Proxy(java.net.Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", AppProxyLauncher.SOCKS_PORT)))) {
            // The controlled VLESS servers redirect this public destination to local origins.
            // Reserved test ranges match upstream geoip:private and would bypass the proxy.
            // A dotted Host avoids the upstream rule that sends single-label LAN names directly.
            socket.connect(new InetSocketAddress("1.1.1.1", 80), 3_000);
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: integration.test\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            var input = socket.getInputStream();
            var header = new java.io.ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4) {
                int value = input.read();
                if (value < 0) throw new java.io.EOFException("Incomplete local HTTP response");
                header.write(value);
                matched = value == '\r' || value == '\n' ? matched + 1 : 0;
            }
            int length = header.toString(StandardCharsets.US_ASCII).lines()
                    .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                    .mapToInt(line -> Integer.parseInt(line.substring(15).trim())).findFirst().orElseThrow();
            return new String(input.readNBytes(length), StandardCharsets.US_ASCII);
        }
    }
    static void assertIsolated() {
        check(attempted.isEmpty(), "Controller must not call unavailable backend operations: " + attempted);
    }
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
