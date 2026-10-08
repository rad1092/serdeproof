package demo;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import io.github.rad1092.serdeproof.api.AdapterResult;
import io.github.rad1092.serdeproof.api.RejectedInputException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class Jackson3AdapterTest {
    private final Jackson3Adapter adapter = new Jackson3Adapter();
    private final JsonMapper parser = new JsonMapper();

    private AdapterResult evaluate(String type, String input, String configuration) throws Exception {
        return adapter.evaluate(type, input.getBytes(StandardCharsets.UTF_8), configuration.getBytes(StandardCharsets.UTF_8));
    }
    private AdapterResult evaluate(String type, String input) throws Exception { return evaluate(type, input, ""); }
    private JsonNode output(AdapterResult result) throws Exception { return parser.readTree(result.output()); }
    private JsonNode observation(AdapterResult result) throws Exception { return parser.readTree(result.observation()); }

    @Test void verifiesUnknownTrailingNullDefaults() throws Exception {
        assertEquals(7, observation(evaluate("primitive", "{\"value\":7,\"future\":true}" )).get("value").intValue());
        assertThrows(RejectedInputException.class, () -> evaluate("primitive", "{\"value\":7} {}"));
        assertThrows(RejectedInputException.class, () -> evaluate("primitive", "{\"value\":null}"));
        assertEquals(7, observation(evaluate("primitive", "{}" )).get("value").intValue());
    }

    @Test void distinguishesEnumWireValueFromDomainIdentity() throws Exception {
        AdapterResult result = evaluate("enum", "{}");
        assertEquals("ready", output(result).get("value").stringValue());
        assertEquals("READY", observation(result).get("value").stringValue());
        assertEquals("READY", observation(evaluate("enum", "{\"value\":\"ready\"}")).get("value").stringValue());
        assertThrows(RejectedInputException.class, () -> evaluate("enum", "{\"value\":\"READY\"}"));
    }

    @Test void datesUseInstantObservationAndHistoricalNegativeTimestampWorks() throws Exception {
        AdapterResult date = evaluate("date", "{\"value\":\"2025-01-02T03:04:05Z\"}");
        assertTrue(output(date).get("value").isString());
        assertEquals("2025-01-02T03:04:05Z", observation(date).get("value").stringValue());
        AdapterResult historical = evaluate("date", "{\"value\":\"-1383043669935\"}");
        assertTrue(output(historical).get("value").isString());
        assertEquals("1926-03-05T13:12:10.065Z", observation(historical).get("value").stringValue());
    }

    @Test void preciseTypedNumbersDoNotPassThroughDouble() throws Exception {
        AdapterResult result = evaluate("numbers", "{\"integer\":9007199254740993,\"decimal\":1.0000000000000001}");
        String observed = new String(result.observation(), StandardCharsets.UTF_8);
        assertTrue(observed.contains("9007199254740993"));
        assertTrue(observed.contains("1.0000000000000001"));
    }

    @Test void explicitNumberConfigurationChangesApplicationRepresentation() throws Exception {
        AdapterResult defaults = evaluate("untyped-number", "{\"value\":1.0000000000000001}");
        AdapterResult decimals = evaluate("untyped-number", "{\"value\":1.0000000000000001}", "{\"decimalNumbers\":true}");
        assertEquals("Double", observation(defaults).get("javaType").stringValue());
        assertEquals("BigDecimal", observation(decimals).get("javaType").stringValue());
        assertNotEquals(new String(defaults.output(), StandardCharsets.UTF_8), new String(decimals.output(), StandardCharsets.UTF_8));
    }

    @Test void missingAndNullStayDistinctAndOmittingNullLosesDomainState() throws Exception {
        assertEquals("fallback", observation(evaluate("nullable", "{}")).get("value").stringValue());
        assertTrue(observation(evaluate("nullable", "{\"value\":null}")).get("value").isNull());
        AdapterResult omitted = evaluate("nullable", "{\"value\":null}", "{\"omitNulls\":true}");
        assertFalse(output(omitted).has("value"));
        AdapterResult consumed = adapter.evaluate("nullable", omitted.output(), "{\"omitNulls\":true}".getBytes(StandardCharsets.UTF_8));
        assertEquals("fallback", observation(consumed).get("value").stringValue());
    }

    @Test void applicationModuleRoundtripCanLosePrecisionEvenWithoutVersionChange() throws Exception {
        AdapterResult exact = evaluate("money", "{\"value\":\"12.345\"}");
        assertEquals("12.345", output(exact).get("value").stringValue());
        AdapterResult rounded = evaluate("money", "{\"value\":\"12.345\"}", "{\"quantizeMoney\":true}");
        assertEquals("12.34", output(rounded).get("value").stringValue());
        assertEquals("12.345", observation(rounded).get("value").stringValue());
        AdapterResult consumed = adapter.evaluate("money", rounded.output(), new byte[0]);
        assertEquals("12.34", observation(consumed).get("value").stringValue());
        assertThrows(RejectedInputException.class, () -> evaluate("money", "{\"value\":\"not-money\"}"));
    }

    @Test void historicalRecordVisibilityFixtureIsSupportedWithCurrentPin() throws Exception {
        AdapterResult result = evaluate("record-visibility", "{}");
        assertTrue(observation(result).get("string").isNull());
        assertEquals(0, observation(result).get("integer").intValue());
        assertEquals(observation(result), observation(adapter.evaluate("record-visibility", result.output(), new byte[0])));
    }

    @Test void configurationAndUnknownTypeAreInfrastructureErrors() {
        assertThrows(IllegalArgumentException.class, () -> evaluate("primitive", "{}", "{\"typo\":true}"));
        assertThrows(IllegalArgumentException.class, () -> evaluate("primitive", "{}", "{\"omitNulls\":\"yes\"}"));
        assertThrows(IllegalArgumentException.class, () -> evaluate("absent", "{}"));
    }
}
