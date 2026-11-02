package app.perroute;

import app.perroute.model.ApplicationIdentity;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Locale;

/** Real host files use host identities; Windows literals remain in explicit Windows contract tests. */
final class RegressionFixtures {
    private RegressionFixtures() {}

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    static String application(String windowsPath) {
        return windows() ? windowsPath : ApplicationIdentity.macExecutable(
                "/Applications/RegressionFixtures/" + windowsPath.substring(windowsPath.lastIndexOf('\\') + 1));
    }

    static String identity(Path file) throws IOException {
        String path = file.toRealPath().toString();
        return windows() ? path : ApplicationIdentity.macExecutable(path);
    }
}
