package app.perroute.platform.macos.routing;

import app.perroute.util.Json;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Objects;
import app.perroute.platform.macos.routing.MacPerAppRoutingPlan.ExecutableRoute;

/** Only typed gateway bindings, upstream ports and an observed Xray PID cross the privileged boundary. */
public final class MacTunStartRequest {
    private final Map<String, Object> values;
    public MacTunStartRequest(List<ExecutableRoute> selected, long corePid, List<Integer> upstreamPorts) {
        if (corePid < 2 || corePid > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid Xray process ID");
        // Reuse the bounded, exact-path preparation contract; freeze the parsed nested values.
        var prepared = Json.object(new MacTunPreparationRequest(List.copyOf(selected)).toJson());
        @SuppressWarnings("unchecked")
        var bindings = (List<Map<String, Object>>) prepared.get("bindings");
        var gatewayPorts = new HashSet<Integer>();
        bindings.forEach(binding -> gatewayPorts.add(((Number)binding.get("socksPort")).intValue()));
        var upstream = List.copyOf(Objects.requireNonNull(upstreamPorts));
        if (upstream.size() != gatewayPorts.size() || new HashSet<>(upstream).size() != upstream.size() ||
                upstream.stream().anyMatch(port -> port < 1 || port > 65535 || gatewayPorts.contains(port)))
            throw new IllegalArgumentException("Expected distinct Xray upstream ports separate from every gateway listener");
        values = Map.of("corePid", corePid, "bindings", bindings.stream().map(Map::copyOf).toList(),
                "upstreamPorts", upstream);
        if ((Json.stringify(Map.of("version", 1, "operation", "start-tun", "corePid", corePid,
                "bindings", values.get("bindings"), "upstreamPorts", upstream))).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
            throw new IllegalArgumentException("TUN start message exceeds 64 KiB");
    }
    public Map<String, Object> values() { return values; }
}
