package app.perroute.platform.windows;

import app.perroute.gateway.AppGateway;
import app.perroute.gateway.GatewayConnection;
import app.perroute.service.XrayConfigGenerator;
import java.util.concurrent.CopyOnWriteArrayList;

import app.perroute.backend.api.ApplicationDescriptor;
import app.perroute.backend.api.ConnectionBackend;
import app.perroute.backend.api.RouteObservation;
import app.perroute.backend.api.StartupRecovery;
import app.perroute.backend.api.TunnelStatus;
import app.perroute.backend.api.UpdateDescriptor;
import app.perroute.backend.core.NetworkDiagnostics;
import app.perroute.service.AdminCheck;
import app.perroute.service.CoreInstaller;
import app.perroute.service.InstalledApplicationDiscovery;
import app.perroute.service.ProcessDiscovery;
import app.perroute.service.ProxiFyreManager;
import app.perroute.service.ProxyHealthCheck;
import app.perroute.service.SecureUpdateService;
import app.perroute.service.SingleInstanceLock;
import app.perroute.service.WindowsSystemProxyManager;
import app.perroute.service.XrayProcessManager;
import app.perroute.service.XrayStatsReader;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Delegates to the existing Windows managers without replacing their connection or recovery behavior. */
public final class WindowsConnectionBackend implements ConnectionBackend, StartupRecovery {
    @Override public app.perroute.backend.api.BackendCapabilities capabilities() {
        return app.perroute.backend.api.BackendCapabilities.windows();
    }
    @Override public boolean supportsRecipeImport() { return true; }
    @Override public boolean supportsWorkspaces() { return true; }
    @Override public boolean supportsLanBridge() { return true; }
    @Override public boolean supportsExplorerMenu() { return true; }
    private final Path dataDirectory;
    private CoreInstaller coreInstaller;
    private XrayProcessManager xray;
    private ProxiFyreManager perApp;
    private WindowsSystemProxyManager proxy;
    private SecureUpdateService updates;
    private final List<Consumer<String>> logListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<GatewayConnection>> gatewayListeners = new CopyOnWriteArrayList<>();
    private AppGateway gateway;
    private final NetworkDiagnostics diagnostics = new NetworkDiagnostics();

    public WindowsConnectionBackend(Path dataDirectory) {
        this.dataDirectory = java.util.Objects.requireNonNull(dataDirectory, "dataDirectory");
    }

    // Construction stays lazy so selecting a provider does not inspect or mutate the host.
    private synchronized CoreInstaller installer() {
        if (coreInstaller == null) coreInstaller = new CoreInstaller();
        return coreInstaller;
    }
    private synchronized XrayProcessManager xray() {
        if (xray == null) xray = new XrayProcessManager();
        return xray;
    }
    private synchronized ProxiFyreManager perApp() {
        if (perApp == null) perApp = new ProxiFyreManager();
        return perApp;
    }
    private synchronized WindowsSystemProxyManager proxy() {
        if (proxy == null) proxy = new WindowsSystemProxyManager(dataDirectory);
        return proxy;
    }
    private synchronized SecureUpdateService updates() {
        if (updates == null) updates = new SecureUpdateService();
        return updates;
    }

    private synchronized AppGateway gateway() {
        if (gateway == null) gateway = new AppGateway(
                connection -> gatewayListeners.forEach(listener -> listener.accept(connection)),
                line -> logListeners.forEach(listener -> listener.accept(line)));
        return gateway;
    }

    private WindowsDesktopOperations desktop;
    private synchronized WindowsDesktopOperations desktop() {
        if (desktop == null) desktop = new WindowsDesktopOperations(proxy(), perApp(), xray(), dataDirectory);
        return desktop;
    }
    @Override public void applyPerApp(List<String> applications, boolean bypassLan) throws Exception { perApp().applyAndStart(applications,bypassLan); }
    @Override public void applyRuntimePlan(XrayConfigGenerator.RuntimePlan plan) throws Exception { xray().applyRuntimePlan(plan); }
    @Override public Map<String,String> networkState() { return desktop().networkState(); }
    @Override public void resetSystemProxyLeftover(Map<String,String> baseline) { desktop().resetSystemProxyLeftover(baseline); }
    @Override public void removeRecoveryEntry() { desktop().removeRecoveryEntry(); }
    @Override public boolean perRouteProxyActive() { return desktop().perRouteProxyActive(); }
    @Override public app.perroute.backend.api.IsolatedTunnel startIsolatedCore(String config, int port) { return desktop().startIsolatedCore(config,port); }
    @Override public app.perroute.backend.api.IsolatedTunnel startIsolatedCore(String config, int port, Consumer<String> listener) { return desktop().startIsolatedCore(config,port,listener); }
    @Override public app.perroute.service.ResourceConditions.Snapshot resourceConditions() { return app.perroute.service.ResourceConditions.read(); }
    @Override public Path resolveShortcut(Path path) { return desktop().resolveShortcut(path); }
    @Override public boolean explorerMenuRegistered(Path path) { return desktop().explorerMenuRegistered(path); }
    @Override public void setExplorerMenu(Path path, boolean enabled) { desktop().setExplorerMenu(path,enabled); }
    @Override public String networkCategory(String address) { return desktop().networkCategory(address); }
    @Override public boolean bridgeFirewallReady(Path core, int first, int last) { return desktop().bridgeFirewallReady(core,first,last); }
    @Override public void allowBridgeFirewall(Path core, int first, int last) { desktop().allowBridgeFirewall(core,first,last); }
    @Override public Process startProgram(List<String> command) { return desktop().startProgram(command); }
    @Override public boolean profileInUse(Path profile) { return desktop().profileInUse(profile); }
    @Override public void shellOpen(Path file, List<String> arguments, boolean elevated) { desktop().shellOpen(file,arguments,elevated); }
    @Override public List<app.perroute.localnet.LocalNetwork> localNetworks(boolean names) { return desktop().localNetworks(names); }
    @Override public boolean systemResolves(String host) { return desktop().systemResolves(host); }
    @Override public Long directServerTime() { return desktop().directServerTime(); }

    @Override public void applyRuntimeChange(XrayConfigGenerator.RuntimeChange change, String newTag, String oldTag, String config) throws Exception {
        xray().applyRuntimeChange(change.outbounds(), change.routing(), newTag, oldTag, config);
    }
    @Override public void startGateway(List<AppGateway.Route> routes) throws Exception { gateway().start(routes); }
    @Override public void stopGateway() { gateway().stop(); }
    @Override public void setGatewayLimits(String path, long down, long up) { gateway().setLimits(path, down, up); }
    @Override public Map<String, AppGateway.Counters> gatewayCounters() { return gateway().counters(); }
    @Override public boolean gatewayRunning() { return gateway().running(); }
    @Override public void addGatewayListener(Consumer<GatewayConnection> listener) { gatewayListeners.add(listener); }

    @Override public boolean coreReady() {
        try { return installer().inspect().ready(); } catch (Exception | LinkageError error) { return false; }
    }
    @Override public boolean perAppReady() {
        try { return perApp().inspect().ready(); } catch (Exception | LinkageError error) { return false; }
    }
    @Override public PerAppDriverState perAppDriverState() {
        return switch (perApp().driverState()) {
            case RUNNING -> PerAppDriverState.RUNNING;
            case STOPPED -> PerAppDriverState.STOPPED;
            case MISSING -> PerAppDriverState.MISSING;
            case UNKNOWN -> PerAppDriverState.UNKNOWN;
        };
    }
    @Override public String perAppDriverName() { return ProxiFyreManager.DRIVER_NAME; }
    @Override public String perAppReadinessMessage() {
        return "Smart per-app needs the per-app engine and the Windows Packet Filter driver, and one of them is missing (see the Doctor). Reinstall PerRoute with the Full Installer and approve its single UAC prompt, or choose Local proxy/System proxy.";
    }
    @Override public boolean elevated() {
        try { return AdminCheck.isElevated(); } catch (Exception | LinkageError error) { return false; }
    }
    @Override public void validate(String config, boolean tun) throws Exception { xray().validate(config, tun); }
    @Override public void start(String config, boolean tun) throws Exception { xray().start(config, tun); }
    @Override public void stop() { xray().stop(); }
    @Override public boolean isRunning() { return xray().isRunning(); }
    @Override public void stopOrphan() { xray().stopOrphan(); }
    @Override public void addLogListener(Consumer<String> listener) { xray().addLogListener(listener); logListeners.add(listener); }
    @Override public void addRunningListener(Consumer<Boolean> listener) { xray().addStateListener(listener); }
    @Override public void applyPerApp(List<String> applications) throws Exception { perApp().applyAndStart(applications); }
    @Override public void restorePerApp() throws Exception { perApp().stopAndRestore(); }
    @Override public void stopPerAppSidecar() throws Exception { perApp().stopManagedSidecar(); }
    @Override public void applySystemProxy() throws Exception { proxy().apply(); }
    @Override public void restoreSystemProxy() throws Exception { proxy().restore(); }
    @Override public TunnelStatus checkTunnel(int socksPort) {
        var result = new ProxyHealthCheck().check("127.0.0.1", socksPort);
        return new TunnelStatus(result.success(), result.detail());
    }
    @Override public Map<String, Long> readInboundCounters() throws Exception { return new XrayStatsReader().readInboundCounters(); }
    @Override public RouteObservation lookupPublicAddress(Integer socksPort) { return diagnostics.lookupPublicAddress(socksPort); }
    @Override public List<String> systemDnsServers() { return diagnostics.systemDnsServers(); }
    @Override public String publicResolver() { return diagnostics.publicResolver(); }
    @Override public List<ApplicationDescriptor> installedApplications() {
        return new InstalledApplicationDiscovery().list().stream()
                .map(app -> new ApplicationDescriptor(app.name(), app.path())).toList();
    }
    @Override public List<ApplicationDescriptor> runningApplications() {
        return new ProcessDiscovery().list().stream()
                .map(app -> new ApplicationDescriptor(app.name(), app.path())).toList();
    }
    @Override public void installCore(Consumer<String> progress) throws Exception { installer().install(progress); }
    @Override public void installPerApp(Consumer<String> progress) throws Exception { perApp().downloadAndInstall(progress); }
    @Override public UpdateDescriptor checkForUpdate(String feed) throws Exception {
        var update = updates().check(feed);
        return new UpdateDescriptor(update.version(), update.url(), update.sha256(), update.size(), update.newer());
    }
    @Override public Path downloadUpdate(UpdateDescriptor update, Consumer<Integer> progress) throws Exception {
        return updates().download(new SecureUpdateService.UpdateInfo(
                update.version(), update.url(), update.sha256(), update.size(), update.newer()), progress);
    }
    @Override public void launchUpdate(Path installer) throws Exception { new ProcessBuilder(installer.toString()).start(); }
    @Override public void recover() throws Exception { restoreSystemProxy(); }

    @Override public void runGuard(Long parentPid) throws Exception {
        var parent = parentPid == null ? null : ProcessHandle.of(parentPid).orElse(null);
        while (parent != null && parent.isAlive()) {
            if (!proxy().hasPendingRestore()) return;
            Thread.sleep(1_000);
        }
        Thread.sleep(1_500);
        if (!proxy().hasPendingRestore()) return;
        SingleInstanceLock acquired;
        try { acquired = SingleInstanceLock.acquire(dataDirectory); }
        catch (Exception error) { return; }
        var lock = acquired;
        if (lock == null) return;
        try (lock) {
            try { stopOrphan(); } catch (Exception ignored) { }
            try { restoreSystemProxy(); } catch (Exception ignored) { }
        }
    }
}
