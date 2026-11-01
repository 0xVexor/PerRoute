package app.perroute.platform.macos.proxy;

import app.perroute.platform.macos.MacStateSession;
import app.perroute.util.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Explicit maintenance entry points run before the desktop, with noninteractive key access. */
public final class MacHelperMaintenance {
    private MacHelperMaintenance() { }

    public static int run(String operation) {
        try {
            if (!System.getProperty("os.name", "").equals("Mac OS X")) throw new IOException("Helper maintenance requires macOS");
            String resources = System.getProperty("compose.application.resources.dir");
            if (resources == null) throw new IOException("Run maintenance from the installed PerRoute app");
            Path library = Path.of(resources).resolve("../../Frameworks/libPerRouteProxyBridge.dylib").normalize();
            if (!Files.isRegularFile(library)) throw new IOException("The installed helper bridge is missing");
            var client = new HelperSystemProxyLifecycle.NativeClient(library);
            if (!Boolean.TRUE.equals(client.request("availability", Map.of()).get("managed")))
                throw new IOException("This installation does not use the managed proxy helper");
            if (operation.equals("--proxy-helper-status")) {
                System.out.println(Json.stringify(client.request("service-status", Map.of())));
            } else if (operation.equals("--proxy-helper-setup")) {
                client.request("prepare", Map.of());
                System.out.println(Json.stringify(client.request("service-status", Map.of())));
            } else if ((operation.equals("--proxy-helper-unregister") || operation.equals("--proxy-helper-unregister-interactive"))) {
                // The durable-state lock prevents maintenance racing a running desktop. Opening is
                // noninteractive by default; the explicit laptop-only flag may ask for Keychain access.
                try (var session = MacStateSession.openDefault(operation.equals("--proxy-helper-unregister-interactive"))) {
                    if (!(session.systemProxy() instanceof HelperSystemProxyLifecycle))
                        throw new IOException("Reopen the original managed installation to recover its proxy settings");
                    unregister(session.systemProxy(), client);
                }
                System.out.println(Json.stringify(Map.of("registered", false, "pending", false)));
            } else throw new IOException("Unknown helper maintenance operation");
            return 0;
        } catch (Exception | LinkageError failure) {
            System.err.println("Helper maintenance failed: " + failure.getMessage());
            return 1;
        }
    }

    static void unregister(MacSystemProxyLifecycle recovery, HelperSystemProxyLifecycle.Client client) throws Exception {
        recovery.restore();
        // Native code repeats recovery immediately before unregister. A successful request alone is
        // insufficient: asynchronous removal must also report the unregistered state before replacement.
        var result = client.request("unregister", Map.of());
        if (!Boolean.FALSE.equals(result.get("pending")) || !Boolean.FALSE.equals(result.get("registered")))
            throw new IOException("Service removal is incomplete. Check status before replacing or deleting the app");
    }
}
