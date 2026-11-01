package app.perroute.platform.macos;

import java.awt.Taskbar;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Runtime Dock artwork follows app appearance; the signed bundle keeps its static Finder icon. */
public final class MacApplicationIcon {
    private MacApplicationIcon() {}

    public static BufferedImage image(boolean dark) throws IOException {
        try (var resource = MacApplicationIcon.class.getResourceAsStream("/icons/perroute-icon-256.png")) {
            if (resource == null) throw new IOException("PerRoute icon resource is missing");
            BufferedImage source = ImageIO.read(resource);
            if (source == null) throw new IOException("PerRoute icon resource is invalid");
            if (dark) return source;
            var light = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
                int pixel = source.getRGB(x, y);
                // The source has two colors. Preserve edge coverage and transparency when changing its palette.
                double coverage = Math.clamp((((pixel >>> 8) & 255) - 31.0) / (246.0 - 31.0), 0.0, 1.0);
                int red = mix(244, 79, coverage), green = mix(244, 94, coverage), blue = mix(239, 5, coverage);
                light.setRGB(x, y, (pixel & 0xff000000) | (red << 16) | (green << 8) | blue);
            }
            return light;
        }
    }

    public static void apply(BufferedImage image) {
        if (!Taskbar.isTaskbarSupported()) return;
        Taskbar taskbar = Taskbar.getTaskbar();
        if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) taskbar.setIconImage(image);
    }

    private static int mix(int background, int foreground, double coverage) {
        return (int)Math.round(background + (foreground - background) * coverage);
    }
}
