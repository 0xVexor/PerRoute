package app.perroute;

import app.perroute.platform.macos.MacApplicationCatalog;
import app.perroute.platform.macos.routing.MacPerAppRoutingPlan;
import app.perroute.platform.macos.routing.MacPerAppRoutingPlan.ExecutableRoute;
import app.perroute.util.Json;
import java.nio.file.*;
import java.util.*;

public final class MacosPerAppRoutingPlanTests {
    static int checks;
    static final String ENGINE = "/Applications/PerRoute.app/Contents/MacOS/PerRoute";
    static final String APP = "/Applications/Editor.app/Contents/MacOS/Editor";
    public static void main(String[] args) throws Exception {
        var input = new ArrayList<>(List.of(new ExecutableRoute(APP, 17890),
                new ExecutableRoute("/Applications/editor.app/Contents/MacOS/editor", 17890),
                new ExecutableRoute("/Applications/ویرایشگر.app/Contents/MacOS/Editor Helper", 17892)));
        var plan = new MacPerAppRoutingPlan(input, List.of(ENGINE));
        input.clear();
        var config = Json.object(plan.toJson());
        var inbound = (Map<?, ?>) ((List<?>)config.get("inbounds")).getFirst();
        check(Boolean.FALSE.equals(inbound.get("auto_route")), "Offline candidate cannot enable host routes");
        var route = (Map<?, ?>)config.get("route");
        var rules = (List<?>)route.get("rules");
        check(rules.size() == 3, "Same endpoint groups its exact executable paths");
        check(((Map<?, ?>)rules.getFirst()).get("process_path").equals(List.of(ENGINE)), "Engine exclusion precedes selected routes");
        check(route.get("final").equals("unmatched-direct"), "Unselected and unresolved attribution are explicitly direct");
        for (int i = 1; i < rules.size(); i++) {
            var rule = (Map<?, ?>)rules.get(i);
            check(rule.get("network").equals("tcp"), "Initial selected routing is TCP only");
            check(rule.containsKey("process_path") && !rule.containsKey("process_name") && !rule.containsKey("process_path_regex"), "No broad name or regex authority");
        }
        var outbounds = (List<?>)config.get("outbounds");
        check(outbounds.size() == 3, "One outbound per distinct endpoint");
        for (int i = 1; i < outbounds.size(); i++) check(((Map<?, ?>)outbounds.get(i)).get("server").equals("127.0.0.1"), "Endpoints are loopback only");
        check(plan.toJson().contains("ویرایشگر"), "Unicode and spaces survive JSON encoding");
        var reversed = new ArrayList<>(List.of(new ExecutableRoute("/Applications/ویرایشگر.app/Contents/MacOS/Editor Helper", 17892),
                new ExecutableRoute("/Applications/editor.app/Contents/MacOS/editor", 17890), new ExecutableRoute(APP,17890)));
        check(Json.object(plan.toJson()).equals(Json.object(new MacPerAppRoutingPlan(reversed,List.of(ENGINE)).toJson())), "Input order does not change routing policy");
        rejects(() -> plan.configuration().clear());
        @SuppressWarnings("unchecked") var immutableRules = (List<Object>) ((Map<?, ?>)plan.configuration().get("route")).get("rules");
        rejects(immutableRules::clear);
        for (String path : List.of("relative", "/a/../b", "/a/./b", "/a//b", "/a/", "/a\\b", "/a\nb", "/"))
            rejects(() -> new ExecutableRoute(path,17890));
        rejects(() -> new ExecutableRoute(null,17890));
        rejects(() -> new ExecutableRoute(APP,0)); rejects(() -> new ExecutableRoute(APP,65536));
        rejects(() -> new MacPerAppRoutingPlan(List.of(),List.of(ENGINE)));
        rejects(() -> new MacPerAppRoutingPlan(List.of(new ExecutableRoute(APP,17890)),List.of()));
        rejects(() -> new MacPerAppRoutingPlan(List.of(new ExecutableRoute(ENGINE,17890)),List.of(ENGINE)));
        rejects(() -> new MacPerAppRoutingPlan(List.of(new ExecutableRoute(APP,17890),new ExecutableRoute(APP,17892)),List.of(ENGINE)));
        rejects(() -> new MacPerAppRoutingPlan(List.of(new ExecutableRoute(APP,17890)),List.of(ENGINE,ENGINE)));
        var observed = new MacApplicationCatalog.Application("Editor",Path.of("/Applications/Editor.app"),Path.of(APP),"example.editor","","",false);
        check(ExecutableRoute.observedMainExecutable(observed,17890).executablePath().equals(APP), "Catalog observation maps to an exact main executable without inventing trust");
        var outside = new MacApplicationCatalog.Application("Editor",Path.of("/Applications/Other.app"),Path.of(APP),"example.editor","","",false);
        rejects(() -> ExecutableRoute.observedMainExecutable(outside,17890));
        if (args.length == 1) {
            Path output = Path.of(args[0]);
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output,plan.toJson());
        }
        System.out.println("macOS per-app routing plan: " + checks + " checks passed; no engine, interface or host setting changed");
    }
    static void rejects(Runnable work) {
        try { work.run(); } catch (IllegalArgumentException | UnsupportedOperationException expected) { checks++; return; }
        throw new AssertionError("Invalid or mutable routing plan accepted");
    }
    static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
}
