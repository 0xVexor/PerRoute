package app.perroute.platform.macos.proxy;

import com.sun.jna.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static app.perroute.platform.macos.proxy.NativePropertyLists.*;

/** Bounded XML plist interchange preserves dictionary types without exposing Java strings as pointers. */
final class NativeProxyEncoding {
    interface Foundation extends Library {
        Pointer CFDataCreate(Pointer allocator, byte[] bytes, NativeLong length);
        Pointer CFPropertyListCreateWithData(Pointer allocator, Pointer data, NativeLong options, Pointer format, Pointer error);
    }
    private static final class Api {
        static final Foundation CF = Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", Foundation.class);
    }
    static Pointer create(ProxySettings settings) throws IOException {
        var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\">");
        append(xml, settings.values(), 0); xml.append("</plist>");
        byte[] bytes = xml.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1_048_576) throw new IOException("Proxy property list is too large");
        Pointer data = Api.CF.CFDataCreate(null, bytes, new NativeLong(bytes.length));
        if (data == null) throw new IOException("Could not allocate proxy property list");
        try {
            Pointer value = Api.CF.CFPropertyListCreateWithData(null, data, new NativeLong(0), null, null);
            if (value == null) throw new IOException("Could not encode proxy property list");
            try {
                if (!new NativePropertyLists().dictionary(value).equals(settings)) throw new IOException("Proxy property list failed lossless round trip");
                return value;
            } catch (IOException error) { release(value); throw error; }
        } finally { release(data); }
    }
    private static void append(StringBuilder xml, Object value, int depth) throws IOException {
        if (depth > 32 || xml.length() > 1_048_576) throw new IOException("Proxy property list exceeds encoding bounds");
        if (value instanceof Map<?, ?> map) {
            xml.append("<dict>");
            for (var entry : map.entrySet()) { xml.append("<key>"); escape(xml, (String) entry.getKey()); xml.append("</key>"); append(xml, entry.getValue(), depth + 1); }
            xml.append("</dict>");
        } else if (value instanceof List<?> list) {
            xml.append("<array>"); for (Object item : list) append(xml, item, depth + 1); xml.append("</array>");
        } else if (value instanceof String text) { xml.append("<string>"); escape(xml, text); xml.append("</string>"); }
        else if (value instanceof Boolean flag) xml.append(flag ? "<true/>" : "<false/>");
        else if (value instanceof BigDecimal number) {
            if (Math.abs((long) number.scale()) > 1024 || number.precision() > 1024) throw new IOException("Proxy number exceeds encoding bounds");
            String type = number.scale() <= 0 ? "integer" : "real";
            xml.append('<').append(type).append('>').append(number.toPlainString()).append("</").append(type).append('>');
        } else throw new IOException("Unsupported proxy property-list value");
    }
    private static void escape(StringBuilder xml, String text) throws IOException {
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i); i += Character.charCount(cp);
            if (!(cp == 9 || cp == 10 || cp == 13 || cp >= 32 && cp <= 0xD7FF || cp >= 0xE000 && cp <= 0xFFFD || cp >= 0x10000 && cp <= 0x10FFFF))
                throw new IOException("Unsupported proxy property-list character");
            switch (cp) { case '&' -> xml.append("&amp;"); case '<' -> xml.append("&lt;"); case '>' -> xml.append("&gt;"); default -> xml.appendCodePoint(cp); }
        }
    }
}
