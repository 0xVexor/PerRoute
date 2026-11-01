package app.perroute.platform.macos.proxy;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static app.perroute.platform.macos.proxy.NetworkServiceInventory.*;

public final class ProxyReadbackTests {
    private static int checks;
    private static final ProxySettings EMPTY = new ProxySettings(Map.of());
    public static void main(String[] args) throws Exception {
        var stable = inventory(EMPTY, EMPTY);
        var count = new AtomicInteger();
        var reader = new StableProxyReadback(() -> { count.incrementAndGet(); return stable; }, () -> {}, 4);
        check(reader.read("one").configured().equals(EMPTY), "Full configured dictionary retained");
        check(count.get() == 2, "Two complete matching samples required");
        check(ManualProxySemantics.equivalent(EMPTY, reader.read("one").effective()), "Absent disabled flags compare semantically");
        var stale = new ProxySettings(Map.of("HTTPEnable", 0, "HTTPProxy", "old.example", "HTTPPort", 8080));
        check(ManualProxySemantics.equivalent(stale, EMPTY), "Dormant endpoint is not an effective conflict");
        check(stale.values().containsKey("HTTPProxy"), "Original restore dictionary unchanged");
        check(!ManualProxySemantics.equivalent(new ProxySettings(Map.of("HTTPEnable", 1, "HTTPProxy", "host", "HTTPPort", 8080)), EMPTY), "Enabled endpoint remains significant");
        check(!ManualProxySemantics.equivalent(new ProxySettings(Map.of("HTTPEnable", true)), EMPTY), "Malformed value never equivalent");
        count.set(0);
        var changing = inventory(new ProxySettings(Map.of("HTTPEnable", 0)), EMPTY);
        var race = new StableProxyReadback(() -> count.incrementAndGet() == 1 ? changing : stable, () -> {}, 4);
        race.read("one"); check(count.get() == 3, "Race discards first sample");
        count.set(0);
        expectIo(() -> new StableProxyReadback(() -> count.incrementAndGet() % 2 == 0 ? stable : changing, () -> {}, 4).read("one"));
        expectIo(() -> reader.read("deleted"));
        var unsupported = new NetworkServiceInventory(stable.services(), Observation.unavailable(Status.UNREADABLE), Consistency.UNCHANGED);
        expectIo(() -> new StableProxyReadback(() -> unsupported, () -> {}, 3).read("one"));
        var pendingService = stable.services().getFirst();
        var pendingSettings = new ProxySettings(Map.of("HTTPEnable", 1, "HTTPProxy", "127.0.0.1", "HTTPPort", 17891));
        var pending = new Service("one", "private", pendingService.interfaces(), true, true, true, true,
                Observation.present(pendingSettings), Observation.present(EMPTY), pendingService.runtimeState(), List.of("en0"));
        var partial = new NetworkServiceInventory(List.of(pending), stable.globalProxies(), Consistency.UNCHANGED);
        var partialState = new StableProxyReadback(() -> partial, () -> {}, 3).read("one");
        check(partialState.configured().equals(pendingSettings), "Partial commit retains actual configured values");
        check(!ManualProxySemantics.equivalent(partialState.configured(), partialState.effective()), "Lagging Setup is observable for rollback");
        Thread.currentThread().interrupt();
        try { reader.read("one"); throw new AssertionError("Cancellation ignored"); }
        catch (InterruptedException expected) { check(Thread.currentThread().isInterrupted(), "Cancellation flag preserved"); }
        finally { Thread.interrupted(); }
        // Exercise the actual ownership policy with an adapter that returns normalized effective values.
        var journal = new ProxyJournal() {
            Optional<Lease> value = Optional.empty();
            public Optional<Lease> load() { return value; }
            public void save(Optional<Lease> value) { this.value = value; }
        };
        var settings = new SystemProxySettings() {
            ProxySettings value = stale;
            public Session lock() {
                return new Session() {
                    public State read(String id) { return new State(value, ManualProxySemantics.normalize(value)); }
                    public boolean equivalent(ProxySettings a, ProxySettings b) { return ManualProxySemantics.equivalent(a, b); }
                    public void replace(String id, ProxySettings expected, ProxySettings replacement) {
                        if (!value.equals(expected)) throw new AssertionError("Expected full dictionary CAS");
                        value = replacement;
                    }
                    public void close() { }
                };
            }
        };
        var ownership = new SystemProxyOwnership(settings, journal);
        ownership.apply(List.of("one"), 17891, 17890);
        check(journal.load().isPresent(), "Normalized adapter journals ownership");
        check(ownership.recover().complete(), "Normalized effective state restores successfully");
        try (var session = settings.lock()) { check(session.read("one").configured().equals(stale), "Exact dormant settings restored"); }
        check(journal.load().isEmpty(), "Recovery completes journal");
        check(ownership.recover().complete(), "Recovery is idempotent");
        System.out.println("Proxy read-back checks passed (" + checks + " assertions)");
    }
    private static NetworkServiceInventory inventory(ProxySettings configured, ProxySettings effective) {
        var observed = Observation.present(configured);
        var service = new Service("one", "private", List.of(new InterfaceLayer("Ethernet", "en0")), true, true, true, true,
                observed, observed, Observation.unavailable(Status.ABSENT), List.of("en0"));
        return new NetworkServiceInventory(List.of(service), Observation.present(new ProxySettings(Map.of("__SCOPED__", Map.of("en0", effective.values())))), Consistency.UNCHANGED);
    }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private static void expectIo(Operation operation) throws Exception {
        try { operation.run(); throw new AssertionError("Unavailable evidence accepted"); }
        catch (IOException expected) { check(true, "Unavailable/unstable evidence refused"); }
    }
    private static void check(boolean result, String message) { checks++; if (!result) throw new AssertionError(message); }
}
