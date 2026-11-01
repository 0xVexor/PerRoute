package app.perroute;

import app.perroute.platform.macos.routing.MacPerAppRoutingPlan;
import app.perroute.platform.macos.routing.MacPerAppRoutingPlan.ExecutableRoute;
import app.perroute.platform.macos.routing.MacTunPreparationRequest;
import app.perroute.util.Json;
import java.nio.file.*;
import java.util.*;

public final class MacosTunPreparationTests {
    private static int checks;
    private static final String APP = "/Applications/Editor.app/Contents/MacOS/Editor";
    public static void main(String[] args) throws Exception {
        var selected = new ArrayList<>(List.of(new ExecutableRoute(APP, 17890),
                new ExecutableRoute("/Applications/editor.app/Contents/MacOS/editor", 17890),
                new ExecutableRoute("/Applications/ویرایشگر.app/Contents/MacOS/Editor Helper", 17892),
                new ExecutableRoute("/Applications/Editor.app/Contents/Frameworks/Worker.app/Contents/MacOS/Worker", 17892)));
        var request = new MacTunPreparationRequest(selected);
        var exclusions = List.of("/Applications/PerRoute.app/Contents/MacOS/PerRoute", "/trusted/engine");
        var expected = new MacPerAppRoutingPlan(selected, exclusions).configuration();
        String snapshot = request.toJson();
        Collections.reverse(selected);
        check(new MacTunPreparationRequest(selected).toJson().equals(snapshot), "Input ordering has no authority");
        selected.clear();
        check(request.toJson().equals(snapshot), "Request owns an immutable snapshot");
        var wire = Json.object(snapshot);
        check(wire.keySet().equals(Set.of("version", "operation", "bindings")), "Only typed preparation fields");
        check(wire.get("operation").equals("prepare-tun"), "Preparation operation only");
        check(((List<?>)wire.get("bindings")).size() == 4, "Exact case-sensitive main/helper paths retained");
        refuse(() -> new MacTunPreparationRequest(List.of()));
        refuse(() -> new MacTunPreparationRequest(List.of(new ExecutableRoute(APP, 1), new ExecutableRoute(APP, 2))));
        var many = new ArrayList<ExecutableRoute>();
        for (int i = 0; i < 257; i++) many.add(new ExecutableRoute("/app/" + i, 1));
        refuse(() -> new MacTunPreparationRequest(many));
        var oversized = new ArrayList<ExecutableRoute>();
        for (int i = 0; i < 16; i++) oversized.add(new ExecutableRoute("/" + "ژ".repeat(4000) + i, 1));
        refuse(() -> new MacTunPreparationRequest(oversized));
        var bounds = new MacTunPreparationRequest(List.of(new ExecutableRoute("/first", 1), new ExecutableRoute("/last", 65535)));
        check(((List<?>)Json.object(bounds.toJson()).get("bindings")).size() == 2, "SOCKS port bounds supported");
        Path output = Path.of(args[0]);
        Files.createDirectories(output.getParent());
        Files.writeString(output, Json.stringify(Map.of("request", wire, "trusted", exclusions, "expected", expected)));
        System.out.println("macOS TUN preparation: " + checks + " checks passed; native parity fixture written");
    }
    private static void refuse(Runnable action) {
        try { action.run(); throw new AssertionError("Expected refusal"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void check(boolean passed, String message) { checks++; if (!passed) throw new AssertionError(message); }
}
