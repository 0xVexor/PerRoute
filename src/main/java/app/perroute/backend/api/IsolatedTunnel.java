package app.perroute.backend.api;

import java.util.List;

/** A probe or workspace core whose lifetime is independent of the main connection. */
public interface IsolatedTunnel extends AutoCloseable {
    int socksPort();
    boolean alive();
    List<String> lastLines();
    @Override void close();
}
