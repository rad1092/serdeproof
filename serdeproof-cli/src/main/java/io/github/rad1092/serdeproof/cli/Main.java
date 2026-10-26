package io.github.rad1092.serdeproof.cli;

import io.github.rad1092.serdeproof.MigrationLab;
import io.github.rad1092.serdeproof.internal.JsonEvidence;
import io.github.rad1092.serdeproof.internal.RunConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Command line entry point. Reports never include payload values or adapter diagnostics. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        System.exit(execute(args, System.out, System.err));
    }

    public static int execute(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("help"))) {
            out.println("""
                    SerdeProof 0.1.1 — serializer migration evidence from your local corpus
                    Usage: java -jar serdeproof-cli-0.1.1.jar run --manifest FILE
                             --json FILE --junit FILE [--markdown FILE] [--as-of YYYY-MM-DD]
                             [--details] [--force] [--fail-on-untested]
                    --markdown Also write a short summary for a pull request or CI job summary.
                    --details  Include fixture IDs, JSON pointers and rule reasons. Never values.
                    --force    Replace existing reports, never declared inputs or classpath trees.
                    --fail-on-untested  Exit 2 if any fixture lacks semantic comparison.
                    --as-of    Evaluation date for rule expiry (default: current UTC date).
                    Exit: 0 no unexpected findings; 1 differences; 2 invalid/incomplete run; 130 cancelled.
                    Adapters execute trusted local code with your privileges. This is not a sandbox.
                    Passing results cover only this corpus and the adapter's observations.
                    """);
            return 0;
        }
        if (args.length == 1 && args[0].equals("--version")) {
            out.println("SerdeProof 0.1.1");
            return 0;
        }
        try {
            Map<String, String> options = parse(args);
            Path manifest = Path.of(required(options, "--manifest")).toAbsolutePath().normalize();
            Path json = Path.of(required(options, "--json")).toAbsolutePath().normalize();
            Path junit = Path.of(required(options, "--junit")).toAbsolutePath().normalize();
            Path markdown = options.containsKey("--markdown")
                    ? Path.of(options.get("--markdown")).toAbsolutePath().normalize() : null;
            boolean force = options.containsKey("--force");
            var outputs = new ArrayList<Path>(java.util.List.of(json, junit));
            if (markdown != null) outputs.add(markdown);
            for (int i = 0; i < outputs.size(); i++) {
                if (sameDestination(outputs.get(i), manifest)) throw new ReportDestination();
                for (int j = i + 1; j < outputs.size(); j++)
                    if (sameDestination(outputs.get(i), outputs.get(j))) throw new ReportDestination();
            }
            var inputs = protectedInputs(manifest);
            for (Path output : outputs) {
                checkInputs(output, inputs);
                checkDestination(output, force);
            }
            LocalDate date = options.containsKey("--as-of")
                    ? LocalDate.parse(options.get("--as-of")) : LocalDate.now(ZoneOffset.UTC);
            var report = new MigrationLab().run(manifest, date, options.containsKey("--details"),
                    options.containsKey("--fail-on-untested"));
            // Ordinary edits while adapters run must not redirect publication onto inputs.
            inputs.addAll(protectedInputs(manifest));
            for (Path output : outputs) {
                checkInputs(output, inputs);
                checkDestination(output, force);
            }
            write(json, report.json(), force);
            write(junit, report.junitXml(), force);
            if (markdown != null) write(markdown, report.markdown(), force);
            out.println(switch (report.exitCode()) {
                case 0 -> "Completed: no unexpected findings in this corpus. Review untested cases in the report.";
                case 1 -> "Completed: unexpected differences found. Review JSON or JUnit report.";
                default -> "Incomplete: infrastructure, configuration or rule errors. Review report.";
            });
            return report.exitCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Cancelled. Adapter cleanup requested.");
            return 130;
        } catch (IOException | RuntimeException e) {
            // Codes and fixed hints only: exception messages can contain local paths or payload text.
            String code = failureCode(e);
            err.println("Run failed (" + code + "). " + FAILURE_HINTS.getOrDefault(code, "Use --help. Details are not printed."));
            return 2;
        }
    }

    private static final class ReportDestination extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static final Map<String, String> FAILURE_HINTS = Map.of(
            "USAGE", "Check the options with --help.",
            "PATH_ENCODING", "A file name has non-ASCII characters, but the JVM is not using UTF-8 for file names."
                    + " Run it with a UTF-8 locale, for example LANG=C.UTF-8.",
            "PATH_INVALID", "A path in the manifest is not valid on this system.",
            "REPORT_DESTINATION", "A report path already exists, repeats another path, or overlaps an input."
                    + " Choose new paths or add --force.",
            "MANIFEST", "The manifest could not be read as JSON. Check the file named by --manifest.");

    static String failureCode(Exception e) {
        if (e instanceof java.nio.file.InvalidPathException) {
            String encoding = System.getProperty("sun.jnu.encoding", "");
            return encoding.toUpperCase(java.util.Locale.ROOT).replace("-", "").equals("UTF8") ? "PATH_INVALID" : "PATH_ENCODING";
        }
        if (e instanceof RunConfiguration.Invalid invalid) return invalid.code();
        if (e instanceof ReportDestination) return "REPORT_DESTINATION";
        if (e instanceof IllegalArgumentException || e instanceof java.time.format.DateTimeParseException) return "USAGE";
        if (e instanceof com.fasterxml.jackson.core.JacksonException) return "MANIFEST";
        if (e instanceof java.nio.file.NoSuchFileException) return "FILE_NOT_FOUND";
        return "IO";
    }

    private static Map<String, String> parse(String[] args) {
        if (args.length == 0 || !args[0].equals("run")) throw new IllegalArgumentException();
        Set<String> values = Set.of("--manifest", "--json", "--junit", "--markdown", "--as-of");
        Set<String> flags = Set.of("--details", "--force", "--fail-on-untested");
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String key = args[i];
            if (result.containsKey(key)) throw new IllegalArgumentException();
            if (flags.contains(key)) result.put(key, "true");
            else if (values.contains(key) && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                result.put(key, args[++i]);
            } else throw new IllegalArgumentException();
        }
        return result;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException();
        return value;
    }

    private static ArrayList<Path> protectedInputs(Path manifest) throws IOException {
        JsonNode root = JsonEvidence.parse(RunConfiguration.boundedRead(manifest, 1024 * 1024));
        var inputs = new ArrayList<Path>();
        inputs.add(manifest);
        Path base = manifest.getParent();
        for (JsonNode fixture : root.path("fixtures")) addInput(inputs, base, fixture.path("file"));
        for (String name : new String[]{"baseline", "candidate"}) {
            JsonNode adapter = root.path(name);
            addInput(inputs, base, adapter.path("configuration"));
            addInput(inputs, base, adapter.path("java"));
            for (JsonNode entry : adapter.path("classpath")) addInput(inputs, base, entry);
        }
        inputs.add(Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java"));
        return inputs;
    }

    private static void addInput(ArrayList<Path> inputs, Path base, JsonNode value) {
        if (value.isTextual() && !value.textValue().isBlank())
            inputs.add(base.resolve(value.textValue()).toAbsolutePath().normalize());
    }

    private static void checkInputs(Path target, ArrayList<Path> inputs) throws IOException {
        Path destination = resolvedDestination(target);
        for (Path input : inputs) {
            if (sameDestination(target, input)
                    || (Files.isDirectory(input) && destination.startsWith(input.toRealPath()))) {
                throw new ReportDestination();
            }
        }
    }

    private static void checkDestination(Path target, boolean force) throws IOException {
        if (Files.isSymbolicLink(target) || (Files.exists(target) && (!force || !Files.isRegularFile(target)))) {
            throw new ReportDestination();
        }
    }

    private static boolean sameDestination(Path first, Path second) throws IOException {
        if (first.equals(second)) return true;
        // Real paths alone do not detect hard links to an existing input or report.
        if (Files.exists(first) && Files.exists(second) && Files.isSameFile(first, second)) return true;
        return resolvedDestination(first).equals(resolvedDestination(second));
    }

    private static Path resolvedDestination(Path target) throws IOException {
        // Resolve existing ancestor aliases without creating output directories before validation.
        // This also catches two as-yet nonexistent report files below a symlinked parent.
        var missing = new ArrayList<Path>();
        Path ancestor = target;
        while (!Files.exists(ancestor)) {
            missing.add(ancestor.getFileName());
            ancestor = ancestor.getParent();
            if (ancestor == null) throw new IOException("Destination unavailable");
        }
        Path resolved = ancestor.toRealPath();
        for (int index = missing.size() - 1; index >= 0; index--) resolved = resolved.resolve(missing.get(index));
        return resolved;
    }

    private static void write(Path target, String content, boolean force) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".serdeproof-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            if (force) {
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                // CREATE_NEW avoids clobbering a file created after the preflight check.
                Files.copy(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
