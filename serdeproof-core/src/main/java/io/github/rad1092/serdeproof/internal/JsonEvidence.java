package io.github.rad1092.serdeproof.internal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.NumericNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Strict, value-free evidence about two JSON documents; not a semantic-equivalence oracle. */
public final class JsonEvidence {
    private static final int MAX_POINTER_LENGTH = 8_192;
    private static final int MAX_DIFFERENCE_CHARACTERS = 4 * 1_048_576;
    private static final int MAX_DOCUMENT_NODES = 100_000;
    private static final JsonFactory FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(128)
                    .maxNumberLength(1_000)
                    .maxStringLength(1_048_576)
                    .maxNameLength(65_536)
                    .build())
            .build();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private JsonEvidence() { }

    /** A kind and RFC 6901 pointer. The root pointer is the empty string. */
    public record Difference(String kind, String pointer) { }

    /** Exceeding the evidence budget must fail the run rather than silently truncate it. */
    public static final class DifferenceLimitException extends IOException {
        private static final long serialVersionUID = 1L;

        public DifferenceLimitException() {
            super("JSON difference limit exceeded");
        }
    }

    /**
     * Reads exactly one strict UTF-8 JSON document without a leading byte-order mark.
     * Malformed UTF-8 and other encodings are rejected. Numbers retain their original JSON token in
     * {@link JsonNode#asText()}; numeric comparison never rounds through binary floating point.
     * Parsing is limited to 128 nested containers, 1,000-character numbers, 1 MiB strings,
     * 64 KiB field names, and 100,000 total containers/scalars per document.
     * Callers must separately bound the document byte length.
     * Failure messages never contain the document, keys, or parser diagnostics.
     */
    public static JsonNode parse(byte[] bytes) throws IOException {
        if (bytes == null) {
            throw new IOException("Invalid JSON document");
        }
        try (JsonParser parser = FACTORY.createParser(decodeUtf8(bytes))) {
            JsonToken token = parser.nextToken();
            if (token == null) {
                throw new IOException("Invalid JSON document");
            }
            JsonNode result = read(parser, token, new int[1]);
            if (parser.nextToken() != null) {
                throw new IOException("Invalid JSON document");
            }
            return result;
        } catch (IOException | RuntimeException failure) {
            // Jackson exceptions may contain fixture values and source snippets; do not chain them.
            throw new IOException("Invalid JSON document");
        }
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            throw new IOException("Invalid JSON document");
        }
        // The character parser cannot silently auto-detect UTF-16/32 from input bytes.
        return text;
    }

    /**
     * Compares object members in sorted key order and array members in index order.
     * DATE/ENUM hints label changed scalar values at their exact pointer; they do not parse
     * dates, infer enum rules, or suppress lexical numeric differences. Structural changes
     * retain MISSING, NULL, and TYPE kinds. Returned evidence never contains input values.
     * A walked pointer exceeding 8,192 characters, more than {@code maxDifferences}
     * findings, or over 4 MiB of combined kind/pointer characters fails closed.
     */
    public static List<Difference> compare(byte[] left, byte[] right,
            Map<String, String> hints, int maxDifferences) throws IOException {
        if (maxDifferences < 0) {
            throw new IOException("Invalid JSON difference limit");
        }
        JsonNode leftNode = parse(left);
        JsonNode rightNode = parse(right);
        List<Difference> result = new ArrayList<>();
        compareNodes(leftNode, rightNode, "", hints == null ? Map.of() : hints,
                maxDifferences, result, new int[1]);
        return List.copyOf(result);
    }

    private static JsonNode read(JsonParser parser, JsonToken token, int[] nodes) throws IOException {
        if (++nodes[0] > MAX_DOCUMENT_NODES) {
            throw new IOException("Invalid JSON document");
        }
        return switch (token) {
            case START_OBJECT -> {
                ObjectNode result = NODES.objectNode();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) {
                        throw new IOException("Invalid JSON document");
                    }
                    String name = parser.currentName();
                    JsonToken value = parser.nextToken();
                    if (value == null) {
                        throw new IOException("Invalid JSON document");
                    }
                    result.set(name, read(parser, value, nodes));
                }
                yield result;
            }
            case START_ARRAY -> {
                ArrayNode result = NODES.arrayNode();
                JsonToken value;
                while ((value = parser.nextToken()) != JsonToken.END_ARRAY) {
                    if (value == null) {
                        throw new IOException("Invalid JSON document");
                    }
                    result.add(read(parser, value, nodes));
                }
                yield result;
            }
            case VALUE_STRING -> TextNode.valueOf(parser.getText());
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> new LexicalNumberNode(parser.getText());
            case VALUE_TRUE -> BooleanNode.TRUE;
            case VALUE_FALSE -> BooleanNode.FALSE;
            case VALUE_NULL -> NullNode.instance;
            default -> throw new IOException("Invalid JSON document");
        };
    }

    private static void compareNodes(JsonNode left, JsonNode right, String pointer,
            Map<String, String> hints, int limit, List<Difference> result, int[] characters) throws IOException {
        if (pointer.length() > MAX_POINTER_LENGTH) {
            throw new DifferenceLimitException();
        }
        if (left == null || right == null) {
            add(result, limit, "MISSING", pointer, characters);
        } else if (left.isNull() || right.isNull()) {
            if (left.isNull() != right.isNull()) {
                add(result, limit, "NULL", pointer, characters);
            }
        } else if (left.getNodeType() != right.getNodeType()) {
            add(result, limit, "TYPE", pointer, characters);
        } else if (left.isObject()) {
            TreeSet<String> names = new TreeSet<>();
            left.fieldNames().forEachRemaining(names::add);
            right.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                compareNodes(left.get(name), right.get(name), pointer + "/" + escape(name),
                        hints, limit, result, characters);
            }
        } else if (left.isArray()) {
            for (int index = 0; index < Math.max(left.size(), right.size()); index++) {
                compareNodes(left.get(index), right.get(index), pointer + "/" + index,
                        hints, limit, result, characters);
            }
        } else if (left.isNumber()) {
            LexicalNumberNode first = (LexicalNumberNode) left;
            LexicalNumberNode second = (LexicalNumberNode) right;
            if (!first.normalized.equals(second.normalized)) {
                add(result, limit, valueKind("NUMERIC_VALUE", pointer, hints), pointer, characters);
            } else if (!first.lexical.equals(second.lexical)) {
                add(result, limit, "NUMERIC_LEXICAL", pointer, characters);
            }
        } else if (!left.equals(right)) {
            add(result, limit, valueKind("VALUE", pointer, hints), pointer, characters);
        }
    }

    private static String valueKind(String fallback, String pointer, Map<String, String> hints) {
        String hint = hints.get(pointer);
        return "DATE".equals(hint) || "ENUM".equals(hint) ? hint : fallback;
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static void add(List<Difference> result, int limit, String kind, String pointer, int[] characters)
            throws DifferenceLimitException {
        int size = kind.length() + pointer.length();
        if (result.size() >= limit || characters[0] > MAX_DIFFERENCE_CHARACTERS - size) {
            throw new DifferenceLimitException();
        }
        characters[0] += size;
        result.add(new Difference(kind, pointer));
    }

    /**
     * Coefficient digits and an arbitrary-precision decimal exponent form a canonical value.
     * This avoids allocating an exponent-sized integer for inputs such as 1e999999999999.
     */
    private record NormalizedNumber(boolean negative, String digits, BigInteger exponent) {
        private static NormalizedNumber from(String token) {
            boolean negative = token.charAt(0) == '-';
            int exponentAt = Math.max(token.indexOf('e'), token.indexOf('E'));
            String coefficient = exponentAt < 0 ? token : token.substring(0, exponentAt);
            BigInteger exponent = exponentAt < 0 ? BigInteger.ZERO
                    : new BigInteger(token.substring(exponentAt + 1));
            if (negative) {
                coefficient = coefficient.substring(1);
            }
            int decimalAt = coefficient.indexOf('.');
            if (decimalAt >= 0) {
                exponent = exponent.subtract(BigInteger.valueOf(coefficient.length() - decimalAt - 1));
                coefficient = coefficient.substring(0, decimalAt) + coefficient.substring(decimalAt + 1);
            }
            int start = 0;
            while (start < coefficient.length() && coefficient.charAt(start) == '0') {
                start++;
            }
            if (start == coefficient.length()) {
                return new NormalizedNumber(false, "0", BigInteger.ZERO);
            }
            int end = coefficient.length();
            while (coefficient.charAt(end - 1) == '0') {
                end--;
            }
            exponent = exponent.add(BigInteger.valueOf(coefficient.length() - end));
            return new NormalizedNumber(negative, coefficient.substring(start, end), exponent);
        }
    }

    /** Numeric accessors retain Java's numeric range limits; evidence comparison does not. */
    private static final class LexicalNumberNode extends NumericNode {
        private static final long serialVersionUID = 1L;
        private final String lexical;
        private final boolean integral;
        private final NormalizedNumber normalized;

        private LexicalNumberNode(String lexical) {
            this.lexical = lexical;
            this.integral = lexical.indexOf('.') < 0 && lexical.indexOf('e') < 0 && lexical.indexOf('E') < 0;
            this.normalized = NormalizedNumber.from(lexical);
        }

        @Override public JsonToken asToken() {
            return integral ? JsonToken.VALUE_NUMBER_INT : JsonToken.VALUE_NUMBER_FLOAT;
        }
        @Override public JsonParser.NumberType numberType() {
            return integral ? JsonParser.NumberType.BIG_INTEGER : JsonParser.NumberType.BIG_DECIMAL;
        }
        @Override public boolean isIntegralNumber() { return integral; }
        @Override public boolean isFloatingPointNumber() { return !integral; }
        @Override public boolean isBigInteger() { return integral; }
        @Override public boolean isBigDecimal() { return !integral; }
        @Override public Number numberValue() { return integral ? new BigInteger(lexical) : decimalValue(); }
        @Override public int intValue() { return decimalValue().intValue(); }
        @Override public long longValue() { return decimalValue().longValue(); }
        @Override public double doubleValue() { return Double.parseDouble(lexical); }
        @Override public BigDecimal decimalValue() { return new BigDecimal(lexical); }
        @Override public BigInteger bigIntegerValue() { return integral ? new BigInteger(lexical) : decimalValue().toBigInteger(); }
        @Override public boolean canConvertToInt() { return within(BigDecimal.valueOf(Integer.MIN_VALUE), BigDecimal.valueOf(Integer.MAX_VALUE)); }
        @Override public boolean canConvertToLong() { return within(BigDecimal.valueOf(Long.MIN_VALUE), BigDecimal.valueOf(Long.MAX_VALUE)); }
        @Override public String asText() { return lexical; }
        @Override public void serialize(JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeNumber(lexical);
        }
        @Override public boolean equals(Object other) {
            return other instanceof LexicalNumberNode number && lexical.equals(number.lexical);
        }
        @Override public int hashCode() { return Objects.hash(lexical); }

        private boolean within(BigDecimal minimum, BigDecimal maximum) {
            try {
                BigDecimal value = decimalValue();
                return value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
            } catch (ArithmeticException | NumberFormatException outsideJavaRange) {
                return false;
            }
        }
    }
}
