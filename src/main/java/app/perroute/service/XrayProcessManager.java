package app.perroute.service;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class XrayProcessManager implements AutoCloseable {
    private final List<Consumer<String>> logListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Boolean>> stateListeners = new CopyOnWriteArrayList<>();
    private final XrayRuntime runtime;
    private final Path dataDirectory;
    private volatile Process process;
    private long corePid;
    private final ProcessStarter starter;
    @FunctionalInterface interface ProcessStarter { Process start(ProcessBuilder builder) throws IOException; }

    public XrayProcessManager() {
        this(XrayRuntime.current(), AppDirectories.dataDirectory());
    }

    public XrayProcessManager(XrayRuntime runtime, Path dataDirectory) {
        this(runtime, dataDirectory, ProcessBuilder::start);
    }

    XrayProcessManager(XrayRuntime runtime, Path dataDirectory, ProcessStarter starter) {
        this.starter = java.util.Objects.requireNonNull(starter, "starter");
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
        this.dataDirectory = java.util.Objects.requireNonNull(dataDirectory, "dataDirectory").toAbsolutePath().normalize();
    }

    public void addLogListener(Consumer<String> listener) { logListeners.add(listener); }
    public void addStateListener(Consumer<Boolean> listener) { stateListeners.add(listener); }
    public boolean isRunning() { Process current = process; return current != null && current.isAlive(); }
    /** Snapshot only; the native helper independently verifies this process and its endpoints. */
    public synchronized long ownedProcessId() throws IOException {
        Process current = process;
        if (current == null || !current.isAlive()) throw new IOException("The owned Xray process is not running");
        return corePid;
    }

    public synchronized void start(String config) throws IOException, InterruptedException {
        start(config, false);
    }

    public synchronized void start(String config, boolean requiresWintun) throws IOException, InterruptedException {
        if (isRunning()) return;
        Path coreDir = runtime.directory();
        Path executable = runtime.requireExecutable(requiresWintun);
        Path data = dataDirectory;
        Files.createDirectories(data);
        validate(config, requiresWintun);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Xray startup cancelled");
        Path configPath = data.resolve("current-config.json");
        Files.writeString(configPath, config, StandardCharsets.UTF_8);

        ProcessBuilder builder = new ProcessBuilder(executable.toString(), "run", "-config", configPath.toString());
        builder.directory(coreDir.toFile());
        builder.redirectErrorStream(true);
        String resources = System.getProperty("compose.application.resources.dir");
        boolean packagedMac = runtime.target() != XrayRuntime.Target.WINDOWS_X64 && resources != null;
        MacCoreProcess.Started owned = packagedMac
                ? MacCoreProcess.start(builder, Path.of(resources).resolve("../../MacOS/PerRouteCoreSupervisor").normalize(), starter)
                : null;
        Process started = owned == null ? starter.start(builder) : owned.process();
        process = started;
        corePid = owned == null ? started.pid() : owned.corePid();
        StartupOutput startup = new StartupOutput();
        Thread drain = Thread.ofPlatform().daemon().name("xray-output").start(() -> readOutput(started, startup));
        try {
            // Preserve Windows startup timing. Darwin must survive an early-exit window before publishing running.
            if (runtime.target() != XrayRuntime.Target.WINDOWS_X64 && started.waitFor(300, TimeUnit.MILLISECONDS)) {
                drain.join(1_000);
                throw new IOException("Xray stopped during startup (exit " + started.exitValue() + "): " + startup.finish());
            }
        } catch (IOException | InterruptedException error) {
            if (terminateOwned(started)) process = null;
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw error;
        } finally { startup.finish(); }
        fireState(true);
        started.onExit().thenRun(() -> {
            boolean wasCurrent;
            synchronized (this) {
                wasCurrent = process == started;
                if (wasCurrent) process = null;
            }
            if (wasCurrent) fireState(false);
        });
    }

    public void validate(String config, boolean requiresWintun) throws IOException, InterruptedException {
        Path coreDir = runtime.directory();
        Path executable = runtime.requireExecutable(requiresWintun);
        Path data = dataDirectory;
        Files.createDirectories(data);
        Path pending = Files.createTempFile(data, "pending-config-", ".json");
        try {
            Files.writeString(pending, config, StandardCharsets.UTF_8);
            validate(executable, coreDir, pending);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private void validate(Path executable, Path coreDir, Path config) throws IOException, InterruptedException {
        String output = command(new ProcessBuilder(executable.toString(), "run", "-test", "-config", config.toString())
                .directory(coreDir.toFile()).redirectErrorStream(true), 15, "config validation");
        if (!output.isBlank()) fireLog(output);
    }

    /** Every helper is owned, bounded and reaped on timeout or interruption, before its config is removed. */
    private String command(ProcessBuilder builder, int timeoutSeconds, String operation) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Xray " + operation + " cancelled");
        Process child = starter.start(builder);
        BoundedOutput captured = new BoundedOutput();
        Thread drain = Thread.ofPlatform().daemon().name("xray-command-output").start(() -> {
            try (var input = child.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) >= 0) captured.append(buffer, count);
            } catch (IOException ignored) { }
        });
        try {
            if (!child.waitFor(timeoutSeconds, TimeUnit.SECONDS)) throw new IOException("Xray " + operation + " timed out");
            drain.join(1_000);
            String text = captured.text();
            if (child.exitValue() != 0) throw new IOException("Xray rejected " + operation + " (exit " + child.exitValue() + "): " + text);
            return text;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        } finally {
            terminateOwned(child);
        }
    }

    private static final class BoundedOutput {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean truncated;
        synchronized void append(byte[] buffer, int count) {
            int retained = Math.min(count, 32768 - bytes.size());
            bytes.write(buffer, 0, retained);
            truncated |= retained < count;
        }
        synchronized String text() { return bytes.toString(StandardCharsets.UTF_8).strip() + (truncated ? " [output truncated]" : ""); }
    }
    private static final class StartupOutput {
        private final StringBuilder text = new StringBuilder();
        private boolean active = true;
        synchronized void append(String line) {
            if (active && text.length() < 8192) text.append(line, 0, Math.min(line.length(), 8192 - text.length())).append('\n');
        }
        synchronized String finish() { active = false; return text.toString().strip(); }
    }

    private void readOutput(Process source, StartupOutput startup) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(source.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) { startup.append(line); fireLog(line); }
        } catch (IOException e) {
            if (source.isAlive()) fireLog("Log reader error: " + e.getMessage());
        }
    }

    public synchronized void stop() {
        Process current = process;
        if (current == null) return;
        if (terminateOwned(current)) {
            process = null;
            fireState(false);
        } else fireLog("The owned Xray process did not exit after termination; it remains tracked.");
    }

    static boolean terminateOwned(Process child) {
        boolean interrupted = Thread.interrupted();
        try {
            if (child.isAlive()) {
                child.destroy();
                try {
                    if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly();
                } catch (InterruptedException error) { interrupted = true; child.destroyForcibly(); }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (child.isAlive() && System.nanoTime() < deadline) {
                    try { child.waitFor(100, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException error) { interrupted = true; child.destroyForcibly(); }
                }
            }
            return !child.isAlive();
        } finally {
            if (!child.isAlive()) {
                try { child.getInputStream().close(); } catch (IOException ignored) { }
                try { child.getErrorStream().close(); } catch (IOException ignored) { }
                try { child.getOutputStream().close(); } catch (IOException ignored) { }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public synchronized void applyRuntimeChange(String outbounds, String routing, String newTag, String oldTag, String config)
            throws IOException, InterruptedException {
        if (!isRunning()) throw new IOException("The connection core is not running");
        Path data = dataDirectory;
        Files.createDirectories(data);
        Path outboundFile = data.resolve("runtime-outbound.json");
        Path routingFile = data.resolve("runtime-routing.json");
        try {
            Files.writeString(outboundFile, outbounds, StandardCharsets.UTF_8);
            Files.writeString(routingFile, routing, StandardCharsets.UTF_8);
            api("ado", outboundFile.toString());
            try {
                api("adrules", routingFile.toString());
            } catch (IOException swapFailed) {
                try { api("rmo", newTag); } catch (IOException ignored) { }
                throw swapFailed;
            }
            try { api("rmo", oldTag); }
            catch (IOException stale) { fireLog("The previous outbound " + oldTag + " could not be removed and stays idle: " + stale.getMessage()); }
            Files.writeString(data.resolve("current-config.json"), config, StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(outboundFile);
            Files.deleteIfExists(routingFile);
        }
    }

    public synchronized void applyRuntimePlan(XrayConfigGenerator.RuntimePlan plan) throws IOException, InterruptedException {
        if (!isRunning()) throw new IOException("The connection core is not running");
        Path data = AppDirectories.dataDirectory();
        Files.createDirectories(data);
        Path inboundFile = data.resolve("runtime-inbounds.json");
        Path outboundFile = data.resolve("runtime-outbounds.json");
        Path routingFile = data.resolve("runtime-routing.json");
        java.util.List<String> addedInbounds = new java.util.ArrayList<>();
        java.util.List<String> addedOutbounds = new java.util.ArrayList<>();
        try {
            if (plan.inbounds() != null) {
                Files.writeString(inboundFile, plan.inbounds(), StandardCharsets.UTF_8);
                api("adi", inboundFile.toString());
                addedInbounds.addAll(plan.addedInboundTags());
            }
            if (plan.outbounds() != null) {
                Files.writeString(outboundFile, plan.outbounds(), StandardCharsets.UTF_8);
                api("ado", outboundFile.toString());
                addedOutbounds.addAll(plan.addedOutboundTags());
            }
            Files.writeString(routingFile, plan.routing(), StandardCharsets.UTF_8);
            api("adrules", routingFile.toString());
        } catch (IOException failed) {
            for (String tag : addedOutbounds) { try { api("rmo", tag); } catch (IOException ignored) { } }
            for (String tag : addedInbounds) { try { api("rmi", tag); } catch (IOException ignored) { } }
            throw failed;
        } finally {
            Files.deleteIfExists(inboundFile);
            Files.deleteIfExists(outboundFile);
            Files.deleteIfExists(routingFile);
        }
        for (String tag : plan.removeOutbounds()) {
            try { api("rmo", tag); } catch (IOException stale) { fireLog("The unused outbound " + tag + " could not be removed and stays idle: " + stale.getMessage()); }
        }
        for (String tag : plan.removeInbounds()) {
            try { api("rmi", tag); } catch (IOException stale) { fireLog("The unused inbound " + tag + " could not be removed and stays idle: " + stale.getMessage()); }
        }
        Files.writeString(data.resolve("current-config.json"), plan.config(), StandardCharsets.UTF_8);
    }

    private String api(String command, String argument) throws IOException, InterruptedException {
        Path coreDir = runtime.directory();
        return command(new ProcessBuilder(runtime.requireExecutable(false).toString(), "api", command,
                "--server=127.0.0.1:" + XrayConfigGenerator.STATS_API_PORT, argument)
                .directory(coreDir.toFile()).redirectErrorStream(true), 8, command);
    }

    public int orphanCount() {
        String executable = AppDirectories.coreDirectory().resolve("xray.exe").toString();
        java.util.Set<Long> own = ProcessHandle.current().descendants()
                .map(ProcessHandle::pid).collect(java.util.stream.Collectors.toSet());
        return (int) ProcessHandle.allProcesses()
                .filter(handle -> !own.contains(handle.pid()))
                .filter(handle -> handle.info().command().map(executable::equalsIgnoreCase).orElse(false))
                .count();
    }

    public void stopOrphan() {
        if (runtime.target() != XrayRuntime.Target.WINDOWS_X64) {
            throw new UnsupportedOperationException("macOS orphan recovery is not implemented");
        }
        String executable = runtime.executable().toString();
        java.util.Set<Long> own = ProcessHandle.current().descendants()
                .map(ProcessHandle::pid).collect(java.util.stream.Collectors.toSet());
        ProcessHandle.allProcesses()
                .filter(handle -> !own.contains(handle.pid()))
                .filter(handle -> handle.info().command().map(executable::equalsIgnoreCase).orElse(false))
                .forEach(handle -> {
                    handle.destroyForcibly();
                    fireLog("Stopped an Xray process left over from a previous session.");
                });
    }

    private void fireLog(String line) { logListeners.forEach(listener -> listener.accept(line)); }
    private void fireState(boolean running) { stateListeners.forEach(listener -> listener.accept(running)); }
    @Override public void close() { stop(); }
}
