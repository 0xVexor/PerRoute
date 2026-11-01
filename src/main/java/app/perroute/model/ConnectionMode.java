package app.perroute.model;

public enum ConnectionMode {
    NATIVE_PER_APP("Smart per-app - route only the applications you select"),
    BROWSER_PROXY("Local proxy - manual SOCKS5 and HTTP endpoints"),
    SYSTEM_PROXY("System proxy — applications that use system proxy settings"),
    TUN_COMPATIBILITY("TUN — full packet tunnel; unavailable in no-admin mode");

    private final String label;

    ConnectionMode(String label) {
        this.label = label;
    }

    @Override public String toString() {
        return label;
    }
}
