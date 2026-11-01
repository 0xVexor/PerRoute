package app.perroute.platform.macos.spike;

import app.perroute.util.Json;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Isolated feasibility harness. Normal PerRoute startup never loads this library or activates an extension. */
public final class MacExtensionSpike {
    public interface Bridge extends Library {
        Pointer perroute_extension_snapshot();
        void perroute_extension_free(Pointer buffer);
        int perroute_extension_request(String identifier, int activate);
    }

    private static String snapshot(Bridge bridge) {
        Pointer buffer = bridge.perroute_extension_snapshot();
        if (buffer == null) throw new IllegalStateException("Native bridge returned no snapshot");
        try { return buffer.getString(0, "UTF-8"); }
        finally { bridge.perroute_extension_free(buffer); }
    }

    public static void main(String[] args) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.equals("mac os x") && !os.equals("darwin")) throw new UnsupportedOperationException("The extension spike requires macOS");
        if (args.length != 1 && args.length != 3) {
            throw new IllegalArgumentException("Usage: MacExtensionSpike <library.dylib> [--activate|--deactivate <extension-id>]");
        }
        if (args.length == 3 && !args[1].equals("--activate") && !args[1].equals("--deactivate")) {
            throw new IllegalArgumentException("Choose --activate or --deactivate explicitly");
        }
        Path library = Path.of(args[0]).toAbsolutePath();
        if (!Files.isRegularFile(library)) throw new IllegalArgumentException("Native bridge not found: " + library);
        Bridge bridge = Native.load(library.toString(), Bridge.class);
        System.out.println(snapshot(bridge));
        if (args.length == 1) return;
        if (bridge.perroute_extension_request(args[2], args[1].equals("--activate") ? 1 : 0) == 0) {
            throw new IllegalStateException("Request refused: " + snapshot(bridge));
        }
        // Keep the request alive during user approval. Completion belongs to Apple's callbacks, not submission.
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(2).toNanos();
        String previous = "";
        while (System.nanoTime() < deadline) {
            String current = snapshot(bridge);
            if (!current.equals(previous)) { System.out.println(current); previous = current; }
            var result = Json.object(current);
            if (!Boolean.TRUE.equals(result.get("requestPending"))) {
                if (!"completed".equals(result.get("state"))) throw new IllegalStateException(current);
                return;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Request is still pending; activation has not been verified. " + snapshot(bridge));
    }
}
