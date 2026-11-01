package app.perroute;

import app.perroute.model.*;
import app.perroute.routing.AutoSelection;
import app.perroute.backend.api.ConnectionBackend;
import app.perroute.persistence.AppStateStore;
import app.perroute.backup.BackupService;
import app.perroute.service.RoutePlanner;
import app.perroute.service.RouteRuleEvaluator;
import app.perroute.service.XrayConfigGenerator;
import app.perroute.service.TrafficHistoryStore;
import app.perroute.service.XrayRuntime;
import app.perroute.platform.macos.MacConnectionBackend;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.nio.file.*;
import java.util.*;

public final class ApplicationIdentityTests {
    private static int checks;
    public static void main(String[] args) throws Exception {
        String mainPath = "/Applications/Editor فارسی.app/Contents/MacOS/Editor ";
        String upper = args.length > 0 ? ApplicationIdentity.macAppFamily(mainPath) : ApplicationIdentity.macExecutable(mainPath);
        check(!AppRoutes.normalize(ApplicationIdentity.macAppFamily(mainPath)).equals(AppRoutes.normalize(ApplicationIdentity.macExecutable(mainPath))),
                "Family opt-in stays distinct from a main-only saved selection");
        check(app.perroute.compose.controller.PerRouteController.Companion.displayName(ApplicationIdentity.macAppFamily(mainPath)).endsWith("(including helpers)"),
                "Family scope remains visible outside the picker");
        String lower = ApplicationIdentity.macExecutable("/Applications/editor فارسی.app/Contents/MacOS/editor ");
        check(!AppRoutes.normalize(upper).equals(AppRoutes.normalize(lower)), "Case variants remain distinct");
        check(AppRoutes.normalize(upper).equals(upper), "No case, slash, Unicode or whitespace loss");
        check(ApplicationIdentity.path(upper).endsWith("/Editor "), "Decode preserves filename whitespace");
        check(ApplicationIdentity.same("C:\\Tools\\APP.exe", "c:\\tools\\app.EXE"), "Legacy comparison stays insensitive");
        check(!ApplicationIdentity.same(upper, lower), "Mac comparison stays exact");
        check(!ApplicationIdentity.same(upper, ApplicationIdentity.path(upper)), "Raw paths cannot impersonate tagged identities");
        for (String invalid : List.of("relative", "/", "/x/", "/x//y", "/x/../y", "/x/./y", "/x\\y", "/x\n", "/x"+'\ud800')) {
            fail(() -> ApplicationIdentity.macExecutable(invalid));
        }
        String legacy = Path.of("C:\\Tools\\APP.exe").toAbsolutePath().normalize().toString().replace('/', '\\').toLowerCase(Locale.ROOT);
        check(AppRoutes.normalize("C:\\Tools\\APP.exe").equals(legacy), "Legacy Windows normalization unchanged on this host");
        var main = new ProxyNode("main", "Main", Protocol.VLESS, "example.invalid", 443, "test", Map.of(), "");
        var custom = new ProxyNode("custom", "Custom", Protocol.VLESS, "example.invalid", 443, "test", Map.of(), "");
        var state = new AppState(); state.nodes().addAll(List.of(main, custom)); state.appPaths().addAll(List.of(upper, lower));
        state.setSelectedNodeKey(main.key()); state.setConnectionMode(ConnectionMode.NATIVE_PER_APP); state.setAdvancedRouting(true);
        state.appRoutes().put(upper, custom.key()); state.autoApps().add(lower);
        state.autoPools().put(lower, "all"); state.autoSelections().put(lower, new AutoSelection(main.key(), 123));
        state.bandwidthPolicies().put(upper, new BandwidthPolicy(16000, 32000));
        var rule = new RouteRule("rule", "Only upper", upper, RouteRule.MatchType.DOMAIN, "example.invalid", RouteRule.Action.BLOCK, true);
        state.routeRules().add(rule);
        state.profiles().add(new ConnectionProfile("profile", "Profile", state.appPaths(), main.key(), ConnectionMode.NATIVE_PER_APP,
                state.routeRules(), true, state.appRoutes(), state.autoApps()));
        var store = AppStateStore.inMemory(); var restored = store.parse(store.serialize(state));
        check(restored.appPaths().equals(List.of(upper, lower)), "Selection state round trip");
        check(restored.appRoutes().get(upper).equals(custom.key()), "Manual assignment state round trip");
        check(restored.autoApps().equals(Set.of(lower)) && restored.autoSelections().get(lower).changedAt()==123, "Auto identity state round trip");
        check(restored.bandwidthPolicies().containsKey(upper), "Bandwidth identity state round trip");
        check(restored.routeRules().getFirst().applicationPath().equals(upper), "Rule whitespace/path round trip");
        check(restored.profiles().getFirst().appRoutes().containsKey(upper) && restored.profiles().getFirst().autoApps().contains(lower), "Profile identities round trip");
        var plan = RoutePlanner.plan(state.nodes(), main.key(), state.appPaths(), true, state.appRoutes(), Map.of(), ConnectionMode.NATIVE_PER_APP, Map.of(lower, main.key()));
        check(plan.nodeFor(upper).key().equals(custom.key()), "Case-specific custom routing");
        check(plan.nodeFor(lower).key().equals(main.key()), "Case-specific Auto routing");
        check(RouteRuleEvaluator.evaluate(List.of(rule), List.of(upper, lower), upper, "example.invalid", 443).action()==RouteRule.Action.BLOCK, "Evaluator matches exact tagged identity including whitespace");
        check(RouteRuleEvaluator.evaluate(List.of(rule), List.of(upper, lower), lower, "example.invalid", 443).action()==RouteRule.Action.PROXY, "Evaluator does not apply case variant's rule");
        String generated = new XrayConfigGenerator().generateApplicationProxy(main, List.of(upper, lower), List.of(rule));
        var root = app.perroute.util.Json.object(generated);
        var routing = (Map<?,?>) root.get("routing");
        var rules = (List<?>) routing.get("rules");
        var blocking = rules.stream().filter(item -> item instanceof Map<?,?> map && "block".equals(map.get("outboundTag"))).map(item -> (Map<?,?>)item).filter(item -> item.containsKey("domain")).findFirst().orElseThrow();
        check(((List<?>)blocking.get("inboundTag")).size()==1, "Xray app rule binds only the exact selected inbound");
        byte[] backup = BackupService.export(state, Map.of(), EnumSet.of(BackupService.Section.MANUAL_SERVERS, BackupService.Section.APP_ASSIGNMENTS, BackupService.Section.RULES, BackupService.Section.PROFILES), null, "test", 123);
        var preview = BackupService.preview(backup, null, new AppState(), path -> true, List.of());
        var copy = BackupService.plan(preview, new AppState(), Map.of(), BackupService.Mode.REPLACE, Map.of(), path -> true).state();
        check(copy.appPaths().equals(List.of(upper, lower)), "Backup restores distinct tagged paths");
        check(copy.appRoutes().containsKey(upper) && copy.autoApps().contains(lower), "Backup restores route/Auto keys");
        check(copy.routeRules().getFirst().applicationPath().equals(upper), "Backup rule identity round trip");
        check(copy.profiles().getFirst().applicationPaths().equals(List.of(upper, lower)), "Backup profile identity round trip");
        String replacement = ApplicationIdentity.macExecutable("/Applications/New.app/Contents/MacOS/New ");
        var remapped = BackupService.plan(preview, new AppState(), Map.of(), BackupService.Mode.REPLACE, Map.of(upper, replacement), path -> true).state();
        check(remapped.appPaths().contains(replacement) && remapped.appPaths().contains(lower), "Explicit backup remap preserves untouched case variant");
        Path directory = Files.createTempDirectory("perroute-identity-tests-").toRealPath();
        try {
            Path history = directory.resolve("history.json");
            try (var traffic = new TrafficHistoryStore(history)) {
                traffic.record(upper, 100, 10); traffic.record(lower, 200, 20);
                check(traffic.report(upper).monthDownloadBytes()==100, "History filter exact upper");
                check(traffic.report(lower).monthDownloadBytes()==200, "History filter exact lower");
            }
            try (var traffic = new TrafficHistoryStore(history)) { check(traffic.snapshot().applications().size()==2, "Persisted history retains both identities"); }
            Path executable = directory.resolve("Tool فارسی "); Files.writeString(executable, "fixture"); executable.toFile().setExecutable(true, true);
            var backend = new MacConnectionBackend(XrayRuntime.forPlatform("Mac OS X", "aarch64", directory), directory, true);
            String selected = backend.selectApplication(executable.toString());
            check(ApplicationIdentity.path(selected).equals(executable.toString()), "Mac selection resolves exact observed file");
            check(backend.restoreApplication(selected).equals(selected), "Mac saved identity validation");
            fail(() -> backend.restoreApplication(executable.toString()));
            fail(() -> backend.restoreApplication("c:\\tools\\tool.exe"));
            Path alias = directory.resolve("Alias"); Files.createSymbolicLink(alias, executable);
            check(backend.selectApplication(alias.toString()).equals(selected), "New explicit alias selection resolves canonical executable");
            fail(() -> backend.restoreApplication(ApplicationIdentity.macExecutable(alias.toString())));
            check(BackupService.executableExists(selected), "Backup filesystem check decodes identity");
            Files.delete(alias); Files.delete(executable); Files.delete(history);
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        var draft = new app.perroute.compose.AppsDraft(); draft.getSelected().add(upper); draft.getSelected().add(lower);
        draft.toggle(upper); check(draft.isSelected(lower) && !draft.isSelected(upper), "Compose toggle keeps the other case variant");
        draft.toggle(upper); check(draft.getSelected().size()==2, "Compose draft can select both case variants");
        ConnectionBackend windows = (ConnectionBackend) Proxy.newProxyInstance(ConnectionBackend.class.getClassLoader(), new Class[]{ConnectionBackend.class},
                (proxy, method, values) -> InvocationHandler.invokeDefault(proxy, method, values));
        fail(() -> windows.restoreApplication(upper));
        check(windows.restoreApplication("relative.exe").equals(Path.of("relative.exe").toAbsolutePath().normalize().toString()), "Default Windows selection resolution unchanged");
        System.out.println("Application identity: " + checks + " checks passed; isolated files, no routing changes");
    }
    interface Checked { void run() throws Exception; }
    static void fail(Checked action) throws Exception { try { action.run(); throw new AssertionError("Expected refusal"); } catch (IllegalArgumentException | java.io.IOException expected) { checks++; } }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
