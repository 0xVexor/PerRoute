package app.perroute;

import app.perroute.platform.macos.routing.MacPerAppRoutingPlan;
import app.perroute.platform.macos.routing.MacPerAppRoutingPlan.ExecutableRoute;
import app.perroute.util.Json;
import java.nio.file.*;
import java.util.*;

/** Development fixture: reuse the production rules with an isolated TCP listener, never a TUN. */
public final class MacosProcessRoutingFixture {
    public static void main(String[] args) throws Exception {
        if (args.length != 9) throw new IllegalArgumentException("Expected fixture directory, engine, four ports, phase and output");
        Path directory = Path.of(args[0]).toRealPath();
        var selected = new ArrayList<>(List.of(
                new ExecutableRoute(directory.resolve("Selected.app/Contents/MacOS/Client").toString(), Integer.parseInt(args[2])),
                new ExecutableRoute(directory.resolve("Other.app/Contents/MacOS/Client").toString(), Integer.parseInt(args[3]))));
        if (args[7].equals("helper")) selected.add(new ExecutableRoute(
                directory.resolve("Selected.app/Contents/Frameworks/Worker.app/Contents/MacOS/Client").toString(), Integer.parseInt(args[3])));
        else if (!args[7].equals("main")) throw new IllegalArgumentException("Unknown fixture phase");
        var plan = new MacPerAppRoutingPlan(selected, List.of(Path.of(args[1]).toRealPath().toString(),
                directory.resolve("engine/Client").toString()));
        // Replace only the capture adapter. Rules, endpoint bindings and exclusions remain exactly
        // those emitted by the production compiler, so this proof cannot enable transparent capture.
        var configuration = new LinkedHashMap<>(plan.configuration());
        configuration.put("inbounds", List.of(Map.of("type", "direct", "tag", "perroute-tun-spike",
                "listen", "127.0.0.1", "listen_port", Integer.parseInt(args[4]), "network", "tcp",
                "override_address", "127.0.0.1", "override_port", Integer.parseInt(args[5]))));
        if (!args[6].equals("loopback-only")) throw new IllegalArgumentException("Expected explicit loopback fixture");
        Files.writeString(Path.of(args[8]), Json.stringify(configuration));
    }
}
