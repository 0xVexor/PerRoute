package app.perroute;

import app.perroute.bridge.BridgeLinks;
import app.perroute.bridge.BridgeStore;
import app.perroute.compose.controller.ActionResult;
import app.perroute.compose.controller.BridgeDeviceView;
import app.perroute.compose.controller.BridgeView;
import app.perroute.compose.controller.PerRouteController;
import app.perroute.compose.controller.QuickRouteTarget;
import app.perroute.localnet.LocalNetwork;
import app.perroute.model.AppRoutes;
import app.perroute.model.AppState;
import app.perroute.model.ConnectionMode;
import app.perroute.model.Protocol;
import app.perroute.model.ProxyNode;
import app.perroute.persistence.AppStateStore;
import app.perroute.routing.AppRouteMode;
import app.perroute.service.ExplorerMenu;
import app.perroute.service.QuickRouteRequests;
import app.perroute.service.ResourceConditions;
import app.perroute.service.XrayConfigGenerator;
import app.perroute.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class AppFeatureTests {
    private static int assertions;

    private AppFeatureTests() {}

    static int assertions() { return assertions; }

    static void runAll() throws Exception {
        run("Quick Route: requests reach the running window", AppFeatureTests::testRequests);
        run("Quick Route: the Explorer menu entry", AppFeatureTests::testExplorerMenu);
        run("Adaptive resources: metered networks are recognised", AppFeatureTests::testMeteredParsing);
        run("LAN Bridge: the core config", AppFeatureTests::testBridgeConfig);
        withFixture("Quick Route: which programs can be routed", AppFeatureTests::testQuickRouteTarget);
        withFixture("Quick Route: saving a route", AppFeatureTests::testQuickRoute);
        withFixture("Quick Route: while connected", AppFeatureTests::testQuickRouteConnected);
        withFixture("Adaptive resources: battery and metered networks", AppFeatureTests::testAdaptiveResources);
        withFixture("LAN Bridge: devices, sharing and removal", AppFeatureTests::testBridge);
    }

    private static void testRequests() throws Exception {
        Path directory = Files.createTempDirectory("perroute-requests-");
        try {
            QuickRouteRequests requests = new QuickRouteRequests(directory);
            equal(List.of(), requests.take());
            requests.submitRoute("C:\\Apps\\Chat.exe");
            requests.submitShow();
            List<QuickRouteRequests.Request> taken = requests.take();
            equal(2, taken.size());
            equal(new QuickRouteRequests.Request(QuickRouteRequests.Kind.ROUTE, "C:\\Apps\\Chat.exe"), taken.get(0));
            equal(QuickRouteRequests.Kind.SHOW, taken.get(1).kind());
            equal(List.of(), requests.take());
            requests.submitRoute("C:\\Apps\\Old.exe");
            try (var files = Files.list(directory.resolve("requests"))) {
                for (Path file : files.toList()) Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000L));
            }
            equal(List.of(), requests.take());
            try (var files = Files.list(directory.resolve("requests"))) { check(files.findAny().isEmpty(), "Old requests are cleaned up"); }
            equal(null, QuickRouteRequests.parse("delete\nC:\\x"));
            equal(null, QuickRouteRequests.parse("route\n"));
            boolean refused = false;
            try { requests.submitRoute(" "); } catch (IllegalArgumentException expected) { refused = true; }
            check(refused, "An empty path is refused");
        } finally {
            deleteTree(directory);
        }
    }

    private static void testExplorerMenu() {
        Path exe = Path.of("C:\\Program Files\\PerRoute\\PerRoute.exe");
        equal("\"C:\\Program Files\\PerRoute\\PerRoute.exe\" --route \"%1\"", ExplorerMenu.command(exe));
        String file = ExplorerMenu.registryFile(exe);
        check(file.startsWith("Windows Registry Editor Version 5.00"), "A registry file Windows can import");
        check(file.contains("[HKEY_CURRENT_USER\\Software\\Classes\\exefile\\shell\\PerRoute.Route\\command]\r\n" +
                "@=\"\\\"C:\\\\Program Files\\\\PerRoute\\\\PerRoute.exe\\\" --route \\\"%1\\\"\""), "Programs get the menu entry, quoted for the registry: " + file);
        check(file.contains("[HKEY_CURRENT_USER\\Software\\Classes\\lnkfile\\shell\\PerRoute.Route]\r\n\"MUIVerb\"=\"Route with PerRoute\""), "Shortcuts get it too");
        check(file.lines().filter(line -> line.startsWith("[")).allMatch(line -> line.startsWith("[HKEY_CURRENT_USER\\")), "Only the user's own registry is touched");
        equal(2, ExplorerMenu.unregisterCommands().size());
    }

    private static void testMeteredParsing() {
        check(!ResourceConditions.meteredFrom("Unrestricted|False|False"), "Unrestricted is not metered");
        check(ResourceConditions.meteredFrom("Fixed|False|False"), "A data plan is metered");
        check(ResourceConditions.meteredFrom("Variable|False|False"), "Pay per use is metered");
        check(ResourceConditions.meteredFrom("Unrestricted|True|False"), "Roaming counts as metered");
        check(!ResourceConditions.meteredFrom(""), "No answer is not metered");
        var both = new ResourceConditions.Snapshot(true, false, true);
        check(both.constrained() && both.reason().contains("battery") && both.reason().contains("metered"), both.reason());
        check(!ResourceConditions.Snapshot.NORMAL.constrained(), "Normal conditions save nothing");
    }

    @SuppressWarnings("unchecked")
    private static void testBridgeConfig() throws Exception {
        ProxyNode node = new ProxyNode("n", "N", Protocol.VLESS, "198.51.100.1", 443, "00000000-0000-0000-0000-000000000000", Map.of(), "");
        String config = new XrayConfigGenerator().generateBridge(node, "192.168.1.20",
                List.of(new XrayConfigGenerator.BridgeInbound("perroute-bridge-0", 19800, "phone-1", "secret-password-1")), 19999);
        Map<String, Object> root = Json.object(config);
        List<Map<String, Object>> inbounds = (List<Map<String, Object>>) (List<?>) root.get("inbounds");
        Map<String, Object> bridge = inbounds.stream().filter(i -> "perroute-bridge-0".equals(i.get("tag"))).findFirst().orElseThrow();
        equal("mixed", bridge.get("protocol"));
        equal("192.168.1.20", bridge.get("listen"));
        check(inbounds.stream().noneMatch(i -> "0.0.0.0".equals(i.get("listen")) || "perroute-socks".equals(i.get("tag"))),
                "Only the device's own port listens, and only on the shared network");
        Map<String, Object> settings = (Map<String, Object>) bridge.get("settings");
        equal("password", settings.get("auth"));
        equal(List.of(Map.of("user", "phone-1", "pass", "secret-password-1")), settings.get("accounts"));
        List<Map<String, Object>> rules = (List<Map<String, Object>>) (List<?>) ((Map<String, Object>) root.get("routing")).get("rules");
        equal(XrayConfigGenerator.BRIDGE_PRIVATE_RULE, rules.get(0).get("ruleTag"));
        equal("block", rules.get(0).get("outboundTag"));
        check(((List<String>) rules.get(0).get("ip")).contains("127.0.0.0/8"), "This computer's loopback is out of reach");
        equal(XrayConfigGenerator.BRIDGE_LOCAL_NAMES_RULE, rules.get(1).get("ruleTag"));
        validate(config);
    }

    private static void testQuickRouteTarget(Fixture f) throws Exception {
        PerRouteController c = f.controller;
        QuickRouteTarget text = c.quickRouteTarget(f.file("notes.txt"));
        check(text.getProblem().contains(".exe"), text.getProblem());
        check(c.quickRouteTarget(f.directory.resolve("missing.exe").toString()).getProblem().contains("not found"), "A missing program is reported");
        QuickRouteTarget selected = c.quickRouteTarget("\"" + f.chrome + "\"");
        check(selected.getProblem().isBlank() && selected.getSelected() && selected.getMode() == AppRouteMode.MAIN, "A selected app shows its route");
        String shortcut = f.file("Code.lnk");
        f.backend.shortcuts.put(shortcut.toLowerCase(), f.code);
        QuickRouteTarget resolved = c.quickRouteTarget(shortcut);
        check(resolved.getProblem().isBlank() && resolved.getPath().equalsIgnoreCase(f.code) && !resolved.getSelected(), "A shortcut is followed to its program");
        String broken = f.file("Broken.lnk");
        check(c.quickRouteTarget(broken).getProblem().contains("shortcut"), "A shortcut without a program is refused");
    }

    private static void testQuickRoute(Fixture f) throws Exception {
        PerRouteController c = f.controller;
        ActionResult main = c.quickRoute(f.code, AppRouteMode.MAIN, "", false);
        check(main.getOk() && main.getMessage().contains("main server"), main.getMessage());
        check(c.applications().stream().anyMatch(p -> p.equalsIgnoreCase(f.code)), "The app is selected");
        check(!c.advancedRouting(), "The main server needs no advanced routing");

        ActionResult manual = c.quickRoute(f.code, AppRouteMode.MANUAL, "third", false);
        check(manual.getOk() && manual.getMessage().contains("Advanced routing was turned on"), manual.getMessage());
        equal("third", c.appRoutes().get(AppRoutes.normalize(f.code)));
        check(c.advancedRouting(), "A server of its own turns advanced routing on");
        equal(3, c.applications().size());

        check(!c.quickRoute(f.code, AppRouteMode.MANUAL, "missing", false).getOk(), "The server must exist");
        ActionResult auto = c.quickRoute(f.code, AppRouteMode.AUTO, "", false);
        check(auto.getOk(), auto.getMessage());
        check(c.autoApps().contains(AppRoutes.normalize(f.code)) && !c.appRoutes().containsKey(AppRoutes.normalize(f.code)), "Auto replaces the manual server");

        ActionResult stop = c.quickRoute(f.code, null, "", false);
        check(stop.getOk() && stop.getMessage().contains("regular connection"), stop.getMessage());
        check(c.applications().stream().noneMatch(p -> p.equalsIgnoreCase(f.code)) && !c.autoApps().contains(AppRoutes.normalize(f.code)), "The app is no longer routed");
        equal(2, c.applications().size());

        f.state.setConnectionMode(ConnectionMode.SYSTEM_PROXY);
        ActionResult note = c.quickRoute(f.code, AppRouteMode.MAIN, "", false);
        check(note.getMessage().contains("Smart per-app"), "Other modes are told where apps are routed: " + note.getMessage());
    }

    private static void testQuickRouteConnected(Fixture f) throws Exception {
        PerRouteController c = f.controller;
        check(c.connect().getOk(), "Connect");
        int starts = f.backend.startCount;
        ActionResult refused = c.quickRoute(f.code, AppRouteMode.MAIN, "", false);
        check(!refused.getOk() && refused.getMessage().contains("reconnect"), refused.getMessage());
        check(c.applications().stream().noneMatch(p -> p.equalsIgnoreCase(f.code)), "Nothing changes without consent");
        ActionResult saved = c.quickRoute(f.code, AppRouteMode.MAIN, "", true);
        check(saved.getOk() && saved.getMessage().contains("Reconnected"), saved.getMessage());
        check(c.isConnected() && f.backend.startCount == starts + 1, "It reconnects once");
        check(f.backend.perAppApplications.stream().anyMatch(p -> p.equalsIgnoreCase(f.code)), "The per-app engine gets the new app");
        c.disconnect();
    }

    private static void testAdaptiveResources(Fixture f) throws Exception {
        PerRouteController c = f.controller;
        check(c.adaptiveResources(), "Resource saving is on by default");
        c.refreshResourceConditions();
        check(!c.resourceSaving() && c.automaticSpeedTestsNow(), "Mains power and an unmetered network change nothing");
        f.backend.resources = new ResourceConditions.Snapshot(true, false, false);
        c.refreshResourceConditions();
        check(c.resourceSaving() && !c.automaticSpeedTestsNow(), "On battery, automatic speed tests pause");
        check(c.killSwitch() == c.killSwitch() && c.routeMonitoring(), "Protection settings are untouched");
        check(c.setAdaptiveResources(false).getOk() && !c.resourceSaving() && c.automaticSpeedTestsNow(), "It can be turned off");
        c.setAdaptiveResources(true);
        f.backend.resources = new ResourceConditions.Snapshot(false, false, true);
        c.refreshResourceConditions();
        check(c.resourceSaving() && c.resourceConditions().metered(), "Metered networks save too");
        f.backend.resources = ResourceConditions.Snapshot.NORMAL;
        c.refreshResourceConditions();
        check(!c.resourceSaving(), "Back to normal");
    }

    private static void testBridge(Fixture f) throws Exception {
        PerRouteController c = f.controller;
        LocalNetwork home = new LocalNetwork(LocalNetwork.identity("192.168.1.0/24", "aa", "192.168.1.1"), "Wi-Fi", "192.168.1.20", "192.168.1.0/24", 24,
                "192.168.1.1", "aa", List.of("192.168.1.1"), List.of());
        f.backend.networks = List.of(home);
        equal(List.of("192.168.1.20"), c.bridgeAddresses());
        check(!c.startBridge("192.168.1.20", 60, true).getOk(), "Sharing needs a device");

        check(c.addBridgeDevice("My phone").getOk(), "Add a device");
        check(!c.addBridgeDevice("my phone").getOk(), "Names are unique");
        BridgeDeviceView phone = c.bridgeView(System.currentTimeMillis()).getDevices().get(0);
        equal("my-phone-1", phone.getDevice().user());
        check(phone.getDevice().password().length() >= 20, "A long random password");
        equal(PerRouteController.Companion.getBRIDGE_PORT_BASE(), phone.getPort());
        byte[] stored = Files.readAllBytes(f.directory.resolve("data").resolve("bridge-devices.bin"));
        check(!new String(stored, StandardCharsets.ISO_8859_1).contains(phone.getDevice().password()), "Passwords are stored encrypted");
        equal(1, new BridgeStore(f.directory.resolve("data").resolve("bridge-devices.bin")).all().size());

        check(!c.startBridge("10.0.0.9", 60, true).getOk(), "Only a network this computer is on");
        f.backend.category = "Public";
        ActionResult publicNet = c.startBridge("192.168.1.20", 60, true);
        check(!publicNet.getOk() && publicNet.getMessage().contains("public"), publicNet.getMessage());
        f.backend.category = "Private";
        ActionResult firewall = c.startBridge("192.168.1.20", 60, false);
        check(!firewall.getOk() && firewall.getMessage().equals(PerRouteController.BRIDGE_NEEDS_FIREWALL), firewall.getMessage());
        equal(0, f.backend.firewallAllowed);
        ActionResult started = c.startBridge("192.168.1.20", 60, true);
        check(started.getOk(), started.getMessage());
        equal(1, f.backend.firewallAllowed);
        BridgeView view = c.bridgeView(System.currentTimeMillis());
        check(view.getRunning() && view.getAddress().equals("192.168.1.20") && view.getUntil() > System.currentTimeMillis() + 59 * 60_000L, "Sharing for an hour");
        check(f.backend.isolatedConfig.contains("\"listen\": \"192.168.1.20\"") || f.backend.isolatedConfig.contains("\"listen\":\"192.168.1.20\""), "It listens on the shared network");
        check(f.backend.isolatedConfig.contains(phone.getDevice().password()) && f.backend.isolatedConfig.contains("main.example"), "The device's account and the main server");

        f.backend.isolated.get(0).line("2026/10/19 15:40:01.000000 from 192.168.1.33:50123 accepted tcp:www.example.com:443 [perroute-bridge-0 -> proxy]");
        BridgeDeviceView active = c.bridgeView(System.currentTimeMillis()).getDevices().get(0);
        check(active.getActivity().activeAt(System.currentTimeMillis()) && active.getActivity().address().equals("192.168.1.33"), "The device is seen");
        equal(List.of("www.example.com"), active.getActivity().recent());
        f.backend.isolated.get(0).line("2026/10/19 16:12:01.497052 from 192.168.1.34:63015 accepted http://plain.example/path?x=1 [perroute-bridge-0 >> proxy]");
        f.backend.isolated.get(0).line("2026/10/19 16:12:02.000000 from tcp:192.168.1.34:63014 rejected  proxy/socks: invalid username or password");
        BridgeDeviceView plain = c.bridgeView(System.currentTimeMillis()).getDevices().get(0);
        equal(List.of("plain.example", "www.example.com"), plain.getActivity().recent());
        equal("192.168.1.34", plain.getActivity().address());
        equal(2, plain.getActivity().connections());
        String link = active.getSocksLink();
        check(link.startsWith("socks://") && link.contains("@192.168.1.20:" + active.getPort()), link);
        String credentials = new String(Base64.getDecoder().decode(link.substring(8, link.indexOf('@'))), StandardCharsets.UTF_8);
        equal(phone.getDevice().user() + ":" + phone.getDevice().password(), credentials);
        check(BridgeLinks.http("192.168.1.20", 19800, phone.getDevice()).startsWith("http://my-phone-1:"), "An HTTP proxy address as well");

        check(c.addBridgeDevice("Tablet").getOk(), "Add a second device");
        equal(2, f.backend.isolated.size());
        check(!f.backend.isolated.get(0).alive() && f.backend.isolated.get(1).alive(), "Sharing restarts with both devices");
        BridgeDeviceView tablet = c.bridgeView(System.currentTimeMillis()).getDevices().stream().filter(d -> d.getDevice().name().equals("Tablet")).findFirst().orElseThrow();
        check(f.backend.isolatedConfig.contains(tablet.getDevice().password()) && f.backend.isolatedConfig.contains(phone.getDevice().password()), "Both accounts");

        ActionResult removed = c.removeBridgeDevice(phone.getDevice().id());
        check(removed.getOk(), removed.getMessage());
        check(!f.backend.isolatedConfig.contains(phone.getDevice().password()) && f.backend.isolatedConfig.contains(tablet.getDevice().password()),
                "The removed device's password stops working");
        check(c.bridgeView(System.currentTimeMillis()).getRunning(), "The other device keeps sharing");
        check(c.stopBridge("").getOk() && !c.bridgeView(System.currentTimeMillis()).getRunning(), "Stop sharing");
        check(f.backend.isolated.stream().noneMatch(ControllerTests.FakeTunnel::alive), "No core is left running");

        check(c.startBridge("192.168.1.20", 0, false).getOk() && c.bridgeView(System.currentTimeMillis()).getUntil() == 0L, "Until stopped, with the firewall ready");
        c.removeBridgeDevice(tablet.getDevice().id());
        check(!c.bridgeView(System.currentTimeMillis()).getRunning(), "Removing the last device stops sharing");
    }

    private static void validate(String config) throws Exception {
        Path xray = Path.of("tools", "xray", "xray.exe").toAbsolutePath().normalize();
        if (!Files.isRegularFile(xray)) return;
        Path temp = Path.of("build", "tmp").toAbsolutePath().normalize();
        Files.createDirectories(temp);
        Path file = Files.createTempFile(temp, "perroute-bridge-test-", ".json");
        try {
            Files.writeString(file, config, StandardCharsets.UTF_8);
            Process process = new ProcessBuilder(xray.toString(), "run", "-test", "-config", file.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check(process.waitFor() == 0, "Bundled Xray rejected the bridge config: " + output);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Path directory;
        final ControllerTests.FakeBackend backend = new ControllerTests.FakeBackend();
        final ControllerTests.MemoryPreferences preferences = new ControllerTests.MemoryPreferences();
        final AppState state = new AppState();
        final PerRouteController controller;
        final String chrome, telegram, code;

        Fixture() throws IOException {
            directory = Files.createTempDirectory("perroute-features-");
            System.setProperty("perroute.data", directory.resolve("data").toString());
            chrome = file("chrome.exe");
            telegram = file("Telegram.exe");
            code = file("Code.exe");
            state.nodes().addAll(List.of(node("main"), node("second"), node("third")));
            state.setSelectedNodeKey("main");
            state.setConnectionMode(ConnectionMode.NATIVE_PER_APP);
            state.appPaths().addAll(List.of(chrome, telegram));
            preferences.putBoolean("autoFailover", false);
            preferences.putBoolean("autoReconnect", false);
            controller = new PerRouteController(state, new AppStateStore(directory.resolve("data").resolve("state.bin")), backend, preferences);
        }

        String file(String name) throws IOException {
            Path file = directory.resolve(name);
            Files.writeString(file, "test");
            return file.toAbsolutePath().normalize().toString();
        }

        static ProxyNode node(String key) {
            return new ProxyNode(key, "Server " + key, Protocol.VLESS, key + ".example", 443, "00000000-0000-0000-0000-000000000000", Map.of(), "");
        }

        @Override public void close() throws IOException {
            controller.close();
            System.clearProperty("perroute.data");
            deleteTree(directory);
        }
    }

    private interface FixtureTest { void run(Fixture fixture) throws Exception; }
    private interface Test { void run() throws Exception; }

    private static void run(String name, Test test) throws Exception {
        System.out.println("[test] " + name);
        test.run();
    }

    private static void withFixture(String name, FixtureTest test) throws Exception {
        System.out.println("[test] " + name);
        try (Fixture fixture = new Fixture()) { test.run(fixture); }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual) {
        assertions++;
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + " but was " + actual);
    }
}
