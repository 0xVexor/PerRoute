package app.perroute.platform.macos.proxy;

import app.perroute.util.Json;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/** Loads the real bridge in a CLI JVM, where service registration must be refused without interaction. */
public final class HelperBridgeTests {
    public interface RawBridge extends Library {
        Pointer perroute_helper_request(byte[] request);
        void perroute_helper_free(Pointer value);
    }
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        var client = new HelperSystemProxyLifecycle.NativeClient(path);
        if (!Boolean.FALSE.equals(client.request("availability", Map.of()).get("managed"))) throw new AssertionError("CLI JVM selected managed helper");
        for (String operation : new String[]{"prepare", "prepare-existing", "apply", "restore", "status", "service-status", "unregister", "unknown"}) {
            try { client.request(operation, Map.of()); throw new AssertionError("Unbundled caller accepted: " + operation); }
            catch (IOException expected) {
                if (!expected.getMessage().contains("signed PerRoute")) throw expected;
            }
        }
        var bridge = Native.load(path.toAbsolutePath().toString(), RawBridge.class);
        for (String malformed : new String[]{"[]", "{broken", "{\"version\":2,\"operation\":\"availability\"}", "x".repeat(65537)}) {
            Pointer result = bridge.perroute_helper_request((malformed + '\0').getBytes(StandardCharsets.UTF_8));
            if (result == null) throw new AssertionError("Missing error response");
            try {
                var response = Json.object(result.getString(0, "UTF-8"));
                if (!Boolean.FALSE.equals(response.get("ok"))) throw new AssertionError("Malformed request accepted");
            } finally { bridge.perroute_helper_free(result); }
        }
        System.out.println("Native helper bridge: 13 checks passed; no registration, authorization or host writes");
    }
}
