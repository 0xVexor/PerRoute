package app.perroute.platform.bootstrap;

import app.perroute.backend.api.PlatformServices;
import app.perroute.platform.windows.WindowsConnectionBackend;
import app.perroute.service.AppDirectories;

import java.nio.file.Path;
import java.util.Locale;

/** The single production selection point, including guard and recovery launches. */
public final class PlatformBootstrap {
    private PlatformBootstrap() {}

    public static PlatformServices create() {
        String os = System.getProperty("os.name", "");
        requireWindows(os);
        return forOs(os, AppDirectories.dataDirectory());
    }

    public static PlatformServices forOs(String osName, Path dataDirectory) {
        if (isMac(osName)) {
            var runtime = app.perroute.service.XrayRuntime.forPlatform(osName, System.getProperty("os.arch"),
                    AppDirectories.coreDirectory());
            var backend = new app.perroute.platform.macos.MacConnectionBackend(runtime, dataDirectory, true);
            return new PlatformServices(backend, backend);
        }
        requireWindows(osName);
        var backend = new WindowsConnectionBackend(dataDirectory);
        return new PlatformServices(backend, backend);
    }

    public static PlatformServices forMac(Path runtimeDirectory, app.perroute.platform.macos.proxy.MacSystemProxyLifecycle systemProxy) {
        if (!isMac(System.getProperty("os.name"))) throw new UnsupportedOperationException("macOS lifecycle requires macOS");
        var runtime = app.perroute.service.XrayRuntime.forPlatform(System.getProperty("os.name"), System.getProperty("os.arch"), AppDirectories.coreDirectory());
        var backend = new app.perroute.platform.macos.MacConnectionBackend(runtime, runtimeDirectory, true, java.util.Objects.requireNonNull(systemProxy));
        return new PlatformServices(backend, backend);
    }

    public static PlatformServices forMac(Path runtimeDirectory,
            app.perroute.platform.macos.proxy.HelperSystemProxyLifecycle.RoutingServices services) {
        if (!isMac(System.getProperty("os.name"))) throw new UnsupportedOperationException("macOS lifecycle requires macOS");
        var runtime = app.perroute.service.XrayRuntime.forPlatform(System.getProperty("os.name"),System.getProperty("os.arch"),AppDirectories.coreDirectory());
        var backend = new app.perroute.platform.macos.MacConnectionBackend(runtime,runtimeDirectory,true,
                java.util.Objects.requireNonNull(services.systemProxy()),services.perApp());
        return new PlatformServices(backend,backend);
    }

    public static boolean isMac(String name) {
        String os = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        return os.equals("mac os x") || os.equals("darwin");
    }

    private static void requireWindows(String osName) {
        String normalized = osName == null ? "" : osName.trim().toLowerCase(Locale.ROOT);
        // Darwin contains "win", so substring matching would select the wrong native implementation.
        if (!normalized.equals("windows") && !normalized.startsWith("windows ")) {
            throw new UnsupportedOperationException("PerRoute does not yet support this operating system: " + osName);
        }
    }
}
