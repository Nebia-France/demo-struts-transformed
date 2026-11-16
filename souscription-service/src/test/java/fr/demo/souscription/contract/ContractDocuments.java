package fr.demo.souscription.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Lecture des contrats geles de {@code global/contracts/} depuis le disque (plan 42).
 *
 * <p>Resout les {@code $ref} entre fichiers ({@code ../shared-schemas/X.json},
 * {@code X.json#/properties/y}) et internes ({@code #/components/...}), y compris lorsqu'un
 * segment de pointeur traverse un noeud qui n'est lui-meme qu'un {@code $ref}. Fournit aussi un
 * validateur JSON Schema reduit au sous-ensemble utilise par les contrats (type, required,
 * properties, additionalProperties, pattern, bornes, enum, items, oneOf). Les fichiers ne sont
 * jamais ecrits : les contrats sont la reference, le service s'y conforme.
 */
final class ContractDocuments {

    static final Path CONTRACTS = Path.of("..", "global", "contracts").toAbsolutePath().normalize();

    static final Path SHARED_SCHEMAS = CONTRACTS.resolve("shared-schemas");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static final Map<Path, JsonNode> CACHE = new ConcurrentHashMap<>();

    private ContractDocuments() {
    }

    /** Noeud de schema et fichier qui le porte, base de resolution de ses {@code $ref}. */
    record Located(Path file, JsonNode node) {
    }

    static Path contract(String communicationId, String file) {
        return CONTRACTS.resolve(communicationId).resolve(file);
    }

    static JsonNode read(Path file) {
        return CACHE.computeIfAbsent(file.toAbsolutePath().normalize(), path -> {
            try {
                String text = Files.readString(path);
                return path.toString().endsWith(".json") ? JSON.readTree(text) : YAML.readTree(text);
            } catch (IOException unreadable) {
                throw new UncheckedIOException("contrat illisible : " + path, unreadable);
            }
        });
    }

    static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("JSON illisible", unreadable);
        }
    }

    /** Fichier cible d'une reference externe, ou le fichier courant pour une reference interne. */
    static Path targetFile(Path base, String ref) {
        int hash = ref.indexOf('#');
        String file = hash < 0 ? ref : ref.substring(0, hash);
        return file.isEmpty() ? base : base.getParent().resolve(file).normalize();
    }

    /** Resout une reference relative au fichier {@code base}. */
    static Located resolve(Path base, String ref) {
        Path file = targetFile(base, ref);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("reference non resolue : " + ref + " depuis " + base);
        }
        int hash = ref.indexOf('#');
        String pointer = hash < 0 ? "" : ref.substring(hash + 1);
        Located current = deref(new Located(file, read(file)));
        if (pointer.isEmpty() || "/".equals(pointer)) {
            return current;
        }
        for (String raw : pointer.substring(1).split("/")) {
            String segment = raw.replace("~1", "/").replace("~0", "~");
            current = deref(current);
            JsonNode next = current.node().path(segment);
            if (next.isMissingNode()) {
                throw new IllegalStateException("pointeur non resolu : " + ref + " depuis " + base);
            }
            current = new Located(current.file(), next);
        }
        return deref(current);
    }

    /** Suit les {@code $ref} jusqu'a un noeud concret. */
    static Located deref(Located located) {
        Located current = located;
        for (int hops = 0; current.node().isObject() && current.node().has("$ref"); hops++) {
            if (hops > 32) {
                throw new IllegalStateException("reference circulaire depuis " + located.file());
            }
            current = resolve(current.file(), current.node().get("$ref").asText());
        }
        return current;
    }

    /** Toutes les valeurs {@code $ref} d'un document, dans l'ordre de parcours. */
    static List<String> refs(JsonNode node) {
        List<String> found = new ArrayList<>();
        collect(node, found);
        return found;
    }

    private static void collect(JsonNode node, List<String> found) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual()) {
                found.add(ref.asText());
            }
            node.fields().forEachRemaining(entry -> collect(entry.getValue(), found));
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, found));
        }
    }

    /** Valide {@code instance} contre {@code schema}. Liste vide si conforme. */
    static List<String> validate(Located schema, JsonNode instance) {
        List<String> errors = new ArrayList<>();
        validate(schema, instance, "$", errors);
        return errors;
    }

    private static void validate(Located raw, JsonNode value, String at, List<String> errors) {
        Located schema = deref(raw);
        JsonNode s = schema.node();
        if (s.has("oneOf")) {
            boolean matched = false;
            for (JsonNode alternative : s.get("oneOf")) {
                if (validate(new Located(schema.file(), alternative), value).isEmpty()) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                errors.add(at + " : aucune alternative oneOf ne correspond");
            }
        }
        if (s.has("type") && !typeMatches(s.get("type"), value)) {
            errors.add(at + " : type attendu " + s.get("type") + ", recu " + value.getNodeType());
            return;
        }
        if (s.has("enum")) {
            boolean listed = false;
            for (JsonNode allowed : s.get("enum")) {
                listed |= allowed.equals(value);
            }
            if (!listed) {
                errors.add(at + " : valeur hors enum " + s.get("enum") + " : " + value);
            }
        }
        if (value.isTextual()) {
            String text = value.asText();
            if (s.has("pattern") && !Pattern.compile(s.get("pattern").asText()).matcher(text).find()) {
                errors.add(at + " : '" + text + "' ne respecte pas " + s.get("pattern").asText());
            }
            if (s.has("maxLength") && text.codePointCount(0, text.length()) > s.get("maxLength").asInt()) {
                errors.add(at + " : longueur > " + s.get("maxLength").asInt());
            }
            if (s.has("minLength") && text.codePointCount(0, text.length()) < s.get("minLength").asInt()) {
                errors.add(at + " : longueur < " + s.get("minLength").asInt());
            }
        }
        if (value.isNumber()) {
            if (s.has("minimum") && value.decimalValue().compareTo(s.get("minimum").decimalValue()) < 0) {
                errors.add(at + " : " + value + " < minimum " + s.get("minimum"));
            }
            if (s.has("maximum") && value.decimalValue().compareTo(s.get("maximum").decimalValue()) > 0) {
                errors.add(at + " : " + value + " > maximum " + s.get("maximum"));
            }
        }
        if (value.isObject()) {
            if (s.has("required")) {
                for (JsonNode name : s.get("required")) {
                    if (!value.has(name.asText())) {
                        errors.add(at + " : champ requis absent '" + name.asText() + "'");
                    }
                }
            }
            JsonNode properties = s.path("properties");
            boolean closed = s.has("additionalProperties")
                    && s.get("additionalProperties").isBoolean()
                    && !s.get("additionalProperties").asBoolean();
            for (Iterator<String> it = value.fieldNames(); it.hasNext(); ) {
                String name = it.next();
                if (properties.has(name)) {
                    validate(new Located(schema.file(), properties.get(name)), value.get(name),
                            at + "." + name, errors);
                } else if (closed) {
                    errors.add(at + " : propriete non declaree '" + name
                            + "' (additionalProperties: false)");
                }
            }
        }
        if (value.isArray() && s.has("items")) {
            for (int i = 0; i < value.size(); i++) {
                validate(new Located(schema.file(), s.get("items")), value.get(i),
                        at + "[" + i + "]", errors);
            }
        }
    }

    private static boolean typeMatches(JsonNode type, JsonNode value) {
        if (type.isArray()) {
            for (JsonNode candidate : type) {
                if (typeMatches(candidate, value)) {
                    return true;
                }
            }
            return false;
        }
        return switch (type.asText()) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "null" -> value.isNull();
            default -> false;
        };
    }
}
