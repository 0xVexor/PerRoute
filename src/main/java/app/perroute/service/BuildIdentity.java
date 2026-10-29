package app.perroute.service;

import java.util.Properties;

/** Source-based identity shared by the UI, diagnostics and native package metadata. */
public final class BuildIdentity {
    private static final Properties VALUES = read();
    private BuildIdentity() { }
    private static Properties read() {
        var values = new Properties();
        try (var input = BuildIdentity.class.getResourceAsStream("/perroute-build.properties")) {
            if (input != null) values.load(input);
        } catch (java.io.IOException ignored) { }
        return values;
    }
    public static String number() { return VALUES.getProperty("number", "dev"); }
    public static String revision() { return VALUES.getProperty("revision", "unknown"); }
    public static String displayVersion() {
        String revision = revision();
        return SecureUpdateService.CURRENT_VERSION + " (build " + number() + ", " +
                revision.substring(0, Math.min(12, revision.length())) + ")";
    }
}
