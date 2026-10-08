package io.github.rad1092.serdeproof;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rad1092.serdeproof.internal.Fingerprints;
import io.github.rad1092.serdeproof.internal.JsonEvidence;
import io.github.rad1092.serdeproof.internal.RunConfiguration;
import io.github.rad1092.serdeproof.internal.RunConfiguration.Adapter;
import io.github.rad1092.serdeproof.internal.RunConfiguration.Fixture;
import io.github.rad1092.serdeproof.internal.RunConfiguration.Rule;
import io.github.rad1092.serdeproof.runtime.JvmAdapter;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TimeZone;

/** Executes trusted local adapters in fresh, separate JVMs; this is not a security sandbox. */
public final class MigrationLab {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> STATUSES = Set.of("ACCEPTED", "REJECTED", "TIMEOUT", "CRASH",
            "MALFORMED_OUTPUT", "OUTPUT_LIMIT", "INPUT_LIMIT", "START_FAILED");
    private static final long METADATA_LIMIT = 16L * 1024 * 1024;

    /**
     * Run one manifest. The supplied date controls rule expiry, making repeated runs reproducible.
     * Detailed reports reveal fixture IDs, pointers and rule reasons, but never payload values.
     * Configuration, infrastructure and stale-rule failures have exit code 2; changes have 1.
     * Interruption propagates after child-process cleanup so callers can map it to exit code 130.
     */
    public Report run(Path manifest, LocalDate asOf, boolean details) throws IOException, InterruptedException {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(asOf, "asOf");
        checkInterrupted();
        Map<String, Object> report = base(asOf, details);
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> fixtureReports = new ArrayList<>();
        report.put("errors", errors);
        report.put("fixtures", fixtureReports);
        RunConfiguration config;
        try { config = RunConfiguration.load(manifest); }
        catch (IOException | RuntimeException e) {
            checkInterrupted();
            errors.add(error("CONFIGURATION", e instanceof RunConfiguration.Invalid i ? i.code() : "MANIFEST_IO_FAILURE", null));
            return finish(report, fixtureReports, errors, 0, 0, 0);
        }
        report.put("manifestSha256", config.manifestHash());
        report.put("limits", map("maxBytes", config.limits().maxBytes(), "timeoutMillis", config.limits().timeoutMillis(),
                "maxFixtures", config.limits().maxFixtures(), "maxDifferences", config.limits().maxDifferences(),
                "maxCorpusBytes", config.limits().maxCorpusBytes(), "maxMetadataBytes", METADATA_LIMIT,
                "maxReportBytes", METADATA_LIMIT));
        Map<String, Object> baselineIdentity, candidateIdentity;
        try {
            baselineIdentity = identity(config.baseline()); candidateIdentity = identity(config.candidate());
            report.put("baseline", baselineIdentity); report.put("candidate", candidateIdentity);
        } catch (IOException | RuntimeException e) {
            checkInterrupted();
            errors.add(error("CONFIGURATION", "ADAPTER_FINGERPRINT_FAILURE", null));
            return finish(report, fixtureReports, errors, 0, 0, 0);
        }
        State state = new State(config, asOf, details, errors);
        long corpusRead = 0;
        int untested = 0;
        try (JvmAdapter runtime = new JvmAdapter()) {
            for (Fixture fixture : config.fixtures()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                Map<String, Object> fr = map("id", visible(fixture.id(), details), "type", visible(fixture.type(), details),
                        "hintsSha256", Fingerprints.sha256(JSON.writeValueAsBytes(new TreeMap<>(fixture.hints()))));
                List<Map<String, Object>> findings = new ArrayList<>();
                List<Map<String, Object>> matrix = new ArrayList<>();
                fr.put("findings", findings); fr.put("matrix", matrix); fixtureReports.add(fr);
                byte[] input;
                try {
                    input = RunConfiguration.boundedRead(fixture.file(), config.limits().maxBytes());
                    corpusRead += input.length;
                    if (corpusRead > config.limits().maxCorpusBytes()) throw new RunConfiguration.Invalid("CORPUS_SIZE_LIMIT");
                } catch (IOException e) {
                    checkInterrupted();
                    fr.put("semanticCoverage", "UNTESTED_INPUT_ERROR");
                    errors.add(error("INPUT", e instanceof RunConfiguration.Invalid i ? i.code() : "INPUT_IO_FAILURE", visible(fixture.id(), details)));
                    untested++;
                    if (e instanceof RunConfiguration.Invalid i && i.code().equals("CORPUS_SIZE_LIMIT")) {
                        report.put("truncated", true);
                        break;
                    }
                    continue;
                }
                fr.put("inputSha256", Fingerprints.sha256(input));
                JvmAdapter.Outcome baseline = invoke(runtime, config.baseline(), fixture, input, config);
                JvmAdapter.Outcome candidate = invoke(runtime, config.candidate(), fixture, input, config);
                fr.put("baseline", baseline.status()); fr.put("candidate", candidate.status());
                state.infrastructure(baseline, fixture, "baseline"); state.infrastructure(candidate, fixture, "candidate");
                boolean bAccepted = accepted(baseline), cAccepted = accepted(candidate);
                boolean comparable = ordinary(baseline) && ordinary(candidate);
                if (bAccepted && cAccepted) fr.put("semanticCoverage", "COMPARED");
                else {
                    fr.put("semanticCoverage", comparable && !bAccepted && !cAccepted ? "UNTESTED_BOTH_REJECTED" : "UNTESTED_ACCEPTANCE");
                    untested++;
                }
                try {
                    if (comparable && bAccepted != cAccepted)
                        state.add(fixture, findings, "ACCEPTANCE", "baseline-to-candidate", "");
                    if (bAccepted && cAccepted) {
                        state.compare(fixture, findings, "OUTPUT", "baseline-to-candidate", baseline.output(), candidate.output(), Map.of());
                        state.compare(fixture, findings, "OBSERVATION", "baseline-to-candidate", baseline.observation(), candidate.observation(), fixture.hints());
                    }
                    String[] names = {"baseline", "candidate"};
                    JvmAdapter.Outcome[] producers = {baseline, candidate};
                    Adapter[] consumers = {config.baseline(), config.candidate()};
                    for (int p = 0; p < 2; p++) for (int c = 0; c < 2; c++) {
                        String direction = names[p] + "-to-" + names[c];
                        Map<String, Object> cell = map("direction", direction);
                        matrix.add(cell);
                        if (!accepted(producers[p])) {
                            cell.put("status", "NOT_RUN");
                            cell.put("coverage", "UNTESTED_PRODUCER_" + producers[p].status());
                            continue;
                        }
                        JvmAdapter.Outcome consumed = invoke(runtime, consumers[c], fixture, producers[p].output(), config);
                        cell.put("status", consumed.status());
                        cell.put("coverage", accepted(consumed) ? "COMPARED" : "UNTESTED_CONSUMER");
                        state.infrastructure(consumed, fixture, direction);
                        if (consumed.status().equals("REJECTED")) state.add(fixture, findings, "ROUNDTRIP_ACCEPTANCE", direction, "");
                        else if (accepted(consumed)) state.compare(fixture, findings, "ROUNDTRIP", direction,
                                producers[p].observation(), consumed.observation(), fixture.hints());
                    }
                } catch (JsonEvidence.DifferenceLimitException e) {
                    errors.add(error("LIMIT", "DIFFERENCE_LIMIT", visible(fixture.id(), details)));
                    if ("COMPARED".equals(fr.get("semanticCoverage"))) untested++;
                    fr.put("semanticCoverage", "UNTESTED_TRUNCATED");
                    fr.put("truncated", true);
                    report.put("truncated", true);
                    break;
                } catch (IOException e) {
                    checkInterrupted();
                    errors.add(error("ADAPTER", "MALFORMED_OUTPUT", visible(fixture.id(), details)));
                    state.incomplete.add(fixture.id());
                }
                if (!state.incomplete.contains(fixture.id())) state.completed.add(fixture.id());
                findings.sort(Comparator.comparing(m -> m.get("direction") + "\u0000" + m.get("kind") + "\u0000" + m.get("pointer")));
            }
        }
        // Detect ordinary edits during a run; concurrent mutation is not an atomic snapshot guarantee.
        verifyIdentity(config.baseline(), baselineIdentity, "baseline", errors);
        verifyIdentity(config.candidate(), candidateIdentity, "candidate", errors);
        List<Map<String, Object>> ruleReports = new ArrayList<>();
        for (Rule rule : config.rules()) {
            int matches = state.matches.getOrDefault(rule.id(), 0);
            String status = rule.expires().isBefore(asOf) ? "EXPIRED" : matches > 0 ? "USED"
                    : state.completed.contains(rule.fixture()) ? "UNUSED" : "NOT_EVALUATED";
            Map<String, Object> rr = map("id", visible(rule.id(), details), "fixture", visible(rule.fixture(), details),
                    "kind", rule.kind(), "direction", rule.direction(), "pointer", visible(rule.pointer(), details),
                    "expires", rule.expires().toString(), "status", status, "matches", matches);
            if (details) rr.put("reason", rule.reason());
            ruleReports.add(rr);
            if (!status.equals("USED")) errors.add(error("RULE", "RULE_" + status, visible(rule.id(), details)));
        }
        report.put("rules", ruleReports);
        return finish(report, fixtureReports, errors, state.unexpected, state.expected, untested);
    }

    private static JvmAdapter.Outcome invoke(JvmAdapter runtime, Adapter adapter, Fixture fixture, byte[] input,
                                             RunConfiguration config) throws InterruptedException {
        JvmAdapter.Outcome outcome = runtime.invoke(adapter.spec(), fixture.type(), input, adapter.configuration(), config.limits().process());
        if (!STATUSES.contains(outcome.status())) return new JvmAdapter.Outcome("MALFORMED_OUTPUT", new byte[0], new byte[0]);
        if (accepted(outcome)) {
            try { JsonEvidence.parse(outcome.output()); JsonEvidence.parse(outcome.observation()); }
            catch (IOException | RuntimeException e) { return new JvmAdapter.Outcome("MALFORMED_OUTPUT", new byte[0], new byte[0]); }
        }
        return outcome;
    }
    private static boolean accepted(JvmAdapter.Outcome o) { return o.status().equals("ACCEPTED"); }
    private static boolean ordinary(JvmAdapter.Outcome o) { return accepted(o) || o.status().equals("REJECTED"); }

    private static final class State {
        final RunConfiguration config; final LocalDate asOf; final boolean details;
        final List<Map<String, Object>> errors; final Map<String, Rule> rules = new HashMap<>();
        final Map<String, Integer> matches = new HashMap<>();
        final Set<String> incomplete = new HashSet<>(), completed = new HashSet<>();
        int expected, unexpected, total; long metadata;
        State(RunConfiguration config, LocalDate asOf, boolean details, List<Map<String, Object>> errors) {
            this.config = config; this.asOf = asOf; this.details = details; this.errors = errors;
            for (Rule rule : config.rules()) rules.put(rule.scope(), rule);
        }
        void compare(Fixture fixture, List<Map<String, Object>> findings, String prefix, String direction,
                     byte[] left, byte[] right, Map<String, String> hints) throws IOException {
            for (JsonEvidence.Difference d : JsonEvidence.compare(left, right, hints, config.limits().maxDifferences() - total))
                add(fixture, findings, prefix + "_" + d.kind(), direction, d.pointer());
        }
        void add(Fixture fixture, List<Map<String, Object>> findings, String kind, String direction, String pointer) throws JsonEvidence.DifferenceLimitException {
            if (total >= config.limits().maxDifferences() || (metadata += pointer.length() * 12L + 2048) > METADATA_LIMIT)
                throw new JsonEvidence.DifferenceLimitException();
            total++;
            Rule rule = rules.get(fixture.id() + '\u0000' + kind + '\u0000' + direction + '\u0000' + pointer);
            boolean known = rule != null && !rule.expires().isBefore(asOf);
            if (known) { expected++; matches.merge(rule.id(), 1, Integer::sum); } else unexpected++;
            Map<String, Object> finding = map("kind", kind, "direction", direction, "pointer", visible(pointer, details), "expected", known);
            if (known) finding.put("ruleId", visible(rule.id(), details));
            findings.add(finding);
        }
        void infrastructure(JvmAdapter.Outcome outcome, Fixture fixture, String direction) {
            if (!ordinary(outcome)) {
                incomplete.add(fixture.id());
                Map<String, Object> e = error("ADAPTER", outcome.status(), visible(fixture.id(), details));
                e.put("direction", direction); errors.add(e);
            }
        }
    }

    private static Map<String, Object> identity(Adapter adapter) throws IOException {
        Path java = adapter.spec().javaExecutable();
        if (java == null) java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java");
        return map("adapterClassSha256", Fingerprints.text(adapter.spec().adapterClass()),
                "configurationSha256", Fingerprints.sha256(adapter.configuration()),
                "classpathSha256", Fingerprints.classpath(adapter.spec().classpath()),
                "javaExecutableSha256", Fingerprints.file(java),
                "jvmArgumentsSha256", Fingerprints.sha256(JSON.writeValueAsBytes(adapter.spec().jvmArgs())));
    }
    private static void verifyIdentity(Adapter adapter, Map<String, Object> before, String name, List<Map<String, Object>> errors) throws InterruptedException {
        checkInterrupted();
        try { if (!before.equals(identity(adapter))) errors.add(error("REPRODUCTION", "ADAPTER_CONTENT_CHANGED", name)); }
        catch (IOException | RuntimeException e) { checkInterrupted(); errors.add(error("REPRODUCTION", "ADAPTER_CONTENT_CHANGED", name)); }
    }
    private static Map<String, Object> base(LocalDate asOf, boolean details) {
        return map("schemaVersion", 1, "toolVersion", "0.1.0", "evaluationDate", asOf.toString(),
                "details", details, "environment", map("osName", System.getProperty("os.name"),
                        "osArch", System.getProperty("os.arch"), "osVersion", System.getProperty("os.version"),
                        "javaVersion", System.getProperty("java.version"), "javaVendor", System.getProperty("java.vendor"),
                        "timeZone", TimeZone.getDefault().getID(), "locale", Locale.getDefault().toLanguageTag()),
                "interpretation", "Adapter observations are evidence, not a guarantee of semantic equivalence.");
    }
    private static Report finish(Map<String, Object> report, List<Map<String, Object>> fixtures,
                                 List<Map<String, Object>> errors, int unexpected, int expected, int untested) throws IOException {
        int exit = !errors.isEmpty() ? 2 : unexpected > 0 ? 1 : 0;
        report.put("summary", map("fixturesProcessed", fixtures.size(), "expectedDifferences", expected,
                "unexpectedDifferences", unexpected, "untestedFixtures", untested, "errors", errors.size(), "exitCode", exit));
        try {
            String json = boundedJson(report);
            String xml = junit(fixtures, errors);
            if (xml.getBytes(StandardCharsets.UTF_8).length > METADATA_LIMIT) throw new ReportLimitException();
            return new Report(json, xml, exit);
        } catch (ReportLimitException e) {
            Map<String, Object> compact = base(LocalDate.parse(String.valueOf(report.get("evaluationDate"))), Boolean.TRUE.equals(report.get("details")));
            List<Map<String, Object>> limits = List.of(error("LIMIT", "REPORT_SIZE_LIMIT", null));
            compact.put("errors", limits); compact.put("fixtures", List.of()); compact.put("truncated", true);
            return finish(compact, List.of(), limits, 0, 0, 0);
        }
    }
    private static final class ReportLimitException extends IOException { private static final long serialVersionUID = 1L; }
    private static String boundedJson(Map<String, Object> value) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        OutputStream bounded = new OutputStream() {
            private int count;
            @Override public void write(int b) throws IOException {
                if (++count > METADATA_LIMIT - 1) throw new ReportLimitException();
                buffer.write(b);
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                if (count + (long) len > METADATA_LIMIT - 1) throw new ReportLimitException();
                count += len; buffer.write(b, off, len);
            }
        };
        try { JSON.writerWithDefaultPrettyPrinter().writeValue(bounded, sorted(value)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) if (cause instanceof ReportLimitException) throw new ReportLimitException();
            throw e;
        }
        return buffer.toString(StandardCharsets.UTF_8) + "\n";
    }
    @SuppressWarnings("unchecked")
    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> result = new TreeMap<>();
            m.forEach((k, v) -> result.put(String.valueOf(k), sorted(v)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(MigrationLab::sorted).toList();
        return value;
    }
    @SuppressWarnings("unchecked")
    private static String junit(List<Map<String, Object>> fixtures, List<Map<String, Object>> errors) {
        int failures = 0, skipped = 0;
        for (Map<String, Object> f : fixtures) {
            List<Map<String, Object>> fs = (List<Map<String, Object>>) f.get("findings");
            if (fs.stream().anyMatch(d -> Boolean.FALSE.equals(d.get("expected")))) failures++;
            else if (String.valueOf(f.get("semanticCoverage")).startsWith("UNTESTED")) skipped++;
        }
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.append("<testsuite name=\"SerdeProof\" tests=\"").append(fixtures.size() + errors.size())
                .append("\" failures=\"").append(failures).append("\" errors=\"").append(errors.size())
                .append("\" skipped=\"").append(skipped).append("\">\n");
        for (Map<String, Object> f : fixtures) {
            out.append("  <testcase classname=\"serdeproof.fixture\" name=\"").append(xml(String.valueOf(f.get("id")))).append("\">");
            List<Map<String, Object>> fs = (List<Map<String, Object>>) f.get("findings");
            List<Map<String, Object>> unexpected = fs.stream().filter(d -> Boolean.FALSE.equals(d.get("expected"))).toList();
            if (!unexpected.isEmpty()) {
                out.append("<failure type=\"CompatibilityChange\" message=\"").append(unexpected.size()).append(" unexpected differences\">");
                out.append("See the value-free JSON report for finding kinds, directions and pointers.");
                out.append("</failure>");
            } else if (String.valueOf(f.get("semanticCoverage")).startsWith("UNTESTED"))
                out.append("<skipped message=\"").append(xml(String.valueOf(f.get("semanticCoverage")))).append("\"/>");
            out.append("</testcase>\n");
        }
        for (int i = 0; i < errors.size(); i++) {
            Map<String, Object> error = errors.get(i);
            out.append("  <testcase classname=\"serdeproof.validation\" name=\"validation-").append(i + 1)
                    .append("\"><error type=\"").append(xml(String.valueOf(error.get("kind"))))
                    .append("\" message=\"").append(xml(String.valueOf(error.get("code")))).append("\"/></testcase>\n");
        }
        return out.append("</testsuite>\n").toString();
    }
    private static String xml(String input) {
        StringBuilder clean = new StringBuilder();
        input.codePoints().forEach(c -> {
            if (c == 9 || c == 10 || c == 13 || (c >= 32 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD) || (c >= 0x10000 && c <= 0x10FFFF)) clean.appendCodePoint(c);
            else clean.append('\uFFFD');
        });
        return clean.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
    private static String visible(String text, boolean details) { return details ? text : "sha256:" + Fingerprints.text(text); }
    private static void checkInterrupted() throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
    }
    private static Map<String, Object> error(String kind, String code, String scope) {
        Map<String, Object> result = map("kind", kind, "code", code);
        if (scope != null) result.put("scope", scope);
        return result;
    }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
