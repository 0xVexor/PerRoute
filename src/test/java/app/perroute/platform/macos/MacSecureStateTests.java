package app.perroute.platform.macos;

import app.perroute.model.*;
import app.perroute.persistence.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

public final class MacSecureStateTests {
    private static int assertions;
    private static final String SECRET = "fixture-only-server-credential";
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("perroute-secure-test-").toRealPath();
        try {
            roundTrip(root.resolve("roundtrip"));
            failures(root.resolve("failures"));
            recoveryKeyGuard(root.resolve("recovery"));
            keyRace(root.resolve("race"));
            symlinks(root.resolve("links"));
            failedSave(root.resolve("save-failure"));
            System.out.println("macOS secure state checks passed (" + assertions + " assertions)");
        } finally { deleteTree(root); }
    }
    static void roundTrip(Path root) throws Exception {
        var keys = new FakeKeychain();
        byte[] first;
        try (var session = MacStateSession.open(root, keys)) {
            check(session.state().connectionMode() == ConnectionMode.BROWSER_PROXY, "New state defaults to local proxy");
            session.state().nodes().add(new ProxyNode("one", "One", Protocol.VLESS, "127.0.0.1", 1234, SECRET, Map.of(), ""));
            session.state().setSelectedNodeKey("one");
            session.store().write(session.store().serialize(session.state()));
            session.preferences().putBoolean("darkTheme", false);
            session.preferences().put("subscriptionFeed", SECRET);
            first = Files.readAllBytes(root.resolve("state.bin"));
            session.store().write(session.store().serialize(session.state()));
            check(!Arrays.equals(first, Files.readAllBytes(root.resolve("state.bin"))), "Each save uses a fresh nonce");
            check(!new String(first, java.nio.charset.StandardCharsets.ISO_8859_1).contains(SECRET), "State does not contain the plaintext credential");
            check(!Files.readString(root.resolve("preferences.bin"), java.nio.charset.StandardCharsets.ISO_8859_1).contains(SECRET), "Preferences are encrypted too");
            check(Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------")), "Directory is owner-only");
            check(Files.getPosixFilePermissions(root.resolve("state.bin")).equals(PosixFilePermissions.fromString("rw-------")), "State is owner-only");
            expectFailure(() -> MacStateSession.open(root, keys), "Concurrent session is rejected");
            Path snapshot = root.resolve("snapshot.bin");
            var sibling = session.store().sibling(snapshot);
            sibling.write(sibling.serialize(session.state()));
            check(sibling.load().nodes().getFirst().credential().equals(SECRET), "Restore snapshots use the same platform cipher");
            check(!Files.exists(root.resolve("snapshot.bin.key")), "No snapshot fallback key");
        }
        try (var session = MacStateSession.open(root, keys)) {
            check(session.state().nodes().getFirst().credential().equals(SECRET), "Server survives session restart");
            check(session.state().selectedNodeKey().equals("one"), "Selection survives restart");
            check(!session.preferences().getBoolean("darkTheme", true), "Appearance survives restart");
            check(session.preferences().get("subscriptionFeed", "").equals(SECRET), "Sensitive preference survives restart");
            check(!session.preferences().getBoolean("autoReconnect", true), "Safe default remains disabled");
        }
        check(keys.adds == 1, "Restart reuses the existing Keychain item");
        try (var files = Files.list(root)) {
            check(files.noneMatch(p -> p.toString().endsWith(".key") || p.toString().endsWith(".tmp")), "No raw key or stale temporary files");
        }
    }
    static void failures(Path root) throws Exception {
        var keys = new FakeKeychain();
        try (var session = MacStateSession.open(root, keys)) { session.store().write(session.store().serialize(session.state())); }
        Path file = root.resolve("state.bin");
        byte[] original = Files.readAllBytes(file);
        byte[] originalPrefs = Files.readAllBytes(root.resolve("preferences.bin"));
        keys.fail = true;
        expectFailure(() -> MacStateSession.open(root, keys), "Denied/locked Keychain aborts startup");
        check(Arrays.equals(original, Files.readAllBytes(file)), "Denial preserves state bytes");
        keys.fail = false;
        byte[] key = keys.key;
        keys.key = null;
        expectFailure(() -> MacStateSession.open(root, keys), "Missing key does not create a replacement for existing state");
        check(keys.adds == 1, "Missing existing key does not call add");
        keys.key = new byte[32];
        expectFailure(() -> MacStateSession.open(root, keys), "Wrong key aborts startup");
        keys.key = key;
        byte[] corrupt = original.clone(); corrupt[corrupt.length - 1] ^= 1;
        Files.write(file, corrupt);
        expectFailure(() -> MacStateSession.open(root, keys), "Authentication failure aborts startup");
        check(Arrays.equals(corrupt, Files.readAllBytes(file)), "Corrupt state is not overwritten");
        check(Arrays.equals(originalPrefs, Files.readAllBytes(root.resolve("preferences.bin"))), "Failed state load does not rewrite preferences");
        Files.write(file, new byte[]{'A','G','C','1', 0});
        expectFailure(() -> MacStateSession.open(root, keys), "Legacy local/Windows envelope is rejected");
        Files.write(file, original);
        Files.write(root.resolve("preferences.bin"), new byte[]{'M','A','C','1'});
        expectFailure(() -> MacStateSession.open(root, keys), "Truncated preferences abort startup");
        Files.write(root.resolve("preferences.bin"), originalPrefs);
        try (var session = MacStateSession.open(root, keys)) { check(session.state() != null, "Lock released after every failure"); }
        var unavailable = new FakeKeychain(); unavailable.fail = true;
        Path fresh = root.resolve("unavailable");
        expectFailure(() -> MacStateSession.open(fresh, unavailable), "Unavailable Keychain cannot fall back on first run");
        check(!Files.exists(fresh.resolve("state.bin")) && !Files.exists(fresh.resolve("preferences.bin")), "No encrypted or plaintext data appears on unavailable first run");
    }
    static void recoveryKeyGuard(Path root) throws Exception {
        Files.createDirectories(root);
        Path journal = root.resolve("proxy-recovery.bin");
        byte[] evidence = new byte[]{1, 2, 3};
        Files.write(journal, evidence);
        var keys = new FakeKeychain();
        expectFailure(() -> MacStateSession.open(root, keys), "Recovery-only directory cannot replace its lost Keychain key");
        check(keys.adds == 0, "Recovery journal prevents creating a replacement key");
        check(Arrays.equals(evidence, Files.readAllBytes(journal)), "Unrecoverable journal bytes are preserved");
    }
    static void keyRace(Path root) throws Exception {
        var keys = new FakeKeychain() {
            @Override public boolean add(String account, byte[] candidate) { adds++; key = new byte[32]; Arrays.fill(key, (byte) 7); return false; }
        };
        try (var session = MacStateSession.open(root, keys)) { session.store().write(session.store().serialize(session.state())); }
        try (var session = MacStateSession.open(root, keys)) { check(session.state() != null, "Duplicate add re-reads winner's key"); }
    }
    static void symlinks(Path root) throws Exception {
        Files.createDirectories(root);
        Path external = root.resolve("external"); Files.createDirectories(external);
        Path linked = root.resolve("linked"); Files.createSymbolicLink(linked, external);
        expectFailure(() -> MacStateSession.open(linked, new FakeKeychain()), "Symlinked state directory rejected");
        var keys = new FakeKeychain();
        Path data = root.resolve("data");
        try (var session = MacStateSession.open(data, keys)) { session.store().write(session.store().serialize(session.state())); }
        Path target = root.resolve("target"); Files.writeString(target, "preserve");
        Files.delete(data.resolve("state.bin")); Files.createSymbolicLink(data.resolve("state.bin"), target);
        expectFailure(() -> MacStateSession.open(data, keys), "Symlinked state file rejected");
        check(Files.readString(target).equals("preserve"), "Symlink target is untouched");
    }
    static void failedSave(Path root) throws Exception {
        PrivateStateFiles.directory(root);
        Path file = root.resolve("state.bin");
        byte[] before = "existing-data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, before);
        var failing = new StateCipher() {
            public byte[] protect(byte[] value) throws IOException { throw new IOException("Fixture encryption failure"); }
            public byte[] unprotect(byte[] value) { return value; }
        };
        boolean failed = false;
        try { new AppStateStore(file, failing).write(new byte[]{1}); } catch (IOException expected) { failed = true; }
        check(failed && Arrays.equals(before, Files.readAllBytes(file)), "Failed encryption preserves the old file");
        try (var files = Files.list(root)) { check(files.count() == 1, "Failed encryption creates no temporary or raw-key file"); }
        Path legacy = root.resolve("legacy"); PrivateStateFiles.directory(legacy);
        Files.write(legacy.resolve("state.bin.key"), new byte[32]);
        var keys = new FakeKeychain();
        expectFailure(() -> MacStateSession.open(legacy, keys), "Legacy raw key requires explicit migration");
        check(keys.adds == 0, "Legacy rejection occurs before a new Keychain identity is created");
        var failedReadback = new FakeKeychain() {
            int reads;
            @Override public byte[] read(String account) throws IOException {
                if (++reads > 1) throw new IOException("Fixture read-back denial");
                return null;
            }
        };
        Path unreadable = root.resolve("readback");
        expectFailure(() -> MacStateSession.open(unreadable, failedReadback), "New key must pass read-back before any data is saved");
        check(!Files.exists(unreadable.resolve("state.bin")) && !Files.exists(unreadable.resolve("preferences.bin")), "Failed key read-back creates no state or preferences");
    }
    static class FakeKeychain implements StateKeychain {
        byte[] key; int adds; boolean fail;
        @Override public byte[] read(String account) throws IOException {
            if (fail) throw new IOException("Fixture denial");
            return key == null ? null : key.clone();
        }
        @Override public boolean add(String account, byte[] value) throws IOException {
            if (fail) throw new IOException("Fixture denial");
            adds++;
            if (key != null) return false;
            key = value.clone(); return true;
        }
    }
    interface Operation { AutoCloseable run() throws Exception; }
    static void expectFailure(Operation operation, String message) throws Exception {
        boolean failed = false;
        try (var ignored = operation.run()) { } catch (IOException expected) { failed = true; }
        check(failed, message);
    }
    static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
}
