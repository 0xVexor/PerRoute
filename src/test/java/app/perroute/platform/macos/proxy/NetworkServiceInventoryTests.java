package app.perroute.platform.macos.proxy;

import java.util.*;
import static app.perroute.platform.macos.proxy.NetworkServiceInventory.*;

public final class NetworkServiceInventoryTests {
    private static int assertions;
    private static final Observation EMPTY = Observation.present(new ProxySettings(Map.of()));
    private static final Observation MISSING = Observation.unavailable(Status.ABSENT);
    public static void main(String[] args) throws Exception {
        check(service("stable-one", "same name", "IEEE80211", true, true, true, true, EMPTY, MISSING).scope() == Scope.CANDIDATE, "Active Wi-Fi is an inspection candidate");
        check(service("one", "one", "Ethernet", true, true, true, true, EMPTY, MISSING).scope() == Scope.CANDIDATE, "Active Ethernet is an inspection candidate");
        check(service("one", "one", "IEEE80211", false, true, true, true, EMPTY, MISSING).scope() == Scope.DISABLED, "Disabled service excluded");
        check(service("one", "one", "IEEE80211", true, false, true, true, EMPTY, MISSING).scope() == Scope.OUTSIDE_CURRENT_SET, "Other location excluded");
        check(service("one", "one", "IEEE80211", true, true, false, true, EMPTY, MISSING).scope() == Scope.NO_ACTIVE_IP, "Inactive service excluded");
        for (String type : List.of("PPP", "VPN", "Bridge", "VLAN", "Unknown")) {
            check(service("one", "one", type, true, true, true, true, EMPTY, MISSING).scope() == Scope.OTHER_INTERFACE, "Virtual/unknown interface excluded");
        }
        check(service("one", "one", "IEEE80211", true, true, true, false, EMPTY, MISSING).scope() == Scope.PROXIES_UNAVAILABLE, "Disabled protocol excluded");
        check(service("one", "one", "IEEE80211", true, true, true, true, Observation.unavailable(Status.UNSUPPORTED), MISSING).scope() == Scope.PROXIES_UNAVAILABLE, "Unsupported configured dictionary excluded");
        var first = service("stable-one", "same name", "IEEE80211", true, true, true, true, EMPTY, MISSING);
        var second = service("stable-two", "same name", "Ethernet", true, true, true, true, EMPTY, MISSING);
        var services = new ArrayList<>(List.of(first, second));
        var inventory = new NetworkServiceInventory(services, EMPTY, Consistency.UNCHANGED);
        services.clear();
        check(inventory.services().size() == 2, "Stable IDs distinguish identical names and list is copied");
        check(first.proxyAgreement() == Agreement.UNAVAILABLE, "Missing runtime entry is never treated as empty settings");
        check(service("one", "one", "IEEE80211", true, true, true, true, EMPTY, EMPTY).proxyAgreement() == Agreement.MATCH, "Two observed empty dictionaries compare equally");
        check(service("one", "one", "IEEE80211", true, true, true, true, EMPTY,
                Observation.present(new ProxySettings(Map.of("HTTPEnable", 0)))).proxyAgreement() == Agreement.DIFFERENT, "Absent and explicit disabled fields stay distinct");
        check(!first.toString().contains("same name") && !inventory.toString().contains("stable-one"), "Default report display redacts identifying metadata");
        var chain = new Service("one", "one", List.of(new InterfaceLayer("PPP", "ppp0"), new InterfaceLayer("Ethernet", "en0")), true, true, true, true, EMPTY, EMPTY, EMPTY);
        check(chain.scope() == Scope.OTHER_INTERFACE, "Layered interface cannot masquerade as physical candidate");
        expect(() -> new NetworkServiceInventory(List.of(first, first), EMPTY, Consistency.UNCHANGED));
        expect(() -> new Observation(Status.ABSENT, Optional.of(new ProxySettings(Map.of()))));
        String previous = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Linux");
            try { new MacNetworkServiceInspector().inspect(); throw new AssertionError("Unsupported platform accepted"); }
            catch (UnsupportedOperationException expected) { check(true, "Platform rejected before native framework load"); }
        } finally { System.setProperty("os.name", previous); }
        System.out.println("Network service model checks passed (" + assertions + " assertions)");
    }
    private static Service service(String id, String name, String type, boolean enabled, boolean current, boolean active,
                                   boolean protocol, Observation configured, Observation runtime) {
        return new Service(id, name, List.of(new InterfaceLayer(type, "en0")), enabled, current, active, protocol, configured, EMPTY, runtime);
    }
    private static void expect(Runnable operation) {
        try { operation.run(); throw new AssertionError("Invalid model accepted"); }
        catch (IllegalArgumentException expected) { check(true, "Invalid observation rejected"); }
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}
