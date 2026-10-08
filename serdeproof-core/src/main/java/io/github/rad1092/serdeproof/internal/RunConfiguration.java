package io.github.rad1092.serdeproof.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rad1092.serdeproof.runtime.JvmAdapter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validated manifest. All paths are resolved relative to the manifest. */
public record RunConfiguration(Adapter baseline, Adapter candidate, List<Fixture> fixtures,
        List<Rule> rules, Limits limits, String manifestHash) {
    public record Adapter(JvmAdapter.Spec spec, byte[] configuration) {}
    public record Fixture(String id, String type, Path file, Map<String, String> hints) {}
    public record Rule(String id, String fixture, String kind, String direction, String pointer,
                       String reason, LocalDate expires) {
        public String scope() { return fixture + '\u0000' + kind + '\u0000' + direction + '\u0000' + pointer; }
    }
    public record Limits(int maxBytes, long timeoutMillis, int maxFixtures, int maxDifferences,
                         long maxCorpusBytes) {
        public JvmAdapter.Limits process() { return new JvmAdapter.Limits(maxBytes, timeoutMillis); }
    }
    public static final class Invalid extends IOException {
        private final String code;
        public Invalid(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }

    public static RunConfiguration load(Path manifest) throws IOException {
        Path source = manifest.toAbsolutePath().normalize();
        byte[] raw = boundedRead(source, 1024 * 1024);
        JsonNode root;
        try { root = JsonEvidence.parse(raw); }
        catch (IOException e) { throw new Invalid("MANIFEST_INVALID_JSON"); }
        fields(root, Set.of("baseline", "candidate", "fixtures", "rules", "limits"));
        Path base = source.getParent();
        JsonNode l = root.get("limits");
        if (l != null) fields(l, Set.of("maxBytes", "timeoutMillis", "maxFixtures", "maxDifferences", "maxCorpusBytes"));
        Limits limits = new Limits((int) number(l, "maxBytes", 1048576, 16, 16777216),
                number(l, "timeoutMillis", 10000, 1, 300000),
                (int) number(l, "maxFixtures", 1000, 1, 10000),
                (int) number(l, "maxDifferences", 10000, 1, 100000),
                number(l, "maxCorpusBytes", 268435456, 16, 1073741824));
        Adapter baseline = adapter(required(root, "baseline"), base, limits);
        Adapter candidate = adapter(required(root, "candidate"), base, limits);
        JsonNode inputs = required(root, "fixtures");
        if (!inputs.isArray() || inputs.isEmpty() || inputs.size() > limits.maxFixtures) throw new Invalid("FIXTURE_COUNT_INVALID");
        List<Fixture> fixtures = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        long total = 0;
        for (JsonNode f : inputs) {
            fields(f, Set.of("id", "type", "file", "hints"));
            String id = identifier(f, "id");
            if (!ids.add(id)) throw new Invalid("FIXTURE_ID_DUPLICATE");
            String type = string(f, "type", 512);
            Path file = path(base, string(f, "file", 8192));
            long size = checkedSize(file, limits.maxBytes);
            total += size;
            if (total > limits.maxCorpusBytes) throw new Invalid("CORPUS_SIZE_LIMIT");
            Map<String, String> hints = new LinkedHashMap<>();
            JsonNode h = f.get("hints");
            if (h != null) {
                if (!h.isObject() || h.size() > 1000) throw new Invalid("HINTS_INVALID");
                var names = h.fieldNames();
                while (names.hasNext()) {
                    String pointer = names.next();
                    if (!validPointer(pointer) || !h.get(pointer).isTextual()
                            || !Set.of("DATE", "ENUM").contains(h.get(pointer).textValue())) throw new Invalid("HINTS_INVALID");
                    hints.put(pointer, h.get(pointer).textValue());
                }
            }
            fixtures.add(new Fixture(id, type, file, Map.copyOf(hints)));
        }
        List<Rule> rules = new ArrayList<>();
        JsonNode rs = root.get("rules");
        if (rs != null) {
            if (!rs.isArray() || rs.size() > 10000) throw new Invalid("RULES_INVALID");
            Set<String> ruleIds = new HashSet<>(), scopes = new HashSet<>();
            for (JsonNode r : rs) {
                fields(r, Set.of("id", "fixture", "kind", "direction", "pointer", "reason", "expires"));
                String id = identifier(r, "id");
                String fixture = identifier(r, "fixture");
                if (!ruleIds.add(id) || !ids.contains(fixture)) throw new Invalid("RULE_SCOPE_INVALID");
                String kind = string(r, "kind", 64);
                if (!kind.matches("(?:ACCEPTANCE|(?:OUTPUT|OBSERVATION|ROUNDTRIP)_(?:MISSING|NULL|TYPE|VALUE|NUMERIC_VALUE|NUMERIC_LEXICAL|DATE|ENUM)|ROUNDTRIP_ACCEPTANCE)")) throw new Invalid("RULE_KIND_INVALID");
                String direction = string(r, "direction", 64);
                if (!direction.matches("(?:baseline|candidate)-to-(?:baseline|candidate)")) throw new Invalid("RULE_DIRECTION_INVALID");
                String pointer = exactString(r, "pointer", 8192, true);
                if (!validPointer(pointer)) throw new Invalid("RULE_POINTER_INVALID");
                LocalDate expiry;
                try { expiry = LocalDate.parse(string(r, "expires", 10)); }
                catch (DateTimeParseException e) { throw new Invalid("RULE_EXPIRY_INVALID"); }
                Rule rule = new Rule(id, fixture, kind, direction, pointer, string(r, "reason", 2048), expiry);
                if (!scopes.add(rule.scope())) throw new Invalid("RULE_SCOPE_DUPLICATE");
                rules.add(rule);
            }
        }
        fixtures.sort(java.util.Comparator.comparing(Fixture::id));
        rules.sort(java.util.Comparator.comparing(Rule::id));
        return new RunConfiguration(baseline, candidate, List.copyOf(fixtures), List.copyOf(rules), limits, Fingerprints.sha256(raw));
    }

    private static Adapter adapter(JsonNode a, Path base, Limits limits) throws IOException {
        fields(a, Set.of("adapterClass", "classpath", "java", "jvmArgs", "configuration"));
        String clazz = string(a, "adapterClass", 1024);
        if (!clazz.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) throw new Invalid("ADAPTER_CLASS_INVALID");
        JsonNode cp = required(a, "classpath");
        if (!cp.isArray() || cp.isEmpty() || cp.size() > 256) throw new Invalid("CLASSPATH_INVALID");
        List<Path> classpath = new ArrayList<>();
        for (JsonNode p : cp) {
            if (!p.isTextual() || p.textValue().isBlank() || p.textValue().length() > 8192) throw new Invalid("CLASSPATH_INVALID");
            Path entry = path(base, p.textValue());
            if (!Files.exists(entry)) throw new Invalid("CLASSPATH_NOT_FOUND");
            classpath.add(entry);
        }
        Path java = a.has("java") ? path(base, string(a, "java", 8192)) : null;
        if (java != null && !Files.isRegularFile(java)) throw new Invalid("JAVA_NOT_FOUND");
        List<String> args = new ArrayList<>();
        JsonNode js = a.get("jvmArgs");
        if (js != null) {
            if (!js.isArray() || js.size() > 64) throw new Invalid("JVM_ARGUMENTS_INVALID");
            for (JsonNode arg : js) {
                if (!arg.isTextual() || arg.textValue().length() > 4096 || arg.textValue().indexOf(0) >= 0) throw new Invalid("JVM_ARGUMENTS_INVALID");
                args.add(arg.textValue());
            }
        }
        byte[] config = a.has("configuration") ? boundedRead(path(base, string(a, "configuration", 8192)), limits.maxBytes) : new byte[]{'{', '}'};
        return new Adapter(new JvmAdapter.Spec(clazz, List.copyOf(classpath), java, List.copyOf(args)), config);
    }

    public static byte[] boundedRead(Path path, int limit) throws IOException {
        checkedSize(path, limit);
        try (var in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) throw new Invalid("INPUT_SIZE_LIMIT");
            return bytes;
        }
    }
    private static long checkedSize(Path path, int limit) throws IOException {
        if (!Files.isRegularFile(path)) throw new Invalid("INPUT_NOT_REGULAR_FILE");
        long size = Files.size(path);
        if (size > limit) throw new Invalid("INPUT_SIZE_LIMIT");
        return size;
    }
    private static Path path(Path base, String value) throws Invalid {
        try { return base.resolve(value).toAbsolutePath().normalize(); }
        catch (RuntimeException e) { throw new Invalid("PATH_INVALID"); }
    }
    private static boolean validPointer(String pointer) {
        return pointer.length() <= 8192 && (pointer.isEmpty() || pointer.startsWith("/")) && !pointer.matches("(?s).*~(?:[^01]|$).*" );
    }
    private static void fields(JsonNode n, Set<String> allowed) throws Invalid {
        if (n == null || !n.isObject()) throw new Invalid("MANIFEST_OBJECT_REQUIRED");
        Iterator<String> fields = n.fieldNames();
        while (fields.hasNext()) if (!allowed.contains(fields.next())) throw new Invalid("MANIFEST_UNKNOWN_FIELD");
    }
    private static JsonNode required(JsonNode n, String key) throws Invalid {
        JsonNode value = n.get(key);
        if (value == null || value.isNull()) throw new Invalid("MANIFEST_FIELD_REQUIRED");
        return value;
    }
    private static String identifier(JsonNode n, String key) throws Invalid {
        String value = string(n, key, 128);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw new Invalid("IDENTIFIER_INVALID");
        return value;
    }
    private static String string(JsonNode n, String key, int max) throws Invalid { return exactString(n, key, max, false); }
    private static String exactString(JsonNode n, String key, int max, boolean emptyAllowed) throws Invalid {
        JsonNode value = required(n, key);
        if (!value.isTextual() || value.textValue().length() > max || value.textValue().indexOf(0) >= 0
                || (!emptyAllowed && value.textValue().isBlank())) throw new Invalid("MANIFEST_STRING_INVALID");
        return value.textValue();
    }
    private static long number(JsonNode n, String key, long fallback, long min, long max) throws Invalid {
        if (n == null || !n.has(key)) return fallback;
        JsonNode value = n.get(key);
        try {
            if (!value.isNumber() || !value.asText().matches("[0-9]+")) throw new NumberFormatException();
            long parsed = Long.parseLong(value.asText());
            if (parsed < min || parsed > max) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) { throw new Invalid("LIMIT_INVALID"); }
    }
}
