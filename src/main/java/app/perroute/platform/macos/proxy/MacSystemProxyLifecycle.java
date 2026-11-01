package app.perroute.platform.macos.proxy;

/** The desktop owns this lifecycle; the settings port alone never enables system routing. */
public interface MacSystemProxyLifecycle {
    void apply() throws Exception;
    void restore() throws Exception;
    /** Direct writers have no persistent helper session; helper implementations must detect lost custody. */
    default void verifySession() throws Exception { }
}
