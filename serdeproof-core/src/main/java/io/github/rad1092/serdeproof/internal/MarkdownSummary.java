package io.github.rad1092.serdeproof.internal;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A short Markdown summary for pull requests and CI job summaries. It groups findings by kind and
 * direction and repeats only what the JSON report already contains, so it never shows payload values.
 */
public final class MarkdownSummary {
    private MarkdownSummary() {}

    private static final Map<String, String> KINDS = Map.ofEntries(
            Map.entry("ACCEPTANCE", "Accepted by one version, rejected by the other"),
            Map.entry("ROUNDTRIP_ACCEPTANCE", "JSON written by one version is rejected by the other"),
            Map.entry("MISSING", "Field missing"),
            Map.entry("NULL", "Null where the other version has a value"),
            Map.entry("TYPE", "Different JSON type"),
            Map.entry("VALUE", "Different value"),
            Map.entry("DATE", "Different date or time format"),
            Map.entry("ENUM", "Different enum value"),
            Map.entry("NUMERIC_VALUE", "Different number"),
            Map.entry("NUMERIC_LEXICAL", "Same number, written differently"));

    private static final Map<String, String> PREFIXES = Map.of(
            "OUTPUT_", "JSON output: ",
            "OBSERVATION_", "Read into objects: ",
            "ROUNDTRIP_", "After a round trip: ");

    /** Human description of a finding kind such as {@code OUTPUT_DATE}. */
    public static String describe(String kind) {
        if (KINDS.containsKey(kind)) return KINDS.get(kind);
        for (var prefix : PREFIXES.entrySet()) {
            if (kind.startsWith(prefix.getKey())) {
                String rest = KINDS.getOrDefault(kind.substring(prefix.getKey().length()), kind);
                return prefix.getValue() + Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return kind;
    }

    @SuppressWarnings("unchecked")
    public static String render(Map<String, Object> report) {
        var summary = (Map<String, Object>) report.get("summary");
        int unexpected = number(summary, "unexpectedDifferences");
        int expected = number(summary, "expectedDifferences");
        int untested = number(summary, "untestedFixtures");
        int errors = number(summary, "errors");
        int processed = number(summary, "fixturesProcessed");

        var out = new StringBuilder();
        out.append("## SerdeProof: ").append(headline(unexpected, errors)).append("\n\n");
        out.append("| | Count |\n|---|---:|\n");
        out.append("| Payloads checked | ").append(processed).append(" |\n");
        out.append("| Unexpected differences | ").append(unexpected).append(" |\n");
        out.append("| Approved differences | ").append(expected).append(" |\n");
        out.append("| Untested payloads | ").append(untested).append(" |\n");
        out.append("| Errors | ").append(errors).append(" |\n");

        var groups = new TreeMap<String, int[]>();
        for (var fixture : (List<Map<String, Object>>) report.getOrDefault("fixtures", List.of())) {
            for (var finding : (List<Map<String, Object>>) fixture.getOrDefault("findings", List.of())) {
                if (Boolean.TRUE.equals(finding.get("expected"))) continue;
                String key = describe(String.valueOf(finding.get("kind"))) + "\u0000" + finding.get("direction");
                groups.computeIfAbsent(key, k -> new int[1])[0]++;
            }
        }
        if (!groups.isEmpty()) {
            out.append("\n### Unexpected differences\n\n| What changed | Direction | Count |\n|---|---|---:|\n");
            groups.forEach((key, count) -> {
                String[] parts = key.split("\u0000", 2);
                out.append("| ").append(parts[0]).append(" | ").append(parts[1].replace("-to-", " → "))
                        .append(" | ").append(count[0]).append(" |\n");
            });
        }

        var errorCodes = new TreeMap<String, int[]>();
        for (var error : (List<Map<String, Object>>) report.getOrDefault("errors", List.of())) {
            errorCodes.computeIfAbsent(error.get("kind") + " " + error.get("code"), k -> new int[1])[0]++;
        }
        if (!errorCodes.isEmpty()) {
            out.append("\n### Errors\n\n| Code | Count |\n|---|---:|\n");
            errorCodes.forEach((code, count) -> out.append("| `").append(code).append("` | ").append(count[0]).append(" |\n"));
        }
        if (untested > 0) {
            out.append("\nUntested payloads were rejected by at least one version, so their output could not be compared.\n");
        }
        if (!Boolean.TRUE.equals(report.get("details")) && unexpected > 0) {
            out.append("\nRun with `--details` to see which payloads and JSON pointers changed.\n");
        }
        return out.toString();
    }

    private static String headline(int unexpected, int errors) {
        if (errors > 0) return "incomplete run";
        if (unexpected == 0) return "no unexpected differences";
        return unexpected == 1 ? "1 unexpected difference" : unexpected + " unexpected differences";
    }

    private static int number(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof Number n ? n.intValue() : 0;
    }
}
