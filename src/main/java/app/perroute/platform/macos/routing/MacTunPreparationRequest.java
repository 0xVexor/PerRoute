package app.perroute.platform.macos.routing;

import app.perroute.util.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;
import app.perroute.platform.macos.routing.MacPerAppRoutingPlan.ExecutableRoute;

/** Typed preparation message, with no execution, capture or recovery authority. */
public final class MacTunPreparationRequest {
    private final String json;

    public MacTunPreparationRequest(List<ExecutableRoute> selected) {
        Objects.requireNonNull(selected);
        if (selected.isEmpty() || selected.size() > 256)
            throw new IllegalArgumentException("Expected bounded executable bindings");
        var bindings = new TreeMap<String, Integer>();
        for (var binding : selected) {
            Objects.requireNonNull(binding);
            if (bindings.putIfAbsent(binding.executablePath(), binding.socksPort()) != null)
                throw new IllegalArgumentException("Duplicate executable routing authority");
        }
        json = Json.stringify(Map.of("version", 1, "operation", "prepare-tun", "bindings",
                bindings.entrySet().stream().map(entry -> Map.of("executable", entry.getKey(), "socksPort", entry.getValue())).toList()));
        if (json.getBytes(StandardCharsets.UTF_8).length > 65536)
            throw new IllegalArgumentException("TUN preparation message exceeds 64 KiB");
    }

    public String toJson() { return json; }
}
