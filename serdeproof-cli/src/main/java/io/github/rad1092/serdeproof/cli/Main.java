package io.github.rad1092.serdeproof.cli;

import io.github.rad1092.serdeproof.MigrationLab;
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
                    SerdeProof 0.1.0 — serializer migration evidence from your local corpus
                    Usage: java -jar serdeproof-cli-0.1.0.jar run --manifest FILE
                             --json FILE --junit FILE [--as-of YYYY-MM-DD] [--details] [--force]
                    --details  Include fixture IDs, JSON pointers and rule reasons. Never values.
                    --force    Replace existing report files.
                    --as-of    Evaluation date for rule expiry (default: current UTC date).
                    Exit: 0 no unexpected findings; 1 differences; 2 invalid/incomplete run; 130 cancelled.
                    Adapters execute trusted local code with your privileges. This is not a sandbox.
                    Passing results cover only this corpus and the adapter's observations.
                    """);
            return 0;
        }
        if (args.length == 1 && args[0].equals("--version")) {
            out.println("SerdeProof 0.1.0");
            return 0;
        }
        try {
            Map<String, String> options = parse(args);
            Path manifest = Path.of(required(options, "--manifest")).toAbsolutePath().normalize();
            Path json = Path.of(required(options, "--json")).toAbsolutePath().normalize();
            Path junit = Path.of(required(options, "--junit")).toAbsolutePath().normalize();
            boolean force = options.containsKey("--force");
            if (sameDestination(json, junit) || sameDestination(json, manifest) || sameDestination(junit, manifest)) {
                throw new IllegalArgumentException();
            }
            checkDestination(json, force);
            checkDestination(junit, force);
            LocalDate date = options.containsKey("--as-of")
                    ? LocalDate.parse(options.get("--as-of")) : LocalDate.now(ZoneOffset.UTC);
            var report = new MigrationLab().run(manifest, date, options.containsKey("--details"));
            write(json, report.json(), force);
            write(junit, report.junitXml(), force);
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
            err.println("Run failed: invalid configuration, report destination, or local I/O. Use --help; diagnostics are redacted.");
            return 2;
        }
    }

    private static Map<String, String> parse(String[] args) {
        if (args.length == 0 || !args[0].equals("run")) throw new IllegalArgumentException();
        Set<String> values = Set.of("--manifest", "--json", "--junit", "--as-of");
        Set<String> flags = Set.of("--details", "--force");
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

    private static void checkDestination(Path target, boolean force) throws IOException {
        if (Files.isSymbolicLink(target) || (Files.exists(target) && (!force || !Files.isRegularFile(target)))) {
            throw new IOException("Destination unavailable");
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
