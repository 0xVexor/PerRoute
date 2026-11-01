package app.perroute.platform.macos.proxy;

import com.sun.jna.*;
import java.io.IOException;
import java.util.*;
import static app.perroute.platform.macos.proxy.NetworkServiceInventory.*;
import static app.perroute.platform.macos.proxy.NativePropertyLists.*;

/** Read-only SystemConfiguration bindings. No authorization, locking, setter, commit or apply exports. */
public final class MacNetworkServiceInspector {
    interface SystemConfiguration extends Library {
        Pointer SCPreferencesCreate(Pointer allocator, Pointer name, Pointer preferencesId);
        Pointer SCPreferencesGetSignature(Pointer preferences);
        Pointer SCNetworkServiceCopyAll(Pointer preferences);
        Pointer SCNetworkServiceGetServiceID(Pointer service);
        Pointer SCNetworkServiceGetName(Pointer service);
        byte SCNetworkServiceGetEnabled(Pointer service);
        Pointer SCNetworkServiceGetInterface(Pointer service);
        Pointer SCNetworkInterfaceGetInterfaceType(Pointer networkInterface);
        Pointer SCNetworkInterfaceGetBSDName(Pointer networkInterface);
        Pointer SCNetworkInterfaceGetInterface(Pointer networkInterface);
        Pointer SCNetworkServiceCopyProtocol(Pointer service, Pointer protocol);
        byte SCNetworkProtocolGetEnabled(Pointer protocol);
        Pointer SCNetworkProtocolGetConfiguration(Pointer protocol);
        Pointer SCNetworkSetCopyCurrent(Pointer preferences);
        Pointer SCNetworkSetCopyServices(Pointer set);
        Pointer SCDynamicStoreCreate(Pointer allocator, Pointer name, Pointer callback, Pointer context);
        Pointer SCDynamicStoreCopyValue(Pointer store, Pointer key);
        Pointer SCDynamicStoreCopyProxies(Pointer store);
        int SCError();
    }
    // Load native libraries only after the explicit platform check in inspect().
    private static final class NativeApi {
        static final SystemConfiguration SC = Native.load("/System/Library/Frameworks/SystemConfiguration.framework/SystemConfiguration", SystemConfiguration.class);
    }
    public NetworkServiceInventory inspect() throws IOException {
        String os = System.getProperty("os.name", "");
        if (!os.equalsIgnoreCase("Mac OS X") && !os.equalsIgnoreCase("Darwin")) throw new UnsupportedOperationException("Native service inspection requires macOS");
        var sc = NativeApi.SC;
        Pointer name = text("PerRoute read-only network inspection");
        Pointer prefs = null, store = null, services = null;
        try {
            prefs = required(sc.SCPreferencesCreate(null, name, null), "preferences session", sc);
            byte[] before = signature(prefs, sc);
            store = required(sc.SCDynamicStoreCreate(null, name, null, null), "dynamic-store session", sc);
            Set<String> current = currentServices(prefs, sc);
            services = required(sc.SCNetworkServiceCopyAll(prefs), "service inventory", sc);
            var result = new ArrayList<Service>();
            int count = count(CF.CFArrayGetCount(services));
            for (int i = 0; i < count; i++) {
                Pointer service = CF.CFArrayGetValueAtIndex(services, new NativeLong(i));
                var codec = new NativePropertyLists();
                String id = codec.string(sc.SCNetworkServiceGetServiceID(service));
                if (id.isBlank() || id.contains("/")) throw new IOException("Invalid native network-service ID");
                Pointer proxyType = text("Proxies");
                Pointer protocol = null;
                Observation configured;
                boolean protocolEnabled = false;
                try {
                    protocol = sc.SCNetworkServiceCopyProtocol(service, proxyType);
                    if (protocol == null) configured = missing(sc.SCError());
                    else {
                        protocolEnabled = sc.SCNetworkProtocolGetEnabled(protocol) != 0;
                        Pointer dictionary = sc.SCNetworkProtocolGetConfiguration(protocol);
                        configured = dictionary == null ? missing(sc.SCError()) : observation(dictionary);
                    }
                } finally { release(protocol); release(proxyType); }
                var setup = dynamic(store, "Setup:/Network/Service/" + id + "/Proxies", sc);
                var state = dynamic(store, "State:/Network/Service/" + id + "/Proxies", sc);
                var activeInterfaces = new ArrayList<String>();
                activeInterface(store, "State:/Network/Service/" + id + "/IPv4", sc).ifPresent(activeInterfaces::add);
                activeInterface(store, "State:/Network/Service/" + id + "/IPv6", sc).ifPresent(activeInterfaces::add);
                result.add(new Service(id, codec.string(sc.SCNetworkServiceGetName(service)), interfaces(sc.SCNetworkServiceGetInterface(service), sc),
                        sc.SCNetworkServiceGetEnabled(service) != 0, current.contains(id), !activeInterfaces.isEmpty(), protocolEnabled,
                        configured, setup, state, activeInterfaces.stream().distinct().toList()));
            }
            Pointer global = sc.SCDynamicStoreCopyProxies(store);
            Observation aggregate;
            try { aggregate = global == null ? missing(sc.SCError()) : observation(global); }
            finally { release(global); }
            // A fresh preferences session detects persistent changes during observation, without locking or writing.
            Pointer afterPrefs = required(sc.SCPreferencesCreate(null, name, null), "verification preferences session", sc);
            byte[] after;
            try { after = signature(afterPrefs, sc); } finally { release(afterPrefs); }
            Consistency consistency = before == null || after == null ? Consistency.UNKNOWN
                    : Arrays.equals(before, after) ? Consistency.UNCHANGED : Consistency.CHANGED;
            return new NetworkServiceInventory(result, aggregate, consistency);
        } finally { release(services); release(store); release(prefs); release(name); }
    }
    private static Set<String> currentServices(Pointer prefs, SystemConfiguration sc) throws IOException {
        Pointer set = sc.SCNetworkSetCopyCurrent(prefs);
        if (set == null) {
            int status = sc.SCError();
            if (status == 0 || status == 1004) return Set.of();
            throw new IOException("Could not read current network set (status " + status + ")");
        }
        Pointer services = null;
        try {
            services = required(sc.SCNetworkSetCopyServices(set), "current-set services", sc);
            var result = new HashSet<String>();
            int count = count(CF.CFArrayGetCount(services));
            for (int i = 0; i < count; i++) result.add(new NativePropertyLists().string(sc.SCNetworkServiceGetServiceID(CF.CFArrayGetValueAtIndex(services, new NativeLong(i)))));
            return result;
        } finally { release(services); release(set); }
    }
    private static List<InterfaceLayer> interfaces(Pointer value, SystemConfiguration sc) throws IOException {
        var result = new ArrayList<InterfaceLayer>();
        var seen = new HashSet<Long>();
        while (value != null) {
            if (result.size() >= 16 || !seen.add(Pointer.nativeValue(value))) throw new IOException("Invalid native interface chain");
            var codec = new NativePropertyLists();
            result.add(new InterfaceLayer(codec.string(sc.SCNetworkInterfaceGetInterfaceType(value)), codec.string(sc.SCNetworkInterfaceGetBSDName(value))));
            value = sc.SCNetworkInterfaceGetInterface(value);
        }
        return result;
    }
    private static Observation dynamic(Pointer store, String key, SystemConfiguration sc) throws IOException {
        Pointer text = text(key), value = null;
        try {
            value = sc.SCDynamicStoreCopyValue(store, text);
            return value == null ? missing(sc.SCError()) : observation(value);
        } finally { release(value); release(text); }
    }
    private static Optional<String> activeInterface(Pointer store, String key, SystemConfiguration sc) throws IOException {
        Pointer text = text(key), value = null;
        try {
            value = sc.SCDynamicStoreCopyValue(store, text);
            if (value == null && sc.SCError() != 1004 && sc.SCError() != 0) throw new IOException("Could not inspect active service state");
            if (value == null) return Optional.empty();
            // Read only these fields: unrelated IP-state metadata can contain opaque CFData.
            Object addresses = new NativePropertyLists().dictionaryField(value, "Addresses");
            if (!(addresses instanceof List<?> list) || list.isEmpty()
                    || !list.stream().allMatch(address -> address instanceof String string && !string.isBlank())) return Optional.empty();
            Object networkInterface = new NativePropertyLists().dictionaryField(value, "InterfaceName");
            // Empty records activity without inventing an interface association for projection.
            return Optional.of(networkInterface instanceof String name ? name : "");
        } finally { release(value); release(text); }
    }
    private static Observation observation(Pointer value) {
        try { return Observation.present(new NativePropertyLists().dictionary(value)); }
        catch (IOException error) { return Observation.unavailable(Status.UNSUPPORTED); }
    }
    private static Observation missing(int status) { return Observation.unavailable(status == 0 || status == 1004 ? Status.ABSENT : Status.UNREADABLE); }
    private static byte[] signature(Pointer prefs, SystemConfiguration sc) throws IOException {
        Pointer signature = sc.SCPreferencesGetSignature(prefs);
        return signature == null ? null : data(signature);
    }
    private static Pointer required(Pointer value, String operation, SystemConfiguration sc) throws IOException {
        if (value == null) throw new IOException("Could not read " + operation + " (status " + sc.SCError() + ")");
        return value;
    }
}
