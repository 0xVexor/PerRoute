package app.perroute.platform.macos.routing;

import app.perroute.persistence.PrivateStateFiles;
import app.perroute.platform.macos.proxy.HelperSystemProxyLifecycle.Client;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import app.perroute.platform.macos.routing.MacPerAppRoutingPlan.ExecutableRoute;

public final class MacTunSessionClientTests {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Check " + checks); }
    @FunctionalInterface interface Action { void run() throws Exception; }
    private static Exception failure(Class<? extends Exception> type, Action action) throws Exception {
        try { action.run(); } catch (Exception error) { check(type.isInstance(error)); return error; }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static final List<ExecutableRoute> ROUTES = List.of(new ExecutableRoute("/Applications/Test.app/Contents/MacOS/Test",17890));
    private static final List<Integer> UPSTREAMS = List.of(17990);
    private static final class Peer implements Client {
        final Path marker;
        final List<String> calls = new ArrayList<>();
        String generation = UUID.randomUUID().toString();
        Map<String,Object> replyOverride;
        boolean oldEndpoints, unavailable, missing, alwaysStarting, badOwnership, cleanupFails, failAfterStart, interruptStart, started;
        Peer(Path marker) { this.marker = marker; }
        Map<String,Object> state() { if (replyOverride != null) return replyOverride; return Map.of("pending",true,"owned",badOwnership ? 1 : true,
                "phase",alwaysStarting ? "starting" : "ready","session",generation); }
        @Override public Map<String,Object> request(String operation, Map<String,Object> values) throws IOException {
            calls.add(operation);
            return switch (operation) {
                case "prepare", "prepare-existing" -> {
                    if (missing) throw new IOException("Helper setup is required");
                    yield Map.of();
                }
                case "tun-capabilities" -> Map.of("protocol",1,"endpointProtocol",oldEndpoints ? 1 : 2,"euid",0,"payloadAvailable",!unavailable);
                case "stop-tun" -> {
                    if (missing) yield Map.of("registered",false);
                    if (cleanupFails) yield Map.of("pending",true,"owned",false,"phase","stopped");
                    started = false; yield Map.of("pending",false,"owned",false,"phase","stopped");
                }
                case "start-tun" -> {
                    check(Files.isRegularFile(marker)); check(values.keySet().equals(Set.of("bindings","corePid","upstreamPorts")));
                    check(values.get("corePid").equals(123L)); check(values.get("upstreamPorts").equals(UPSTREAMS)); started = true;
                    if (failAfterStart) cleanupFails = true;
                    if (interruptStart) Thread.currentThread().interrupt();
                    yield state();
                }
                case "tun-status" -> state();
                default -> throw new IOException("Unexpected operation " + operation);
            };
        }
    }
    private static MacTunSessionClient client(Peer peer) {
        AtomicLong clock = new AtomicLong();
        return new MacTunSessionClient(peer,peer.marker,clock::get,ms -> clock.addAndGet(ms*1_000_000));
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("perroute-tun-client-").toRealPath();
        try {
            Path marker = root.resolve("pending");
            var source = new ArrayList<>(ROUTES); var request = new MacTunStartRequest(source,123,UPSTREAMS); source.clear();
            var bindings = (List<?>)request.values().get("bindings"); check(bindings.size() == 1);
            failure(UnsupportedOperationException.class, () -> request.values().clear());
            failure(UnsupportedOperationException.class,bindings::clear);
            failure(UnsupportedOperationException.class, () -> ((Map<?,?>)bindings.getFirst()).clear());
            check(((Map<?,?>)bindings.getFirst()).get("executable").equals(ROUTES.getFirst().executablePath()));
            failure(IllegalArgumentException.class, () -> new MacTunStartRequest(ROUTES,1,UPSTREAMS));
            failure(IllegalArgumentException.class, () -> new MacTunStartRequest(ROUTES,2147483648L,UPSTREAMS));
            failure(UnsupportedOperationException.class, () -> ((List<?>)request.values().get("upstreamPorts")).clear());
            failure(IllegalArgumentException.class, () -> new MacTunStartRequest(ROUTES,123,List.of()));
            failure(IllegalArgumentException.class, () -> new MacTunStartRequest(ROUTES,123,List.of(17890)));
            failure(IllegalArgumentException.class, () -> new MacTunStartRequest(ROUTES,123,List.of(0)));
            failure(IllegalArgumentException.class, () -> new MacTunStartRequest(ROUTES,123,List.of(65536)));
            var twoRoutes = List.of(ROUTES.getFirst(),new ExecutableRoute("/Applications/Other.app/Contents/MacOS/Other",17892));
            failure(IllegalArgumentException.class, () -> new MacTunStartRequest(twoRoutes,123,List.of(17990,17990)));
            var sharedPort = List.of(ROUTES.getFirst(),new ExecutableRoute("/Applications/Other.app/Contents/MacOS/Other",17890));
            check(((List<?>)new MacTunStartRequest(sharedPort,123,UPSTREAMS).values().get("bindings")).size() == 2);
            var mutablePorts = new ArrayList<>(UPSTREAMS);
            var frozen = new MacTunStartRequest(ROUTES,123,mutablePorts); mutablePorts.clear();
            check(frozen.values().get("upstreamPorts").equals(UPSTREAMS));
            Peer peer = new Peer(marker); var service = client(peer);
            service.start(ROUTES,123,UPSTREAMS); check(peer.started && service.pendingRecovery()); service.verifySession();
            peer.generation = UUID.randomUUID().toString(); failure(IOException.class,service::verifySession); check(service.pendingRecovery());
            service.stop(); check(!peer.started && !service.pendingRecovery()); service.stop(); failure(IOException.class,service::verifySession);
            peer = new Peer(marker); peer.unavailable = true; var unavailable = client(peer);
            failure(IOException.class, () -> unavailable.start(ROUTES,123,UPSTREAMS)); check(!peer.calls.contains("start-tun") && !Files.exists(marker));
            peer = new Peer(marker); peer.oldEndpoints = true; var oldHelper = client(peer);
            failure(IOException.class, () -> oldHelper.start(ROUTES,123,UPSTREAMS));
            check(!peer.calls.contains("start-tun") && !Files.exists(marker));
            peer = new Peer(marker); peer.badOwnership = true; var bad = client(peer);
            failure(IOException.class, () -> bad.start(ROUTES,123,UPSTREAMS)); check(!bad.pendingRecovery());
            for (String field : List.of("pending","owned","phase","session")) {
                var malformed = new Peer(marker);
                var reply = new HashMap<String,Object>(malformed.state());
                reply.remove(field); malformed.replyOverride = reply;
                var malformedClient = client(malformed);
                var rejected = failure(IOException.class, () -> malformedClient.start(ROUTES,123,UPSTREAMS));
                check(rejected.getMessage().contains(field.equals("session") ? "session identifier" : field));
                check(!malformedClient.pendingRecovery() && !malformed.started);
            }
            peer = new Peer(marker); peer.badOwnership = true; peer.failAfterStart = true; var pending = client(peer);
            Exception error = failure(IOException.class, () -> pending.start(ROUTES,123,UPSTREAMS)); check(error.getSuppressed().length == 1 && pending.pendingRecovery());
            peer.cleanupFails = false; pending.stop(); check(!pending.pendingRecovery());
            peer = new Peer(marker); peer.alwaysStarting = true; AtomicLong time = new AtomicLong();
            var timeout = new MacTunSessionClient(peer,marker,() -> time.getAndAdd(16_000_000_000L),ms -> {});
            failure(IOException.class, () -> timeout.start(ROUTES,123,UPSTREAMS)); check(!timeout.pendingRecovery());
            peer = new Peer(marker); peer.interruptStart = true; var cancelled = client(peer);
            try { failure(InterruptedException.class, () -> cancelled.start(ROUTES,123,UPSTREAMS)); check(!cancelled.pendingRecovery()); }
            finally { Thread.interrupted(); }
            peer = new Peer(marker); var earlyCancelled = client(peer); Thread.currentThread().interrupt();
            try { failure(InterruptedException.class, () -> earlyCancelled.start(ROUTES,123,UPSTREAMS)); check(peer.calls.isEmpty()); }
            finally { Thread.interrupted(); }
            peer = new Peer(marker); peer.missing = true; var missing = client(peer); missing.stop(); check(!missing.pendingRecovery());
            PrivateStateFiles.write(marker,new byte[]{1}); failure(IOException.class,missing::stop); check(missing.pendingRecovery());
            peer.missing = false; missing.stop(); check(!missing.pendingRecovery());
            PrivateStateFiles.write(marker,new byte[]{0}); failure(IOException.class,missing::pendingRecovery); check(Files.readAllBytes(marker)[0] == 0); Files.delete(marker);
            Path target = root.resolve("target"); PrivateStateFiles.write(target,new byte[]{1}); Files.createSymbolicLink(marker,target);
            failure(IOException.class,missing::pendingRecovery); check(Files.isSymbolicLink(marker)); Files.delete(marker); Files.delete(target);
            peer = new Peer(marker); var valid = client(peer); failure(IllegalArgumentException.class, () -> valid.start(List.of(),123,UPSTREAMS)); check(peer.calls.isEmpty());
            peer = new Peer(marker); var retry = client(peer);
            retry.start(ROUTES,123,UPSTREAMS,false);
            check(peer.calls.contains("prepare-existing") && !peer.calls.contains("prepare"));
            peer.replyOverride = Map.of("phase","network-changed","pending",false,"owned",false,"session",peer.generation);
            failure(app.perroute.backend.api.RoutingNetworkChangedException.class,retry::verifySession);
            check(retry.pendingRecovery());
            for (String field : List.of("pending","owned","session")) {
                var malformed = new HashMap<>(peer.replyOverride);
                malformed.put(field,field.equals("session") ? UUID.randomUUID().toString() : true);
                var previous = peer.replyOverride; peer.replyOverride = malformed;
                check(!(failure(IOException.class,retry::verifySession) instanceof app.perroute.backend.api.RoutingNetworkChangedException));
                peer.replyOverride = previous;
            }
            retry.stop(); check(!retry.pendingRecovery());
            peer = new Peer(marker); peer.missing = true; var noSetup = client(peer);
            failure(IOException.class,() -> noSetup.start(ROUTES,123,UPSTREAMS,false));
            check(peer.calls.equals(List.of("prepare-existing")) && !Files.exists(marker));
            System.out.println("macOS TUN Java lifecycle: " + checks + " checks passed; simulated helper, isolated recovery files");
        } finally {
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
}
