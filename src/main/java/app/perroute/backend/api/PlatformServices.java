package app.perroute.backend.api;

import java.util.Objects;

public record PlatformServices(ConnectionBackend connections, StartupRecovery recovery) {
    public PlatformServices {
        Objects.requireNonNull(connections, "connections");
        Objects.requireNonNull(recovery, "recovery");
    }
}
