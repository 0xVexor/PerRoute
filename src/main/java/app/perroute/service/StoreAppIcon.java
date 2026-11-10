package app.perroute.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds the tile logo of a packaged (Store) application. Many of these executables are stubs without an embedded icon,
 * so the shell returns nothing for them and the logo declared in the package manifest is the only real artwork.
 */
public final class StoreAppIcon {
    private StoreAppIcon() {}

    private static final int WANTED = 64;
    private static final Pattern APPLICATION = Pattern.compile(
            "<(?:[a-zA-Z0-9_-]+:)?Application\\b([^>]*)>(.*?)</(?:[a-zA-Z0-9_-]+:)?Application>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern EXECUTABLE = Pattern.compile("\\bExecutable\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern TARGET_SIZE = Pattern.compile("targetsize-(\\d+)");
    private static final Pattern SCALE = Pattern.compile("scale-(\\d+)");

    /** The best logo file for the executable of a packaged application, or null when it has none. */
    public static Path find(Path executable) {
        try {
            for (Path directory = executable.getParent(); directory != null; directory = directory.getParent()) {
                Path manifest = directory.resolve("AppxManifest.xml");
                if (!Files.isRegularFile(manifest)) continue;
                String logo = logoOf(Files.readString(manifest, StandardCharsets.UTF_8), directory.relativize(executable).toString());
                return logo == null ? null : bestVariant(directory.resolve(logo.replace('/', '\\')));
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return null;
    }

    static String logoOf(String manifest, String relativeExecutable) {
        String fallback = null;
        Matcher application = APPLICATION.matcher(manifest);
        while (application.find()) {
            String logo = preferredLogo(application.group(2));
            if (logo == null) continue;
            Matcher executable = EXECUTABLE.matcher(application.group(1));
            if (executable.find() && executable.group(2).replace('/', '\\').equalsIgnoreCase(relativeExecutable)) return logo;
            if (fallback == null) fallback = logo;
        }
        return fallback;
    }

    // The 44x44 logo is the small app-list icon; larger tile logos are only a fallback.
    private static String preferredLogo(String visualElements) {
        for (String kind : new String[] { "Square44x44Logo", "Square30x30Logo", "Square71x71Logo", "Square150x150Logo" }) {
            Matcher logo = Pattern.compile("\\b" + kind + "\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE).matcher(visualElements);
            if (logo.find()) return logo.group(2);
        }
        return null;
    }

    private static Path bestVariant(Path logo) throws IOException {
        Path directory = logo.getParent();
        String file = logo.getFileName().toString();
        int dot = file.lastIndexOf('.');
        String stem = (dot < 0 ? file : file.substring(0, dot)).toLowerCase(Locale.ROOT);
        if (directory == null || !Files.isDirectory(directory)) return null;
        Path best = null;
        int bestScore = Integer.MAX_VALUE;
        try (Stream<Path> files = Files.list(directory)) {
            for (Path candidate : (Iterable<Path>) files::iterator) {
                String name = candidate.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".png") || !(name.equals(stem + ".png") || name.startsWith(stem + ".")) || name.contains("contrast-")) continue;
                int size = pixels(name);
                // Prefer the smallest image that is still sharp at the displayed size, plain over theme-specific plates.
                int score = (size >= WANTED ? size - WANTED : (WANTED - size) * 4) * 4 + (name.contains("altform-lightunplated") ? 2 : name.contains("altform") ? 1 : 0);
                if (score < bestScore) { bestScore = score; best = candidate; }
            }
        }
        return best;
    }

    static int pixels(String name) {
        Matcher target = TARGET_SIZE.matcher(name);
        if (target.find()) return Integer.parseInt(target.group(1));
        Matcher scale = SCALE.matcher(name);
        if (scale.find()) return 44 * Integer.parseInt(scale.group(1)) / 100;
        return 44;
    }
}
