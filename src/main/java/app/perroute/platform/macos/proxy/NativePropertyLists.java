package app.perroute.platform.macos.proxy;

import com.sun.jna.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Copies supported CF values while their owning native container remains retained. */
final class NativePropertyLists {
    interface CoreFoundation extends Library {
        NativeLong CFGetTypeID(Pointer value);
        NativeLong CFStringGetTypeID();
        NativeLong CFNumberGetTypeID();
        NativeLong CFBooleanGetTypeID();
        NativeLong CFArrayGetTypeID();
        NativeLong CFDictionaryGetTypeID();
        NativeLong CFDataGetTypeID();
        NativeLong CFStringGetLength(Pointer value);
        NativeLong CFStringGetMaximumSizeForEncoding(NativeLong length, int encoding);
        byte CFStringGetCString(Pointer value, byte[] buffer, NativeLong size, int encoding);
        Pointer CFStringCreateWithCString(Pointer allocator, String text, int encoding);
        NativeLong CFArrayGetCount(Pointer value);
        Pointer CFArrayGetValueAtIndex(Pointer value, NativeLong index);
        NativeLong CFDictionaryGetCount(Pointer value);
        void CFDictionaryGetKeysAndValues(Pointer value, Pointer[] keys, Pointer[] values);
        Pointer CFDictionaryGetValue(Pointer value, Pointer key);
        byte CFBooleanGetValue(Pointer value);
        byte CFNumberIsFloatType(Pointer value);
        byte CFNumberGetValue(Pointer value, int type, Pointer result);
        NativeLong CFDataGetLength(Pointer value);
        Pointer CFDataGetBytePtr(Pointer value);
        void CFRelease(Pointer value);
    }
    static final CoreFoundation CF = Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", CoreFoundation.class);
    private static final int UTF8 = 0x08000100;
    private int remaining = 10_000;
    private long remainingBytes = 1_048_576;

    ProxySettings dictionary(Pointer value) throws IOException {
        if (value == null || !type(value, CF.CFDictionaryGetTypeID())) throw unsupported();
        try { return new ProxySettings(map(value, 0)); }
        catch (IllegalArgumentException failure) { throw unsupported(); }
    }
    Object dictionaryField(Pointer value, String key) throws IOException {
        if (value == null || !type(value, CF.CFDictionaryGetTypeID())) throw unsupported();
        Pointer fieldName = text(key);
        try {
            Pointer field = CF.CFDictionaryGetValue(value, fieldName);
            return field == null ? null : read(field, 0);
        } finally { release(fieldName); }
    }
    String string(Pointer value) throws IOException {
        if (value == null) return "";
        if (!type(value, CF.CFStringGetTypeID())) throw unsupported();
        long characters = CF.CFStringGetLength(value).longValue();
        long size = CF.CFStringGetMaximumSizeForEncoding(new NativeLong(characters), UTF8).longValue() + 1;
        if (size < 1 || size > remainingBytes) throw unsupported();
        remainingBytes -= size;
        byte[] buffer = new byte[(int) size];
        if (CF.CFStringGetCString(value, buffer, new NativeLong(size), UTF8) == 0) throw unsupported();
        int length = 0;
        while (length < buffer.length && buffer[length] != 0) length++;
        String result = new String(buffer, 0, length, StandardCharsets.UTF_8);
        if (result.length() != characters) throw unsupported();
        return result;
    }
    static Pointer text(String value) throws IOException {
        Pointer pointer = CF.CFStringCreateWithCString(null, value, UTF8);
        if (pointer == null) throw new IOException("Could not allocate native string");
        return pointer;
    }
    static byte[] data(Pointer value) throws IOException {
        if (value == null || !type(value, CF.CFDataGetTypeID())) throw unsupported();
        long length = CF.CFDataGetLength(value).longValue();
        if (length < 0 || length > 65_536) throw unsupported();
        return length == 0 ? new byte[0] : CF.CFDataGetBytePtr(value).getByteArray(0, (int) length);
    }
    static int count(NativeLong value) throws IOException {
        long count = value.longValue();
        if (count < 0 || count > 10_000) throw unsupported();
        return (int) count;
    }
    private Map<String, Object> map(Pointer value, int depth) throws IOException {
        int count = count(CF.CFDictionaryGetCount(value));
        var keys = new Pointer[count]; var values = new Pointer[count];
        CF.CFDictionaryGetKeysAndValues(value, keys, values);
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < count; i++) {
            String key = string(keys[i]);
            if (key.isBlank() || result.containsKey(key)) throw unsupported();
            result.put(key, read(values[i], depth + 1));
        }
        return result;
    }
    private Object read(Pointer value, int depth) throws IOException {
        if (value == null || depth > 32 || --remaining < 0) throw unsupported();
        if (type(value, CF.CFStringGetTypeID())) return string(value);
        if (type(value, CF.CFBooleanGetTypeID())) return CF.CFBooleanGetValue(value) != 0;
        if (type(value, CF.CFNumberGetTypeID())) {
            try (var memory = new Memory(8)) {
                boolean floating = CF.CFNumberIsFloatType(value) != 0;
                if (CF.CFNumberGetValue(value, floating ? 13 : 4, memory) == 0) throw unsupported();
                if (!floating) return memory.getLong(0);
                double number = memory.getDouble(0);
                if (!Double.isFinite(number)) throw unsupported();
                return number;
            }
        }
        if (type(value, CF.CFDictionaryGetTypeID())) return map(value, depth);
        if (type(value, CF.CFArrayGetTypeID())) {
            var result = new ArrayList<>();
            int count = count(CF.CFArrayGetCount(value));
            for (int i = 0; i < count; i++) result.add(read(CF.CFArrayGetValueAtIndex(value, new NativeLong(i)), depth + 1));
            return result;
        }
        // CFData/date/unknown types must not silently become strings or disappear from a future snapshot.
        throw unsupported();
    }
    private static boolean type(Pointer value, NativeLong type) { return CF.CFGetTypeID(value).equals(type); }
    private static IOException unsupported() { return new IOException("Unsupported or oversized native property-list value"); }
    static void release(Pointer value) { if (value != null) CF.CFRelease(value); }
}
