package app.perroute;

import app.perroute.backup.BackupCodec;
import app.perroute.backup.BackupService;
import app.perroute.gateway.AppGateway;
import app.perroute.gateway.GatewayConnection;
import app.perroute.inspector.ConnectionInspector;
import app.perroute.inspector.XrayLogParser;
import app.perroute.model.AppRoutes;
import app.perroute.model.AppState;
import app.perroute.model.BandwidthPolicy;
import app.perroute.model.ConnectionMode;
import app.perroute.model.ConnectionProfile;
import app.perroute.model.Protocol;
import app.perroute.model.ProxyNode;
import app.perroute.model.RouteRule;
import app.perroute.persistence.AppStateStore;
import app.perroute.quality.NetworkQualityScorer;
import app.perroute.quality.QualityHistoryStore;
import app.perroute.quality.QualityLabel;
import app.perroute.quality.QualitySample;
import app.perroute.quality.QualityScore;
import app.perroute.routing.AutoCandidatePool;
import app.perroute.routing.AutoSelection;
import app.perroute.routing.AutoServerSelector;
import app.perroute.routing.RouteHealthTracker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

final class V3DomainTests {
    private static int assertions;
    private static final long NOW = 1_800_000_000_000L;
    private V3DomainTests() {}

    static int assertions() { return assertions; }

    static void runAll() throws Exception {
        run("Store apps: an updated app is found at its new folder", V3DomainTests::testStoreAppPaths);
        run("Quality: unknown without evidence", V3DomainTests::testQualityUnknown);
        run("Quality: excellent, explained and deterministic", V3DomainTests::testQualityExcellent);
        run("Quality: port checks alone are capped", V3DomainTests::testQualityUnverifiedCap);
        run("Quality: unavailable after failures", V3DomainTests::testQualityUnavailable);
        run("Quality: stale and forgotten results", V3DomainTests::testQualityStale);
        run("Quality: jitter, stability and recovery", V3DomainTests::testQualityJitterAndRecovery);
        run("Quality: bounded persisted history", V3DomainTests::testQualityHistoryStore);
        run("Auto: initial choice and no-evidence fallback", V3DomainTests::testAutoInitial);
        run("Auto: hysteresis and cooldown", V3DomainTests::testAutoHysteresis);
        run("Auto: failover and never direct", V3DomainTests::testAutoFailover);
        run("Auto: candidate pools", V3DomainTests::testAutoPools);
        run("Route health: bounded backoff and recovery", V3DomainTests::testRouteHealth);
        run("Gateway: TCP relay and counters", V3DomainTests::testGatewayTcp);
        run("Gateway: aggregate download and upload limits", V3DomainTests::testGatewayLimits);
        run("Gateway: UDP association and limit", V3DomainTests::testGatewayUdp);
        run("Gateway: failures, all-or-nothing start and cleanup", V3DomainTests::testGatewayFailures);
        run("Inspector: Xray log formats", V3DomainTests::testLogParser);
        run("Inspector: joins gateway and core observations", V3DomainTests::testInspectorJoin);
        run("Inspector: core-only mode and bounds", V3DomainTests::testInspectorBounds);
        run("Backup: authenticated encryption", V3DomainTests::testBackupCrypto);
        run("Backup: preview, merge, replace and remap", V3DomainTests::testBackupRestorePlan);
        run("State: v2 migration and v3 round trip", V3DomainTests::testStateMigration);
        run("Rules: one site entry covers every form of it", V3DomainTests::testDomainEntries);
    }

    @SuppressWarnings("unchecked")
    private static void testDomainEntries() {
        RouteRule rule = new RouteRule("", "Snapp", "", RouteRule.MatchType.DOMAIN,
                "https://snapp.ir/, www.snapp.ir, SNAPP.ir:443/login?next=1, http://user@www.snapp.ir#top, snapp.ir.", RouteRule.Action.DIRECT, true);
        equal("snapp.ir", rule.value());
        equal("foo.com, full:www.example.com, full:api.example.com, keyword:Tracker, geosite:netflix, regexp:^ads\\.",
                app.perroute.model.DomainEntries.normalizeList("*.foo.com .foo.com, full:https://www.example.com/x, FULL:api.example.com, keyword:Tracker, geosite:netflix, regexp:^ads\\."));
        equal("xn--mgba3a4f16a.xn--mgba3a4f16a", app.perroute.model.DomainEntries.normalize("ایران.ایران"));
        equal("www.com", app.perroute.model.DomainEntries.normalize("www.com"));
        equal("", app.perroute.model.DomainEntries.normalizeList("https:// , /"));
        try { new RouteRule("", "x", "", RouteRule.MatchType.DOMAIN, "https://", RouteRule.Action.PROXY, true); throw new AssertionError("An entry with no site must be refused"); }
        catch (IllegalArgumentException expected) { }
        equal("https://1.2.3.4/", new RouteRule("", "ip", "", RouteRule.MatchType.IP, "https://1.2.3.4/", RouteRule.Action.PROXY, true).value());

        List<RouteRule> rules = List.of(rule);
        for (String host : List.of("snapp.ir", "www.snapp.ir", "api.snapp.ir", "a.b.snapp.ir")) {
            equal(RouteRule.Action.DIRECT, app.perroute.service.RouteRuleEvaluator.evaluate(rules, List.of(), "", host, 443).action());
        }
        equal(RouteRule.Action.PROXY, app.perroute.service.RouteRuleEvaluator.evaluate(rules, List.of(), "", "notsnapp.ir", 443).action());

        Map<String, Object> config = app.perroute.util.Json.object(new app.perroute.service.XrayConfigGenerator()
                .generateApplicationProxy(node("main", "main.example"), List.of("C:\\A.exe"), rules));
        Map<String, Object> generated = ((List<Object>) ((Map<String, Object>) config.get("routing")).get("rules")).stream()
                .map(item -> (Map<String, Object>) item).filter(item -> String.valueOf(item.get("ruleTag")).startsWith("perroute-custom-"))
                .findFirst().orElseThrow();
        equal(List.of("domain:snapp.ir"), generated.get("domain"));
    }

    @FunctionalInterface private interface CheckedTest { void run() throws Exception; }

    private static void run(String name, CheckedTest test) throws Exception {
        System.out.println("[test] " + name);
        test.run();
    }

    private static List<QualitySample> steady(int count, long spacingMillis, long reachMs, long tunnelMs) {
        List<QualitySample> samples = new ArrayList<>();
        for (int i = count; i >= 1; i--) {
            long at = NOW - i * spacingMillis;
            samples.add(QualitySample.reachability(at, reachMs + (i % 2)));
            samples.add(QualitySample.tunnel(at + 1_000, true, tunnelMs + (i % 3), ""));
        }
        return samples;
    }

    private static void testStoreAppPaths() {
        String old = "C:\\Program Files\\WindowsApps\\Claude_2.37337.1.0_x64__pzs8sxrjxfjjc\\app\\Claude.exe";
        String now = "C:\\Program Files\\WindowsApps\\Claude_2.42304.0.0_x64__pzs8sxrjxfjjc\\app\\claude.exe";
        String older = "C:\\Program Files\\WindowsApps\\Claude_2.40000.0.0_x64__pzs8sxrjxfjjc\\app\\Claude.exe";
        String otherPublisher = "C:\\Program Files\\WindowsApps\\Claude_9.0.0.0_x64__someoneelse01\\app\\Claude.exe";
        String otherArch = "C:\\Program Files\\WindowsApps\\Claude_9.0.0.0_arm64__pzs8sxrjxfjjc\\app\\Claude.exe";
        String chatgpt = "C:\\Program Files\\WindowsApps\\OpenAI.Codex_26.1013.3656.0_x64__2p2nqsd0c76g0\\app\\ChatGPT.exe";
        java.util.Set<String> installed = java.util.Set.of(now, older, otherPublisher, otherArch, chatgpt);
        java.util.function.Predicate<String> exists = installed::contains;
        check(app.perroute.service.StoreAppPaths.isStorePath(old), "A WindowsApps package path is recognised");
        check(!app.perroute.service.StoreAppPaths.isStorePath("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"), "An ordinary path is not a Store path");
        equal(now, app.perroute.service.StoreAppPaths.resolve(old, installed, exists));
        equal(chatgpt, app.perroute.service.StoreAppPaths.resolve(
            "C:\\Program Files\\WindowsApps\\OpenAI.Codex_26.1007.2314.0_x64__2p2nqsd0c76g0\\app\\ChatGPT.exe", installed, exists));
        equal(now, app.perroute.service.StoreAppPaths.resolve(now, installed, exists));
        equal("C:\\Apps\\tool.exe", app.perroute.service.StoreAppPaths.resolve("C:\\Apps\\tool.exe", installed, exists));
        String unknown = "C:\\Program Files\\WindowsApps\\Unknown.App_1.0.0.0_x64__abcdefabcdef1\\Unknown.exe";
        equal(unknown, app.perroute.service.StoreAppPaths.resolve(unknown, installed, exists));
        equal(old, app.perroute.service.StoreAppPaths.resolve(old, java.util.List.of(otherPublisher, otherArch), exists));
        equal(old, app.perroute.service.StoreAppPaths.resolve(old, java.util.List.of(now), path -> false));
    }

    private static void testQualityUnknown() {
        QualityScore score = NetworkQualityScorer.score(List.of(), NOW);
        equal(QualityLabel.UNKNOWN, score.label());
        equal(null, score.score());
        check(!score.usable(), "An unmeasured server must not be usable");
        equal(0.0, score.confidence());
        check(score.headline().contains("Not measured"), score.headline());
    }

    private static void testQualityExcellent() {
        List<QualitySample> samples = steady(6, 60_000, 20, 120);
        QualityScore score = NetworkQualityScorer.score(samples, NOW);
        check(score.score() != null && score.score() >= 85, "Low steady latency must be excellent: " + score);
        equal(QualityLabel.EXCELLENT, score.label());
        check(score.tunnelVerified(), "Tunnel samples verify the server");
        check(score.confidence() >= 0.9, "Twelve fresh samples give high confidence: " + score.confidence());
        check(!score.stale(), "Fresh samples are not stale");
        equal(100, score.components().successScore());
        check(score.components().latencyMillis() <= 21, "Median latency from port checks");
        check(score.explanation().contains("100% of recent checks succeeded"), score.explanation());
        equal(score, NetworkQualityScorer.score(new ArrayList<>(samples), NOW));
    }

    private static void testQualityUnverifiedCap() {
        List<QualitySample> samples = new ArrayList<>();
        for (int i = 10; i >= 1; i--) samples.add(QualitySample.reachability(NOW - i * 30_000, 15));
        QualityScore score = NetworkQualityScorer.score(samples, NOW);
        check(score.score() <= 74, "Without a tunnel check the score must be capped: " + score.score());
        check(!score.tunnelVerified(), "Port checks are not tunnel verification");
        check(score.confidence() <= 0.6, "Confidence must be lower without tunnel evidence: " + score.confidence());
        check(score.explanation().contains("Only the server's port answered"), score.explanation());
    }

    private static void testQualityUnavailable() {
        List<QualitySample> samples = new ArrayList<>(steady(4, 60_000, 30, 150));
        samples.add(QualitySample.tunnel(NOW - 20_000, false, -1, "timeout"));
        samples.add(QualitySample.tunnel(NOW - 10_000, false, -1, "timeout"));
        QualityScore score = NetworkQualityScorer.score(samples, NOW);
        equal(QualityLabel.UNAVAILABLE, score.label());
        equal(0, score.score());
        check(!score.usable(), "An unavailable server is not usable");
        check(score.explanation().contains("last two checks failed"), score.explanation());
        QualityScore single = NetworkQualityScorer.score(List.of(QualitySample.reachability(NOW - 1_000, -1)), NOW);
        equal(QualityLabel.UNAVAILABLE, single.label());
    }

    private static void testQualityStale() {
        List<QualitySample> samples = steady(6, 60_000, 20, 120);
        QualityScore fresh = NetworkQualityScorer.score(samples, NOW);
        QualityScore later = NetworkQualityScorer.score(samples, NOW + 45 * 60_000L);
        check(later.stale(), "Results older than 30 minutes are stale");
        check(later.confidence() < fresh.confidence(), "Stale results lose confidence");
        equal("Last result may be outdated.", later.headline());
        QualityScore old = NetworkQualityScorer.score(samples, NOW + 25 * 60 * 60_000L);
        equal(QualityLabel.UNKNOWN, old.label());
        check(old.measuredAt() > 0 && old.headline().contains("too long ago"), old.headline());
    }

    private static void testQualityJitterAndRecovery() {
        List<QualitySample> jittery = new ArrayList<>();
        long[] latencies = {20, 180, 30, 200, 25, 190};
        for (int i = 0; i < latencies.length; i++) {
            jittery.add(QualitySample.reachability(NOW - (latencies.length - i) * 60_000L, latencies[i]));
            jittery.add(QualitySample.tunnel(NOW - (latencies.length - i) * 60_000L + 500, true, 200, ""));
        }
        QualityScore score = NetworkQualityScorer.score(jittery, NOW);
        check(score.components().jitterMillis() >= 150, "Large swings are jitter: " + score.components().jitterMillis());
        equal(0, score.components().jitterScore());
        QualityScore steadyScore = NetworkQualityScorer.score(steady(6, 60_000, 20, 200), NOW);
        check(score.score() < steadyScore.score(), "Jitter must lower the score");

        List<QualitySample> recovered = new ArrayList<>(steady(5, 60_000, 20, 120));
        recovered.add(QualitySample.tunnel(NOW - 30_000, false, -1, "reset"));
        recovered.add(QualitySample.recovery(NOW - 10_000));
        recovered.add(QualitySample.tunnel(NOW - 9_000, true, 120, ""));
        QualityScore afterRecovery = NetworkQualityScorer.score(recovered, NOW);
        check(afterRecovery.components().recoveryPenalty() > 0, "A recent recovery must cost points");
        check(afterRecovery.components().stabilityScore() < 100, "A failure lowers stability");
        check(afterRecovery.explanation().contains("recovered from a failure"), afterRecovery.explanation());
    }

    private static void testQualityHistoryStore() throws Exception {
        Path directory = Files.createTempDirectory("perroute-quality-");
        Path file = directory.resolve("quality.json");
        try {
            QualityHistoryStore store = new QualityHistoryStore(file);
            long now = System.currentTimeMillis();
            for (int i = 0; i < 100; i++) store.record("a", QualitySample.reachability(now - (100 - i) * 1_000L, 10 + i % 3));
            equal(QualityHistoryStore.MAX_SAMPLES_PER_SERVER, store.samples("a").size());
            store.record("b", QualitySample.tunnel(now, true, 80, "secret detail that is not stored"));
            store.close();
            String saved = Files.readString(file);
            check(!saved.contains("secret detail"), "Details are not written to disk");
            QualityHistoryStore reloaded = new QualityHistoryStore(file);
            equal(QualityHistoryStore.MAX_SAMPLES_PER_SERVER, reloaded.samples("a").size());
            equal(1, reloaded.samples("b").size());
            reloaded.retain(List.of("b"));
            equal(0, reloaded.samples("a").size());
            check(reloaded.score("b", now).score() != null, "A reloaded sample still scores");
            Files.writeString(file, "{not json");
            equal(0, new QualityHistoryStore(file).samples("b").size());
        } finally {
            deleteTree(directory);
        }
    }

    private static QualityScore scored(int score) {
        return new QualityScore(score, QualityLabel.forScore(score), 0.9, NOW, false, true,
                QualityScore.Components.EMPTY, 10, "h", "e");
    }

    private static AutoServerSelector.Candidate candidate(String key, int score) {
        return new AutoServerSelector.Candidate(key, key.toUpperCase(), scored(score), false);
    }

    private static void testAutoInitial() {
        var policy = AutoServerSelector.Policy.DEFAULT;
        var decision = AutoServerSelector.decide(List.of(candidate("a", 70), candidate("b", 92), candidate("c", 80)), null, "main", "Main", policy, NOW);
        equal("b", decision.nodeKey());
        equal(AutoServerSelector.Reason.INITIAL_BEST, decision.reason());
        check(decision.changed(), "A first choice is a change");
        equal("Best quality · 92", decision.summary());

        var unknown = new AutoServerSelector.Candidate("u", "U", NetworkQualityScorer.score(List.of(), NOW), false);
        var none = AutoServerSelector.decide(List.of(unknown), null, "main", "Main", policy, NOW);
        equal("main", none.nodeKey());
        equal(AutoServerSelector.Reason.PROVISIONAL_MAIN, none.reason());
        check(none.explanation().contains("until measurements"), none.explanation());

        var lowConfidence = new AutoServerSelector.Candidate("l", "L", new QualityScore(95, QualityLabel.EXCELLENT, 0.1, NOW, false, true,
                QualityScore.Components.EMPTY, 1, "h", "e"), false);
        equal("main", AutoServerSelector.decide(List.of(lowConfidence), null, "main", "Main", policy, NOW).nodeKey());
        var nothing = AutoServerSelector.decide(List.of(unknown), null, "", "", policy, NOW);
        check(!nothing.hasServer(), "Without evidence or a main server there is no choice");
        equal(AutoServerSelector.Reason.NO_SERVER, nothing.reason());
    }

    private static void testAutoHysteresis() {
        var policy = AutoServerSelector.Policy.DEFAULT;
        var current = new AutoServerSelector.Current("a", NOW - 60 * 60_000L);
        var small = AutoServerSelector.decide(List.of(candidate("a", 80), candidate("b", 86)), current, "main", "Main", policy, NOW);
        equal("a", small.nodeKey());
        equal(AutoServerSelector.Reason.KEPT_MARGIN, small.reason());
        check(!small.changed(), "A small gain must not switch");
        check(small.explanation().contains("only 6 points"), small.explanation());

        var large = AutoServerSelector.decide(List.of(candidate("a", 70), candidate("b", 85)), current, "main", "Main", policy, NOW);
        equal("b", large.nodeKey());
        equal(AutoServerSelector.Reason.SWITCHED_BETTER, large.reason());

        var recent = new AutoServerSelector.Current("a", NOW - 2 * 60_000L);
        var cooling = AutoServerSelector.decide(List.of(candidate("a", 60), candidate("b", 95)), recent, "main", "Main", policy, NOW);
        equal("a", cooling.nodeKey());
        equal(AutoServerSelector.Reason.KEPT_COOLDOWN, cooling.reason());
        var stable = AutoServerSelector.decide(List.of(candidate("a", 90), candidate("b", 80)), current, "main", "Main", policy, NOW);
        equal(AutoServerSelector.Reason.KEPT_STABLE, stable.reason());
        var tie = AutoServerSelector.decide(List.of(candidate("b", 88), candidate("a", 88)), current, "main", "Main", policy, NOW);
        equal("a", tie.nodeKey());
    }

    private static void testAutoFailover() {
        var policy = AutoServerSelector.Policy.DEFAULT;
        var current = new AutoServerSelector.Current("a", NOW - 2 * 60_000L);
        var failing = new AutoServerSelector.Candidate("a", "A", scored(90), true);
        var failover = AutoServerSelector.decide(List.of(failing, candidate("b", 60)), current, "main", "Main", policy, NOW);
        equal("b", failover.nodeKey());
        equal(AutoServerSelector.Reason.FAILOVER, failover.reason());
        check(failover.changed(), "Failover changes the server even inside the cooldown");

        var justChanged = new AutoServerSelector.Current("a", NOW - 10_000L);
        var waiting = AutoServerSelector.decide(List.of(failing, candidate("b", 60)), justChanged, "main", "Main", policy, NOW);
        equal("a", waiting.nodeKey());
        equal(AutoServerSelector.Reason.KEPT_COOLDOWN, waiting.reason());

        var alone = AutoServerSelector.decide(List.of(failing, new AutoServerSelector.Candidate("b", "B", scored(80), true)), current, "main", "Main", policy, NOW);
        equal("a", alone.nodeKey());
        equal(AutoServerSelector.Reason.KEPT_NO_ALTERNATIVE, alone.reason());
        check(alone.hasServer(), "Auto never answers with no server while one is assigned");
        check(alone.explanation().contains("not released to the direct route"), alone.explanation());

        var unavailable = new AutoServerSelector.Candidate("a", "A", NetworkQualityScorer.score(List.of(
                QualitySample.tunnel(NOW - 20_000, false, -1, "x"), QualitySample.tunnel(NOW - 10_000, false, -1, "x")), NOW), false);
        equal("b", AutoServerSelector.decide(List.of(unavailable, candidate("b", 75)), current, "main", "Main", policy, NOW).nodeKey());
        var removed = AutoServerSelector.decide(List.of(candidate("b", 75)), current, "main", "Main", policy, NOW);
        equal("b", removed.nodeKey());
        check(removed.explanation().contains("no longer available"), removed.explanation());
    }

    private static void testAutoPools() {
        equal(AutoCandidatePool.ALL, AutoCandidatePool.decode(""));
        equal("favorites", AutoCandidatePool.decode("favorites").encode());
        AutoCandidatePool tag = AutoCandidatePool.decode("tag:Work");
        equal("tag:Work", tag.encode());
        check(tag.includes("x", false, "work") && !tag.includes("x", true, "home"), "Tag pools match the tag only");
        check(AutoCandidatePool.decode("favorites").includes("x", true, "") && !AutoCandidatePool.decode("favorites").includes("x", false, ""),
                "Favorite pools match favorites only");
        equal(AutoCandidatePool.ALL, AutoCandidatePool.decode("tag:"));
    }

    private static void testRouteHealth() {
        RouteHealthTracker tracker = new RouteHealthTracker("r", "Route", RouteHealthTracker.Policy.DEFAULT, NOW);
        check(tracker.due(NOW), "A new route is checked at once");
        equal(RouteHealthTracker.Action.NONE, tracker.record(true, 40, "", NOW));
        equal(RouteHealthTracker.State.HEALTHY, tracker.state());
        equal(NOW + 60_000L, tracker.nextCheckAt());

        long t = NOW + 60_000L;
        long[] expected = {2_000, 4_000, 8_000, 16_000, 32_000};
        List<RouteHealthTracker.Action> actions = new ArrayList<>();
        for (int attempt = 1; attempt <= 5; attempt++) {
            actions.add(tracker.record(false, -1, "handshake timeout", t));
            equal(RouteHealthTracker.State.RECOVERING, tracker.state());
            equal(attempt, tracker.snapshot().attempt());
            equal(t + expected[attempt - 1], tracker.nextCheckAt());
            t = tracker.nextCheckAt();
        }
        equal(RouteHealthTracker.Action.REFRESH_ROUTE, actions.get(1));
        equal(1L, actions.stream().filter(a -> a == RouteHealthTracker.Action.REFRESH_ROUTE).count());
        equal(RouteHealthTracker.Action.EXHAUSTED, tracker.record(false, -1, "handshake timeout", t));
        equal(RouteHealthTracker.State.FAILED, tracker.state());
        equal(t + 120_000L, tracker.nextCheckAt());
        long failedAt = tracker.nextCheckAt();
        equal(RouteHealthTracker.Action.NONE, tracker.record(false, -1, "still down", failedAt));
        equal(failedAt + 120_000L, tracker.nextCheckAt());
        check(tracker.snapshot().affected(), "A failed route is affected");

        tracker.retryNow(failedAt + 1_000);
        check(tracker.due(failedAt + 1_000), "Retry now makes the route due immediately");
        equal(RouteHealthTracker.State.RECOVERING, tracker.state());
        equal(RouteHealthTracker.Action.RECOVERED, tracker.record(true, 55, "", failedAt + 2_000));
        equal(RouteHealthTracker.State.HEALTHY, tracker.state());
        equal(55L, tracker.snapshot().lastLatencyMillis());
        check(tracker.snapshot().events().size() <= 12, "Events are bounded");
        check(tracker.snapshot().events().stream().anyMatch(e -> e.type() == RouteHealthTracker.EventType.RECOVERED), "Recovery is in the timeline");
        check(tracker.snapshot().events().stream().anyMatch(e -> e.title().equals("Failure detected")), "The failure is in the timeline");
    }

    private static final class FakeSocks implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        final List<Integer> clientPorts = new CopyOnWriteArrayList<>();
        final List<Socket> sockets = new CopyOnWriteArrayList<>();
        volatile boolean closed;

        FakeSocks() throws IOException {
            Thread.ofVirtual().start(() -> {
                while (!closed) {
                    try { Socket socket = server.accept(); sockets.add(socket); Thread.ofVirtual().start(() -> handle(socket)); }
                    catch (IOException e) { return; }
                }
            });
        }

        int port() { return server.getLocalPort(); }

        private void handle(Socket socket) {
            try (socket) {
                clientPorts.add(socket.getPort());
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                int version = in.read();
                int count = in.read();
                in.readNBytes(count);
                if (version != 5) return;
                out.write(new byte[]{5, 0});
                byte[] head = in.readNBytes(4);
                int port;
                switch (head[3]) {
                    case 1 -> in.readNBytes(4);
                    case 3 -> in.readNBytes(in.read());
                    case 4 -> in.readNBytes(16);
                    default -> { return; }
                }
                byte[] portBytes = in.readNBytes(2);
                port = ((portBytes[0] & 0xff) << 8) | (portBytes[1] & 0xff);
                if (head[1] == 3) { udp(socket, in, out); return; }
                if (port == 1) { out.write(new byte[]{5, 5, 0, 1, 0, 0, 0, 0, 0, 0}); return; }
                try (Socket target = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
                    Thread copy = Thread.ofVirtual().start(() -> {
                        try { target.getInputStream().transferTo(out); socket.shutdownOutput(); } catch (IOException ignored) { }
                    });
                    try { in.transferTo(target.getOutputStream()); target.shutdownOutput(); } catch (IOException ignored) { }
                    copy.join();
                }
            } catch (Exception ignored) { }
        }

        private void udp(Socket control, InputStream in, OutputStream out) throws IOException {
            try (DatagramSocket relay = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
                int p = relay.getLocalPort();
                out.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, (byte) (p >>> 8), (byte) p});
                Thread echo = Thread.ofVirtual().start(() -> {
                    byte[] buffer = new byte[65_535];
                    try {
                        while (true) {
                            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                            relay.receive(packet);
                            relay.send(new DatagramPacket(buffer, packet.getLength(), packet.getSocketAddress()));
                        }
                    } catch (IOException ignored) { }
                });
                while (in.read() >= 0) { }
                relay.close();
                try { echo.join(); } catch (InterruptedException ignored) { }
            }
        }

        @Override public void close() throws IOException {
            closed = true;
            server.close();
            for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private static final class Target implements AutoCloseable {
        enum Kind { SOURCE, ECHO, SINK }
        final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        final AtomicLong received = new AtomicLong();

        Target(Kind kind, long bytes) throws IOException {
            Thread.ofVirtual().start(() -> {
                while (true) {
                    Socket socket;
                    try { socket = server.accept(); } catch (IOException e) { return; }
                    Thread.ofVirtual().start(() -> {
                        try (socket) {
                            switch (kind) {
                                case SOURCE -> {
                                    byte[] chunk = new byte[16 * 1024];
                                    long left = bytes;
                                    while (left > 0) { int n = (int) Math.min(chunk.length, left); socket.getOutputStream().write(chunk, 0, n); left -= n; }
                                    socket.shutdownOutput();
                                    socket.getInputStream().transferTo(OutputStream.nullOutputStream());
                                }
                                case ECHO -> socket.getInputStream().transferTo(socket.getOutputStream());
                                case SINK -> {
                                    byte[] buffer = new byte[16 * 1024];
                                    int n;
                                    while ((n = socket.getInputStream().read(buffer)) >= 0) received.addAndGet(n);
                                }
                            }
                        } catch (IOException ignored) { }
                    });
                }
            });
        }

        int port() { return server.getLocalPort(); }
        @Override public void close() throws IOException { server.close(); }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); }
    }

    private static Socket connect(int gatewayPort, String host, int targetPort) throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), gatewayPort);
        socket.setSoTimeout(15_000);
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(new byte[]{5, 1, 0});
        byte[] method = in.readNBytes(2);
        if (method[1] != 0) throw new IOException("method refused");
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        byte[] name = host.getBytes(StandardCharsets.US_ASCII);
        request.writeBytes(new byte[]{5, 1, 0, 3, (byte) name.length});
        request.writeBytes(name);
        request.write(targetPort >>> 8);
        request.write(targetPort);
        out.write(request.toByteArray());
        byte[] reply = in.readNBytes(10);
        if (reply.length < 2 || reply[1] != 0) throw new IOException("SOCKS reply " + (reply.length > 1 ? reply[1] : -1));
        return socket;
    }

    private static void testGatewayTcp() throws Exception {
        try (FakeSocks socks = new FakeSocks(); Target echo = new Target(Target.Kind.ECHO, 0)) {
            List<GatewayConnection> opened = new CopyOnWriteArrayList<>();
            AppGateway gateway = new AppGateway(opened::add, line -> { });
            int port = freePort();
            String app = "C:\\Apps\\Chat.exe";
            gateway.start(List.of(new AppGateway.Route(app, 0, port, socks.port())));
            try {
                check(gateway.carries(app.toLowerCase()), "The gateway carries the app");
                try (Socket socket = connect(port, "chat.example", echo.port())) {
                    byte[] message = "hello through the gateway".getBytes(StandardCharsets.UTF_8);
                    socket.getOutputStream().write(message);
                    socket.shutdownOutput();
                    equal(new String(message, StandardCharsets.UTF_8), new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                }
                waitFor(() -> gateway.counters(app).openConnections() == 0);
                AppGateway.Counters counters = gateway.counters(app);
                equal(25L, counters.downloadBytes());
                equal(25L, counters.uploadBytes());
                equal(1, opened.size());
                GatewayConnection connection = opened.get(0);
                equal("chat.example", connection.destinationHost());
                equal(echo.port(), connection.destinationPort());
                equal(GatewayConnection.Protocol.TCP, connection.protocol());
                check(connection.finished(), "The connection is closed");
                check(socks.clientPorts.contains(connection.upstreamLocalPort()), "The upstream port is the one Xray sees");
            } finally {
                gateway.stop();
            }
        }
    }

    private static void testGatewayLimits() throws Exception {
        long size = 2L * 1024 * 1024;
        try (FakeSocks socks = new FakeSocks(); Target source = new Target(Target.Kind.SOURCE, size); Target sink = new Target(Target.Kind.SINK, 0)) {
            AppGateway gateway = new AppGateway(c -> { }, line -> { });
            int port = freePort();
            String app = "C:\\Apps\\Video.exe";
            long downLimit = 2L * 1024 * 1024;
            gateway.setLimits(app, downLimit, 0);
            gateway.start(List.of(new AppGateway.Route(app, 0, port, socks.port())));
            try {
                long started = System.nanoTime();
                List<Thread> readers = new ArrayList<>();
                AtomicLong total = new AtomicLong();
                for (int i = 0; i < 3; i++) {
                    readers.add(Thread.ofVirtual().start(() -> {
                        try (Socket socket = connect(port, "video.example", source.port())) {
                            byte[] buffer = new byte[32 * 1024];
                            int n;
                            while ((n = socket.getInputStream().read(buffer)) >= 0) total.addAndGet(n);
                        } catch (IOException e) { throw new RuntimeException(e); }
                    }));
                }
                for (Thread reader : readers) reader.join();
                double seconds = (System.nanoTime() - started) / 1e9;
                equal(3 * size, total.get());
                double rate = total.get() / seconds;
                System.out.printf("    download: %.2f MB in %.2f s = %.2f MB/s (limit 2.00 MB/s, 3 connections)%n", total.get() / 1048576.0, seconds, rate / 1048576.0);
                check(rate <= downLimit * 1.15, "The three connections together must not exceed the app's limit: " + rate);
                check(rate >= downLimit * 0.70, "The limit must not throttle far below the configured rate: " + rate);

                gateway.setLimits(app, 0, 1024L * 1024);
                long uploadStarted = System.nanoTime();
                List<Thread> writers = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    writers.add(Thread.ofVirtual().start(() -> {
                        try (Socket socket = connect(port, "upload.example", sink.port())) {
                            byte[] chunk = new byte[16 * 1024];
                            for (int sent = 0; sent < 1024 * 1024; sent += chunk.length) socket.getOutputStream().write(chunk);
                            socket.shutdownOutput();
                            socket.getInputStream().readAllBytes();
                        } catch (IOException e) { throw new RuntimeException(e); }
                    }));
                }
                for (Thread writer : writers) writer.join();
                waitFor(() -> sink.received.get() == 2L * 1024 * 1024);
                double uploadSeconds = (System.nanoTime() - uploadStarted) / 1e9;
                double uploadRate = sink.received.get() / uploadSeconds;
                System.out.printf("    upload: %.2f MB in %.2f s = %.2f MB/s (limit 1.00 MB/s, 2 connections)%n", sink.received.get() / 1048576.0, uploadSeconds, uploadRate / 1048576.0);
                check(uploadRate <= 1024 * 1024 * 1.15, "Upload must respect the app's limit: " + uploadRate);
                check(uploadRate >= 1024 * 1024 * 0.70, "Upload must not be throttled far below the limit: " + uploadRate);

                gateway.setLimits(app, 0, 0);
                long fast = System.nanoTime();
                try (Socket socket = connect(port, "video.example", source.port())) { socket.getInputStream().readAllBytes(); }
                double unlimited = size / ((System.nanoTime() - fast) / 1e9);
                System.out.printf("    unlimited: %.1f MB/s%n", unlimited / 1048576.0);
                check(unlimited > downLimit * 3, "Removing the limit must remove the throttling: " + unlimited);
            } finally {
                gateway.stop();
            }
        }
    }

    private static void testGatewayUdp() throws Exception {
        try (FakeSocks socks = new FakeSocks()) {
            List<GatewayConnection> opened = new CopyOnWriteArrayList<>();
            AppGateway gateway = new AppGateway(opened::add, line -> { });
            int port = freePort();
            String app = "C:\\Apps\\Voice.exe";
            gateway.setLimits(app, 0, 160 * 1024);
            gateway.start(List.of(new AppGateway.Route(app, 0, port, socks.port())));
            try (Socket control = new Socket(InetAddress.getLoopbackAddress(), port);
                 DatagramSocket udp = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
                control.setSoTimeout(5_000);
                udp.setSoTimeout(5_000);
                control.getOutputStream().write(new byte[]{5, 1, 0});
                control.getInputStream().readNBytes(2);
                control.getOutputStream().write(new byte[]{5, 3, 0, 1, 0, 0, 0, 0, 0, 0});
                byte[] reply = control.getInputStream().readNBytes(10);
                equal((byte) 0, reply[1]);
                int relayPort = ((reply[8] & 0xff) << 8) | (reply[9] & 0xff);
                check(relayPort != socks.port(), "The application must be given the gateway's own UDP port");
                byte[] header = {0, 0, 0, 1, 9, 9, 9, 9, 0, 53};
                byte[] datagram = new byte[header.length + 8 * 1024];
                System.arraycopy(header, 0, datagram, 0, header.length);
                long started = System.nanoTime();
                int rounds = 40;
                for (int i = 0; i < rounds; i++) {
                    udp.send(new DatagramPacket(datagram, datagram.length, InetAddress.getLoopbackAddress(), relayPort));
                    DatagramPacket echo = new DatagramPacket(new byte[65_535], 65_535);
                    udp.receive(echo);
                    equal(datagram.length, echo.getLength());
                }
                double seconds = (System.nanoTime() - started) / 1e9;
                double rate = rounds * 8.0 * 1024 / seconds;
                System.out.printf("    udp upload: %d KB in %.2f s = %.0f KB/s (limit 160 KB/s)%n", rounds * 8, seconds, rate / 1024);
                check(rate <= 160 * 1024 * 1.2, "UDP must respect the app's upload limit: " + rate);
                check(rate >= 160 * 1024 * 0.6, "UDP must not be throttled far below the limit: " + rate);
                AppGateway.Counters counters = gateway.counters(app);
                equal((long) rounds * 8 * 1024, counters.uploadBytes());
                equal((long) rounds * 8 * 1024, counters.downloadBytes());
                GatewayConnection association = opened.get(0);
                equal(GatewayConnection.Protocol.UDP, association.protocol());
                equal("9.9.9.9", association.destinationHost());
                equal(53, association.destinationPort());
                equal(1, association.udpDestinations());
                check(association.udpLocalPort() > 0, "The UDP source port towards Xray is known");
            } finally {
                gateway.stop();
            }
        }
    }

    private static void testGatewayFailures() throws Exception {
        try (FakeSocks socks = new FakeSocks()) {
            List<GatewayConnection> opened = new CopyOnWriteArrayList<>();
            List<String> logs = new CopyOnWriteArrayList<>();
            AppGateway gateway = new AppGateway(opened::add, logs::add);
            int port = freePort();
            gateway.start(List.of(new AppGateway.Route("C:\\A.exe", 0, port, socks.port())));
            try {
                try { connect(port, "refused.example", 1); throw new AssertionError("A refused connection must fail"); }
                catch (IOException expected) { check(expected.getMessage().contains("5"), expected.getMessage()); }
                waitFor(() -> !opened.isEmpty() && opened.get(0).finished());
                equal(GatewayConnection.Status.FAILED, opened.get(0).status());
                check(opened.get(0).failure().contains("connection refused"), opened.get(0).failure());

                try (ServerSocket busy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                    int first = freePort();
                    AppGateway other = new AppGateway(c -> { }, line -> { });
                    try {
                        other.start(List.of(new AppGateway.Route("C:\\B.exe", 0, first, socks.port()),
                                new AppGateway.Route("C:\\C.exe", 1, busy.getLocalPort(), socks.port())));
                        throw new AssertionError("A port conflict must fail the start");
                    } catch (IOException expected) {
                        check(expected.getMessage().contains("C.exe"), "The error must name the application: " + expected.getMessage());
                    }
                    check(!other.running(), "A failed start leaves the gateway stopped");
                    for (int attempt = 1; ; attempt++) {
                        try (ServerSocket again = new ServerSocket()) {
                            again.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), first));
                            break;
                        } catch (java.net.BindException busyNow) {
                            if (attempt >= 5) throw busyNow;
                            Thread.sleep(200);
                        }
                    }
                }

                Socket held = connect(port, "held.example", freePortWithEcho(socks));
                gateway.stop();
                check(!gateway.running(), "Stopped");
                held.setSoTimeout(3_000);
                int read;
                try { read = held.getInputStream().read(); } catch (IOException closed) { read = -1; }
                equal(-1, read);
                held.close();
                try (ServerSocket reuse = new ServerSocket()) {
                    reuse.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                }
            } finally {
                gateway.stop();
            }
        }
    }

    private static final List<Target> KEPT = new CopyOnWriteArrayList<>();

    private static int freePortWithEcho(FakeSocks ignored) throws IOException {
        Target echo = new Target(Target.Kind.ECHO, 0);
        KEPT.add(echo);
        return echo.port();
    }

    private static void testLogParser() {
        var access = (XrayLogParser.Access) XrayLogParser.parse("2026/10/14 13:22:17.818097 from tcp:127.0.0.1:57338 accepted tcp:104.20.23.154:80 [perroute-app-0 -> slot]");
        equal(57338, access.sourcePort());
        equal("104.20.23.154", access.destinationHost());
        equal(80, access.destinationPort());
        equal("perroute-app-0", access.inbound());
        equal("slot", access.outbound());
        var older = (XrayLogParser.Access) XrayLogParser.parse("2024/01/01 00:00:00 from 127.0.0.1:5000 accepted udp:[2001:db8::1]:53 [perroute-app-2 >> direct]");
        equal("2001:db8::1", older.destinationHost());
        equal("direct", older.outbound());
        var hit = (XrayLogParser.RuleHit) XrayLogParser.parse("2026/10/14 13:22:17.818097 [Info] [3901296262] app/dispatcher: Hit route rule: [perroute-custom-abc#route1] so taking detour [perroute-route-1] for [tcp:example.com:80]");
        equal("3901296262", hit.session());
        equal("perroute-custom-abc#route1", hit.ruleTag());
        equal("perroute-route-1", hit.outbound());
        var plain = (XrayLogParser.RuleHit) XrayLogParser.parse("2026/10/14 13:22:18.087653 [Info] [3885485071] app/dispatcher: taking detour [api] for [tcp:127.0.0.1:27900]");
        equal("", plain.ruleTag());
        var sniffed = (XrayLogParser.Sniffed) XrayLogParser.parse("2026/10/14 13:22:17.818097 [Info] [3901296262] app/dispatcher: sniffed domain: example.com");
        equal("example.com", sniffed.domain());
        var request = (XrayLogParser.Request) XrayLogParser.parse("2026/10/14 13:22:17.817593 [Info] [3901296262] proxy/socks: TCP Connect request to tcp:104.20.23.154:80");
        equal("104.20.23.154", request.destinationHost());
        var failure = (XrayLogParser.Failure) XrayLogParser.parse("2026/10/14 13:22:19 [Warning] [3901296262] app/proxyman/outbound: failed to process outbound traffic > proxy/vless/outbound: failed to find an available destination > dial tcp: i/o timeout");
        equal("dial tcp: i/o timeout", failure.detail());
        equal(null, XrayLogParser.parse("Xray 26.3.27 started"));
    }

    private static void testInspectorJoin() throws Exception {
        try (FakeSocks socks = new FakeSocks(); Target echo = new Target(Target.Kind.ECHO, 0)) {
            ConnectionInspector inspector = new ConnectionInspector();
            inspector.gatewayActive(true);
            AppGateway gateway = new AppGateway(connection -> inspector.onGatewayConnection(connection, System.currentTimeMillis()), line -> { });
            int port = freePort();
            String app = "C:\\Apps\\Browser.exe";
            gateway.start(List.of(new AppGateway.Route(app, 0, port, socks.port())));
            try (Socket socket = connect(port, "203.0.113.7", echo.port())) {
                socket.getOutputStream().write(new byte[512]);
                socket.getInputStream().readNBytes(512);
                long now = System.currentTimeMillis();
                int upstream = socks.clientPorts.get(socks.clientPorts.size() - 1);
                inspector.onLogLine("2026/10/14 13:22:17 [Info] [77] proxy/socks: TCP Connect request to tcp:203.0.113.7:" + echo.port(), now);
                inspector.onLogLine("2026/10/14 13:22:17 [Info] [77] app/dispatcher: sniffed domain: news.example", now);
                inspector.onLogLine("2026/10/14 13:22:17 [Info] [77] app/dispatcher: Hit route rule: [perroute-custom-r1] so taking detour [direct] for [tcp:news.example:" + echo.port() + "]", now);
                inspector.onLogLine("2026/10/14 13:22:17 from tcp:127.0.0.1:" + upstream + " accepted tcp:203.0.113.7:" + echo.port() + " [perroute-app-0 -> direct]", now);
                waitFor(() -> inspector.snapshot(System.currentTimeMillis()).stream().anyMatch(o -> o.downloadBytes() == 512));
                ConnectionInspector.Observation observation = inspector.snapshot(System.currentTimeMillis()).get(0);
                equal(app, observation.applicationPath());
                equal("perroute-app-0", observation.inboundTag());
                equal("direct", observation.observedOutbound());
                equal("perroute-custom-r1", observation.observedRuleTag());
                check(observation.ruleObserved(), "The rule was observed in the core's log");
                equal("news.example", observation.sniffedDomain());
                equal("TCP", observation.protocol());
                equal("ACTIVE", observation.status());
                check(observation.viaGateway(), "Gateway connections carry byte counts");
                equal(512L, observation.uploadBytes());
            } finally {
                gateway.stop();
            }
            waitFor(() -> inspector.snapshot(System.currentTimeMillis()).get(0).status().equals("CLOSED"));
        }
    }

    private static void testInspectorBounds() {
        ConnectionInspector inspector = new ConnectionInspector();
        inspector.gatewayActive(false);
        long now = NOW;
        inspector.onLogLine("2026/10/14 13:22:17 from tcp:127.0.0.1:4000 accepted tcp:1.1.1.1:443 [perroute-app-3 -> block]", now);
        inspector.onLogLine("2026/10/14 13:22:17 from 127.0.0.1:4001 accepted tcp:127.0.0.1:17900 [perroute-api -> api]", now);
        List<ConnectionInspector.Observation> seen = inspector.snapshot(now);
        equal(1, seen.size());
        equal("SEEN", seen.get(0).status());
        equal("block", seen.get(0).observedOutbound());
        check(!seen.get(0).viaGateway(), "Core-only observations have no byte counts");
        equal(0, inspector.snapshot(now + 61_000).size());
        for (int i = 0; i < ConnectionInspector.MAX_RECORDS + 300; i++) {
            inspector.onLogLine("2026/10/14 13:22:17 from tcp:127.0.0.1:" + (5000 + i % 50000) + " accepted tcp:10.0.0." + (i % 250) + ":443 [perroute-app-0 -> proxy]", now);
        }
        check(inspector.size() <= ConnectionInspector.MAX_RECORDS, "The table stays bounded: " + inspector.size());
    }

    private static ProxyNode node(String key, String host) {
        return new ProxyNode(key, host, Protocol.VLESS, host, 443, "11111111-1111-1111-1111-111111111111", Map.of("security", "tls"), "");
    }

    private static void testBackupCrypto() throws Exception {
        byte[] payload = "{\"schema\":1,\"secret\":\"credential-123\"}".getBytes(StandardCharsets.UTF_8);
        char[] password = "correct horse".toCharArray();
        byte[] file = BackupCodec.encode(payload, 1, "PerRoute 3.0.0", NOW, password);
        String text = new String(file, StandardCharsets.UTF_8);
        check(!text.contains("credential-123"), "Encrypted backups must not contain plaintext secrets");
        check(text.contains("PBKDF2-HMAC-SHA256") && text.contains("AES-256-GCM"), "The method is recorded");
        BackupCodec.Header header = BackupCodec.header(file);
        check(header.encrypted(), "Header says encrypted");
        equal(NOW, header.createdAt());
        equal(new String(payload, StandardCharsets.UTF_8), new String(BackupCodec.decode(file, password), StandardCharsets.UTF_8));
        expectFailure(() -> BackupCodec.decode(file, "wrong password".toCharArray()), BackupCodec.BackupException.Reason.WRONG_PASSWORD_OR_TAMPERED);
        expectFailure(() -> BackupCodec.decode(file, null), BackupCodec.BackupException.Reason.PASSWORD_REQUIRED);
        String tamperedText = text.replaceFirst("\"createdAt\":\\s*" + NOW, "\"createdAt\": " + (NOW + 1));
        check(!tamperedText.equals(text), "The tampering must change the file");
        byte[] tamperedHeader = tamperedText.getBytes(StandardCharsets.UTF_8);
        expectFailure(() -> BackupCodec.decode(tamperedHeader, password), BackupCodec.BackupException.Reason.WRONG_PASSWORD_OR_TAMPERED);
        try { BackupCodec.encode(payload, 1, "x", NOW, "short".toCharArray()); throw new AssertionError("Short passwords are refused"); }
        catch (IllegalArgumentException expected) { }

        byte[] open = BackupCodec.encode(payload, 1, "PerRoute 3.0.0", NOW, null);
        equal(new String(payload, StandardCharsets.UTF_8), new String(BackupCodec.decode(open, null), StandardCharsets.UTF_8));
        String openText = new String(open, StandardCharsets.UTF_8);
        java.util.regex.Matcher payloadStart = java.util.regex.Pattern.compile("\"payload\":\\s*\"(.)").matcher(openText);
        check(payloadStart.find(), "The payload is in the file");
        String damaged = openText.substring(0, payloadStart.start(1)) + (payloadStart.group(1).equals("A") ? "B" : "A") + openText.substring(payloadStart.end(1));
        expectFailure(() -> BackupCodec.decode(damaged.getBytes(StandardCharsets.UTF_8), null), BackupCodec.BackupException.Reason.DAMAGED);
        expectFailure(() -> BackupCodec.decode("{\"hello\":1}".getBytes(StandardCharsets.UTF_8), null), BackupCodec.BackupException.Reason.NOT_A_BACKUP);
        String futureText = openText.replaceFirst("\"version\":\\s*1", "\"version\": 9");
        check(!futureText.equals(openText), "The version must change");
        byte[] future = futureText.getBytes(StandardCharsets.UTF_8);
        expectFailure(() -> BackupCodec.decode(future, null), BackupCodec.BackupException.Reason.NEWER_FORMAT);
    }

    private static void testBackupRestorePlan() throws Exception {
        Path directory = Files.createTempDirectory("perroute-backup-");
        try {
            String chrome = RegressionFixtures.identity(Files.writeString(directory.resolve("chrome.exe"), "x"));
            String telegram = RegressionFixtures.identity(Files.writeString(directory.resolve("Telegram.exe"), "x"));
            String elsewhereTelegram = RegressionFixtures.identity(Files.writeString(Files.createDirectories(directory.resolve("other")).resolve("Telegram.exe"), "x"));
            String sub = "https://sub.example/list";

            AppState source = new AppState();
            source.nodes().addAll(List.of(node("s1", "one.example"), node("s2", "two.example"), node("m1", "manual.example")));
            source.nodeSources().putAll(Map.of("s1", sub, "s2", sub, "m1", BackupService.MANUAL_SOURCE));
            source.subscriptionUrls().add(sub);
            source.favoriteNodeKeys().add("s2");
            source.nodeTags().put("s1", "Work");
            source.setSelectedNodeKey("s2");
            source.appPaths().addAll(List.of(chrome, telegram));
            source.setAdvancedRouting(true);
            source.appRoutes().put(AppRoutes.normalize(chrome), "s1");
            source.autoApps().add(AppRoutes.normalize(telegram));
            source.autoPools().put(AppRoutes.normalize(telegram), "favorites");
            source.bandwidthPolicies().put(AppRoutes.normalize(telegram), new BandwidthPolicy(10_000_000, 5_000_000));
            source.routeRules().add(new RouteRule("r1", "Work", telegram, RouteRule.MatchType.DOMAIN, "work.example", RouteRule.Action.PROXY, true));
            source.profiles().add(new ConnectionProfile("p1", "Office", List.of(chrome), "s1", ConnectionMode.NATIVE_PER_APP, List.of(), true,
                    Map.of(chrome, "s1"), Set.of(telegram)));
            source.setConnectionMode(ConnectionMode.NATIVE_PER_APP);

            Map<String, String> prefs = Map.of("darkTheme", "false", "perAppKillSwitch", "true");
            byte[] file = BackupService.export(source, prefs, EnumSet.allOf(BackupService.Section.class), "a good password".toCharArray(), "PerRoute 3.0.0", NOW);

            java.util.function.Predicate<String> exists = path -> !path.equalsIgnoreCase(telegram) && BackupService.executableExists(path);
            AppState current = new AppState();
            current.nodes().addAll(List.of(node("local-m", "manual.example"), node("local-x", "x.example")));
            current.nodeSources().putAll(Map.of("local-m", BackupService.MANUAL_SOURCE, "local-x", BackupService.MANUAL_SOURCE));
            current.setSelectedNodeKey("local-x");
            current.profiles().add(new ConnectionProfile("p9", "office", List.of(), "", ConnectionMode.SYSTEM_PROXY, List.of()));

            expectFailure(() -> BackupService.preview(file, "nope nope".toCharArray(), current, exists, List.of()), BackupCodec.BackupException.Reason.WRONG_PASSWORD_OR_TAMPERED);
            BackupService.RestorePreview preview = BackupService.preview(file, "a good password".toCharArray(), current, exists, List.of(elsewhereTelegram));
            equal(BackupService.Compatibility.COMPATIBLE, preview.compatibility());
            equal(EnumSet.allOf(BackupService.Section.class), preview.sections());
            equal(1, preview.counts().subscriptions());
            equal(3, preview.counts().servers());
            equal(2, preview.counts().applications());
            equal(1, preview.counts().rules());
            equal(1, preview.counts().profiles());
            equal(1, preview.serversAlreadyPresent());
            equal(1, preview.profilesWithSameName());
            equal(1, preview.missingPaths().size());
            equal(telegram, preview.missingPaths().get(0).originalPath());
            equal(elsewhereTelegram, preview.missingPaths().get(0).suggestion());

            AppStateStore codec = new AppStateStore(directory.resolve("unused.bin"));
            AppState mergeCopy = codec.parse(codec.serialize(current));
            BackupService.Plan merge = BackupService.plan(preview, mergeCopy, Map.of("darkTheme", "true"), BackupService.Mode.MERGE,
                    Map.of(telegram, elsewhereTelegram), exists);
            AppState merged = merge.state();
            equal(4, merged.nodes().size());
            check(merged.nodes().stream().anyMatch(n -> n.key().equals("local-m")), "Merge keeps the existing copy of an identical server");
            equal("local-x", merged.selectedNodeKey());
            equal(List.of(chrome, elsewhereTelegram), merged.appPaths());
            equal("s1", merged.appRoutes().get(AppRoutes.normalize(chrome)));
            check(merged.autoApps().contains(AppRoutes.normalize(elsewhereTelegram)), "Auto mode follows the remapped path");
            equal("favorites", merged.autoPools().get(AppRoutes.normalize(elsewhereTelegram)));
            equal(new BandwidthPolicy(10_000_000, 5_000_000), merged.bandwidthPolicies().get(AppRoutes.normalize(elsewhereTelegram)));
            equal(elsewhereTelegram, merged.routeRules().get(0).applicationPath());
            equal(1, merged.profiles().size());
            equal("true", merge.preferences().get("darkTheme"));
            check(merged.favoriteNodeKeys().contains("s2") && "Work".equals(merged.nodeTags().get("s1")), "Server metadata travels with new servers");
            equal(0, merge.skippedApplications());

            AppState replaceCopy = codec.parse(codec.serialize(current));
            BackupService.Plan replace = BackupService.plan(preview, replaceCopy, Map.of(), BackupService.Mode.REPLACE, Map.of(), exists);
            AppState replaced = replace.state();
            equal(3, replaced.nodes().size());
            check(replaced.nodes().stream().noneMatch(n -> n.key().equals("local-x")), "Replace removes servers of the replaced sections");
            equal("s2", replaced.selectedNodeKey());
            equal(List.of(chrome), replaced.appPaths());
            check(replaced.autoApps().isEmpty(), "A skipped app keeps nothing");
            equal(1, replace.skippedApplications());
            equal(0, replaced.routeRules().size());
            equal("p1", replaced.profiles().get(0).id());
            equal("false", replace.preferences().get("darkTheme"));
            equal(ConnectionMode.NATIVE_PER_APP, replace.connectionMode());
            check(replace.summary().stream().anyMatch(s -> s.contains("Skipped 1")), replace.summary().toString());

            byte[] newer = new String(BackupCodec.encode("{}".getBytes(StandardCharsets.UTF_8), 7, "PerRoute 9", NOW, null), StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
            expectFailure(() -> BackupService.preview(newer, null, current, exists, List.of()), BackupCodec.BackupException.Reason.NEWER_FORMAT);
        } finally {
            deleteTree(directory);
        }
    }

    private static void testStateMigration() throws Exception {
        Path directory = Files.createTempDirectory("perroute-state3-");
        try {
            AppStateStore store = new AppStateStore(directory.resolve("state.bin"));
            String application = RegressionFixtures.windows() ? "C:\\Apps\\A.exe"
                    : RegressionFixtures.identity(directory.resolve("A"));
            Properties v2 = new Properties();
            v2.setProperty("node.count", "0");
            v2.setProperty("app.count", "1");
            v2.setProperty("app.0.path", application);
            v2.setProperty("advanced.enabled", "true");
            v2.setProperty("route.count", "1");
            v2.setProperty("route.0.app", application);
            v2.setProperty("route.0.node", "n1");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            v2.store(bytes, "v2");
            AppState migrated = store.parse(bytes.toByteArray());
            check(migrated.migratedFromV2(), "A state without a version comes from version 2");
            check(migrated.autoApps().isEmpty() && migrated.bandwidthPolicies().isEmpty(), "Nothing is automatic after migration");
            equal("n1", migrated.appRoutes().get(AppRoutes.normalize(application)));
            check(!new AppState().migratedFromV2(), "A fresh installation is not a migration");

            migrated.autoApps().add(AppRoutes.normalize(application));
            migrated.autoPools().put(AppRoutes.normalize(application), "tag:Work");
            migrated.autoSelections().put(AppRoutes.normalize(application), new AutoSelection("n2", NOW));
            migrated.bandwidthPolicies().put(AppRoutes.normalize(application), new BandwidthPolicy(8_000_000, 0));
            migrated.profiles().add(new ConnectionProfile("p", "P", List.of(application), "n1", ConnectionMode.NATIVE_PER_APP,
                    List.of(), true, Map.of(), Set.of(application)));
            store.save(migrated);
            AppState reloaded = store.load();
            check(reloaded.migratedFromV2(), "The migration flag survives saving");
            equal(Set.of(AppRoutes.normalize(application)), reloaded.autoApps());
            equal("tag:Work", reloaded.autoPools().get(AppRoutes.normalize(application)));
            equal(new AutoSelection("n2", NOW), reloaded.autoSelections().get(AppRoutes.normalize(application)));
            equal(new BandwidthPolicy(8_000_000, 0), reloaded.bandwidthPolicies().get(AppRoutes.normalize(application)));
            equal(Set.of(AppRoutes.normalize(application)), reloaded.profiles().get(0).autoApps());
            equal(String.valueOf(AppStateStore.STATE_VERSION), store.loadProperties().getProperty("state.version"));

            equal(BandwidthPolicy.MIN_BITS_PER_SECOND, new BandwidthPolicy(10, 0).downloadBitsPerSecond());
            equal(BandwidthPolicy.UNLIMITED, BandwidthPolicy.decode("garbage"));
            equal(new BandwidthPolicy(1_000_000, 2_000_000), BandwidthPolicy.decode(new BandwidthPolicy(1_000_000, 2_000_000).encode()));
            AppState copy = new AppState();
            copy.replaceWith(reloaded);
            equal(reloaded.autoSelections(), copy.autoSelections());
        } finally {
            deleteTree(directory);
        }
    }

    @FunctionalInterface private interface Failing { void run() throws Exception; }

    private static void expectFailure(Failing action, BackupCodec.BackupException.Reason reason) throws Exception {
        try { action.run(); }
        catch (BackupCodec.BackupException expected) { equal(reason, expected.reason()); return; }
        throw new AssertionError("Expected a " + reason + " failure");
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        check(condition.getAsBoolean(), "Timed out waiting for a condition");
    }

    static void deleteTree(Path directory) throws IOException {
        for (int attempt = 1; ; attempt++) {
            if (!Files.exists(directory)) return;
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
                return;
            } catch (IOException busy) {
                if (attempt >= 8) throw busy;
                try { Thread.sleep(75L * attempt); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw busy; }
            }
        }
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
