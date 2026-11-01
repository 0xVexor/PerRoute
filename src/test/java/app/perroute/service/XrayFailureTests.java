package app.perroute.service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Owned JVM helpers exercise OS process failure/interrupt paths without substituting fake process handles. */
public final class XrayFailureTests {
    private static int assertions;
    private static XrayRuntime runtime;
    public static void main(String[] args) throws Exception {
        if (args[0].equals("--fixture")) {
            switch (args[1]) {
                case "success" -> { return; }
                case "reject" -> { System.out.print("fixture rejection"); System.exit(9); }
                case "flood" -> { System.out.print("x".repeat(100_000)); System.exit(9); }
                case "exit" -> { System.out.println("fixture immediate exit"); System.exit(7); }
                default -> Thread.sleep(60_000);
            }
            return;
        }
        runtime = XrayRuntime.forPlatform(System.getProperty("os.name"), System.getProperty("os.arch"), Path.of(args[0]));
        runtime.requireExecutable(false);
        Path root = Files.createTempDirectory("perroute-process-failures-");
        try {
            invalidPreservesConfig(root.resolve("reject"));
            immediateExit(root.resolve("exit"));
            interruptedValidation(root.resolve("cancel-validation"));
            interruptedStartup(root.resolve("cancel-start"));
            interruptedApi(root.resolve("cancel-api"));
            validationTimeout(root.resolve("timeout"));
            interruptedStop(root.resolve("stop"));
            System.out.println("Xray process failure checks passed (" + assertions + " assertions)");
        } finally {
            try (var paths = Files.walk(root)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
    private static final class Fixture implements XrayProcessManager.ProcessStarter, AutoCloseable {
        String validation = "success", run = "hang", api = "hang";
        final List<Process> children = new CopyOnWriteArrayList<>();
        final CountDownLatch validationStarted = new CountDownLatch(1), coreStarted = new CountDownLatch(1), apiStarted = new CountDownLatch(1);
        @Override public Process start(ProcessBuilder builder) throws IOException {
            boolean test = builder.command().contains("-test"), apiCall = builder.command().contains("api");
            String mode = test ? validation : apiCall ? api : run;
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), XrayFailureTests.class.getName(), "--fixture", mode).redirectErrorStream(true).start();
            children.add(child);
            (test ? validationStarted : apiCall ? apiStarted : coreStarted).countDown();
            return child;
        }
        @Override public void close() throws Exception {
            boolean leaked = children.stream().anyMatch(Process::isAlive);
            for (Process child : children) if (child.isAlive()) { child.destroyForcibly(); child.waitFor(2, TimeUnit.SECONDS); }
            check(!leaked, "Every owned fixture child was already stopped before fixture cleanup");
        }
    }
    private static void invalidPreservesConfig(Path data) throws Exception {
        Files.createDirectories(data); Files.writeString(data.resolve("current-config.json"), "previous");
        try (var fixture = new Fixture(); var manager = new XrayProcessManager(runtime, data, fixture)) {
            fixture.validation = "flood";
            try { manager.start("candidate"); throw new AssertionError("Rejected config accepted"); }
            catch (IOException expected) { check(expected.getMessage().length() < 34_000 && expected.getMessage().contains("truncated"), "Helper output is bounded and marked truncated"); }
            check(Files.readString(data.resolve("current-config.json")).equals("previous"), "Rejected start preserves current config");
            check(!manager.isRunning(), "Rejected validation does not launch core");
            cleanPending(data);
        }
    }
    private static void immediateExit(Path data) throws Exception {
        try (var fixture = new Fixture(); var manager = new XrayProcessManager(runtime, data, fixture)) {
            fixture.run = "exit";
            var states = new CopyOnWriteArrayList<Boolean>(); manager.addStateListener(states::add);
            try { manager.start("candidate"); throw new AssertionError("Immediate exit accepted"); }
            catch (IOException expected) { check(expected.getMessage().contains("fixture immediate exit"), "Startup error includes captured diagnostic"); }
            check(!manager.isRunning() && !states.contains(true), "Immediate exit never publishes running");
        }
    }
    private static void interruptedValidation(Path data) throws Exception {
        try (var fixture = new Fixture(); var manager = new XrayProcessManager(runtime, data, fixture)) {
            fixture.validation = "hang";
            Worker worker = new Worker(() -> manager.validate("candidate", false));
            worker.thread.start(); check(fixture.validationStarted.await(5, TimeUnit.SECONDS), "Validation child started");
            worker.thread.interrupt(); worker.checkCancelled();
            check(!manager.isRunning(), "Cancelled validation has no core"); cleanPending(data);
        }
    }
    private static void interruptedStartup(Path data) throws Exception {
        try (var fixture = new Fixture(); var manager = new XrayProcessManager(runtime, data, fixture)) {
            Worker worker = new Worker(() -> manager.start("candidate"));
            worker.thread.start(); check(fixture.coreStarted.await(5, TimeUnit.SECONDS), "Core child started");
            worker.thread.interrupt(); worker.checkCancelled();
            check(!manager.isRunning(), "Cancelled startup does not retain a live core");
        }
    }
    private static void interruptedApi(Path data) throws Exception {
        try (var fixture = new Fixture(); var manager = new XrayProcessManager(runtime, data, fixture)) {
            manager.start("original");
            Worker worker = new Worker(() -> manager.applyRuntimeChange("outbound", "routing", "new", "old", "new-config"));
            worker.thread.start(); check(fixture.apiStarted.await(5, TimeUnit.SECONDS), "API helper started");
            worker.thread.interrupt(); worker.checkCancelled();
            check(manager.isRunning(), "Cancelled API helper leaves owned core alive");
            check(!Files.exists(data.resolve("runtime-outbound.json")) && !Files.exists(data.resolve("runtime-routing.json")), "Cancelled API removes credential-bearing helper files");
            check(Files.readString(data.resolve("current-config.json")).equals("original"), "Cancelled API does not replace current config");
        }
    }
    private static void validationTimeout(Path data) throws Exception {
        try (var fixture = new Fixture(); var manager = new XrayProcessManager(runtime, data, fixture)) {
            fixture.validation = "hang";
            try { manager.validate("candidate", false); throw new AssertionError("Hanging validation accepted"); }
            catch (IOException expected) { check(expected.getMessage().contains("timed out"), "Validation timeout is explicit"); }
            cleanPending(data);
        }
    }
    private static void interruptedStop(Path data) throws Exception {
        try (var fixture = new Fixture(); var manager = new XrayProcessManager(runtime, data, fixture)) {
            manager.start("candidate");
            try {
                Thread.currentThread().interrupt(); manager.stop();
                check(Thread.currentThread().isInterrupted(), "Stop preserves interrupt status");
                check(!manager.isRunning() && fixture.children.stream().noneMatch(Process::isAlive), "Interrupted stop reaps owned children");
            } finally { Thread.interrupted(); }
        }
    }
    private static final class Worker {
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final AtomicBoolean interrupted = new AtomicBoolean();
        final Thread thread;
        Worker(Operation operation) { thread = Thread.ofPlatform().unstarted(() -> {
            try { operation.run(); } catch (Throwable failure) { error.set(failure); }
            interrupted.set(Thread.currentThread().isInterrupted());
        }); }
        void checkCancelled() throws Exception {
            thread.join(5_000); check(!thread.isAlive(), "Cancelled operation returns within five seconds");
            check(error.get() instanceof InterruptedException && interrupted.get(), "Cancellation propagates with interrupt status preserved");
        }
    }
    @FunctionalInterface interface Operation { void run() throws Exception; }
    private static void cleanPending(Path data) throws IOException {
        try (var files = Files.list(data)) { check(files.noneMatch(p -> p.getFileName().toString().startsWith("pending-config")), "Validation temporary configs are removed"); }
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
