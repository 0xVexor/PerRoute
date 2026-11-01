package app.perroute.platform.macos.proxy;

import app.perroute.platform.macos.MacStateCipher;
import app.perroute.util.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static app.perroute.platform.macos.proxy.ProxyJournal.*;

/** No native settings adapter exists in this fixture; every network service is an in-memory dictionary. */
public final class SystemProxyOwnershipTests {
    private static int assertions;
    private static final int HTTP = 37891, SOCKS = 37892;
    private static final String WIFI = "service-wifi-id", ETHERNET = "service-ethernet-id";

    public static void main(String[] args) throws Exception {
        lifecycle();
        preflight();
        externalEdits();
        partialFailures();
        journalFailures();
        cancellation();
        encryptedJournal();
        System.out.println("System proxy ownership checks passed (" + assertions + " assertions; simulated settings only)");
    }

    private static void lifecycle() throws Exception {
        var f = new Fixture();
        var original = f.port.read(WIFI).configured();
        f.port.add(ETHERNET, new ProxySettings(Map.of()));
        f.port.add("new-service", new ProxySettings(Map.of("HTTPEnable", 0)));
        f.manager.apply(List.of(WIFI, ETHERNET), HTTP, SOCKS);
        var lease = f.journal.load().orElseThrow();
        check(lease.entries().size() == 2, "Explicit service scope is journaled");
        check(f.port.read(WIFI).configured().values().get("HTTPProxy").equals("127.0.0.1"), "Loopback endpoint applied");
        check(f.port.read(WIFI).configured().values().get("ExceptionsList").equals(original.values().get("ExceptionsList")), "Bypass preserved");
        check(f.port.read(WIFI).configured().values().get("ProxyAutoConfigURLString").equals(original.values().get("ProxyAutoConfigURLString")), "PAC URL preserved");
        check(f.port.read("new-service").configured().values().size() == 1, "Unselected service untouched");
        // A new manager models next-launch recovery, retaining only serialized ownership data.
        check(new SystemProxyOwnership(f.port, f.journal).recover().complete(), "New manager restores journaled ownership");
        check(f.port.read(WIFI).configured().equals(original), "Manual/PAC/bypass/unknown fields round-trip exactly");
        check(f.port.read(ETHERNET).configured().values().isEmpty(), "Originally absent fields are removed");
        check(f.journal.load().isEmpty(), "Successful restore clears lease");
        int writes = f.port.writes;
        check(f.manager.recover().complete() && f.port.writes == writes, "Repeated recovery is idempotent");
        check(f.port.lockDepth == 0, "Settings session released");

        var settings = new LinkedHashMap<String, Object>();
        var nested = new ArrayList<>(List.of("one"));
        settings.put("list", nested);
        var immutable = new ProxySettings(settings);
        nested.add("two");
        check(immutable.values().get("list").equals(List.of("one")), "Snapshot deeply copies mutable values");
        check(new ProxySettings(Map.of("port", 80)).equals(new ProxySettings(Map.of("port", 80L))), "Native numeric widths compare equally");
        check(!immutable.toString().contains("one"), "Snapshot display does not expose values");
        expect(IllegalArgumentException.class, () -> new ProxySettings(Map.of("binary", new byte[]{1})), "Unknown plist value refused without lossy conversion");
    }

    private static void preflight() throws Exception {
        var f = new Fixture();
        expect(IllegalArgumentException.class, () -> f.manager.apply(List.of(), HTTP, SOCKS), "Empty scope refused");
        expect(IllegalArgumentException.class, () -> f.manager.apply(List.of(WIFI, WIFI), HTTP, SOCKS), "Duplicate service refused");
        expect(IllegalArgumentException.class, () -> f.manager.apply(List.of(WIFI), 0, SOCKS), "Invalid port refused");
        expect(IOException.class, () -> f.manager.apply(List.of(WIFI, "removed"), HTTP, SOCKS), "Removed service refuses full preflight");
        check(f.port.writes == 0 && f.journal.load().isEmpty(), "All targets inspected before journal or settings writes");
        var original = f.port.read(WIFI);
        f.port.states.put(WIFI, new SystemProxySettings.State(original.configured(), new ProxySettings(Map.of())));
        expect(IOException.class, () -> f.manager.apply(List.of(WIFI), HTTP, SOCKS), "Unsettled effective state refused");
        check(f.port.writes == 0, "Unsettled state is untouched");
        f.port.states.put(WIFI, original);
        f.manager.apply(List.of(WIFI), HTTP, SOCKS);
        int writes = f.port.writes;
        expect(IOException.class, () -> f.manager.apply(List.of(WIFI), HTTP, SOCKS), "Existing lease blocks overwrite");
        check(writes == f.port.writes, "Existing ownership is not replaced");
        f.manager.recover();

        // Matching settings are shared state, not something PerRoute may claim and later undo.
        f.manager.apply(List.of(WIFI), HTTP, SOCKS);
        f.journal.value = Optional.empty();
        writes = f.port.writes;
        f.manager.apply(List.of(WIFI), HTTP, SOCKS);
        check(f.port.writes == writes && f.journal.load().isEmpty(), "Already matching configuration is not claimed");
    }

    private static void externalEdits() throws Exception {
        var f = new Fixture();
        var original = f.port.read(WIFI).configured();
        f.manager.apply(List.of(WIFI), HTTP, SOCKS);
        f.port.edit(WIFI, Map.of("HTTPPort", 12345, "ExceptionsList", List.of("user-edited"), "OtherFlag", "new"));
        var editedHttp = f.port.read(WIFI).configured().select(Group.HTTP.keys());
        var result = f.manager.recover();
        check(!result.complete() && result.issues().equals(List.of(new SystemProxyOwnership.Issue(WIFI, Group.HTTP, SystemProxyOwnership.Problem.EXTERNAL_CHANGE))), "External endpoint edit is reported");
        check(f.port.read(WIFI).configured().select(Group.HTTP.keys()).equals(editedHttp), "Entire edited HTTP tuple remains intact");
        check(f.port.read(WIFI).configured().select(Group.HTTPS.keys()).equals(original.select(Group.HTTPS.keys())), "Unchanged owned HTTPS tuple restores");
        check(f.port.read(WIFI).configured().values().get("ExceptionsList").equals(List.of("user-edited")), "External bypass preserved");
        check(f.port.read(WIFI).configured().values().get("OtherFlag").equals("new"), "Unknown external edit preserved");
        check(f.journal.load().orElseThrow().entries().getFirst().groups().equals(Set.of(Group.HTTP)), "Only unresolved group remains journaled");
        int writes = f.port.writes;
        check(!f.manager.recover().complete() && f.port.writes == writes, "Conflict retry does not force restore");
        var current = f.port.read(WIFI).configured().replacing(Group.HTTP.keys(), original.select(Group.HTTP.keys()));
        f.port.add(WIFI, current);
        check(f.manager.recover().complete() && f.journal.load().isEmpty(), "Externally restored original resolves conflict");

        f = new Fixture();
        f.manager.apply(List.of(WIFI), HTTP, SOCKS);
        f.port.edit(WIFI, Map.of("ProxyAutoConfigURLString", "https://new.example/pac"));
        result = f.manager.recover();
        check(result.issues().stream().anyMatch(issue -> issue.group() == Group.PAC), "PAC URL edit guards PAC enable flag");
        check(f.port.read(WIFI).configured().values().get("ProxyAutoConfigEnable").equals(new java.math.BigDecimal(0)), "Changed PAC is not implicitly re-enabled");
        f.port.states.remove(WIFI);
        check(!f.manager.recover().complete() && f.journal.load().isPresent(), "Removed service stays unresolved");
    }

    private static void partialFailures() throws Exception {
        var f = new Fixture();
        f.port.add(ETHERNET, new ProxySettings(Map.of()));
        var original = f.port.read(WIFI).configured();
        f.port.failCommitAt = 2;
        expect(IOException.class, () -> f.manager.apply(List.of(WIFI, ETHERNET), HTTP, SOCKS), "Failure after second persistent commit is reported");
        check(f.port.read(WIFI).configured().equals(original) && f.port.read(WIFI).effective().equals(original), "First service restored after partial apply");
        check(f.port.read(ETHERNET).configured().values().isEmpty() && f.port.read(ETHERNET).effective().values().isEmpty(), "Commit-only second service restores both states");
        check(f.journal.load().isEmpty(), "Partial-apply rollback completes journal");

        var denied = new Fixture();
        denied.port.failBeforeAt = 1;
        expect(IOException.class, () -> denied.manager.apply(List.of(WIFI), HTTP, SOCKS), "Authorization-like rejection before commit is reported");
        check(denied.journal.load().isEmpty(), "Unchanged denied operation resolves journal");

        var restore = new Fixture();
        restore.manager.apply(List.of(WIFI), HTTP, SOCKS);
        restore.port.failBeforeAt = 2;
        check(!restore.manager.recover().complete() && restore.journal.load().isPresent(), "Failed restore retains ownership");
        check(restore.manager.recover().complete(), "Restore succeeds on explicit retry");

        var verification = new Fixture();
        verification.manager.apply(List.of(WIFI), HTTP, SOCKS);
        verification.port.skipApplyAt = 2;
        check(!verification.manager.recover().complete() && verification.journal.load().isPresent(), "Restore read-back failure retains ownership");
        check(verification.manager.recover().complete(), "Commit-only restore retries apply even when configured values already match");

        var mismatch = new Fixture();
        mismatch.port.skipApplyAt = 1;
        expect(IOException.class, () -> mismatch.manager.apply(List.of(WIFI), HTTP, SOCKS), "Read-back mismatch is not success");
        check(mismatch.journal.load().isEmpty(), "Read-back mismatch rollback handles commit-only state");

        var conflict = new Fixture();
        conflict.port.beforeReplace = () -> conflict.port.edit(WIFI, Map.of("OtherFlag", "concurrent"));
        expect(IOException.class, () -> conflict.manager.apply(List.of(WIFI), HTTP, SOCKS), "Compare-before-write rejects concurrent edit");
        check(conflict.port.read(WIFI).configured().values().get("OtherFlag").equals("concurrent"), "Concurrent edit not overwritten");
        check(conflict.journal.load().isEmpty(), "No owned change after rejected comparison");
    }

    private static void journalFailures() throws Exception {
        var before = new Fixture();
        before.journal.failSaveAt = 1;
        expect(IOException.class, () -> before.manager.apply(List.of(WIFI), HTTP, SOCKS), "Journal failure prevents mutation");
        check(before.port.writes == 0, "No write before persisted intent");
        var after = new Fixture();
        after.manager.apply(List.of(WIFI), HTTP, SOCKS);
        var lease = after.journal.load();
        after.journal.failSaveAt = 2;
        expect(IOException.class, after.manager::recover, "Failed completion marker does not claim successful cleanup");
        check(after.journal.load().equals(lease), "Previous journal retained when completion fails");
        int writes = after.port.writes;
        check(after.manager.recover().complete() && after.port.writes == writes, "Already restored fields resolve without another mutation");
    }

    private static void cancellation() throws Exception {
        var f = new Fixture();
        var original = f.port.read(WIFI).configured();
        f.port.interruptCommitAt = 1;
        try {
            expect(InterruptedException.class, () -> f.manager.apply(List.of(WIFI), HTTP, SOCKS), "Cancellation after commit propagates");
            check(Thread.currentThread().isInterrupted(), "Interrupted apply preserves flag after rollback");
        } finally { Thread.interrupted(); }
        check(f.port.read(WIFI).configured().equals(original) && f.port.read(WIFI).effective().equals(original), "Cancelled commit restores configured/effective settings");
        check(f.journal.load().isEmpty(), "Cancelled commit rollback resolves journal");
        f.manager.apply(List.of(WIFI), HTTP, SOCKS);
        try {
            Thread.currentThread().interrupt();
            expect(InterruptedException.class, f.manager::recover, "Interrupted recovery stops before mutation");
            check(Thread.currentThread().isInterrupted(), "Recovery preserves interrupt flag");
        } finally { Thread.interrupted(); }
        check(f.journal.load().isPresent(), "Cancelled recovery retains journal for retry");
        check(f.manager.recover().complete(), "Cancelled recovery can be retried");

        var duringRestore = new Fixture();
        duringRestore.manager.apply(List.of(WIFI), HTTP, SOCKS);
        duringRestore.port.interruptCommitAt = 2;
        try {
            expect(InterruptedException.class, duringRestore.manager::recover, "Cancellation during restore propagates");
            check(Thread.currentThread().isInterrupted(), "Interrupted restore preserves flag");
        } finally { Thread.interrupted(); }
        check(duringRestore.journal.load().isPresent(), "Interrupted restore retains durable lease");
        check(duringRestore.manager.recover().complete(), "Interrupted commit-only restore can be retried");
    }

    private static void encryptedJournal() throws Exception {
        Path root = Files.createTempDirectory("perroute-proxy-journal-").toRealPath();
        Path path = root.resolve("proxy-recovery.bin");
        try (var cipher = new MacStateCipher(new byte[32])) {
            var disk = new EncryptedProxyJournal(path, cipher);
            check(disk.load().isEmpty(), "Missing journal has no ownership");
            expect(IllegalArgumentException.class, () -> new Entry(WIFI, new ProxySettings(Map.of()),
                    new ProxySettings(Map.of("HTTPPort", HTTP)), Set.of(Group.HTTP)), "Incomplete proxy tuple refused");
            expect(IllegalArgumentException.class, () -> new Entry(WIFI, new ProxySettings(Map.of()),
                    new ProxySettings(Map.of("ProxyAutoConfigEnable", 0, "ProxyAutoConfigURLString", "changed")), Set.of(Group.PAC)), "Journal cannot claim a PAC reference mutation");
            var f = new Fixture();
            f.manager.apply(List.of(WIFI), HTTP, SOCKS);
            var lease = f.journal.load();
            disk.save(lease);
            check(new EncryptedProxyJournal(path, cipher).load().equals(lease), "Encrypted journal round-trips in a new store");
            byte[] bytes = Files.readAllBytes(path);
            var failing = new EncryptedProxyJournal(path, new app.perroute.persistence.StateCipher() {
                @Override public byte[] protect(byte[] plain) throws IOException { throw new IOException("Fixture encryption failure"); }
                @Override public byte[] unprotect(byte[] encrypted) throws IOException { return cipher.unprotect(encrypted); }
            });
            expect(IOException.class, () -> failing.save(Optional.empty()), "Failed journal encryption preserves active lease");
            check(Arrays.equals(bytes, Files.readAllBytes(path)), "Failed encryption never replaces recovery bytes");
            check(!new String(bytes, StandardCharsets.ISO_8859_1).contains("old.example"), "Recovery settings not stored as plaintext");
            check(Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rw-------")), "Journal file owner-only");
            check(Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------")), "Journal directory owner-only");
            var port = new FakePort(disk);
            port.add(WIFI, f.port.read(WIFI).configured());
            check(new SystemProxyOwnership(port, new EncryptedProxyJournal(path, cipher)).recover().complete(), "Recovery uses only durable journal in a new policy object");
            check(port.read(WIFI).configured().equals(original()), "Durable recovery restores original settings");
            check(disk.load().isEmpty(), "Encrypted completion marker has no lease");
            disk.save(lease);
            bytes = Files.readAllBytes(path); bytes[bytes.length - 1] ^= 1;
            Files.write(path, bytes);
            var policy = new SystemProxyOwnership(port, disk);
            int writes = port.writes;
            expect(IOException.class, policy::recover, "Tampered ciphertext refuses recovery");
            check(port.writes == writes && Arrays.equals(bytes, Files.readAllBytes(path)), "Corrupt journal preserved without settings writes");
            Files.write(path, cipher.protect(Json.stringify(Map.of("version", 99, "lease", Map.of())).getBytes(StandardCharsets.UTF_8)));
            expect(IOException.class, disk::load, "Unknown schema refused");
            Files.write(path, cipher.protect("{\"version\":1,\"lease\":{\"owner\":\"secret-value\"}}".getBytes(StandardCharsets.UTF_8)));
            try { disk.load(); throw new AssertionError("Malformed journal accepted"); }
            catch (IOException error) { check(!error.toString().contains("secret-value") && error.getCause() == null, "Parser errors do not reveal journal values"); }
            Path linked = root.resolve("linked.bin");
            Files.createSymbolicLink(linked, path.getFileName());
            var symlink = new EncryptedProxyJournal(linked, cipher);
            expect(IOException.class, symlink::load, "Symlink journal refused");
            expect(IOException.class, () -> symlink.save(lease), "Symlink journal not overwritten");
        } finally {
            try (var paths = Files.walk(root)) { for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
        }
    }

    private static ProxySettings original() {
        return new ProxySettings(Map.of("HTTPEnable", 1, "HTTPProxy", "old.example", "HTTPPort", 8080,
                "ProxyAutoConfigEnable", 1, "ProxyAutoConfigURLString", "https://old.example/private-pac",
                "ExceptionsList", List.of("*.internal", "localhost"), "ExcludeSimpleHostnames", 1,
                "OpaqueReference", Map.of("name", "kept")));
    }
    private static final class MemoryJournal implements ProxyJournal {
        Optional<Lease> value = Optional.empty();
        int saves, failSaveAt;
        @Override public Optional<Lease> load() { return value; }
        @Override public void save(Optional<Lease> lease) throws IOException {
            if (++saves == failSaveAt) throw new IOException("Fixture journal save failed");
            value = lease;
        }
    }
    private static final class Fixture {
        final MemoryJournal journal = new MemoryJournal();
        final FakePort port = new FakePort(journal);
        final SystemProxyOwnership manager = new SystemProxyOwnership(port, journal);
        Fixture() { port.add(WIFI, original()); }
    }
    private static final class FakePort implements SystemProxySettings, SystemProxySettings.Session {
        final Map<String, State> states = new LinkedHashMap<>();
        final ProxyJournal journal;
        int writes, lockDepth, failBeforeAt, failCommitAt, skipApplyAt, interruptCommitAt;
        Runnable beforeReplace;
        FakePort(ProxyJournal journal) { this.journal = journal; }
        void add(String id, ProxySettings settings) { states.put(id, new State(settings, settings)); }
        void edit(String id, Map<String, Object> values) {
            var current = states.get(id).configured();
            add(id, current.replacing(values.keySet(), new ProxySettings(values)));
        }
        @Override public Session lock() { check(lockDepth++ == 0, "Exclusive fixture session"); return this; }
        @Override public State read(String id) throws IOException {
            if (!states.containsKey(id)) throw new IOException("Fixture service unavailable");
            return states.get(id);
        }
        @Override public void replace(String id, ProxySettings expected, ProxySettings replacement) throws IOException, InterruptedException {
            check(lockDepth == 1, "Mutation under settings lock");
            check(journal.load().isPresent(), "Durable intent exists before every mutation");
            if (beforeReplace != null) { var action = beforeReplace; beforeReplace = null; action.run(); }
            if (!read(id).configured().equals(expected)) throw new IOException("Fixture configuration conflict");
            int write = ++writes;
            if (write == failBeforeAt) throw new IOException("Fixture authorization failure");
            states.put(id, new State(replacement, read(id).effective()));
            if (write == failCommitAt) throw new IOException("Fixture failed after commit");
            if (write == interruptCommitAt) throw new InterruptedException("Fixture cancelled after commit");
            if (write != skipApplyAt) add(id, replacement);
        }
        @Override public void close() { lockDepth--; }
    }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private static void expect(Class<? extends Throwable> type, Operation operation, String message) throws Exception {
        try { operation.run(); }
        catch (Throwable error) { if (type.isInstance(error)) { check(true, message); return; } throw error; }
        throw new AssertionError(message);
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
