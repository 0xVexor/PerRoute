package app.perroute.platform.macos;

import app.perroute.model.ApplicationIdentity;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Objects;

/** Read-only native thumbnails, independent of discovery and routing authority. */
public final class MacApplicationIcons {
    @FunctionalInterface public interface Source { String read(String executable) throws IOException; }
    private final Source source;

    public MacApplicationIcons(Source source) { this.source = Objects.requireNonNull(source); }

    public static MacApplicationIcons system() {
        return new MacApplicationIcons(executable -> {
            try {
                var bridge = SystemBridge.INSTANCE;
                Pointer value = bridge.perroute_application_icon(executable);
                if (value == null) return null;
                try { return value.getString(0, "UTF-8"); }
                finally { bridge.perroute_application_icon_free(value); }
            } catch (LinkageError failure) { throw new IOException("macOS application icons are unavailable", failure); }
        });
    }

    public byte[] png(String identity) throws IOException {
        if (!ApplicationIdentity.isMac(identity)) return null;
        String encoded = source.read(ApplicationIdentity.path(identity));
        if (encoded == null) return null;
        if (encoded.length() > 87384) throw new IOException("Oversized application icon");
        final byte[] png;
        try { png = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException failure) { throw new IOException("Invalid application icon", failure); }
        // Validate dimensions before a UI image decoder allocates pixels.
        if (png.length < 33 || png.length > 65536) throw new IOException("Invalid application icon size");
        ByteBuffer header = ByteBuffer.wrap(png);
        if (header.getLong() != 0x89504e470d0a1a0aL || header.getInt() != 13 ||
                header.getInt() != 0x49484452 || header.getInt() != 64 || header.getInt() != 64)
            throw new IOException("Expected a 64-pixel PNG application icon");
        return png;
    }

    private static final class SystemBridge {
        static final Bridge INSTANCE = load();
        private static Bridge load() {
            String resources = System.getProperty("compose.application.resources.dir");
            Path library = resources == null ? Path.of("build/macos-desktop/libPerRouteDesktopBridge.dylib")
                    : Path.of(resources).resolve("../../Frameworks/libPerRouteDesktopBridge.dylib").normalize();
            return Native.load(library.toAbsolutePath().toString(), Bridge.class);
        }
    }
    private interface Bridge extends Library {
        Pointer perroute_application_icon(String executable);
        void perroute_application_icon_free(Pointer value);
    }
}
