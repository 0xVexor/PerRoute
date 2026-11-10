package app.perroute.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Remembers problems that were handled by falling back, so a fallback never hides its cause. Recent issues are kept in
 * memory for the Doctor and appended to a small file for support; recording never throws.
 */
public final class IssueLog {
    public record Issue(Instant at, String source, String message) {}

    private static final int MEMORY_LIMIT = 100;
    private static final long FILE_LIMIT_BYTES = 256 * 1024;
    private static final Deque<Issue> RECENT = new ArrayDeque<>();
    private static volatile Path file;

    private IssueLog() {}

    /** Records a handled problem. {@code source} names the feature, e.g. "app-discovery". */
    public static void record(String source, String message, Throwable cause) {
        String text = cause == null ? message : message + ": " + describe(cause);
        Issue issue = new Issue(Instant.now(), source, text);
        synchronized (RECENT) {
            if (RECENT.size() >= MEMORY_LIMIT) RECENT.removeFirst();
            RECENT.addLast(issue);
        }
        append(issue);
    }

    public static void record(String source, String message) { record(source, message, null); }

    /** Issues from one feature, newest last. */
    public static List<Issue> recent(String source) {
        synchronized (RECENT) { return RECENT.stream().filter(issue -> issue.source().equals(source)).toList(); }
    }

    public static List<Issue> recent() {
        synchronized (RECENT) { return List.copyOf(RECENT); }
    }

    /** Forgets recorded issues and redirects the file (null restores the default); used by tests. */
    public static void reset(Path target) {
        synchronized (RECENT) { RECENT.clear(); }
        file = target;
    }

    private static String describe(Throwable cause) {
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : " - " + message);
    }

    private static void append(Issue issue) {
        try {
            Path target = file;
            if (target == null) target = file = AppDirectories.dataDirectory().resolve("issues.log");
            Files.createDirectories(target.getParent());
            if (Files.isRegularFile(target) && Files.size(target) > FILE_LIMIT_BYTES) {
                Files.move(target, target.resolveSibling("issues.old.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(target, issue.at() + " [" + issue.source() + "] " + issue.message() + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // The log must never become a new failure; the in-memory copy above is still available to the Doctor.
        }
    }
}
