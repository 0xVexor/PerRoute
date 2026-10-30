package app.perroute;

import app.perroute.backend.api.*;
import app.perroute.compose.controller.PerRouteController;
import app.perroute.model.*;
import app.perroute.persistence.*;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public final class ApplicationMigrationControllerTests {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        String previousData = System.getProperty("perroute.data");
        Path root = Files.createTempDirectory("perroute-migration-controller-").toRealPath();
        try {
            scenario(root.resolve("success"), false);
            scenario(root.resolve("failed-write"), true);
            System.out.println("Application migration controller: " + checks + " checks passed; fake backend and disposable state only");
        } finally {
            if (previousData == null) System.clearProperty("perroute.data"); else System.setProperty("perroute.data", previousData);
            try (var files = Files.walk(root)) { for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
        }
    }
    private static void scenario(Path root, boolean failWrite) throws Exception {
        Files.createDirectories(root); System.setProperty("perroute.data", root.toString());
        Path executable = root.resolve("App.app/Contents/MacOS/App");
        Files.createDirectories(executable.getParent()); Files.writeString(executable, "not executed");
        String exact = ApplicationIdentity.macExecutable(executable.toString()), family = ApplicationIdentity.macAppFamily(executable.toString());
        var group = new ApplicationGroup(new ApplicationDescriptor("App", family), List.of(exact));
        var groups = new AtomicReference<>(List.of(group));
        var delegate = new ControllerTests.FakeBackend();
        ConnectionBackend backend = (ConnectionBackend) Proxy.newProxyInstance(ConnectionBackend.class.getClassLoader(), new Class<?>[]{ConnectionBackend.class}, (proxy, method, values) -> {
            return switch (method.getName()) {
                case "supportsApplicationGrouping" -> true;
                case "applicationGroups" -> groups.get();
                case "capabilities" -> BackendCapabilities.macosPerApp();
                case "supportsExplorerMenu", "supportsWorkspaces", "supportsLanBridge", "supportsRecipeImport" -> false;
                case "restoreApplication", "selectApplication" -> values[0];
                default -> { try { yield method.invoke(delegate, values); } catch (InvocationTargetException error) { throw error.getCause(); } }
            };
        });
        var state = new AppState(); state.appPaths().add(exact);
        var preferences = new ControllerTests.MemoryPreferences();
        preferences.putBoolean("v3.initialized", true); preferences.putBoolean("v3.qualityBackground", false);
        preferences.putBoolean("v3.routeMonitoring", false); preferences.putBoolean("autoUpdate", false);
        AppStateStore store = failWrite ? new AppStateStore(root.resolve("state.bin"), new StateCipher() {
            public byte[] protect(byte[] plain) throws IOException { throw new IOException("fixture: encrypted write failed"); }
            public byte[] unprotect(byte[] encrypted) { return encrypted; }
        }) : AppStateStore.inMemory();
        try (var controller = new PerRouteController(state, store, backend, preferences)) {
            var preview = controller.previewApplicationMigration();
            var choices = Map.of(preview.getPlan().entries().getFirst().key(), exact);
            if (failWrite) {
                var result = controller.applyApplicationMigration(preview, choices);
                check(!result.getOk() && result.getMessage().contains("encrypted write failed"), "Encrypted save failure is reported");
                check(state.appPaths().equals(List.of(exact)), "Failed save does not publish changed selections");
                check(state.applicationHistoryAliases().isEmpty(), "Failed save does not change history grouping");
                return;
            }
            var unchanged = controller.previewApplicationMigration();
            check(unchanged.getSettings().equals(preview.getSettings()), "Preview comparison ignores serialization timestamp comments");
            check(!controller.applyApplicationMigration(preview, Map.of()).getOk(), "Missing scope consent is rejected by controller");
            state.setAdvancedRouting(true);
            check(!controller.applyApplicationMigration(preview, choices).getOk(), "Changed saved settings invalidate preview");
            check(state.appPaths().equals(List.of(exact)), "Stale preview has no partial side effects");
            preview = controller.previewApplicationMigration();
            groups.set(List.of(new ApplicationGroup(new ApplicationDescriptor("Changed", family), List.of(exact))));
            check(!controller.applyApplicationMigration(preview, choices).getOk(), "Changed inventory invalidates preview");
            groups.set(List.of(group)); preview = controller.previewApplicationMigration();
            check(controller.applyApplicationMigration(preview, choices).getOk(), "Reviewed migration succeeds through controller");
            check(controller.applications().equals(List.of(family)), "Controller publishes logical identity only after save");
            check(store.load().appPaths().equals(List.of(family)), "Persistent state has the same logical identity");
            check(store.load().advancedRouting(), "Other routing settings survive migration");
            check(store.load().applicationHistoryAliases().get(exact).equals(family), "Stored history alias survives reload");
            check(!controller.applyApplicationMigration(preview, choices).getOk(), "Reusing a stale applied preview is rejected");
            check(delegate.startCount == 0 && delegate.proxyApplications == 0, "Migration never starts a core or changes network settings");
        }
    }
}
