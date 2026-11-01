package app.perroute.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Unprivileged packaged-core custody. Native code watches the JVM and reaps its one child. */
final class MacCoreProcess {
    record Started(Process process, long corePid) { }
    private MacCoreProcess() { }

    static Started start(ProcessBuilder command, Path supervisor, XrayProcessManager.ProcessStarter starter)
            throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("macOS core startup cancelled");
        if (!Files.isRegularFile(supervisor) || Files.isSymbolicLink(supervisor) || !Files.isExecutable(supervisor))
            throw new IOException("The packaged macOS core supervisor is missing or invalid");
        Path executable = Path.of(command.command().getFirst()).toRealPath();
        var arguments = new ArrayList<String>();
        arguments.add(supervisor.toString()); arguments.addAll(command.command());
        command.command(arguments);
        Process process = starter.start(command);
        try {
            var handshake = new FutureTask<String>(() -> {
                StringBuilder line = new StringBuilder();
                for (int i=0;i<64;i++) {
                    int value = process.getInputStream().read();
                    if (value == '\n') return line.toString();
                    if (value < 32 || value > 126) throw new IOException("Invalid macOS core ownership handshake");
                    line.append((char)value);
                }
                throw new IOException("Oversized macOS core ownership handshake");
            });
            Thread.ofVirtual().name("macos-core-handshake").start(handshake);
            String line;
            try { line = handshake.get(5,TimeUnit.SECONDS); }
            catch (ExecutionException | TimeoutException error) { throw new IOException("The macOS core supervisor did not confirm its child",error); }
            if (!line.matches("PRCORE1 [1-9][0-9]{0,9}")) throw new IOException("Invalid macOS core ownership handshake");
            long pid = Long.parseLong(line.substring(8));
            if (pid > Integer.MAX_VALUE) throw new IOException("Invalid macOS core process identifier");
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while (process.isAlive() && System.nanoTime() < deadline) {
                ProcessHandle child = ProcessHandle.of(pid).orElse(null);
                if (child != null && child.parent().map(p -> p.pid() == process.pid()).orElse(false) &&
                        child.info().command().map(p -> Path.of(p).equals(executable)).orElse(false))
                    return new Started(process,pid);
                Thread.sleep(10);
            }
            throw new IOException("The macOS core supervisor did not start the expected executable");
        } catch (IOException | InterruptedException | RuntimeException error) {
            // SIGTERM lets the native owner terminate/reap its child before it exits.
            XrayProcessManager.terminateOwned(process);
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw error;
        }
    }
}
