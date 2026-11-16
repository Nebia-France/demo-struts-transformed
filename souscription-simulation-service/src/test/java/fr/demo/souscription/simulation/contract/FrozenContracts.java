package fr.demo.souscription.simulation.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Read-only access to the frozen inputs under {@code global/contracts/} (plan 32).
 *
 * <p>The contracts are an input, never an output: nothing here writes, and a divergence is fixed in
 * this service rather than by editing a contract. The helper exists so the verification test states
 * each contracted figure <em>once</em>, read from the file, instead of restating it as a literal
 * that could drift from the agreement the consumers build against.
 */
final class FrozenContracts {

    /** The two communications this application provides, and the only ones it may appear in. */
    static final String GATEWAY_CONTRACT = "souscription-gateway-to-souscription-simulation-service";
    static final String SERVICE_CONTRACT = "souscription-service-to-souscription-simulation-service";
    static final String APPLICATION = "souscription-simulation-service";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FrozenContracts() {
    }

    /**
     * Resolves a repository-relative path from either the module directory (Surefire working
     * directory) or the reactor root, so the test runs the same way from the IDE and the pipeline.
     */
    static Path path(String relative) {
        Path direct = Path.of(relative);
        if (Files.exists(direct)) {
            return direct;
        }
        Path fromModule = Path.of("..").resolve(relative).normalize();
        if (!Files.exists(fromModule)) {
            throw new IllegalStateException(
                    "frozen input not found: " + direct.toAbsolutePath() + " nor " + fromModule.toAbsolutePath());
        }
        return fromModule;
    }

    static JsonNode json(String relative) {
        Path file = path(relative);
        try {
            return MAPPER.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("unreadable frozen input: " + file, e);
        }
    }

    static JsonNode parse(String rawBody) {
        try {
            return MAPPER.readTree(rawBody);
        } catch (IOException e) {
            throw new UncheckedIOException("response body is not JSON: " + rawBody, e);
        }
    }

    static JsonNode registry() {
        return json("global/contracts/INTER_APPLICATION_CONTRACTS.json");
    }

    static JsonNode resilience(String communicationId) {
        return json("global/contracts/" + communicationId + "/resilience-contract.json");
    }

    static JsonNode security(String communicationId) {
        return json("global/contracts/" + communicationId + "/security-policy.json");
    }

    /**
     * A shared schema with its file-relative {@code $ref}s inlined, so it can be handed to a
     * validator as a single self-contained document. Only the references are resolved: no keyword
     * is added, removed or relaxed.
     */
    static JsonNode sharedSchema(String name) {
        String relative = "global/contracts/shared-schemas/" + name;
        return inline(json(relative), path(relative).toAbsolutePath().getParent());
    }

    private static JsonNode inline(JsonNode node, Path baseDir) {
        if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                array.set(i, inline(array.get(i), baseDir));
            }
            return array;
        }
        if (!(node instanceof ObjectNode object)) {
            return node;
        }
        JsonNode ref = object.get("$ref");
        if (ref != null && ref.isTextual() && !ref.asText().startsWith("#")) {
            return resolveFileRef(ref.asText(), baseDir);
        }
        for (String field : object.properties().stream().map(java.util.Map.Entry::getKey).toList()) {
            object.set(field, inline(object.get(field), baseDir));
        }
        return object;
    }

    /** Resolves {@code Other.json#/properties/x} against the sibling schema file. */
    private static JsonNode resolveFileRef(String ref, Path baseDir) {
        int hash = ref.indexOf('#');
        String file = hash < 0 ? ref : ref.substring(0, hash);
        String fragment = hash < 0 ? "" : ref.substring(hash + 1);
        Path target = baseDir.resolve(file).normalize();
        JsonNode document;
        try {
            document = MAPPER.readTree(Files.readString(target));
        } catch (IOException e) {
            throw new UncheckedIOException("unresolvable $ref " + ref + " -> " + target, e);
        }
        JsonNode resolved = document;
        for (String token : fragment.split("/")) {
            if (token.isEmpty()) {
                continue;
            }
            resolved = resolved.get(token.replace("~1", "/").replace("~0", "~"));
            if (resolved == null) {
                throw new IllegalStateException("unresolvable $ref fragment " + ref + " in " + target);
            }
        }
        return inline(resolved.deepCopy(), target.getParent());
    }
}
