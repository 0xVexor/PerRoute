package app.perroute;

import app.perroute.model.ApplicationIdentity;
import app.perroute.platform.macos.MacApplicationIcons;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import javax.imageio.ImageIO;

public final class MacosApplicationIconTests {
    private static int checks;
    public static void main(String[] args) throws Exception {
        String executable = "/Applications/Editor فارسی.app/Contents/MacOS/Editor";
        String exact = ApplicationIdentity.macExecutable(executable);
        String family = ApplicationIdentity.macAppFamily(executable);
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB), "png", output);
        byte[] png = output.toByteArray();
        var paths = new ArrayList<String>();
        var icons = new MacApplicationIcons(path -> { paths.add(path); return Base64.getEncoder().encodeToString(png); });
        check(Arrays.equals(icons.png(exact), png), "Exact icon bytes survive the native boundary");
        check(Arrays.equals(icons.png(family), png), "Explicit family uses main executable artwork");
        check(paths.equals(java.util.List.of(executable, executable)), "Unicode and exact path reach icon source without scope tags");
        check(icons.png("C:\\Applications\\Editor.exe") == null && paths.size() == 2, "Foreign identity does not enter the Mac bridge");
        check(new MacApplicationIcons(path -> null).png(exact) == null, "Missing icon remains an explicit fallback");
        fail(() -> new MacApplicationIcons(path -> "!").png(exact));
        fail(() -> new MacApplicationIcons(path -> "A".repeat(87385)).png(exact));
        fail(() -> new MacApplicationIcons(path -> Base64.getEncoder().encodeToString(new byte[65537])).png(exact));
        fail(() -> new MacApplicationIcons(path -> "").png(exact));
        byte[] hugePixels = png.clone(); ByteBuffer.wrap(hugePixels).putInt(16, 100000);
        fail(() -> new MacApplicationIcons(path -> Base64.getEncoder().encodeToString(hugePixels)).png(exact));
        byte[] jpeg = png.clone(); jpeg[0] = 0;
        fail(() -> new MacApplicationIcons(path -> Base64.getEncoder().encodeToString(jpeg)).png(exact));
        fail(() -> new MacApplicationIcons(path -> { throw new IOException("Unavailable"); }).png(exact));
        if (System.getProperty("os.name").equals("Mac OS X")) nativeChecks();
        System.out.println("Application icons: " + checks + " checks passed; read-only native artwork, no apps launched");
    }
    private static void nativeChecks() throws Exception {
        var nativeIcons = MacApplicationIcons.system();
        Path root = Files.createTempDirectory("perroute-icons-").toRealPath();
        try {
            Path main = bundle(root.resolve("Editor فارسی.app"));
            Path helper = bundle(root.resolve("Editor فارسی.app/Contents/Helpers/Worker.app"));
            checkImage(nativeIcons.png(ApplicationIdentity.macExecutable(main.toString())));
            checkImage(nativeIcons.png(ApplicationIdentity.macAppFamily(main.toString())));
            checkImage(nativeIcons.png(ApplicationIdentity.macExecutable(helper.toString())));
            Path resource = main.resolveSibling("Unrelated");
            Files.writeString(resource, "fixture, never executed"); resource.toFile().setExecutable(true);
            check(nativeIcons.png(ApplicationIdentity.macExecutable(resource.toString())) == null, "Unrelated file cannot inherit outer bundle artwork");
            Path plain = root.resolve("Standalone"); Files.copy(main,plain); plain.toFile().setExecutable(true);
            check(nativeIcons.png(ApplicationIdentity.macExecutable(plain.toString())) == null, "Unbundled executable retains fallback");
            check(nativeIcons.png(ApplicationIdentity.macExecutable(root.resolve("Missing").toString())) == null, "Missing path does not invent artwork");
            check(nativeIcons.png(ApplicationIdentity.macExecutable(root.toString())) == null, "Directory is not an executable candidate");
            Path chrome = Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            Path safari = Path.of("/Applications/Safari.app/Contents/MacOS/Safari");
            if (Files.exists(chrome) && Files.exists(safari)) {
                byte[] chromeIcon = nativeIcons.png(ApplicationIdentity.macExecutable(chrome.toRealPath().toString()));
                byte[] safariIcon = nativeIcons.png(ApplicationIdentity.macExecutable(safari.toRealPath().toString()));
                checkImage(chromeIcon); checkImage(safariIcon);
                check(!Arrays.equals(chromeIcon,safariIcon), "Real Chrome and Safari return distinct artwork");
            }
        } finally {
            try (var walk = Files.walk(root)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static Path bundle(Path path) throws Exception {
        Path contents = path.resolve("Contents"); Path executable = contents.resolve("MacOS/Fixture");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable,"fixture, never executed");
        if (!executable.toFile().setExecutable(true)) throw new IOException("Cannot prepare fixture metadata");
        Files.writeString(contents.resolve("Info.plist"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <plist version="1.0"><dict>
                <key>CFBundleExecutable</key><string>Fixture</string>
                <key>CFBundleIdentifier</key><string>example.perroute.iconfixture</string>
                <key>CFBundlePackageType</key><string>APPL</string>
                </dict></plist>
                """);
        return executable;
    }
    private static void checkImage(byte[] png) throws Exception {
        check(png != null, "Native icon is available");
        try (var input = new ByteArrayInputStream(png)) {
            var image = ImageIO.read(input);
            check(image != null && image.getWidth() == 64 && image.getHeight() == 64, "Native PNG decodes to the bounded thumbnail");
            boolean visible = false;
            for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++)
                if ((image.getRGB(x,y) >>> 24) != 0) visible = true;
            check(visible, "Rendered native artwork is not fully transparent");
        }
    }
    private interface Work { void run() throws Exception; }
    private static void fail(Work work) throws Exception {
        try { work.run(); throw new AssertionError("Invalid native thumbnail accepted"); }
        catch (IOException expected) { checks++; }
    }
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
}
