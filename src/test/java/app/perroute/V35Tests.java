package app.perroute;

import app.perroute.compose.controller.ActionResult;
import app.perroute.compose.CheckStatus;
import app.perroute.compose.CoreLogHints;
import app.perroute.compose.HealthFinding;
import app.perroute.compose.HealthReport;
import app.perroute.compose.controller.PerRouteController;
import app.perroute.compose.controller.RecipeDraft;
import app.perroute.compose.Repair;
import app.perroute.compose.RuleAnalysis;
import app.perroute.compose.controller.TemporaryRoute;
import app.perroute.gaming.GamingProbe;
import app.perroute.gaming.GamingScore;
import app.perroute.localnet.LocalNetwork;
import app.perroute.localnet.LocalNetworkPolicy;
import app.perroute.localnet.LocalNetworkScanner;
import app.perroute.model.AppRoutes;
import app.perroute.model.AppState;
import app.perroute.model.ConnectionMode;
import app.perroute.model.Protocol;
import app.perroute.model.ProxyNode;
import app.perroute.model.RouteRule;
import app.perroute.netstate.NetworkHistoryStore;
import app.perroute.netstate.NetworkRestorePlan;
import app.perroute.netstate.NetworkSnapshot;
import app.perroute.netstate.NetworkStateDiff;
import app.perroute.netstate.NetworkStateKeys;
import app.perroute.persistence.AppStateStore;
import app.perroute.recipe.Recipe;
import app.perroute.recipe.RecipeCodec;
import app.perroute.recipe.RecipePlanner;
import app.perroute.service.RoutePlanner;
import app.perroute.service.WindowsSystemProxyManager;
import app.perroute.service.XrayConfigGenerator;
import app.perroute.shadow.ShadowExperiment;
import app.perroute.subscription.SubscriptionChange;
import app.perroute.subscription.SubscriptionHealth;
import app.perroute.subscription.SubscriptionInsight;
import app.perroute.subscription.SubscriptionInsightStore;
import app.perroute.subscription.SubscriptionUserInfo;
import app.perroute.update.StartupHealth;
import app.perroute.util.Json;
import app.perroute.workspace.Workspace;
import app.perroute.workspace.WorkspaceBrowsers;
import app.perroute.workspace.WorkspaceStore;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

final class V35Tests {
    private static int assertions;
    private V35Tests() {}

    static int assertions() { return assertions; }

    static void runAll() throws Exception {
        run("3.5: snapshots compare and say who changed what", V35Tests::testSnapshotDiff);
        run("3.5: restore plans undo only PerRoute's changes", V35Tests::testRestorePlan);
        run("3.5: snapshot history keeps a baseline", V35Tests::testHistoryStore);
        run("3.5: startup health counts failed starts", V35Tests::testStartupHealth);
        run("3.5: subscription usage, expiry and changes", V35Tests::testSubscriptionAnalysis);
        run("3.5: gaming score", V35Tests::testGamingScore);
        run("3.5: side-by-side comparison", V35Tests::testShadowExperiment);
        run("3.5: recipes round trip and refuse unsafe input", V35Tests::testRecipeCodec);
        run("3.5: recipe import plan", V35Tests::testRecipePlanner);
        run("3.5: browser workspaces are stored and launched safely", V35Tests::testWorkspaces);
        run("3.5: local network policy and rules", V35Tests::testLocalNetwork);
        run("3.5: temporary routes in the planner", V35Tests::testTemporaryPlanner);
        run("3.5: runtime layout plans", V35Tests::testRuntimePlan);
        run("3.5: rule analysis and core log hints", V35Tests::testAnalysis);
        withFixture("3.5 controller: temporary servers", V35Tests::testTemporaryRoutes);
        withFixture("3.5 controller: network restore", V35Tests::testNetworkRestore);
        withFixture("3.5 controller: recipes apply and undo", V35Tests::testRecipesController);
        withFixture("3.5 controller: doctor and safety center", V35Tests::testHealth);
        withFixture("3.5 controller: subscription intelligence", V35Tests::testSubscriptionController);
        withFixture("3.5 controller: local network trust", V35Tests::testLocalNetworkController);
        withFixture("3.5 controller: QUIC is blocked for routed apps unless turned off", V35Tests::testQuicBlocked);
        withFixture("3.5 controller: workspaces", V35Tests::testWorkspaceController);
    }

    private static void testSnapshotDiff() {
        NetworkStateKeys.setPerRouteServer(WindowsSystemProxyManager.perRouteProxyServer());
        String ours = WindowsSystemProxyManager.perRouteProxyServer();
        NetworkSnapshot before = snapshot(1, NetworkSnapshot.Reason.STARTUP, Map.of(
                NetworkStateKeys.PROXY_ENABLE, "0x0", NetworkStateKeys.PROXY_SERVER, "",
                NetworkStateKeys.DNS, "192.0.2.53", NetworkStateKeys.CORE_PROCESSES, "0"));
        NetworkSnapshot connected = snapshot(2, NetworkSnapshot.Reason.CONNECTED, Map.of(
                NetworkStateKeys.PROXY_ENABLE, "0x1", NetworkStateKeys.PROXY_SERVER, ours,
                NetworkStateKeys.DNS, "192.0.2.53", NetworkStateKeys.CORE_PROCESSES, "0"));
        var changes = NetworkStateDiff.compare(before, connected, false);
        equal(2, changes.size());
        check(changes.stream().allMatch(change -> change.kind() == NetworkStateDiff.Kind.BY_PERROUTE), "PerRoute's proxy is recognised as its own: " + changes);
        NetworkSnapshot other = snapshot(3, NetworkSnapshot.Reason.MANUAL, Map.of(
                NetworkStateKeys.PROXY_ENABLE, "0x1", NetworkStateKeys.PROXY_SERVER, "proxy.corp:8080",
                NetworkStateKeys.DNS, "192.0.2.53", NetworkStateKeys.CORE_PROCESSES, "0"));
        var afterOther = NetworkStateDiff.compare(connected, other, false);
        check(afterOther.stream().anyMatch(change -> change.key().id().equals(NetworkStateKeys.PROXY_SERVER) && change.kind() == NetworkStateDiff.Kind.ELSEWHERE),
                "A proxy set by another program is reported as such");
        var back = NetworkStateDiff.compare(connected, before, false);
        check(back.stream().allMatch(change -> change.kind() == NetworkStateDiff.Kind.PUT_BACK), "Undoing PerRoute's change is reported as put back");
        equal(4, NetworkStateDiff.compare(before, connected, true).size());
        equal("On", NetworkStateKeys.display(NetworkStateKeys.PROXY_ENABLE, "0x1"));
        equal("Not set", NetworkStateKeys.display(NetworkStateKeys.PROXY_SERVER, ""));
        NetworkSnapshot copy = NetworkSnapshot.fromJson(Json.parse(Json.stringify(connected.toJson())));
        equal(connected.values(), copy.values());
        equal(NetworkSnapshot.Reason.CONNECTED, copy.reason());
    }

    private static void testRestorePlan() {
        NetworkStateKeys.setPerRouteServer(WindowsSystemProxyManager.perRouteProxyServer());
        String ours = WindowsSystemProxyManager.perRouteProxyServer();
        check(NetworkRestorePlan.build(snapshot(1, NetworkSnapshot.Reason.MANUAL, Map.of()), null, true).blocked() != null, "A restore is refused while connected");

        NetworkSnapshot clean = snapshot(1, NetworkSnapshot.Reason.MANUAL, Map.of(NetworkStateKeys.PROXY_ENABLE, "0x0", NetworkStateKeys.PROXY_SERVER, "",
                NetworkStateKeys.PROXY_BACKUP, "no", NetworkStateKeys.RUN_ONCE, "no", NetworkStateKeys.CORE_PROCESSES, "0"));
        check(!NetworkRestorePlan.build(clean, null, false).needed(), "A clean machine needs nothing");

        Map<String, String> crashed = new LinkedHashMap<>();
        crashed.put(NetworkStateKeys.PROXY_ENABLE, "0x1");
        crashed.put(NetworkStateKeys.PROXY_SERVER, ours);
        crashed.put(NetworkStateKeys.PROXY_BACKUP, "yes");
        crashed.put(NetworkStateKeys.RUN_ONCE, "yes");
        crashed.put(NetworkStateKeys.ENGINE_SERVICE, NetworkStateKeys.RUNNING);
        crashed.put(NetworkStateKeys.ENGINE_CONFIG, NetworkStateKeys.CONFIG_PERROUTE);
        crashed.put(NetworkStateKeys.ENGINE_BACKUP, "yes");
        crashed.put(NetworkStateKeys.CORE_PROCESSES, "2");
        crashed.put(NetworkStateKeys.SESSION, "yes");
        var plan = NetworkRestorePlan.build(snapshot(2, NetworkSnapshot.Reason.MANUAL, crashed), null, false);
        var actions = plan.actions().stream().map(NetworkRestorePlan.Step::action).toList();
        equal(List.of(NetworkRestorePlan.Action.RESTORE_PROXY, NetworkRestorePlan.Action.REMOVE_RECOVERY_ENTRY, NetworkRestorePlan.Action.STOP_CORES,
                NetworkRestorePlan.Action.RESTORE_ENGINE, NetworkRestorePlan.Action.CLEAR_SESSION), actions);
        check(plan.actions().stream().filter(step -> step.action() == NetworkRestorePlan.Action.RESTORE_ENGINE).allMatch(step -> step.needsAdmin() && step.releasesApps()),
                "Restoring the engine needs Administrator and releases apps");

        Map<String, String> replaced = new LinkedHashMap<>(crashed);
        replaced.put(NetworkStateKeys.PROXY_SERVER, "proxy.corp:8080");
        var respectful = NetworkRestorePlan.build(snapshot(3, NetworkSnapshot.Reason.MANUAL, replaced), null, false);
        check(respectful.actions().stream().noneMatch(step -> step.action() == NetworkRestorePlan.Action.RESTORE_PROXY || step.action() == NetworkRestorePlan.Action.RESET_PROXY),
                "Another program's proxy is never overwritten");
        check(respectful.leftAlone().stream().anyMatch(step -> step.title().contains("Windows proxy")), "It says the proxy was left alone");
        check(respectful.actions().stream().anyMatch(step -> step.action() == NetworkRestorePlan.Action.DISCARD_PROXY_COPY),
                "PerRoute's outdated copy is dropped so it cannot overwrite the newer setting later");

        Map<String, String> lost = new LinkedHashMap<>(Map.of(NetworkStateKeys.PROXY_ENABLE, "0x1", NetworkStateKeys.PROXY_SERVER, ours, NetworkStateKeys.PROXY_BACKUP, "no"));
        var reset = NetworkRestorePlan.build(snapshot(4, NetworkSnapshot.Reason.MANUAL, lost), clean, false);
        equal(NetworkRestorePlan.Action.RESET_PROXY, reset.actions().get(0).action());
        var foreign = NetworkRestorePlan.build(snapshot(5, NetworkSnapshot.Reason.MANUAL, Map.of(NetworkStateKeys.ENGINE_CONFIG, NetworkStateKeys.CONFIG_OTHER,
                NetworkStateKeys.ENGINE_SERVICE, NetworkStateKeys.RUNNING)), null, false);
        check(foreign.actions().isEmpty() && !foreign.leftAlone().isEmpty(), "Another app list is not touched");
    }

    private static void testHistoryStore() throws Exception {
        Path directory = Files.createTempDirectory("perroute-history-");
        try {
            NetworkStateKeys.setPerRouteServer(WindowsSystemProxyManager.perRouteProxyServer());
            NetworkHistoryStore store = new NetworkHistoryStore(directory.resolve("history.json"));
            store.add(NetworkSnapshot.Reason.STARTUP, "3.5.0", Map.of(NetworkStateKeys.PROXY_SERVER, ""), 1_000);
            for (int i = 0; i < NetworkHistoryStore.LIMIT + 5; i++) {
                store.add(NetworkSnapshot.Reason.CONNECTED, "3.5.0", Map.of(NetworkStateKeys.PROXY_ENABLE, "0x1",
                        NetworkStateKeys.PROXY_SERVER, WindowsSystemProxyManager.perRouteProxyServer()), 2_000 + i);
            }
            equal(NetworkHistoryStore.LIMIT, store.all().size());
            check(store.baseline() != null && store.baseline().reason() == NetworkSnapshot.Reason.STARTUP, "The clean baseline is kept even when older than the limit");
            NetworkHistoryStore reloaded = new NetworkHistoryStore(directory.resolve("history.json"));
            equal(NetworkHistoryStore.LIMIT, reloaded.all().size());
            check(reloaded.all().stream().allMatch(snapshot -> snapshot.id() > 0), "Snapshots keep their ids");
        } finally { V3DomainTests.deleteTree(directory); }
    }

    private static void testStartupHealth() throws Exception {
        Path directory = Files.createTempDirectory("perroute-health-");
        try {
            StartupHealth health = new StartupHealth(directory.resolve("startup-health.json"));
            var first = health.beginStart("3.0.0", 100);
            equal(0, first.failedStarts());
            health.markHealthy(150);
            var update = health.beginStart("3.5.0", 200);
            equal("3.0.0", update.updatedFrom());
            equal(0, update.failedStarts());
            var second = health.beginStart("3.5.0", 300);
            equal(1, second.failedStarts());
            var third = health.beginStart("3.5.0", 400);
            equal(2, third.failedStarts());
            check(third.struggling(), "Two failed starts in a row offer a rollback");
            equal("3.0.0", third.updatedFrom());
            health.markHealthy(450);
            var fourth = health.beginStart("3.5.0", 500);
            equal(0, fourth.failedStarts());
            check(!fourth.struggling(), "A healthy start clears the count");
        } finally { V3DomainTests.deleteTree(directory); }
    }

    private static void testSubscriptionAnalysis() throws Exception {
        var info = SubscriptionUserInfo.parse("upload=1073741824; download=9663676416; total=10737418240; expire=4102444800");
        equal(10L * 1024 * 1024 * 1024, info.usedBytes());
        check(info.hasQuota() && info.hasExpiry() && info.usedShare() == 1.0, "Usage and expiry are read");
        equal(4_102_444_800_000L, info.expireAtMillis());
        check(!SubscriptionUserInfo.parse("").known() && !SubscriptionUserInfo.parse("garbage").known(), "Missing headers are unknown");
        equal(0L, SubscriptionUserInfo.parse("expire=0").expireAtMillis());
        equal("My VPN", SubscriptionUserInfo.title("base64:" + Base64.getEncoder().encodeToString("My VPN".getBytes(StandardCharsets.UTF_8))));
        equal("Plain", SubscriptionUserInfo.title("Plain"));
        equal(12, SubscriptionUserInfo.updateIntervalHours("12"));

        List<SubscriptionChange.Entry> before = List.of(entry("a", "DE-1"), entry("b", "NL-1"), entry("c", "FR-1"), entry("d", "US-1"));
        var small = SubscriptionChange.between(before, List.of(entry("a", "DE-1"), entry("b", "NL-1"), entry("c2", "FR-1"), entry("d", "US-1"), entry("e", "JP-1")), 10);
        equal(1, small.added());
        equal(1, small.changed());
        equal(0, small.removed());
        check(!small.unexpected(), "A small change is normal");
        equal("1 added, 1 changed", small.summary());
        var big = SubscriptionChange.between(before, List.of(entry("x", "A"), entry("y", "B")), 20);
        check(big.unexpected(), "Losing most servers is flagged: " + big);

        long now = 1_000_000_000_000L;
        SubscriptionInsight insight = SubscriptionInsight.empty(now)
                .succeeded(now, "Provider", new SubscriptionUserInfo(0, 950, 1000, now + SubscriptionHealth.DAY), 0, 10, 1, 2, 0, big);
        var report = SubscriptionHealth.analyze(insight, now);
        check(report.severity() == SubscriptionHealth.Severity.WARNING, "Expiring soon and 95% used are warnings: " + report.findings());
        check(report.findings().stream().anyMatch(finding -> finding.title().startsWith("Expires")), "Expiry is reported");
        check(report.findings().stream().anyMatch(finding -> finding.title().contains("% of the data used")), "Usage is reported");
        check(report.findings().stream().anyMatch(finding -> finding.title().contains("Large change")), "The large change is reported");
        check(report.findings().stream().anyMatch(finding -> finding.title().contains("also in another source")), "Duplicates elsewhere are reported");
        var expired = SubscriptionHealth.analyze(insight.failed(now + 1, "HTTP 404").failed(now + 2, "HTTP 404").failed(now + 3, "HTTP 404"), now + 2 * SubscriptionHealth.DAY);
        equal(SubscriptionHealth.Severity.PROBLEM, expired.severity());
        check(expired.findings().stream().anyMatch(finding -> finding.title().equals("Expired")), "An expired subscription is a problem");
        check(expired.findings().stream().anyMatch(finding -> finding.title().contains("3 updates in a row failed")), "Repeated failures are a problem");
        equal("Not updated by this version yet", SubscriptionHealth.analyze(null, now).status());

        Path directory = Files.createTempDirectory("perroute-insights-");
        try {
            SubscriptionInsightStore store = new SubscriptionInsightStore(directory.resolve("insights.json"));
            store.put("https://sub.example/secret-token", insight);
            String file = Files.readString(directory.resolve("insights.json"));
            check(!file.contains("secret-token") && !file.contains("sub.example"), "The subscription link is never stored in the insights file");
            SubscriptionInsight reloaded = new SubscriptionInsightStore(directory.resolve("insights.json")).get("https://SUB.example/secret-token ");
            check(reloaded != null && reloaded.title().equals("Provider") && reloaded.history().size() == 1, "Insights survive a restart");
            equal(1000L, reloaded.userInfo().totalBytes());
        } finally { V3DomainTests.deleteTree(directory); }
    }

    private static SubscriptionChange.Entry entry(String identity, String name) { return new SubscriptionChange.Entry(identity, name); }

    private static void testGamingScore() {
        List<Long> steady = new ArrayList<>(), jittery = new ArrayList<>(), lossy = new ArrayList<>(), none = new ArrayList<>();
        for (int i = 0; i < GamingProbe.SAMPLES; i++) {
            steady.add(30L + (i % 2));
            jittery.add(i % 2 == 0 ? 40L : 120L);
            lossy.add(i % 5 == 0 ? -1L : 35L);
            none.add(-1L);
        }
        GamingScore best = GamingScore.of(new GamingProbe.Result(new GamingProbe.Samples(steady, ""), new GamingProbe.Samples(steady, "")), 1);
        check(best.score() >= 95 && best.label().equals("Excellent") && best.udpSupported(), "Steady and low is excellent: " + best);
        GamingScore shaky = GamingScore.of(new GamingProbe.Result(new GamingProbe.Samples(steady, ""), new GamingProbe.Samples(jittery, "")), 1);
        check(shaky.score() < best.score() && shaky.jitterMillis() == 80, "Jitter costs points: " + shaky);
        GamingScore lost = GamingScore.of(new GamingProbe.Result(new GamingProbe.Samples(steady, ""), new GamingProbe.Samples(lossy, "")), 1);
        check(lost.lossPercent() == 20.0 && lost.score() < 75, "Lost packets cost points: " + lost);
        GamingScore noUdp = GamingScore.of(new GamingProbe.Result(new GamingProbe.Samples(steady, ""), new GamingProbe.Samples(none, "no UDP")), 1);
        check(!noUdp.udpSupported() && noUdp.score() <= GamingScore.UDP_UNSUPPORTED_CAP && noUdp.note().contains("UDP"), "No UDP is capped: " + noUdp);
        GamingScore down = GamingScore.of(new GamingProbe.Result(new GamingProbe.Samples(none, "refused"), new GamingProbe.Samples(none, "")), 1);
        equal(0, down.score());
        equal("Unavailable", down.label());
    }

    private static void testShadowExperiment() {
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        ShadowExperiment.Timer timer = (port, host) -> {
            calls.set(calls.get() + 1);
            if (port == 1 && host.equals("b.example") && calls.get() == 4) throw new IOException("reset");
            return port == 1 ? 150 : 60;
        };
        var report = ShadowExperiment.run(timer, 1, 2, List.of("a.example", "b.example"), 3, () -> false);
        equal(12, calls.get());
        equal(ShadowExperiment.Outcome.BETTER, report.verdict().outcome());
        equal(150L, report.verdict().liveMillis());
        equal(60L, report.verdict().candidateMillis());
        equal(1, report.verdict().liveFailures());
        check(report.verdict().detail().contains("faster on 2 of 2"), report.verdict().detail());
        var similar = ShadowExperiment.run((port, host) -> port == 1 ? 100 : 95, 1, 2, List.of("a.example"), 3, () -> false);
        equal(ShadowExperiment.Outcome.SIMILAR, similar.verdict().outcome());
        var worse = ShadowExperiment.run((port, host) -> port == 1 ? 50 : 200, 1, 2, List.of("a.example"), 3, () -> false);
        equal(ShadowExperiment.Outcome.WORSE, worse.verdict().outcome());
        var failed = ShadowExperiment.run((port, host) -> { if (port == 2) throw new IOException("down"); return 50; }, 1, 2, List.of("a.example"), 3, () -> false);
        equal(ShadowExperiment.Outcome.CANDIDATE_FAILED, failed.verdict().outcome());
        var stopped = ShadowExperiment.run((port, host) -> 50, 1, 2, List.of("a.example"), 3, () -> true);
        check(stopped.cancelled() && stopped.verdict().outcome() == ShadowExperiment.Outcome.INCONCLUSIVE, "A stopped test says so");
    }

    private static void testRecipeCodec() throws Exception {
        Recipe recipe = new Recipe(Recipe.FORMAT, "Developer", "For coding", "", "PerRoute 3.5.0",
                List.of(new Recipe.Role("server-1", "Server 1", "Low latency")),
                List.of(new Recipe.AppEntry("Code.exe", "Code", Recipe.Route.ROLE, "server-1", "", 0, 0),
                        new Recipe.AppEntry("chrome.exe", "Chrome", Recipe.Route.AUTO, "", "favorites", 8_000_000, 0)),
                List.of(new Recipe.RuleEntry("GitHub", "", RouteRule.MatchType.DOMAIN, "github.com, githubusercontent.com", RouteRule.Action.PROXY),
                        new Recipe.RuleEntry("Docker", "", RouteRule.MatchType.IP, "172.17.0.0/16", RouteRule.Action.DIRECT),
                        new Recipe.RuleEntry("Torrents", "chrome.exe", RouteRule.MatchType.PORT, "6881-6889", RouteRule.Action.BLOCK)),
                Map.of("perAppKillSwitch", true));
        String json = RecipeCodec.toJson(recipe);
        Recipe parsed = RecipeCodec.parse(json);
        equal(recipe.name(), parsed.name());
        equal(recipe.apps(), parsed.apps());
        equal(recipe.rules(), parsed.rules());
        equal(recipe.roles(), parsed.roles());
        equal(Map.of("perAppKillSwitch", true), parsed.settings());
        Recipe fromText = RecipeCodec.parse("Here is my setup: " + RecipeCodec.toText(recipe) + " enjoy");
        equal(recipe.rules(), fromText.rules());
        for (String secret : List.of("vless://", "subscription", "C:\\", "Users")) check(!json.contains(secret), "A recipe never contains " + secret);

        refused(json.replace("github.com", "evil.example"), "changed or damaged");
        refused(json.replace("\"perrouteRecipe\": 1", "\"perrouteRecipe\": 9"), "newer PerRoute");
        refused("{\"perrouteRecipe\":1,\"rules\":[{\"match\":\"domain\",\"value\":\"ext:secret.dat:x\",\"action\":\"proxy\"}]}", "not a valid domain");
        refused("{\"perrouteRecipe\":1,\"rules\":[{\"match\":\"ip\",\"value\":\"999.1.1.1\",\"action\":\"proxy\"}]}", "not a valid ip");
        refused("{\"perrouteRecipe\":1,\"rules\":[{\"match\":\"port\",\"value\":\"0-70000\",\"action\":\"proxy\"}]}", "not a valid port");
        refused("{\"perrouteRecipe\":1,\"apps\":[{\"executable\":\"C:\\\\Windows\\\\cmd.exe\",\"route\":\"main\"}]}", "not an executable file name");
        refused("{\"perrouteRecipe\":1,\"apps\":[{\"executable\":\"a.exe\",\"route\":\"role\",\"role\":\"missing\"}]}", "does not define");
        refused("{\"perrouteRecipe\":1}", "empty");
        refused("perroute-recipe:%%%", "incomplete");
        refused("hello", "not a PerRoute recipe");
        equal("geosite:netflix, keyword:ads", RecipeCodec.validValue(RouteRule.MatchType.DOMAIN, "geosite:netflix,keyword:ads"));
        equal("2001:db8::/32", RecipeCodec.validValue(RouteRule.MatchType.IP, "2001:db8::/32"));
    }

    private static void refused(String text, String reason) {
        try {
            RecipeCodec.parse(text);
            throw new AssertionError("A bad recipe was accepted: " + text);
        } catch (RecipeCodec.RecipeException expected) {
            check(expected.getMessage().contains(reason), "Expected \"" + reason + "\" in: " + expected.getMessage());
        }
    }

    private static void testRecipePlanner() {
        Recipe recipe = new Recipe(Recipe.FORMAT, "Mine", "", "", "", List.of(new Recipe.Role("server-1", "Server 1", "")),
                List.of(new Recipe.AppEntry("Code.exe", "", Recipe.Route.ROLE, "server-1", "", 0, 0),
                        new Recipe.AppEntry("Missing.exe", "", Recipe.Route.MAIN, "", "", 0, 0)),
                List.of(new Recipe.RuleEntry("GitHub", "", RouteRule.MatchType.DOMAIN, "github.com", RouteRule.Action.PROXY),
                        new Recipe.RuleEntry("Ads", "", RouteRule.MatchType.DOMAIN, "ads.example", RouteRule.Action.BLOCK),
                        new Recipe.RuleEntry("New", "", RouteRule.MatchType.DOMAIN, "new.example", RouteRule.Action.DIRECT)),
                Map.of("autoReconnect", false));
        List<RouteRule> rules = List.of(
                new RouteRule("1", "Mine GitHub", "", RouteRule.MatchType.DOMAIN, "www.github.com, github.com", RouteRule.Action.PROXY, true),
                new RouteRule("2", "Allow ads", "", RouteRule.MatchType.DOMAIN, "ads.example", RouteRule.Action.DIRECT, true));
        String code = "C:\\Tools\\Code.exe";
        var plan = RecipePlanner.plan(recipe, rules, List.of(code), List.of("D:\\Other\\Thing.exe"), Map.of(AppRoutes.normalize(code), "Main"),
                Map.of("autoReconnect", true));
        equal(RecipePlanner.RuleStatus.DUPLICATE, plan.rules().get(0).status());
        equal(RecipePlanner.RuleStatus.CONFLICT, plan.rules().get(1).status());
        equal("Allow ads", plan.rules().get(1).conflictsWith());
        equal(RecipePlanner.RuleStatus.ADD, plan.rules().get(2).status());
        equal(2, plan.rulesToAdd());
        equal(code, plan.apps().get(0).path());
        check(plan.apps().get(0).configured(), "Code is routed differently now, so it is a decision");
        equal(null, plan.apps().get(1).path());
        equal(1, plan.missingApps().size());
        check(plan.usesRoles(), "The recipe uses a server slot");
        equal(1, plan.settings().size());
        check(plan.settings().get(0).from() && !plan.settings().get(0).to(), "Settings show before and after");
        Recipe exported = RecipePlanner.export("Out", "", "", "", List.of(), List.of(),
                List.of(new RouteRule("x", "Scenario · Work · Work tools", "C:\\Users\\name\\app.exe", RouteRule.MatchType.DOMAIN, "slack.com", RouteRule.Action.PROXY, true),
                        new RouteRule("y", "Skip me", "", RouteRule.MatchType.DOMAIN, "skip.example", RouteRule.Action.PROXY, true)), Map.of(), Set.of("y"));
        equal(1, exported.rules().size());
        equal("Work tools", exported.rules().get(0).name());
        equal("app.exe", exported.rules().get(0).executable());
    }

    private static void testWorkspaces() throws Exception {
        Path directory = Files.createTempDirectory("perroute-workspaces-");
        try {
            WorkspaceStore store = new WorkspaceStore(directory.resolve("workspaces.json"));
            Workspace work = store.create("Work", "C:\\Chrome\\chrome.exe", "", 1);
            Workspace personal = store.create("Personal", "C:\\Chrome\\chrome.exe", "node-b", 2);
            equal(0, work.slot());
            equal(1, personal.slot());
            check(work.followsMain() && !personal.followsMain(), "A blank server means the main server");
            store.remove(work.id());
            Workspace again = store.create("Shopping", "C:\\Edge\\msedge.exe", "", 3);
            equal(0, again.slot());
            WorkspaceStore reloaded = new WorkspaceStore(directory.resolve("workspaces.json"));
            equal(2, reloaded.all().size());
            equal("Personal", reloaded.find(personal.id()).name());
            check(WorkspaceBrowsers.supported("C:\\x\\brave.exe") && !WorkspaceBrowsers.supported("C:\\x\\firefox.exe"), "Only Chromium browsers are offered");
            List<String> command = WorkspaceBrowsers.command("C:\\Chrome\\chrome.exe", directory.resolve("profile"), 19_700);
            check(command.contains("--proxy-server=socks5://127.0.0.1:19700"), "The workspace uses its own SOCKS port");
            check(command.stream().anyMatch(part -> part.startsWith("--user-data-dir=")), "A profile folder of its own");
            check(command.contains("--disable-quic") && command.stream().anyMatch(part -> part.contains("disable_non_proxied_udp"))
                    && command.stream().anyMatch(part -> part.startsWith("--host-resolver-rules=MAP * ~NOTFOUND")), "QUIC, WebRTC and DNS stay inside the route");
            ProxyNode node = new ProxyNode("n", "N", Protocol.VLESS, "198.51.100.1", 443, "00000000-0000-0000-0000-000000000000", Map.of(), "");
            String config = new XrayConfigGenerator().generateIsolated(node, 19_700, 25_000, List.of(
                    new RouteRule("r1", "All", "", RouteRule.MatchType.DOMAIN, "example.com", RouteRule.Action.DIRECT, true),
                    new RouteRule("r2", "One app", "C:\\a.exe", RouteRule.MatchType.DOMAIN, "other.example", RouteRule.Action.BLOCK, true)));
            check(config.contains("\"port\": 19700") && config.contains("perroute-custom-r1") && !config.contains("perroute-custom-r2"),
                    "Global rules apply to a workspace, rules for one app do not");
            var inbounds = ((List<?>) Json.object(config).get("inbounds")).stream().map(raw -> String.valueOf(((Map<?, ?>) raw).get("tag"))).toList();
            check(inbounds.contains("perroute-socks") && !inbounds.contains("perroute-http"), "Only the SOCKS port is opened: " + inbounds);
        } finally { V3DomainTests.deleteTree(directory); }
    }

    private static void testLocalNetwork() throws Exception {
        equal("192.168.1.0/24", LocalNetwork.cidrOf("192.168.1.37", 24));
        equal("10.0.0.0/8", LocalNetwork.cidrOf("10.20.30.40", 8));
        check(LocalNetwork.contains("192.168.1.0/24", "192.168.1.254") && !LocalNetwork.contains("192.168.1.0/24", "192.168.2.1"), "Ranges contain their addresses");
        check(LocalNetwork.privateRange("100.100.1.1") && !LocalNetwork.privateRange("8.8.8.8"), "Private ranges");
        String arp = """
                Interface: 192.168.1.20 --- 0x7
                  Internet Address      Physical Address      Type
                  192.168.1.1           aa-bb-cc-dd-ee-ff     dynamic
                  192.168.1.50          11-22-33-44-55-66     dynamic
                  192.168.1.255         ff-ff-ff-ff-ff-ff     static
                  224.0.0.22            01-00-5e-00-00-16     static
                """;
        var table = LocalNetworkScanner.parseArp(arp);
        equal("aa-bb-cc-dd-ee-ff", table.get("192.168.1.1"));
        check(!table.containsKey("224.0.0.22"), "Multicast entries are not devices");
        check(!LocalNetwork.identity("192.168.1.0/24", "aa-bb-cc-dd-ee-ff", "192.168.1.1").equals(LocalNetwork.identity("192.168.1.0/24", "11-11-11-11-11-11", "192.168.1.1")),
                "The same range behind another router is another network");

        Path directory = Files.createTempDirectory("perroute-localnet-");
        try {
            LocalNetworkPolicy policy = new LocalNetworkPolicy(directory.resolve("local.json"));
            LocalNetwork home = new LocalNetwork("home", "Wi-Fi", "192.168.1.20", "192.168.1.0/24", 24, "192.168.1.1", "aa", List.of("192.168.1.1"), List.of());
            check(policy.localNamesDirect(), "Local names stay local by default");
            check(!policy.rules(List.of(home)).blocking(), "Networks are trusted unless marked");
            policy.setTrust("home", "Café", LocalNetworkPolicy.Trust.UNTRUSTED);
            var rules = new LocalNetworkPolicy(directory.resolve("local.json")).rules(List.of(home));
            equal(List.of("192.168.1.0/24"), rules.blocked());
            equal(List.of("192.168.1.1/32"), rules.allowed());
            ProxyNode node = new ProxyNode("n", "N", Protocol.VLESS, "198.51.100.1", 443, "00000000-0000-0000-0000-000000000000", Map.of(), "");
            String config = new XrayConfigGenerator().withLocalNetwork(true, rules.allowed(), rules.blocked()).generateApplicationProxy(node, List.of("C:\\a.exe"));
            int allowed = config.indexOf("perroute-local-allowed"), blocked = config.indexOf("perroute-local-blocked"),
                    privateDirect = config.indexOf("perroute-private-direct"), names = config.indexOf("perroute-local-names"), app = config.indexOf("perroute-app-proxy");
            check(allowed > 0 && allowed < blocked && blocked < privateDirect && privateDirect < names && names < app,
                    "Router and DNS, then the untrusted range, then private, then local names, all before the app rule");
            String plain = new XrayConfigGenerator().generateApplicationProxy(node, List.of("C:\\a.exe"));
            check(!plain.contains("perroute-local-"), "Without the policy nothing changes");
            check(new app.perroute.service.ProxiFyreManager().generateConfig(List.of("C:\\a.exe"), false).contains("\"bypassLan\": false"),
                    "Blocking needs the engine to send local traffic to the core");
        } finally { V3DomainTests.deleteTree(directory); }
    }

    private static void testTemporaryPlanner() {
        ProxyNode main = node("main"), second = node("second"), third = node("third");
        String a = "C:\\A.exe", b = "C:\\B.exe";
        var plan = RoutePlanner.plan(List.of(main, second, third), "main", List.of(a, b), false, Map.of(), Map.of(), ConnectionMode.NATIVE_PER_APP,
                Map.of(), Map.of(AppRoutes.normalize(b), "third"));
        equal(2, plan.routes().size());
        var temporary = plan.routeFor(b);
        check(temporary.temporary() && temporary.node().key().equals("third") && !temporary.auto(), "B has a temporary route of its own");
        equal(RoutePlanner.TEMPORARY_ROUTE_PREFIX + AppRoutes.normalize(b), temporary.id());
        check(plan.routeFor(a).main(), "A follows the main server");
        var ignored = RoutePlanner.plan(List.of(main, second), "main", List.of(a, b), false, Map.of(), Map.of(), ConnectionMode.NATIVE_PER_APP,
                Map.of(), Map.of(AppRoutes.normalize(b), "removed"));
        check(ignored.ready() && ignored.routeFor(b).main(), "A removed temporary server is ignored, never blocking");
        var systemProxy = RoutePlanner.plan(List.of(main, third), "main", List.of(a, b), false, Map.of(), Map.of(), ConnectionMode.SYSTEM_PROXY,
                Map.of(), Map.of(AppRoutes.normalize(b), "third"));
        equal(1, systemProxy.routes().size());
    }

    private static void testRuntimePlan() {
        ProxyNode main = node("main"), second = node("second"), third = node("third");
        List<String> apps = List.of("C:\\A.exe", "C:\\B.exe", "C:\\C.exe");
        XrayConfigGenerator generator = new XrayConfigGenerator(XrayConfigGenerator.PER_APP_PORT_BASE, true);
        java.util.ArrayList<ProxyNode> withC = new java.util.ArrayList<>(java.util.Arrays.asList(null, null, second));
        String running = generator.generateApplicationProxy(main, apps, List.of(), withC);
        java.util.ArrayList<ProxyNode> withB = new java.util.ArrayList<>(java.util.Arrays.asList(null, slot("temp-slot:b", third), second));
        String next = generator.generateApplicationProxy(main, apps, List.of(), withB);
        var targetsRunning = XrayConfigGenerator.applicationRouteTargets(running);
        var targetsNext = XrayConfigGenerator.applicationRouteTargets(next);
        Map<String, String> keep = new LinkedHashMap<>();
        keep.put(targetsNext.get("perroute-app-proxy"), targetsRunning.get("perroute-app-proxy"));
        keep.put(targetsNext.get("perroute-app-route-1"), targetsRunning.get("perroute-app-route-0"));
        var plan = XrayConfigGenerator.runtimePlan(running, next, keep);
        check(plan != null, "Adding a route of its own can be done in place");
        equal(List.of("perroute-check-1"), plan.addedInboundTags());
        equal(1, plan.addedOutboundTags().size());
        check(plan.removeOutbounds().isEmpty() && plan.removeInbounds().isEmpty(), "Nothing is removed when a route is added");
        var after = XrayConfigGenerator.applicationRouteTargets(plan.config());
        equal(targetsRunning.get("perroute-app-route-0"), after.get("perroute-app-route-1"));
        check(!after.get("perroute-app-route-0").equals(targetsRunning.get("perroute-app-route-0")), "The new route gets a new outbound");
        Map<String, String> back = new LinkedHashMap<>();
        var current = XrayConfigGenerator.applicationRouteTargets(plan.config());
        back.put(targetsRunning.get("perroute-app-proxy"), current.get("perroute-app-proxy"));
        back.put(targetsRunning.get("perroute-app-route-0"), current.get("perroute-app-route-1"));
        var undo = XrayConfigGenerator.runtimePlan(plan.config(), running, back);
        check(undo != null && undo.removeInbounds().equals(List.of("perroute-check-1")) && undo.removeOutbounds().size() == 1 && undo.addedOutboundTags().isEmpty(),
                "Ending the route removes only what it added: " + (undo == null ? "null" : undo.removeOutbounds()));
        String balanced = running.replace("\"rules\": [", "\"balancers\": [{\"tag\": \"b\", \"selector\": [\"proxy\"]}],\n    \"rules\": [");
        check(XrayConfigGenerator.runtimePlan(balanced, next, keep) == null, "Balancers are never changed in place");
        String noControl = new XrayConfigGenerator(XrayConfigGenerator.PER_APP_PORT_BASE, false).generateApplicationProxy(main, apps, List.of(), withB);
        check(XrayConfigGenerator.runtimePlan(running, noControl, keep) == null, "A config without the control API needs a restart");
    }

    private static ProxyNode slot(String key, ProxyNode node) {
        return new ProxyNode(key, node.name(), node.protocol(), node.address(), node.port(), node.credential(), node.options(), node.sourceLink());
    }

    private static void testAnalysis() {
        var shadowed = RuleAnalysis.INSTANCE.shadowed(List.of(
                new RouteRule("1", "Everything Google", "", RouteRule.MatchType.DOMAIN, "google.com", RouteRule.Action.DIRECT, true),
                new RouteRule("2", "Mail", "", RouteRule.MatchType.DOMAIN, "mail.google.com", RouteRule.Action.PROXY, true),
                new RouteRule("3", "Ranges", "", RouteRule.MatchType.IP, "10.0.0.0/8", RouteRule.Action.DIRECT, true),
                new RouteRule("4", "Office", "", RouteRule.MatchType.IP, "10.1.2.0/24", RouteRule.Action.PROXY, true),
                new RouteRule("5", "Ports", "", RouteRule.MatchType.PORT, "1000-2000", RouteRule.Action.BLOCK, true),
                new RouteRule("6", "One port", "", RouteRule.MatchType.PORT, "1500", RouteRule.Action.PROXY, true),
                new RouteRule("7", "Elsewhere", "", RouteRule.MatchType.DOMAIN, "example.org", RouteRule.Action.PROXY, true),
                new RouteRule("8", "App only", "C:\\x.exe", RouteRule.MatchType.DOMAIN, "example.net", RouteRule.Action.PROXY, true),
                new RouteRule("9", "For all", "", RouteRule.MatchType.DOMAIN, "example.net", RouteRule.Action.DIRECT, true)));
        equal(List.of("2", "4", "6"), shadowed.stream().map(item -> item.getRule().id()).toList());
        var hint = CoreLogHints.INSTANCE.analyze(List.of("2026/10/19 [Warning] failed to dial: i/o timeout", "[Warning] failed: i/o timeout", "accepted tcp:x"));
        check(hint != null && hint.getTitle().contains("time out"), "Timeouts are recognised: " + hint);
        var credentials = CoreLogHints.INSTANCE.analyze(List.of("[Warning] proxy/vless: failed: invalid request user id"));
        check(credentials != null && credentials.getTitle().contains("account"), "Rejected accounts are recognised");
        equal(null, CoreLogHints.INSTANCE.analyze(List.of("accepted tcp:example.com:443")));
    }

    private static final class Fixture implements AutoCloseable {
        final Path directory;
        final ControllerTests.FakeBackend backend = new ControllerTests.FakeBackend();
        final ControllerTests.MemoryPreferences preferences = new ControllerTests.MemoryPreferences();
        final AppState state = new AppState();
        final AppStateStore store;
        final PerRouteController controller;
        final String chrome, telegram, code;

        Fixture() throws IOException {
            directory = Files.createTempDirectory("perroute-v35-");
            System.setProperty("perroute.data", directory.resolve("data").toString());
            chrome = executable("chrome.exe");
            telegram = executable("Telegram.exe");
            code = executable("Code.exe");
            state.nodes().addAll(List.of(node("main"), node("second"), node("third")));
            state.setSelectedNodeKey("main");
            state.setConnectionMode(ConnectionMode.NATIVE_PER_APP);
            state.appPaths().addAll(List.of(chrome, telegram));
            preferences.putBoolean("autoFailover", false);
            preferences.putBoolean("autoReconnect", false);
            store = new AppStateStore(directory.resolve("data").resolve("state.bin"));
            controller = new PerRouteController(state, store, backend, preferences);
        }

        String executable(String name) throws IOException {
            Path file = directory.resolve(name);
            Files.writeString(file, "test");
            return file.toAbsolutePath().normalize().toString();
        }

        @Override public void close() throws IOException {
            controller.close();
            System.clearProperty("perroute.data");
            V3DomainTests.deleteTree(directory);
        }
    }

    private interface FixtureTest { void run(Fixture fixture) throws Exception; }

    private static void withFixture(String name, FixtureTest test) throws Exception {
        System.out.println("[test] " + name);
        try (Fixture fixture = new Fixture()) { test.run(fixture); }
    }

    private static void testTemporaryRoutes(Fixture f) throws Exception {
        PerRouteController c = f.controller;
        check(!c.setTemporaryRoute(f.code, "third", 30).getOk(), "Only selected apps can use a temporary server");
        check(!c.setTemporaryRoute(f.chrome, "missing", 30).getOk(), "The server must exist");
        check(!c.setTemporaryRoute(f.chrome, "third", 0).getOk(), "The length must be sensible");
        ActionResult set = c.setTemporaryRoute(f.chrome, "third", 30);
        check(set.getOk() && set.getMessage().contains("until"), set.getMessage());
        TemporaryRoute route = c.temporaryRoute(f.chrome);
        check(route != null && route.getNodeKey().equals("third") && route.remainingMillis(System.currentTimeMillis()) > 29 * 60_000L, "The temporary server is saved");
        check(c.routePlan(false).routeFor(f.chrome).temporary(), "The plan shows the temporary route");
        check(c.appRoutes().isEmpty() && !c.advancedRouting(), "Nothing permanent changes");

        check(c.connect().getOk(), "Connect");
        check(f.backend.lastConfig.contains("third.example"), "The temporary server is in the running core");
        check(c.activeRoutes().stream().anyMatch(r -> r.getId().startsWith(RoutePlanner.TEMPORARY_ROUTE_PREFIX)), "It has a route of its own");
        int starts = f.backend.startCount;
        check(c.cancelTemporaryRoute(f.chrome).getOk(), "End it");
        check(!f.backend.lastConfig.contains("third.example"), "The usual route is back");
        equal(starts + 1, f.backend.startCount);
        f.backend.supportsRuntimePlan = true;
        starts = f.backend.startCount;
        ActionResult inPlace = c.setTemporaryRoute(f.telegram, "second", 15);
        check(inPlace.getOk(), inPlace.getMessage());
        check(c.cancelTemporaryRoute(f.telegram).getOk(), "End it again");
        check(c.setTemporaryRoute(f.telegram, "third", 15).getOk(), "Start another one");
        equal(starts, f.backend.startCount);
        equal(3, f.backend.runtimePlans.size());
        check(f.backend.lastConfig.contains("third.example"), "The new temporary route is live");
        check(f.backend.runtimePlans.get(0).addedInboundTags().size() == 1 && f.backend.runtimePlans.get(1).removeInbounds().size() == 1,
                "Starting adds the route's check port, ending removes it");
        check(c.activeRoutes().stream().filter(r -> r.getId().startsWith(RoutePlanner.TEMPORARY_ROUTE_PREFIX)).count() == 1, "One temporary route is live");
        c.disconnect();

        Path file = f.directory.resolve("data").resolve("temporary-routes.json");
        Files.writeString(file, Json.stringify(Map.of("format", 1, "routes", List.of(Map.of("app", f.chrome, "node", "third", "startedAt", 1, "until", 2)))));
        try (Fixture second = new Fixture()) {
            Files.createDirectories(second.directory.resolve("data"));
            Files.writeString(second.directory.resolve("data").resolve("temporary-routes.json"),
                    Json.stringify(Map.of("format", 1, "routes", List.of(Map.of("app", second.chrome, "node", "third", "startedAt", 1, "until", 2)))));
            check(second.controller.temporaryRoutes(System.currentTimeMillis()).isEmpty(), "An ended temporary server is not listed");
            check(second.controller.connect().getOk() && !second.backend.lastConfig.contains("third.example"), "An ended temporary server is not applied");
        }
    }

    private static void testNetworkRestore(Fixture f) throws Exception {
        PerRouteController c = f.controller;
        String ours = WindowsSystemProxyManager.perRouteProxyServer();
        f.backend.networkValues.putAll(Map.of(NetworkStateKeys.PROXY_ENABLE, "0x0", NetworkStateKeys.PROXY_SERVER, "",
                NetworkStateKeys.PROXY_BACKUP, "no", NetworkStateKeys.RUN_ONCE, "no", NetworkStateKeys.CORE_PROCESSES, "0"));
        check(c.takeNetworkSnapshot(NetworkSnapshot.Reason.STARTUP) != null, "A snapshot is taken");
        check(!c.networkRestorePlan().needed(), "Nothing to restore on a clean machine");
        check(c.restoreNetwork().getOk(), "A restore with nothing to do succeeds");

        f.backend.networkValues.putAll(Map.of(NetworkStateKeys.PROXY_ENABLE, "0x1", NetworkStateKeys.PROXY_SERVER, ours,
                NetworkStateKeys.CORE_PROCESSES, "1", NetworkStateKeys.ENGINE_SERVICE, NetworkStateKeys.RUNNING,
                NetworkStateKeys.ENGINE_CONFIG, NetworkStateKeys.CONFIG_PERROUTE, NetworkStateKeys.ENGINE_BACKUP, "yes"));
        check(c.networkRestorePlan().needed(), "Leftovers are found");
        check(c.connect().getOk(), "Connect");
        check(!c.restoreNetwork().getOk(), "A restore is refused while connected");
        c.disconnect();
        f.backend.machineCalls.clear();
        ActionResult restored = c.restoreNetwork();
        check(!restored.getOk() && restored.getMessage().contains("Administrator"), "Without Administrator the engine is left for the recovery tool: " + restored.getMessage());
        check(f.backend.machineCalls.contains("resetSystemProxyLeftover baseline"), "The proxy is put back from the clean baseline: " + f.backend.machineCalls);
        check(f.backend.machineCalls.contains("stopOrphan"), "Leftover cores are stopped");
        check(!f.backend.machineCalls.contains("restorePerApp"), "The engine is not touched without Administrator");
        f.backend.elevatedNow = true;
        f.backend.machineCalls.clear();
        check(c.restoreNetwork().getOk(), "Elevated, everything is restored");
        check(f.backend.machineCalls.contains("restorePerApp"), "The engine is restored when elevated");
        check(c.networkSnapshots().stream().anyMatch(s -> s.reason() == NetworkSnapshot.Reason.BEFORE_RESTORE), "A snapshot before the restore is kept");
    }

    private static void testRecipesController(Fixture f) throws Exception {
        PerRouteController c = f.controller;
        c.addRule("GitHub", "", RouteRule.MatchType.DOMAIN, "github.com", RouteRule.Action.PROXY);
        check(c.saveApplicationSetup(List.of(f.chrome, f.telegram), Map.of(f.telegram, "second"), true, Set.of(), Map.of()).getOk(), "Telegram on second");
        RecipeDraft draft = c.recipeDraft();
        equal(2, draft.getApps().size());
        equal(1, draft.getRoles().size());
        Recipe recipe = c.buildRecipe("Team", "", "", draft, true, true, Map.of("server-1", "Messaging"), Set.of());
        String text = RecipeCodec.toJson(recipe);
        check(!text.contains("second.example") && !text.contains(f.directory.toString()), "The recipe holds no server and no folder");
        equal("Messaging", recipe.roles().get(0).label());

        try (Fixture other = new Fixture()) {
            var opened = other.controller.openRecipe(RecipeCodec.toText(recipe));
            var plan = opened.getFirst();
            check(plan != null, "The recipe opens: " + opened.getSecond());
            equal(RecipePlanner.RuleStatus.ADD, plan.rules().get(0).status());
            check(!other.controller.applyRecipe(plan, Map.of(), Map.of("Telegram.exe", other.telegram), Set.of(), false).getOk(), "Every slot needs a server");
            ActionResult applied = other.controller.applyRecipe(plan, Map.of("server-1", "third"), Map.of("Telegram.exe", other.telegram, "chrome.exe", other.chrome),
                    Set.of("Telegram.exe"), true);
            check(applied.getOk(), applied.getMessage());
            equal("third", other.controller.appRoutes().get(AppRoutes.normalize(other.telegram)));
            check(other.controller.advancedRouting(), "A recipe with slots turns Advanced routing on");
            check(other.controller.rules().stream().anyMatch(rule -> rule.name().startsWith(RecipePlanner.RULE_PREFIX)), "Recipe rules are marked");
            check(other.controller.hasRecipeSnapshot(), "A rollback snapshot is kept");
            check(other.controller.undoRecipe().getOk(), "Undo");
            check(other.controller.appRoutes().isEmpty() && other.controller.rules().isEmpty() && !other.controller.advancedRouting(), "Everything is back");
            var bad = other.controller.openRecipe("not a recipe");
            check(bad.getFirst() == null && bad.getSecond() != null, "A bad recipe gives a reason");
        }
    }

    private static void testHealth(Fixture f) throws Exception {
        PerRouteController c = f.controller;
        f.backend.serverTime = System.currentTimeMillis() - 5 * 60_000L;
        HealthReport report = c.diagnose(null, null);
        HealthFinding clock = report.getFindings().stream().filter(item -> item.getId().equals("clock")).findFirst().orElseThrow();
        equal(CheckStatus.FAIL, clock.getStatus());
        check(clock.getTitle().contains("5 minutes"), clock.getTitle());
        f.backend.serverTime = System.currentTimeMillis();
        f.backend.resolves = false;
        check(c.diagnose(null, null).getFindings().stream().anyMatch(item -> item.getId().equals("dns") && item.getStatus() == CheckStatus.FAIL), "DNS failures are found");
        f.backend.resolves = true;

        HealthReport unselected = c.diagnose(f.code, "https://blocked.example/page");
        check(unselected.getFindings().stream().anyMatch(item -> item.getId().equals("app") && item.getStatus() == CheckStatus.FAIL && item.getRepair() == Repair.OPEN_APPS),
                "An unselected app is the cause");
        c.addRule("Block it", "", RouteRule.MatchType.DOMAIN, "blocked.example", RouteRule.Action.BLOCK);
        HealthReport blocked = c.diagnose(f.chrome, "blocked.example");
        check(blocked.getFindings().stream().anyMatch(item -> item.getId().equals("rule") && item.getStatus() == CheckStatus.FAIL), "A blocking rule is found");

        check(c.connect().getOk(), "Connect");
        f.backend.failingPorts.add(app.perroute.service.AppProxyLauncher.SOCKS_PORT);
        f.backend.emit("2026/10/19 12:00:00 [Warning] failed to dial: i/o timeout");
        f.backend.emit("2026/10/19 12:00:01 [Warning] failed to dial: i/o timeout");
        HealthReport tunnel = c.diagnose(null, null);
        HealthFinding finding = tunnel.getFindings().stream().filter(item -> item.getId().equals("tunnel")).findFirst().orElseThrow();
        equal(CheckStatus.FAIL, finding.getStatus());
        check(finding.getTitle().contains("time out"), "The core's own log explains the failure: " + finding.getTitle());
        f.backend.failingPorts.clear();

        HealthReport safety = c.safetyCheck();
        check(safety.getFindings().stream().anyMatch(item -> item.getId().equals("killswitch") && item.getStatus() == CheckStatus.WARN), "The kill switch off is a warning");
        check(safety.getFindings().stream().anyMatch(item -> item.getId().startsWith("exit:") && item.getStatus() == CheckStatus.PASS), "Routes leave from another address");
        check(safety.getFindings().stream().anyMatch(item -> item.getId().equals("dns") && item.getStatus() == CheckStatus.WARN), "Local name lookups are explained");
        c.disconnect();
        c.addRule("Wide", "", RouteRule.MatchType.DOMAIN, "example.org", RouteRule.Action.DIRECT);
        c.addRule("Narrow", "", RouteRule.MatchType.DOMAIN, "www.example.org", RouteRule.Action.PROXY);
        check(c.safetyCheck().getFindings().stream().anyMatch(item -> item.getId().startsWith("shadow:") && item.getTitle().contains("Narrow")), "Shadowed rules are found");
        check(c.repair(Repair.TURN_OFF_KILL_SWITCH).getOk() && !c.killSwitch(), "Repairs run what they say");
    }

    private static void testSubscriptionController(Fixture f) throws Exception {
        PerRouteController c = f.controller;
        AtomicReference<String> body = new AtomicReference<>(links(4));
        AtomicReference<Integer> status = new AtomicReference<>(200);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/sub", exchange -> {
            byte[] bytes = Base64.getEncoder().encode(body.get().getBytes(StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("subscription-userinfo", "upload=100; download=800; total=1000; expire=4102444800");
            exchange.getResponseHeaders().add("profile-title", "base64:" + Base64.getEncoder().encodeToString("Test VPN".getBytes(StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(status.get(), status.get() == 200 ? bytes.length : -1);
            if (status.get() == 200) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/sub";
            check(c.fetchSubscription(url).getOk(), "Fetch");
            SubscriptionInsight insight = c.subscriptionInsight(url);
            check(insight != null && insight.title().equals("Test VPN") && insight.serverCount() == 4, "Title and servers are recorded");
            equal(1000L, insight.userInfo().totalBytes());
            check(c.subscriptionHealth(url).findings().stream().anyMatch(item -> item.title().contains("% of the data used")), "Usage is analysed");
            body.set(links(1));
            check(c.fetchSubscription(url).getOk(), "Fetch again");
            SubscriptionChange change = c.subscriptionInsight(url).lastChange();
            check(change != null && change.removed() == 3 && change.unexpected(), "The large change is recorded: " + change);
            status.set(404);
            check(!c.updateAllSubscriptions().getOk(), "A failed update is reported");
            equal(1, c.subscriptionInsight(url).consecutiveFailures());
            check(c.subscriptionAttention() == 1, "The subscription needs attention");
            c.removeSubscription(url);
            check(c.subscriptionInsight(url) == null, "Removing a subscription removes its insights");
        } finally {
            server.stop(0);
        }
    }

    private static String links(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append("vless://00000000-0000-0000-0000-00000000000").append(i).append("@198.51.100.").append(i + 1)
                    .append(":443?encryption=none&security=tls&type=tcp#Server-").append(i).append('\n');
        }
        return text.toString();
    }

    private static void testLocalNetworkController(Fixture f) throws Exception {
        PerRouteController c = f.controller;
        LocalNetwork cafe = new LocalNetwork(LocalNetwork.identity("10.9.0.0/16", "aa", "10.9.0.1"), "Wi-Fi", "10.9.3.4", "10.9.0.0/16", 16, "10.9.0.1", "aa",
                List.of("10.9.0.1"), List.of(new LocalNetwork.Device("10.9.0.1", "aa", "", true)));
        f.backend.networks = List.of(cafe);
        check(c.localNamesDirect(), "Local names stay local by default");
        check(c.connect().getOk(), "Connect");
        check(f.backend.lastConfig.contains("perroute-local-names") && !f.backend.lastConfig.contains("perroute-local-blocked"), "Local names direct, nothing blocked");
        check(f.backend.machineCalls.stream().noneMatch(call -> call.contains("bypassLan=false")), "The engine bypasses the LAN as before");
        c.disconnect();
        c.setNetworkTrusted(cafe.id(), "Café", false);
        check(!c.networkTrusted(cafe.id()), "The network is not trusted now");
        f.backend.machineCalls.clear();
        check(c.connect().getOk(), "Connect on the untrusted network");
        check(f.backend.lastConfig.contains("perroute-local-blocked") && f.backend.lastConfig.contains("10.9.0.0/16") && f.backend.lastConfig.contains("10.9.0.1/32"),
                "The range is blocked, the router stays reachable");
        check(f.backend.machineCalls.contains("applyPerApp bypassLan=false"), "Local traffic of routed apps reaches the core so it can be blocked");
        c.disconnect();
        c.setLocalNamesDirect(false);
        c.setNetworkTrusted(cafe.id(), "Café", true);
        check(c.connect().getOk() && !f.backend.lastConfig.contains("perroute-local-"), "Both can be turned off");
    }

    private static void testQuicBlocked(Fixture f) throws Exception {
        PerRouteController c = f.controller;
        check(c.quicBlocked(), "QUIC is blocked by default");
        check(c.connect().getOk(), "Connect");
        String config = f.backend.lastConfig;
        int privateDirect = config.indexOf("perroute-private-direct"), quic = config.indexOf(XrayConfigGenerator.QUIC_BLOCK_RULE),
                app = config.indexOf("perroute-app-proxy");
        check(privateDirect > 0 && privateDirect < quic && quic < app, "Local traffic first, then QUIC, all before the app rule");
        String rule = config.substring(config.lastIndexOf("{", quic), config.indexOf("}", quic) + 1).replaceAll("\\s", "");
        check(rule.contains("\"network\":\"udp\"") && rule.contains("\"port\":\"443\"") && rule.contains("\"outboundTag\":\"block\""),
                "Only UDP 443 goes to the block outbound: " + rule);
        c.disconnect();
        check(c.setQuicBlocked(false).getOk() && !c.quicBlocked(), "QUIC can be allowed");
        check(c.connect().getOk() && !f.backend.lastConfig.contains(XrayConfigGenerator.QUIC_BLOCK_RULE), "Allowed QUIC adds no rule");
        c.disconnect();
        c.setQuicBlocked(true);
        ProxyNode node = new ProxyNode("n", "N", Protocol.VLESS, "198.51.100.1", 443, "00000000-0000-0000-0000-000000000000", Map.of(), "");
        check(!new XrayConfigGenerator().generateApplicationProxy(node, List.of("C:\\a.exe")).contains(XrayConfigGenerator.QUIC_BLOCK_RULE),
                "The generator leaves QUIC alone unless asked");
    }

    private static void testWorkspaceController(Fixture f) throws Exception {
        PerRouteController c = f.controller;
        String firefox = f.executable("firefox.exe");
        check(!c.createWorkspace("Work", firefox, "").getOk(), "Firefox cannot be a workspace browser");
        check(!c.createWorkspace("", f.chrome, "").getOk(), "A name is needed");
        check(!c.createWorkspace("Work", f.chrome, "missing").getOk(), "The server must exist");
        check(c.createWorkspace("Work", f.chrome, "").getOk(), "Create");
        check(!c.createWorkspace("work", f.chrome, "").getOk(), "Names are unique");
        var view = c.workspaces().get(0);
        equal("main", view.getNode().key());
        equal(PerRouteController.Companion.getWORKSPACE_PORT_BASE(), view.getPort());
        check(!view.getRunning() && view.getProblem().isEmpty(), "Ready to open");
        check(c.updateWorkspace(view.getWorkspace().id(), "Work", f.chrome, "second").getOk(), "Move it to another server");
        equal("second", c.workspaces().get(0).getNode().key());
        f.backend.failIsolatedStart = "Core unavailable in this fixture";
        ActionResult opened = c.openWorkspace(view.getWorkspace().id());
        check(!opened.getOk() && opened.getMessage().contains("could not start its route"), "Without a core the workspace does not open: " + opened.getMessage());
        check(c.removeWorkspace(view.getWorkspace().id(), false).getOk() && c.workspaces().isEmpty(), "Remove");
        int base = PerRouteController.Companion.getWORKSPACE_PORT_BASE();
        check(base >= XrayConfigGenerator.GATEWAY_UPSTREAM_PORT_BASE + 500, "Workspace ports come after every connection port");
    }

    private static NetworkSnapshot snapshot(long id, NetworkSnapshot.Reason reason, Map<String, String> values) {
        return new NetworkSnapshot(id, id, reason, "3.5.0", values);
    }

    private static ProxyNode node(String key) {
        return new ProxyNode(key, key, Protocol.VLESS, "127.0.0.1", 9, key + ".example", Map.of(), "");
    }

    private interface Test { void run() throws Exception; }

    private static void run(String name, Test test) throws Exception {
        System.out.println("[test] " + name);
        test.run();
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
