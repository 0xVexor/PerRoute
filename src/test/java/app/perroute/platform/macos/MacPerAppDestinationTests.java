package app.perroute.platform.macos;

import app.perroute.gateway.AppGateway;
import app.perroute.model.ApplicationIdentity;
import app.perroute.model.Protocol;
import app.perroute.model.ProxyNode;
import app.perroute.service.XrayConfigGenerator;
import app.perroute.service.XrayProcessManager;
import app.perroute.service.XrayRuntime;
import app.perroute.util.Json;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLContext;

/** Observe the destination actually sent by Xray, without resolving or contacting the destination. */
final class MacPerAppDestinationTests {
    static void run(XrayRuntime runtime, Path root) throws Exception {
        exchange(runtime, root, ApplicationIdentity.macExecutable("/fixture/Browser"), false, "browser-destination.test", true);
        exchange(runtime, root, ApplicationIdentity.macAppFamily("/fixture/Browser"), true, "browser-destination.test", true);
        exchange(runtime, root, "C:\\Browser\\browser.exe", false, "8.8.4.4", true);
        exchange(runtime, root, ApplicationIdentity.macExecutable("/fixture/Browser"), false, "8.8.4.4", false);
        System.out.println("macOS per-app destination: HTTP hostname and TLS SNI reach the upstream; Windows retains its original IP");
    }

    private static void exchange(XrayRuntime runtime, Path root, String identity, boolean tls, String expected, boolean perApp) throws Exception {
        var observed = new CompletableFuture<String>();
        try (var upstream = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
            upstream.setSoTimeout(5000);
            Thread peer = Thread.ofVirtual().start(() -> {
                try (var client = upstream.accept()) {
                    client.setSoTimeout(5000);
                    var in = new DataInputStream(client.getInputStream());
                    var out = client.getOutputStream();
                    if (in.readUnsignedByte() != 5) throw new IOException("Expected SOCKS greeting");
                    in.readNBytes(in.readUnsignedByte());
                    out.write(new byte[]{5, 0}); out.flush();
                    if (in.readUnsignedByte() != 5 || in.readUnsignedByte() != 1 || in.readUnsignedByte() != 0)
                        throw new IOException("Expected SOCKS CONNECT");
                    String host = switch (in.readUnsignedByte()) {
                        case 1 -> InetAddress.getByAddress(in.readNBytes(4)).getHostAddress();
                        case 3 -> new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.US_ASCII);
                        case 4 -> InetAddress.getByAddress(in.readNBytes(16)).getHostAddress();
                        default -> throw new IOException("Unexpected address type");
                    };
                    int port = in.readUnsignedShort();
                    out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 0}); out.flush();
                    if (in.read() < 0) throw new EOFException("Expected actual browser payload");
                    if (port != 443) throw new IOException("Destination port changed");
                    observed.complete(host);
                } catch (Throwable failure) { observed.completeExceptionally(failure); }
            });
            var profile = Json.stringify(Map.of("routing", Map.of("rules", List.of(Map.of("type", "field", "inboundTag", List.of("fixture"), "outboundTag", "remote"))), "outbounds", List.of(Map.of("tag", "remote", "protocol", "socks",
                    "settings", Map.of("servers", List.of(Map.of("address", "127.0.0.1", "port", upstream.getLocalPort())))))));
            var node = new ProxyNode("fixture", "fixture", Protocol.XRAY_PROFILE, "127.0.0.1", upstream.getLocalPort(),
                    "fixture", Map.of("rawProfileJson", profile), "");
            String config = new XrayConfigGenerator(XrayConfigGenerator.GATEWAY_UPSTREAM_PORT_BASE)
                    .generateApplicationProxy(node, List.of(identity));
            try (var process = new XrayProcessManager(runtime, root.resolve("destination-core"));
                 var gateway = new AppGateway(null, null)) {
                process.start(config, false);
                int gatewayPort;
                try (var reservation = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) { gatewayPort = reservation.getLocalPort(); }
                gateway.start(List.of(new AppGateway.Route(identity, 0, gatewayPort, perApp ? XrayConfigGenerator.gatewayUpstreamPort(0) : app.perroute.service.AppProxyLauncher.SOCKS_PORT)));
                try (var socket = new Socket(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", gatewayPort)))) {
                    // Public numeric destination models a browser's local DNS result. The local SOCKS fixture never dials it.
                    socket.connect(new InetSocketAddress("8.8.4.4", 443), 3000);
                    if (tls) {
                        var engine = SSLContext.getDefault().createSSLEngine("browser-destination.test", 443);
                        engine.setUseClientMode(true); engine.beginHandshake();
                        var hello = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
                        engine.wrap(ByteBuffer.allocate(0), hello);
                        socket.getOutputStream().write(hello.array(), 0, hello.position());
                    } else {
                        socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: browser-destination.test\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    }
                    socket.getOutputStream().flush();
                    String actual = observed.get(6, TimeUnit.SECONDS);
                    if (!expected.equals(actual)) throw new AssertionError("Expected upstream destination " + expected + " but received " + actual);
                }
            } finally {
                upstream.close(); peer.join(6000);
                if (peer.isAlive()) throw new AssertionError("Destination fixture did not stop");
            }
        }
    }
}
