package app.perroute.platform.macos.proxy;

import java.math.BigDecimal;
import java.util.*;

/** Immutable, lossless JSON-compatible projection. A native adapter must refuse unsupported plist values. */
public record ProxySettings(Map<String, Object> values) {
    public ProxySettings { values = copyMap(values); }

    public ProxySettings select(Collection<String> keys) {
        var selected = new LinkedHashMap<String, Object>();
        keys.forEach(key -> { if (values.containsKey(key)) selected.put(key, values.get(key)); });
        return new ProxySettings(selected);
    }

    ProxySettings replacing(Collection<String> keys, ProxySettings replacement) {
        var result = new LinkedHashMap<>(values);
        keys.forEach(result::remove);
        result.putAll(replacement.values);
        return new ProxySettings(result);
    }

    private static Map<String, Object> copyMap(Map<?, ?> source) {
        Objects.requireNonNull(source, "settings");
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> {
            if (!(key instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Invalid proxy setting key");
            result.put(text, copy(value));
        });
        return Collections.unmodifiableMap(result);
    }
    private static Object copy(Object value) {
        if (value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number number) return new BigDecimal(number.toString()).stripTrailingZeros();
        if (value instanceof Map<?, ?> map) return copyMap(map);
        if (value instanceof List<?> list) return list.stream().map(ProxySettings::copy).toList();
        throw new IllegalArgumentException("Unsupported proxy setting value");
    }

    // Recovery dictionaries can contain PAC URLs or authentication references; never print their contents.
    @Override public String toString() { return "ProxySettings[redacted]"; }
}
