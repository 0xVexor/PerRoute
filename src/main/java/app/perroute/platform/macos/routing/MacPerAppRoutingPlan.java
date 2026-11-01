package app.perroute.platform.macos.routing;

import app.perroute.platform.macos.MacApplicationCatalog;
import app.perroute.util.Json;
import java.util.*;

/** Offline candidate for the standalone TUN backend. Never installs or starts a process. */
public final class MacPerAppRoutingPlan {
    public static final String ENGINE_VERSION = "1.14.3";
    public record ExecutableRoute(String executablePath, int socksPort) {
        public ExecutableRoute {
            executablePath = executable(executablePath);
            if (socksPort < 1 || socksPort > 65535) throw new IllegalArgumentException("Invalid loopback SOCKS port");
        }
        public static ExecutableRoute observedMainExecutable(MacApplicationCatalog.Application application, int port) {
            Objects.requireNonNull(application);
            String bundle = executable(application.bundlePath().toString());
            String path = executable(application.executablePath().toString());
            if (!bundle.endsWith(".app") || !path.startsWith(bundle + "/Contents/MacOS/"))
                throw new IllegalArgumentException("Executable is outside the observed bundle");
            return new ExecutableRoute(path, port);
        }
    }
    private final Map<String, Object> configuration;

    public MacPerAppRoutingPlan(List<ExecutableRoute> selected, List<String> engineExecutables) {
        Objects.requireNonNull(selected);
        Objects.requireNonNull(engineExecutables);
        if (selected.isEmpty() || selected.size() > 256 || engineExecutables.isEmpty() || engineExecutables.size() > 32)
            throw new IllegalArgumentException("Expected bounded explicit selections and engine exclusions");
        var exclusions = new TreeSet<String>();
        for (String path : engineExecutables)
            if (!exclusions.add(executable(path))) throw new IllegalArgumentException("Duplicate engine executable");
        var bindings = new TreeMap<String, Integer>();
        for (var route : selected) {
            Objects.requireNonNull(route);
            if (exclusions.contains(route.executablePath())) throw new IllegalArgumentException("An engine cannot proxy itself");
            if (bindings.putIfAbsent(route.executablePath(), route.socksPort()) != null)
                throw new IllegalArgumentException("Duplicate executable routing authority");
        }
        var rules = new ArrayList<Map<String, Object>>();
        rules.add(Map.of("process_path", List.copyOf(exclusions), "action", "route", "outbound", "unmatched-direct"));
        var outbounds = new ArrayList<Map<String, Object>>();
        outbounds.add(Map.of("type", "direct", "tag", "unmatched-direct"));
        var ports = new TreeSet<>(bindings.values());
        for (int port : ports) {
            String tag = "xray-loopback-" + port;
            outbounds.add(Map.of("type", "socks", "tag", tag, "server", "127.0.0.1", "server_port", port, "version", "5"));
            var paths = bindings.entrySet().stream().filter(entry -> entry.getValue() == port).map(Map.Entry::getKey).toList();
            rules.add(Map.of("inbound", List.of("perroute-tun-spike"), "network", "tcp", "process_path", paths,
                    "action", "route", "outbound", tag));
        }
        // A config check must never imply approval to capture the host's traffic. The future native
        // supervisor must own interface/route recovery before enabling any automatic host routes.
        configuration = Map.of("log", Map.of("disabled", true),
                "inbounds", List.of(Map.of("type", "tun", "tag", "perroute-tun-spike",
                        "address", List.of("172.31.255.1/30"), "auto_route", false)),
                "outbounds", List.copyOf(outbounds),
                "route", Map.of("auto_detect_interface", true, "rules", List.copyOf(rules), "final", "unmatched-direct"));
    }
    public Map<String, Object> configuration() { return configuration; }
    public String toJson() { return Json.stringify(configuration); }

    private static String executable(String path) {
        if (path == null || path.length() > 8192 || !path.startsWith("/") || path.endsWith("/") ||
                path.contains("\\") || path.chars().anyMatch(c -> Character.isISOControl(c)) ||
                Arrays.stream(path.substring(1).split("/", -1)).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..")))
            throw new IllegalArgumentException("Expected an exact normalized macOS executable path");
        return path;
    }
}
