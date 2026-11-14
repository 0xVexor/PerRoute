package app.perroute;

import app.perroute.service.InstalledApplicationDiscovery;
import app.perroute.service.IssueLog;
import app.perroute.service.ProxiFyreManager;
import app.perroute.service.WindowsAppIcons;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the parts of PerRoute that depend on the real Windows machine instead of on fakes: app discovery, icons,
 * the packet-filter driver and name resolution. Run it with {@code gradlew runWindowsSmoke} on a clean system.
 * It exits non-zero on any FAIL; WARN lines are things to read, not blockers.
 */
public final class WindowsSmoke {
    private static final double MIN_ICON_COVERAGE = 0.70;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            System.out.println("[skip] The Windows smoke check only runs on Windows");
            return;
        }
        discoveryAndIcons();
        driver();
        nameResolution();
        IssueLog.recent().forEach(issue -> System.out.println("[note] " + issue.source() + ": " + issue.message()));
        if (failures.isEmpty()) System.out.println("Windows smoke check passed");
        else {
            failures.forEach(failure -> System.out.println("[FAIL] " + failure));
            System.exit(1);
        }
    }

    private static void discoveryAndIcons() {
        var apps = new InstalledApplicationDiscovery().list();
        if (apps.isEmpty()) {
            failures.add("No installed applications were found; the Applications list would be empty");
            return;
        }
        System.out.println("[ok] " + apps.size() + " installed applications found");
        List<String> without = new ArrayList<>();
        for (var app : apps) if (WindowsAppIcons.load(Path.of(app.path())) == null) without.add(app.name());
        double coverage = 1.0 - (double) without.size() / apps.size();
        System.out.printf("[%s] %d%% of applications have an icon%n", coverage >= MIN_ICON_COVERAGE ? "ok" : "FAIL", Math.round(coverage * 100));
        if (!without.isEmpty()) System.out.println("      without icon: " + String.join(", ", without));
        if (coverage < MIN_ICON_COVERAGE) failures.add("Fewer than " + Math.round(MIN_ICON_COVERAGE * 100) + "% of applications have an icon");
    }

    private static void driver() {
        var state = new ProxiFyreManager().driverState();
        System.out.println("[" + (state == ProxiFyreManager.DriverState.RUNNING ? "ok" : "warn") + "] Windows Packet Filter driver: " + state);
    }

    // A resolver that answers a real public name with a private address is the situation that broke per-app routing.
    private static void nameResolution() {
        for (String host : new String[] { "www.cloudflare.com", "www.youtube.com" }) {
            try {
                var addresses = InetAddress.getAllByName(host);
                boolean fake = java.util.Arrays.stream(addresses).anyMatch(a -> a.isSiteLocalAddress() || a.isLoopbackAddress() || a.isAnyLocalAddress());
                System.out.println("[" + (fake ? "warn" : "ok") + "] " + host + " -> " + addresses[0].getHostAddress() + (fake ? " (private address: DNS is filtered)" : ""));
            } catch (java.net.UnknownHostException error) {
                System.out.println("[warn] " + host + " did not resolve");
            }
        }
    }
}
