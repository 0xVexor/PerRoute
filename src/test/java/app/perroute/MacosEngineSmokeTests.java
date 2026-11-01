package app.perroute;

import app.perroute.service.XrayConfigGenerator;
import app.perroute.service.XrayProcessManager;
import app.perroute.service.XrayRuntime;
import app.perroute.service.XrayStatsReader;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CopyOnWriteArrayList;

/** Actual Darwin engine, loopback traffic only. No host proxy, extension or orphan-process changes. */
public final class MacosEngineSmokeTests {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        XrayRuntime runtime = XrayRuntime.forPlatform(System.getProperty("os.name"),
                System.getProperty("os.arch"), Path.of(args[0]));
        if (runtime.target() == XrayRuntime.Target.WINDOWS_X64) {
            throw new IllegalStateException("This smoke task requires a native macOS engine");
        }
        runtime.requireExecutable(false);
        Path data = Files.createTempDirectory("perroute-mac-engine-");
        var states = new CopyOnWriteArrayList<Boolean>();
        var logs = new CopyOnWriteArrayList<String>();
        int socks = freePort();
        int http = freePort();
        String config = config(socks, http);
        try (XrayProcessManager process = new XrayProcessManager(runtime, data);
             ServerSocket origin = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            process.addStateListener(states::add);
            process.addLogListener(logs::add);
            Thread server = Thread.ofVirtual().start(() -> serve(origin));
            process.validate(config, false);
            check(!process.isRunning(), "Validation must not start the core");
            check(noPendingConfig(data), "Validation file must be removed");
            process.start(config, false);
            waitForPort(process, socks);
            waitForPort(process, http);
            check(request(socks, origin.getLocalPort(), true).endsWith("perroute-loopback"), "SOCKS relays to the local HTTP origin");
            check(request(http, origin.getLocalPort(), false).endsWith("perroute-loopback"), "HTTP proxy relays to the local HTTP origin");
            var counters = new XrayStatsReader(runtime).readInboundCounters();
            check(counters.getOrDefault("inbound>>>smoke-socks>>>traffic>>>downlink", 0L) > 0, "SOCKS counters come from the real engine");
            check(counters.getOrDefault("inbound>>>smoke-http>>>traffic>>>downlink", 0L) > 0, "HTTP counters come from the real engine");
            try {
                process.validate("invalid config", false);
                throw new AssertionError("Invalid configuration was accepted");
            } catch (IOException expected) {
                check(process.isRunning(), "Rejected validation preserves the active engine");
                check(noPendingConfig(data), "Rejected validation is cleaned up");
            }
            String outbound = "{\"outbounds\":[{\"tag\":\"replacement\",\"protocol\":\"freedom\",\"settings\":{}}]}";
            String routing = "{\"routing\":{\"rules\":[{\"type\":\"field\",\"inboundTag\":[\"perroute-api\"],\"outboundTag\":\"api\",\"ruleTag\":\"api-route\"},"
                    + "{\"type\":\"field\",\"inboundTag\":[\"smoke-http\",\"smoke-socks\"],\"outboundTag\":\"replacement\",\"ruleTag\":\"smoke-route\"}]}}";
            process.applyRuntimeChange(outbound, routing, "replacement", "direct", config.replace("\"direct\"", "\"replacement\""));
            check(request(http, origin.getLocalPort(), false).endsWith("perroute-loopback"), "Live API route replacement preserves relay");
            check(!Files.exists(data.resolve("runtime-outbound.json")) && !Files.exists(data.resolve("runtime-routing.json")), "API temporary files are removed");
            process.stop();
            check(!process.isRunning(), "Stop ends the owned process");
            check(states.contains(true) && states.contains(false), "Lifecycle listeners receive start and stop");
            try (ServerSocket released = new ServerSocket(socks, 1, InetAddress.getByName("127.0.0.1"))) {
                check(released.isBound(), "Stopped engine releases its listener");
            }
            origin.close();
            server.join(1_000);
            System.out.println("macOS engine smoke passed (" + assertions + " assertions), target=" + runtime.target());
        } catch (Exception | AssertionError error) {
            logs.forEach(System.err::println);
            throw error;
        } finally {
            try (var paths = Files.walk(data)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static String config(int socks, int http) {
        return """
                {"log":{"loglevel":"warning"},
                 "inbounds":[
                  {"listen":"127.0.0.1","port":%d,"protocol":"socks","tag":"smoke-socks","settings":{"auth":"noauth","udp":false}},
                  {"listen":"127.0.0.1","port":%d,"protocol":"http","tag":"smoke-http","settings":{}},
                  {"listen":"127.0.0.1","port":%d,"protocol":"dokodemo-door","tag":"perroute-api","settings":{"address":"127.0.0.1"}}],
                 "outbounds":[{"tag":"direct","protocol":"freedom","settings":{}}],
                 "api":{"tag":"api","services":["StatsService","HandlerService","RoutingService"]},
                 "stats":{},"policy":{"system":{"statsInboundUplink":true,"statsInboundDownlink":true}},
                 "routing":{"rules":[{"type":"field","inboundTag":["perroute-api"],"outboundTag":"api","ruleTag":"api-route"},
                  {"type":"field","inboundTag":["smoke-http","smoke-socks"],"outboundTag":"direct","ruleTag":"smoke-route"}]}}
                """.formatted(socks, http, XrayConfigGenerator.STATS_API_PORT);
    }

    private static void serve(ServerSocket origin) {
        while (!origin.isClosed()) {
            try (Socket client = origin.accept()) {
                client.setSoTimeout(5_000);
                var input = client.getInputStream();
                int matched = 0;
                while (matched < 4) {
                    int value = input.read();
                    if (value < 0) break;
                    matched = value == '\r' || value == '\n' ? matched + 1 : 0;
                }
                byte[] body = "perroute-loopback".getBytes(StandardCharsets.US_ASCII);
                client.getOutputStream().write(("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().write(body);
            } catch (IOException error) {
                if (!origin.isClosed()) throw new RuntimeException(error);
            }
        }
    }

    private static String request(int proxyPort, int originPort, boolean socks) throws IOException {
        try (Socket socket = socks ? new Socket(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", proxyPort))) : new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", socks ? originPort : proxyPort), 2_000);
            socket.setSoTimeout(5_000);
            String path = socks ? "/" : "http://127.0.0.1:" + originPort + "/";
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + originPort + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        }
    }

    private static void waitForPort(XrayProcessManager process, int port) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (!process.isRunning()) throw new AssertionError("Engine exited before readiness");
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
                return;
            } catch (IOException pending) { Thread.sleep(25); }
        }
        throw new AssertionError("Engine listener did not become ready");
    }

    private static boolean noPendingConfig(Path data) throws IOException {
        try (var files = Files.list(data)) {
            return files.noneMatch(path -> path.getFileName().toString().startsWith("pending-config-"));
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
