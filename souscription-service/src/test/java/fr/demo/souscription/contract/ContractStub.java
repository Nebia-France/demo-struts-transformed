package fr.demo.souscription.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fr.demo.souscription.contract.ContractDocuments.Located;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bouchon fournisseur genere a partir d'un contrat OpenAPI gele (plan 42).
 *
 * <p>Tout ce qui est observable sur le fil est derive du document
 * {@code global/contracts/<communication>/openapi.yaml} lu sur le disque :
 * <ul>
 *   <li>le prefixe de base est le chemin de la premiere URL de {@code servers} ;</li>
 *   <li>seules les operations declarees ({@code paths} x methode) sont routees, toute autre
 *       requete est un 404 et une violation ;</li>
 *   <li>le corps de requete est valide contre le schema du {@code requestBody} (resolu dans
 *       {@code shared-schemas/}) : un corps non conforme est refuse en 400 comme le ferait le
 *       fournisseur, et consigne dans {@link #requestViolations()} ;</li>
 *   <li>la reponse produite par le comportement fournisseur est verifiee contre le contrat :
 *       statut declare pour l'operation et corps conforme au schema de ce statut, sinon
 *       {@link #violations()}.</li>
 * </ul>
 *
 * <p>Seule la semantique metier (quels produits existent, l'arithmetique de projection) est
 * fournie par le test sous forme de {@link Function} : un contrat ne la porte pas.
 */
final class ContractStub implements AutoCloseable {

    /** Requete recue, rattachee a l'operation du contrat qu'elle cible. */
    record Call(String operationId, Map<String, String> pathParameters, JsonNode body) {
    }

    /** Reponse du comportement fournisseur, verifiee contre le contrat avant emission. */
    record Reply(int status, String body) {
    }

    private record Operation(String id, String method, Pattern route, List<String> names,
                             JsonNode node) {
    }

    private final Path contract;
    private final String basePath;
    private final List<Operation> operations = new ArrayList<>();
    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> violations = new CopyOnWriteArrayList<>();
    private final List<String> requestViolations = new CopyOnWriteArrayList<>();
    private final Function<Call, Reply> provider;

    ContractStub(Path contract, int port, Function<Call, Reply> provider) throws IOException {
        this.contract = contract;
        this.provider = provider;
        JsonNode document = ContractDocuments.read(contract);
        this.basePath = URI.create(document.path("servers").path(0).path("url").asText())
                .getPath().replaceAll("/$", "");
        JsonNode paths = document.path("paths");
        for (Iterator<String> it = paths.fieldNames(); it.hasNext(); ) {
            String template = it.next();
            List<String> names = new ArrayList<>();
            Matcher placeholder = Pattern.compile("\\{([^}]+)}").matcher(template);
            StringBuilder regex = new StringBuilder();
            int last = 0;
            while (placeholder.find()) {
                regex.append(Pattern.quote(template.substring(last, placeholder.start())))
                        .append("([^/]+)");
                names.add(placeholder.group(1));
                last = placeholder.end();
            }
            regex.append(Pattern.quote(template.substring(last)));
            Pattern route = Pattern.compile(Pattern.quote(basePath) + regex);
            for (Iterator<Map.Entry<String, JsonNode>> ops = paths.get(template).fields();
                 ops.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = ops.next();
                if (entry.getValue().has("operationId")) {
                    operations.add(new Operation(entry.getValue().get("operationId").asText(),
                            entry.getKey().toUpperCase(), route, names, entry.getValue()));
                }
            }
        }
        server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** Identifiants des operations routees, c'est-a-dire celles du contrat. */
    List<String> operationIds() {
        return operations.stream().map(Operation::id).toList();
    }

    int requests() {
        return requests.get();
    }

    /** Ecarts entre la reponse servie et le contrat, ou requetes hors contrat. */
    List<String> violations() {
        return List.copyOf(violations);
    }

    /** Corps de requete du consommateur non conformes au schema du contrat. */
    List<String> requestViolations() {
        return List.copyOf(requestViolations);
    }

    void reset() {
        requests.set(0);
        violations.clear();
        requestViolations.clear();
    }

    private void handle(HttpExchange exchange) {
        requests.incrementAndGet();
        String body;
        try {
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            body = "";
        }
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        for (Operation operation : operations) {
            Matcher matcher = operation.route().matcher(path);
            if (operation.method().equals(method) && matcher.matches()) {
                serve(exchange, operation, matcher, body);
                return;
            }
        }
        violations.add("requete hors contrat : " + method + " " + path);
        respond(exchange, 404, "application/problem+json",
                "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}");
    }

    private void serve(HttpExchange exchange, Operation operation, Matcher matcher, String body) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (int i = 0; i < operation.names().size(); i++) {
            parameters.put(operation.names().get(i), matcher.group(i + 1));
        }
        JsonNode json = null;
        JsonNode requestBody = operation.node().path("requestBody");
        if (!requestBody.isMissingNode()) {
            Located schema = new Located(contract,
                    requestBody.path("content").path("application/json").path("schema"));
            List<String> errors;
            try {
                json = ContractDocuments.parse(body);
                errors = ContractDocuments.validate(schema, json);
            } catch (RuntimeException unreadable) {
                errors = List.of("corps illisible : " + unreadable.getMessage());
            }
            if (!errors.isEmpty()) {
                requestViolations.addAll(errors);
                reply(exchange, operation, new Reply(400, """
                        {"type":"urn:horizon:validation-failed","title":"horizon.request.invalid",\
                        "status":400}"""));
                return;
            }
        }
        reply(exchange, operation, provider.apply(new Call(operation.id(), parameters, json)));
    }

    private void reply(HttpExchange exchange, Operation operation, Reply reply) {
        String status = String.valueOf(reply.status());
        JsonNode declared = operation.node().path("responses").path(status);
        String contentType = "application/json";
        if (declared.isMissingNode()) {
            violations.add(operation.id() + " : statut " + status + " non declare par le contrat");
        } else {
            Located response = ContractDocuments.deref(new Located(contract, declared));
            Iterator<Map.Entry<String, JsonNode>> media = response.node().path("content").fields();
            if (media.hasNext()) {
                Map.Entry<String, JsonNode> first = media.next();
                contentType = first.getKey();
                for (String error : ContractDocuments.validate(
                        new Located(response.file(), first.getValue().path("schema")),
                        ContractDocuments.parse(reply.body()))) {
                    violations.add(operation.id() + " " + status + " " + error);
                }
            }
        }
        respond(exchange, reply.status(), contentType, reply.body());
    }

    private static void respond(HttpExchange exchange, int status, String type, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (exchange) {
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException broken) {
            // Le client a abandonne : rien a signaler au test.
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
