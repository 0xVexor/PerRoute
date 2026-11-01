package app.perroute.service;

import app.perroute.model.ProxyNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class IsolatedCore implements AutoCloseable {
    private static final int LOG_LINES = 40;
    private final Process process;
    private final Path config;
    private final int socksPort;
    private final ArrayDeque<String> lastLines = new ArrayDeque<>();
    private final Consumer<String> onLine;

    private IsolatedCore(Process process, Path config, int socksPort, Consumer<String> onLine) {
        this.process = process;
        this.config = config;
        this.socksPort = socksPort;
        this.onLine = onLine;
        Thread.ofVirtual().name("perroute-isolated-core-log").start(this::readOutput);
    }

    public static IsolatedCore forNode(ProxyNode node) throws IOException {
        int socksPort = RealTunnelProbe.reservePort();
        int apiPort = RealTunnelProbe.reservePort();
        return start(new XrayConfigGenerator().generateProbe(node, socksPort, apiPort), socksPort);
    }

    public static IsolatedCore start(String configJson, int socksPort) throws IOException {
        return start(configJson, socksPort, null);
    }

    public static IsolatedCore start(String configJson, int socksPort, Consumer<String> onLine) throws IOException {
        XrayRuntime runtime = XrayRuntime.current();
        Path executable = runtime.requireExecutable(false);
        Path config = AppDirectories.dataDirectory().resolve("probes").resolve("isolated-" + UUID.randomUUID() + ".json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, configJson, StandardCharsets.UTF_8);
        try {
            Process process = new ProcessBuilder(executable.toString(), "run", "-config", config.toString())
                    .directory(runtime.directory().toFile()).redirectErrorStream(true).start();
            return new IsolatedCore(process, config, socksPort, onLine);
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(config);
            throw error;
        }
    }

    public int socksPort() { return socksPort; }

    public boolean alive() { return process.isAlive(); }

    public long pid() { return process.pid(); }

    public List<String> lastLines() {
        synchronized (lastLines) { return List.copyOf(lastLines); }
    }

    private void readOutput() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (lastLines) {
                    if (lastLines.size() >= LOG_LINES) lastLines.removeFirst();
                    lastLines.addLast(line);
                }
                if (onLine != null) {
                    try { onLine.accept(line); } catch (RuntimeException ignored) {}
                }
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        try { Files.deleteIfExists(config); } catch (IOException ignored) {}
    }
}
