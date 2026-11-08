package app.perroute.service;

import app.perroute.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProxiFyreManager {
    public static final String SERVICE_NAME = "ProxiFyreService";
    /** The Windows Packet Filter kernel driver that ProxiFyre depends on. */
    public static final String DRIVER_SERVICE = "ndisrd";
    private static final String DRIVER_NAME = "Windows.Packet.Filter.3.6.2.1.x64.msi";
    private static final URI DRIVER_URI = URI.create("https://github.com/wiresock/ndisapi/releases/download/v3.6.2/" + DRIVER_NAME);
    private static final String EXPECTED_DRIVER_SHA256 = "9c388c0b7f189f7fa98720bae2caecf7d64f30910838b80b438ecf8956b8502c";
    public static final String VERSION = "2.6.2";
    private static final String SETUP_NAME = "ProxiFyre-" + VERSION + "-win-x64-setup.exe";
    private static final URI SETUP_URI = URI.create("https://github.com/wiresock/proxifyre/releases/download/v" + VERSION + "/" + SETUP_NAME);
    private static final URI CHECKSUM_URI = URI.create(SETUP_URI + ".sha256");
    private static final String EXPECTED_SETUP_SHA256 = "952ca0f40571194e7dfcd922a92bc3bef1d0759a90c602e8632a9af958c13b73";
    private static final Pattern SHA256 = Pattern.compile("(?i)\\b[0-9a-f]{64}\\b");
    private static final Pattern SERVICE_STATE = Pattern.compile("(?m)^\\s*STATE\\s*:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final int MAX_SETUP_BYTES = 64 * 1024 * 1024;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private volatile Process limitedProcess;

    public Inspection inspect() {
        Path executable = executablePath();
        return new Inspection(Files.isRegularFile(executable), serviceExists() && serviceExists(DRIVER_SERVICE), executable);
    }

    public String generateConfig(List<String> applicationPaths) {
        return generateConfig(applicationPaths, true);
    }

    /**
     * @param bypassLan when false, the selected apps' local-network traffic is also sent to the core, so it can be
     *                  blocked on networks that are not trusted; the core still sends trusted local traffic direct
     */
    public String generateConfig(List<String> applicationPaths, boolean bypassLan) {
        if (applicationPaths == null || applicationPaths.isEmpty()) {
            throw new IllegalArgumentException("At least one application must be selected");
        }
        List<Object> rules = new ArrayList<>();
        for (int index = 0; index < applicationPaths.size(); index++) {
            String value = applicationPaths.get(index);
            Path path = Path.of(value).toAbsolutePath().normalize();
            List<String> matches = new ArrayList<>();
            String normalized = path.toString();
            matches.add(normalized);
            if (normalized.toLowerCase(Locale.ROOT).contains("\\windowsapps\\") && path.getParent() != null) {
                String packageDirectory = path.getParent().toString() + "\\";
                matches.add(packageDirectory);
            }

            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("appNames", matches);
            rule.put("socks5ProxyEndpoint", "127.0.0.1:" + XrayConfigGenerator.perAppPort(index));
            rule.put("socks5Transport", "TCP");
            rule.put("supportedProtocols", List.of("TCP", "UDP"));
            rule.put("supportedAddressFamilies", List.of("IPv4", "IPv6"));
            rules.add(rule);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("logLevel", "Info");
        root.put("bypassLan", bypassLan);
        root.put("proxies", rules);
        root.put("excludes", List.of("xray.exe", "PerRoute.exe", "java.exe", "javaw.exe", "ProxiFyre.exe"));
        return Json.stringify(root) + System.lineSeparator();
    }

    public synchronized void applyAndStart(List<String> applicationPaths) throws IOException, InterruptedException {
        applyAndStart(applicationPaths, true);
    }

    public synchronized void applyAndStart(List<String> applicationPaths, boolean bypassLan) throws IOException, InterruptedException {
        Inspection inspection = inspect();
        if (!inspection.ready()) throw new IOException("ProxiFyre is not installed. Install the per-app engine first.");
        if (!AdminCheck.isElevated()) {
            startLimited(inspection, applicationPaths, bypassLan);
            return;
        }
        Path directory = inspection.executable().getParent();
        Path config = directory.resolve("app-config.json");
        Path backup = directory.resolve("app-config.perroute-backup.json");
        Path noConfigMarker = directory.resolve("app-config.perroute-was-missing");

        stopServiceQuietly();
        if (!Files.exists(backup) && !Files.exists(noConfigMarker)) {
            if (Files.isRegularFile(config)) Files.copy(config, backup, StandardCopyOption.COPY_ATTRIBUTES);
            else Files.writeString(noConfigMarker, "PerRoute\n", StandardCharsets.US_ASCII);
        }
        writeAtomically(config, generateConfig(applicationPaths, bypassLan));
        runScChecked("start");
        waitForState(4, Duration.ofSeconds(20));
    }

    public synchronized void stopAndRestore() throws IOException, InterruptedException {
        stopLimited();
        Inspection inspection = inspect();
        if (!inspection.installed()) return;
        if (!AdminCheck.isElevated()) return;
        stopServiceQuietly();
        Path directory = inspection.executable().getParent();
        Path config = directory.resolve("app-config.json");
        Path backup = directory.resolve("app-config.perroute-backup.json");
        Path noConfigMarker = directory.resolve("app-config.perroute-was-missing");
        if (Files.isRegularFile(backup)) {
            Files.move(backup, config, StandardCopyOption.REPLACE_EXISTING);
        } else if (Files.exists(noConfigMarker)) {
            Files.deleteIfExists(config);
        }
        Files.deleteIfExists(noConfigMarker);
    }

    public synchronized void stopManagedSidecar() throws IOException, InterruptedException {
        stopLimited();
    }

    /** "running", "stopped" or "not installed", read from the service manager. */
    public String serviceState() {
        try {
            Process process = new ProcessBuilder("sc.exe", "query", SERVICE_NAME).redirectErrorStream(true).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); return "stopped"; }
            if (process.exitValue() != 0) return "not installed";
            Matcher matcher = SERVICE_STATE.matcher(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            return matcher.find() && Integer.parseInt(matcher.group(1)) == 4 ? "running" : "stopped";
        } catch (Exception unknown) {
            return "stopped";
        }
    }

    /**
     * Whose app list the engine uses: "perroute" while PerRoute's list is in place (PerRoute keeps the previous
     * list, or a marker that there was none, exactly as long as its own list is installed), "other" when an app
     * list exists that PerRoute did not write, "none" otherwise.
     */
    public String configOwner() {
        Path directory = inspect().executable().getParent();
        if (directory == null) return "none";
        if (Files.exists(directory.resolve("app-config.perroute-backup.json")) || Files.exists(directory.resolve("app-config.perroute-was-missing"))) return "perroute";
        return Files.isRegularFile(directory.resolve("app-config.json")) ? "other" : "none";
    }

    /** Whether PerRoute's saved copy of the engine's previous settings is waiting to be put back. */
    public boolean backupPending() {
        Path directory = inspect().executable().getParent();
        return directory != null && Files.exists(directory.resolve("app-config.perroute-backup.json"));
    }

    /** How many apps the engine's current list redirects. */
    public int configuredApps() {
        Path directory = inspect().executable().getParent();
        try {
            Path config = directory == null ? null : directory.resolve("app-config.json");
            if (config == null || !Files.isRegularFile(config)) return 0;
            Object proxies = Json.object(Files.readString(config, StandardCharsets.UTF_8)).get("proxies");
            return proxies instanceof List<?> list ? list.size() : 0;
        } catch (IOException | RuntimeException unreadable) {
            return 0;
        }
    }

    /** Whether PerRoute's own copy of the engine (used without Administrator rights) is still running. */
    public boolean limitedRunning() {
        Path runtime = AppDirectories.dataDirectory().resolve("proxifyre-limited");
        Path pidFile = runtime.resolve("perroute.pid");
        try {
            if (!Files.isRegularFile(pidFile)) return false;
            long pid = Long.parseLong(Files.readString(pidFile, StandardCharsets.US_ASCII).strip());
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                    .map(handle -> handle.info().command().orElse("").equalsIgnoreCase(runtime.resolve("ProxiFyre.exe").toString()))
                    .orElse(false);
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    private void startLimited(Inspection inspection, List<String> applicationPaths, boolean bypassLan) throws IOException, InterruptedException {
        Path runtime = AppDirectories.dataDirectory().resolve("proxifyre-limited");
        Files.createDirectories(runtime);
        stopOrphanedLimitedProcess(runtime);
        stageLimitedRuntime(inspection.executable().getParent(), runtime);
        writeAtomically(runtime.resolve("app-config.json"), generateConfig(applicationPaths, bypassLan));

        Path executable = runtime.resolve("ProxiFyre.exe");
        Path consoleLog = runtime.resolve("perroute-console.log");
        Process started = new ProcessBuilder(executable.toString(), "--allow-not-admin")
                .directory(runtime.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(consoleLog.toFile()))
                .start();
        limitedProcess = started;
        Files.writeString(runtime.resolve("perroute.pid"), Long.toString(started.pid()), StandardCharsets.US_ASCII);
        Thread.sleep(1_500);
        if (!started.isAlive()) {
            limitedProcess = null;
            Files.deleteIfExists(runtime.resolve("perroute.pid"));
            String detail = tail(consoleLog, 3_000);
            throw new IOException("The per-app engine could not start without Administrator rights" +
                    (detail.isBlank() ? ". Reinstall PerRoute with the Full Installer, or start it once with Run as administrator." : ": " + detail));
        }
    }

    private void stageLimitedRuntime(Path source, Path target) throws IOException {
        try (var files = Files.list(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.equals("app-config.json") || name.endsWith(".bak") || name.contains("perroute")) continue;
                Files.copy(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        if (!Files.isRegularFile(target.resolve("ProxiFyre.exe"))) {
            throw new IOException("ProxiFyre runtime could not be prepared for non-administrator mode");
        }
    }

    private void stopLimited() throws IOException, InterruptedException {
        Process current = limitedProcess;
        limitedProcess = null;
        if (current != null && current.isAlive()) {
            current.destroy();
            if (!current.waitFor(5, TimeUnit.SECONDS)) current.destroyForcibly();
        }
        Path runtime = AppDirectories.dataDirectory().resolve("proxifyre-limited");
        stopOrphanedLimitedProcess(runtime);
    }

    private void stopOrphanedLimitedProcess(Path runtime) throws IOException, InterruptedException {
        Path pidFile = runtime.resolve("perroute.pid");
        if (!Files.isRegularFile(pidFile)) return;
        try {
            long pid = Long.parseLong(Files.readString(pidFile, StandardCharsets.US_ASCII).strip());
            ProcessHandle.of(pid).ifPresent(handle -> {
                String command = handle.info().command().orElse("");
                if (command.equalsIgnoreCase(runtime.resolve("ProxiFyre.exe").toString())) handle.destroy();
            });
            ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
            if (handle != null && handle.isAlive()) {
                try { handle.onExit().get(5, TimeUnit.SECONDS); }
                catch (java.util.concurrent.TimeoutException e) { handle.destroyForcibly(); }
                catch (java.util.concurrent.ExecutionException e) { throw new IOException("Could not stop ProxiFyre", e); }
            }
        } catch (NumberFormatException ignored) {
        } finally {
            Files.deleteIfExists(pidFile);
        }
    }

    private static String tail(Path file, int maxCharacters) {
        try {
            String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8).strip() : "";
            return text.length() <= maxCharacters ? text : text.substring(text.length() - maxCharacters);
        } catch (IOException ignored) {
            return "";
        }
    }

    public void downloadAndInstall(Consumer<String> progress) throws IOException, InterruptedException {
        Path downloads = AppDirectories.dataDirectory().resolve("downloads");
        Files.createDirectories(downloads);
        Path setup = downloads.resolve(SETUP_NAME);
        progress.accept("Downloading the official checksum of the per-app engine…");
        String checksumText = new String(download(CHECKSUM_URI, 64 * 1024), StandardCharsets.UTF_8);
        Matcher matcher = SHA256.matcher(checksumText);
        if (!matcher.find()) throw new IOException("Official ProxiFyre checksum is invalid");
        String sidecarChecksum = matcher.group().toLowerCase(Locale.ROOT);
        if (!MessageDigest.isEqual(EXPECTED_SETUP_SHA256.getBytes(StandardCharsets.US_ASCII),
                sidecarChecksum.getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("ProxiFyre sidecar does not match the pinned release checksum");
        }

        progress.accept("Downloading ProxiFyre " + VERSION + " from GitHub…");
        byte[] payload = download(SETUP_URI, MAX_SETUP_BYTES);
        String actual = sha256(payload);
        if (!MessageDigest.isEqual(EXPECTED_SETUP_SHA256.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("ProxiFyre installer checksum mismatch");
        }
        Files.write(setup, payload);
        progress.accept("Checksum verified. The installer opens with an Administrator prompt.");
        Process installer = new ProcessBuilder(setup.toString()).start();
        int exit = installer.waitFor();
        if (exit != 0 && exit != 1641 && exit != 3010) {
            throw new IOException("ProxiFyre setup exited with code " + exit);
        }
        if (!serviceExists(DRIVER_SERVICE)) installDriver(downloads, progress);
        if (!inspect().ready()) throw new IOException("Setup finished, but the ProxiFyre service was not found. Windows may need a restart.");
        progress.accept("The per-app engine is installed.");
    }

    /** Installs the packet filter driver when the engine's setup left it out; the file is checked against a pinned hash. */
    private void installDriver(Path downloads, Consumer<String> progress) throws IOException, InterruptedException {
        progress.accept("Downloading the Windows Packet Filter driver…");
        byte[] payload = download(DRIVER_URI, 4 * 1024 * 1024);
        if (!MessageDigest.isEqual(EXPECTED_DRIVER_SHA256.getBytes(StandardCharsets.US_ASCII),
                sha256(payload).getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("Windows Packet Filter installer checksum mismatch");
        }
        Path msi = downloads.resolve(DRIVER_NAME);
        Files.write(msi, payload);
        progress.accept("Installing the Windows Packet Filter driver…");
        int exit = new ProcessBuilder("msiexec.exe", "/i", msi.toString(), "/qn", "/norestart").start().waitFor();
        if (exit != 0 && exit != 1641 && exit != 3010 && exit != 1638) {
            throw new IOException("Windows Packet Filter setup exited with code " + exit);
        }
    }

    private byte[] download(URI uri, int limit) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(3))
                .header("User-Agent", "PerRoute/0.4").GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Download failed with HTTP " + response.statusCode());
        }
        if (response.body().length == 0 || response.body().length > limit) {
            throw new IOException("Downloaded file has an unexpected size");
        }
        return response.body();
    }

    private Path executablePath() {
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null && !programFiles.isBlank()) {
            Path normal = Path.of(programFiles, "ProxiFyre", "ProxiFyre.exe").toAbsolutePath().normalize();
            if (Files.isRegularFile(normal)) return normal;
        }
        return Path.of(programFiles == null ? "C:\\Program Files" : programFiles,
                "ProxiFyre", "ProxiFyre.exe").toAbsolutePath().normalize();
    }

    private boolean serviceExists() {
        return serviceExists(SERVICE_NAME);
    }

    private boolean serviceExists(String name) {
        try {
            Process process = new ProcessBuilder("sc.exe", "query", name).redirectErrorStream(true).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void stopServiceQuietly() throws IOException, InterruptedException {
        if (!serviceExists()) return;
        runSc("stop");
        waitForState(1, Duration.ofSeconds(20));
    }

    private void runScChecked(String action) throws IOException, InterruptedException {
        Result result = runSc(action);
        if (result.exitCode != 0) throw new IOException("Service " + action + " failed: " + result.output.strip());
    }

    private Result runSc(String action) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("sc.exe", action, SERVICE_NAME).redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Service " + action + " timed out");
        }
        return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private void waitForState(int desired, Duration timeout) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Process process = new ProcessBuilder("sc.exe", "query", SERVICE_NAME).redirectErrorStream(true).start();
            if (process.waitFor(5, TimeUnit.SECONDS)) {
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                Matcher matcher = SERVICE_STATE.matcher(output);
                if (matcher.find() && Integer.parseInt(matcher.group(1)) == desired) return;
            }
            Thread.sleep(250);
        }
        throw new IOException("ProxiFyre service did not reach state " + desired);
    }

    private static void writeAtomically(Path target, String text) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".perroute.tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(byte[] payload) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IOException("SHA-256 is unavailable", e); }
    }

    public record Inspection(boolean installed, boolean serviceInstalled, Path executable) {
        public boolean ready() { return installed && serviceInstalled; }
    }
    private record Result(int exitCode, String output) {}
}
