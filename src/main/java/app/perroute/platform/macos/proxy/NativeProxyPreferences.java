package app.perroute.platform.macos.proxy;

import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import static app.perroute.platform.macos.proxy.NativePropertyLists.*;

/** Uses configd's authorization path; the JVM never runs as root or receives an admin password. */
final class NativeProxyPreferences implements MacSystemProxySettings.Access {
    interface Preferences extends MacNetworkServiceInspector.SystemConfiguration {
        Pointer SCPreferencesCreateWithAuthorization(Pointer allocator, Pointer name, Pointer id, Pointer authorization);
        byte SCPreferencesLock(Pointer preferences, byte wait);
        byte SCPreferencesUnlock(Pointer preferences);
        void SCPreferencesSynchronize(Pointer preferences);
        Pointer SCNetworkServiceCopy(Pointer preferences, Pointer id);
        byte SCNetworkProtocolSetConfiguration(Pointer protocol, Pointer configuration);
        byte SCPreferencesCommitChanges(Pointer preferences);
        byte SCPreferencesApplyChanges(Pointer preferences);
    }
    interface Security extends Library {
        int AuthorizationCreate(Pointer rights, Pointer environment, int flags, PointerByReference authorization);
        int AuthorizationFree(Pointer authorization, int flags);
    }
    private static final class Api {
        static final Preferences SC = Native.load("/System/Library/Frameworks/SystemConfiguration.framework/SystemConfiguration", Preferences.class);
        static final Security SECURITY = Native.load("/System/Library/Frameworks/Security.framework/Security", Security.class);
    }
    private Pointer prefs;
    private Pointer authorization;
    private boolean locked;
    private NativeProxyPreferences() { }
    static NativeProxyPreferences open() throws IOException {
        if (!app.perroute.platform.bootstrap.PlatformBootstrap.isMac(System.getProperty("os.name")))
            throw new UnsupportedOperationException("Native proxy settings require macOS");
        var result = new NativeProxyPreferences();
        Pointer name = null;
        try {
            var reference = new PointerByReference();
            // Same creation flags as Apple's scutil. SystemConfiguration requests the required rights.
            int status = Api.SECURITY.AuthorizationCreate(null, null, 0, reference);
            if (status != 0) throw new IOException("Network-preference authorization failed (status " + status + ")");
            result.authorization = reference.getValue();
            if (result.authorization == null) throw new IOException("Network-preference authorization was not created");
            name = text("PerRoute system proxy");
            result.prefs = Api.SC.SCPreferencesCreateWithAuthorization(null, name, null, result.authorization);
            if (result.prefs == null) throw result.failure("create authorized preferences");
            Api.SC.SCPreferencesSynchronize(result.prefs);
            // Refuse a busy session rather than blocking indefinitely behind another preferences client.
            if (Api.SC.SCPreferencesLock(result.prefs, (byte) 0) == 0) throw result.failure("lock network preferences");
            result.locked = true;
            return result;
        } catch (Throwable error) {
            try { result.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            if (error instanceof IOException io) throw io;
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IOException("Could not initialize native network preferences", error);
        } finally { release(name); }
    }
    private interface ProtocolOperation<T> { T run(Pointer protocol) throws IOException; }
    private <T> T protocol(String id, ProtocolOperation<T> operation) throws IOException {
        if (prefs == null || !locked) throw new IOException("Native preferences are closed");
        if (id == null || id.isBlank() || (id.contains("/") || id.indexOf(0) >= 0)) throw new IOException("Invalid stable network-service ID");
        Pointer key = text(id), type = null, service = null, protocol = null;
        try {
            service = Api.SC.SCNetworkServiceCopy(prefs, key);
            if (service == null || Api.SC.SCNetworkServiceGetEnabled(service) == 0) throw failure("read enabled network service");
            type = text("Proxies"); protocol = Api.SC.SCNetworkServiceCopyProtocol(service, type);
            if (protocol == null || Api.SC.SCNetworkProtocolGetEnabled(protocol) == 0) throw failure("read enabled proxy protocol");
            return operation.run(protocol);
        } finally { release(protocol); release(service); release(type); release(key); }
    }
    @Override public ProxySettings current(String id) throws IOException {
        return protocol(id, protocol -> new NativePropertyLists().dictionary(Api.SC.SCNetworkProtocolGetConfiguration(protocol)));
    }
    @Override public void set(String id, ProxySettings value) throws IOException {
        protocol(id, protocol -> {
            Pointer dictionary = NativeProxyEncoding.create(value);
            try { if (Api.SC.SCNetworkProtocolSetConfiguration(protocol, dictionary) == 0) throw failure("stage proxy settings"); }
            finally { release(dictionary); }
            return null;
        });
    }
    @Override public void commit() throws IOException {
        if (Api.SC.SCPreferencesCommitChanges(prefs) == 0) {
            IOException error = failure("commit proxy preferences");
            // Discard staged cache on failure so rollback reads actual persisted values.
            Api.SC.SCPreferencesSynchronize(prefs);
            throw error;
        }
    }
    @Override public void apply() throws IOException {
        if (Api.SC.SCPreferencesApplyChanges(prefs) == 0) throw failure("apply committed proxy preferences");
    }
    private IOException failure(String action) { return new IOException("Could not " + action + " (status " + Api.SC.SCError() + ")"); }
    @Override public void close() throws IOException {
        IOException failure = null;
        if (locked) {
            locked = false;
            if (Api.SC.SCPreferencesUnlock(prefs) == 0) failure = failure("unlock network preferences");
        }
        release(prefs); prefs = null;
        if (authorization != null) { Api.SECURITY.AuthorizationFree(authorization, 0); authorization = null; }
        if (failure != null) throw failure;
    }
}
