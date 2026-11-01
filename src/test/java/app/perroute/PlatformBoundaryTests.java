package app.perroute;

import app.perroute.platform.bootstrap.PlatformBootstrap;
import app.perroute.platform.windows.WindowsConnectionBackend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Portable regression entry point; no native engine, installer or OS networking changes are invoked. */
public final class PlatformBoundaryTests {
    private static int assertions;

    static int assertions() { return assertions; }

    public static void main(String[] args) throws Exception {
        runPlatformSelection();
        runDiagnosticRouting();
        runGatewayAdapter();
        ControllerTests.runPlatformBoundaryChecks();
        System.out.println("Platform boundary tests passed (" + (assertions + ControllerTests.assertions()) + " assertions)");
        System.exit(0);
    }

    static void runPlatformSelection() throws Exception {
        Path directory = Files.createTempDirectory("perroute-platform-");
        Path data = directory.resolve("not-created");
        try {
            var windows = PlatformBootstrap.forOs("Windows 11", data);
            check(windows.connections() instanceof WindowsConnectionBackend, "Windows selects the existing adapter");
            check(windows.connections() == windows.recovery(), "Connection and recovery use the same provider");
            check(!Files.exists(data), "Provider selection must not create data or inspect the host");
            for (String os : new String[] {"Linux", "FreeBSD", "", "notwindows", null}) {
                try {
                    PlatformBootstrap.forOs(os, data);
                    throw new AssertionError("Unsupported OS selected a backend: " + os);
                } catch (UnsupportedOperationException expected) {
                    check(expected.getMessage().contains("does not yet support"), "Unsupported status is explicit");
                }
            }
            for (String mac : new String[] {"Darwin", "Mac OS X"}) {
                var preview = PlatformBootstrap.forOs(mac, data);
                check(preview.connections() instanceof app.perroute.platform.macos.MacConnectionBackend, "macOS selects its local adapter");
                check(!preview.connections().capabilities().networkRecovery(), "macOS never enables network recovery");
                check(!Files.exists(data), "macOS provider selection must not create state");
            }
            check(!Files.exists(data), "Unsupported hosts must not create platform state");
            String previousOs = System.getProperty("os.name");
            String previousData = System.getProperty("perroute.data");
            var previousError = System.err;
            try {
                System.setProperty("os.name", "Unsupported test OS");
                System.setProperty("perroute.data", data.toString());
                var output = new java.io.ByteArrayOutputStream();
                System.setErr(new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8));
                for (String[] mode : new String[][] { {}, {"--guard", "1"}, {"--recover"} }) {
                    output.reset();
                    app.perroute.compose.ComposeMainKt.main(mode);
                    check(output.toString(java.nio.charset.StandardCharsets.UTF_8).contains("does not yet support"),
                            "Every startup mode rejects unsupported hosts before native operations");
                    check(!Files.exists(data), "Startup must reject unsupported hosts before a lock or state file");
                }
            } finally {
                System.setErr(previousError);
                restoreProperty("os.name", previousOs);
                restoreProperty("perroute.data", previousData);
            }
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    static void runGatewayAdapter() throws Exception {
        var backend = new WindowsConnectionBackend(Path.of("unused-gateway-test-data"));
        var events = new java.util.concurrent.LinkedBlockingQueue<app.perroute.gateway.GatewayConnection>();
        backend.addGatewayListener(events::add);
        check(!backend.gatewayRunning(), "Gateway starts stopped");
        check(backend.gatewayCounters().isEmpty(), "Stopped gateway has no application counters");
        var loopback = java.net.InetAddress.getLoopbackAddress();
        try (var upstream = new java.net.ServerSocket(0, 1, loopback);
             var reserved = new java.net.ServerSocket(0, 1, loopback)) {
            int port = reserved.getLocalPort();
            var route = new app.perroute.gateway.AppGateway.Route("test-app", 0, port, upstream.getLocalPort());
            try {
                backend.startGateway(java.util.List.of(route));
                throw new AssertionError("An occupied listener must fail gateway startup");
            } catch (java.io.IOException expected) {
                check(!backend.gatewayRunning(), "Failed startup leaves the adapter stopped");
            }
            reserved.close();
            backend.setGatewayLimits("test-app", 1024, 1024);
            backend.startGateway(java.util.List.of(route));
            check(backend.gatewayRunning(), "Adapter starts the shared Java gateway");
            var server = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try (var socket = upstream.accept()) {
                    socket.setSoTimeout(3000);
                    socket.getOutputStream().write(socket.getInputStream().read());
                } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            });
            try (var client = new java.net.Socket(loopback, port)) {
                client.setSoTimeout(3000);
                client.getOutputStream().write(42);
                check(client.getInputStream().read() == 42, "Adapter forwards loopback traffic through the gateway");
                var event = events.poll(3, java.util.concurrent.TimeUnit.SECONDS);
                check(event != null && event.applicationPath().equals("test-app"), "Gateway observations reach backend listeners");
                check(backend.gatewayCounters().containsKey("test-app"), "Application counters reach the backend caller");
                backend.setGatewayLimits("test-app", 0, 0);
            }
            server.get(3, java.util.concurrent.TimeUnit.SECONDS);
        } finally { backend.stopGateway(); }
        check(!backend.gatewayRunning(), "Adapter stops the gateway");
        check(backend.gatewayCounters().isEmpty(), "Stopping clears gateway counters");
    }

    static void runDiagnosticRouting() {
        var requests = new java.util.ArrayList<app.perroute.service.TunnelHttp.Route>();
        var diagnostics = new app.perroute.backend.core.NetworkDiagnostics((route, host, path, timeout) -> {
            requests.add(route);
            if (host.equals("www.cloudflare.com")) throw new java.io.IOException("Trace failed");
            return "203.0.113.7";
        });
        var proxied = diagnostics.lookupPublicAddress(17892);
        check("203.0.113.7".equals(proxied.ip()), "Fallback returns the observed address");
        check(requests.size() == 2 && requests.stream().allMatch(route -> route.proxyPort() == 17892),
                "A failed proxied lookup must keep its fallback on the same SOCKS route");
        requests.clear();
        diagnostics.lookupPublicAddress(null);
        check(requests.stream().allMatch(route -> route.proxyPort() == 0), "Direct lookup keeps both requests direct");
        var trace = new app.perroute.backend.core.NetworkDiagnostics((route, host, path, timeout) -> "ip=203.0.113.8\nloc=NL");
        check("NL".equals(trace.lookupPublicAddress(17892).country()), "Trace retains country metadata");
        var failed = new app.perroute.backend.core.NetworkDiagnostics((route, host, path, timeout) -> {
            throw new java.io.IOException("No network");
        }).lookupPublicAddress(17892);
        check(failed.ip() == null && "No network".equals(failed.error()), "Lookup failure carries its diagnostic error");
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name); else System.setProperty(name, value);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
