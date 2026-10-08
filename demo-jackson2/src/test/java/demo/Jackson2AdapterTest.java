package demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.rad1092.serdeproof.api.AdapterResult;
import io.github.rad1092.serdeproof.api.RejectedInputException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class Jackson2AdapterTest {
    private final Jackson2Adapter adapter = new Jackson2Adapter();
    private final JsonMapper parser = new JsonMapper();

    private AdapterResult evaluate(String type, String input, String configuration) throws Exception {
        return adapter.evaluate(type, input.getBytes(StandardCharsets.UTF_8), configuration.getBytes(StandardCharsets.UTF_8));
    }
    private AdapterResult evaluate(String type, String input) throws Exception { return evaluate(type, input, ""); }
    private JsonNode output(AdapterResult result) throws Exception { return parser.readTree(result.output()); }
    private JsonNode observation(AdapterResult result) throws Exception { return parser.readTree(result.observation()); }

    @Test void verifiesUnknownTrailingNullDefaults() throws Exception {
        assertThrows(RejectedInputException.class, () -> evaluate("primitive", "{\"value\":7,\"future\":true}"));
        assertEquals(7, observation(evaluate("primitive", "{\"value\":7} {}" )).get("value").intValue());
        assertEquals(0, observation(evaluate("primitive", "{\"value\":null}" )).get("value").intValue());
        assertEquals(7, observation(evaluate("primitive", "{}" )).get("value").intValue());
    }

    @Test void distinguishesEnumWireValueFromDomainIdentity() throws Exception {
        AdapterResult result = evaluate("enum", "{}");
        assertEquals("READY", output(result).get("value").textValue());
        assertEquals("READY", observation(result).get("value").textValue());
        assertThrows(RejectedInputException.class, () -> evaluate("enum", "{\"value\":\"ready\"}"));
        assertEquals("READY", observation(evaluate("enum", "{\"value\":\"READY\"}")).get("value").textValue());
    }

    @Test void datesUseInstantObservationAndHistoricalNegativeTimestampWorks() throws Exception {
        AdapterResult date = evaluate("date", "{\"value\":\"2025-01-02T03:04:05Z\"}");
        assertEquals(1735787045000L, output(date).get("value").longValue());
        assertEquals("2025-01-02T03:04:05Z", observation(date).get("value").textValue());
        AdapterResult historical = evaluate("date", "{\"value\":\"-1383043669935\"}");
        assertEquals(-1383043669935L, output(historical).get("value").longValue());
        assertEquals("1926-03-05T13:12:10.065Z", observation(historical).get("value").textValue());
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
        assertEquals("Double", observation(defaults).get("javaType").textValue());
        assertEquals("BigDecimal", observation(decimals).get("javaType").textValue());
        assertNotEquals(new String(defaults.output(), StandardCharsets.UTF_8), new String(decimals.output(), StandardCharsets.UTF_8));
    }

    @Test void missingAndNullStayDistinctAndOmittingNullLosesDomainState() throws Exception {
        assertEquals("fallback", observation(evaluate("nullable", "{}")).get("value").textValue());
        assertTrue(observation(evaluate("nullable", "{\"value\":null}")).get("value").isNull());
        AdapterResult omitted = evaluate("nullable", "{\"value\":null}", "{\"omitNulls\":true}");
        assertFalse(output(omitted).has("value"));
        AdapterResult consumed = adapter.evaluate("nullable", omitted.output(), "{\"omitNulls\":true}".getBytes(StandardCharsets.UTF_8));
        assertEquals("fallback", observation(consumed).get("value").textValue());
    }

    @Test void applicationModuleRoundtripCanLosePrecisionEvenWithoutVersionChange() throws Exception {
        AdapterResult exact = evaluate("money", "{\"value\":\"12.345\"}");
        assertEquals("12.345", output(exact).get("value").textValue());
        AdapterResult rounded = evaluate("money", "{\"value\":\"12.345\"}", "{\"quantizeMoney\":true}");
        assertEquals("12.34", output(rounded).get("value").textValue());
        assertEquals("12.345", observation(rounded).get("value").textValue());
        AdapterResult consumed = adapter.evaluate("money", rounded.output(), new byte[0]);
        assertEquals("12.34", observation(consumed).get("value").textValue());
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
