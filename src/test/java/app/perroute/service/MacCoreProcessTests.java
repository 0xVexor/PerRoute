package app.perroute.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public final class MacCoreProcessTests {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Core custody check "+checks); }
    public static void main(String[] args) throws Exception {
        Path supervisor = Path.of(args[0]).toAbsolutePath();
        var started = MacCoreProcess.start(new ProcessBuilder("/bin/sleep","60").redirectErrorStream(true),supervisor,ProcessBuilder::start);
        Process process = started.process();
        try {
            check(process.isAlive()); check(started.corePid() != process.pid());
            check(ProcessHandle.of(started.corePid()).orElseThrow().parent().orElseThrow().pid() == process.pid());
            process.destroy();
            check(process.waitFor(5,TimeUnit.SECONDS));
            check(ProcessHandle.of(started.corePid()).map(p -> !p.isAlive()).orElse(true));
        } finally { if (process.isAlive()) { process.destroy(); process.waitFor(5,TimeUnit.SECONDS); } }
        try {
            MacCoreProcess.start(new ProcessBuilder("/bin/sleep","60"),supervisor.resolveSibling("missing"),ProcessBuilder::start);
            throw new AssertionError("Missing supervisor accepted");
        } catch (IOException expected) { check(true); }
        try {
            MacCoreProcess.start(new ProcessBuilder("/bin/sleep","60"),supervisor,
                ignored -> new ProcessBuilder("/usr/bin/printf","bad-handshake\\n").start());
            throw new AssertionError("Malformed handshake accepted");
        } catch (IOException expected) { check(true); }
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        Thread task = Thread.ofPlatform().start(() -> {
            Thread.currentThread().interrupt();
            try {
                MacCoreProcess.start(new ProcessBuilder("/bin/sleep","60"),supervisor,ProcessBuilder::start);
            } catch (InterruptedException expected) { interrupted.set(Thread.currentThread().isInterrupted()); }
            catch (IOException failure) { throw new RuntimeException(failure); }
        });
        task.join(8_000); check(!task.isAlive()); check(interrupted.get());
        var launched = new java.util.concurrent.CountDownLatch(1);
        var captured = new java.util.concurrent.atomic.AtomicReference<Process>();
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        Thread pending = Thread.ofPlatform().start(() -> {
            try {
                MacCoreProcess.start(new ProcessBuilder("/bin/sleep","60"),supervisor,ignored -> {
                    Process child = new ProcessBuilder("/bin/sleep","60").start();
                    captured.set(child); launched.countDown(); return child;
                });
            } catch (InterruptedException expected) { cancelled.set(Thread.currentThread().isInterrupted()); }
            catch (IOException failure) { throw new RuntimeException(failure); }
        });
        check(launched.await(5,TimeUnit.SECONDS)); pending.interrupt(); pending.join(8_000);
        check(!pending.isAlive()); check(cancelled.get()); check(!captured.get().isAlive());
        Path bundle = Files.createTempDirectory("perroute-core-bundle-").toRealPath();
        String previous = System.getProperty("compose.application.resources.dir");
        try {
            Path resources = bundle.resolve("Contents/app/resources"); Files.createDirectories(resources);
            Path installed = bundle.resolve("Contents/MacOS/PerRouteCoreSupervisor"); Files.createDirectories(installed.getParent());
            Files.copy(supervisor,installed); check(installed.toFile().setExecutable(true));
            System.setProperty("compose.application.resources.dir",resources.toString());
            var runtime = XrayRuntime.forPlatform(System.getProperty("os.name"),System.getProperty("os.arch"),Path.of(args[1]));
            try (var manager = new XrayProcessManager(runtime,bundle.resolve("data"))) {
                String config = "{\"log\":{\"loglevel\":\"none\"},\"outbounds\":[{\"protocol\":\"freedom\"}]}";
                manager.start(config);
                long core = manager.ownedProcessId();
                check(manager.isRunning());
                check(ProcessHandle.of(core).orElseThrow().info().command().orElseThrow().equals(runtime.executable().toRealPath().toString()));
                manager.stop(); check(!manager.isRunning());
                check(ProcessHandle.of(core).map(p -> !p.isAlive()).orElse(true));
                manager.start(config); check(manager.ownedProcessId() != core); manager.stop(); check(!manager.isRunning());
            }
        } finally {
            if (previous == null) System.clearProperty("compose.application.resources.dir");
            else System.setProperty("compose.application.resources.dir",previous);
            try (var paths = Files.walk(bundle)) { for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
        System.out.println("macOS core JVM custody: "+checks+" checks passed; disposable same-user processes only");
    }
}
