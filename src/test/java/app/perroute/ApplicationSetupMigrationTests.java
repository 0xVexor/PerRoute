package app.perroute;

import app.perroute.backend.api.*;
import app.perroute.model.*;
import app.perroute.persistence.AppStateStore;
import app.perroute.routing.AutoSelection;
import app.perroute.service.TrafficHistoryStore;
import java.nio.file.*;
import java.util.*;

public final class ApplicationSetupMigrationTests {
    private static int checks;
    private static final String MAIN = ApplicationIdentity.macExecutable("/Applications/Browser.app/Contents/MacOS/Browser");
    private static final String FAMILY = ApplicationIdentity.macAppFamily(ApplicationIdentity.path(MAIN));
    private static final String HELPER = ApplicationIdentity.macExecutable("/Applications/Browser.app/Contents/Helpers/Network.app/Contents/MacOS/Network");
    private static final ApplicationGroup GROUP = new ApplicationGroup(new ApplicationDescriptor("Browser", FAMILY), List.of(MAIN, HELPER));
    private static final BandwidthPolicy LIMIT = new BandwidthPolicy(2_000_000, 1_000_000);
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private static void refuses(Work work) throws Exception {
        try { work.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Unsafe migration was accepted");
    }
    private static AppState state(String... selected) {
        var state = new AppState(); state.appPaths().addAll(List.of(selected)); return state;
    }
    private static ApplicationSetupMigration.Plan plan(AppState state) { return ApplicationSetupMigration.preview(state, List.of(GROUP)); }
    private static ApplicationSetupMigration.Key current() { return new ApplicationSetupMigration.Key("current", FAMILY); }
    public static void main(String[] args) throws Exception {
        var exact = state(MAIN);
        var initial = plan(exact);
        check(initial.entries().size() == 1 && initial.entries().getFirst().expandsSelection(), "Exact main requires scope consent");
        refuses(() -> ApplicationSetupMigration.apply(exact, initial, Map.of()));
        check(exact.appPaths().equals(List.of(MAIN)), "Rejected migration cannot mutate original selection");
        var kept = ApplicationSetupMigration.apply(exact, initial, Map.of(current(), ApplicationSetupMigration.KEEP_EXACT));
        check(kept.appPaths().equals(exact.appPaths()) && kept.applicationHistoryAliases().isEmpty(), "Keeping exact leaves identity/history unchanged");
        var migrated = ApplicationSetupMigration.apply(exact, initial, Map.of(current(), MAIN));
        check(migrated.appPaths().equals(List.of(FAMILY)), "Explicit consent converts main to logical app");
        check(migrated.applicationHistoryAliases().get(MAIN).equals(FAMILY), "Historical exact identity remains mapped");
        check(exact.appPaths().equals(List.of(MAIN)), "Successful migration is detached too");
        var helperOnly = state(HELPER);
        check(plan(helperOnly).entries().getFirst().expandsSelection(), "Helper-only selection never silently broadens");
        refuses(() -> ApplicationSetupMigration.apply(helperOnly, plan(helperOnly), Map.of()));
        var same = state(MAIN, FAMILY, HELPER);
        for (String alias : same.appPaths()) {
            same.appRoutes().put(alias, "server-a"); same.autoApps().add(alias); same.autoPools().put(alias, "favorites");
            same.bandwidthPolicies().put(alias, LIMIT);
        }
        same.autoSelections().put(MAIN, new AutoSelection("server-a", 123));
        var equivalentPlan = plan(same);
        check(!equivalentPlan.entries().getFirst().requiresChoice(), "Existing family and equivalent settings combine without new scope");
        var combined = ApplicationSetupMigration.apply(same, equivalentPlan, Map.of());
        check(combined.appPaths().equals(List.of(FAMILY)), "Equivalent selected identities deduplicate");
        check(combined.appRoutes().equals(Map.of(FAMILY, "server-a")), "Server assignment is preserved once");
        check(combined.autoApps().equals(Set.of(FAMILY)) && "favorites".equals(combined.autoPools().get(FAMILY)), "Auto choice and pool migrate together");
        check(combined.bandwidthPolicies().get(FAMILY).equals(LIMIT) && combined.bandwidthPolicies().get(MAIN).equals(LIMIT), "Bandwidth policy migrates without summing or dropping limits");
        check(combined.autoSelections().get(FAMILY).changedAt() == 123, "Remembered Auto selection retains its timestamp");
        check(plan(combined).entries().isEmpty(), "Migration is idempotent");
        for (int conflict = 0; conflict < 4; conflict++) {
            var state = state(MAIN, FAMILY);
            switch (conflict) {
                case 0 -> state.appRoutes().put(MAIN, "different-server");
                case 1 -> state.autoApps().add(MAIN);
                case 2 -> state.autoPools().put(MAIN, "favorites");
                case 3 -> state.bandwidthPolicies().put(MAIN, LIMIT);
            }
            var preview = plan(state);
            check(preview.entries().stream().anyMatch(ApplicationSetupMigration.Entry::conflict), "Different server/mode/pool/limit creates a conflict");
            refuses(() -> ApplicationSetupMigration.apply(state, preview, Map.of()));
            var resolutions = new LinkedHashMap<ApplicationSetupMigration.Key,String>();
            preview.entries().forEach(entry -> resolutions.put(entry.key(), MAIN));
            var chosen = ApplicationSetupMigration.apply(state, preview, resolutions);
            check(chosen.appPaths().equals(List.of(FAMILY)), "Explicit conflict source applies as one logical selection");
        }
        refuses(() -> ApplicationSetupMigration.apply(same, equivalentPlan, Map.of(current(), "not-a-source")));
        refuses(() -> ApplicationSetupMigration.apply(same, equivalentPlan, Map.of(new ApplicationSetupMigration.Key("unknown", FAMILY), MAIN)));
        var stale = plan(exact); exact.bandwidthPolicies().put(MAIN, LIMIT);
        refuses(() -> ApplicationSetupMigration.apply(exact, stale, Map.of(current(), MAIN)));
        var profiled = state(MAIN);
        var rule = new RouteRule("rule-id", "Domain", MAIN, RouteRule.MatchType.DOMAIN, "example.com", RouteRule.Action.PROXY, true);
        profiled.routeRules().add(rule);
        profiled.profiles().add(new ConnectionProfile("profile-id", "Work", List.of(HELPER, FAMILY), "main-node", ConnectionMode.NATIVE_PER_APP,
                List.of(rule), true, Map.of(HELPER, "profile-server"), Set.of(HELPER)));
        profiled.setActiveProfileId("profile-id");
        var profilePlan = plan(profiled);
        check(profilePlan.entries().size() == 2, "Profiles are reviewed separately from current settings");
        refuses(() -> ApplicationSetupMigration.apply(profiled, profilePlan, Map.of(current(), MAIN)));
        check(profiled.profiles().getFirst().applicationPaths().size() == 2, "Unresolved profile conflict leaves all scopes untouched");
        var choices = Map.of(current(), MAIN, new ApplicationSetupMigration.Key("profile-id", FAMILY), HELPER);
        var next = ApplicationSetupMigration.apply(profiled, profilePlan, choices);
        check(next.profiles().getFirst().applicationPaths().equals(List.of(FAMILY)), "Profile application list migrates");
        check(next.profiles().getFirst().appRoutes().equals(Map.of(FAMILY, "profile-server")), "Profile assignment stays independent");
        check(next.profiles().getFirst().autoApps().equals(Set.of(FAMILY)), "Profile Auto identity migrates");
        check(next.activeProfileId().equals("profile-id"), "Active profile identity remains stable");
        check(next.routeRules().getFirst().applicationPath().equals(FAMILY) && next.routeRules().getFirst().id().equals("rule-id"), "Rule scope migrates without changing rule ID/order");
        check(next.profiles().getFirst().rules().getFirst().applicationPath().equals(FAMILY), "Profile rule scope migrates too");
        var store = AppStateStore.inMemory(); store.write(store.serialize(next)); var restored = store.load();
        check(restored.applicationHistoryAliases().equals(next.applicationHistoryAliases()), "Encrypted-state format retains history aliases");
        check(restored.profiles().equals(next.profiles()), "Profile migration survives state round trip");
        var backup = app.perroute.backup.BackupService.export(next, Map.of(), java.util.EnumSet.of(
                app.perroute.backup.BackupService.Section.APP_ASSIGNMENTS, app.perroute.backup.BackupService.Section.PROFILES), null, "test", 123);
        var backupPreview = app.perroute.backup.BackupService.preview(backup, null, new AppState(), path -> true, List.of());
        var backupState = app.perroute.backup.BackupService.plan(backupPreview, new AppState(), Map.of(),
                app.perroute.backup.BackupService.Mode.REPLACE, Map.of(), path -> true).state();
        check(backupState.applicationHistoryAliases().equals(next.applicationHistoryAliases()), "Backup preserves logical history associations");
        check(backupState.appPaths().equals(next.appPaths()) && backupState.profiles().getFirst().applicationPaths().equals(List.of(FAMILY)),
                "Backup preserves logical selection and profile identities");
        check(store.parse(store.serialize(new AppState())).applicationHistoryAliases().isEmpty(), "Older/no-alias state remains valid");
        var mixed = state(MAIN, FAMILY);
        mixed.bandwidthPolicies().put(MAIN, LIMIT); mixed.bandwidthPolicies().put(HELPER, new BandwidthPolicy(4_000_000, 0));
        mixed.autoPools().put(HELPER, "favorites");
        mixed.profiles().add(new ConnectionProfile("exact-profile", "Keep helper", List.of(HELPER), "", ConnectionMode.NATIVE_PER_APP, List.of()));
        var mixedPlan = plan(mixed);
        var mixedChoices = new LinkedHashMap<ApplicationSetupMigration.Key,String>();
        mixedPlan.entries().forEach(entry -> mixedChoices.put(entry.key(), entry.key().scope().equals("exact-profile") ? ApplicationSetupMigration.KEEP_EXACT : MAIN));
        var mixedNext = ApplicationSetupMigration.apply(mixed, mixedPlan, mixedChoices);
        check(mixedNext.profiles().getFirst().applicationPaths().equals(List.of(HELPER)), "Keeping an exact profile preserves its executable selection");
        check(mixedNext.bandwidthPolicies().get(HELPER).equals(mixed.bandwidthPolicies().get(HELPER)), "Kept exact profile retains its separate global limit");
        check(mixedNext.bandwidthPolicies().get(FAMILY).equals(LIMIT), "Combined app receives explicitly chosen shared policy");
        var mixedRestored = store.parse(store.serialize(mixedNext));
        check("favorites".equals(mixedRestored.autoPools().get(HELPER)), "Dormant exact-profile Auto pool survives restart");
        mixedChoices.put(new ApplicationSetupMigration.Key("shared", FAMILY), ApplicationSetupMigration.KEEP_EXACT);
        refuses(() -> ApplicationSetupMigration.apply(mixed, mixedPlan, mixedChoices));
        var reserved = state(MAIN);
        reserved.profiles().add(new ConnectionProfile("current", "Bad identity", List.of(MAIN), "", ConnectionMode.NATIVE_PER_APP, List.of()));
        refuses(() -> plan(reserved));
        var windows = state("C:\\Apps\\Browser.exe"); windows.appRoutes().put("c:\\apps\\browser.exe", "windows-server");
        check(plan(windows).entries().isEmpty(), "Windows identities never enter macOS migration");
        check(ApplicationSetupMigration.apply(windows, plan(windows), Map.of()).appRoutes().equals(windows.appRoutes()), "Windows assignments remain unchanged");
        refuses(() -> ApplicationSetupMigration.preview(exact, List.of(GROUP,
                new ApplicationGroup(new ApplicationDescriptor("Other", ApplicationIdentity.macAppFamily("/Other.app/Contents/MacOS/Other")), List.of(MAIN)))));
        refuses(() -> ApplicationSetupMigration.historyIdentity(MAIN, Map.of(MAIN, FAMILY, FAMILY, MAIN)));
        Path historyFile = Files.createTempFile("perroute-migration-history-", ".json");
        try {
            try (var history = new TrafficHistoryStore(historyFile)) {
                history.record(MAIN, 100, 10); history.record(HELPER, 200, 20); history.record(FAMILY, 300, 30);
                var aliases = Map.of(MAIN, FAMILY, HELPER, FAMILY);
                var snapshot = history.snapshot(aliases);
                check(snapshot.applications().size() == 1 && snapshot.monthDownloadBytes() == 600, "Historical gateway identities aggregate without lost/double bytes");
                check(history.report(FAMILY, aliases).monthUploadBytes() == 60, "Logical app report includes all historical uploads");
                check(history.snapshot().applications().size() == 3, "Raw historical records remain intact for restore/older versions");
            }
            try (var history = new TrafficHistoryStore(historyFile)) {
                check(history.snapshot(restored.applicationHistoryAliases()).applications().size() == 1, "Restarted history uses persisted aliases");
            }
        } finally { Files.deleteIfExists(historyFile); }
        System.out.println("Application setup migration: " + checks + " checks passed; detached state and disposable history only");
    }
}
