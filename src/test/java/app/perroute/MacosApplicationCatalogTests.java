package app.perroute;

import app.perroute.platform.macos.MacApplicationCatalog;
import app.perroute.util.Json;
import java.util.*;
import java.io.IOException;

public final class MacosApplicationCatalogTests {
    static int checks;
    static Map<String, Object> app(String path) {
        return new LinkedHashMap<>(Map.of("name", "Editor فارسی", "bundlePath", path,
                "executablePath", path + "/Contents/MacOS/Editor", "bundleIdentifier", "example.editor",
                "signingIdentifier", "", "teamIdentifier", "", "signatureValid", false));
    }
    static MacApplicationCatalog catalog(List<?> apps, List<?> warnings) {
        return new MacApplicationCatalog(op -> Json.stringify(Map.of("version", 1, "applications", apps, "warnings", warnings)));
    }
    public static void main(String[] args) throws Exception {
        var upper = app("/Applications/Editor.app");
        var lower = app("/Applications/editor.app");
        var snapshot = catalog(List.of(upper, lower), List.of("Partial inventory")).installed();
        check(snapshot.applications().size() == 2, "Case-sensitive bundle paths remain distinct despite duplicate bundle IDs");
        check(snapshot.applications().getFirst().name().equals("Editor فارسی"), "Unicode labels preserved");
        check(!snapshot.applications().getFirst().signatureValid(), "Unsigned observations are explicit, not fabricated trusted identities");
        check(snapshot.warnings().equals(List.of("Partial inventory")), "Incomplete inventory warning preserved");
        try { snapshot.applications().clear(); throw new AssertionError("Mutable snapshot"); } catch (UnsupportedOperationException expected) { checks++; }
        try { snapshot.warnings().clear(); throw new AssertionError("Mutable warnings"); } catch (UnsupportedOperationException expected) { checks++; }
        fail(() -> catalog(List.of(upper, upper), List.of()).installed());
        for (var mutation : List.of(Map.<String, Object>of("bundlePath", "relative.app"),
                Map.<String, Object>of("executablePath", "/tmp/other"),
                Map.<String, Object>of("bundlePath", "/Applications/../Editor.app"),
                Map.<String, Object>of("signatureValid", "true"),
                Map.<String, Object>of("bundleIdentifier", ""))) {
            var invalid = new LinkedHashMap<>(upper); invalid.putAll(mutation);
            fail(() -> catalog(List.of(invalid), List.of()).installed());
        }
        fail(() -> catalog(List.of(upper), List.of(1)).running());
        fail(() -> new MacApplicationCatalog(op -> "{broken").installed());
        fail(() -> new MacApplicationCatalog(op -> "{\"error\":\"unavailable\"}").installed());
        fail(() -> new MacApplicationCatalog(op -> " ".repeat(4_194_305)).installed());
        var helper = app("/Applications/Editor.app/Contents/Frameworks/Network.app");
        helper.put("name", "Editor — Network Helper");
        var family = catalog(List.of(upper, helper), List.of()).installed();
        check(family.applications().size() == 2, "Main and helper remain independently selectable");
        check(family.applications().stream().anyMatch(a -> a.name().equals("Editor — Network Helper")), "Owner label preserved");
        check(family.applications().stream().map(a -> a.executablePath().toString()).distinct().count() == 2,
                "Helper keeps its own exact executable authority");
        var operations = new ArrayList<String>();
        var source = new MacApplicationCatalog(op -> { operations.add(op); return "{\"version\":1,\"applications\":[],\"warnings\":[]}"; });
        source.installed(); source.running();
        check(operations.equals(List.of("installed", "running")), "Separate fixed read-only operations");
        if (System.getProperty("os.name").equals("Mac OS X")) {
            var real = MacApplicationCatalog.system();
            var installed = real.installed(); var running = real.running();
            check(!installed.applications().isEmpty(), "Known macOS application roots produce an inventory");
            check(installed.warnings().isEmpty(), "Installed inventory is complete enough for the picker");
            var safariAlias = java.nio.file.Path.of("/Applications/Safari.app");
            if (java.nio.file.Files.exists(safariAlias)) {
                var safariExecutable = safariAlias.resolve("Contents/MacOS/Safari").toRealPath();
                check(installed.applications().stream().anyMatch(a -> a.executablePath().equals(safariExecutable)),
                        "Safari's hidden Cryptex alias yields its canonical executable");
            }
            check(running.applications().stream().allMatch(a -> a.bundlePath().isAbsolute()), "Running bundles use absolute identities");
            System.out.println("Read-only native catalog: " + installed.applications().size() + " installed, " + running.applications().size() + " running; no names or paths logged");
        }
        System.out.println("Application catalog: " + checks + " checks passed");
    }
    interface Work { void run() throws Exception; }
    static void fail(Work work) throws Exception { try { work.run(); throw new AssertionError("Invalid catalog accepted"); } catch (IOException expected) { checks++; } }
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
