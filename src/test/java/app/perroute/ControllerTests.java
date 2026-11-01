package app.perroute;

import app.perroute.compose.controller.ActionResult;
import app.perroute.backend.api.ConnectionBackend;
import app.perroute.compose.controller.ConnectionPhase;
import app.perroute.compose.controller.ImportPreview;
import app.perroute.backend.api.IsolatedTunnel;
import app.perroute.compose.controller.PerRouteController;
import app.perroute.compose.controller.RouteCheckState;
import app.perroute.model.AppRoutes;
import app.perroute.model.AppState;
import app.perroute.model.ConnectionMode;
import app.perroute.model.Protocol;
import app.perroute.model.ProxyNode;
import app.perroute.model.RouteRule;
import app.perroute.parser.ParseResult;
import app.perroute.persistence.AppStateStore;
import app.perroute.service.ProxyHealthCheck;
import app.perroute.service.RoutePlanner;
import app.perroute.service.XrayConfigGenerator;
import app.perroute.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;

final class ControllerTests {
    private static int assertions;
    private ControllerTests() {}

    static int assertions() { return assertions; }

    static void runPlatformBoundaryChecks() throws Exception {
        run("Controller: platform maintenance is injected", ControllerTests::testPlatformMaintenance);
        run("Controller: system proxy lifecycle and rollback", ControllerTests::testSystemProxyLifecycle);
        run("Controller: local proxy lifecycle", ControllerTests::testLocalProxyLifecycle);
        run("Controller: v3 live main switch and restart fallback", ControllerTests::testLiveMainSwitch);
    }

    private static void testSystemProxyLifecycle(Fixture f) {
        f.state.appPaths().clear();
        f.controller.setMode(ConnectionMode.SYSTEM_PROXY);
        check(f.controller.connect().getOk(), "System proxy connects through the injected backend");
        equal(ConnectionPhase.CONNECTED, f.controller.connectionPhase());
        equal(1, f.backend.proxyApplications);
        int restorations = f.backend.proxyRestorations;
        int stops = f.backend.stopCount;
        f.backend.rejectValidation = "second.example";
        check(!f.controller.switchNode("second").getOk(), "Invalid switch must fail before stopping the core");
        equal(stops, f.backend.stopCount);
        check(f.controller.isConnected(), "Rejected validation leaves the connection running");
        f.backend.rejectValidation = null;
        f.backend.failStart = "second.example";
        check(!f.controller.switchNode("second").getOk(), "Failed switch reports rollback");
        check(f.controller.isConnected(), "The previous server is restored after a failed switch");
        equal("main", f.controller.selectedNodeKey());
        equal(restorations, f.backend.proxyRestorations);
        equal(1, f.backend.proxyApplications);
        check(f.controller.disconnect().getOk(), "System proxy disconnect succeeds");
        equal(restorations + 1, f.backend.proxyRestorations);
        check(!f.backend.running, "Disconnect stops the backend core");
        equal(ConnectionPhase.DISCONNECTED, f.controller.connectionPhase());
        f.backend.failStart = "main.example";
        check(!f.controller.connect().getOk(), "Initial startup failure is reported");
        equal(ConnectionPhase.DISCONNECTED, f.controller.connectionPhase());
        equal(restorations + 2, f.backend.proxyRestorations);
    }

    private static void testLocalProxyLifecycle(Fixture f) {
        f.state.appPaths().clear();
        f.controller.setMode(ConnectionMode.BROWSER_PROXY);
        check(f.controller.connect().getOk(), "Local proxy connects through the injected backend");
        check(f.backend.running, "Local core is running");
        equal(0, f.backend.proxyApplications);
        check(f.controller.disconnect().getOk(), "Local proxy disconnect succeeds");
        check(!f.backend.running, "Local core stops");
    }

    private static void testLiveMainSwitch(Fixture f) {
        f.state.appPaths().clear();
        f.controller.setMode(ConnectionMode.SYSTEM_PROXY);
        f.controller.setRouteMonitoring(true);
        f.backend.supportsRuntimeChange = true;
        check(f.controller.connect().getOk(), "Connect with runtime control");
        int starts = f.backend.startCount;
        int stops = f.backend.stopCount;
        check(f.controller.switchNode("second").getOk(), "Live main switch succeeds");
        equal(starts, f.backend.startCount);
        equal(stops, f.backend.stopCount);
        equal(1, f.backend.runtimeChanges.size());
        String[] change = f.backend.runtimeChanges.get(0);
        equal("proxy~1", change[0]);
        equal("proxy", change[1]);
        check(change[3].contains("second.example"), "Runtime outbound contains the chosen server");
        check(change[4].contains("proxy~1"), "Runtime routing targets the new outbound");
        equal("second", f.controller.selectedNodeKey());
        f.backend.failRuntimeChange = "Runtime change refused";
        check(f.controller.switchNode("third").getOk(), "A refused live change falls back to restart");
        equal(starts + 1, f.backend.startCount);
        check(f.controller.isConnected(), "Restart fallback retains a working connection");
        equal("third", f.controller.selectedNodeKey());
        equal(1, f.backend.proxyApplications);
        check(f.controller.disconnect().getOk(), "Disconnect after live switch and fallback");
    }

    private static void testPlatformMaintenance(Fixture f) throws Exception {
        var installProgress = new ArrayList<String>();
        check(f.controller.installCore(text -> { installProgress.add(text); return kotlin.Unit.INSTANCE; }).getOk(), "Core install delegates to the backend");
        check(f.controller.installPerApp(text -> { installProgress.add(text); return kotlin.Unit.INSTANCE; }).getOk(), "Per-app install delegates to the backend");
        equal(List.of("core installed", "per-app installed"), installProgress);
        f.backend.failInstall = true;
        check(!f.controller.installCore(text -> kotlin.Unit.INSTANCE).getOk(), "Installer failure must reach the UI result");
        f.backend.failInstall = false;

        f.backend.update = new app.perroute.backend.api.UpdateDescriptor("9.0.0", java.net.URI.create("https://updates.example/app.exe"), "a".repeat(64), 4, true);
        f.controller.setUpdateFeedUrl("https://updates.example/feed.json");
        check(f.controller.checkForUpdate().getOk(), "Update lookup delegates to the backend");
        equal("https://updates.example/feed.json", f.backend.checkedFeed);
        equal("9.0.0", f.controller.availableUpdateVersion());
        check(!f.controller.launchDownloadedUpdate().getOk(), "An update cannot launch before a download");
        f.backend.download = f.directory.resolve("verified-update.exe");
        Files.writeString(f.backend.download, "fake");
        var progress = new ArrayList<Integer>();
        check(f.controller.downloadAvailableUpdate(value -> { progress.add(value); return kotlin.Unit.INSTANCE; }).getOk(), "Download delegates to the backend");
        equal(List.of(100), progress);
        equal(f.backend.update, f.backend.downloadedDescriptor);
        check(f.controller.launchDownloadedUpdate().getOk(), "Launch delegates to the backend without executing a host process");
        equal(f.backend.download, f.backend.launchedInstaller);
    }

    static void runAll() throws Exception {
        run("Controller: Advanced routing is optional", ControllerTests::testAdvancedIsOptional);
        run("Controller: per-app servers reach the core", ControllerTests::testConnectAppliesAssignments);
        run("Controller: mutation locks", ControllerTests::testMutationLocks);
        run("Controller: missing custom server", ControllerTests::testMissingCustomServer);
        run("Controller: main switch keeps assignments", ControllerTests::testSwitchKeepsAssignments);
        run("Controller: switch rollback", ControllerTests::testSwitchRollback);
        run("Controller: profiles store routing", ControllerTests::testProfilesStoreRouting);
        run("Controller: subscription replacement", ControllerTests::testSubscriptionReplacement);
        run("Controller: rule editing and order", ControllerTests::testRuleEditing);
        run("Controller: import preview", ControllerTests::testImportPreview);
        run("Controller: route-specific checks", ControllerTests::testRouteSpecificChecks);
        run("Controller: reset to initial state", ControllerTests::testReset);
    }

    private static void testReset(Fixture f) throws Exception {
        f.assign(Map.of(f.chrome, "second"), true);
        f.controller.addRule("Mine", "", RouteRule.MatchType.DOMAIN, "example.com", RouteRule.Action.DIRECT);
        f.controller.saveProfile("Saved");
        f.controller.toggleFavorite("second");
        f.controller.setNodeTag("third", "gaming");
        f.controller.setDarkTheme(false);
        f.controller.setKillSwitch(true);
        equal(app.perroute.service.SecureUpdateService.OFFICIAL_FEED, f.controller.effectiveUpdateFeed());
        check(f.controller.autoUpdate(), "Updates are automatic by default");
        check(f.controller.setAutoUpdate(false).getOk() && !f.controller.autoUpdate(), "Automatic updates can be turned off");
        equal(null, f.controller.updateReadyVersion());
        f.controller.setUpdateFeedUrl("https://updates.example/update.json");
        equal("https://updates.example/update.json", f.controller.effectiveUpdateFeed());

        check(f.controller.connect().getOk(), "Connect");
        check(!f.controller.resetAll().getOk(), "A reset must be refused while connected");
        equal(3, f.controller.nodes().size());
        check(f.controller.advancedRouting(), "A refused reset must not change anything");
        f.controller.disconnect();
        Files.createDirectories(f.directory.resolve("data"));
        Files.writeString(f.directory.resolve("data").resolve("current-config.json"), "{\"outbounds\":[]}");

        check(f.controller.resetAll().getOk(), "A reset must work while disconnected");
        equal(0, f.controller.nodes().size());
        equal(0, f.controller.applications().size());
        equal(0, f.controller.subscriptions().size());
        equal(0, f.controller.rules().size());
        equal(0, f.controller.profiles().size());
        equal(0, f.controller.appRoutes().size());
        equal("", f.controller.selectedNodeKey());
        equal("", f.controller.activeProfileId());
        check(!f.controller.advancedRouting(), "Advanced routing must be off after a reset");
        equal(ConnectionMode.SYSTEM_PROXY, f.controller.connectionMode());
        check(f.controller.isDarkTheme() && !f.controller.killSwitch() && f.controller.autoReconnect() && f.controller.autoFailover()
                && !f.controller.fastestOnConnect() && f.controller.autoSpeedTest(), "Every preference must return to its default");
        equal("", f.controller.updateFeedUrl());
        check(f.controller.autoUpdate(), "A reset turns automatic updates back on");
        equal(app.perroute.service.SecureUpdateService.OFFICIAL_FEED, f.controller.effectiveUpdateFeed());
        equal(0L, f.controller.trafficReport("").monthDownloadBytes());
        check(!f.controller.connect().getOk(), "Nothing is left to connect to");

        AppState restored = f.store.load();
        equal(0, restored.nodes().size());
        equal(0, restored.favoriteNodeKeys().size());
        equal(0, restored.nodeTags().size());
        equal(0, restored.appRoutes().size());
        check(!restored.advancedRouting(), "The reset must be saved");
        check(!Files.exists(f.directory.resolve("data").resolve("current-config.json")), "The last config, which holds credentials, must be removed");
    }

    private static void testRouteSpecificChecks(Fixture f) {
        f.assign(Map.of(f.chrome, "second", f.telegram, "third"), true);
        check(!f.controller.checkRoute("second").getOk(), "A server cannot be checked while disconnected");
        check(f.controller.checkRoutes().getOk(), "The direct route can always be checked");
        equal(List.of(-1), f.backend.lookups);
        equal(RouteCheckState.PASSED, f.controller.routeChecks().get(PerRouteController.DIRECT_ROUTE_KEY).getState());
        check(f.controller.routeChecks().get("main") == null, "A server must not get a result without a connection");

        check(f.controller.connect().getOk(), "Connect");
        int secondPort = XrayConfigGenerator.routeCheckPort(0), thirdPort = XrayConfigGenerator.routeCheckPort(1);
        f.backend.failingPorts.add(thirdPort);
        f.backend.lookups.clear();
        ActionResult result = f.controller.checkRoutes();
        check(!result.getOk() && result.getMessage().contains("1 of 3"), "One failed route must be reported: " + result.getMessage());
        check(f.backend.lookups.containsAll(List.of(-1, app.perroute.service.AppProxyLauncher.SOCKS_PORT, secondPort, thirdPort)),
                "Every route must be observed through its own port: " + f.backend.lookups);
        var checks = f.controller.routeChecks();
        equal(RouteCheckState.PASSED, checks.get("main").getState());
        equal(RouteCheckState.PASSED, checks.get("second").getState());
        equal(RouteCheckState.FAILED, checks.get("third").getState());
        equal("203.0.113." + (app.perroute.service.AppProxyLauncher.SOCKS_PORT % 250), checks.get("main").getPublicIp());
        equal("203.0.113." + (secondPort % 250), checks.get("second").getPublicIp());
        equal(null, checks.get("third").getPublicIp());
        check(checks.get("third").getDetail().contains("could not reach"), "A failed route must keep its own error");
        equal("198.51.100.24", checks.get(PerRouteController.DIRECT_ROUTE_KEY).getPublicIp());
        equal(List.of("192.0.2.53"), f.controller.dnsObservation().getSystemServers());
        check(!checks.get("main").stale(System.currentTimeMillis()), "A fresh result is not stale");
        check(checks.get("main").stale(System.currentTimeMillis() + PerRouteController.ROUTE_CHECK_STALE_MILLIS + 1_000),
                "A result older than the limit must be reported as stale");

        f.backend.failingPorts.clear();
        check(f.controller.checkRoute("third").getOk(), "Check again must re-test only that route");
        equal(RouteCheckState.PASSED, f.controller.routeChecks().get("third").getState());
        f.controller.disconnect();
        check(f.controller.routeChecks().get("main") == null && f.controller.routeChecks().get("third") == null,
                "Server results must be cleared when the connection ends");
        check(f.controller.routeChecks().get(PerRouteController.DIRECT_ROUTE_KEY) != null, "The direct result does not depend on the connection");
    }

    @FunctionalInterface
    private interface CheckedTest { void run(Fixture fixture) throws Exception; }

    private static void run(String name, CheckedTest test) throws Exception {
        System.out.println("[test] " + name);
        Fixture fixture = new Fixture();
        try { test.run(fixture); }
        finally { fixture.close(); }
    }

    private static final class Fixture {
        final Path directory;
        final FakeBackend backend = new FakeBackend();
        final Preferences preferences = new MemoryPreferences();
        final AppState state = new AppState();
        final AppStateStore store;
        final ProxyNode main = server("main", "main.example");
        final ProxyNode second = server("second", "second.example");
        final ProxyNode third = server("third", "third.example");
        final String chrome, telegram, discord;
        final PerRouteController controller;

        Fixture() throws IOException {
            directory = Files.createTempDirectory("perroute-controller-");
            System.setProperty("perroute.data", directory.resolve("data").toString());
            chrome = executable("Chrome.exe");
            telegram = executable("Telegram.exe");
            discord = executable("Discord.exe");
            state.nodes().addAll(List.of(main, second, third));
            state.setSelectedNodeKey(main.key());
            state.setConnectionMode(ConnectionMode.NATIVE_PER_APP);
            state.appPaths().addAll(List.of(chrome, telegram, discord));
            preferences.putBoolean("autoFailover", false);
            preferences.putBoolean("autoReconnect", false);
            store = new AppStateStore(directory.resolve("data").resolve("state.bin"));
            controller = new PerRouteController(state, store, backend, preferences);
        }

        private String executable(String name) throws IOException {
            Path file = directory.resolve(name);
            Files.writeString(file, "test");
            return file.toAbsolutePath().normalize().toString();
        }

        ActionResult assign(Map<String, String> routes, boolean advanced) {
            return controller.saveApplicationSetup(List.of(chrome, telegram, discord), routes, advanced);
        }

        void close() throws IOException {
            backend.release();
            controller.close();
            System.clearProperty("perroute.data");
            for (int attempt = 1; ; attempt++) {
                try (var files = Files.walk(directory)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
                    return;
                } catch (IOException busy) {
                    if (attempt >= 5) throw busy;
                    try { Thread.sleep(100L * attempt); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw busy; }
                }
            }
        }
    }

    private static ProxyNode server(String key, String host) {
        return new ProxyNode(key, host, Protocol.VLESS, "127.0.0.1", 9, host, Map.of(), "");
    }

    private static void testAdvancedIsOptional(Fixture f) {
        check(!f.controller.advancedRouting(), "Advanced routing must be off by default");
        check(f.assign(Map.of(f.chrome, "second"), false).getOk(), "Assignments can be saved while Advanced is off");
        RoutePlanner.Plan standard = f.controller.routePlan(false);
        check(!standard.advancedEffective(), "A saved assignment must not turn Advanced on");
        equal(1, standard.routes().size());
        equal(f.main, standard.nodeFor(f.chrome));
        equal(Map.of(AppRoutes.normalize(f.chrome), "second"), f.controller.appRoutes());

        check(f.controller.setAdvancedRouting(true).getOk(), "Advanced can be enabled while disconnected");
        RoutePlanner.Plan advanced = f.controller.routePlan(false);
        equal(2, advanced.routes().size());
        equal(f.second, advanced.nodeFor(f.chrome));
        equal(f.main, advanced.nodeFor(f.telegram));

        check(f.controller.setAdvancedRouting(false).getOk(), "Advanced can be disabled while disconnected");
        equal(Map.of(AppRoutes.normalize(f.chrome), "second"), f.controller.appRoutes());
        equal(f.main, f.controller.routePlan(false).nodeFor(f.chrome));
        check(f.controller.setAdvancedRouting(true).getOk(), "Advanced can be enabled again");
        equal(f.second, f.controller.routePlan(false).nodeFor(f.chrome));

        f.controller.setMode(ConnectionMode.SYSTEM_PROXY);
        check(!f.controller.routePlan(false).advancedEffective(), "Other modes must ignore assignments");
        equal(Map.of(AppRoutes.normalize(f.chrome), "second"), f.controller.appRoutes());
    }

    private static void testConnectAppliesAssignments(Fixture f) throws Exception {
        f.assign(Map.of(f.chrome, "second"), false);
        check(f.controller.connect().getOk(), "Standard connection must succeed");
        check(!f.backend.lastConfig.contains("perroute-route-0"), "Advanced off must route every app through Main");
        equal(1, f.controller.activeRoutes().size());
        equal(List.of(f.chrome, f.telegram, f.discord), f.backend.perAppApplications);
        f.controller.disconnect();

        f.controller.setAdvancedRouting(true);
        check(f.controller.connect().getOk(), "Advanced connection must succeed");
        equal(f.backend.lastValidated, f.backend.lastConfig);
        Map<String, Object> root = Json.object(f.backend.lastConfig);
        check(f.backend.lastConfig.contains("perroute-route-0"), "The custom server must be in the running config");
        equal(2, f.controller.activeRoutes().size());
        var custom = f.controller.activeRoutes().get(1);
        check(!custom.getMain(), "The second active route is the custom server");
        equal("second", custom.getNode().key());
        equal(List.of(f.chrome), custom.getApplications());
        equal(XrayConfigGenerator.routeCheckPort(0), custom.getCheckPort());
        check(root.containsKey("stats") && root.containsKey("api"), "Statistics must stay enabled");
        equal(List.of(f.chrome, f.telegram, f.discord), f.backend.perAppApplications);

        waitFor(() -> f.controller.routeChecks().values().stream().filter(c -> c.getState() == RouteCheckState.PASSED).count() == 2);
        f.backend.failingPorts.add(XrayConfigGenerator.routeCheckPort(0));
        f.controller.disconnect();
        check(f.controller.routeChecks().get("second") == null, "Route observations must not outlive the connection");
        check(f.controller.connect().getOk(), "Reconnect must succeed");
        waitFor(() -> f.controller.routeChecks().getOrDefault("second", new app.perroute.compose.controller.RouteCheck()).getState() == RouteCheckState.FAILED);
        waitFor(() -> f.controller.routeChecks().getOrDefault("main", new app.perroute.compose.controller.RouteCheck()).getState() == RouteCheckState.PASSED);
        equal(RouteCheckState.PASSED, f.controller.routeChecks().get("main").getState());
        equal(RouteCheckState.FAILED, f.controller.routeChecks().get("second").getState());

        f.controller.disconnect();
        f.controller.setMode(ConnectionMode.BROWSER_PROXY);
        check(f.controller.connect().getOk(), "Local proxy must still connect");
        check(!f.backend.lastConfig.contains("perroute-route-0") && !f.backend.lastConfig.contains("perroute-app-0"),
                "Local proxy must ignore app assignments");
    }

    private static void testMutationLocks(Fixture f) throws Exception {
        f.assign(Map.of(f.chrome, "second"), true);
        f.controller.addRule("Mine", "", RouteRule.MatchType.DOMAIN, "example.com", RouteRule.Action.DIRECT);
        f.controller.saveProfile("Saved");
        String profileId = f.controller.profiles().get(0).id();
        String ruleId = f.controller.rules().get(0).id();

        f.backend.blockStart = new CountDownLatch(1);
        f.backend.startEntered = new CountDownLatch(1);
        Thread connecting = Thread.ofPlatform().start(f.controller::connect);
        check(f.backend.startEntered.await(5, TimeUnit.SECONDS), "The fake core must have been asked to start");
        equal(ConnectionPhase.CONNECTING, f.controller.connectionPhase());
        assertLocked(f, profileId, ruleId, "while connecting");
        f.backend.release();
        connecting.join(5_000);
        check(f.controller.isConnected(), "The connection must complete");
        assertLocked(f, profileId, ruleId, "while connected");

        f.controller.disconnect();
        check(f.controller.setAdvancedRouting(false).getOk(), "Edits must be possible again after disconnecting");
        check(f.assign(Map.of(), true).getOk(), "Assignments must be editable after disconnecting");
    }

    private static void assertLocked(Fixture f, String profileId, String ruleId, String when) {
        check(!f.controller.setAdvancedRouting(false).getOk(), "Advanced must be locked " + when);
        check(!f.assign(Map.of(f.telegram, "third"), true).getOk(), "Assignments must be locked " + when);
        check(!f.controller.replaceApplications(List.of(f.chrome)).getOk(), "The app selection must be locked " + when);
        check(!f.controller.removeNode("second").getOk(), "Servers must not be removable " + when);
        check(!f.controller.applyProfile(profileId).getOk(), "Profiles must be locked " + when);
        check(!f.controller.addRule("x", "", RouteRule.MatchType.PORT, "80", RouteRule.Action.BLOCK).getOk(), "Rules must be locked " + when);
        check(!f.controller.moveRule(ruleId, 1).getOk() && !f.controller.removeRule(ruleId).getOk(), "Rule edits must be locked " + when);
        check(!f.controller.applyScenario("work").getOk(), "Presets must be locked " + when);
        check(f.controller.advancedRouting(), "A rejected edit must not change the Advanced flag");
        equal(Map.of(AppRoutes.normalize(f.chrome), "second"), f.controller.appRoutes());
        equal(3, f.controller.applications().size());
        equal(3, f.controller.nodes().size());
        equal(1, f.controller.rules().size());
    }

    private static void testMissingCustomServer(Fixture f) {
        f.assign(Map.of(f.chrome, "second"), true);
        ActionResult removed = f.controller.removeNode("second");
        check(removed.getOk() && removed.getMessage().contains("replacement"), "Removing an assigned server must be reported");
        equal(Map.of(AppRoutes.normalize(f.chrome), "second"), f.controller.appRoutes());
        equal("second.example", f.controller.appRouteLabel("second"));

        ActionResult blocked = f.controller.connect();
        check(!blocked.getOk(), "An effective missing custom server must block connecting");
        check(blocked.getMessage().contains("Chrome") && blocked.getMessage().contains("second.example")
                && blocked.getMessage().contains("replacement"), "The message must name the app, the server and the fix: " + blocked.getMessage());
        equal(0, f.backend.startCount);
        equal(0, f.backend.validateCount);
        equal(ConnectionPhase.DISCONNECTED, f.controller.connectionPhase());

        f.controller.setAdvancedRouting(false);
        check(f.controller.connect().getOk(), "An inactive missing assignment must not block standard routing");
        check(!f.backend.lastConfig.contains("perroute-route-0"), "Standard routing must use Main only");
        f.controller.disconnect();

        f.controller.setAdvancedRouting(true);
        check(f.assign(Map.of(f.chrome, "third"), true).getOk(), "A replacement can be saved");
        f.backend.rejectValidation = "third.example";
        int startsBefore = f.backend.startCount;
        ActionResult rejected = f.controller.connect();
        check(!rejected.getOk() && rejected.getMessage().contains("third.example") && rejected.getMessage().contains("Chrome"),
                "A rejected custom server must be named with its app: " + rejected.getMessage());
        equal(startsBefore, f.backend.startCount);
        equal(ConnectionPhase.DISCONNECTED, f.controller.connectionPhase());
        f.backend.rejectValidation = null;
        check(f.controller.connect().getOk(), "Connecting must work after the replacement");
        equal("third", f.controller.activeRoutes().get(1).getNode().key());
    }

    private static void testSwitchKeepsAssignments(Fixture f) {
        f.assign(Map.of(f.chrome, "second"), true);
        check(f.controller.connect().getOk(), "Connect");
        int startsBefore = f.backend.startCount;
        ActionResult switched = f.controller.switchNode("third");
        check(switched.getOk(), "Switching the main server must succeed: " + switched.getMessage());
        equal(startsBefore + 1, f.backend.startCount);
        equal("third", f.controller.selectedNodeKey());
        check(f.backend.lastConfig.contains("perroute-route-0") && f.backend.lastConfig.contains("second.example")
                && f.backend.lastConfig.contains("third.example"), "The rebuilt config must keep the custom server");
        check(!f.backend.lastConfig.contains("main.example"), "The old main server must be gone");
        equal(2, f.controller.activeRoutes().size());
        equal(Map.of(AppRoutes.normalize(f.chrome), "second"), f.controller.appRoutes());

        check(f.controller.switchNode("second").getOk(), "Switching Main to an assigned server must succeed");
        equal(1, f.controller.activeRoutes().size());
        check(!f.backend.lastConfig.contains("perroute-route-0"), "An app assigned to the main server shares its outbound");
        equal(Map.of(AppRoutes.normalize(f.chrome), "second"), f.controller.appRoutes());
    }

    private static void testSwitchRollback(Fixture f) {
        f.assign(Map.of(f.chrome, "second"), true);
        check(f.controller.connect().getOk(), "Connect");
        String running = f.backend.lastConfig;

        f.backend.rejectValidation = "third.example";
        int starts = f.backend.startCount, stops = f.backend.stopCount;
        ActionResult invalid = f.controller.switchNode("third");
        check(!invalid.getOk() && invalid.getMessage().contains("left untouched"), "A rejected config must not be applied");
        equal(starts, f.backend.startCount);
        equal(stops, f.backend.stopCount);
        equal("main", f.controller.selectedNodeKey());
        check(f.controller.isConnected(), "The connection must survive a rejected config");
        equal(ConnectionPhase.CONNECTED, f.controller.connectionPhase());

        f.backend.rejectValidation = null;
        f.backend.failStart = "third.example";
        ActionResult failed = f.controller.switchNode("third");
        check(!failed.getOk() && failed.getMessage().contains("previous connection was restored"),
                "A failed switch must roll back: " + failed.getMessage());
        equal("main", f.controller.selectedNodeKey());
        check(f.controller.isConnected(), "The previous connection must be running again");
        equal(running, f.backend.lastConfig);
        check(f.backend.lastConfig.contains("perroute-route-0"), "The rollback must keep the custom server");
        equal(2, f.controller.activeRoutes().size());
        equal(Map.of(AppRoutes.normalize(f.chrome), "second"), f.controller.appRoutes());
    }

    private static void testProfilesStoreRouting(Fixture f) throws Exception {
        f.assign(Map.of(f.chrome, "second"), true);
        check(f.controller.saveProfile("Office").getOk(), "Save the advanced profile");
        f.assign(Map.of(f.telegram, "third"), false);
        check(f.controller.saveProfile("Simple").getOk(), "Save the standard profile");
        var office = f.controller.profiles().stream().filter(p -> p.name().equals("Office")).findFirst().orElseThrow();
        var simple = f.controller.profiles().stream().filter(p -> p.name().equals("Simple")).findFirst().orElseThrow();
        check(office.advancedRouting() && !simple.advancedRouting(), "Profiles must store the Advanced flag");
        equal(Map.of(AppRoutes.normalize(f.chrome), "second"), office.appRoutes());

        check(f.controller.applyProfile(office.id()).getOk(), "Apply Office");
        check(f.controller.advancedRouting(), "Applying a profile must restore its Advanced flag");
        equal(Map.of(AppRoutes.normalize(f.chrome), "second"), f.controller.appRoutes());
        check(f.controller.applyProfile(simple.id()).getOk(), "Apply Simple");
        check(!f.controller.advancedRouting(), "A standard profile must turn Advanced off");
        equal(Map.of(AppRoutes.normalize(f.telegram), "third"), f.controller.appRoutes());

        f.state.profiles().add(new app.perroute.model.ConnectionProfile("legacy", "Legacy",
                List.of(f.chrome), "main", ConnectionMode.NATIVE_PER_APP, List.of()));
        f.assign(Map.of(f.chrome, "second"), true);
        check(f.controller.applyProfile("legacy").getOk(), "Apply the legacy profile");
        check(!f.controller.advancedRouting(), "A legacy profile must leave Advanced off");
        equal(Map.of(AppRoutes.normalize(f.chrome), "second"), f.controller.appRoutes());

        f.controller.close();
        AppState restored = f.store.load();
        equal(3, restored.profiles().size());
        check(restored.profiles().stream().anyMatch(p -> p.name().equals("Office") && p.advancedRouting()
                && p.appRoutes().equals(Map.of(AppRoutes.normalize(f.chrome), "second"))), "Profiles must persist their routing");
    }

    private static void testSubscriptionReplacement(Fixture f) {
        String url = "https://sub.example/feed";
        ProxyNode one = new ProxyNode("sub-1", "Sub One", Protocol.VLESS, "127.0.0.1", 9, "sub-id-1", Map.of(), "");
        ProxyNode two = new ProxyNode("sub-2", "Sub Two", Protocol.VLESS, "127.0.0.1", 9, "sub-id-2", Map.of(), "");
        check(f.controller.commitImport(new ImportPreview(url, true, new ParseResult(List.of(one, two), List.of()), List.of(), 0)).getOk(),
                "First subscription import");
        f.assign(Map.of(f.chrome, "sub-2"), true);
        f.controller.toggleFavorite("sub-2");

        ProxyNode oneAgain = new ProxyNode("fresh-1", "Sub One (renamed)", Protocol.VLESS, "127.0.0.1", 9, "sub-id-1", Map.of(), "");
        ProxyNode twoAgain = new ProxyNode("fresh-2", "Sub Two (renamed)", Protocol.VLESS, "127.0.0.1", 9, "sub-id-2", Map.of(), "");
        check(f.controller.commitImport(new ImportPreview(url, true, new ParseResult(List.of(oneAgain, twoAgain), List.of()), List.of(), 0)).getOk(),
                "Subscription refresh");
        check(f.controller.nodes().stream().anyMatch(node -> node.key().equals("sub-2") && node.name().equals("Sub Two (renamed)")),
                "A refreshed server must keep its key");
        check(f.controller.routePlan(false).ready(), "The assignment must still resolve after a refresh");
        equal("sub-2", f.controller.routePlan(false).nodeFor(f.chrome).key());
        check(f.controller.isFavorite("sub-2"), "Favorites must follow the same mapping");

        check(f.controller.commitImport(new ImportPreview(url, true, new ParseResult(List.of(oneAgain), List.of()), List.of(), 0)).getOk(),
                "Subscription without the assigned server");
        equal(Map.of(AppRoutes.normalize(f.chrome), "sub-2"), f.controller.appRoutes());
        RoutePlanner.Plan plan = f.controller.routePlan(false);
        check(!plan.ready(), "The dropped server must be detected as unresolved");
        equal("Sub Two", plan.missing().get(0).label());
        check(!f.controller.connect().getOk(), "Connecting must be blocked while the assignment is effective");
        equal(0, f.backend.startCount);
        f.controller.setAdvancedRouting(false);
        equal(1, f.controller.routePlan(true).missing().size());
        check(f.controller.connect().getOk(), "Standard routing must still connect");
    }

    private static void testRuleEditing(Fixture f) {
        f.controller.addRule("First", "", RouteRule.MatchType.DOMAIN, "one.example", RouteRule.Action.DIRECT);
        f.controller.addRule("Second", "", RouteRule.MatchType.DOMAIN, "two.example", RouteRule.Action.PROXY);
        f.controller.addRule("Third", f.chrome, RouteRule.MatchType.PORT, "25", RouteRule.Action.BLOCK);
        List<String> ids = f.controller.rules().stream().map(RouteRule::id).toList();

        check(f.controller.moveRule(ids.get(2), -1).getOk(), "Move up");
        equal(List.of("First", "Third", "Second"), f.controller.rules().stream().map(RouteRule::name).toList());
        check(!f.controller.moveRule(ids.get(0), -1).getOk(), "The first rule cannot move further up");
        check(f.controller.updateRule(ids.get(1), "Second edited", f.telegram, RouteRule.MatchType.IP, "203.0.113.0/24", RouteRule.Action.DIRECT).getOk(), "Edit");
        RouteRule edited = f.controller.rules().get(2);
        equal(ids.get(1), edited.id());
        equal("Second edited", edited.name());
        equal(RouteRule.MatchType.IP, edited.matchType());
        check(edited.enabled(), "Editing must keep the enabled state");
        check(!f.controller.updateRule(ids.get(1), "x", "", RouteRule.MatchType.IP, " ", RouteRule.Action.DIRECT).getOk(), "Empty values are rejected");

        var preview = f.controller.previewScenario("work");
        equal(2, preview.getAddedRules().size());
        equal(3, f.controller.rules().size());
        check(preview.getSettings().stream().anyMatch(change -> change.getLabel().contains("kill switch") && !change.getFrom() && change.getTo()),
                "The preview must list automation changes without applying them");
        check(!f.preferences.getBoolean("perAppKillSwitch", false), "A preview must not change settings");

        check(f.controller.applyScenario("work").getOk(), "Apply preset");
        List<RouteRule> rules = f.controller.rules();
        equal(5, rules.size());
        check(PerRouteController.Companion.isScenarioRule(rules.get(0)) && PerRouteController.Companion.isScenarioRule(rules.get(1)),
                "Preset rules must sit above the user's rules");
        equal("First", rules.get(2).name());
        check(!f.controller.moveRule(ids.get(0), -1).getOk(), "A user rule cannot move above preset rules");
        check(!f.controller.moveRule(rules.get(0).id(), 1).getOk(), "Preset rules cannot be reordered");
        check(!f.controller.updateRule(rules.get(0).id(), "x", "", RouteRule.MatchType.PORT, "1", RouteRule.Action.BLOCK).getOk(),
                "Preset rules cannot be edited");
        equal("work", f.controller.activeScenarioId());
        check(f.controller.previewScenario("gaming").getRemovedRules().size() == 2, "Switching presets replaces preset rules only");
        check(f.controller.clearScenario().getOk(), "Remove preset");
        equal(List.of("First", "Third", "Second edited"), f.controller.rules().stream().map(RouteRule::name).toList());
    }

    private static void testImportPreview(Fixture f) {
        int before = f.controller.nodes().size();
        var outcome = f.controller.previewPasted(
                "vless://11111111-1111-1111-1111-111111111111@new.example:443?security=tls#New\nwireguard://unsupported\n");
        check(outcome.getPreview() != null, "Valid links must produce a preview: " + outcome.getError());
        equal(1, outcome.getPreview().getCandidates().size());
        equal(1, outcome.getPreview().getNewCount());
        check(!outcome.getPreview().getWarnings().isEmpty(), "Parser problems must be shown before saving");
        equal(before, f.controller.nodes().size());
        check(f.controller.commitImport(outcome.getPreview()).getOk(), "Commit");
        equal(before + 1, f.controller.nodes().size());
        var again = f.controller.previewPasted("vless://11111111-1111-1111-1111-111111111111@new.example:443?security=tls#Renamed");
        equal(0, again.getPreview().getNewCount());
        check(f.controller.previewPasted("garbage").getPreview() == null, "Nothing valid means no preview");
        check(f.controller.previewPasted(" ").getError() != null, "Empty input is an error");
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        check(condition.getAsBoolean(), "Timed out waiting for a background check");
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

    static final class FakeBackend implements ConnectionBackend {
        @Override public boolean supportsRecipeImport() { return true; }
        @Override public boolean supportsWorkspaces() { return true; }
        @Override public boolean supportsLanBridge() { return true; }
        @Override public boolean supportsExplorerMenu() { return true; }
        @Override public app.perroute.backend.api.BackendCapabilities capabilities() { return app.perroute.backend.api.BackendCapabilities.windows(); }
        private final List<Consumer<Boolean>> runningListeners = new ArrayList<>();
        final List<Integer> failingPorts = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile boolean running;
        volatile String lastConfig = "";
        volatile String lastValidated = "";
        volatile String rejectValidation;
        volatile String failStart;
        volatile String failIsolatedStart;
        volatile int startCount, stopCount, validateCount, proxyApplications, proxyRestorations;
        volatile List<String> perAppApplications = List.of();
        volatile CountDownLatch blockStart;
        volatile CountDownLatch startEntered;

        void release() { CountDownLatch latch = blockStart; if (latch != null) latch.countDown(); }

        boolean failInstall;
        app.perroute.backend.api.UpdateDescriptor update, downloadedDescriptor;
        String checkedFeed;
        Path download, launchedInstaller;
        @Override public void installCore(Consumer<String> progress) {
            if (failInstall) throw new IllegalStateException("Installation failed");
            progress.accept("core installed");
        }
        @Override public void installPerApp(Consumer<String> progress) { progress.accept("per-app installed"); }
        @Override public app.perroute.backend.api.UpdateDescriptor checkForUpdate(String feed) {
            checkedFeed = feed;
            if (update == null) throw new UnsupportedOperationException("No update configured in this fake");
            return update;
        }
        @Override public Path downloadUpdate(app.perroute.backend.api.UpdateDescriptor update, Consumer<Integer> progress) {
            downloadedDescriptor = update;
            if (download == null) throw new UnsupportedOperationException("No download configured in this fake");
            progress.accept(100);
            return download;
        }
        @Override public void launchUpdate(Path installer) { launchedInstaller = installer; }
        @Override public boolean coreReady() { return true; }
        @Override public boolean perAppReady() { return true; }
        @Override public boolean elevated() { return elevatedNow; }

        @Override public void validate(String config, boolean tun) {
            validateCount++;
            if (rejectValidation != null && config.contains(rejectValidation)) throw new IllegalStateException("Xray rejected the generated config");
            lastValidated = config;
        }

        @Override public void start(String config, boolean tun) {
            startCount++;
            CountDownLatch entered = startEntered;
            if (entered != null) entered.countDown();
            CountDownLatch latch = blockStart;
            if (latch != null) {
                try { latch.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (failStart != null && config.contains(failStart)) throw new IllegalStateException("Xray could not start");
            lastConfig = config;
            running = true;
        }

        @Override public void stop() { stopCount++; running = false; }
        @Override public boolean isRunning() { return running; }
        @Override public void stopOrphan() { machineCalls.add("stopOrphan"); }
        @Override public void addLogListener(Consumer<String> listener) { logListeners.add(listener); }
        @Override public void addRunningListener(Consumer<Boolean> listener) { runningListeners.add(listener); }
        @Override public void applyPerApp(List<String> applications) { perAppApplications = List.copyOf(applications); applyPerAppCount++; }
        @Override public void restorePerApp() { restorePerAppCount++; machineCalls.add("restorePerApp"); }

        final List<Consumer<String>> logListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile int applyPerAppCount, restorePerAppCount;

        void emit(String line) { logListeners.forEach(listener -> listener.accept(line)); }

        void crash() { running = false; runningListeners.forEach(listener -> listener.accept(false)); }

        volatile boolean supportsRuntimeChange;
        volatile String failRuntimeChange;
        final List<String[]> runtimeChanges = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override public void applyRuntimeChange(XrayConfigGenerator.RuntimeChange change, String newTag, String oldTag, String config) {
            if (!supportsRuntimeChange) throw new UnsupportedOperationException("This backend cannot change a running core");
            if (failRuntimeChange != null) throw new IllegalStateException(failRuntimeChange);
            runtimeChanges.add(new String[]{newTag, oldTag, config, change.outbounds(), change.routing()});
            lastConfig = config;
        }

        volatile List<app.perroute.gateway.AppGateway.Route> gatewayRoutes = List.of();
        volatile boolean gatewayRunning;
        volatile int gatewayStarts, gatewayStops;
        volatile String failGateway;
        final Map<String, long[]> gatewayLimits = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, app.perroute.gateway.AppGateway.Counters> gatewayCounters = new java.util.concurrent.ConcurrentHashMap<>();

        @Override public void startGateway(List<app.perroute.gateway.AppGateway.Route> routes) {
            if (failGateway != null) throw new IllegalStateException(failGateway);
            gatewayRoutes = List.copyOf(routes);
            gatewayStarts++;
            gatewayRunning = true;
        }
        @Override public void stopGateway() { gatewayStops++; gatewayRunning = false; }
        @Override public void setGatewayLimits(String path, long down, long up) { gatewayLimits.put(path.toLowerCase(), new long[]{down, up}); }
        @Override public Map<String, app.perroute.gateway.AppGateway.Counters> gatewayCounters() { return Map.copyOf(gatewayCounters); }
        @Override public boolean gatewayRunning() { return gatewayRunning; }
        @Override public void addGatewayListener(java.util.function.Consumer<app.perroute.gateway.GatewayConnection> listener) {}
        @Override public void shellOpen(Path file, List<String> arguments, boolean elevated) { launchedInstaller = file; }
        @Override public void stopPerAppSidecar() { machineCalls.add("stopPerAppSidecar"); }

        volatile app.perroute.service.ResourceConditions.Snapshot resources = app.perroute.service.ResourceConditions.Snapshot.NORMAL;
        final Map<String, String> shortcuts = new java.util.concurrent.ConcurrentHashMap<>();
        volatile String category = "Private";
        volatile boolean firewallReady;
        volatile int firewallAllowed;
        volatile String isolatedConfig = "";
        final List<FakeTunnel> isolated = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override public app.perroute.service.ResourceConditions.Snapshot resourceConditions() { return resources; }
        @Override public java.nio.file.Path resolveShortcut(java.nio.file.Path shortcut) {
            String target = shortcuts.get(shortcut.toString().toLowerCase());
            return target == null ? null : java.nio.file.Path.of(target);
        }
        @Override public String networkCategory(String address) { return category; }
        @Override public boolean bridgeFirewallReady(java.nio.file.Path core, int firstPort, int lastPort) { return firewallReady; }
        @Override public void allowBridgeFirewall(java.nio.file.Path core, int firstPort, int lastPort) { firewallAllowed++; firewallReady = true; }
        @Override public IsolatedTunnel startIsolatedCore(String config, int socksPort, java.util.function.Consumer<String> onLine) {
            if (failIsolatedStart != null) throw new IllegalStateException(failIsolatedStart);
            isolatedConfig = config;
            FakeTunnel tunnel = new FakeTunnel(socksPort, onLine);
            isolated.add(tunnel);
            return tunnel;
        }
        @Override public void applySystemProxy() { proxyApplications++; machineCalls.add("applySystemProxy"); }
        @Override public void restoreSystemProxy() { proxyRestorations++; machineCalls.add("restoreSystemProxy"); }

        final List<String> machineCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
        final Map<String, String> networkValues = new java.util.concurrent.ConcurrentHashMap<>();
        volatile boolean supportsRuntimePlan;
        final List<XrayConfigGenerator.RuntimePlan> runtimePlans = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile boolean perRouteProxy = true;
        volatile Long serverTime;
        volatile boolean resolves = true;
        volatile boolean elevatedNow;
        volatile List<app.perroute.localnet.LocalNetwork> networks = List.of();

        @Override public void applyPerApp(List<String> applications, boolean bypassLan) {
            machineCalls.add("applyPerApp bypassLan=" + bypassLan);
            applyPerApp(applications);
        }
        @Override public void applyRuntimePlan(XrayConfigGenerator.RuntimePlan plan) {
            if (!supportsRuntimePlan) throw new UnsupportedOperationException("This backend cannot change a running core");
            runtimePlans.add(plan);
            lastConfig = plan.config();
        }
        @Override public Map<String, String> networkState() { return Map.copyOf(networkValues); }
        @Override public void resetSystemProxyLeftover(Map<String, String> baseline) { machineCalls.add("resetSystemProxyLeftover " + (baseline == null ? "none" : "baseline")); }
        @Override public void removeRecoveryEntry() { machineCalls.add("removeRecoveryEntry"); }
        @Override public boolean perRouteProxyActive() { return perRouteProxy; }
        @Override public List<app.perroute.localnet.LocalNetwork> localNetworks(boolean resolveNames) { return networks; }
        @Override public boolean systemResolves(String host) { return resolves; }
        @Override public Long directServerTime() { return serverTime; }
        @Override public app.perroute.backend.api.TunnelStatus checkTunnel(int socksPort) {
            return failingPorts.contains(socksPort) ? new app.perroute.backend.api.TunnelStatus(false, "The proxy could not reach the test endpoint")
                    : new app.perroute.backend.api.TunnelStatus(true, "Tunnel answered from test (HTTP 204)");
        }
        @Override public Map<String, Long> readInboundCounters() { return Map.of(); }
        @Override public app.perroute.backend.api.RouteObservation lookupPublicAddress(Integer socksPort) {
            lookups.add(socksPort == null ? -1 : socksPort);
            if (socksPort != null && failingPorts.contains(socksPort)) {
                return new app.perroute.backend.api.RouteObservation(null, null, "The proxy could not reach www.cloudflare.com");
            }
            return new app.perroute.backend.api.RouteObservation(socksPort == null ? "198.51.100.24" : "203.0.113." + (socksPort % 250), "ZZ", null);
        }
        @Override public List<String> systemDnsServers() { return List.of("192.0.2.53"); }
        @Override public String publicResolver() { return "192.0.2.54"; }
        volatile List<app.perroute.backend.api.ApplicationDescriptor> installed = List.of();
        @Override public List<app.perroute.backend.api.ApplicationDescriptor> installedApplications() { return installed; }
        @Override public List<app.perroute.backend.api.ApplicationDescriptor> runningApplications() { return List.of(); }
        final List<Integer> lookups = new java.util.concurrent.CopyOnWriteArrayList<>();
    }

    static final class FakeTunnel implements IsolatedTunnel {
        private final int port;
        private final java.util.function.Consumer<String> onLine;
        volatile boolean alive = true;

        FakeTunnel(int port, java.util.function.Consumer<String> onLine) { this.port = port; this.onLine = onLine; }

        void line(String text) { onLine.accept(text); }

        @Override public int socksPort() { return port; }
        @Override public boolean alive() { return alive; }
        @Override public List<String> lastLines() { return List.of(); }
        @Override public void close() { alive = false; }
    }

    static final class MemoryPreferences extends AbstractPreferences {
        private final Map<String, String> values = new LinkedHashMap<>();
        MemoryPreferences() { super(null, ""); }
        @Override protected void putSpi(String key, String value) { values.put(key, value); }
        @Override protected String getSpi(String key) { return values.get(key); }
        @Override protected void removeSpi(String key) { values.remove(key); }
        @Override protected void removeNodeSpi() {}
        @Override protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
        @Override protected String[] childrenNamesSpi() { return new String[0]; }
        @Override protected AbstractPreferences childSpi(String name) { return new MemoryPreferences(); }
        @Override protected void syncSpi() {}
        @Override protected void flushSpi() {}
    }
}
