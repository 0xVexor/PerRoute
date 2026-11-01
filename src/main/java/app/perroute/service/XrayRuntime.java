package app.perroute.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;

/** Resolves one engine consistently for launch, validation, API calls and probes. */
public record XrayRuntime(Path root, Target target) {
    public enum Target {
        WINDOWS_X64("", "xray.exe", 0),
        MACOS_ARM64("macos-arm64", "xray", 0x0100000c),
        MACOS_X64("macos-x64", "xray", 0x01000007);

        private final String folder;
        private final String executable;
        private final int cpuType;

        Target(String folder, String executable, int cpuType) {
            this.folder = folder;
            this.executable = executable;
            this.cpuType = cpuType;
        }
    }

    public XrayRuntime {
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        Objects.requireNonNull(target, "target");
    }

    public static XrayRuntime current() {
        return forPlatform(System.getProperty("os.name"), System.getProperty("os.arch"), AppDirectories.coreDirectory());
    }

    public static XrayRuntime forPlatform(String osName, String architecture, Path root) {
        String os = normalize(osName);
        String arch = normalize(architecture);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if ((os.equals("windows") || os.startsWith("windows ")) && x64) {
            return new XrayRuntime(root, Target.WINDOWS_X64);
        }
        if (os.equals("mac os x") || os.equals("darwin")) {
            if (x64) return new XrayRuntime(root, Target.MACOS_X64);
            if (arch.equals("aarch64") || arch.equals("arm64")) return new XrayRuntime(root, Target.MACOS_ARM64);
        }
        throw new UnsupportedOperationException("Unsupported Xray platform: " + osName + "/" + architecture);
    }

    public Path directory() { return root.resolve(target.folder); }
    public Path executable() { return directory().resolve(target.executable); }

    public Path requireExecutable(boolean requiresWintun) throws IOException {
        if (requiresWintun && target != Target.WINDOWS_X64) {
            throw new UnsupportedOperationException("Wintun mode is available only on Windows");
        }
        Path executable = executable();
        if (!Files.isRegularFile(executable)) throw new IOException("Xray is not installed at " + executable);
        if (requiresWintun && !Files.isRegularFile(directory().resolve("wintun.dll"))) {
            throw new IOException("Wintun is not installed. Use the Install core button first.");
        }
        if (target != Target.WINDOWS_X64) {
            if (!Files.isExecutable(executable)) throw new IOException("Xray has no execute permission: " + executable);
            // Official Darwin artifacts are thin 64-bit Mach-O images. Refuse PE/ELF, truncated files
            // and the other CPU architecture before creating configs or launching a child process.
            ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            try (FileChannel channel = FileChannel.open(executable, StandardOpenOption.READ)) {
                while (header.hasRemaining() && channel.read(header) != -1) { }
            }
            header.flip();
            if (header.remaining() != 8 || header.getInt() != 0xfeedfacf || header.getInt() != target.cpuType) {
                throw new IOException("Xray is not a matching 64-bit Mach-O executable for " + target);
            }
        }
        return executable;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
