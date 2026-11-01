package app.perroute.backend.api;

import app.perroute.model.ConnectionMode;
import java.util.Set;

public record BackendCapabilities(Set<ConnectionMode> modes, boolean coreInstallation, boolean updates,
                                  boolean applicationDiscovery, boolean networkRecovery, boolean persistentState, boolean backupRestore) {
    public BackendCapabilities { modes = Set.copyOf(modes); }
    public boolean supports(ConnectionMode mode) { return modes.contains(mode); }
    public boolean perApp() { return supports(ConnectionMode.NATIVE_PER_APP); }
    public static BackendCapabilities windows() {
        return new BackendCapabilities(Set.of(ConnectionMode.values()), true, true, true, true, true, true);
    }
    public static BackendCapabilities macosPerApp() {
        return new BackendCapabilities(Set.of(ConnectionMode.BROWSER_PROXY, ConnectionMode.SYSTEM_PROXY,
                ConnectionMode.NATIVE_PER_APP), false, false, true, false, true, false);
    }
    public static BackendCapabilities macosSystemProxy() {
        return new BackendCapabilities(Set.of(ConnectionMode.BROWSER_PROXY, ConnectionMode.SYSTEM_PROXY), false, false, true, false, true, false);
    }
    public static BackendCapabilities macosLocal() {
        return new BackendCapabilities(Set.of(ConnectionMode.BROWSER_PROXY), false, false, true, false, true, false);
    }
    public static BackendCapabilities macosLocalPreview() {
        return new BackendCapabilities(Set.of(ConnectionMode.BROWSER_PROXY), false, false, true, false, false, false);
    }
}
