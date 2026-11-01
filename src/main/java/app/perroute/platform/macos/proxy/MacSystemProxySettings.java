package app.perroute.platform.macos.proxy;

import java.io.IOException;
import java.util.Objects;

/** Authorized native writes. A session owns the preferences lock until verification/rollback ends. */
public final class MacSystemProxySettings implements SystemProxySettings {
    interface Access extends AutoCloseable {
        ProxySettings current(String id) throws IOException;
        void set(String id, ProxySettings value) throws IOException;
        void commit() throws IOException;
        void apply() throws IOException;
        @Override void close() throws IOException;
    }
    @FunctionalInterface interface Factory { Access open() throws IOException, InterruptedException; }
    private final Factory factory;
    private final StableProxyReadback readback;
    public MacSystemProxySettings() {
        this(NativeProxyPreferences::open, new StableProxyReadback(new MacNetworkServiceInspector()::inspect));
    }
    MacSystemProxySettings(Factory factory, StableProxyReadback readback) {
        this.factory = Objects.requireNonNull(factory); this.readback = Objects.requireNonNull(readback);
    }
    @Override public Session lock() throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Proxy authorization cancelled");
        Access access = factory.open();
        return new Session() {
            private boolean closed;
            private void requireOpen() throws IOException { if (closed) throw new IOException("Proxy session is closed"); }
            @Override public State read(String id) throws IOException, InterruptedException {
                requireOpen();
                var observed = readback.read(id);
                if (!access.current(id).equals(observed.configured())) throw new IOException("Network preferences changed during read-back");
                return observed;
            }
            @Override public boolean equivalent(ProxySettings expected, ProxySettings observed) {
                return ManualProxySemantics.equivalent(expected, observed);
            }
            @Override public void replace(String id, ProxySettings expected, ProxySettings replacement) throws IOException, InterruptedException {
                requireOpen();
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Proxy update cancelled");
                try { ManualProxySemantics.normalize(replacement); }
                catch (IllegalArgumentException | ArithmeticException error) { throw new IOException("Unsupported proxy replacement"); }
                if (!access.current(id).equals(expected)) throw new IOException("Network preferences changed before replacement");
                access.set(id, replacement);
                access.commit();
                access.apply();
                long deadline = System.nanoTime() + 6_000_000_000L;
                do {
                    var actual = read(id);
                    if (!actual.configured().equals(replacement)) throw new IOException("Committed proxy configuration changed");
                    if (equivalent(replacement, actual.effective())) return;
                    Thread.sleep(100);
                } while (System.nanoTime() < deadline);
                throw new IOException("Committed proxy settings were not applied; recovery is required");
            }
            @Override public void close() throws IOException {
                if (!closed) { closed = true; access.close(); }
            }
        };
    }
}
