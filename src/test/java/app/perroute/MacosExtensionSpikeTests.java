package app.perroute;

import app.perroute.platform.macos.spike.MacExtensionSpike;
import app.perroute.util.Json;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.util.Map;

/** Exercises the actual native ABI without submitting an OS request or installing a proxy configuration. */
public final class MacosExtensionSpikeTests {
    private static int assertions;

    public static void main(String[] args) {
        MacExtensionSpike.Bridge bridge = Native.load(args[0], MacExtensionSpike.Bridge.class);
        Map<String, Object> initial = snapshot(bridge);
        check("idle".equals(initial.get("state")), "Inspection must not initiate activation");
        check(Boolean.FALSE.equals(initial.get("requestPending")), "Inspection has no pending OS request");
        check(initial.get("bundlePath") instanceof String path && !path.isBlank(), "Native bundle path is observable");
        check(initial.get("bundleIdentifier") instanceof String, "An absent bundle identity remains explicit");
        check(bridge.perroute_extension_request("", 1) == 0, "Empty activation identifier is refused before OS submission");
        Map<String, Object> refused = snapshot(bridge);
        check("invalid-container".equals(refused.get("state")), "Refused request has an explicit container error");
        check(Boolean.FALSE.equals(refused.get("requestPending")), "Refusal leaves no pending OS request");
        check(bridge.perroute_extension_request("", 0) == 0, "Empty deactivation identifier is also refused");
        // Repeated copies exercise native buffer ownership through JNA, including caller-side release.
        for (int i = 0; i < 100; i++) snapshot(bridge);
        System.out.println("macOS extension bridge tests passed (" + assertions + " assertions)");
    }

    private static Map<String, Object> snapshot(MacExtensionSpike.Bridge bridge) {
        Pointer buffer = bridge.perroute_extension_snapshot();
        if (buffer == null) throw new AssertionError("Native snapshot allocation failed");
        try { return Json.object(buffer.getString(0, "UTF-8")); }
        finally { bridge.perroute_extension_free(buffer); }
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
