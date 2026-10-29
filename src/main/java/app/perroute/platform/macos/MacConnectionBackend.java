package app.perroute.platform.macos;

import app.perroute.backend.api.*;
import app.perroute.model.ApplicationIdentity;
import app.perroute.backend.core.NetworkDiagnostics;
import app.perroute.gateway.AppGateway;
import app.perroute.gateway.GatewayConnection;
import app.perroute.service.ProxyHealthCheck;
import app.perroute.service.XrayConfigGenerator;
import app.perroute.service.XrayProcessManager;
import app.perroute.service.XrayRuntime;
import app.perroute.service.XrayStatsReader;
import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CopyOnWriteArrayList;
import app.perroute.platform.macos.routing.MacPerAppRouting;
import app.perroute.platform.macos.routing.MacTunSessionClient;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Owns its local Xray child and an optional, explicitly wired system-proxy lifecycle. */
public final class MacConnectionBackend implements ConnectionBackend, StartupRecovery {
    private final BackendCapabilities capabilities;
    private final MacApplicationCatalog catalog;
    private final MacApplicationFamilies families;
    private final List<Consumer<String>> logs = new CopyOnWriteArrayList<>();
    private final List<Consumer<GatewayConnection>> connections = new CopyOnWriteArrayList<>();
    private final AppGateway gateway = new AppGateway(value -> connections.forEach(listener -> listener.accept(value)),
            line -> logs.forEach(listener -> listener.accept(line)));
    private final MacPerAppRouting perApp;
    private final app.perroute.platform.macos.proxy.MacSystemProxyLifecycle systemProxy;
    private final XrayRuntime runtime;
    private final XrayProcessManager process;
    private final NetworkDiagnostics diagnostics = new NetworkDiagnostics();

    public MacConnectionBackend(XrayRuntime runtime, Path data) { this(runtime, data, false); }
    public MacConnectionBackend(XrayRuntime runtime, Path data, boolean persistent) {
        this(runtime, data, persistent, null);
    }
    public MacConnectionBackend(XrayRuntime runtime, Path data, boolean persistent,
                                app.perroute.platform.macos.proxy.MacSystemProxyLifecycle systemProxy) {
        this(runtime,data,persistent,systemProxy,null);
    }
    public MacConnectionBackend(XrayRuntime runtime, Path data, boolean persistent,
            app.perroute.platform.macos.proxy.MacSystemProxyLifecycle systemProxy, MacTunSessionClient session) {
        this(runtime,data,persistent,systemProxy,session,MacApplicationCatalog.system());
    }
    public MacConnectionBackend(XrayRuntime runtime, Path data, boolean persistent,
            app.perroute.platform.macos.proxy.MacSystemProxyLifecycle systemProxy, MacTunSessionClient session,
            MacApplicationCatalog catalog) {
        this.catalog = java.util.Objects.requireNonNull(catalog);
        this.families = new MacApplicationFamilies(catalog);
        if (session != null && (!persistent || systemProxy == null))
            throw new IllegalArgumentException("Per-app routing requires durable state and proxy recovery");
        perApp = session == null ? null : new MacPerAppRouting(session,gateway,families::bindings);
        if (systemProxy != null && !persistent) throw new IllegalArgumentException("System proxy requires durable recovery state");
        this.systemProxy = systemProxy;
        capabilities = perApp != null ? BackendCapabilities.macosPerApp() : systemProxy != null ? BackendCapabilities.macosSystemProxy()
                : persistent ? BackendCapabilities.macosLocal() : BackendCapabilities.macosLocalPreview();
        if (runtime.target() == XrayRuntime.Target.WINDOWS_X64) throw new IllegalArgumentException("Darwin runtime required");
        this.runtime = runtime;
        this.process = new XrayProcessManager(runtime, data);
    }
    @Override public BackendCapabilities capabilities() { return capabilities; }
    @Override public boolean coreReady() {
        try { runtime.requireExecutable(false); return true; } catch (java.io.IOException error) { return false; }
    }
    @Override public boolean perAppReady() { return perApp != null && coreReady(); }
    @Override public boolean requiresPerAppGateway() { return perApp != null; }
    @Override public boolean supportsPerAppKillSwitch() { return false; }
    @Override public String perAppReadinessMessage() { return "Install the matching signed PerRoute app, routing engine and background helper before using per-app mode."; }
    @Override public void prepareConnection() throws Exception {
        if (perApp != null) perApp.restore();
    }
    @Override public boolean elevated() { return false; }
    @Override public void validate(String config, boolean tun) throws Exception { process.validate(config, tun); }
    @Override public void start(String config, boolean tun) throws Exception {
        process.start(config,tun);
        try { if (perApp != null) perApp.coreStarted(process.ownedProcessId()); }
        catch (Exception failure) { process.stop(); throw failure; }
    }
    @Override public void stop() throws Exception {
        if (perApp != null) perApp.coreStopping();
        process.stop();
        if (process.isRunning()) throw new IOException("The owned Xray process remains tracked; retry Disconnect before reconnecting");
    }
    @Override public boolean isRunning() { return process.isRunning(); }
    @Override public void addLogListener(Consumer<String> listener) { logs.add(listener); process.addLogListener(listener); }
    @Override public void addRunningListener(Consumer<Boolean> listener) { process.addStateListener(listener); }
    @Override public TunnelStatus checkTunnel(int port) {
        var result = new ProxyHealthCheck().check("127.0.0.1", port);
        return new TunnelStatus(result.success(), result.detail());
    }
    @Override public Map<String, Long> readInboundCounters() throws Exception { return new XrayStatsReader(runtime).readInboundCounters(); }
    @Override public RouteObservation lookupPublicAddress(Integer port) { return diagnostics.lookupPublicAddress(port); }
    @Override public List<String> systemDnsServers() { return diagnostics.systemDnsServers(); }
    @Override public String publicResolver() { return diagnostics.publicResolver(); }
    @Override public void stopGateway() {
        if (perApp == null) return;
        try { perApp.stopGateway(); } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    @Override public boolean gatewayRunning() { return gateway.running(); }
    @Override public Map<String, AppGateway.Counters> gatewayCounters() { return gateway.counters(); }
    @Override public void addGatewayListener(Consumer<GatewayConnection> listener) { connections.add(listener); }
    @Override public void startGateway(List<AppGateway.Route> routes) throws Exception {
        if (perApp == null) throw unavailable("Per-app gateway");
        for (var route : routes) restoreApplication(route.applicationPath());
        perApp.startGateway(routes);
        long safari = perApp.safariBindingCount();
        logs.forEach(listener -> listener.accept("[Safari] " + app.perroute.service.BuildIdentity.displayVersion() +
                "; selected_routes=" + routes.size() + "; safari_bindings=" + safari +
                "; IPv4 TCP only; diagnostics subsystem=app.perroute.safari"));
    }
    @Override public void setGatewayLimits(String path, long down, long up) {
        if (perApp == null) throw unavailable("Per-app limits");
        gateway.setLimits(path,down,up);
    }
    @Override public void applyRuntimeChange(XrayConfigGenerator.RuntimeChange change, String newTag, String oldTag, String config) throws Exception {
        if (perApp == null) throw unavailable("Live route replacement");
        process.applyRuntimeChange(change.outbounds(),change.routing(),newTag,oldTag,config);
    }
    @Override public void stopOrphan() { throw unavailable("Orphan process recovery"); }
    @Override public void applySystemProxy() throws Exception {
        if (systemProxy == null) throw unavailable("System proxy");
        if (perApp != null) perApp.restore();
        if (!process.isRunning()) throw new java.io.IOException("The owned proxy engine must be running before applying system settings");
        for (int port : new int[]{app.perroute.service.AppProxyLauncher.HTTP_PORT, app.perroute.service.AppProxyLauncher.SOCKS_PORT}) {
            try (var socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 1000);
            }
        }
        systemProxy.apply();
        if (!process.isRunning()) {
            systemProxy.restore();
            throw new java.io.IOException("The proxy engine stopped while applying system settings");
        }
    }
    @Override public void restoreSystemProxy() throws Exception {
        if (systemProxy == null) throw unavailable("System proxy recovery");
        systemProxy.restore();
    }
    @Override public void verifySystemProxySession() throws Exception {
        if (systemProxy != null) systemProxy.verifySession();
    }
    @Override public void applyPerApp(List<String> apps) throws Exception { applyPerApp(apps,true,true); }
    @Override public void applyPerApp(List<String> apps, boolean bypassLan, boolean allowSetup) throws Exception {
        if (perApp == null) throw unavailable("Per-app routing");
        for (String app : apps) restoreApplication(app);
        systemProxy.restore();
        perApp.apply(apps,process.ownedProcessId(),allowSetup);
    }
    @Override public void restorePerApp() throws IOException {
        if (perApp == null) throw unavailable("Per-app recovery");
        perApp.restore();
    }
    @Override public void stopPerAppSidecar() throws IOException { restorePerApp(); }
    @Override public void verifyPerAppSession() throws IOException {
        if (perApp == null) throw unavailable("Per-app session");
        perApp.verify();
    }
    @Override public String selectApplication(String path) throws java.io.IOException {
        if (ApplicationIdentity.isMacFamily(path)) return families.validateSelection(path);
        String raw = ApplicationIdentity.path(path);
        if (raw == null || !raw.startsWith("/")) throw new java.io.IOException("Select a macOS executable from the application list.");
        Path executable = Path.of(raw).toRealPath();
        if (!java.nio.file.Files.isRegularFile(executable) || !java.nio.file.Files.isExecutable(executable))
            throw new java.io.IOException("Select an executable file, rather than its application folder.");
        return ApplicationIdentity.macExecutable(executable.toString());
    }
    @Override public String restoreApplication(String identity) throws java.io.IOException {
        if (!ApplicationIdentity.isMac(identity)) throw new java.io.IOException("Reselect this application on macOS; its old path cannot be migrated safely.");
        String resolved = selectApplication(identity);
        if (!resolved.equals(identity)) throw new java.io.IOException("The application's executable path changed. Select it again.");
        return resolved;
    }
    @Override public List<ApplicationDescriptor> installedApplications() {
        return descriptors(catalog::installed);
    }
    @Override public List<ApplicationDescriptor> runningApplications() {
        return descriptors(catalog::running);
    }
    private static List<ApplicationDescriptor> descriptors(CatalogRead source) {
        try {
            var snapshot = source.read();
            if (!snapshot.warnings().isEmpty()) throw new java.io.IOException(String.join("; ", snapshot.warnings()));
            return MacApplicationFamilies.descriptors(snapshot);
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    @FunctionalInterface private interface CatalogRead { MacApplicationCatalog.Snapshot read() throws java.io.IOException; }
    @Override public void installCore(Consumer<String> progress) { throw unavailable("Core installation; supply a matching Darwin Xray binary"); }
    @Override public void installPerApp(Consumer<String> progress) { throw unavailable("Driver installation"); }
    @Override public UpdateDescriptor checkForUpdate(String feed) { throw unavailable("Automatic updates"); }
    @Override public Path downloadUpdate(UpdateDescriptor update, Consumer<Integer> progress) { throw unavailable("Automatic updates"); }
    @Override public void launchUpdate(Path installer) { throw unavailable("Automatic updates"); }
    @Override public void recover() { throw unavailable("Network recovery"); }
    @Override public void runGuard(Long parentPid) { throw unavailable("Network guard"); }
    private static UnsupportedOperationException unavailable(String feature) {
        return new UnsupportedOperationException(feature + " is unavailable on this macOS backend");
    }
}
