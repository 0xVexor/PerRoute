package app.perroute.platform.macos.proxy;

import app.perroute.service.AppProxyLauncher;
import app.perroute.util.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;

/** Read-only host acceptance observer. Stores hashes, never proxy dictionaries or credentials. */
public final class NativeProxyAcceptance {
    public static void main(String[] args) throws Exception {
        var inventory = new MacNetworkServiceInspector().inspect();
        var candidates = new EffectiveProxyProjection().project(inventory).stream()
                .filter(result -> result.outcome() == EffectiveProxyProjection.Outcome.MATCH).toList();
        if (candidates.size() != 1) throw new AssertionError("Expected one supported settled service");
        if (args[0].equals("connected")) {
            var state = new StableProxyReadback(new MacNetworkServiceInspector()::inspect).read(candidates.getFirst().serviceId());
            var expected = new LinkedHashMap<String, Object>();
            for (String protocol : List.of("HTTP", "HTTPS", "SOCKS")) {
                expected.put(protocol + "Enable", 1); expected.put(protocol + "Proxy", "127.0.0.1");
                expected.put(protocol + "Port", protocol.equals("SOCKS") ? AppProxyLauncher.SOCKS_PORT : AppProxyLauncher.HTTP_PORT);
            }
            var keys = expected.keySet();
            if (!state.configured().select(keys).equals(new ProxySettings(expected)) || !ManualProxySemantics.equivalent(state.configured(), state.effective()))
                throw new AssertionError("Connected loopback settings did not match native configuration and scope");
            System.out.println("Native acceptance: HTTP, HTTPS and SOCKS point to PerRoute; scoped read-back matches");
            return;
        }
        var fingerprints = new TreeMap<String, Object>();
        for (var service : inventory.services()) {
            if (service.configured().settings().isPresent())
                fingerprints.put(hash(service.id()), hash(Json.stringify(canonical(service.configured().settings().orElseThrow().values()))));
        }
        var scoped = candidates.getFirst();
        fingerprints.put("effective", hash(Json.stringify(canonical(scoped.scoped().orElseThrow().values()))));
        String encoded = Json.stringify(fingerprints);
        Path file = Path.of(args[1]);
        switch (args[0]) {
            case "baseline" -> {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.writeString(file, encoded);
                System.out.println("Native acceptance baseline: configured service and scoped fingerprints captured");
            }
            case "verify" -> {
                if (!Json.object(Files.readString(file)).equals(Json.object(encoded))) throw new AssertionError("Host proxy fingerprints differ from the original baseline");
                System.out.println("Native acceptance: all configured dictionaries and supported effective scope restored to baseline");
            }
            default -> throw new IllegalArgumentException("Choose baseline, connected or verify");
        }
    }
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, item) -> sorted.put((String) key, canonical(item))); return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(NativeProxyAcceptance::canonical).toList();
        return value;
    }
    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
