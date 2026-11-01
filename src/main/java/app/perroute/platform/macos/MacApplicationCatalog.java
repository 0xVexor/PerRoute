package app.perroute.platform.macos;

import app.perroute.util.Json;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Read-only executable candidates; signing metadata is observational, not routing authority. */
public final class MacApplicationCatalog {
    public record Application(String name, Path bundlePath, Path executablePath, String bundleIdentifier,
                              String signingIdentifier, String teamIdentifier, boolean signatureValid) { }
    public record Snapshot(List<Application> applications, List<String> warnings) {
        public Snapshot { applications = List.copyOf(applications); warnings = List.copyOf(warnings); }
    }
    @FunctionalInterface public interface Source { String read(String operation) throws IOException; }
    private final Source source;
    public MacApplicationCatalog(Source source) { this.source = Objects.requireNonNull(source); }
    public static MacApplicationCatalog system() {
        return new MacApplicationCatalog(operation -> {
            String resources = System.getProperty("compose.application.resources.dir");
            Path library = resources == null ? Path.of("build/macos-desktop/libPerRouteDesktopBridge.dylib")
                    : Path.of(resources).resolve("../../Frameworks/libPerRouteDesktopBridge.dylib").normalize();
            try {
                var bridge = Native.load(library.toAbsolutePath().toString(), Bridge.class);
                Pointer value = bridge.perroute_catalog_request(operation);
                if (value == null) throw new IOException("Application catalog returned no data");
                try { return value.getString(0, "UTF-8"); } finally { bridge.perroute_catalog_free(value); }
            } catch (LinkageError failure) { throw new IOException("macOS application discovery is unavailable", failure); }
        });
    }
    public Snapshot installed() throws IOException { return read("installed"); }
    public Snapshot running() throws IOException { return read("running"); }
    private Snapshot read(String operation) throws IOException {
        try {
            String text = source.read(operation);
            if (text == null || text.length() > 4_194_304) throw new IllegalArgumentException("Oversized catalog");
            var data = Json.object(text);
            if (!(data.get("version") instanceof Number version) || version.doubleValue() != 1.0 ||
                    !(data.get("applications") instanceof List<?> rows) || rows.size() > 4096 ||
                    !(data.get("warnings") instanceof List<?> warnings)) throw new IllegalArgumentException("Invalid catalog response");
            var apps = new TreeMap<String, Application>();
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> values) || !(values.get("signatureValid") instanceof Boolean valid))
                    throw new IllegalArgumentException("Invalid application metadata");
                Path bundle = absolute(values, "bundlePath"), executable = absolute(values, "executablePath");
                if (!bundle.toString().endsWith(".app") || !executable.startsWith(bundle.resolve("Contents/MacOS")))
                    throw new IllegalArgumentException("Executable outside application bundle");
                var app = new Application(string(values, "name", false), bundle, executable,
                        string(values, "bundleIdentifier", false), string(values, "signingIdentifier", true),
                        string(values, "teamIdentifier", true), valid);
                if (apps.putIfAbsent(bundle.toString(), app) != null) throw new IllegalArgumentException("Duplicate bundle path");
            }
            var messages = new ArrayList<String>();
            for (Object warning : warnings) {
                if (!(warning instanceof String message) || message.length() > 1024) throw new IllegalArgumentException("Invalid catalog warning");
                messages.add(message);
            }
            return new Snapshot(new ArrayList<>(apps.values()), messages);
        } catch (IllegalArgumentException failure) { throw new IOException("Invalid macOS application catalog", failure); }
    }
    private static Path absolute(Map<?, ?> values, String key) {
        Path path = Path.of(string(values, key, false));
        if (!path.isAbsolute() || !path.normalize().equals(path)) throw new IllegalArgumentException("Noncanonical application path");
        return path;
    }
    private static String string(Map<?, ?> values, String key, boolean empty) {
        if (!(values.get(key) instanceof String value) || (!empty && value.isBlank()) || value.length() > 8192)
            throw new IllegalArgumentException("Invalid application field");
        return value;
    }
    private interface Bridge extends Library {
        Pointer perroute_catalog_request(String operation);
        void perroute_catalog_free(Pointer value);
    }
}
