package app.perroute.platform.macos.proxy;

import java.io.IOException;
import java.util.Objects;
import static app.perroute.platform.macos.proxy.NetworkServiceInventory.*;

/** Bounded optimistic observation. Configuration locking belongs to the native writer session. */
public final class StableProxyReadback {
    @FunctionalInterface public interface Inspector { NetworkServiceInventory inspect() throws IOException; }
    @FunctionalInterface public interface Pause { void await() throws InterruptedException; }
    private final Inspector inspector;
    private final Pause pause;
    private final int attempts;
    public StableProxyReadback(Inspector inspector) { this(inspector, () -> Thread.sleep(100), 30); }
    public StableProxyReadback(Inspector inspector, Pause pause, int attempts) {
        this.inspector = Objects.requireNonNull(inspector); this.pause = Objects.requireNonNull(pause);
        if (attempts < 2 || attempts > 100) throw new IllegalArgumentException("Invalid observation bound");
        this.attempts = attempts;
    }
    public SystemProxySettings.State read(String serviceId) throws IOException, InterruptedException {
        NetworkServiceInventory previous = null;
        for (int i = 0; i < attempts; i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Proxy observation cancelled");
            var current = inspector.inspect();
            // Compare the complete immutable observations, not just the target endpoint. A service-set,
            // interface, Setup, runtime or scoped-context change invalidates the previous sample.
            if (current.equals(previous)) {
                // Commit can precede apply. Keep configured and observed scope separate when Setup lags;
                // ownership must see the disagreement so it can roll back a partially committed write.
                var result = new EffectiveProxyProjection().project(current, false).stream()
                        .filter(value -> value.serviceId().equals(serviceId)).findFirst();
                if (result.isPresent() && result.get().scoped().isPresent()) {
                    var service = current.services().stream().filter(value -> value.id().equals(serviceId)).findFirst().orElseThrow();
                    return new SystemProxySettings.State(service.configured().settings().orElseThrow(), result.get().scoped().orElseThrow());
                }
            }
            previous = current;
            if (i + 1 < attempts) pause.await();
        }
        throw new IOException("Network-service proxy observations did not settle into a supported configuration");
    }
}
