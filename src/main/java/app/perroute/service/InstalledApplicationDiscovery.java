package app.perroute.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InstalledApplicationDiscovery {
    /** The {@link IssueLog} source for everything this class falls back on. */
    public static final String SOURCE = "app-discovery";
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(12);
    private static final List<Query> QUERIES = List.of(
            new Query("HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall", null),
            new Query("HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall", "64"),
            new Query("HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall", "32")
    );
    private static final String SHELL_DISCOVERY_SCRIPT = """
            $ErrorActionPreference = 'SilentlyContinue'
            Get-StartApps | ForEach-Object { Write-Output ("START`t$($_.Name)`t$($_.AppID)") }
            Get-AppxPackage | ForEach-Object { Write-Output ("PACKAGE`t$($_.PackageFamilyName)`t$($_.InstallLocation)") }
            """;
    private static final Pattern APPLICATION_TAG = Pattern.compile("<(?:[a-zA-Z0-9_-]+:)?Application\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_ATTRIBUTE = Pattern.compile("(?:^|\\s)(Id|Executable)\\s*=\\s*([\"'])(.*?)\\2", Pattern.CASE_INSENSITIVE);

    public List<InstalledApplication> list() {
        Map<String, InstalledApplication> byPath = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (InstalledApplication app : discoverShellApplications()) addCandidate(byPath, app.name(), safePath(app.path()));
        for (Query query : QUERIES) {
            for (RegistryEntry entry : query(query)) {
                if (!isUserFacingProductName(entry.name)) continue;
                addCandidate(byPath, entry.name, resolveExecutable(entry));
            }
        }
        Map<String, InstalledApplication> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        byPath.values().forEach(app -> byName.putIfAbsent(canonicalName(app.name()), app));
        return byName.values().stream()
                .sorted(Comparator.comparing(InstalledApplication::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static void addCandidate(Map<String, InstalledApplication> destination, String suggestedName, Path executable) {
        if (executable == null || !Files.isRegularFile(executable) || !isApplicationExecutable(executable)) return;
        String name = suggestedName == null || suggestedName.isBlank()
                ? stripExe(executable.getFileName().toString()) : suggestedName.strip();
        if (!isUserFacingProductName(name)) return;
        InstalledApplication app = new InstalledApplication(cleanDisplayName(name), executable.toAbsolutePath().normalize().toString());
        destination.putIfAbsent(app.path(), app);
    }

    private static List<InstalledApplication> discoverShellApplications() {
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        Path powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        if (!Files.isRegularFile(powershell)) return List.of();
        String encoded = java.util.Base64.getEncoder().encodeToString(SHELL_DISCOVERY_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        try {
            Process process = new ProcessBuilder(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                    .redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(QUERY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                IssueLog.record(SOURCE, "Windows Store app list timed out after " + QUERY_TIMEOUT.toSeconds() + "s");
                return List.of();
            }
            return parseShellOutput(decodePowerShellOutput(output));
        } catch (IOException error) {
            IssueLog.record(SOURCE, "Could not read the Windows Store app list", error);
            return List.of();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    static List<InstalledApplication> parseShellOutput(String output) {
        List<InstalledApplication> result = new ArrayList<>();
        Map<String, String> startNames = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<PackageEntry> packages = new ArrayList<>();
        for (String line : output.replace("\r", "").split("\n")) {
            String[] fields = line.strip().split("\\t", 3);
            if (fields.length != 3) continue;
            if (fields[0].equals("START") && !fields[1].isBlank() && !fields[2].isBlank()) {
                startNames.put(fields[2].strip(), fields[1].strip());
            } else if (fields[0].equals("PACKAGE") && !fields[1].isBlank() && !fields[2].isBlank()) {
                packages.add(new PackageEntry(fields[1].strip(), fields[2].strip()));
            }
        }
        for (PackageEntry entry : packages) {
            Path location = safePath(entry.location);
            if (location == null) continue;
            Path manifest = location.resolve("AppxManifest.xml");
            if (!Files.isRegularFile(manifest)) continue;
            try {
                String xml = Files.readString(manifest, StandardCharsets.UTF_8);
                Matcher application = APPLICATION_TAG.matcher(xml);
                while (application.find()) {
                    String id = null;
                    String executable = null;
                    Matcher attribute = XML_ATTRIBUTE.matcher(application.group(1));
                    while (attribute.find()) {
                        if (attribute.group(1).equalsIgnoreCase("Id")) id = attribute.group(3);
                        if (attribute.group(1).equalsIgnoreCase("Executable")) executable = attribute.group(3);
                    }
                    if (id == null || executable == null) continue;
                    String name = startNames.get(entry.family + "!" + id);
                    if (name == null || name.isBlank()) continue;
                    result.add(new InstalledApplication(name, location.resolve(executable.replace('/', '\\')).normalize().toString()));
                }
            } catch (IOException | SecurityException ignored) {
            }
        }
        return result;
    }

    private static String decodePowerShellOutput(byte[] output) {
        if (output.length >= 4 && (output[1] == 0 || (output[0] == (byte) 0xff && output[1] == (byte) 0xfe))) {
            return new String(output, StandardCharsets.UTF_16LE);
        }
        return new String(output, Charset.defaultCharset());
    }

    private static List<RegistryEntry> query(Query query) {
        List<String> command = new ArrayList<>(List.of("reg.exe", "query", query.key, "/s"));
        if (query.view != null) command.add("/reg:" + query.view);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(QUERY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                IssueLog.record(SOURCE, "Registry query timed out: " + query.key);
                return List.of();
            }
            // A missing key (exit 1) is normal for the 32-bit view on some systems, so only other codes are issues.
            if (process.exitValue() != 0) {
                if (process.exitValue() != 1) IssueLog.record(SOURCE, "Registry query failed (" + process.exitValue() + "): " + query.key);
                return List.of();
            }
            return parseRegistryOutput(new String(output, Charset.defaultCharset()));
        } catch (IOException error) {
            IssueLog.record(SOURCE, "Could not run the registry query for " + query.key, error);
            return List.of();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    static List<RegistryEntry> parseRegistryOutput(String output) {
        List<RegistryEntry> result = new ArrayList<>();
        Map<String, String> values = new LinkedHashMap<>();
        for (String rawLine : output.replace("\r", "").split("\n")) {
            String line = rawLine.stripTrailing();
            if (line.stripLeading().startsWith("HKEY_")) {
                addEntry(result, values);
                values = new LinkedHashMap<>();
                continue;
            }
            String trimmed = line.stripLeading();
            int typeAt = trimmed.indexOf("REG_");
            if (typeAt <= 0) continue;
            String name = trimmed.substring(0, typeAt).strip();
            int valueAt = trimmed.indexOf(' ', typeAt);
            if (valueAt < 0) continue;
            String value = trimmed.substring(valueAt).strip();
            values.put(name, value);
        }
        addEntry(result, values);
        return result;
    }

    private static void addEntry(List<RegistryEntry> result, Map<String, String> values) {
        String name = values.getOrDefault("DisplayName", "");
        String icon = values.getOrDefault("DisplayIcon", "");
        String location = values.getOrDefault("InstallLocation", "");
        if (!name.isBlank() && (!icon.isBlank() || !location.isBlank())) result.add(new RegistryEntry(name, icon, location));
    }

    private static Path resolveExecutable(RegistryEntry entry) {
        Path icon = pathFromDisplayIcon(entry.icon);
        if (icon != null && Files.isRegularFile(icon)) return icon.toAbsolutePath().normalize();
        if (entry.location == null || entry.location.isBlank()) return null;
        Path directory = safePath(expandEnvironment(entry.location));
        if (directory == null || !Files.isDirectory(directory)) return null;
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        for (String root : new String[] { System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)") }) {
            if (root != null && normalizedDirectory.equals(Path.of(root).toAbsolutePath().normalize())) return null;
        }
        String wanted = normalizeName(entry.name);
        try (var files = Files.walk(directory, 2)) {
            return files.filter(Files::isRegularFile)
                    .filter(InstalledApplicationDiscovery::isApplicationExecutable)
                    .sorted(Comparator.comparingInt(path -> executableScore(path, wanted)))
                    .findFirst().map(Path::toAbsolutePath).map(Path::normalize).orElse(null);
        } catch (IOException | UncheckedIOException | SecurityException ignored) {
            return null;
        }
    }

    private static Path pathFromDisplayIcon(String value) {
        if (value == null || value.isBlank()) return null;
        String expanded = expandEnvironment(value.strip());
        String path;
        if (expanded.startsWith("\"")) {
            int close = expanded.indexOf('"', 1);
            path = close > 1 ? expanded.substring(1, close) : expanded.substring(1);
        } else {
            int comma = expanded.toLowerCase(Locale.ROOT).indexOf(".exe,");
            path = comma >= 0 ? expanded.substring(0, comma + 4) : expanded;
        }
        return safePath(path.strip().replace("\\\"", "\""));
    }

    private static Path safePath(String value) {
        try { return value == null || value.isBlank() ? null : Path.of(value); }
        catch (RuntimeException ignored) { return null; }
    }

    private static String expandEnvironment(String value) {
        String expanded = value;
        for (Map.Entry<String, String> variable : System.getenv().entrySet()) {
            expanded = expanded.replace("%" + variable.getKey() + "%", variable.getValue());
            expanded = expanded.replace("%" + variable.getKey().toLowerCase(Locale.ROOT) + "%", variable.getValue());
        }
        return expanded;
    }

    static boolean isUserFacingProductName(String value) {
        String name = value == null ? "" : value.toLowerCase(Locale.ROOT).strip();
        if (name.isBlank()) return false;
        if (name.matches("java(\\(tm\\))?\\s+\\d+.*")) return false;
        if (name.matches(".*\\b(jdk|sdk|runtime)\\b.*")) return false;
        return List.of(" jdk", "jdk ", "openjdk", "development kit", " sdk", "sdk ", "runtime",
                "redistributable", "webview2", "windows packet filter", "proxifyre", "driver package",
                "debug symbols", "toolchain").stream().noneMatch(name::contains);
    }

    private static boolean isApplicationExecutable(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".exe")) return false;
        if (List.of("java.exe", "javaw.exe", "javac.exe", "jshell.exe", "jpackage.exe").contains(name)) return false;
        return List.of("unins", "uninstall", "update", "updater", "setup", "install", "repair",
                "crashpad", "helper", "service", "notify", "elevate").stream().noneMatch(name::contains);
    }

    private static int executableScore(Path path, String wanted) {
        String candidate = normalizeName(stripExe(path.getFileName().toString()));
        if (candidate.equals(wanted)) return 0;
        if (candidate.contains(wanted) || wanted.contains(candidate)) return 1;
        return 10 + path.getNameCount();
    }

    private static String cleanDisplayName(String value) {
        return value.replaceAll("(?i)\\s*\\((x64|x86|64-bit|32-bit)\\)\\s*$", "").strip();
    }

    private static String canonicalName(String value) {
        String normalized = normalizeName(value);
        if (normalized.contains("chatgpt")) return "chatgpt";
        if (normalized.contains("claude")) return "claude";
        return normalized;
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String stripExe(String value) {
        return value.toLowerCase(Locale.ROOT).endsWith(".exe") ? value.substring(0, value.length() - 4) : value;
    }

    private record Query(String key, String view) {}
    private record PackageEntry(String family, String location) {}
    static record RegistryEntry(String name, String icon, String location) {}
    public record InstalledApplication(String name, String path) {}
}
