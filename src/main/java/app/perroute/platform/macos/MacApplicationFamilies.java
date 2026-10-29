package app.perroute.platform.macos;

import app.perroute.backend.api.ApplicationDescriptor;
import app.perroute.gateway.AppGateway;
import app.perroute.model.ApplicationIdentity;
import app.perroute.platform.macos.routing.MacPerAppRoutingPlan.ExecutableRoute;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Expands explicit family intent into exact paths; labels and bundle identifiers never confer authority. */
public final class MacApplicationFamilies {
    private final MacApplicationCatalog catalog;
    public MacApplicationFamilies(MacApplicationCatalog catalog) { this.catalog = Objects.requireNonNull(catalog); }

    public static List<ApplicationDescriptor> descriptors(MacApplicationCatalog.Snapshot snapshot) throws IOException {
        requireComplete(snapshot);
        var result = new ArrayList<ApplicationDescriptor>();
        for (var app : snapshot.applications()) {
            result.add(new ApplicationDescriptor(app.name(), ApplicationIdentity.macExecutable(app.executablePath().toString())));
            if (outermost(app, snapshot) && members(app, snapshot).size() > 1)
                result.add(new ApplicationDescriptor(app.name() + " (including helpers)",
                        ApplicationIdentity.macAppFamily(app.executablePath().toString())));
        }
        return List.copyOf(result);
    }

    public String validateSelection(String identity) throws IOException {
        if (!ApplicationIdentity.isMacFamily(identity)) throw new IOException("Expected an explicit application family");
        var snapshot = catalog.installed();
        requireComplete(snapshot);
        resolve(identity, snapshot);
        return identity;
    }

    public List<ExecutableRoute> bindings(List<AppGateway.Route> routes) throws IOException {
        // Read once so every family in this connection uses the same complete inventory.
        MacApplicationCatalog.Snapshot snapshot = catalog.installed();
        requireComplete(snapshot);
        var result = new ArrayList<ExecutableRoute>();
        var owners = new HashMap<String, String>();
        for (var route : routes) {
            String identity = route.applicationPath();
            if (!ApplicationIdentity.isMac(identity)) throw new IOException("Expected a macOS application identity");
            List<String> paths = ApplicationIdentity.isMacFamily(identity) ? resolve(identity, snapshot)
                    : List.of(executable(Path.of(ApplicationIdentity.path(identity))));
            for (String path : paths) {
                if (owners.putIfAbsent(path, identity) != null)
                    throw new IOException("Application selections overlap. Select either an app including helpers or its individual executables.");
                // Catalog metadata requests the scope; the native helper and engine
                // independently require the actual Apple Safari signing identity.
                boolean safari = snapshot.applications().stream().anyMatch(app ->
                        app.executablePath().toString().equals(path) && app.signatureValid() &&
                        "com.apple.Safari".equals(app.bundleIdentifier()) && "com.apple.Safari".equals(app.signingIdentifier()));
                result.add(new ExecutableRoute(path, route.listenPort(), safari
                        ? app.perroute.platform.macos.routing.MacPerAppRoutingPlan.Scope.SAFARI_APPLICATION
                        : app.perroute.platform.macos.routing.MacPerAppRoutingPlan.Scope.EXECUTABLE));
                if (result.size() > 256) throw new IOException("The selection includes more than 256 executable paths. Select fewer apps or individual helpers.");
            }
        }
        return List.copyOf(result);
    }

    private static List<String> resolve(String identity, MacApplicationCatalog.Snapshot snapshot) throws IOException {
        String main = ApplicationIdentity.path(identity);
        var app = snapshot.applications().stream().filter(candidate -> candidate.executablePath().toString().equals(main))
                .findFirst().orElseThrow(() -> new IOException("The selected application family is no longer installed. Select it again."));
        if (!outermost(app, snapshot)) throw new IOException("Select the outer application to include its helpers.");
        Path bundle = app.bundlePath();
        if (!bundle.equals(bundle.toRealPath())) throw new IOException("The application's bundle path changed. Select it again.");
        var paths = new TreeSet<String>();
        for (var member : members(app, snapshot)) {
            if (!member.bundlePath().equals(member.bundlePath().toRealPath()))
                throw new IOException("A helper bundle path changed. Refresh and select the application again.");
            String path = executable(member.executablePath());
            if (!Path.of(path).startsWith(bundle.resolve("Contents")))
                throw new IOException("A helper executable escaped the selected application.");
            paths.add(path);
        }
        return List.copyOf(paths);
    }
    private static String executable(Path path) throws IOException {
        if (!path.equals(path.toRealPath()) || !Files.isRegularFile(path) || !Files.isExecutable(path))
            throw new IOException("An application executable changed or is unavailable. Refresh and select it again.");
        return path.toString();
    }
    private static boolean outermost(MacApplicationCatalog.Application app, MacApplicationCatalog.Snapshot snapshot) {
        return snapshot.applications().stream().noneMatch(other -> !other.bundlePath().equals(app.bundlePath()) &&
                app.bundlePath().startsWith(other.bundlePath().resolve("Contents")));
    }
    private static List<MacApplicationCatalog.Application> members(MacApplicationCatalog.Application app, MacApplicationCatalog.Snapshot snapshot) {
        return snapshot.applications().stream().filter(candidate -> candidate.bundlePath().equals(app.bundlePath()) ||
                candidate.bundlePath().startsWith(app.bundlePath().resolve("Contents"))).toList();
    }
    private static void requireComplete(MacApplicationCatalog.Snapshot snapshot) throws IOException {
        if (!snapshot.warnings().isEmpty()) throw new IOException("Application discovery is incomplete. Refresh the application list before connecting.");
    }
}
