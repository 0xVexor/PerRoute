package app.perroute.service;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.filechooser.FileSystemView;

/** Loads the icon of a Windows application: the shell icon first, then the logo declared by a packaged app. */
public final class WindowsAppIcons {
    /** The {@link IssueLog} source for icons that could not be found. */
    public static final String SOURCE = "app-icons";
    private static final int SIZE = 64;

    private WindowsAppIcons() {}

    /** The icon image, or null when the app has none (the caller then shows a lettered placeholder). */
    public static BufferedImage load(Path executable) {
        if (!Files.isRegularFile(executable)) return null;
        BufferedImage shell = fromShell(executable);
        if (shell != null) return shell;
        Path logo = StoreAppIcon.find(executable);
        if (logo == null) return null;
        try {
            return ImageIO.read(logo.toFile());
        } catch (IOException | RuntimeException error) {
            IssueLog.record(SOURCE, "Could not read the logo " + logo, error);
            return null;
        }
    }

    // The JDK throws instead of returning null when the shell has no icon for a packaged-app stub, so a thrown
    // error here means "no shell icon", not a failure worth reporting on its own.
    private static BufferedImage fromShell(Path executable) {
        Icon icon;
        try {
            icon = FileSystemView.getFileSystemView().getSystemIcon(executable.toFile(), SIZE, SIZE);
        } catch (RuntimeException ignored) {
            return null;
        }
        if (icon == null) return null;
        BufferedImage image = new BufferedImage(Math.max(1, icon.getIconWidth()), Math.max(1, icon.getIconHeight()), BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            icon.paintIcon(null, graphics, 0, 0);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
