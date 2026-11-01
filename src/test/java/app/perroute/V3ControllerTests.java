package app.perroute;

import app.perroute.backup.BackupService;
import app.perroute.compose.controller.ActionResult;
import app.perroute.compose.controller.ActiveRoute;
import app.perroute.compose.controller.BandwidthState;
import app.perroute.compose.inspector.InspectorRow;
import app.perroute.compose.controller.PerRouteController;
import app.perroute.compose.controller.RestoreRequest;
import app.perroute.compose.controller.RouteCheckState;
import app.perroute.compose.controller.RouteHealthView;
import app.perroute.compose.inspector.RouteKind;
import app.perroute.gateway.AppGateway;
import app.perroute.model.AppRoutes;
import app.perroute.model.AppState;
import app.perroute.model.BandwidthPolicy;
import app.perroute.model.ConnectionMode;
import app.perroute.model.Protocol;
import app.perroute.model.ProxyNode;
import app.perroute.model.RouteRule;
import app.perroute.persistence.AppStateStore;
import app.perroute.quality.QualitySample;
import app.perroute.routing.AppRouteMode;
import app.perroute.routing.AutoServerSelector;
import app.perroute.routing.RouteHealthTracker;
import app.perroute.service.XrayConfigGenerator;
import app.perroute.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.prefs.Preferences;

final class V3ControllerTests {
    private static int assertions;
    private V3ControllerTests() {}

    static int assertions() { return assertions; }

    static final RouteHealthTracker.Policy FAST = new RouteHealthTracker.Policy(60_000L, 100L, 200L, 3, 2, 1_000L);

    static void runAll() throws Exception {
        run("V3: Auto apps get a route of their own", false, V3ControllerTests::testAutoRoute);
        run("V3: Auto without evidence uses the main server", false, V3ControllerTests::testAutoProvisional);
        run("V3: a failing route recovers alone", false, V3ControllerTests::testIndependentRecovery);
        run("V3: Auto fails over in place", false, V3ControllerTests::testAutoFailover);
        run("V3: main switch replaces only the main route", false, V3ControllerTests::testInPlaceMainSwitch);
        run("V3: a refused runtime change keeps the connection", false, V3ControllerTests::testRuntimeChangeRefused);
        run("V3: a failed route changes server while connected", false, V3ControllerTests::testChangeRouteServer);
        run("V3: traffic gateway and live limits", false, V3ControllerTests::testGatewayAndLimits);
        run("V3: gateway start failure rolls back", false, V3ControllerTests::testGatewayFailure);
        run("V3: migrated installations keep version 2 behavior", true, V3ControllerTests::testMigratedDefaults);
        run("V3: kill switch with the gateway", false, V3ControllerTests::testKillSwitch);
        run("V3: inspector separates configured, observed and verified", false, V3ControllerTests::testInspector);
        run("V3: backup restore, rollback and undo", false, V3ControllerTests::testBackupRestore);
        run("V3: rule precedence with Auto routes", false, V3ControllerTests::testRulePrecedence);
        run("V3: removed Auto server is re-decided", false, V3ControllerTests::testRemovedAutoServer);
        run("UI: the interface style is its own preference", false, V3ControllerTests::testUiStylePreference);
        run("V3: an updated Store app keeps its route", false, V3ControllerTests::testUpdatedStoreApp);
    }

    @FunctionalInterface private interface CheckedTest { void run(Fixture fixture) throws Exception; }

    private static void run(String name, boolean migrated, CheckedTest test) throws Exception {
        System.out.println("[test] " + name);
        Fixture fixture = new Fixture(migrated);
        try { test.run(fixture); }
        finally { fixture.close(); }
    }

    private static final class Fixture {
        final Path directory;
        final ControllerTests.FakeBackend backend = new ControllerTests.FakeBackend();
        final Preferences preferences = new ControllerTests.MemoryPreferences();
        final AppState state = new AppState();
        final AppStateStore store;
        final ProxyNode main = server("main", "main.example");
        final ProxyNode second = server("second", "second.example");
        final ProxyNode third = server("third", "third.example");
        final String chrome, telegram, discord;
        final PerRouteController controller;
        final List<String> logs = new CopyOnWriteArrayList<>();

        Fixture(boolean migrated) throws IOException {
            directory = Files.createTempDirectory("perroute-v3-");
            System.setProperty("perroute.data", directory.resolve("data").toString());
            chrome = executable("Chrome.exe");
            telegram = executable("Telegram.exe");
            discord = executable("Discord.exe");
            state.nodes().addAll(List.of(main, second, third));
            state.setSelectedNodeKey(main.key());
            state.setConnectionMode(ConnectionMode.NATIVE_PER_APP);
            state.appPaths().addAll(List.of(chrome, telegram, discord));
            state.setMigratedFromV2(migrated);
            preferences.putBoolean("autoFailover", false);
            preferences.putBoolean("autoReconnect", false);
            store = new AppStateStore(directory.resolve("data").resolve("state.bin"));
            controller = new PerRouteController(state, store, backend, preferences, FAST, 60_000L);
            controller.onLog(line -> { logs.add(line); return kotlin.Unit.INSTANCE; });
        }

        private String executable(String name) throws IOException {
            Path file = directory.resolve(name);
            Files.writeString(file, "test");
            return file.toAbsolutePath().normalize().toString();
        }

        ActionResult setup(Map<String, String> routes, Set<String> auto) {
            return controller.saveApplicationSetup(List.of(chrome, telegram, discord), routes, true, auto, Map.of());
        }

        void good(ProxyNode node, long latency) {
            long now = System.currentTimeMillis();
            for (int i = 6; i >= 1; i--) {
                controller.recordQualitySample(node.key(), QualitySample.reachability(now - i * 30_000L, latency));
                controller.recordQualitySample(node.key(), QualitySample.tunnel(now - i * 30_000L + 500, true, latency * 4, ""));
            }
        }

        ActiveRoute autoRoute(String path) {
            return controller.activeRoutes().stream().filter(r -> r.getAuto() && AppRoutes.normalize(r.getApplications().get(0)).equals(AppRoutes.normalize(path)))
                    .findFirst().orElse(null);
        }

        String savedSelection(String path) {
            try {
                var selection = store.load().autoSelections().get(AppRoutes.normalize(path));
                return selection == null ? null : selection.nodeKey();
            } catch (IOException e) { return null; }
        }

        RouteHealthTracker.Snapshot health(String routeId) {
            return controller.routeHealth().stream().filter(view -> view.getRoute().getId().equals(routeId)).map(RouteHealthView::getHealth)
                    .findFirst().orElse(null);
        }

        void close() throws IOException {
            backend.release();
            controller.close();
            System.clearProperty("perroute.data");
            for (int attempt = 1; ; attempt++) {
                try { V3DomainTests.deleteTree(directory); return; }
                catch (IOException busy) {
                    if (attempt >= 5) throw busy;
                    try { Thread.sleep(100L * attempt); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw busy; }
                }
            }
        }
    }

    private static ProxyNode server(String key, String host) {
        return new ProxyNode(key, host, Protocol.VLESS, "127.0.0.1", 9, host, Map.of(), "");
    }

    private static void testAutoRoute(Fixture f) throws Exception {
        f.good(f.second, 15);
        f.good(f.third, 120);
        check(f.setup(Map.of(), Set.of(f.telegram)).getOk(), "Save Auto mode");
        equal(AppRouteMode.AUTO, f.controller.appRouteMode(f.telegram));
        equal(AppRouteMode.MAIN, f.controller.appRouteMode(f.chrome));
        check(f.controller.connect().getOk(), "Connect with an Auto app");
        ActiveRoute auto = f.autoRoute(f.telegram);
        check(auto != null, "The Auto app must have a route of its own");
        equal("second", auto.getNode().key());
        equal("auto:" + AppRoutes.normalize(f.telegram), auto.getId());
        equal(XrayConfigGenerator.routeCheckPort(0), auto.getCheckPort());
        ActiveRoute mainRoute = f.controller.activeRoutes().get(0);
        check(mainRoute.getMain() && mainRoute.getApplications().equals(List.of(f.chrome, f.discord)), "The others follow Main: " + mainRoute.getApplications());
        check(f.backend.lastConfig.contains("perroute-route-0") && f.backend.lastConfig.contains("second.example"), "The Auto route is in the config");
        var view = f.controller.autoDecision(f.telegram);
        equal(AutoServerSelector.Reason.INITIAL_BEST, view.getDecision().reason());
        check(view.getApplied(), "The decision was applied");
        waitFor(() -> "second".equals(f.savedSelection(f.telegram)));
        f.controller.disconnect();

        f.good(f.third, 14);
        check(f.controller.connect().getOk(), "Reconnect");
        equal("second", f.autoRoute(f.telegram).getNode().key());
        equal(AutoServerSelector.Reason.KEPT_STABLE, f.controller.autoDecision(f.telegram).getDecision().reason());
    }

    private static void testAutoProvisional(Fixture f) {
        f.setup(Map.of(), Set.of(f.telegram));
        check(f.controller.connect().getOk(), "Connect without any measurement");
        ActiveRoute auto = f.autoRoute(f.telegram);
        equal("main", auto.getNode().key());
        equal(AutoServerSelector.Reason.PROVISIONAL_MAIN, f.controller.autoDecision(f.telegram).getDecision().reason());
        equal(f.main, f.controller.routePlan(false).nodeFor(f.telegram));
        check(f.controller.activeRoutes().stream().allMatch(r -> r.getNode() != null), "No app is left without a server");
    }

    private static void testIndependentRecovery(Fixture f) throws Exception {
        f.good(f.third, 20);
        f.setup(Map.of(f.chrome, "second"), Set.of(f.telegram));
        f.backend.supportsRuntimeChange = true;
        check(f.controller.connect().getOk(), "Connect");
        waitFor(() -> f.controller.routeChecks().values().stream().filter(c -> c.getState() == RouteCheckState.PASSED).count() == 3);
        int starts = f.backend.startCount, stops = f.backend.stopCount;
        ActiveRoute custom = f.controller.activeRoutes().stream().filter(r -> r.getId().equals("second")).findFirst().orElseThrow();
        f.backend.failingPorts.add(custom.getCheckPort());
        check(f.controller.retryRoute("second").getOk(), "Retry now");
        waitFor(() -> f.health("second") != null && f.health("second").state() == RouteHealthTracker.State.FAILED);
        equal(starts, f.backend.startCount);
        equal(stops, f.backend.stopCount);
        equal(RouteHealthTracker.State.HEALTHY, f.health("main").state());
        equal(RouteHealthTracker.State.HEALTHY, f.health(f.autoRoute(f.telegram).getId()).state());
        equal(RouteCheckState.PASSED, f.controller.routeChecks().get("main").getState());
        equal(RouteCheckState.FAILED, f.controller.routeChecks().get("second").getState());
        check(!f.backend.runtimeChanges.isEmpty(), "The failing route must be rebuilt in place");
        String[] rebuild = f.backend.runtimeChanges.get(0);
        equal("perroute-route-0~1", rebuild[0]);
        equal("perroute-route-0", rebuild[1]);
        Map<String, String> targets = XrayConfigGenerator.applicationRouteTargets(rebuild[2]);
        equal("proxy", targets.get("perroute-app-proxy"));
        equal("perroute-route-1", targets.get("perroute-app-route-1"));
        check(rebuild[3].contains("second.example") && !rebuild[3].contains("third.example"), "Only the failing route's outbound is replaced");
        equal(Map.of(AppRoutes.normalize(f.chrome), "second"), f.controller.appRoutes());
        check(f.health("second").events().stream().anyMatch(e -> e.type() == RouteHealthTracker.EventType.EXHAUSTED), "The timeline shows exhaustion");

        f.backend.failingPorts.clear();
        f.controller.retryRoute("second");
        waitFor(() -> f.health("second").state() == RouteHealthTracker.State.HEALTHY);
        waitFor(() -> f.logs.stream().anyMatch(line -> line.contains("recovered on its own route")));
        check(f.controller.qualityScore("second").components().recoveryPenalty() > 0, "A recovery counts against the server's quality");
        equal(starts, f.backend.startCount);
    }

    private static void testAutoFailover(Fixture f) throws Exception {
        f.good(f.third, 10);
        f.good(f.second, 200);
        f.setup(Map.of(), Set.of(f.telegram));
        f.backend.supportsRuntimeChange = true;
        check(f.controller.connect().getOk(), "Connect");
        equal("third", f.autoRoute(f.telegram).getNode().key());
        int starts = f.backend.startCount;
        Thread.sleep(450);
        f.backend.failingPorts.add(f.autoRoute(f.telegram).getCheckPort());
        f.controller.retryRoute(f.autoRoute(f.telegram).getId());
        waitFor(() -> f.autoRoute(f.telegram) != null && f.autoRoute(f.telegram).getNode().key().equals("second"));
        f.backend.failingPorts.clear();
        equal(starts, f.backend.startCount);
        String[] failover = f.backend.runtimeChanges.get(f.backend.runtimeChanges.size() - 1);
        check(failover[3].contains("second.example"), "The new outbound carries the new server");
        equal("proxy", XrayConfigGenerator.applicationRouteTargets(failover[2]).get("perroute-app-proxy"));
        waitFor(() -> "second".equals(f.savedSelection(f.telegram)));
        equal(AutoServerSelector.Reason.FAILOVER, f.controller.autoDecision(f.telegram).getDecision().reason());
        check(f.logs.stream().anyMatch(line -> line.contains("Auto moved") && line.contains("Other routes were not touched")), "The failover is logged");
        equal("main", f.controller.activeRoutes().get(0).getNode().key());
    }

    private static void testInPlaceMainSwitch(Fixture f) {
        f.setup(Map.of(f.chrome, "second"), Set.of());
        f.backend.supportsRuntimeChange = true;
        check(f.controller.connect().getOk(), "Connect");
        int starts = f.backend.startCount;
        check(f.backend.lastConfig.contains("HandlerService") && f.backend.lastConfig.contains("RoutingService"), "Route monitoring needs the control API");
        check(f.controller.switchNode("third").getOk(), "Switch");
        equal(starts, f.backend.startCount);
        equal(1, f.backend.runtimeChanges.size());
        String[] change = f.backend.runtimeChanges.get(0);
        equal("proxy~1", change[0]);
        equal("proxy", change[1]);
        check(change[3].contains("third.example"), "The main outbound now carries the new server");
        equal("perroute-route-0", XrayConfigGenerator.applicationRouteTargets(change[2]).get("perroute-app-route-0"));
        check(change[4].contains("\"proxy~1\"") && !change[4].contains("\"proxy\""), "Every main rule points at the new outbound");
        equal("third", f.controller.selectedNodeKey());
        equal("third", f.controller.activeRoutes().get(0).getNode().key());
        equal("second", f.controller.activeRoutes().get(1).getNode().key());
        check(f.controller.switchNode("main").getOk(), "Switch back");
        equal("proxy", f.backend.runtimeChanges.get(1)[0]);
        equal("proxy~1", f.backend.runtimeChanges.get(1)[1]);
        check(f.controller.switchNode("second").getOk(), "Switch to the custom server");
        equal(starts + 1, f.backend.startCount);
        equal(2, f.backend.runtimeChanges.size());
    }

    private static void testRuntimeChangeRefused(Fixture f) {
        f.setup(Map.of(f.chrome, "second"), Set.of());
        f.backend.supportsRuntimeChange = true;
        f.backend.failRuntimeChange = "Xray refused ado";
        check(f.controller.connect().getOk(), "Connect");
        int starts = f.backend.startCount;
        check(f.controller.switchNode("third").getOk(), "The switch still works through a restart");
        equal(starts + 1, f.backend.startCount);
        check(f.controller.isConnected(), "Still connected");
        check(f.logs.stream().anyMatch(line -> line.contains("could not be replaced in place")), "The fallback is logged");
    }

    private static void testChangeRouteServer(Fixture f) throws Exception {
        f.good(f.third, 20);
        f.setup(Map.of(f.chrome, "second"), Set.of(f.telegram));
        f.backend.supportsRuntimeChange = true;
        check(f.controller.connect().getOk(), "Connect");
        int starts = f.backend.startCount;
        ActiveRoute custom = f.controller.activeRoutes().stream().filter(r -> r.getId().equals("second")).findFirst().orElseThrow();
        f.backend.failingPorts.add(custom.getCheckPort());
        f.controller.retryRoute("second");
        waitFor(() -> f.health("second") != null && f.health("second").state() == RouteHealthTracker.State.FAILED);
        f.backend.failingPorts.clear();

        int changes = f.backend.runtimeChanges.size();
        check(f.controller.changeRouteServer("second", "third").getOk(), "Change the failed route's server");
        check(f.controller.isConnected(), "Still connected");
        equal(starts, f.backend.startCount);
        equal(changes + 1, f.backend.runtimeChanges.size());
        check(f.backend.runtimeChanges.get(changes)[3].contains("third.example"), "The new outbound carries the new server");
        equal(Map.of(AppRoutes.normalize(f.chrome), "third"), f.controller.appRoutes());
        check(f.controller.activeRoutes().stream().anyMatch(r -> r.getId().equals("third") && !r.getMain() && !r.getAuto()), "The route now uses the new server");
        check(f.controller.activeRoutes().stream().noneMatch(r -> r.getId().equals("second")), "The old route is gone");
        check(f.health("third") != null, "The moved route is watched");
        equal("main", f.controller.activeRoutes().get(0).getNode().key());
        waitFor(() -> "third".equals(savedRoute(f, f.chrome)));

        check(f.controller.changeRouteServer("third", "main").getOk(), "Move onto the main server");
        equal(starts + 1, f.backend.startCount);
        check(f.controller.isConnected(), "Still connected after the restart");
        equal(Map.of(AppRoutes.normalize(f.chrome), "main"), f.controller.appRoutes());

        String autoId = f.autoRoute(f.telegram).getId();
        check(f.controller.changeRouteServer(autoId, "second").getOk(), "Change the Auto route's server");
        equal("second", f.autoRoute(f.telegram).getNode().key());
        equal(starts + 1, f.backend.startCount);
        waitFor(() -> "second".equals(f.savedSelection(f.telegram)));

        check(!f.controller.changeRouteServer("missing", "second").getOk(), "An unknown route is refused");
        check(!f.controller.changeRouteServer(autoId, "nowhere").getOk(), "An unknown server is refused");
        f.controller.disconnect();
        check(!f.controller.changeRouteServer(autoId, "third").getOk(), "Needs a connection");
    }

    private static String savedRoute(Fixture f, String path) {
        try { return f.store.load().appRoutes().get(AppRoutes.normalize(path)); }
        catch (IOException e) { return null; }
    }

    private static void testGatewayAndLimits(Fixture f) throws Exception {
        check(f.controller.trafficGateway(), "A fresh installation starts with the gateway on");
        check(f.controller.setBandwidthPolicy(f.telegram, new BandwidthPolicy(8_000_000, 2_000_000)).getOk(), "Set a limit");
        equal(BandwidthState.PENDING, f.controller.bandwidthStatus(f.telegram).getState());
        equal(BandwidthState.UNLIMITED, f.controller.bandwidthStatus(f.chrome).getState());
        check(f.controller.connect().getOk(), "Connect");
        equal(1, f.backend.gatewayStarts);
        equal(3, f.backend.gatewayRoutes.size());
        AppGateway.Route route = f.backend.gatewayRoutes.get(1);
        equal(f.telegram, route.applicationPath());
        equal(XrayConfigGenerator.perAppPort(1), route.listenPort());
        equal(XrayConfigGenerator.gatewayUpstreamPort(1), route.upstreamPort());
        Map<String, Object> config = Json.object(f.backend.lastConfig);
        Object inbound = ((List<?>) config.get("inbounds")).stream().map(Map.class::cast)
                .filter(map -> "perroute-app-1".equals(map.get("tag"))).findFirst().orElseThrow().get("port");
        equal(XrayConfigGenerator.gatewayUpstreamPort(1), ((Number) inbound).intValue());
        equal(List.of(f.chrome, f.telegram, f.discord), f.backend.perAppApplications);
        long[] limit = f.backend.gatewayLimits.get(f.telegram.toLowerCase());
        equal(1_000_000L, limit[0]);
        equal(250_000L, limit[1]);
        equal(BandwidthState.ENFORCED, f.controller.bandwidthStatus(f.telegram).getState());

        Thread pump = Thread.ofVirtual().start(() -> {
            long down = 0;
            while (!Thread.currentThread().isInterrupted()) {
                down += 400_000;
                f.backend.gatewayCounters.put(f.telegram, new AppGateway.Counters(down, 0, 2, 0));
                try { Thread.sleep(200); } catch (InterruptedException e) { return; }
            }
        });
        try {
            waitFor(() -> f.controller.bandwidthStatus(f.telegram).getState() == BandwidthState.THROTTLING);
            check(f.controller.trafficSnapshot().getApplications().get(1).getDownloadBytesPerSecond() > 1_000_000, "Per-app rates come from the gateway");
        } finally {
            pump.interrupt();
        }

        check(f.controller.setBandwidthPolicy(f.telegram, BandwidthPolicy.UNLIMITED).getOk(), "Remove the limit while connected");
        equal(0L, f.backend.gatewayLimits.get(f.telegram.toLowerCase())[0]);
        equal(BandwidthState.UNLIMITED, f.controller.bandwidthStatus(f.telegram).getState());
        f.controller.setBandwidthPolicy(f.discord, new BandwidthPolicy(4_000_000, 0));
        equal(500_000L, f.backend.gatewayLimits.get(f.discord.toLowerCase())[0]);
        f.controller.disconnect();
        check(!f.backend.gatewayRunning && f.backend.gatewayStops >= 1, "Disconnecting stops the gateway");
        waitFor(() -> { try { return new BandwidthPolicy(4_000_000, 0).equals(f.store.load().bandwidthPolicies().get(AppRoutes.normalize(f.discord))); } catch (IOException e) { return false; } });

        f.controller.setMode(ConnectionMode.SYSTEM_PROXY);
        check(f.controller.connect().getOk(), "System proxy");
        equal(BandwidthState.NOT_ENFORCED, f.controller.bandwidthStatus(f.discord).getState());
        equal(1, f.backend.gatewayStarts);
    }

    private static void testGatewayFailure(Fixture f) {
        f.backend.failGateway = "port 18100 is in use";
        int restores = f.backend.restorePerAppCount;
        ActionResult result = f.controller.connect();
        check(!result.getOk() && result.getMessage().contains("port 18100"), "The gateway error is reported: " + result.getMessage());
        check(!f.backend.running, "The core is stopped again");
        check(!f.controller.isConnected(), "Not connected");
        check(f.backend.restorePerAppCount > restores, "The per-app driver is released");
        f.backend.failGateway = null;
        check(f.controller.connect().getOk(), "A later attempt works");
    }

    private static void testMigratedDefaults(Fixture f) throws Exception {
        check(!f.controller.trafficGateway() && !f.controller.routeMonitoring() && !f.controller.qualityBackground(),
                "Migrated installations start with the new automatic features off");
        check(f.controller.autoApps().isEmpty(), "No app is in Auto mode");
        f.controller.setBandwidthPolicy(f.telegram, new BandwidthPolicy(8_000_000, 0));
        check(f.controller.connect().getOk(), "Connect");
        equal(0, f.backend.gatewayStarts);
        check(!f.backend.lastConfig.contains("HandlerService") && !f.backend.lastConfig.contains("RoutingService"),
                "Without the features that need it, the core keeps the read-only statistics API of version 2");
        Map<String, Object> config = Json.object(f.backend.lastConfig);
        Object inbound = ((List<?>) config.get("inbounds")).stream().map(Map.class::cast)
                .filter(map -> "perroute-app-0".equals(map.get("tag"))).findFirst().orElseThrow().get("port");
        equal(XrayConfigGenerator.perAppPort(0), ((Number) inbound).intValue());
        var status = f.controller.bandwidthStatus(f.telegram);
        equal(BandwidthState.NOT_ENFORCED, status.getState());
        check(status.getDetail().contains("gateway is off"), status.getDetail());
        waitFor(() -> f.controller.routeChecks().getOrDefault("main", new app.perroute.compose.controller.RouteCheck()).getState() == RouteCheckState.PASSED);
        f.backend.failingPorts.add(app.perroute.service.AppProxyLauncher.SOCKS_PORT);
        Thread.sleep(600);
        equal(RouteCheckState.PASSED, f.controller.routeChecks().get("main").getState());
        f.controller.disconnect();
        check(f.controller.setTrafficGateway(true).getOk(), "The gateway can be turned on while disconnected");
        f.controller.setRouteMonitoring(true);
        check(f.controller.connect().getOk(), "Connect with the gateway");
        equal(1, f.backend.gatewayStarts);
        check(!f.controller.setTrafficGateway(false).getOk(), "The gateway cannot be switched while connected");
    }

    private static void testKillSwitch(Fixture f) throws Exception {
        f.controller.setKillSwitch(true);
        int restores = f.backend.restorePerAppCount;
        check(f.controller.connect().getOk(), "Connect");
        check(f.backend.applyPerAppCount >= 1, "The driver holds the apps");
        f.controller.disconnect();
        equal(restores, f.backend.restorePerAppCount);
        check(!f.backend.gatewayRunning, "The gateway is stopped; the held apps have nowhere to go");
        check(f.controller.connect().getOk(), "Reconnect");
        f.backend.crash();
        waitFor(() -> !f.backend.gatewayRunning && !f.controller.isConnected());
        check(!f.backend.gatewayRunning, "A core crash stops the gateway too");
        equal(restores, f.backend.restorePerAppCount);
        check(!f.controller.isConnected(), "The crash is noticed");
    }

    private static void testInspector(Fixture f) throws Exception {
        f.controller.setTrafficGateway(false);
        f.controller.addRule("News direct", "", RouteRule.MatchType.DOMAIN, "news.example", RouteRule.Action.DIRECT);
        String ruleId = f.controller.rules().get(0).id();
        check(f.controller.connect().getOk(), "Connect");
        check(f.controller.inspectorAvailable(), "Smart per-app can be inspected");
        f.backend.emit("2026/10/14 13:22:17 [Info] [41] proxy/socks: TCP Connect request to tcp:203.0.113.9:443");
        f.backend.emit("2026/10/14 13:22:17 [Info] [41] app/dispatcher: sniffed domain: news.example");
        f.backend.emit("2026/10/14 13:22:17 [Info] [41] app/dispatcher: Hit route rule: [perroute-custom-" + ruleId + "] so taking detour [direct] for [tcp:news.example:443]");
        f.backend.emit("2026/10/14 13:22:17 from tcp:127.0.0.1:5555 accepted tcp:203.0.113.9:443 [perroute-app-1 -> direct]");
        f.backend.emit("2026/10/14 13:22:18 from tcp:127.0.0.1:5556 accepted tcp:198.51.100.7:443 [perroute-app-0 -> proxy]");
        List<InspectorRow> rows = f.controller.inspectorRows();
        equal(2, rows.size());
        InspectorRow news = rows.stream().filter(r -> r.getDestinationHost().equals("203.0.113.9")).findFirst().orElseThrow();
        equal(f.telegram, news.getApplicationPath());
        equal(RouteKind.DIRECT, news.getObserved().getKind());
        equal("News direct", news.getObserved().getRuleLabel());
        equal("news.example", news.getObserved().getSniffedDomain());
        equal(RouteKind.DIRECT, news.getConfigured().getKind());
        equal("News direct", news.getConfigured().getRuleLabel());
        check(!news.getViaGateway(), "Without the gateway there are no byte counts");
        equal(null, news.getVerified());

        InspectorRow other = rows.stream().filter(r -> r.getDestinationHost().equals("198.51.100.7")).findFirst().orElseThrow();
        equal(f.chrome, other.getApplicationPath());
        equal(RouteKind.PROXY, other.getObserved().getKind());
        equal("main.example", other.getObserved().getRouteLabel());
        equal(null, other.getObserved().getRuleLabel());
        equal("main.example", other.getConfigured().getRouteLabel());

        f.controller.checkRoutes();
        InspectorRow verified = f.controller.inspectorRows().stream().filter(r -> r.getDestinationHost().equals("203.0.113.9")).findFirst().orElseThrow();
        check(verified.getVerified() != null && verified.getVerified().getPassed(), "The direct route check verifies the direct exit");
        equal("198.51.100.24", verified.getVerified().getPublicIp());
        f.controller.disconnect();
        equal(0, f.controller.inspectorRows().size());
    }

    private static void testBackupRestore(Fixture f) throws Exception {
        f.controller.addRule("Keep", "", RouteRule.MatchType.DOMAIN, "keep.example", RouteRule.Action.PROXY);
        f.setup(Map.of(f.chrome, "second"), Set.of(f.telegram));
        f.controller.setBandwidthPolicy(f.discord, new BandwidthPolicy(2_000_000, 0));
        Path file = f.directory.resolve("backup.prb");
        check(f.controller.exportBackup(file, EnumSet.allOf(BackupService.Section.class), "backup password".toCharArray()).getOk(), "Export");
        check(!Files.readString(file).contains("second.example"), "Server credentials are encrypted");

        f.controller.removeRule(f.controller.rules().get(0).id());
        f.controller.pasteLinks("vless://11111111-1111-1111-1111-111111111111@extra.example:443?security=tls#Extra");
        equal(4, f.controller.nodes().size());

        check(f.controller.openBackup(file, "wrong password".toCharArray()).getFirst() == null, "A wrong password is refused");
        var opened = f.controller.openBackup(file, "backup password".toCharArray());
        RestoreRequest request = opened.getFirst();
        check(request != null, "Open: " + opened.getSecond());
        equal(3, request.getPreview().counts().servers());
        equal(1, request.getPreview().counts().rules());

        check(f.controller.connect().getOk(), "Connect");
        check(!f.controller.restoreBackup(request, BackupService.Mode.REPLACE, Map.of()).getOk(), "Restoring is refused while connected");
        f.controller.disconnect();

        check(f.controller.restoreBackup(request, BackupService.Mode.REPLACE, Map.of()).getOk(), "Restore");
        equal(3, f.controller.nodes().size());
        equal(List.of("Keep"), f.controller.rules().stream().map(RouteRule::name).toList());
        equal(AppRouteMode.AUTO, f.controller.appRouteMode(f.telegram));
        equal(new BandwidthPolicy(2_000_000, 0), f.controller.bandwidthPolicy(f.discord));
        check(f.controller.hasRestoreSnapshot(), "A rollback snapshot exists");
        equal(3, f.store.load().nodes().size());

        check(f.controller.undoRestore().getOk(), "Undo");
        equal(4, f.controller.nodes().size());
        equal(0, f.controller.rules().size());
        check(!f.controller.hasRestoreSnapshot(), "The snapshot is used up");

        Path blocker = f.directory.resolve("data").resolve("state.bin.tmp");
        Files.createDirectories(blocker);
        Files.writeString(blocker.resolve("keep"), "x");
        ActionResult failed = f.controller.restoreBackup(request, BackupService.Mode.REPLACE, Map.of());
        check(!failed.getOk() && failed.getMessage().contains("put back"), "A failed restore is rolled back: " + failed.getMessage());
        equal(4, f.controller.nodes().size());
        equal(0, f.controller.rules().size());
        V3DomainTests.deleteTree(blocker);
    }

    @SuppressWarnings("unchecked")
    private static void testRulePrecedence(Fixture f) {
        f.good(f.third, 20);
        f.controller.addRule("Work via proxy", "", RouteRule.MatchType.DOMAIN, "work.example", RouteRule.Action.PROXY);
        f.controller.addRule("Block ads", "", RouteRule.MatchType.DOMAIN, "ads.example", RouteRule.Action.BLOCK);
        String work = f.controller.rules().get(0).id();
        f.setup(Map.of(f.chrome, "second"), Set.of(f.telegram));
        check(f.controller.connect().getOk(), "Connect");
        List<Map<String, Object>> rules = ((List<Object>) ((Map<String, Object>) Json.object(f.backend.lastConfig).get("routing")).get("rules"))
                .stream().map(item -> (Map<String, Object>) item).toList();
        List<String> tags = rules.stream().map(rule -> String.valueOf(rule.get("ruleTag"))).toList();
        int mainPart = tags.indexOf("perroute-custom-" + work);
        int customPart = tags.indexOf("perroute-custom-" + work + "#route0");
        int autoPart = tags.indexOf("perroute-custom-" + work + "#route1");
        check(mainPart >= 0 && customPart > mainPart && autoPart > customPart, "A proxy rule is split per route at one position: " + tags);
        check(autoPart < tags.indexOf("perroute-app-proxy"), "User rules come before the app routes");
        equal("perroute-route-1", rules.get(autoPart).get("outboundTag"));
        check(((List<?>) rules.get(autoPart).get("inboundTag")).contains("perroute-app-1"), "The Auto part applies to the Auto app only");
        Map<String, Object> block = rules.stream().filter(rule -> String.valueOf(rule.get("ruleTag")).startsWith("perroute-custom-") && "block".equals(rule.get("outboundTag")))
                .findFirst().orElseThrow();
        check(((List<?>) block.get("inboundTag")).contains("perroute-app-1"), "Block rules cover Auto apps too");
    }

    private static void testRemovedAutoServer(Fixture f) throws InterruptedException {
        f.good(f.second, 15);
        f.setup(Map.of(), Set.of(f.telegram));
        check(f.controller.connect().getOk(), "Connect");
        equal("second", f.autoRoute(f.telegram).getNode().key());
        waitFor(() -> f.controller.routeHealth().stream().allMatch(view -> view.getHealth() != null
                && view.getHealth().state() == app.perroute.routing.RouteHealthTracker.State.HEALTHY));
        f.controller.disconnect();
        check(f.controller.removeNode("second").getOk(), "Remove the server");
        check(f.controller.routePlan(false).ready(), "Auto never blocks on a removed server");
        check(f.controller.connect().getOk(), "Connect again");
        equal("main", f.autoRoute(f.telegram).getNode().key());
        check(f.controller.autoDecision(f.telegram).getDecision().explanation().contains("no longer available"), f.controller.autoDecision(f.telegram).getDecision().explanation());
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        check(condition.getAsBoolean(), "Timed out waiting for a condition");
    }

    private static void testUpdatedStoreApp(Fixture f) throws Exception {
        Path installed = f.directory.resolve("WindowsApps").resolve("Example.Chat_2.0.0.0_x64__pub0example01").resolve("app").resolve("Chat.exe");
        Files.createDirectories(installed.getParent());
        Files.writeString(installed, "test");
        String saved = f.directory.resolve("WindowsApps").resolve("Example.Chat_1.0.0.0_x64__pub0example01").resolve("app").resolve("Chat.exe").toString();
        check(f.controller.saveApplicationSetup(List.of(f.chrome, saved), Map.of(), false).getOk(), "Save the selection");
        f.backend.installed = List.of(new app.perroute.backend.api.ApplicationDescriptor("Chat", installed.toString()));
        check(f.controller.connect().getOk(), "Connect");
        check(f.backend.perAppApplications.contains(installed.toString()), "The engine routes the installed version: " + f.backend.perAppApplications);
        check(!f.backend.perAppApplications.contains(saved), "The old folder is not given to the engine");
        check(f.backend.perAppApplications.contains(f.chrome), "Other apps are untouched");
        check(f.controller.applications().contains(saved), "The saved selection keeps its path");
        check(f.logs.stream().anyMatch(line -> line.contains("updated by the Microsoft Store")), "The change is logged");
        f.controller.disconnect();
    }

    private static void testUiStylePreference(Fixture f) throws Exception {
        equal("new", f.controller.uiStyle());
        f.controller.setDarkTheme(false);
        f.controller.setAutoSpeedTest(false);
        Map<String, String> before = snapshot(f.preferences);
        f.controller.setUiStyle("simple");
        equal("simple", f.controller.uiStyle());
        Map<String, String> after = snapshot(f.preferences);
        after.remove(PerRouteController.PREF_UI_STYLE);
        equal(before, after);
        check(!f.controller.isDarkTheme(), "Choosing a design leaves the theme alone");
        f.controller.setDarkTheme(true);
        equal("simple", f.controller.uiStyle());

        try (PerRouteController restarted = new PerRouteController(f.state, f.store, f.backend, f.preferences, FAST, 60_000L)) {
            equal("simple", restarted.uiStyle());
        }

        f.preferences.put(PerRouteController.PREF_UI_STYLE, "something else");
        equal("new", f.controller.uiStyle());
        f.controller.setUiStyle("unknown");
        equal("new", f.preferences.get(PerRouteController.PREF_UI_STYLE, ""));

        f.controller.setUiStyle("simple");
        Path file = f.directory.resolve("style.prb");
        check(f.controller.exportBackup(file, EnumSet.allOf(BackupService.Section.class), null).getOk(), "Export");
        f.controller.setUiStyle("new");
        RestoreRequest request = f.controller.openBackup(file, null).getFirst();
        check(request != null, "Open the backup");
        check(f.controller.restoreBackup(request, BackupService.Mode.REPLACE, Map.of()).getOk(), "Restore");
        equal("new", f.controller.uiStyle());

        f.controller.setUiStyle("simple");
        check(f.controller.resetAll().getOk(), "Reset");
        equal("new", f.controller.uiStyle());
    }

    private static Map<String, String> snapshot(Preferences preferences) throws Exception {
        Map<String, String> values = new java.util.TreeMap<>();
        for (String key : preferences.keys()) values.put(key, preferences.get(key, null));
        return values;
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
