package app.perroute;

import app.perroute.model.Protocol;
import app.perroute.model.ProxyNode;
import app.perroute.model.RoutingMode;
import app.perroute.model.RouteRule;
import app.perroute.model.AppState;
import app.perroute.model.ConnectionMode;
import app.perroute.model.ConnectionProfile;
import app.perroute.persistence.AppStateStore;
import app.perroute.parser.ParseResult;
import app.perroute.parser.ShareLinkParser;
import app.perroute.parser.SubscriptionParser;
import app.perroute.service.XrayConfigGenerator;
import app.perroute.service.AppProxyLauncher;
import app.perroute.service.ProxiFyreManager;
import app.perroute.service.ProxyHealthCheck;
import app.perroute.service.RouteRuleEvaluator;
import app.perroute.service.TunnelHttp;
import app.perroute.service.TunnelSpeedTest;
import app.perroute.service.ServerLatencyProbe;
import app.perroute.service.SecureUpdateService;
import app.perroute.service.TrafficHistoryStore;
import app.perroute.service.QrImportService;
import app.perroute.service.ProcessDiscovery;
import app.perroute.service.InstalledApplicationDiscovery;
import app.perroute.util.Codec;
import app.perroute.util.Json;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import javax.imageio.ImageIO;

public final class AllTests {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        run("VLESS parsing", AllTests::testVless);
        run("VMess parsing", AllTests::testVmess);
        run("Trojan parsing", AllTests::testTrojan);
        run("Shadowsocks parsing", AllTests::testShadowsocks);
        run("Subscription decoding", AllTests::testSubscriptionBase64);
        run("Xray JSON import", AllTests::testXrayJsonSubscription);
        run("Portable config export", AllTests::testPortableConfigExport);
        run("Duplicate identity", AllTests::testDuplicateIdentity);
        run("Include routing", AllTests::testConfigIncludeMode);
        run("Exclude routing", AllTests::testConfigExcludeMode);
        run("Local proxy config", AllTests::testApplicationProxyConfig);
        run("Per-app rules", AllTests::testPerAppRoutingRules);
        run("Launch plans", AllTests::testAppProxyLaunchPlans);
        run("Native per-app config", AllTests::testNativePerAppConfig);
        run("Tunnel health check", AllTests::testProxyHealthCheck);
        run("Latency probe", AllTests::testServerLatencyProbe);
        run("Application discovery", AllTests::testApplicationDiscoveryDeduplication);
        run("Signed updates", AllTests::testSignedUpdateManifest);
        run("Traffic history", AllTests::testTrafficHistory);
        run("QR import", AllTests::testQrImport);
        run("Version 1 state", AllTests::testVersionOneStateRoundTrip);
        run("JSON round trip", AllTests::testJsonRoundTrip);
        run("Lenient share links", AllTests::testLenientShareLinks);
        run("Route rule evaluator", AllTests::testRouteRuleEvaluator);
        run("Speed measurement", AllTests::testSpeedMeasurement);
        run("Legacy state migration", AllTests::testLegacyStateMigration);
        run("Advanced routing persistence", AllTests::testAdvancedRoutingPersistence);
        run("Route planner", AllTests::testRoutePlanner);
        run("Multi-server config", AllTests::testMultiServerConfig);
        run("Multi-server rule precedence", AllTests::testMultiServerRulePrecedence);
        run("Embedded profile with custom servers", AllTests::testProfileWithCustomServers);
        run("Embedded profile as a custom server", AllTests::testEmbeddedProfileAsCustomServer);
        run("Traffic report", AllTests::testTrafficReport);
        PlatformBoundaryTests.runPlatformSelection();
        PlatformBoundaryTests.runDiagnosticRouting();
        PlatformBoundaryTests.runGatewayAdapter();
        V3DomainTests.runAll();
        ControllerTests.runAll();
        V3ControllerTests.runAll();
        UiStyleSwitchTests.runAll();
        V35Tests.runAll();
        AppFeatureTests.runAll();
        RealCoreIntegrationTests.runAll();
        System.out.println("All tests passed (" + (assertions + PlatformBoundaryTests.assertions() + V3DomainTests.assertions() + ControllerTests.assertions() + V3ControllerTests.assertions() + UiStyleSwitchTests.assertions()
                + V35Tests.assertions() + AppFeatureTests.assertions() + RealCoreIntegrationTests.assertions()) + " assertions)");
        System.exit(0);
    }

    @FunctionalInterface
    private interface CheckedTest { void run() throws Exception; }

    private static void removeTemporaryDirectory(Path directory) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try { V3DomainTests.deleteTree(directory); return; }
            catch (java.io.IOException busy) {
                if (attempt >= 5) throw busy;
                Thread.sleep(100L * attempt);
            }
        }
    }

    private static void run(String name, CheckedTest test) throws Exception {
        System.out.println("[test] " + name);
        test.run();
    }

    private static void testVless() {
        ProxyNode node = new ShareLinkParser().parse("vless://11111111-1111-1111-1111-111111111111@example.com:443?type=ws&security=tls&sni=edge.example.com&path=%2Fvpn&host=cdn.example.com#My%20Node");
        equal(Protocol.VLESS, node.protocol());
        equal("example.com", node.address());
        equal(443, node.port());
        equal("My Node", node.name());
        equal("/vpn", node.option("path"));
    }

    private static void testVmess() {
        String json = "{\"v\":\"2\",\"ps\":\"VM Test\",\"add\":\"vm.example\",\"port\":\"8443\",\"id\":\"22222222-2222-2222-2222-222222222222\",\"aid\":\"0\",\"scy\":\"auto\",\"net\":\"grpc\",\"path\":\"svc\",\"tls\":\"tls\",\"sni\":\"vm.example\"}";
        String link = "vmess://" + Base64.getEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        ProxyNode node = new ShareLinkParser().parse(link);
        equal(Protocol.VMESS, node.protocol());
        equal("vm.example", node.address());
        equal("grpc", node.option("type"));
        equal("VM Test", node.name());
    }

    private static void testTrojan() {
        ProxyNode node = new ShareLinkParser().parse("trojan://secret@trojan.example:443?security=tls&sni=trojan.example#Trojan");
        equal(Protocol.TROJAN, node.protocol());
        equal("secret", node.credential());
    }

    private static void testShadowsocks() {
        String user = Base64.getUrlEncoder().withoutPadding().encodeToString("aes-256-gcm:password".getBytes(StandardCharsets.UTF_8));
        ProxyNode node = new ShareLinkParser().parse("ss://" + user + "@ss.example:8388#SS");
        equal(Protocol.SHADOWSOCKS, node.protocol());
        equal("aes-256-gcm", node.option("method"));
        equal("password", node.credential());
    }

    private static void testSubscriptionBase64() {
        String links = "vless://id@one.example:443?security=tls#One\n" +
                "trojan://pass@two.example:443?security=tls#Two\n";
        String subscription = Base64.getEncoder().withoutPadding().encodeToString(links.getBytes(StandardCharsets.UTF_8));
        ParseResult result = new SubscriptionParser().parse(subscription);
        equal(2, result.nodes().size());
        equal(0, result.warnings().size());
    }

    private static void testPortableConfigExport() {
        String link = "vless://id@copy.example:443?security=tls#Copy";
        equal(link, new ShareLinkParser().parse(link).exportText());

        String json = """
                {"remarks":"Copy JSON","outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"json-copy.example","port":443,"users":[{"id":"id"}]}]}}]}
                """;
        ProxyNode profile = new SubscriptionParser().parse(json).nodes().get(0);
        equal(Json.parse(json), Json.parse(profile.exportText()));
    }

    private static void testDuplicateIdentity() {
        ProxyNode first = new ShareLinkParser().parse("vless://same-id@duplicate.example:443?security=tls&type=ws&path=%2Fvpn#First%20name");
        ProxyNode renamed = new ShareLinkParser().parse("vless://same-id@duplicate.example:443?security=tls&type=ws&path=%2Fvpn#Another%20name");
        equal(first.identity(), renamed.identity());

        String profileA = """
                {"remarks":"First","log":{"access":"/mobile/a.log"},"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"same-json.example","port":443,"users":[{"id":"same-id"}]}]}}]}
                """;
        String profileB = profileA.replace("First", "Renamed").replace("/mobile/a.log", "C:/different.log");
        ProxyNode jsonA = new SubscriptionParser().parse(profileA).nodes().get(0);
        ProxyNode jsonB = new SubscriptionParser().parse(profileB).nodes().get(0);
        equal(jsonA.identity(), jsonB.identity());
    }

    @SuppressWarnings("unchecked")
    private static void testXrayJsonSubscription() {
        String json = """
                [{
                  "remarks":"Balanced profile",
                  "log":{"loglevel":"warning","access":"/private/var/mobile/Containers/Shared/AppGroup/example/logs/access.log","error":"/data/user/0/example/error.log"},
                  "inbounds":[],
                  "outbounds":[
                    {"tag":"proxy-a","protocol":"vless","settings":{"vnext":[{"address":"json.example","port":443,"users":[{"id":"33333333-3333-3333-3333-333333333333","encryption":"mlkem768x25519plus.native.0rtt.LLkO3dzcSol4mV4L-9cOmCKU9onnO6_Ng87hglhbc1g"}]}]},"streamSettings":{"network":"tcp","tcpSettings":{"header":{"type":"none"}}}},
                    {"tag":"direct","protocol":"freedom","settings":{}}
                  ],
                  "routing":{"rules":[]}
                }]
                """;
        ParseResult result = new SubscriptionParser().parse(json);
        equal(1, result.nodes().size());
        ProxyNode profile = result.nodes().get(0);
        equal(Protocol.XRAY_PROFILE, profile.protocol());
        equal("Balanced profile", profile.name());
        equal("json.example", profile.address());
        Map<String, Object> generated = Json.object(new XrayConfigGenerator().generate(profile,
                List.of("C:\\Apps\\Only.exe"), RoutingMode.INCLUDE_SELECTED));
        Map<String, Object> generatedLog = (Map<String, Object>)generated.get("log");
        equal(Map.of("loglevel", "info"), generatedLog);
        List<Object> inbounds = (List<Object>)generated.get("inbounds");
        equal("tun", ((Map<String, Object>)inbounds.get(0)).get("protocol"));
        List<Object> outbounds = (List<Object>)generated.get("outbounds");
        equal(3, outbounds.size());
        List<Object> rules = (List<Object>)((Map<String, Object>)generated.get("routing")).get("rules");
        check(rules.stream().map(Map.class::cast).anyMatch(rule -> "proxy-a".equals(rule.get("outboundTag")) && rule.containsKey("process")),
                "Selected application must target the embedded profile");
        Map<String, Object> applicationProxy = Json.object(new XrayConfigGenerator().generateApplicationProxy(profile));
        equal(Map.of("loglevel", "info"), applicationProxy.get("log"));
        validateWithBundledXray(Json.stringify(applicationProxy));
    }

    private static void validateWithBundledXray(String config) {
        Path xray = Path.of("tools", "xray", "xray.exe").toAbsolutePath().normalize();
        if (!Files.isRegularFile(xray)) return;
        Path temporary = null;
        try {
            Path testTemp = Path.of("build", "tmp").toAbsolutePath().normalize();
            Files.createDirectories(testTemp);
            temporary = Files.createTempFile(testTemp, "perroute-profile-test-", ".json");
            Files.writeString(temporary, config, StandardCharsets.UTF_8);
            Process process = new ProcessBuilder(xray.toString(), "run", "-test", "-config", temporary.toString())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            check(exit == 0, "Bundled Xray rejected the ML-KEM profile: " + output);
        } catch (Exception error) {
            throw new AssertionError("Could not validate the ML-KEM profile with bundled Xray", error);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    private static void testConfigIncludeMode() {
        ProxyNode node = new ShareLinkParser().parse("vless://id@example.com:443?type=ws&security=tls&sni=example.com&path=%2Fx#One");
        String config = new XrayConfigGenerator().generate(node, List.of("C:\\Apps\\Browser.exe"), RoutingMode.INCLUDE_SELECTED);
        Map<String, Object> root = Json.object(config);
        List<Object> outbounds = (List<Object>) root.get("outbounds");
        equal("vless", ((Map<String, Object>)outbounds.get(0)).get("protocol"));
        Map<String, Object> routing = (Map<String, Object>)root.get("routing");
        List<Object> rules = (List<Object>)routing.get("rules");
        equal("proxy", ((Map<String, Object>)rules.get(1)).get("outboundTag"));
        equal("direct", ((Map<String, Object>)rules.get(2)).get("outboundTag"));
        check(config.contains("C:/Apps/Browser.exe"), "Windows paths must use forward slashes");
        check(config.contains("\"Browser\"") && config.contains("\"browser\""),
                "Process rules must include case variants of the executable name");
    }

    @SuppressWarnings("unchecked")
    private static void testConfigExcludeMode() {
        ProxyNode node = new ShareLinkParser().parse("trojan://pass@example.com:443?security=tls#T");
        Map<String, Object> root = Json.object(new XrayConfigGenerator().generate(node,
                List.of("C:\\Apps\\Direct.exe"), RoutingMode.EXCLUDE_SELECTED));
        List<Object> rules = (List<Object>)((Map<String, Object>)root.get("routing")).get("rules");
        equal("direct", ((Map<String, Object>)rules.get(1)).get("outboundTag"));
        equal("proxy", ((Map<String, Object>)rules.get(2)).get("outboundTag"));
    }

    @SuppressWarnings("unchecked")
    private static void testApplicationProxyConfig() {
        ProxyNode node = new ShareLinkParser().parse("vless://id@example.com:443?security=tls#Isolated");
        Map<String, Object> root = Json.object(new XrayConfigGenerator().generateApplicationProxy(node));
        List<Object> inbounds = (List<Object>)root.get("inbounds");
        equal(3, inbounds.size());
        equal("127.0.0.1", ((Map<String, Object>)inbounds.get(0)).get("listen"));
        equal("socks", ((Map<String, Object>)inbounds.get(0)).get("protocol"));
        equal(AppProxyLauncher.SOCKS_PORT, ((Map<String, Object>)inbounds.get(0)).get("port"));
        equal("http", ((Map<String, Object>)inbounds.get(1)).get("protocol"));
        equal(AppProxyLauncher.HTTP_PORT, ((Map<String, Object>)inbounds.get(1)).get("port"));
        equal("perroute-api", ((Map<String, Object>)inbounds.get(2)).get("tag"));
        check(root.containsKey("stats") && root.containsKey("api"),
                "Application proxy mode must expose loopback traffic statistics");
        check(inbounds.stream().map(Map.class::cast).noneMatch(inbound -> "tun".equals(inbound.get("protocol"))),
                "Application proxy mode must not create a TUN inbound");
        List<Object> rules = (List<Object>)((Map<String, Object>)root.get("routing")).get("rules");
        check(rules.stream().map(Map.class::cast).anyMatch(rule -> "perroute-app-proxy".equals(rule.get("ruleTag")) &&
                        "proxy".equals(rule.get("outboundTag"))),
                "Local application proxy traffic must target the selected server");

        Map<String, Object> smart = Json.object(new XrayConfigGenerator().generateApplicationProxy(node,
                List.of("C:\\Apps\\Chrome.exe", "C:\\Apps\\ChatGPT.exe")));
        List<Object> smartInbounds = (List<Object>)smart.get("inbounds");
        equal(5, smartInbounds.size());
        equal("perroute-app-0", ((Map<String, Object>)smartInbounds.get(2)).get("tag"));
        equal(XrayConfigGenerator.perAppPort(0), ((Map<String, Object>)smartInbounds.get(2)).get("port"));
        equal("perroute-app-1", ((Map<String, Object>)smartInbounds.get(3)).get("tag"));
        equal(XrayConfigGenerator.perAppPort(1), ((Map<String, Object>)smartInbounds.get(3)).get("port"));
        validateWithBundledXray(Json.stringify(smart));
    }

    private static void testAppProxyLaunchPlans() throws Exception {
        AppProxyLauncher launcher = new AppProxyLauncher();

        AppProxyLauncher.LaunchPlan chrome = launcher.plan("C:\\Apps\\chrome.exe");
        equal(AppProxyLauncher.Adapter.CHROMIUM, chrome.compatibility().adapter());
        check(chrome.command().stream().anyMatch(value -> value.startsWith("--proxy-server=http://127.0.0.1:")),
                "Chromium apps must receive their native proxy switch");
        check(!chrome.environment().containsKey("JAVA_TOOL_OPTIONS"),
                "Chromium apps must not receive JVM-only settings");

        AppProxyLauncher.LaunchPlan idea = launcher.plan("C:\\Apps\\idea64.exe");
        equal(AppProxyLauncher.Adapter.JETBRAINS, idea.compatibility().adapter());
        equal(1, idea.command().size());
        check(idea.environment().get("JAVA_TOOL_OPTIONS").contains("https.proxyHost=127.0.0.1"),
                "JetBrains apps must receive JVM proxy properties");

        AppProxyLauncher.LaunchPlan generic = launcher.plan("C:\\Apps\\proxy-aware.exe");
        equal(AppProxyLauncher.Adapter.ENVIRONMENT, generic.compatibility().adapter());
        check(generic.environment().containsKey("HTTP_PROXY") && generic.environment().containsKey("ALL_PROXY"),
                "Generic proxy-aware apps must receive standard proxy environment variables");

        check(!launcher.compatibility("C:\\Apps\\firefox.exe").supported(),
                "Known apps that ignore per-launch proxy settings must be rejected");
    }

    @SuppressWarnings("unchecked")
    private static void testPerAppRoutingRules() {
        ProxyNode node = new ShareLinkParser().parse("vless://id@example.com:443?security=tls#Rules");
        List<String> apps = List.of("C:\\Apps\\Chrome.exe", "C:\\Apps\\ChatGPT.exe");
        RouteRule direct = new RouteRule("rule-1", "Direct intranet", apps.get(0),
                RouteRule.MatchType.DOMAIN, "example.ir,full:api.local", RouteRule.Action.DIRECT, true);
        RouteRule blocked = new RouteRule("rule-2", "Block port", apps.get(1),
                RouteRule.MatchType.PORT, "25", RouteRule.Action.BLOCK, true);
        Map<String, Object> root = Json.object(new XrayConfigGenerator().generateApplicationProxy(node, apps, List.of(direct, blocked)));
        List<Object> rules = (List<Object>)((Map<String, Object>)root.get("routing")).get("rules");
        Map<String, Object> directRule = rules.stream().map(Map.class::cast)
                .filter(rule -> "perroute-custom-rule-1".equals(rule.get("ruleTag"))).findFirst().orElseThrow();
        equal(List.of("perroute-app-0"), directRule.get("inboundTag"));
        equal(List.of("domain:example.ir", "full:api.local"), directRule.get("domain"));
        equal("direct", directRule.get("outboundTag"));
        Map<String, Object> blockRule = rules.stream().map(Map.class::cast)
                .filter(rule -> "perroute-custom-rule-2".equals(rule.get("ruleTag"))).findFirst().orElseThrow();
        equal("25", blockRule.get("port"));
        equal("block", blockRule.get("outboundTag"));
        validateWithBundledXray(Json.stringify(root));
    }

    @SuppressWarnings("unchecked")
    private static void testNativePerAppConfig() {
        Map<String, Object> root = Json.object(new ProxiFyreManager().generateConfig(List.of(
                "C:\\Program Files\\WindowsApps\\OpenAI.ChatGPT\\ChatGPT.exe",
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe")));
        equal(true, root.get("bypassLan"));
        List<Object> proxies = (List<Object>)root.get("proxies");
        equal(2, proxies.size());
        Map<String, Object> rule = (Map<String, Object>)proxies.get(0);
        equal("127.0.0.1:" + XrayConfigGenerator.perAppPort(0), rule.get("socks5ProxyEndpoint"));
        equal("127.0.0.1:" + XrayConfigGenerator.perAppPort(1),
                ((Map<String, Object>)proxies.get(1)).get("socks5ProxyEndpoint"));
        List<Object> appNames = (List<Object>)rule.get("appNames");
        check(appNames.get(0).toString().endsWith("ChatGPT.exe"),
                "Native mode must match the selected executable path");
        check(appNames.stream().anyMatch(value -> value.toString().endsWith("OpenAI.ChatGPT\\")),
                "Store-app helpers in the same package directory must use the proxy too");
        equal(List.of("TCP", "UDP"), rule.get("supportedProtocols"));
        check(((List<Object>)root.get("excludes")).contains("xray.exe"),
                "The Xray carrier must be excluded to avoid a proxy loop");
    }

    private static void testJsonRoundTrip() {
        Map<String, Object> value = Map.of("text", "سلام \\\"", "number", 12, "ok", true, "list", List.of("a", "b"));
        equal(value, Json.parse(Json.stringify(value)));
    }

    private static void testSignedUpdateManifest() throws Exception {
        String manifest = """
                {
                  "version": "9.9.9",
                  "url": "https://updates.perroute.invalid/PerRoute-Setup-9.9.9.exe",
                  "sha256": "ae084cd577687fb114d695cd88447e8cc4be6d5048fde285d6c93669ab1a745d",
                  "size": 6644,
                  "signature": "O8i6V/3UApes+NqiscDCiy/XFuQSs6lL3+Q7VTk+K3RJiGmxY6sWkPQTFrSOkvptSg5rvHZ7m9/2ITMP3YLcBQ=="
                }
                """;
        SecureUpdateService.UpdateInfo update = new SecureUpdateService().parseAndVerify(
                manifest.getBytes(StandardCharsets.UTF_8));
        equal("9.9.9", update.version());
        check(update.newer(), "A newer signed version must be detected");
        check(SecureUpdateService.compareVersions("0.15.0", "0.14.2") > 0,
                "Semantic version comparison must identify newer releases");
        boolean rejected = false;
        try {
            new SecureUpdateService().parseAndVerify(
                    manifest.replace("9.9.9", "9.9.8").getBytes(StandardCharsets.UTF_8));
        } catch (Exception expected) {
            rejected = true;
        }
        check(rejected, "A modified signed update manifest must be rejected");
    }

    private static void testTrafficHistory() throws Exception {
        Path file = Files.createTempDirectory("perroute-history-test").resolve("history.json");
        TrafficHistoryStore store = new TrafficHistoryStore(file);
        store.record("C:\\Apps\\Chrome.exe", 4096, 1024);
        store.record("C:\\Apps\\Chrome.exe", 2048, 512);
        TrafficHistoryStore.Snapshot snapshot = store.snapshot();
        equal(6144L, snapshot.todayDownloadBytes());
        equal(1536L, snapshot.todayUploadBytes());
        equal(1, snapshot.applications().size());
        store.close();
        TrafficHistoryStore restored = new TrafficHistoryStore(file);
        equal(6144L, restored.snapshot().monthDownloadBytes());
        restored.clear();
        equal(0L, restored.snapshot().todayDownloadBytes());
        restored.close();
        removeTemporaryDirectory(file.getParent());
    }

    private static void testQrImport() throws Exception {
        String link = "vless://11111111-1111-1111-1111-111111111111@qr.example:443?security=tls#QR";
        var matrix = new MultiFormatWriter().encode(link, BarcodeFormat.QR_CODE, 320, 320);
        Path image = Files.createTempFile("perroute-qr-", ".png");
        ImageIO.write(MatrixToImageWriter.toBufferedImage(matrix), "PNG", image.toFile());
        equal(link, new QrImportService().decode(image));
        Files.deleteIfExists(image);
    }

    private static void testVersionOneStateRoundTrip() throws Exception {
        Path directory = Files.createTempDirectory("perroute-state-v1-");
        AppStateStore store = new AppStateStore(directory.resolve("state.bin"));
        AppState state = new AppState();
        ProxyNode node = new ShareLinkParser().parse("trojan://secret@state.example:443?security=tls#State");
        RouteRule rule = new RouteRule("saved-rule", "Direct", "C:\\Apps\\Game.exe",
                RouteRule.MatchType.IP, "10.0.0.0/8", RouteRule.Action.DIRECT, true);
        ConnectionProfile profile = new ConnectionProfile("saved-profile", "Gaming",
                List.of("C:\\Apps\\Game.exe"), node.key(), ConnectionMode.NATIVE_PER_APP, List.of(rule));
        state.nodes().add(node); state.favoriteNodeKeys().add(node.key()); state.nodeTags().put(node.key(), "Gaming");
        state.routeRules().add(rule); state.profiles().add(profile); state.setActiveProfileId(profile.id());
        store.save(state);
        AppState restored = store.load();
        check(restored.favoriteNodeKeys().contains(node.key()), "Favorites must survive state encryption");
        equal("Gaming", restored.nodeTags().get(node.key()));
        equal(RouteRule.Action.DIRECT, restored.routeRules().get(0).action());
        equal("Gaming", restored.profiles().get(0).name());
        equal("saved-profile", restored.activeProfileId());
        removeTemporaryDirectory(directory);
    }

    private static void testProxyHealthCheck() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread responder = Thread.ofPlatform().start(() -> {
                try (Socket client = server.accept()) {
                    InputStream input = client.getInputStream();
                    input.readNBytes(3);
                    client.getOutputStream().write(new byte[]{0x05, 0x00});
                    byte[] request = input.readNBytes(5);
                    int domainLength = request[4] & 0xff;
                    input.readNBytes(domainLength + 2);
                    client.getOutputStream().write(new byte[]{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 80});
                    client.getOutputStream().flush();
                    byte[] head = new byte[512];
                    int read = input.read(head);
                    check(read > 0 && new String(head, 0, read, StandardCharsets.US_ASCII).startsWith("HEAD /"), "The check must send a request through the tunnel");
                    client.getOutputStream().write("HTTP/1.1 204 No Content\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            ProxyHealthCheck.Result result = new ProxyHealthCheck().check("127.0.0.1", server.getLocalPort());
            check(result.success(), "Health check must verify an outbound SOCKS tunnel");
            check(result.detail().startsWith("Tunnel answered from") && result.detail().contains("204"), "Health check must describe the tunnel result: " + result.detail());
            responder.join(2_000);
        }
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread responder = Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try (Socket client = server.accept()) {
                        InputStream input = client.getInputStream();
                        input.readNBytes(3);
                        client.getOutputStream().write(new byte[]{0x05, 0x00});
                        byte[] request = input.readNBytes(5);
                        input.readNBytes((request[4] & 0xff) + 2);
                        client.getOutputStream().write(new byte[]{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 80});
                        client.getOutputStream().flush();
                    } catch (Exception ignored) { }
                }
            });
            ProxyHealthCheck.Result dead = new ProxyHealthCheck().check("127.0.0.1", server.getLocalPort());
            check(!dead.success(), "A SOCKS success without an answer from the far side is not a working tunnel");
            check(dead.detail().contains("without an answer"), dead.detail());
            server.close();
            responder.join(2_000);
        }
    }

    private static void testServerLatencyProbe() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread accept = Thread.ofPlatform().start(() -> {
                try (Socket ignored = server.accept()) {
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            ProxyNode node = new ProxyNode("latency", "Local", Protocol.VLESS, "127.0.0.1",
                    server.getLocalPort(), "id", Map.of(), "");
            ServerLatencyProbe.Result result = new ServerLatencyProbe().probe(node, 1_000);
            check(result.reachable(), "Latency probe must detect a reachable TCP endpoint");
            check(result.milliseconds() >= 1, "Latency probe must report milliseconds");
            accept.join(1_000);
        }
    }

    private static void testApplicationDiscoveryDeduplication() {
        var running = new ProcessDiscovery().list();
        long runningNames = running.stream().map(app -> app.name().toLowerCase()).distinct().count();
        equal((long)running.size(), runningNames);

        var installed = new InstalledApplicationDiscovery().list();
        long installedNames = installed.stream().map(app -> app.name().toLowerCase()).distinct().count();
        equal((long)installed.size(), installedNames);
        check(installed.stream().allMatch(app -> app.path().toLowerCase().endsWith(".exe")),
                "Installed application choices must point to executable files");
        check(installed.stream().map(app -> app.name().toLowerCase()).noneMatch(name ->
                        name.matches(".*\\b(jdk|sdk|runtime)\\b.*") || name.contains("development kit")),
                "Developer runtimes and SDKs must not appear as user applications");
    }

    private static void testLenientShareLinks() {
        ShareLinkParser parser = new ShareLinkParser();
        ProxyNode named = parser.parse("vless://11111111-1111-1111-1111-111111111111@example.com:443?type=ws&path=/a b|c&security=tls#DE | Frankfurt [01] 12.5GB");
        equal("example.com", named.address());
        equal(443, named.port());
        equal("DE | Frankfurt [01] 12.5GB", named.name());
        equal("/a b|c", named.option("path"));
        equal("11111111-1111-1111-1111-111111111111", named.credential());
        ProxyNode renamed = parser.parse("vless://11111111-1111-1111-1111-111111111111@example.com:443?type=ws&path=/a b|c&security=tls#DE | Frankfurt [01] 9.1GB");
        equal(named.identity(), renamed.identity());
        check(!named.key().equals(renamed.key()), "A renamed link gets a different raw key");
        ProxyNode stray = parser.parse("trojan://secret@host.example:8443?sni=a.example#100% uptime");
        equal("100% uptime", stray.name());
        equal(8443, stray.port());
        ProxyNode ipv6 = parser.parse("vless://11111111-1111-1111-1111-111111111111@[2001:db8::1]:443?security=tls#v6");
        equal("2001:db8::1", ipv6.address());
        ProxyNode sip002 = parser.parse("ss://" + Base64.getEncoder().encodeToString("aes-256-gcm:pass".getBytes(StandardCharsets.UTF_8))
                + "@ss.example.com:8388/?plugin=obfs-local#SS | 1");
        equal("ss.example.com", sip002.address());
        equal(8388, sip002.port());
        equal("SS | 1", sip002.name());
        ParseResult mixed = new SubscriptionParser().parse(
                "vless://11111111-1111-1111-1111-111111111111@a.example:443?security=tls#A | 1\n"
                + "vless://11111111-1111-1111-1111-111111111111@b.example:443?security=tls#B [2]\n");
        equal(2, mixed.nodes().size());
        equal(0, mixed.warnings().size());
    }

    private static void testRouteRuleEvaluator() {
        String browser = "C:\\Apps\\browser.exe";
        List<RouteRule> rules = List.of(
                new RouteRule("1", "Block ads", "", RouteRule.MatchType.DOMAIN, "ads.example.com, keyword:tracker", RouteRule.Action.BLOCK, true),
                new RouteRule("2", "Browser direct", browser, RouteRule.MatchType.DOMAIN, "example.com", RouteRule.Action.DIRECT, true),
                new RouteRule("3", "Disabled", "", RouteRule.MatchType.DOMAIN, "github.com", RouteRule.Action.BLOCK, false),
                new RouteRule("4", "Office range", "", RouteRule.MatchType.IP, "203.0.113.0/24", RouteRule.Action.DIRECT, true),
                new RouteRule("5", "Mail ports", "", RouteRule.MatchType.PORT, "25,587,1000-2000", RouteRule.Action.BLOCK, true));
        List<String> apps = List.of(browser);

        var ads = RouteRuleEvaluator.evaluate(rules, apps, "", "sub.ads.example.com", 443);
        equal(RouteRule.Action.BLOCK, ads.action());
        equal("1", ads.rule().id());
        equal(RouteRule.Action.BLOCK, RouteRuleEvaluator.evaluate(rules, apps, "", "my-tracker.net", 443).action());
        equal(RouteRule.Action.DIRECT, RouteRuleEvaluator.evaluate(rules, apps, browser, "www.example.com", 443).action());
        equal(RouteRule.Action.PROXY, RouteRuleEvaluator.evaluate(rules, apps, "", "www.example.com", 443).action());
        equal(RouteRule.Action.PROXY, RouteRuleEvaluator.evaluate(rules, List.of(), browser, "www.example.com", 443).action());
        equal(RouteRule.Action.PROXY, RouteRuleEvaluator.evaluate(rules, apps, "", "notexample.com", 443).action());
        equal(RouteRule.Action.PROXY, RouteRuleEvaluator.evaluate(rules, apps, "", "github.com", 443).action());
        equal(RouteRule.Action.DIRECT, RouteRuleEvaluator.evaluate(rules, apps, "", "203.0.113.77", 443).action());
        equal(RouteRule.Action.PROXY, RouteRuleEvaluator.evaluate(rules, apps, "", "203.0.114.1", 443).action());
        equal(RouteRule.Action.BLOCK, RouteRuleEvaluator.evaluate(rules, apps, "", "203.0.114.1", 1500).action());
        equal(RouteRule.Action.DIRECT, RouteRuleEvaluator.evaluate(rules, apps, "", "192.168.1.10", 25).action());

        var unresolved = RouteRuleEvaluator.evaluate(rules, apps, "", "github.com", 443);
        check(!unresolved.certain(), "An unresolved host with an earlier IP rule must be reported as uncertain");
        equal("4", unresolved.uncertain().get(0).id());
        var geo = RouteRuleEvaluator.evaluate(List.of(
                new RouteRule("9", "Geo", "", RouteRule.MatchType.DOMAIN, "geosite:netflix", RouteRule.Action.DIRECT, true)),
                apps, "", "netflix.com", 443);
        check(!geo.certain() && geo.action() == RouteRule.Action.PROXY, "geosite matchers must not be guessed");
    }

    private static void testSpeedMeasurement() throws Exception {
        byte[] payload = new byte[300_000];
        var result = TunnelSpeedTest.measure(new java.io.ByteArrayInputStream(payload), 1_000_000, 2_000_000_000L, 500_000_000L);
        check(result.success(), "A finished transfer must succeed");
        equal(300_000L, result.bytesReceived());
        check(result.bytesPerSecond() > 0, "A finished transfer must report a positive rate");
        var capped = TunnelSpeedTest.measure(new java.io.ByteArrayInputStream(payload), 100_000, 2_000_000_000L, 0L);
        check(capped.bytesReceived() >= 100_000 && capped.bytesReceived() < 300_000, "The byte budget must stop the transfer");
        check(!TunnelSpeedTest.measure(new java.io.ByteArrayInputStream(new byte[0]), 1_000, 1_000_000_000L, 0L).success(),
                "An empty response is a failed measurement");

        byte[] response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi".getBytes(StandardCharsets.US_ASCII);
        InputStream input = new java.io.ByteArrayInputStream(response);
        equal(200, TunnelHttp.readHeaders(input));
        equal("hi", new String(input.readAllBytes(), StandardCharsets.US_ASCII));
    }

    private static final String CHROME = "C:\\Apps\\Chrome.exe";
    private static final String TELEGRAM = "C:\\Apps\\Telegram.exe";
    private static final String DISCORD = "C:\\Apps\\Discord.exe";

    private static ProxyNode server(String key, String host) {
        return new ProxyNode(key, host, Protocol.VLESS, host, 443, "id-" + key, Map.of("security", "tls"), "");
    }

    private static ProxyNode embeddedProfile(String key) {
        String json = """
                {"remarks":"Embedded","outbounds":[
                  {"tag":"proxy-a","protocol":"vless","settings":{"vnext":[{"address":"profile.example","port":443,"users":[{"id":"33333333-3333-3333-3333-333333333333","encryption":"none"}]}]}},
                  {"tag":"direct","protocol":"freedom","settings":{}}],
                 "routing":{"rules":[{"type":"field","domain":["domain:intranet.example"],"outboundTag":"direct"}]}}
                """;
        ProxyNode parsed = new SubscriptionParser().parse(json).nodes().get(0);
        return new ProxyNode(key, parsed.name(), parsed.protocol(), parsed.address(), parsed.port(),
                parsed.credential(), parsed.options(), parsed.sourceLink());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> routingRules(Map<String, Object> root) {
        return ((List<Object>)((Map<String, Object>)root.get("routing")).get("rules")).stream()
                .map(rule -> (Map<String, Object>)rule).toList();
    }

    private static Map<String, Object> ruleByTag(List<Map<String, Object>> rules, String tag) {
        return rules.stream().filter(rule -> tag.equals(rule.get("ruleTag"))).findFirst()
                .orElseThrow(() -> new AssertionError("Missing rule " + tag));
    }

    private static int ruleIndex(List<Map<String, Object>> rules, String tag) {
        for (int index = 0; index < rules.size(); index++) if (tag.equals(rules.get(index).get("ruleTag"))) return index;
        throw new AssertionError("Missing rule " + tag);
    }

    private static void testLegacyStateMigration() throws Exception {
        Path directory = Files.createTempDirectory("perroute-state-legacy-");
        AppStateStore store = new AppStateStore(directory.resolve("state.bin"));
        ProxyNode node = new ShareLinkParser().parse("trojan://secret@legacy.example:443?security=tls#Legacy");
        java.util.Properties legacy = new java.util.Properties();
        legacy.setProperty("node.count", "1");
        legacy.setProperty("node.0.link", node.sourceLink());
        legacy.setProperty("app.count", "1");
        legacy.setProperty("app.0.path", CHROME);
        legacy.setProperty("connection.mode", "NATIVE_PER_APP");
        legacy.setProperty("selected.node", "anything");
        legacy.setProperty("profile.count", "1");
        legacy.setProperty("profile.0.json", "{\"id\":\"old\",\"name\":\"Old\",\"applicationPaths\":[\"C:\\\\Apps\\\\Chrome.exe\"],"
                + "\"nodeKey\":\"k\",\"mode\":\"NATIVE_PER_APP\",\"rules\":[]}");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        legacy.store(out, "legacy");
        store.write(out.toByteArray());

        AppState restored = store.load();
        check(!restored.advancedRouting(), "A state saved before Advanced routing must load with it off");
        equal(0, restored.appRoutes().size());
        equal(1, restored.nodes().size());
        equal(List.of(CHROME), restored.appPaths());
        equal(ConnectionMode.NATIVE_PER_APP, restored.connectionMode());
        ConnectionProfile profile = restored.profiles().get(0);
        check(!profile.advancedRouting(), "A legacy profile must not enable Advanced routing");
        equal(0, profile.appRoutes().size());

        store.save(restored);
        AppState again = store.load();
        check(!again.advancedRouting(), "Re-saving a migrated state must keep Advanced routing off");
        removeTemporaryDirectory(directory);
    }

    private static void testAdvancedRoutingPersistence() throws Exception {
        Path directory = Files.createTempDirectory("perroute-state-advanced-");
        AppStateStore store = new AppStateStore(directory.resolve("state.bin"));
        AppState state = new AppState();
        ProxyNode main = server("main", "main.example");
        ProxyNode custom = server("custom", "custom.example");
        state.nodes().add(main); state.nodes().add(custom);
        state.appPaths().add(CHROME);
        state.setAdvancedRouting(true);
        state.appRoutes().put(app.perroute.model.AppRoutes.normalize(CHROME), custom.key());
        state.appRouteLabels().put(custom.key(), "Custom server");
        state.profiles().add(new ConnectionProfile("p", "Office", List.of(CHROME), main.key(),
                ConnectionMode.NATIVE_PER_APP, List.of(), true, Map.of("C:/Apps/CHROME.exe", custom.key())));
        store.save(state);

        AppState restored = store.load();
        check(restored.advancedRouting(), "The Advanced flag must survive a restart");
        equal(Map.of("c:\\apps\\chrome.exe", "custom"), restored.appRoutes());
        equal("Custom server", restored.appRouteLabels().get("custom"));
        check(restored.profiles().get(0).advancedRouting(), "Profiles must store the Advanced flag");
        equal(Map.of("c:\\apps\\chrome.exe", "custom"), restored.profiles().get(0).appRoutes());
        equal("c:\\apps\\chrome.exe", app.perroute.model.AppRoutes.normalize("C:/Apps/../Apps/CHROME.EXE"));

        restored.setAdvancedRouting(false);
        store.save(restored);
        AppState disabled = store.load();
        check(!disabled.advancedRouting(), "Advanced routing must be saved as off");
        equal(Map.of("c:\\apps\\chrome.exe", "custom"), disabled.appRoutes());
        removeTemporaryDirectory(directory);
    }

    private static void testRoutePlanner() {
        ProxyNode main = server("main", "main.example");
        ProxyNode second = server("second", "second.example");
        ProxyNode profile = embeddedProfile("profile");
        List<ProxyNode> nodes = List.of(main, second, profile);
        List<String> apps = List.of(CHROME, TELEGRAM, DISCORD);
        Map<String, String> routes = Map.of("c:\\apps\\chrome.exe", "second", "c:\\apps\\discord.exe", "main");

        var off = app.perroute.service.RoutePlanner.plan(nodes, "main", apps, false, routes, Map.of(), ConnectionMode.NATIVE_PER_APP);
        check(!off.advancedEffective() && off.ready(), "Advanced off must ignore every assignment");
        equal(1, off.routes().size());
        equal(apps, off.routes().get(0).applications());
        equal(main, off.nodeFor(CHROME));

        var on = app.perroute.service.RoutePlanner.plan(nodes, "main", apps, true, routes, Map.of(), ConnectionMode.NATIVE_PER_APP);
        check(on.advancedEffective() && on.ready(), "Valid assignments must be ready");
        equal(2, on.routes().size());
        equal(second, on.nodeFor("c:/apps/CHROME.exe"));
        equal(main, on.nodeFor(TELEGRAM));
        equal(main, on.nodeFor(DISCORD));
        equal(List.of(TELEGRAM, DISCORD), on.routes().get(0).applications());
        check(on.routes().get(0).main() && !on.routes().get(1).main(), "The main server must be listed first");
        equal(List.of(CHROME), on.routes().get(1).applications());
        equal(null, on.nodeFor("C:\\Apps\\NotSelected.exe"));

        for (ConnectionMode mode : List.of(ConnectionMode.SYSTEM_PROXY, ConnectionMode.BROWSER_PROXY, ConnectionMode.TUN_COMPATIBILITY)) {
            var other = app.perroute.service.RoutePlanner.plan(nodes, "main", apps, true, routes, Map.of(), mode);
            check(!other.advancedEffective() && other.routes().size() == 1, "Assignments only apply in Smart per-app");
        }

        var followsNewMain = app.perroute.service.RoutePlanner.plan(nodes, "second", apps, true, routes, Map.of(), ConnectionMode.NATIVE_PER_APP);
        equal(second, followsNewMain.nodeFor(CHROME));
        equal(main, followsNewMain.nodeFor(DISCORD));
        equal(second, followsNewMain.nodeFor(TELEGRAM));
        equal(List.of(CHROME, TELEGRAM), followsNewMain.routes().get(0).applications());

        Map<String, String> dangling = Map.of("c:\\apps\\chrome.exe", "gone", "c:\\apps\\telegram.exe", "profile");
        var missing = app.perroute.service.RoutePlanner.plan(nodes, "main", apps, true, dangling, Map.of("gone", "Old server"), ConnectionMode.NATIVE_PER_APP);
        check(!missing.ready(), "An effective assignment to a removed server must block");
        equal(1, missing.missing().size());
        equal(app.perroute.service.RoutePlanner.Problem.REMOVED, missing.missing().get(0).problem());
        equal("Old server", missing.missing().get(0).label());
        equal(null, missing.nodeFor(CHROME));
        equal(profile, missing.nodeFor(TELEGRAM));
        check(app.perroute.service.RoutePlanner.plan(nodes, "main", apps, false, dangling, Map.of(), ConnectionMode.NATIVE_PER_APP).ready(),
                "An inactive unresolved assignment must not block standard routing");
        check(app.perroute.service.RoutePlanner.plan(nodes, "main", apps, true, dangling, Map.of(), ConnectionMode.SYSTEM_PROXY).ready(),
                "An unresolved assignment must not block other connection modes");
        check(app.perroute.service.RoutePlanner.selectableAsCustom(profile) && app.perroute.service.RoutePlanner.selectableAsCustom(second),
                "Every saved server, embedded profiles included, can be a custom server");
    }

    @SuppressWarnings("unchecked")
    private static void testMultiServerConfig() {
        ProxyNode main = server("main", "main.example");
        ProxyNode second = server("second", "second.example");
        ProxyNode third = server("third", "third.example");
        List<String> apps = List.of(CHROME, TELEGRAM, DISCORD);
        XrayConfigGenerator generator = new XrayConfigGenerator();

        String standard = generator.generateApplicationProxy(main, apps, List.of());
        equal(standard, generator.generateApplicationProxy(main, apps, List.of(), java.util.Arrays.asList(null, null, null)));
        equal(standard, generator.generateApplicationProxy(main, apps, List.of(), List.of(main, main, main)));
        check(!standard.contains("perroute-route-") && !standard.contains("perroute-check-"),
                "Standard routing must not create custom outbounds or check inbounds");

        List<ProxyNode> nodes = java.util.Arrays.asList(second, null, third);
        equal(List.of("second", "third"), XrayConfigGenerator.customRouteKeys(main, apps, nodes));
        Map<String, Object> root = Json.object(generator.generateApplicationProxy(main, apps, List.of(), nodes));
        List<Map<String, Object>> outbounds = ((List<Object>)root.get("outbounds")).stream().map(item -> (Map<String, Object>)item).toList();
        equal(List.of("proxy", "direct", "block", "perroute-route-0", "perroute-route-1"),
                outbounds.stream().map(item -> item.get("tag")).toList());
        equal("main.example", ((Map<String, Object>)outbounds.get(0).get("settings")).get("address"));
        equal("second.example", ((Map<String, Object>)outbounds.get(3).get("settings")).get("address"));
        equal("third.example", ((Map<String, Object>)outbounds.get(4).get("settings")).get("address"));

        List<Map<String, Object>> inbounds = ((List<Object>)root.get("inbounds")).stream().map(item -> (Map<String, Object>)item).toList();
        equal(List.of("perroute-socks", "perroute-http", "perroute-app-0", "perroute-app-1", "perroute-app-2",
                "perroute-check-0", "perroute-check-1", "perroute-api"), inbounds.stream().map(item -> item.get("tag")).toList());
        equal(XrayConfigGenerator.perAppPort(0), inbounds.get(2).get("port"));
        equal(XrayConfigGenerator.routeCheckPort(1), inbounds.get(6).get("port"));
        check(inbounds.stream().allMatch(item -> "127.0.0.1".equals(item.get("listen"))), "Every inbound must stay on loopback");

        List<Map<String, Object>> rules = routingRules(root);
        equal("perroute-api-route", rules.get(0).get("ruleTag"));
        Map<String, Object> mainRule = ruleByTag(rules, "perroute-app-proxy");
        equal(List.of("perroute-socks", "perroute-http", "perroute-app-1"), mainRule.get("inboundTag"));
        equal("proxy", mainRule.get("outboundTag"));
        Map<String, Object> firstCustom = ruleByTag(rules, "perroute-app-route-0");
        equal(List.of("perroute-app-0", "perroute-check-0"), firstCustom.get("inboundTag"));
        equal("perroute-route-0", firstCustom.get("outboundTag"));
        Map<String, Object> secondCustom = ruleByTag(rules, "perroute-app-route-1");
        equal(List.of("perroute-app-2", "perroute-check-1"), secondCustom.get("inboundTag"));
        equal("perroute-route-1", secondCustom.get("outboundTag"));
        validateWithBundledXray(Json.stringify(root));

        Map<String, Object> shared = Json.object(generator.generateApplicationProxy(main, apps, List.of(),
                java.util.Arrays.asList(second, second, null)));
        equal(4, ((List<Object>)shared.get("outbounds")).size());
        equal(List.of("perroute-app-0", "perroute-app-1", "perroute-check-0"),
                ruleByTag(routingRules(shared), "perroute-app-route-0").get("inboundTag"));

        Map<String, Object> engine = Json.object(new ProxiFyreManager().generateConfig(apps));
        equal("127.0.0.1:" + XrayConfigGenerator.perAppPort(2),
                ((Map<String, Object>)((List<Object>)engine.get("proxies")).get(2)).get("socks5ProxyEndpoint"));
    }

    private static void testMultiServerRulePrecedence() {
        ProxyNode main = server("main", "main.example");
        ProxyNode second = server("second", "second.example");
        List<String> apps = List.of(CHROME, TELEGRAM);
        List<RouteRule> source = List.of(
                new RouteRule("block", "Block trackers", "", RouteRule.MatchType.DOMAIN, "keyword:tracker", RouteRule.Action.BLOCK, true),
                new RouteRule("tools", "Work tools", "", RouteRule.MatchType.DOMAIN, "github.com", RouteRule.Action.PROXY, true),
                new RouteRule("chrome", "Chrome proxy", CHROME, RouteRule.MatchType.PORT, "8443", RouteRule.Action.PROXY, true),
                new RouteRule("off", "Disabled", "", RouteRule.MatchType.DOMAIN, "example.org", RouteRule.Action.PROXY, false),
                new RouteRule("direct", "Bank direct", "", RouteRule.MatchType.DOMAIN, "bank.example", RouteRule.Action.DIRECT, true));
        XrayConfigGenerator generator = new XrayConfigGenerator();

        List<Map<String, Object>> standard = routingRules(Json.object(generator.generateApplicationProxy(main, apps, source)));
        equal(List.of("perroute-api-route", "perroute-private-direct", "perroute-custom-block", "perroute-custom-tools",
                "perroute-custom-chrome", "perroute-custom-direct", "perroute-app-proxy"),
                standard.stream().map(rule -> rule.get("ruleTag")).toList());
        equal("proxy", ruleByTag(standard, "perroute-custom-tools").get("outboundTag"));

        Map<String, Object> root = Json.object(generator.generateApplicationProxy(main, apps, source, java.util.Arrays.asList(second, null)));
        List<Map<String, Object>> rules = routingRules(root);
        equal(List.of("perroute-api-route", "perroute-private-direct", "perroute-custom-block",
                "perroute-custom-tools", "perroute-custom-tools#route0", "perroute-custom-chrome#route0",
                "perroute-custom-direct", "perroute-app-proxy", "perroute-app-route-0"),
                rules.stream().map(rule -> rule.get("ruleTag")).toList());

        Map<String, Object> block = ruleByTag(rules, "perroute-custom-block");
        equal(List.of("perroute-socks", "perroute-http", "perroute-app-0", "perroute-app-1"), block.get("inboundTag"));
        equal("block", block.get("outboundTag"));
        Map<String, Object> toolsMain = ruleByTag(rules, "perroute-custom-tools");
        equal(List.of("perroute-socks", "perroute-http", "perroute-app-1"), toolsMain.get("inboundTag"));
        equal("proxy", toolsMain.get("outboundTag"));
        Map<String, Object> toolsCustom = ruleByTag(rules, "perroute-custom-tools#route0");
        equal(List.of("perroute-app-0"), toolsCustom.get("inboundTag"));
        equal("perroute-route-0", toolsCustom.get("outboundTag"));
        equal(toolsMain.get("domain"), toolsCustom.get("domain"));
        equal(ruleIndex(rules, "perroute-custom-tools") + 1, ruleIndex(rules, "perroute-custom-tools#route0"));
        Map<String, Object> chrome = ruleByTag(rules, "perroute-custom-chrome#route0");
        equal(List.of("perroute-app-0"), chrome.get("inboundTag"));
        equal("perroute-route-0", chrome.get("outboundTag"));
        Map<String, Object> direct = ruleByTag(rules, "perroute-custom-direct");
        equal(List.of("perroute-socks", "perroute-http", "perroute-app-0", "perroute-app-1"), direct.get("inboundTag"));
        equal("direct", direct.get("outboundTag"));
        check(rules.stream().noneMatch(rule -> String.valueOf(rule.get("inboundTag")).contains("perroute-check-0")
                        && String.valueOf(rule.get("ruleTag")).startsWith("perroute-custom-")),
                "User rules must not apply to the route-check inbound");

        equal("tools", XrayConfigGenerator.customRuleId("perroute-custom-tools#route0"));
        equal("tools", XrayConfigGenerator.customRuleId("perroute-custom-tools"));
        equal(null, XrayConfigGenerator.customRuleId("perroute-app-proxy"));
        equal(java.util.Set.of("block", "tools", "chrome", "direct"), rules.stream()
                .map(rule -> XrayConfigGenerator.customRuleId(String.valueOf(rule.get("ruleTag"))))
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet()));
        validateWithBundledXray(Json.stringify(root));
    }

    @SuppressWarnings("unchecked")
    private static void testProfileWithCustomServers() {
        ProxyNode profile = embeddedProfile("profile");
        ProxyNode second = server("second", "second.example");
        List<String> apps = List.of(CHROME, TELEGRAM);
        List<RouteRule> source = List.of(
                new RouteRule("tools", "Work tools", "", RouteRule.MatchType.DOMAIN, "github.com", RouteRule.Action.PROXY, true));
        XrayConfigGenerator generator = new XrayConfigGenerator();

        String standard = generator.generateApplicationProxy(profile, apps, source);
        equal(standard, generator.generateApplicationProxy(profile, apps, source, java.util.Arrays.asList(null, null)));
        Map<String, Object> intranet = routingRules(Json.object(standard)).stream()
                .filter(rule -> rule.containsKey("domain") && !rule.containsKey("ruleTag")).findFirst().orElseThrow();
        check(!intranet.containsKey("inboundTag"), "Standard mode must leave the profile's own rules untouched");

        Map<String, Object> root = Json.object(generator.generateApplicationProxy(profile, apps, source, java.util.Arrays.asList(null, second)));
        List<Object> outbounds = (List<Object>)root.get("outbounds");
        check(outbounds.stream().map(Map.class::cast).anyMatch(item -> "perroute-route-0".equals(item.get("tag"))),
                "A custom server must be added next to the profile's outbounds");
        List<Map<String, Object>> rules = routingRules(root);
        Map<String, Object> confined = rules.stream()
                .filter(rule -> rule.containsKey("domain") && !rule.containsKey("ruleTag")).findFirst().orElseThrow();
        equal(List.of("perroute-socks", "perroute-http", "perroute-app-0"), confined.get("inboundTag"));
        equal("proxy-a", ruleByTag(rules, "perroute-custom-tools").get("outboundTag"));
        equal("perroute-route-0", ruleByTag(rules, "perroute-custom-tools#route0").get("outboundTag"));
        equal("proxy-a", ruleByTag(rules, "perroute-app-proxy").get("outboundTag"));
        equal(List.of("perroute-app-1", "perroute-check-0"), ruleByTag(rules, "perroute-app-route-0").get("inboundTag"));
        validateWithBundledXray(Json.stringify(root));
    }

    private static ProxyNode balancedProfile(String key, String host) {
        String json = """
                {"remarks":"Balanced %s",
                 "dns":{"servers":["1.1.1.1"]},
                 "observatory":{"subjectSelector":["proxy-"],"probeUrl":"https://www.gstatic.com/generate_204","probeInterval":"30s"},
                 "outbounds":[
                  {"tag":"proxy-one","protocol":"vless","settings":{"vnext":[{"address":"one.%s","port":443,"users":[{"id":"33333333-3333-3333-3333-333333333333","encryption":"none"}]}]}},
                  {"tag":"proxy-two","protocol":"vless","settings":{"vnext":[{"address":"two.%s","port":443,"users":[{"id":"44444444-4444-4444-4444-444444444444","encryption":"none"}]}]},
                   "streamSettings":{"network":"tcp","sockopt":{"dialerProxy":"fragment"}}},
                  {"tag":"fragment","protocol":"freedom","settings":{}},
                  {"tag":"direct","protocol":"freedom","settings":{}},
                  {"tag":"block","protocol":"blackhole","settings":{}}],
                 "routing":{"domainStrategy":"IPIfNonMatch",
                  "balancers":[{"tag":"balancer","selector":["proxy-"],"strategy":{"type":"leastPing"},"fallbackTag":"proxy-one"}],
                  "rules":[
                   {"type":"field","inboundTag":["dns-in"],"outboundTag":"direct"},
                   {"type":"field","domain":["domain:local.%s"],"outboundTag":"direct"},
                   {"type":"field","port":"6881-6889","outboundTag":"block"},
                   {"type":"field","network":"tcp,udp","balancerTag":"balancer"}]}}
                """.formatted(host, host, host, host);
        ProxyNode parsed = new SubscriptionParser().parse(json).nodes().get(0);
        return new ProxyNode(key, parsed.name(), parsed.protocol(), parsed.address(), parsed.port(),
                parsed.credential(), parsed.options(), parsed.sourceLink());
    }

    @SuppressWarnings("unchecked")
    private static void testEmbeddedProfileAsCustomServer() {
        ProxyNode mainProfile = balancedProfile("main-profile", "main.example");
        ProxyNode customProfile = balancedProfile("custom-profile", "custom.example");
        ProxyNode plain = server("plain", "plain.example");
        List<String> apps = List.of(CHROME, TELEGRAM, DISCORD);
        List<RouteRule> source = List.of(
                new RouteRule("tools", "Work tools", "", RouteRule.MatchType.DOMAIN, "github.com", RouteRule.Action.PROXY, true));
        XrayConfigGenerator generator = new XrayConfigGenerator();

        Map<String, Object> root = Json.object(generator.generateApplicationProxy(mainProfile, apps, source,
                java.util.Arrays.asList(customProfile, plain, null)));
        List<String> tags = ((List<Object>)root.get("outbounds")).stream().map(item -> String.valueOf(((Map<String, Object>)item).get("tag"))).toList();
        check(tags.containsAll(List.of("proxy-one", "proxy-two", "fragment", "direct", "block")), "The main profile keeps its own tags: " + tags);
        check(tags.containsAll(List.of("perroute-route-0/proxy-one", "perroute-route-0/proxy-two", "perroute-route-0/fragment",
                "perroute-route-0/direct", "perroute-route-0/block", "perroute-route-1")), "The custom profile lives in its own namespace: " + tags);
        equal(tags.size(), (int)tags.stream().distinct().count());
        check(tags.stream().filter(tag -> tag.startsWith("proxy-")).count() == 2,
                "Nothing of a custom server may match the main profile's balancer selector");

        Map<String, Object> chained = ((List<Object>)root.get("outbounds")).stream().map(item -> (Map<String, Object>)item)
                .filter(item -> "perroute-route-0/proxy-two".equals(item.get("tag"))).findFirst().orElseThrow();
        equal("perroute-route-0/fragment", ((Map<String, Object>)((Map<String, Object>)chained.get("streamSettings")).get("sockopt")).get("dialerProxy"));
        check(String.valueOf(chained).contains("two.custom.example"), "The namespaced outbound must still point at the custom profile's server");

        Map<String, Object> routing = (Map<String, Object>)root.get("routing");
        List<Map<String, Object>> balancers = ((List<Object>)routing.get("balancers")).stream().map(item -> (Map<String, Object>)item).toList();
        equal(List.of("balancer", "perroute-route-0/balancer"), balancers.stream().map(item -> item.get("tag")).toList());
        equal(List.of("perroute-route-0/proxy-"), balancers.get(1).get("selector"));
        equal("perroute-route-0/proxy-one", balancers.get(1).get("fallbackTag"));
        equal(List.of("proxy-", "perroute-route-0/proxy-"), ((Map<String, Object>)root.get("observatory")).get("subjectSelector"));
        equal("https://www.gstatic.com/generate_204", ((Map<String, Object>)root.get("observatory")).get("probeUrl"));

        List<Map<String, Object>> rules = routingRules(root);
        List<String> mainInbounds = List.of("perroute-socks", "perroute-http", "perroute-app-2");
        List<String> customInbounds = List.of("perroute-app-0");
        check(rules.stream().anyMatch(rule -> "direct".equals(rule.get("outboundTag")) && mainInbounds.equals(rule.get("inboundTag"))
                && List.of("domain:local.main.example").equals(rule.get("domain"))), "The main profile's direct rule must stay with Main's apps");
        check(rules.stream().anyMatch(rule -> "block".equals(rule.get("outboundTag")) && mainInbounds.equals(rule.get("inboundTag"))),
                "The main profile's block rule must stay with Main's apps");
        check(rules.stream().anyMatch(rule -> "perroute-route-0/direct".equals(rule.get("outboundTag")) && customInbounds.equals(rule.get("inboundTag"))
                && List.of("domain:local.custom.example").equals(rule.get("domain"))), "The custom profile's direct rule must follow its app");
        check(rules.stream().anyMatch(rule -> "perroute-route-0/block".equals(rule.get("outboundTag")) && customInbounds.equals(rule.get("inboundTag"))),
                "The custom profile's block rule must follow its app");
        check(rules.stream().noneMatch(rule -> List.of("dns-in").equals(rule.get("inboundTag"))), "Rules tied to the profile's own inbounds are dropped");

        equal("balancer", ruleByTag(rules, "perroute-app-proxy").get("balancerTag"));
        equal(mainInbounds, ruleByTag(rules, "perroute-app-proxy").get("inboundTag"));
        Map<String, Object> customApps = ruleByTag(rules, "perroute-app-route-0");
        equal("perroute-route-0/balancer", customApps.get("balancerTag"));
        equal(List.of("perroute-app-0", "perroute-check-0"), customApps.get("inboundTag"));
        equal("perroute-route-1", ruleByTag(rules, "perroute-app-route-1").get("outboundTag"));
        equal("balancer", ruleByTag(rules, "perroute-custom-tools").get("balancerTag"));
        equal("perroute-route-0/balancer", ruleByTag(rules, "perroute-custom-tools#route0").get("balancerTag"));
        equal("perroute-route-1", ruleByTag(rules, "perroute-custom-tools#route1").get("outboundTag"));
        equal(Map.of("servers", List.of("1.1.1.1")), root.get("dns"));
        validateWithBundledXray(Json.stringify(root));

        Map<String, Object> mixed = Json.object(generator.generateApplicationProxy(plain, apps, source,
                java.util.Arrays.asList(customProfile, null, null)));
        List<Map<String, Object>> mixedRules = routingRules(mixed);
        equal("proxy", ruleByTag(mixedRules, "perroute-app-proxy").get("outboundTag"));
        equal("perroute-route-0/balancer", ruleByTag(mixedRules, "perroute-app-route-0").get("balancerTag"));
        equal(1, ((List<Object>)((Map<String, Object>)mixed.get("routing")).get("balancers")).size());
        equal(List.of("perroute-route-0/proxy-"), ((Map<String, Object>)mixed.get("observatory")).get("subjectSelector"));
        check(!mixed.containsKey("dns"), "A custom profile's DNS section is global in Xray and must not be adopted");
        validateWithBundledXray(Json.stringify(mixed));

        equal(List.of("custom-profile", "plain"), XrayConfigGenerator.customRouteKeys(mainProfile, apps,
                java.util.Arrays.asList(customProfile, plain, null)));
        String standard = generator.generateApplicationProxy(mainProfile, apps, source);
        check(!standard.contains("perroute-route-"), "Without custom servers the profile config is unchanged");
    }

    private static void testTrafficReport() throws Exception {
        Path file = Files.createTempDirectory("perroute-report-test").resolve("history.json");
        TrafficHistoryStore store = new TrafficHistoryStore(file);
        store.record(CHROME, 4000, 400);
        store.record(TELEGRAM, 1000, 100);
        var all = store.report("");
        equal(5000L, all.todayDownloadBytes());
        equal(500L, all.monthUploadBytes());
        equal(2, all.monthApplications().size());
        equal(CHROME, all.todayApplications().get(0).applicationPath());
        equal(1, all.days().size());
        equal(5000L, all.days().get(0).downloadBytes());
        var one = store.report("c:\\apps\\telegram.exe");
        equal(1000L, one.todayDownloadBytes());
        equal(100L, one.monthUploadBytes());
        equal(1000L, one.days().get(0).downloadBytes());
        equal(2, one.monthApplications().size());
        equal(0L, store.report("C:\\Apps\\Unknown.exe").monthDownloadBytes());
        store.close();
        removeTemporaryDirectory(file.getParent());
    }

    private static void equal(Object expected, Object actual) {
        assertions++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
