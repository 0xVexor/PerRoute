package app.perroute.backend.api;

import java.io.IOException;

/** The backend verified a network change and finished cleanup of the previously owned session. */
public final class RoutingNetworkChangedException extends IOException {
    public RoutingNetworkChangedException() {
        super("The primary network changed; the previous routing session was restored.");
    }
}
