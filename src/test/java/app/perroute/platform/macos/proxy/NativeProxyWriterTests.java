package app.perroute.platform.macos.proxy;

import java.io.IOException;
import java.util.*;
import static app.perroute.platform.macos.proxy.NetworkServiceInventory.*;

public final class NativeProxyWriterTests {
    private static int checks;
    private static final ProxySettings ORIGINAL = new ProxySettings(Map.of("HTTPEnable", 0, "HTTPProxy", "dormant.example", "HTTPPort", 8080, "ExceptionsList", List.of("localhost")));
    public static void main(String[] args) throws Exception {
        for (String failure : List.of("none", "set", "commit", "apply", "commitPartial")) {
            var fixture = new Fixture(); fixture.failure = failure;
            var journal = new MemoryJournal();
            var owner = new SystemProxyOwnership(fixture.settings(), journal);
            if (failure.equals("none")) {
                owner.apply(List.of("one"), 17891, 17890);
                check(!fixture.configured.equals(ORIGINAL), "Native port applies new configuration");
                check(journal.value.isPresent(), "Intent journal precedes native writes");
                check(owner.recover().complete(), "Native port restores ownership");
            } else expectIo(() -> owner.apply(List.of("one"), 17891, 17890));
            check(fixture.configured.equals(ORIGINAL), "Failure/restore preserves exact original");
            check(journal.value.isEmpty(), "Successful recovery completes journal");
            check(fixture.opens == fixture.closes, "Preferences resource released after success/failure");
        }
        var fixture = new Fixture(); var settings = fixture.settings();
        try (var session = settings.lock()) {
            expectIo(() -> session.replace("one", new ProxySettings(Map.of()), ORIGINAL));
            check(fixture.sets == 0, "CAS mismatch performs no mutation");
            expectIo(() -> session.replace("one", ORIGINAL, new ProxySettings(Map.of("ProxyAutoConfigEnable", 1))));
            check(fixture.sets == 0, "Unsupported replacement performs no mutation");
        }
        try (var session = settings.lock()) { session.close(); expectIo(() -> session.read("one")); }
        expectIo(() -> new MacSystemProxySettings(() -> { throw new IOException("Authorization denied"); }, new StableProxyReadback(fixture::inventory, () -> {}, 3)).lock());
        if (app.perroute.platform.bootstrap.PlatformBootstrap.isMac(System.getProperty("os.name"))) {
            var unicode = new ProxySettings(Map.of("HTTPEnable", 0, "ExceptionsList", List.of("شبکه&<example>", "😀"),
                    "nested", Map.of("boolean", true, "number", 1.25)));
            var pointer = NativeProxyEncoding.create(unicode);
            try { check(new NativePropertyLists().dictionary(pointer).equals(unicode), "Native encoder preserves Unicode, XML escapes, types and nested values"); }
            finally { NativePropertyLists.release(pointer); }
            expectIo(() -> NativeProxyEncoding.create(new ProxySettings(Map.of("bad", "\u0000"))));
        }
        System.out.println("Native proxy writer checks passed (" + checks + " assertions; settings simulated)");
    }
    static final class MemoryJournal implements ProxyJournal {
        Optional<Lease> value = Optional.empty();
        public Optional<Lease> load() { return value; }
        public void save(Optional<Lease> value) { this.value = value; }
    }
    static final class Fixture implements MacSystemProxySettings.Access {
        ProxySettings configured = ORIGINAL, effective = ORIGINAL, setup = ORIGINAL, staged;
        String failure = "none"; int opens, closes, sets;
        boolean fail(String stage) { if (!failure.equals(stage)) return false; failure = "none"; return true; }
        MacSystemProxySettings settings() { return new MacSystemProxySettings(() -> { opens++; return this; }, new StableProxyReadback(this::inventory, () -> {}, 3)); }
        NetworkServiceInventory inventory() {
            var service = new Service("one", "private", List.of(new InterfaceLayer("Ethernet", "en0")), true, true, true, true,
                    Observation.present(configured), Observation.present(setup), Observation.unavailable(Status.ABSENT), List.of("en0"));
            return new NetworkServiceInventory(List.of(service), Observation.present(new ProxySettings(Map.of("__SCOPED__", Map.of("en0", effective.values())))), Consistency.UNCHANGED);
        }
        public ProxySettings current(String id) { return configured; }
        public void set(String id, ProxySettings value) throws IOException { sets++; if (fail("set")) throw new IOException("Set failed"); staged = value; }
        public void commit() throws IOException {
            if (fail("commit")) { staged = null; throw new IOException("Commit failed"); }
            configured = staged;
            if (fail("commitPartial")) throw new IOException("Commit result uncertain");
        }
        public void apply() throws IOException { if (fail("apply")) throw new IOException("Apply failed"); effective = configured; setup = configured; }
        public void close() { closes++; }
    }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private static void expectIo(Operation operation) throws Exception { try { operation.run(); throw new AssertionError("Failure accepted"); } catch (IOException expected) { check(true, "Explicit failure"); } }
    private static void check(boolean result, String message) { checks++; if (!result) throw new AssertionError(message); }
}
