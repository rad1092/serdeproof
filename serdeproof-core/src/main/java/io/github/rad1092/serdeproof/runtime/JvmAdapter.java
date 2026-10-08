package io.github.rad1092.serdeproof.runtime;

import io.github.rad1092.serdeproof.api.AdapterMain;
import io.github.rad1092.serdeproof.api.WireProtocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Supervises trusted local adapters in independent JVMs. Bounds transport and elapsed time, but is not
 * a sandbox or a memory/CPU quota. Descendant cleanup is best effort: hostile processes can detach.
 * Reuse an instance in try-with-resources. Invocations may run concurrently.
 */
public final class JvmAdapter implements AutoCloseable {
    public record Spec(String adapterClass, List<Path> classpath, Path javaExecutable, List<String> jvmArgs) {
        public Spec {
            Objects.requireNonNull(adapterClass, "adapterClass");
            if (adapterClass.isBlank()) throw new IllegalArgumentException("adapterClass is required");
            classpath = List.copyOf(Objects.requireNonNull(classpath, "classpath"));
            if (classpath.isEmpty()) throw new IllegalArgumentException("classpath is required");
            for (Path entry : classpath) {
                if (entry.toString().contains("*") || entry.toString().contains(File.pathSeparator)) {
                    throw new IllegalArgumentException("Classpath entries cannot contain wildcards or path separators");
                }
            }
            jvmArgs = jvmArgs == null ? List.of() : List.copyOf(jvmArgs);
            if (javaExecutable == null) {
                String name = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
                javaExecutable = Path.of(System.getProperty("java.home"), "bin", name);
            }
        }
    }

    /** Individual field limit (including stderr) and whole invocation deadline, in milliseconds. */
    public record Limits(int maxBytes, long timeoutMillis) {
        public Limits {
            WireProtocol.checkLimit(maxBytes);
            if (timeoutMillis < 1 || timeoutMillis > TimeUnit.DAYS.toMillis(1)) {
                throw new IllegalArgumentException("timeoutMillis must be between 1 and 86400000");
            }
        }
    }

    /** Failure outcomes always contain empty byte arrays and no untrusted error text. */
    public record Outcome(String status, byte[] output, byte[] observation) {
        public Outcome {
            Objects.requireNonNull(status, "status");
            output = Objects.requireNonNull(output, "output").clone();
            observation = Objects.requireNonNull(observation, "observation").clone();
        }
        @Override public byte[] output() { return output.clone(); }
        @Override public byte[] observation() { return observation.clone(); }
    }

    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();
    private final Object lifecycle = new Object();
    private final Set<ActiveProcess> active = ConcurrentHashMap.newKeySet();
    private final ExecutorService io = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "serdeproof-io-" + THREAD_NUMBER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private final Thread shutdownHook;
    private volatile boolean closed;

    public JvmAdapter() {
        shutdownHook = new Thread(this::stopActive, "serdeproof-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public Outcome invoke(Spec spec, String type, byte[] input, byte[] configuration, Limits limits)
            throws InterruptedException {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(limits, "limits");
        if (Thread.interrupted()) throw new InterruptedException();
        if (closed) throw new IllegalStateException("JvmAdapter is closed");
        if (input.length > limits.maxBytes() || configuration.length > limits.maxBytes()
                || type.getBytes(StandardCharsets.UTF_8).length > limits.maxBytes()) {
            return failure("INPUT_LIMIT");
        }
        // Snapshot mutable caller data before asynchronous writes.
        byte[] inputCopy = input.clone();
        byte[] configurationCopy = configuration.clone();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.timeoutMillis());
        ActiveProcess child;
        synchronized (lifecycle) {
            if (closed) throw new IllegalStateException("JvmAdapter is closed");
            Path launcher = null;
            try {
                launcher = createLauncher(spec);
                child = new ActiveProcess(new ProcessBuilder(command(spec, limits, launcher)).start(), launcher);
                active.add(child);
            } catch (IOException | SecurityException startupFailure) {
                deleteLauncher(launcher);
                return failure("START_FAILED");
            }
        }
        Future<?> writer = null;
        Future<byte[]> stdout = null;
        Future<byte[]> stderr = null;
        try {
            // Include executor submission in lifecycle synchronization so close cannot race with submit.
            synchronized (lifecycle) {
                if (closed) return failure("CRASH");
                writer = io.submit(() -> {
                    try (OutputStream stream = child.process.getOutputStream()) {
                        WireProtocol.writeRequest(stream, type, inputCopy, configurationCopy, limits.maxBytes());
                    }
                    return null;
                });
                stdout = io.submit(() -> collect(child.process.getInputStream(),
                        WireProtocol.maxResponseBytes(limits.maxBytes()), true, child.exceeded));
                stderr = io.submit(() -> collect(child.process.getErrorStream(),
                        limits.maxBytes(), false, child.exceeded));
            }
            while (true) {
                if (Thread.interrupted()) throw new InterruptedException();
                child.captureDescendants();
                if (child.exceeded.get()) return failure("OUTPUT_LIMIT");
                if (closed) return failure("CRASH");
                if (System.nanoTime() - deadline >= 0) return failure("TIMEOUT");
                if (!child.process.isAlive() && stdout.isDone() && stderr.isDone() && writer.isDone()) break;
                Thread.sleep(5);
            }
            if (child.exceeded.get()) return failure("OUTPUT_LIMIT");
            if (child.process.exitValue() == WireProtocol.EXIT_OUTPUT_LIMIT) return failure("OUTPUT_LIMIT");
            if (child.process.exitValue() != 0) return failure("CRASH");
            byte[] bytes;
            try {
                writer.get();
                stderr.get();
                bytes = stdout.get();
            } catch (ExecutionException transportFailure) {
                return failure("MALFORMED_OUTPUT");
            }
            try {
                WireProtocol.Response response = WireProtocol.readResponse(new ByteArrayInputStream(bytes), limits.maxBytes());
                return new Outcome(response.accepted() ? "ACCEPTED" : "REJECTED",
                        response.output(), response.observation());
            } catch (WireProtocol.LimitException tooLarge) {
                return failure("OUTPUT_LIMIT");
            } catch (IOException malformed) {
                return failure("MALFORMED_OUTPUT");
            }
        } finally {
            child.terminate();
            if (writer != null) writer.cancel(true);
            if (stdout != null) stdout.cancel(true);
            if (stderr != null) stderr.cancel(true);
            active.remove(child);
        }
    }

    private static List<String> command(Spec spec, Limits limits, Path launcher) {
        List<String> command = new ArrayList<>();
        command.add(spec.javaExecutable().toString());
        command.addAll(spec.jvmArgs());
        command.add("-jar");
        command.add(launcher.toString());
        command.add(spec.adapterClass());
        command.add(Integer.toString(limits.maxBytes()));
        return command;
    }

    private static Path createLauncher(Spec spec) throws IOException {
        // Java 17's native -cp conversion fails for supplementary Unicode jar names. Manifest URI
        // entries encode them correctly, and also avoid Windows command-line classpath length limits.
        // The actual adapter classpath files are neither copied nor modified.
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, AdapterMain.class.getName());
        attributes.put(Attributes.Name.CLASS_PATH, String.join(" ", spec.classpath().stream()
                .map(path -> path.toAbsolutePath().normalize().toUri().toASCIIString()).toList()));
        Path launcher = Files.createTempFile("serdeproof-launcher-", ".jar");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(launcher), manifest)) {
            return launcher;
        } catch (IOException failure) {
            deleteLauncher(launcher);
            throw failure;
        }
    }

    private static void deleteLauncher(Path launcher) {
        if (launcher == null) return;
        try {
            Files.deleteIfExists(launcher);
        } catch (IOException | SecurityException unavailable) {
            launcher.toFile().deleteOnExit();
        }
    }

    private static byte[] collect(InputStream stream, int maxBytes, boolean retain, AtomicBoolean exceeded)
            throws IOException {
        try (stream) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(retain ? Math.min(maxBytes, 8192) : 0);
            byte[] buffer = new byte[8192];
            int total = 0;
            for (int read; (read = stream.read(buffer)) != -1;) {
                if (read > maxBytes - total) {
                    exceeded.set(true);
                    return new byte[0];
                }
                total += read;
                if (retain) bytes.write(buffer, 0, read);
            }
            return bytes.toByteArray();
        }
    }

    private static Outcome failure(String status) {
        return new Outcome(status, new byte[0], new byte[0]);
    }

    @Override public void close() {
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
        }
        stopActive();
        io.shutdownNow();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException shuttingDown) {
            // The registered hook is already responsible for cleanup.
        }
    }

    private void stopActive() {
        for (ActiveProcess process : active) process.terminate();
    }

    private static final class ActiveProcess {
        private final Process process;
        private final Path launcher;
        private final Set<ProcessHandle> descendants = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean exceeded = new AtomicBoolean();

        private ActiveProcess(Process process, Path launcher) {
            this.process = process;
            this.launcher = launcher;
        }

        private void captureDescendants() {
            try (var handles = process.descendants()) {
                handles.forEach(descendants::add);
            } catch (RuntimeException ignored) {
                // Trusted adapters normally share user privileges; restrictions can prevent inspection.
            }
        }

        private synchronized void terminate() {
            boolean interrupted = Thread.interrupted();
            try {
                captureDescendants();
                // Kill children before their parent so the JVM can reap them while it is still alive.
                descendants.forEach(handle -> destroy(handle, false));
                waitForDescendants(100);
                descendants.forEach(handle -> destroy(handle, true));
                waitForDescendants(100);
                destroy(process.toHandle(), false);
                try {
                    if (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                        destroy(process.toHandle(), true);
                        process.waitFor(500, TimeUnit.MILLISECONDS);
                    }
                } catch (InterruptedException ex) {
                    interrupted = true;
                    destroy(process.toHandle(), true);
                }
                closeQuietly(process.getOutputStream());
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                deleteLauncher(launcher);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        private void waitForDescendants(long millis) {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (descendants.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < end) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private static void destroy(ProcessHandle handle, boolean force) {
            try {
                if (handle.isAlive()) {
                    if (force) handle.destroyForcibly(); else handle.destroy();
                }
            } catch (SecurityException | UnsupportedOperationException ignored) {
                // Cleanup is best effort and cannot replace OS containment of hostile code.
            }
        }

        private static void closeQuietly(java.io.Closeable stream) {
            try { stream.close(); } catch (IOException ignored) { }
        }
    }
}
