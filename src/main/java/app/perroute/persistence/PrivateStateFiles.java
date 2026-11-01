package app.perroute.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/** Private directories and fresh temporary files prevent broad umasks or stale symlinks exposing state. */
public final class PrivateStateFiles {
    private PrivateStateFiles() { }
    public static Path directory(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        // Reject symlinks in every existing component before creating anything underneath it.
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("State directory must not contain symbolic links");
        }
        Files.createDirectories(absolute, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) throw new IOException("State directory is invalid");
        Files.setPosixFilePermissions(absolute, PosixFilePermissions.fromString("rwx------"));
        return absolute;
    }
    public static void requireRegular(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("State file must be a regular file");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
    public static void write(Path file, byte[] bytes) throws IOException {
        Path parent = directory(file.toAbsolutePath().getParent());
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) requireRegular(file);
        Path temporary = Files.createTempFile(parent, ".state-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // No non-atomic fallback on macOS: a failed replacement must preserve the previous state.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
