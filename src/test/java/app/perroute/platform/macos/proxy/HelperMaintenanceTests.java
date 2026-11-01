package app.perroute.platform.macos.proxy;

import java.io.IOException;
import java.util.*;

public final class HelperMaintenanceTests {
    static int checks;
    public static void main(String[] args) throws Exception {
        var calls = new ArrayList<String>();
        MacSystemProxyLifecycle recovery = new MacSystemProxyLifecycle() {
            public void apply() { throw new AssertionError("Maintenance cannot apply"); }
            public void restore() { calls.add("restore"); }
        };
        MacHelperMaintenance.unregister(recovery, (op, values) -> {
            calls.add(op); check(values.isEmpty(), "Fixed maintenance request");
            return Map.of("pending", false, "registered", false);
        });
        check(calls.equals(List.of("restore", "unregister")), "Verified restoration precedes unregister");
        for (var reply : List.of(Map.<String, Object>of(), Map.<String, Object>of("pending", false),
                Map.<String, Object>of("pending", true, "registered", false),
                Map.<String, Object>of("pending", false, "registered", true),
                Map.<String, Object>of("pending", false, "registered", "false"))) {
            fail(() -> MacHelperMaintenance.unregister(recovery, (op, values) -> reply));
        }
        calls.clear();
        MacSystemProxyLifecycle unresolved = new MacSystemProxyLifecycle() {
            public void apply() { throw new AssertionError(); }
            public void restore() throws IOException { throw new IOException("Recovery conflict"); }
        };
        fail(() -> MacHelperMaintenance.unregister(unresolved, (op, values) -> { calls.add(op); return Map.of(); }));
        check(calls.isEmpty(), "Failed legacy/helper recovery cannot unregister");
        fail(() -> MacHelperMaintenance.unregister(recovery, (op, values) -> { throw new IOException("Transport failed"); }));
        System.out.println("Helper maintenance: " + checks + " checks passed; simulated service only");
    }
    interface Work { void run() throws Exception; }
    static void fail(Work work) throws Exception {
        try { work.run(); throw new AssertionError("Failure expected"); }
        catch (IOException expected) { check(true, "Explicit failure"); }
    }
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
