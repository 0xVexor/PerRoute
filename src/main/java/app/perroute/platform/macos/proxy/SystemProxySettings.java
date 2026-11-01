package app.perroute.platform.macos.proxy;

import java.io.IOException;

/**
 * Port for a future authorized SystemConfiguration adapter. No native implementation is wired yet.
 * Reads must preserve absence, unknown fields and stable service IDs, or refuse mutation.
 */
public interface SystemProxySettings {
    Session lock() throws IOException, InterruptedException;

    interface Session extends AutoCloseable {
        /**
         * Return comparable per-service projections of persistent and effective values. Refuse a service
         * if authentication references or other plist values cannot safely survive endpoint replacement.
         */
        State read(String serviceId) throws IOException, InterruptedException;

        /**
         * Compare configured state under the configuration lock, commit replacement, then apply it.
         * May throw after a partial commit/apply. Equal dictionaries must still support applying
         * committed values to the running system. Do not use a read-then-write shell approximation.
         */
        void replace(String serviceId, ProxySettings expected, ProxySettings replacement) throws IOException, InterruptedException;
        /** Native normalization may remove disabled endpoints; fake/Windows semantics remain exact. */
        default boolean equivalent(ProxySettings expected, ProxySettings observed) { return expected.equals(observed); }
        @Override void close() throws IOException;
    }
    record State(ProxySettings configured, ProxySettings effective) {
        public State { java.util.Objects.requireNonNull(configured); java.util.Objects.requireNonNull(effective); }
    }
}
