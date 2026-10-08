package io.github.rad1092.serdeproof;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rad1092.serdeproof.api.AdapterResult;
import io.github.rad1092.serdeproof.api.RejectedInputException;
import io.github.rad1092.serdeproof.api.SerializerAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import static org.junit.jupiter.api.Assertions.*;

class MigrationLabTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);
    @TempDir Path temp;

    @Test void reportsExactNumericEvidenceAndFourWayCompatibilityDeterministically() throws Exception {
        Path manifest = manifest(Echo.class, NumericUpgrade.class, "{\"n\":1}", Map.of());
        Report first = new MigrationLab().run(manifest, DATE, true);
        Report second = new MigrationLab().run(manifest, DATE, true);
        assertEquals(first, second);
        assertEquals(1, first.exitCode());
        JsonNode fixture = JSON.readTree(first.json()).path("fixtures").get(0);
        assertEquals(4, fixture.path("matrix").size());
        assertEquals("COMPARED", fixture.path("semanticCoverage").asText());
        assertTrue(has(fixture, "OUTPUT_NUMERIC_LEXICAL", "baseline-to-candidate", "/n"));
        assertTrue(has(fixture, "OBSERVATION_NUMERIC_LEXICAL", "baseline-to-candidate", "/n"));
        assertTrue(has(fixture, "ROUNDTRIP_NUMERIC_LEXICAL", "baseline-to-candidate", "/n"));
        var xml = xml(first.junitXml());
        assertEquals("1", xml.getDocumentElement().getAttribute("failures"));
    }

    @Test void detectsLossInBothSameVersionAndCrossVersionRoundTrips() throws Exception {
        Report report = new MigrationLab().run(manifest(Losing.class, Losing.class, "{\"account\":7}", Map.of()), DATE, true);
        JsonNode fixture = JSON.readTree(report.json()).path("fixtures").get(0);
        assertEquals(1, report.exitCode());
        for (String p : List.of("baseline", "candidate")) for (String c : List.of("baseline", "candidate"))
            assertTrue(has(fixture, "ROUNDTRIP_MISSING", p + "-to-" + c, "/account"));
        assertEquals(4, fixture.path("findings").size());
    }

    @Test void defaultReportsRedactIdsPointersValuesAndAdapterExceptions() throws Exception {
        String payload = "{\"private/account~field\":\"private-value-very-secret\"}";
        Path manifest = manifest(Echo.class, Reject.class, payload, Map.of());
        Report report = new MigrationLab().run(manifest, DATE, false);
        assertEquals(1, report.exitCode());
        for (String sensitive : List.of("private-fixture-id", "private/account~field", "private-value-very-secret", "private-exception-message", temp.toString(), "private-type")) {
            assertFalse(report.json().contains(sensitive), sensitive);
            assertFalse(report.junitXml().contains(sensitive), sensitive);
        }
        assertTrue(report.json().contains("sha256:"));
        assertNotNull(xml(report.junitXml()));
    }

    @Test void bothRejectedRecordsUntestedSemanticsAndSkippedJunit() throws Exception {
        Report report = new MigrationLab().run(manifest(Reject.class, Reject.class, "{}", Map.of()), DATE, false);
        assertEquals(0, report.exitCode());
        JsonNode fixture = JSON.readTree(report.json()).path("fixtures").get(0);
        assertEquals("UNTESTED_BOTH_REJECTED", fixture.path("semanticCoverage").asText());
        assertEquals(4, fixture.path("matrix").size());
        assertEquals("1", xml(report.junitXml()).getDocumentElement().getAttribute("skipped"));
    }

    @Test void expectedRulesRequireExactScopeAndFailWhenExpiredOrUnused() throws Exception {
        List<Map<String, Object>> rules = List.of(rule("accept", "ACCEPTANCE"), rule("roundtrip", "ROUNDTRIP_ACCEPTANCE"));
        Path manifest = manifest(Echo.class, Reject.class, "{}", Map.of("rules", rules));
        Report accepted = new MigrationLab().run(manifest, DATE, true);
        assertEquals(0, accepted.exitCode(), accepted.json());
        assertEquals(2, JSON.readTree(accepted.json()).path("summary").path("expectedDifferences").asInt());
        assertTrue(accepted.json().contains("Intentional strict validation"));
        assertFalse(new MigrationLab().run(manifest, DATE, false).json().contains("Intentional strict validation"));
        Report expired = new MigrationLab().run(manifest, DATE.plusDays(1), true);
        assertEquals(2, expired.exitCode());
        assertTrue(expired.json().contains("RULE_EXPIRED"));
        Path unusedManifest = manifest(Echo.class, Echo.class, "{}", Map.of("rules", rules));
        Report unused = new MigrationLab().run(unusedManifest, DATE, true);
        assertEquals(2, unused.exitCode());
        assertTrue(unused.json().contains("RULE_UNUSED"));
    }

    @Test void invalidAdapterJsonIsInfrastructureFailureWithoutLeakingContent() throws Exception {
        Report report = new MigrationLab().run(manifest(Malformed.class, Echo.class, "{}", Map.of()), DATE, true);
        assertEquals(2, report.exitCode());
        assertTrue(report.json().contains("MALFORMED_OUTPUT"));
        assertFalse(report.json().contains("private-duplicate"));
    }

    @Test void corpusAndDifferenceBudgetsFailClosed() throws Exception {
        Report size = new MigrationLab().run(manifest(Echo.class, Echo.class, "\"" + "x".repeat(40) + "\"", Map.of("limits", Map.of("maxBytes", 16))), DATE, false);
        assertEquals(2, size.exitCode());
        assertTrue(size.json().contains("INPUT_SIZE_LIMIT"));
        Report differences = new MigrationLab().run(manifest(Echo.class, NumericUpgrade.class, "{\"n\":1}", Map.of("limits", Map.of("maxDifferences", 1))), DATE, true);
        assertEquals(2, differences.exitCode());
        assertTrue(differences.json().contains("DIFFERENCE_LIMIT"));
        assertTrue(JSON.readTree(differences.json()).path("truncated").asBoolean());
        assertEquals("UNTESTED_TRUNCATED", JSON.readTree(differences.json()).path("fixtures").get(0).path("semanticCoverage").asText());
    }

    @Test void unknownManifestFieldsAndDuplicateKeysFailBeforeExecution() throws Exception {
        Path manifest = manifest(Echo.class, Echo.class, "{}", Map.of("typo", 1));
        Report unknown = new MigrationLab().run(manifest, DATE, false);
        assertEquals(2, unknown.exitCode());
        assertTrue(unknown.json().contains("MANIFEST_UNKNOWN_FIELD"));
        Files.writeString(manifest, "{\"private\":1,\"private\":2}");
        Report duplicate = new MigrationLab().run(manifest, DATE, false);
        assertEquals(2, duplicate.exitCode());
        assertFalse(duplicate.json().contains("private"));
    }

    @Test void fingerprintsChangeWithFixtureContentButDoNotPublishTheContent() throws Exception {
        Path manifest = manifest(Reject.class, Reject.class, "{\"secret\":1}", Map.of());
        JsonNode first = JSON.readTree(new MigrationLab().run(manifest, DATE, false).json());
        Files.writeString(manifest.getParent().resolve("fixture 데이터.json"), "{\"secret\":2}");
        JsonNode second = JSON.readTree(new MigrationLab().run(manifest, DATE, false).json());
        assertNotEquals(first.path("fixtures").get(0).path("inputSha256"), second.path("fixtures").get(0).path("inputSha256"));
        assertEquals(first.path("baseline"), second.path("baseline"));
    }

    private Path manifest(Class<?> baseline, Class<?> candidate, String input, Map<String, Object> extra) throws Exception {
        Path folder = Files.createTempDirectory(temp, "경로 with spaces ");
        Files.writeString(folder.resolve("fixture 데이터.json"), input);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("baseline", adapter(baseline)); manifest.put("candidate", adapter(candidate));
        manifest.put("fixtures", List.of(Map.of("id", "private-fixture-id", "type", "private-type", "file", "fixture 데이터.json")));
        manifest.putAll(extra);
        Path path = folder.resolve("manifest 한글.json"); JSON.writeValue(path.toFile(), manifest); return path;
    }
    private Map<String, Object> adapter(Class<?> cls) throws Exception {
        List<String> cp = new ArrayList<>();
        cp.add(Path.of(cls.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        cp.add(Path.of(SerializerAdapter.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        return Map.of("adapterClass", cls.getName(), "classpath", cp);
    }
    private static Map<String, Object> rule(String id, String kind) {
        return Map.of("id", id, "fixture", "private-fixture-id", "kind", kind, "direction", "baseline-to-candidate",
                "pointer", "", "reason", "Intentional strict validation", "expires", DATE.toString());
    }
    private static boolean has(JsonNode fixture, String kind, String direction, String pointer) {
        for (JsonNode finding : fixture.path("findings")) if (kind.equals(finding.path("kind").asText())
                && direction.equals(finding.path("direction").asText()) && pointer.equals(finding.path("pointer").asText())) return true;
        return false;
    }
    private static org.w3c.dom.Document xml(String value) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    }

    public static final class Echo implements SerializerAdapter {
        @Override public AdapterResult evaluate(String type, byte[] input, byte[] configuration) { return new AdapterResult(input, input); }
    }
    public static final class NumericUpgrade implements SerializerAdapter {
        @Override public AdapterResult evaluate(String type, byte[] input, byte[] configuration) {
            byte[] changed = new String(input, StandardCharsets.UTF_8).replace("\"n\":1}", "\"n\":1.0}").getBytes(StandardCharsets.UTF_8);
            return new AdapterResult(changed, changed);
        }
    }
    public static final class Losing implements SerializerAdapter {
        @Override public AdapterResult evaluate(String type, byte[] input, byte[] configuration) { return new AdapterResult("{}".getBytes(StandardCharsets.UTF_8), input); }
    }
    public static final class Reject implements SerializerAdapter {
        @Override public AdapterResult evaluate(String type, byte[] input, byte[] configuration) throws RejectedInputException { throw new RejectedInputException("private-exception-message"); }
    }
    public static final class Malformed implements SerializerAdapter {
        @Override public AdapterResult evaluate(String type, byte[] input, byte[] configuration) { return new AdapterResult("{\"private-duplicate\":1,\"private-duplicate\":2}".getBytes(StandardCharsets.UTF_8), input); }
    }
}
