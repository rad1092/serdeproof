package io.github.rad1092.serdeproof.runtime;

import io.github.rad1092.serdeproof.api.AdapterMain;
import io.github.rad1092.serdeproof.api.AdapterResult;
import io.github.rad1092.serdeproof.api.RejectedInputException;
import io.github.rad1092.serdeproof.api.SerializerAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class JvmAdapterTest {
    private static final byte[] JSON = "{\"secret\":\"한글 🌍\"}".getBytes(StandardCharsets.UTF_8);
    private static final JvmAdapter.Limits NORMAL = new JvmAdapter.Limits(64 * 1024, 10_000);
    @TempDir Path temporary;

    private static JvmAdapter.Spec spec(Class<?> adapter) throws Exception {
        return new JvmAdapter.Spec(adapter.getName(), List.of(location(AdapterMain.class), location(JvmAdapterTest.class)),
                null, List.of());
    }

    private static Path location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static JvmAdapter.Outcome invoke(JvmAdapter runner, Class<?> adapter) throws Exception {
        return runner.invoke(spec(adapter), "Order", JSON, new byte[0], NORMAL);
    }

    @Test void realChildEchoesJsonAndRedirectsApplicationStdout() throws Exception {
        try (JvmAdapter runner = new JvmAdapter()) {
            JvmAdapter.Outcome result = invoke(runner, Echo.class);
            assertEquals("ACCEPTED", result.status());
            assertArrayEquals(JSON, result.output());
            assertArrayEquals(JSON, result.observation());
            result.output()[0] = 0;
            assertArrayEquals(JSON, result.output());
        }
    }

    @Test void rejectionAndCrashesNeverReturnPrivateExceptionText() throws Exception {
        try (JvmAdapter runner = new JvmAdapter()) {
            for (Class<?> type : List.of(Rejected.class, Throws.class, Crash.class)) {
                JvmAdapter.Outcome result = invoke(runner, type);
                assertEquals(type == Rejected.class ? "REJECTED" : "CRASH", result.status());
                assertEquals(0, result.output().length);
                assertEquals(0, result.observation().length);
            }
        }
    }

    @Test void malformedAndEmptyProtocolAreInfrastructureFailures() throws Exception {
        try (JvmAdapter runner = new JvmAdapter()) {
            assertEquals("MALFORMED_OUTPUT", invoke(runner, Malformed.class).status());
            assertEquals("MALFORMED_OUTPUT", invoke(runner, Empty.class).status());
        }
    }

    @Test void fieldStdoutAndStderrLimitsAreEnforced() throws Exception {
        try (JvmAdapter runner = new JvmAdapter()) {
            for (Class<?> type : List.of(HugeResult.class, StdoutFlood.class, StderrFlood.class)) {
                assertEquals("OUTPUT_LIMIT", runner.invoke(spec(type), "Order", JSON, new byte[0],
                        new JvmAdapter.Limits(1024, 10_000)).status(), type.getSimpleName());
            }
            assertEquals("INPUT_LIMIT", runner.invoke(spec(Echo.class), "Order", new byte[1025], new byte[0],
                    new JvmAdapter.Limits(1024, 10_000)).status());
            assertEquals("INPUT_LIMIT", runner.invoke(spec(Echo.class), "Order", new byte[0], new byte[1025],
                    new JvmAdapter.Limits(1024, 10_000)).status());
        }
    }

    @Test void timeoutIncludesBlockedRequestWritesAndConstructorStartup() throws Exception {
        try (JvmAdapter runner = new JvmAdapter()) {
            assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
                JvmAdapter.Outcome result = runner.invoke(spec(BlockedConstructor.class), "Order", new byte[1024 * 1024],
                        new byte[0], new JvmAdapter.Limits(1024 * 1024, 750));
                assertEquals("TIMEOUT", result.status());
            });
        }
    }

    @Test void timeoutKillsObservedDescendantProcesses() throws Exception {
        Path childPid = temporary.resolve("descendant.pid");
        try (JvmAdapter runner = new JvmAdapter()) {
            JvmAdapter.Spec childSpec = spec(Spawns.class);
            var executor = Executors.newSingleThreadExecutor();
            try {
                var future = executor.submit(() -> runner.invoke(childSpec, "Order", JSON,
                        childPid.toString().getBytes(StandardCharsets.UTF_8), new JvmAdapter.Limits(64 * 1024, 5_000)));
                long pid = awaitPid(childPid);
                assertEquals("TIMEOUT", future.get(12, TimeUnit.SECONDS).status());
                awaitDead(pid);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test void cancellationKillsAdapterAndDescendant() throws Exception {
        Path pids = temporary.resolve("cancel.pid");
        try (JvmAdapter runner = new JvmAdapter()) {
            AtomicReference<Throwable> result = new AtomicReference<>();
            JvmAdapter.Spec childSpec = spec(Spawns.class);
            Thread worker = new Thread(() -> {
                try {
                    runner.invoke(childSpec, "Order", JSON, pids.toString().getBytes(StandardCharsets.UTF_8),
                            new JvmAdapter.Limits(64 * 1024, 60_000));
                    result.set(new AssertionError("Expected interruption"));
                } catch (Throwable exception) { result.set(exception); }
            });
            worker.start();
            long childPid = awaitPid(pids);
            long adapterPid = Long.parseLong(Files.readString(pids.resolveSibling(pids.getFileName() + ".parent")));
            // Let supervision observe the descendant before requesting cancellation.
            Thread.sleep(50);
            worker.interrupt();
            worker.join(5_000);
            assertFalse(worker.isAlive(), "invoke must return after interruption");
            assertInstanceOf(InterruptedException.class, result.get());
            awaitDead(childPid);
            awaitDead(adapterPid);
        }
    }

    @Test void closeTerminatesActiveInvocation() throws Exception {
        Path pidFile = temporary.resolve("close.pid");
        JvmAdapter runner = new JvmAdapter();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> runner.invoke(spec(Spawns.class), "Order", JSON,
                    pidFile.toString().getBytes(StandardCharsets.UTF_8), new JvmAdapter.Limits(64 * 1024, 60_000)));
            long pid = awaitPid(pidFile);
            Thread.sleep(50);
            runner.close();
            assertEquals("CRASH", future.get(5, TimeUnit.SECONDS).status());
            awaitDead(pid);
            assertThrows(IllegalStateException.class, () -> invoke(runner, Echo.class));
        } finally {
            runner.close();
            executor.shutdownNow();
        }
    }

    @Test void supervisingJvmShutdownHookTerminatesAdapterAndDescendant() throws Exception {
        Path pidFile = temporary.resolve("shutdown.pid");
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        String classpath = String.join(java.io.File.pathSeparator,
                List.of(location(AdapterMain.class), location(JvmAdapter.class), location(JvmAdapterTest.class))
                        .stream().map(Path::toString).toList());
        Process supervisor = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", classpath, ShutdownSupervisor.class.getName(), pidFile.toString()).start();
        try {
            long childPid = awaitPid(pidFile);
            long adapterPid = Long.parseLong(Files.readString(pidFile.resolveSibling(pidFile.getFileName() + ".parent")));
            Thread.sleep(50);
            supervisor.getOutputStream().write(1);
            supervisor.getOutputStream().flush();
            assertTrue(supervisor.waitFor(8, TimeUnit.SECONDS), "Supervisor shutdown must finish");
            assertEquals(0, supervisor.exitValue());
            awaitDead(childPid);
            awaitDead(adapterPid);
        } finally {
            supervisor.destroyForcibly();
            supervisor.waitFor(3, TimeUnit.SECONDS);
        }
    }

    @Test void unicodeAndSpacesClasspathWorksWithoutShellParsing() throws Exception {
        Path jar = temporary.resolve("사용자 DTO spaces 🌍.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Path source : List.of(location(AdapterMain.class), location(JvmAdapterTest.class))) {
                copyClasses(source, output);
            }
        }
        try (JarFile fixture = new JarFile(jar.toFile())) {
            assertNotNull(fixture.getJarEntry(AdapterMain.class.getName().replace('.', '/') + ".class"),
                    "Unicode fixture must contain the bootstrap during both test and verify lifecycles");
            assertNotNull(fixture.getJarEntry(Echo.class.getName().replace('.', '/') + ".class"));
        }
        try (JvmAdapter runner = new JvmAdapter()) {
            var result = runner.invoke(new JvmAdapter.Spec(Echo.class.getName(), List.of(jar), null,
                    List.of("-Dserdeproof.test=spaces 한글")), "유형", JSON, new byte[0], NORMAL);
            assertEquals("ACCEPTED", result.status());
            assertArrayEquals(JSON, result.output());
        }
    }

    private static void copyClasses(Path source, JarOutputStream output) throws Exception {
        if (Files.isDirectory(source)) {
            try (var files = Files.walk(source)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                    output.putNextEntry(new JarEntry(source.relativize(file).toString().replace('\\', '/')));
                    Files.copy(file, output);
                    output.closeEntry();
                }
            }
        } else {
            // Maven reactor dependencies become packaged jars before downstream verify/install tests.
            try (JarFile archive = new JarFile(source.toFile())) {
                for (JarEntry entry : archive.stream().filter(item -> item.getName().endsWith(".class")).toList()) {
                    output.putNextEntry(new JarEntry(entry.getName()));
                    try (var input = archive.getInputStream(entry)) { input.transferTo(output); }
                    output.closeEntry();
                }
            }
        }
    }

    @Test void unavailableJavaAndUnsafeClasspathHavePredictableOutcomes() throws Exception {
        try (JvmAdapter runner = new JvmAdapter()) {
            assertEquals("START_FAILED", runner.invoke(new JvmAdapter.Spec(Echo.class.getName(),
                            spec(Echo.class).classpath(), temporary.resolve("java-does-not-exist"), List.of()),
                    "Order", JSON, new byte[0], NORMAL).status());
        }
        assertThrows(IllegalArgumentException.class,
                () -> new JvmAdapter.Spec("Example", List.of(Path.of("lib", "*")), null, List.of()));
    }

    private static long awaitPid(Path file) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < end) {
            if (Files.exists(file)) {
                String text = Files.readString(file).strip();
                if (!text.isEmpty()) return Long.parseLong(text);
            }
            Thread.sleep(10);
        }
        fail("Child did not report startup");
        return -1;
    }

    private static void awaitDead(long pid) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "Process remained alive: " + pid);
    }

    public static class Echo implements SerializerAdapter {
        static { System.out.println("static initializer log"); }
        public AdapterResult evaluate(String type, byte[] input, byte[] config) {
            System.out.println("private application log");
            return new AdapterResult(input, input);
        }
    }
    public static class Rejected implements SerializerAdapter {
        public AdapterResult evaluate(String type, byte[] input, byte[] config) throws Exception {
            throw new RejectedInputException("SECRET fixture data");
        }
    }
    public static class Throws implements SerializerAdapter {
        public AdapterResult evaluate(String type, byte[] input, byte[] config) {
            throw new IllegalStateException("SECRET fixture data");
        }
    }
    public static class Crash implements SerializerAdapter {
        public AdapterResult evaluate(String type, byte[] input, byte[] config) {
            Runtime.getRuntime().halt(7);
            return null;
        }
    }
    public static class Empty implements SerializerAdapter {
        public AdapterResult evaluate(String type, byte[] input, byte[] config) {
            Runtime.getRuntime().halt(0);
            return null;
        }
    }
    public static class Malformed implements SerializerAdapter {
        public AdapterResult evaluate(String type, byte[] input, byte[] config) throws Exception {
            new FileOutputStream(FileDescriptor.out).write("unframed bytes".getBytes(StandardCharsets.UTF_8));
            return new AdapterResult(input, input);
        }
    }
    public static class HugeResult implements SerializerAdapter {
        public AdapterResult evaluate(String type, byte[] input, byte[] config) {
            return new AdapterResult(new byte[2048], input);
        }
    }
    public static class StdoutFlood implements SerializerAdapter {
        public AdapterResult evaluate(String type, byte[] input, byte[] config) throws Exception {
            var stream = new FileOutputStream(FileDescriptor.out);
            while (true) stream.write(new byte[8192]);
        }
    }
    public static class StderrFlood implements SerializerAdapter {
        public AdapterResult evaluate(String type, byte[] input, byte[] config) {
            while (true) System.err.write(new byte[8192], 0, 8192);
        }
    }
    public static class BlockedConstructor implements SerializerAdapter {
        public BlockedConstructor() throws InterruptedException { Thread.sleep(60_000); }
        public AdapterResult evaluate(String type, byte[] input, byte[] config) { return new AdapterResult(input, input); }
    }
    public static class Spawns implements SerializerAdapter {
        public AdapterResult evaluate(String type, byte[] input, byte[] config) throws Exception {
            String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-cp", System.getProperty("java.class.path"), SleepingChild.class.getName()).start();
            Path pid = Path.of(new String(config, StandardCharsets.UTF_8));
            Files.writeString(pid.resolveSibling(pid.getFileName() + ".parent"), Long.toString(ProcessHandle.current().pid()));
            Files.writeString(pid, Long.toString(child.pid()));
            Thread.sleep(60_000);
            return new AdapterResult(input, input);
        }
    }
    public static class SleepingChild {
        public static void main(String[] args) throws Exception { Thread.sleep(60_000); }
    }
    public static class ShutdownSupervisor {
        public static void main(String[] args) throws Exception {
            JvmAdapter runner = new JvmAdapter();
            Thread invocation = new Thread(() -> {
                try {
                    runner.invoke(spec(Spawns.class), "Order", JSON, args[0].getBytes(StandardCharsets.UTF_8),
                            new JvmAdapter.Limits(64 * 1024, 60_000));
                } catch (Exception ignored) { }
            });
            invocation.start();
            System.in.read();
            // Exercise portable JVM shutdown hooks; TerminateProcess on Windows cannot run hooks.
            System.exit(0);
        }
    }
}
