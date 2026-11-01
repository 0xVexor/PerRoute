package app.perroute.platform.macos;

import app.perroute.model.AppState;
import app.perroute.model.ConnectionMode;
import app.perroute.persistence.AppStateStore;
import app.perroute.persistence.PrivateStateFiles;
import app.perroute.service.SingleInstanceLock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

/** Opens all persistent data before constructing a controller that could save or migrate anything. */
public final class MacStateSession implements AutoCloseable {
    private final SingleInstanceLock lock;
    private final MacStateCipher cipher;
    private final AppStateStore store;
    private final AppState state;
    private final EncryptedPreferences preferences;
    private final app.perroute.platform.macos.proxy.HelperSystemProxyLifecycle.RoutingServices routingServices;
    private boolean closed;
    private MacStateSession(SingleInstanceLock lock, MacStateCipher cipher, AppStateStore store,
                            AppState state, EncryptedPreferences preferences, Path recoveryFile) throws IOException {
        this.lock = lock; this.cipher = cipher; this.store = store; this.state = state; this.preferences = preferences;
        routingServices = app.perroute.platform.macos.proxy.HelperSystemProxyLifecycle.preferredServices(
                new app.perroute.platform.macos.proxy.EncryptedProxyJournal(recoveryFile, cipher), recoveryFile.resolveSibling("helper-proxy.pending"));
    }
    public static MacStateSession openDefault() throws IOException { return openDefault(false); }
    public static MacStateSession openDefault(boolean interactiveDesktop) throws IOException {
        Path path = Path.of(System.getProperty("user.home"), "Library", "Application Support", "PerRoute");
        try { return open(path, interactiveDesktop ? MacKeychain.interactiveDesktop() : new MacKeychain()); }
        catch (LinkageError error) { throw new IOException("macOS Keychain Services could not be loaded", error); }
    }
    public static MacStateSession open(Path directory, StateKeychain keychain) throws IOException {
        Path root = PrivateStateFiles.directory(directory);
        Path lockFile = root.resolve("perroute.lock");
        if (Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)) PrivateStateFiles.requireRegular(lockFile);
        SingleInstanceLock lock = SingleInstanceLock.acquire(root);
        if (lock == null) throw new IOException("PerRoute is already using this macOS state directory");
        MacStateCipher cipher = null;
        try {
            PrivateStateFiles.requireRegular(lockFile);
            Path stateFile = root.resolve("state.bin");
            Path preferenceFile = root.resolve("preferences.bin");
            Path recoveryFile = root.resolve("proxy-recovery.bin");
            // Never silently migrate a raw local key or a copied Windows state into a new identity.
            if (Files.exists(root.resolve("state.bin.key"), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Legacy local-key state requires explicit migration. Existing data was preserved.");
            boolean existing = Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS) || Files.exists(preferenceFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(recoveryFile, LinkOption.NOFOLLOW_LINKS);
            String account = account(root);
            byte[] key = keychain.read(account);
            if (key == null) {
                if (existing) throw new IOException("The Keychain state key is missing. Existing files were preserved; restore the original key before retrying.");
                byte[] candidate = new byte[32];
                new SecureRandom().nextBytes(candidate);
                try {
                    boolean created = keychain.add(account, candidate);
                    key = keychain.read(account);
                    if (created && (key == null || !MessageDigest.isEqual(candidate, key))) {
                        if (key != null) Arrays.fill(key, (byte) 0);
                        throw new IOException("Keychain state key failed read-back verification");
                    }
                } finally { Arrays.fill(candidate, (byte) 0); }
                if (key == null) throw new IOException("Keychain state key was not available after creation");
            }
            try { cipher = new MacStateCipher(key); } finally { Arrays.fill(key, (byte) 0); }
            var store = new AppStateStore(stateFile, cipher);
            AppState state = store.load();
            if (!store.exists()) state.setConnectionMode(ConnectionMode.BROWSER_PROXY);
            var preferences = new EncryptedPreferences(new AppStateStore(preferenceFile, cipher));
            // Defaults only apply to new keys, so previously accepted user preferences survive restart.
            if (preferences.get("v3.initialized", null) == null) {
                for (String name : new String[]{"v3.qualityBackground", "v3.routeMonitoring", "v3.trafficGateway", "autoReconnect", "autoFailover", "autoSpeedTest"})
                    preferences.putBoolean(name, false);
                preferences.putBoolean("v3.initialized", true);
            }
            return new MacStateSession(lock, cipher, store, state, preferences, recoveryFile);
        } catch (Throwable error) {
            if (cipher != null) cipher.close();
            lock.close();
            if (error instanceof IOException io) throw io;
            throw new IOException("Could not open secure macOS settings. Existing data was preserved.", error);
        }
    }
    public static String account(Path directory) {
        try {
            return "state-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(directory.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public app.perroute.platform.macos.proxy.MacSystemProxyLifecycle systemProxy() { return routingServices.systemProxy(); }
    public app.perroute.platform.macos.proxy.HelperSystemProxyLifecycle.RoutingServices routingServices() { return routingServices; }
    public AppStateStore store() { return store; }
    public AppState state() { return state; }
    public EncryptedPreferences preferences() { return preferences; }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        cipher.close();
        lock.close();
    }
}
