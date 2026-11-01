package app.perroute.model;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** Persistent macOS identities retain the exact executable path, independent of the host reading them. */
public final class ApplicationIdentity {
    public static final String MACOS_EXECUTABLE = "macos-executable:";
    private ApplicationIdentity() {}

    public static final String MACOS_APP_FAMILY = "macos-app-family:";
    public static boolean isMacFamily(String value) { return value != null && value.startsWith(MACOS_APP_FAMILY); }
    public static boolean isMac(String value) { return value != null && (value.startsWith(MACOS_EXECUTABLE) || isMacFamily(value)); }

    /** Explicit opt-in to the discovered main executable and its contained helpers, resolved at Connect. */
    public static String macAppFamily(String mainExecutable) {
        macExecutable(mainExecutable);
        return MACOS_APP_FAMILY + mainExecutable;
    }

    public static String macExecutable(String path) {
        if (path == null || !path.startsWith("/") || path.endsWith("/") || path.contains("\\") ||
                !StandardCharsets.UTF_8.newEncoder().canEncode(path) || path.getBytes(StandardCharsets.UTF_8).length > 8192 ||
                path.chars().anyMatch(Character::isISOControl) ||
                Arrays.stream(path.substring(1).split("/", -1)).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..")))
            throw new IllegalArgumentException("Expected an exact macOS executable path");
        return MACOS_EXECUTABLE + path;
    }

    public static String path(String identity) {
        if (!isMac(identity)) return identity;
        String path = identity.substring(isMacFamily(identity) ? MACOS_APP_FAMILY.length() : MACOS_EXECUTABLE.length());
        macExecutable(path);
        return path;
    }

    public static String comparisonKey(String identity) {
        return isMac(identity) ? identity : identity.toLowerCase(Locale.ROOT);
    }

    public static boolean same(String first, String second) {
        if (first == null || second == null) return first == second;
        return isMac(first) || isMac(second) ? first.equals(second) : first.equalsIgnoreCase(second);
    }
}
