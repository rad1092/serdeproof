package demo;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.github.rad1092.serdeproof.api.AdapterResult;
import io.github.rad1092.serdeproof.api.RejectedInputException;
import io.github.rad1092.serdeproof.api.SerializerAdapter;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** A small application adapter, with actual DTOs and an explicit application module. */
public final class Jackson2Adapter implements SerializerAdapter {
    private static final ObjectMapper OBSERVATIONS = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public Jackson2Adapter() { }

    @Override
    public AdapterResult evaluate(String type, byte[] input, byte[] configuration) throws Exception {
        Options options = options(configuration); // A broken config is infrastructure, not rejected input.
        JsonMapper.Builder builder = JsonMapper.builder().addModule(moneyModule(options.quantizeMoney));
        if (options.decimalNumbers) builder.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        if (options.omitNulls) builder.serializationInclusion(JsonInclude.Include.NON_NULL);
        if (type.equals("record-visibility")) {
            // Historical #3906 shape; field visibility also makes roundtrip serialization meaningful.
            builder.visibility(PropertyAccessor.ALL, Visibility.NONE)
                    .visibility(PropertyAccessor.FIELD, Visibility.ANY)
                    .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        }
        ObjectMapper mapper = builder.build();
        Class<?> dtoType = switch (type) {
            case "primitive" -> PrimitiveValue.class;
            case "enum" -> EnumValue.class;
            case "date" -> DateValue.class;
            case "numbers" -> Numbers.class;
            case "untyped-number" -> UntypedNumber.class;
            case "nullable" -> NullableValue.class;
            case "money" -> MoneyValue.class;
            case "record-visibility" -> HiddenRecord.class;
            default -> throw new IllegalArgumentException("Unknown demo type");
        };
        Object dto;
        try {
            dto = mapper.readValue(input, dtoType);
        } catch (InvalidDefinitionException error) {
            throw error; // A broken DTO/module is not an ordinary payload rejection.
        } catch (JsonProcessingException error) {
            throw new RejectedInputException("Payload rejected");
        }
        if (dto == null) throw new RejectedInputException("A DTO object is required");
        return new AdapterResult(mapper.writeValueAsBytes(dto), OBSERVATIONS.writeValueAsBytes(observe(dto)));
    }

    private static Map<String, Object> observe(Object dto) {
        Map<String, Object> observation = new LinkedHashMap<>();
        if (dto instanceof PrimitiveValue value) observation.put("value", value.value);
        else if (dto instanceof EnumValue value) observation.put("value", value.value == null ? null : value.value.name());
        else if (dto instanceof DateValue value) observation.put("value", value.value == null ? null : value.value.toInstant().toString());
        else if (dto instanceof Numbers value) {
            observation.put("integer", value.integer);
            observation.put("decimal", value.decimal == null ? null : value.decimal.stripTrailingZeros());
        } else if (dto instanceof UntypedNumber value) {
            observation.put("value", value.value);
            observation.put("javaType", value.value == null ? null : value.value.getClass().getSimpleName());
        } else if (dto instanceof NullableValue value) observation.put("value", value.value);
        else if (dto instanceof MoneyValue value) observation.put("value", value.value == null ? null : value.value.amount.stripTrailingZeros().toPlainString());
        else if (dto instanceof HiddenRecord value) {
            observation.put("string", value.string());
            observation.put("integer", value.integer());
        } else throw new IllegalArgumentException("Unknown DTO");
        return observation;
    }

    private static Options options(byte[] configuration) throws IOException {
        if (configuration.length == 0) return new Options(false, false, false);
        Map<?, ?> config = OBSERVATIONS.readValue(configuration, Map.class);
        if (config == null) throw new IllegalArgumentException("Config must be an object");
        Set<String> allowed = Set.of("omitNulls", "decimalNumbers", "quantizeMoney");
        for (Map.Entry<?, ?> entry : config.entrySet()) {
            if (!allowed.contains(entry.getKey()) || !(entry.getValue() instanceof Boolean)) {
                throw new IllegalArgumentException("Unknown config key or nonboolean value");
            }
        }
        return new Options(Boolean.TRUE.equals(config.get("omitNulls")),
                Boolean.TRUE.equals(config.get("decimalNumbers")), Boolean.TRUE.equals(config.get("quantizeMoney")));
    }

    private static SimpleModule moneyModule(boolean quantize) {
        SimpleModule module = new SimpleModule("application-money");
        module.addSerializer(Money.class, new JsonSerializer<Money>() {
            @Override public void serialize(Money value, JsonGenerator generator, SerializerProvider context) throws IOException {
                BigDecimal amount = quantize ? value.amount.setScale(2, RoundingMode.HALF_EVEN) : value.amount;
                generator.writeString(amount.toPlainString());
            }
        });
        module.addDeserializer(Money.class, new JsonDeserializer<Money>() {
            @Override public Money deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                if (!parser.hasToken(JsonToken.VALUE_STRING)) return (Money) context.handleUnexpectedToken(Money.class, parser);
                String text = parser.getText();
                try { return new Money(new BigDecimal(text)); }
                catch (NumberFormatException error) { throw context.weirdStringException(text, Money.class, "Expected a decimal string"); }
            }
        });
        return module;
    }

    private record Options(boolean omitNulls, boolean decimalNumbers, boolean quantizeMoney) { }
    public static final class PrimitiveValue { public int value = 7; }
    public enum Status { READY; @Override public String toString() { return "ready"; } }
    public static final class EnumValue { public Status value = Status.READY; }
    public static final class DateValue { public Date value; }
    public static final class Numbers { public BigInteger integer; public BigDecimal decimal; }
    public static final class UntypedNumber { public Object value; }
    public static final class NullableValue { public String value = "fallback"; }
    public record Money(BigDecimal amount) { }
    public static final class MoneyValue { public Money value; }
    public record HiddenRecord(String string, int integer) { }
}
