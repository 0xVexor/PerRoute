package app.perroute.platform.macos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;

/** A private directory for runtime configs and histories; no persistent state/key fallback is used. */
public final class MacPreviewSession implements AutoCloseable {
    private final Path directory;
    private final String previousData;
    private boolean closed;
    private MacPreviewSession(Path directory) {
        this.directory = directory;
        previousData = System.getProperty("perroute.data");
        System.setProperty("perroute.data", directory.toString());
    }
    public static MacPreviewSession open() throws IOException {
        return new MacPreviewSession(Files.createTempDirectory("perroute-macos-local-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))));
    }
    public Path directory() { return directory; }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        if (previousData == null) System.clearProperty("perroute.data");
        else System.setProperty("perroute.data", previousData);
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
