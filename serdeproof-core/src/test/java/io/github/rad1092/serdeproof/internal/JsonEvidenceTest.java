package io.github.rad1092.serdeproof.internal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonEvidenceTest {
    @Test
    void rejectsAmbiguousOrNonJsonInputWithoutLeakingContent() {
        for (String invalid : List.of("", " ", "{} {}", "{\"secret\":1,\"secret\":2}",
                "{\"a\":{\"secret\":1,\"secret\":2}}", "[NaN]", "[Infinity]", "[-Infinity]",
                "[01]", "[+1]", "[.1]", "[1.]", "[1e]", "{'secret':1}",
                "{secret:1}", "[1,]", "/*secret*/{}", "{\"secret\":", "[")) {
            IOException failure = assertThrows(IOException.class, () -> JsonEvidence.parse(bytes(invalid)), invalid);
            assertEquals("Invalid JSON document", failure.getMessage());
            assertNull(failure.getCause());
        }
        assertThrows(IOException.class, () -> JsonEvidence.parse(null));
    }

    @Test
    void rejectsOtherEncodingsMalformedUtf8AndLeadingBom() throws IOException {
        String json = "{\"x\":1}";
        for (byte[] invalid : List.of(json.getBytes(StandardCharsets.UTF_16),
                json.getBytes(StandardCharsets.UTF_16LE), json.getBytes(StandardCharsets.UTF_16BE),
                new byte[]{0, 0, 0, '{', 0, 0, 0, '}'},
                new byte[]{'{', 0, 0, 0, '}', 0, 0, 0},
                new byte[]{0, 0, (byte) 0xFE, (byte) 0xFF, 0, 0, 0, '{', 0, 0, 0, '}'},
                new byte[]{(byte) 0xFF, (byte) 0xFE, 0, 0, '{', 0, 0, 0, '}', 0, 0, 0},
                new byte[]{'"', (byte) 0xC3, '(', '"'},
                new byte[]{'"', (byte) 0xC0, (byte) 0xAF, '"'},
                new byte[]{'"', (byte) 0xE2},
                new byte[]{'"', (byte) 0xED, (byte) 0xA0, (byte) 0x80, '"'},
                new byte[]{'"', (byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80, '"'},
                bytes("\uFEFF{}"))) {
            IOException failure = assertThrows(IOException.class, () -> JsonEvidence.parse(invalid));
            assertEquals("Invalid JSON document", failure.getMessage());
            assertNull(failure.getCause());
            assertThrows(IOException.class, () -> JsonEvidence.compare(bytes(json), invalid, Map.of(), 10));
        }
        assertEquals("한글 😀", JsonEvidence.parse(bytes("\"한글 😀\"")).textValue());
    }

    @Test
    void preservesOriginalNumericTokensAndExactIntegerPrecision() throws IOException {
        JsonNode parsed = JsonEvidence.parse(bytes("[-0,1.00,1E+002,9007199254740993]"));
        assertEquals("-0", parsed.get(0).asText());
        assertEquals("1.00", parsed.get(1).asText());
        assertEquals("1E+002", parsed.get(2).asText());
        assertEquals("9007199254740993", parsed.get(3).asText());
        assertEquals("[-0,1.00,1E+002,9007199254740993]", parsed.toString());
        assertEquals(List.of(difference("NUMERIC_VALUE", "/n")), compare(
                "{\"n\":9007199254740992}", "{\"n\":9007199254740993}"));
    }

    @Test
    void separatesNumericLexicalChangesFromMathematicalValueChanges() throws IOException {
        for (String[] pair : List.of(new String[]{"1", "1.00"}, new String[]{"10e-1", "1"},
                new String[]{"-0", "0"}, new String[]{"0e999999999999", "0.000"},
                new String[]{"1e999999999999", "10e999999999998"},
                new String[]{"-0.00100", "-1E-3"})) {
            assertEquals(List.of(difference("NUMERIC_LEXICAL", "")), compare(pair[0], pair[1]));
        }
        for (String[] pair : List.of(new String[]{"1e999999999999", "1e999999999998"},
                new String[]{"0.100000000000000000001", "0.100000000000000000002"},
                new String[]{"-1", "1"})) {
            assertEquals(List.of(difference("NUMERIC_VALUE", "")), compare(pair[0], pair[1]));
        }
    }

    @Test
    void usesDeterministicPointersIncludingEscapedKeysAndArrayPositions() throws IOException {
        String left = "{\"z\":[1,2],\"a/b~c\":0,\"\":false,\"한 글\":0}";
        String right = "{\"한 글\":1,\"\":true,\"a/b~c\":1,\"z\":[1,3,4]}";
        assertEquals(List.of(difference("VALUE", "/"), difference("NUMERIC_VALUE", "/a~1b~0c"),
                difference("NUMERIC_VALUE", "/z/1"), difference("MISSING", "/z/2"),
                difference("NUMERIC_VALUE", "/한 글")), compare(left, right));
        assertEquals(List.of(), compare("{\"b\":2,\"a\":1}", "{\"a\":1,\"b\":2}"));
        assertEquals(List.of(), compare("\"a\"", "\"\\u0061\""));
    }

    @Test
    void distinguishesMissingNullTypeAndValue() throws IOException {
        assertEquals(List.of(difference("MISSING", "/field")), compare("{}", "{\"field\":null}"));
        assertEquals(List.of(difference("MISSING", "/field")), compare("{\"field\":{\"nested\":1}}", "{}"));
        assertEquals(List.of(difference("NULL", "/field")), compare("{\"field\":null}", "{\"field\":0}"));
        assertEquals(List.of(difference("TYPE", "")), compare("[]", "{}"));
        assertEquals(List.of(difference("TYPE", "")), compare("1", "\"1\""));
        assertEquals(List.of(difference("VALUE", "")), compare("false", "true"));
        assertEquals(List.of(), compare("null", "null"));
    }

    @Test
    void hintsLabelOnlyExactScalarValuesWithoutClaimingDomainEquality() throws IOException {
        assertEquals(List.of(difference("DATE", "/date"), difference("ENUM", "/enum"),
                difference("VALUE", "/nested/date")), JsonEvidence.compare(
                bytes("{\"date\":\"2024-01-01\",\"enum\":\"UP\",\"nested\":{\"date\":\"x\"}}"),
                bytes("{\"date\":\"2024-01-01T00:00:00Z\",\"enum\":\"up\",\"nested\":{\"date\":\"y\"}}"),
                Map.of("/date", "DATE", "/enum", "ENUM"), 10));
        assertEquals(List.of(difference("NUMERIC_LEXICAL", "")),
                JsonEvidence.compare(bytes("1"), bytes("1.0"), Map.of("", "DATE"), 1));
        assertEquals(List.of(difference("TYPE", "")),
                JsonEvidence.compare(bytes("1"), bytes("\"1\""), Map.of("", "DATE"), 1));
    }

    @Test
    void differenceBudgetFailsClosedAndAllowsExactlyTheLimit() throws IOException {
        assertEquals(1, JsonEvidence.compare(bytes("[0]"), bytes("[1]"), Map.of(), 1).size());
        assertEquals(List.of(), JsonEvidence.compare(bytes("[0]"), bytes("[0]"), Map.of(), 0));
        assertThrows(JsonEvidence.DifferenceLimitException.class,
                () -> JsonEvidence.compare(bytes("[0,0]"), bytes("[1,1]"), Map.of(), 1));
        assertThrows(JsonEvidence.DifferenceLimitException.class,
                () -> JsonEvidence.compare(bytes("0"), bytes("1"), Map.of(), 0));
        assertThrows(IOException.class,
                () -> JsonEvidence.compare(bytes("0"), bytes("1"), Map.of(), -1));
    }

    @Test
    void boundsPathologicalNumbersAndNesting() throws IOException {
        assertThrows(IOException.class, () -> JsonEvidence.parse(bytes("1".repeat(1_001))));
        assertThrows(IOException.class, () -> JsonEvidence.parse(bytes("[".repeat(129) + "0" + "]".repeat(129))));
        assertTrue(JsonEvidence.parse(bytes("[".repeat(128) + "0" + "]".repeat(128))).isArray());
        assertThrows(IOException.class, () -> JsonEvidence.parse(bytes("\"" + "x".repeat(1_048_577) + "\"")));
        assertThrows(IOException.class, () -> JsonEvidence.parse(bytes("{\"" + "x".repeat(65_537) + "\":0}")));
        assertFalse(JsonEvidence.parse(bytes("1e999999999999")).canConvertToLong());
    }

    @Test
    void boundsPointerAndAggregateDifferenceMetadata() {
        String longKey = "x".repeat(8_192);
        assertThrows(JsonEvidence.DifferenceLimitException.class, () -> JsonEvidence.compare(
                bytes("{\"" + longKey + "\":0}"), bytes("{\"" + longKey + "\":1}"), Map.of(), 1));
        String repeatedPath = "x".repeat(4_100);
        StringBuilder left = new StringBuilder("{\"" + repeatedPath + "\":[");
        StringBuilder right = new StringBuilder("{\"" + repeatedPath + "\":[");
        for (int i = 0; i < 1_025; i++) {
            if (i > 0) {
                left.append(',');
                right.append(',');
            }
            left.append('0');
            right.append('1');
        }
        left.append("]}");
        right.append("]}");
        assertThrows(JsonEvidence.DifferenceLimitException.class, () -> JsonEvidence.compare(
                bytes(left.toString()), bytes(right.toString()), Map.of(), 2_000));
    }

    @Test
    void boundsTotalDocumentNodesIncludingContainersAndScalars() throws IOException {
        // The array itself is a node, so 99,999 children reach the exact 100,000-node limit.
        assertEquals(99_999, JsonEvidence.parse(bytes("[" + "{},".repeat(99_998) + "{}]")).size());
        IOException containers = assertThrows(IOException.class,
                () -> JsonEvidence.parse(bytes("[" + "{},".repeat(100_000) + "{}]")));
        assertEquals("Invalid JSON document", containers.getMessage());
        assertNull(containers.getCause());
        assertThrows(IOException.class,
                () -> JsonEvidence.parse(bytes("[" + "0,".repeat(99_999) + "0]")));
    }

    private static List<JsonEvidence.Difference> compare(String left, String right) throws IOException {
        return JsonEvidence.compare(bytes(left), bytes(right), Map.of(), 100);
    }

    private static JsonEvidence.Difference difference(String kind, String pointer) {
        return new JsonEvidence.Difference(kind, pointer);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
