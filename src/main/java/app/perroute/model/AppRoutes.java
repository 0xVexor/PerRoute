package app.perroute.model;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class AppRoutes {
    private AppRoutes() {}

    /** Legacy Windows normalization is unchanged; tagged macOS identities remain exact on every host. */
    public static String normalize(String applicationPath) {
        if (applicationPath == null || applicationPath.isBlank()) return "";
        if (ApplicationIdentity.isMac(applicationPath)) {
            String path = ApplicationIdentity.path(applicationPath);
            return ApplicationIdentity.isMacFamily(applicationPath) ? ApplicationIdentity.macAppFamily(path) : ApplicationIdentity.macExecutable(path);
        }
        String text = applicationPath.trim();
        try { text = Path.of(text).toAbsolutePath().normalize().toString(); }
        catch (RuntimeException ignored) { }
        return text.replace('/', '\\').toLowerCase(Locale.ROOT);
    }

    public static Map<String, String> normalized(Map<String, String> routes) {
        Map<String, String> result = new LinkedHashMap<>();
        if (routes == null) return result;
        routes.forEach((path, nodeKey) -> {
            String key = normalize(path);
            if (!key.isBlank() && nodeKey != null && !nodeKey.isBlank()) result.put(key, nodeKey);
        });
        return result;
    }
}
