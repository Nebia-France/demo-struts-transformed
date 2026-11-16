package fr.demo.souscription.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Bouchon HTTP du catalogue pour les tests du client REST (W6-T01).
 *
 * <p>Sert les reponses derivees du contrat gele
 * ({@code souscription-service-to-souscription-catalogue-service/openapi.yaml} et
 * {@code shared-schemas/ProductDetail.json}) sur le port fixe cible par
 * {@code %test.quarkus.rest-client.catalogue.url}. Compte les requetes recues : c'est la seule
 * facon d'affirmer qu'un 404 n'est pas reessaye et qu'un 500 l'est exactement deux fois.
 *
 * <p>Implemente avec le serveur HTTP du JDK : aucune dependance de test supplementaire n'est
 * introduite pour un bouchon de trois reponses.
 */
public final class CatalogueStubServer implements AutoCloseable {

    static final int PORT = 18089;

    /** Corps conforme au schema gele : annualRate est une chaine d'echelle 6. */
    static final String PRUDENT = """
            {"code":"PRUDENT","name":"Horizon Prudent","profile":"Prudent",\
            "description":"Une hypothese de progression moderee.","annualRate":"0.020000"}""";

    static final String DYNAMIQUE = """
            {"code":"DYNAMIQUE","name":"Horizon Dynamique","profile":"Dynamique",\
            "description":"Une hypothese de progression plus elevee.","annualRate":"0.060000"}""";

    private static final String NOT_FOUND = """
            {"type":"urn:horizon:not-found","title":"horizon.request.not-found","status":404}""";

    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile Consumer<HttpExchange> handler = CatalogueStubServer::product;

    public CatalogueStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", PORT), 0);
        server.createContext("/v1/products", exchange -> {
            requests.incrementAndGet();
            handler.accept(exchange);
        });
        server.start();
    }

    int requests() {
        return requests.get();
    }

    void reset() {
        requests.set(0);
    }

    public void serveProduct() {
        handler = CatalogueStubServer::product;
    }

    void serveNotFound() {
        handler = exchange -> respond(exchange, 404, NOT_FOUND);
    }

    void serveStatus(int status) {
        handler = exchange -> respond(exchange, status, NOT_FOUND);
    }

    /** Reponse plus lente que le timeout de 3 s du contrat. */
    void serveSlowly() {
        handler = exchange -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, PRUDENT);
        };
    }

    private static void product(HttpExchange exchange) {
        String code = exchange.getRequestURI().getPath().replaceFirst(".*/", "");
        if ("PRUDENT".equals(code)) {
            respond(exchange, 200, PRUDENT);
        } else if ("DYNAMIQUE".equals(code)) {
            respond(exchange, 200, DYNAMIQUE);
        } else {
            respond(exchange, 404, NOT_FOUND);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (exchange) {
            exchange.getResponseHeaders().add("Content-Type",
                    status == 200 ? "application/json" : "application/problem+json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException broken) {
            // Le client a abandonne (timeout) : rien a signaler au test.
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
