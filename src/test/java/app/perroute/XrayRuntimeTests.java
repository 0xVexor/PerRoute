package app.perroute;

import app.perroute.service.XrayRuntime;
import app.perroute.service.XrayProcessManager;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.Set;

public final class XrayRuntimeTests {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("perroute-runtime-");
        try {
            equal(root.resolve("xray.exe"), XrayRuntime.forPlatform("Windows 11", "amd64", root).executable());
            equal(root.resolve("xray.exe"), XrayRuntime.forPlatform("Windows", "x86_64", root).executable());
            equal(root.resolve("macos-arm64/xray"), XrayRuntime.forPlatform("Darwin", "aarch64", root).executable());
            equal(root.resolve("macos-arm64/xray"), XrayRuntime.forPlatform("Mac OS X", "arm64", root).executable());
            equal(root.resolve("macos-x64/xray"), XrayRuntime.forPlatform("Mac OS X", "amd64", root).executable());
            equal(root.resolve("macos-x64/xray"), XrayRuntime.forPlatform("Darwin", "x86_64", root).executable());
            for (String[] platform : new String[][] {{"Linux", "amd64"}, {"Darwin", "x86"},
                    {"Windows 11", "aarch64"}, {"notwindows", "amd64"}, {null, null}}) {
                rejects(UnsupportedOperationException.class, () -> XrayRuntime.forPlatform(platform[0], platform[1], root));
            }
            XrayRuntime mac = XrayRuntime.forPlatform("Darwin", "arm64", root);
            rejects(IOException.class, () -> mac.requireExecutable(false));
            rejects(UnsupportedOperationException.class, () -> mac.requireExecutable(true));
            // Synthetic headers test preflight only; no synthetic executable is ever launched.
            if (root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createDirectories(mac.directory());
                header(mac.executable(), 0x0100000c);
                Files.setPosixFilePermissions(mac.executable(), Set.of(PosixFilePermission.OWNER_READ));
                rejects(IOException.class, () -> mac.requireExecutable(false));
                Files.setPosixFilePermissions(mac.executable(), Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
                equal(mac.executable(), mac.requireExecutable(false));
                header(mac.executable(), 0x01000007);
                rejects(IOException.class, () -> mac.requireExecutable(false));
                Path data = root.resolve("no-state-on-rejection");
                try (XrayProcessManager process = new XrayProcessManager(mac, data)) {
                    rejects(IOException.class, () -> process.validate("{}", false));
                    rejects(IOException.class, () -> process.start("{}", false));
                    equal(false, Files.exists(data));
                    rejects(UnsupportedOperationException.class, process::stopOrphan);
                }
                Files.write(mac.executable(), new byte[] {'M', 'Z'});
                rejects(IOException.class, () -> mac.requireExecutable(false));
            }
            XrayRuntime windows = XrayRuntime.forPlatform("Windows 11", "amd64", root);
            Files.write(windows.executable(), new byte[] {'M', 'Z'});
            equal(windows.executable(), windows.requireExecutable(false));
            rejects(IOException.class, () -> windows.requireExecutable(true));
            Files.write(root.resolve("wintun.dll"), new byte[] {0});
            equal(windows.executable(), windows.requireExecutable(true));
            System.out.println("Xray runtime checks passed (" + assertions + " assertions)");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void header(Path file, int cpu) throws IOException {
        Files.write(file, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(0xfeedfacf).putInt(cpu).array());
    }
    private static void equal(Object expected, Object actual) {
        assertions++;
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(expected + " != " + actual);
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void rejects(Class<? extends Exception> type, Action action) throws Exception {
        assertions++;
        try { action.run(); } catch (Exception error) {
            if (type.isInstance(error)) return;
            throw error;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}
