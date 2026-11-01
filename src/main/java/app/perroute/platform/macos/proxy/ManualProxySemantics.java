package app.perroute.platform.macos.proxy;

import java.math.BigDecimal;
import java.util.*;

/** Supported native semantics only. Never use this projection as the persistent restore dictionary. */
public final class ManualProxySemantics {
    private ManualProxySemantics() { }
    private static final List<String> PROTOCOLS = List.of("HTTP", "HTTPS", "SOCKS");
    static final Set<String> KEYS;
    static {
        var keys = new HashSet<>(List.of("ExceptionsList", "ExcludeSimpleHostnames", "FTPPassive",
                "ProxyAutoConfigEnable", "ProxyAutoDiscoveryEnable"));
        for (String protocol : PROTOCOLS) {
            keys.add(protocol + "Enable"); keys.add(protocol + "Proxy"); keys.add(protocol + "Port");
        }
        KEYS = Set.copyOf(keys);
    }

    public static ProxySettings normalize(ProxySettings settings) {
        var source = settings.values();
        if (!KEYS.containsAll(source.keySet())) throw new IllegalArgumentException();
        var result = new LinkedHashMap<String, Object>();
        for (String protocol : PROTOCOLS) {
            int enabled = flag(source, protocol + "Enable");
            result.put(protocol + "Enable", enabled);
            // Apple's normalized aggregate removes dormant endpoints. The original dictionary is untouched.
            if (enabled == 0) continue;
            Object host = source.get(protocol + "Proxy");
            Object port = source.get(protocol + "Port");
            if (!(host instanceof String text) || text.isBlank() || !(port instanceof BigDecimal number))
                throw new IllegalArgumentException();
            int value = number.intValueExact();
            // Do not infer getservbyname-dependent defaults for absent/zero ports.
            if (value < 1 || value > 65535) throw new IllegalArgumentException();
            result.put(protocol + "Proxy", text); result.put(protocol + "Port", value);
        }
        // PAC/WPAD execution, authentication references, legacy and unknown keys are outside this schema.
        if (flag(source, "ProxyAutoConfigEnable") != 0 || flag(source, "ProxyAutoDiscoveryEnable") != 0)
            throw new IllegalArgumentException();
        result.put("ExcludeSimpleHostnames", flag(source, "ExcludeSimpleHostnames"));
        Object exceptions = source.getOrDefault("ExceptionsList", List.of());
        if (!(exceptions instanceof List<?> list) || list.stream().anyMatch(value -> !(value instanceof String text) || text.isBlank()))
            throw new IllegalArgumentException();
        result.put("ExceptionsList", exceptions);
        if (source.containsKey("FTPPassive")) result.put("FTPPassive", flag(source, "FTPPassive"));
        return new ProxySettings(result);
    }
    private static int flag(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return 0;
        if (!(value instanceof BigDecimal number) || !(number.equals(BigDecimal.ZERO) || number.equals(BigDecimal.ONE)))
            throw new IllegalArgumentException();
        return number.intValueExact();
    }
    public static boolean equivalent(ProxySettings expected, ProxySettings observed) {
        try { return normalize(expected).equals(normalize(observed)); }
        catch (IllegalArgumentException | ArithmeticException refused) { return false; }
    }
}
