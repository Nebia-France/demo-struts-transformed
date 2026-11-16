package fr.demo.souscription.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bouchon HTTP de souscription-simulation-service pour les tests du client REST (W6-T02).
 *
 * <p>Sert les reponses derivees du contrat gele
 * ({@code souscription-service-to-souscription-simulation-service/openapi.yaml} et
 * {@code shared-schemas/SimulationResult.json}) sur le port fixe cible par
 * {@code %test.quarkus.rest-client.simulation.url}. L'arithmetique reproduite ici est celle que le
 * fournisseur doit implementer d'apres le schema gele : capitalisation mensuelle avec
 * {@link MathContext#DECIMAL128} puis arrondi {@link RoundingMode#HALF_UP} a l'echelle 2. Le
 * calcul appartient desormais au fournisseur ; il n'est reproduit dans le bouchon que pour que les
 * assertions de contrat portent sur des valeurs exactes.
 *
 * <p>Compte les requetes recues : c'est la seule facon d'affirmer qu'un 400 n'est pas reessaye et
 * qu'un 500 l'est exactement une fois.
 */
public final class SimulationStubServer implements AutoCloseable {

    static final int PORT = 18090;

    private static final MathContext PRECISION = MathContext.DECIMAL128;

    private static final Pattern STRING_FIELD =
            Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");

    private static final Pattern NUMBER_FIELD =
            Pattern.compile("\"%s\"\\s*:\\s*(-?[0-9]+)");

    private static final String REJECTED = """
            {"type":"urn:horizon:validation-failed","title":"horizon.request.invalid",\
            "status":400}""";

    private static final String REJECTED_WITH_FIELDS = """
            {"type":"urn:horizon:validation-failed","title":"horizon.validation.failed",\
            "status":400,"errors":[{"field":"durationYears",\
            "message":"simulation.durationYears.range","code":"DURATION_YEARS_RANGE"}]}""";

    private static final String FAILED = """
            {"type":"urn:horizon:internal-error","title":"horizon.server.error","status":500}""";

    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    /**
     * Journal des corps de requete recus : c'est la seule preuve du taux reellement envoye au
     * fournisseur, la reponse etant identique quel que soit le taux transmis.
     */
    private static final List<String> BODIES = new CopyOnWriteArrayList<>();

    /** En-tetes {@code traceparent} recus, dans l'ordre (W6-T06, parite du plan 30). */
    private static final List<String> TRACEPARENTS = new CopyOnWriteArrayList<>();
    private volatile Consumer<HttpExchange> handler = SimulationStubServer::compute;

    public SimulationStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", PORT), 0);
        server.createContext("/v1/simulations", exchange -> {
            requests.incrementAndGet();
            TRACEPARENTS.add(exchange.getRequestHeaders().getFirst("traceparent"));
            handler.accept(exchange);
        });
        server.start();
    }

    int requests() {
        return requests.get();
    }

    public void reset() {
        requests.set(0);
        BODIES.clear();
        TRACEPARENTS.clear();
    }

    /**
     * En-tete {@code traceparent} de la derniere requete recue, ou {@code null} si aucune. Seul
     * l'en-tete <b>recu par le fournisseur</b> prouve la propagation : une trace lue cote appelant
     * resterait vraie meme si rien n'etait emis sur le fil.
     */
    public String lastTraceparent() {
        return TRACEPARENTS.isEmpty() ? null : TRACEPARENTS.get(TRACEPARENTS.size() - 1);
    }

    /** Corps de la derniere requete recue, ou {@code null} si aucune. */
    String lastRequestBody() {
        return BODIES.isEmpty() ? null : BODIES.get(BODIES.size() - 1);
    }

    /**
     * Refus de parametres portant des verdicts par champ, comme le fournisseur les publie :
     * seules ces entrees doivent etre relayees a l'appelant.
     */
    void serveRejectionWithFieldErrors() {
        handler = exchange -> {
            drain(exchange);
            respond(exchange, 400, REJECTED_WITH_FIELDS);
        };
    }

    public void serveComputation() {
        handler = SimulationStubServer::compute;
    }

    /**
     * Projection imposee, volontairement invraisemblable dans les tests de parite : si cette
     * valeur est persistee, elle ne peut venir que du fournisseur.
     */
    void serveFixedFinalCapital(String finalCapital) {
        handler = exchange -> {
            String body = drain(exchange);
            respond(exchange, 200, """
                    {"initialAmount":"%s","monthlyAmount":"%s","durationYears":%d,\
                    "contributed":"%s","finalCapital":"%s"}"""
                    .formatted(text(body, "initialAmount"), text(body, "monthlyAmount"),
                            number(body, "durationYears"),
                            expectedContributed(text(body, "initialAmount"),
                                    text(body, "monthlyAmount"), number(body, "durationYears")),
                            finalCapital));
        };
    }

    public void serveStatus(int status) {
        handler = exchange -> {
            drain(exchange);
            respond(exchange, status, status == 400 ? REJECTED : FAILED);
        };
    }

    /** Reponse plus lente que le timeout de 3 s du contrat. */
    void serveSlowly() {
        handler = exchange -> {
            drain(exchange);
            try {
                Thread.sleep(5000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{}");
        };
    }

    /**
     * Latence injectee calibree de part et d'autre du timeout de 3 s (W6-T06) : le calcul
     * nominal est ensuite servi, pour que le resultat soit exploitable quand la latence reste
     * sous le timeout.
     */
    public void serveWithLatency(long millis) {
        handler = exchange -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            compute(exchange);
        };
    }

    /** Projection attendue du fournisseur, calculee hors HTTP pour les assertions des tests. */
    public static String expectedFinalCapital(String initial, String monthly, int years, String rate) {
        return projection(new BigDecimal(initial), new BigDecimal(monthly), years,
                new BigDecimal(rate)).toPlainString();
    }

    static String expectedContributed(String initial, String monthly, int years) {
        return new BigDecimal(initial)
                .add(new BigDecimal(monthly).multiply(BigDecimal.valueOf(years * 12L)))
                .setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static void compute(HttpExchange exchange) {
        String body = drain(exchange);
        String initial = text(body, "initialAmount");
        String monthly = text(body, "monthlyAmount");
        String rate = text(body, "annualRate");
        Integer years = number(body, "durationYears");
        if (initial == null || monthly == null || rate == null || years == null
                || !accepted(new BigDecimal(initial), new BigDecimal(monthly), years,
                        new BigDecimal(rate))) {
            respond(exchange, 400, REJECTED);
            return;
        }
        BigDecimal capital = projection(new BigDecimal(initial), new BigDecimal(monthly), years,
                new BigDecimal(rate));
        respond(exchange, 200, """
                {"initialAmount":"%s","monthlyAmount":"%s","durationYears":%d,\
                "contributed":"%s","finalCapital":"%s"}"""
                .formatted(new BigDecimal(initial).setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        new BigDecimal(monthly).setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        years, expectedContributed(initial, monthly, years),
                        capital.toPlainString()));
    }

    /** Bornes du schema gele {@code SimulationInput.json}, refusees en 400 par le fournisseur. */
    private static boolean accepted(BigDecimal initial, BigDecimal monthly, int years,
                                    BigDecimal rate) {
        return initial.scale() <= 2 && monthly.scale() <= 2
                && initial.compareTo(new BigDecimal("100")) >= 0
                && initial.compareTo(new BigDecimal("100000")) <= 0
                && monthly.signum() >= 0 && monthly.compareTo(new BigDecimal("2000")) <= 0
                && years >= 1 && years <= 30
                && rate.signum() >= 0 && rate.compareTo(BigDecimal.ONE) <= 0;
    }

    private static BigDecimal projection(BigDecimal initial, BigDecimal monthly, int years,
                                         BigDecimal annualRate) {
        BigDecimal factor = BigDecimal.ONE.add(annualRate.divide(new BigDecimal("12"), PRECISION));
        BigDecimal capital = initial;
        for (int month = 0; month < years * 12; month++) {
            capital = capital.multiply(factor, PRECISION).add(monthly, PRECISION);
        }
        return capital.setScale(2, RoundingMode.HALF_UP);
    }

    private static String text(String body, String field) {
        Matcher matcher = Pattern.compile(STRING_FIELD.pattern().formatted(field)).matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Integer number(String body, String field) {
        Matcher matcher = Pattern.compile(NUMBER_FIELD.pattern().formatted(field)).matcher(body);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    /**
     * Le corps de la requete doit etre lu meme lorsque la reponse est une erreur : un POST dont
     * le corps n'est pas consomme peut rompre la connexion avant la reponse et faire echouer le
     * test pour une raison etrangere a ce qu'il verifie.
     */
    private static String drain(HttpExchange exchange) {
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            BODIES.add(body);
            return body;
        } catch (IOException unreadable) {
            return "";
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
