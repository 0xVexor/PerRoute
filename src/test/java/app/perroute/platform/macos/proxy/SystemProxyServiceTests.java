package app.perroute.platform.macos.proxy;

import java.io.IOException;
import java.util.*;
import static app.perroute.platform.macos.proxy.NetworkServiceInventory.*;

/** No native preferences writes: service policy over the same ownership/native-session fixtures. */
public final class SystemProxyServiceTests {
    private static int checks;
    public static void main(String[] args) throws Exception {
        var fixture = new NativeProxyWriterTests.Fixture();
        var journal = new NativeProxyWriterTests.MemoryJournal();
        var service = new MacSystemProxyService(journal, fixture.settings(), fixture::inventory);
        service.restore();
        check(fixture.opens == 0, "Empty recovery does not authorize");
        var original = fixture.configured;
        service.apply();
        check(journal.value.isPresent(), "Apply retains encrypted-journal contract");
        check(fixture.sets == 1, "Exactly one selected service is updated");
        // Reconstruct the lifecycle with the retained journal, as at next startup.
        new MacSystemProxyService(journal, fixture.settings(), fixture::inventory).restore();
        check(fixture.configured.equals(original), "Restart restores exact originals");
        check(journal.value.isEmpty(), "Restart clears completed ownership");
        service.restore();
        check(fixture.opens == 2, "Repeated empty recovery remains authorization free");
        service.apply();
        var changed = new LinkedHashMap<String, Object>(fixture.configured.values());
        changed.put("HTTPProxy", "external.example");
        fixture.configured = new ProxySettings(changed); fixture.setup = fixture.configured; fixture.effective = fixture.configured;
        expectFailure(service::restore);
        check(fixture.configured.values().get("HTTPProxy").equals("external.example"), "External endpoint is preserved");
        check(journal.value.isPresent(), "Unresolved group stays journaled");
        int sets = fixture.sets;
        expectFailure(service::apply);
        check(fixture.sets == sets, "Unresolved ownership blocks a new lease");
        check(fixture.opens == fixture.closes, "All sessions close during failed recovery");
        var clean = new NativeProxyWriterTests.Fixture();
        var empty = new NativeProxyWriterTests.MemoryJournal();
        var none = new NetworkServiceInventory(List.of(), clean.inventory().globalProxies(), Consistency.UNCHANGED);
        expectFailure(() -> new MacSystemProxyService(empty, clean.settings(), () -> none).apply());
        check(clean.opens == 0, "No candidate refuses before authorization");
        var one = clean.inventory().services().getFirst();
        var two = new Service("two", one.name(), List.of(new InterfaceLayer("Ethernet", "en1")), true, true, true, true,
                one.configured(), one.publishedSetup(), one.runtimeState(), List.of("en1"));
        var ambiguous = new NetworkServiceInventory(List.of(one, two), clean.inventory().globalProxies(), Consistency.UNCHANGED);
        expectFailure(() -> new MacSystemProxyService(empty, clean.settings(), () -> ambiguous).apply());
        check(clean.opens == 0, "Multiple candidates refuse before authorization");
        clean.configured = new ProxySettings(Map.of("ProxyAutoConfigEnable", 1)); clean.setup = clean.configured;
        expectFailure(() -> new MacSystemProxyService(empty, clean.settings(), clean::inventory).apply());
        check(clean.opens == 0 && clean.sets == 0, "PAC refuses without changing host settings");
        System.out.println("System proxy service checks passed (" + checks + " assertions; simulated settings)");
    }
    @FunctionalInterface interface Operation { void run() throws Exception; }
    static void expectFailure(Operation action) throws Exception { try { action.run(); throw new AssertionError("Unsupported action accepted"); } catch (IOException expected) { check(true, "Explicit actionable failure"); } }
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
