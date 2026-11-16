package fr.demo.souscription.gateway.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Reads the committed contracts of {@code global/contracts/} and checks JSON values against the
 * schemas they declare (W7-T03).
 *
 * <p>The contracts reference {@code ../shared-schemas/*.json} and JSON pointers through
 * intermediate {@code $ref} nodes ({@code #/components/schemas/ProductSummary/properties/code},
 * where {@code ProductSummary} is itself a reference): {@link #deref(Path, JsonNode)} follows both.
 * The validator covers the JSON Schema keywords the contracts actually use — {@code type},
 * {@code required}, {@code properties}, {@code additionalProperties: false}, {@code items},
 * {@code oneOf}, {@code enum}, {@code pattern}, {@code format}, {@code minLength},
 * {@code maxLength}, {@code minimum}, {@code maximum} — so a payload drifting from its contract is
 * reported member by member.
 */
public final class ContractDocuments {

    /** Root of the committed contracts, relative to the module directory (Maven working dir). */
    public static final Path CONTRACTS = Path.of("..", "global", "contracts");

    public static final Path SHARED_SCHEMAS = CONTRACTS.resolve("shared-schemas");

    /** The provided contract. */
    public static final String PUBLIC_CONTRACT = "souscription-web-to-souscription-gateway";

    public static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Map<Path, JsonNode> CACHE = new ConcurrentHashMap<>();

    private static final Map<String, Pattern> FORMATS = Map.of(
            "uuid", Pattern.compile(
                    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"),
            "date", Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$"),
            "date-time", Pattern.compile(
                    "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$"),
            "uri", Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:\\S+$"));

    private ContractDocuments() {
    }

    /** A schema node together with the file its relative references resolve against. */
    public record Located(Path file, JsonNode node) {
    }

    /** One operation of a contract. */
    public record Operation(String method, String path, String operationId, JsonNode node,
                            List<JsonNode> parameters) {

        public String key() {
            return method.toUpperCase() + " " + path;
        }
    }

    public static Path contractFile(String contractId) {
        return CONTRACTS.resolve(contractId).resolve("openapi.yaml");
    }

    public static JsonNode load(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        return CACHE.computeIfAbsent(normalized, p -> parse(p));
    }

    /** Parses without caching: used for documents produced by the build itself. */
    public static JsonNode parse(Path file) {
        try {
            String text = Files.readString(file);
            String name = file.getFileName().toString();
            return name.endsWith(".yaml") || name.endsWith(".yml")
                    ? YAML.readTree(text) : JSON.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException("unreadable contract document " + file, e);
        }
    }

    public static JsonNode parseJson(String text) {
        try {
            return JSON.readTree(text);
        } catch (IOException e) {
            throw new AssertionError("not a JSON document: " + text, e);
        }
    }

    /** The path of {@code servers[0].url}, e.g. {@code /v1} or {@code /api/v1}. */
    public static String basePath(JsonNode document) {
        return URI.create(document.get("servers").get(0).get("url").asText()).getPath()
                .replaceAll("/$", "");
    }

    /** Every operation of a contract, keyed by {@code METHOD path} (path without base path). */
    public static Map<String, Operation> operations(Path file) {
        JsonNode document = load(file);
        Map<String, Operation> operations = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = document.get("paths").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> path = it.next();
            List<JsonNode> shared = resolvedParameters(file, path.getValue().get("parameters"));
            for (Iterator<Map.Entry<String, JsonNode>> m = path.getValue().fields(); m.hasNext(); ) {
                Map.Entry<String, JsonNode> verb = m.next();
                if (!HTTP_METHODS.contains(verb.getKey())) {
                    continue;
                }
                List<JsonNode> parameters = new ArrayList<>(shared);
                parameters.addAll(resolvedParameters(file, verb.getValue().get("parameters")));
                JsonNode id = verb.getValue().get("operationId");
                Operation operation = new Operation(verb.getKey(), path.getKey(),
                        id == null ? null : id.asText(), verb.getValue(), parameters);
                operations.put(operation.key(), operation);
            }
        }
        return operations;
    }

    private static List<JsonNode> resolvedParameters(Path file, JsonNode parameters) {
        List<JsonNode> resolved = new ArrayList<>();
        if (parameters != null) {
            parameters.forEach(p -> resolved.add(deref(file, p).node()));
        }
        return resolved;
    }

    /** Follows {@code $ref} nodes (relative files and JSON pointers) until a concrete node. */
    public static Located deref(Path file, JsonNode node) {
        Path current = file;
        JsonNode value = node;
        int hops = 0;
        while (value != null && value.isObject() && value.has("$ref")) {
            if (++hops > 32) {
                throw new AssertionError("reference cycle at " + node + " in " + file);
            }
            String ref = value.get("$ref").asText();
            int hash = ref.indexOf('#');
            String target = hash < 0 ? ref : ref.substring(0, hash);
            String pointer = hash < 0 ? "" : ref.substring(hash + 1);
            if (!target.isEmpty()) {
                current = current.getParent().resolve(target).normalize();
            }
            Located walked = walk(current, pointer);
            current = walked.file();
            value = walked.node();
        }
        return new Located(current, value);
    }

    /** Follows a JSON pointer, dereferencing every intermediate {@code $ref} node. */
    public static Located walk(Path file, String pointer) {
        Path current = file;
        JsonNode node = load(file);
        for (String raw : pointer.split("/")) {
            if (raw.isEmpty()) {
                continue;
            }
            Located located = deref(current, node);
            current = located.file();
            node = located.node();
            String token = raw.replace("~1", "/").replace("~0", "~");
            if (node == null || !node.has(token)) {
                throw new AssertionError("unresolvable pointer #" + pointer + " in " + file);
            }
            node = node.get(token);
        }
        return new Located(current, node);
    }

    /** The schema of the first media type declared by a response or request body. */
    public static Located contentSchema(Path file, JsonNode bodyOrResponse) {
        Located located = deref(file, bodyOrResponse);
        JsonNode content = located.node().get("content");
        if (content == null || !content.fields().hasNext()) {
            return null;
        }
        return new Located(located.file(), content.fields().next().getValue().get("schema"));
    }

    /** The media type of the first content entry of a response, or {@code null}. */
    public static String mediaType(Path file, JsonNode response) {
        JsonNode content = deref(file, response).node().get("content");
        return content == null || !content.fieldNames().hasNext() ? null
                : content.fieldNames().next();
    }

    /** Violations of {@code value} against {@code schema}; empty when the value conforms. */
    public static List<String> validate(Path file, JsonNode schema, JsonNode value, String where) {
        List<String> violations = new ArrayList<>();
        check(file, schema, value, where, violations);
        return violations;
    }

    private static void check(Path file, JsonNode schemaNode, JsonNode value, String where,
            List<String> violations) {
        Located located = deref(file, schemaNode);
        Path schemaFile = located.file();
        JsonNode schema = located.node();
        if (schema == null) {
            violations.add(where + ": unresolvable schema");
            return;
        }
        if (schema.has("oneOf")) {
            int matches = 0;
            for (JsonNode option : schema.get("oneOf")) {
                if (validate(schemaFile, option, value, where).isEmpty()) {
                    matches++;
                }
            }
            if (matches != 1) {
                violations.add(where + ": matches " + matches + " branches of oneOf, expected 1");
            }
            return;
        }
        String actual = typeOf(value);
        String expected = schema.has("type") ? schema.get("type").asText() : null;
        if (expected != null && !expected.equals(actual)
                && !("number".equals(expected) && "integer".equals(actual))) {
            violations.add(where + ": " + actual + " where the contract requires " + expected);
            return;
        }
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode allowed : schema.get("enum")) {
                found |= allowed.equals(value);
            }
            if (!found) {
                violations.add(where + ": " + value + " not in " + schema.get("enum"));
            }
        }
        switch (actual) {
            case "string" -> checkString(schema, value.asText(), where, violations);
            case "integer", "number" -> {
                if (schema.has("minimum") && value.decimalValue()
                        .compareTo(schema.get("minimum").decimalValue()) < 0) {
                    violations.add(where + ": below " + schema.get("minimum"));
                }
                if (schema.has("maximum") && value.decimalValue()
                        .compareTo(schema.get("maximum").decimalValue()) > 0) {
                    violations.add(where + ": above " + schema.get("maximum"));
                }
            }
            case "object" -> checkObject(schemaFile, schema, value, where, violations);
            case "array" -> {
                if (schema.has("items")) {
                    for (int i = 0; i < value.size(); i++) {
                        check(schemaFile, schema.get("items"), value.get(i), where + "[" + i + "]",
                                violations);
                    }
                }
            }
            default -> {
                // null: only the type check applies
            }
        }
    }

    private static void checkString(JsonNode schema, String text, String where,
            List<String> violations) {
        if (schema.has("maxLength") && text.length() > schema.get("maxLength").asInt()) {
            violations.add(where + ": longer than " + schema.get("maxLength"));
        }
        if (schema.has("minLength") && text.length() < schema.get("minLength").asInt()) {
            violations.add(where + ": shorter than " + schema.get("minLength"));
        }
        if (schema.has("pattern")
                && !Pattern.compile(schema.get("pattern").asText()).matcher(text).find()) {
            violations.add(where + ": '" + text + "' does not match " + schema.get("pattern"));
        }
        if (schema.has("format")) {
            Pattern format = FORMATS.get(schema.get("format").asText());
            if (format != null && !format.matcher(text).matches()) {
                violations.add(where + ": '" + text + "' is not a " + schema.get("format").asText());
            }
        }
    }

    private static void checkObject(Path file, JsonNode schema, JsonNode value, String where,
            List<String> violations) {
        JsonNode properties = schema.get("properties");
        if (schema.has("required")) {
            for (JsonNode member : schema.get("required")) {
                if (!value.has(member.asText())) {
                    violations.add(where + ": required member '" + member.asText() + "' missing");
                }
            }
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> member = it.next();
            if (properties != null && properties.has(member.getKey())) {
                check(file, properties.get(member.getKey()), member.getValue(),
                        where + "." + member.getKey(), violations);
            } else if (schema.has("additionalProperties")
                    && schema.get("additionalProperties").isBoolean()
                    && !schema.get("additionalProperties").asBoolean()) {
                violations.add(where + ": member '" + member.getKey()
                        + "' not allowed by the contract");
            }
        }
    }

    private static String typeOf(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "null";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        if (value.isIntegralNumber()) {
            return "integer";
        }
        if (value.isNumber()) {
            return "number";
        }
        if (value.isTextual()) {
            return "string";
        }
        return value.isArray() ? "array" : "object";
    }
}
