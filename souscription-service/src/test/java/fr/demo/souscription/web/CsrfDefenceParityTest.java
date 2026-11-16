package fr.demo.souscription.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Parite du plan 31 : le controle anti-CSRF est durci, jamais affaibli.
 *
 * <p>Le filtre servlet de la source portait quatre garanties. Trois doivent survivre a
 * l'identique — comparaison en <b>temps constant</b> sur des octets <b>UTF-8</b>, borne de
 * <b>100 caracteres</b> inclusive, refus en <b>403</b> — une doit etre <b>renforcee</b> : la
 * liste blanche source n'admettait que {@code GET}/{@code HEAD}/{@code POST}, de sorte que les
 * trois {@code PUT} de la cible n'auraient aucun controle ; le filtre couvre donc tous les verbes
 * non surs. La quatrieme, le jeton porte par la session et sa fenetre de 20 minutes, est
 * abandonnee (service sans cookie) et ses successeurs sont nommes par ecrit dans
 * {@code SECURITY_GATES.md}.
 *
 * <p>Le filtre est {@code preMatching} : les cas passants sont observes sur
 * {@code POST /v1/subscriptions/{reference}}, route sans methode POST, ou un <b>405</b> prouve le
 * franchissement du filtre et un <b>403</b> le refus. Aucune base de donnees n'est sollicitee.
 */
@QuarkusTest
class CsrfDefenceParityTest {

    private static final String PATH = "/v1/subscriptions/REF-2024-000001";

    private static final java.nio.file.Path FILTER_SOURCE = java.nio.file.Path.of(
            "src", "main", "java", "fr", "demo", "souscription", "web", "CsrfDefenceFilter.java");

    private static final java.nio.file.Path API_CLASSES =
            java.nio.file.Path.of("target", "classes", "fr", "demo", "souscription", "api");

    /** Exactement la borne de la source : {@code length() > 100} refuse, 100 accepte. */
    private static final String TOKEN_100 = "j".repeat(100);

    private static final String TOKEN_101 = "j".repeat(101);

    private record Endpoint(String method, String path) { }

    /**
     * Etape 1 : la comparaison reste en temps constant. Les tests fonctionnels passent a
     * l'identique avec {@code String.equals}, qui court-circuite au premier octet different et
     * fuit le prefixe du jeton par le temps de reponse : l'invariant doit donc etre affirme
     * statiquement.
     */
    @Test
    void comparisonIsConstantTime() throws IOException {
        String source = Files.readString(FILTER_SOURCE, StandardCharsets.UTF_8);
        assertEquals(1, countOccurrences(source, "MessageDigest.isEqual"),
                "la comparaison des jetons doit passer par un unique MessageDigest.isEqual");
        assertFalse(Pattern.compile("\\.equals\\(|equalsIgnoreCase|==\\s*cookieToken")
                        .matcher(source).find(),
                "aucune comparaison de jeton fondee sur equals ne doit subsister");
    }

    /** Etape 2 : les deux operandes sont convertis en octets UTF-8 explicites. */
    @Test
    void comparisonOperatesOnUtf8Bytes() throws IOException {
        String source = Files.readString(FILTER_SOURCE, StandardCharsets.UTF_8);
        assertTrue(source.contains("getBytes(StandardCharsets.UTF_8)"),
                "le charset doit rester explicite : un charset par defaut rend la comparaison"
                        + " dependante de l'environnement");
    }

    /** Etape 3 : la borne de 100 caracteres est inclusive, comme {@code > 100} dans la source. */
    @Test
    void theHundredCharacterBoundIsPreserved() {
        assertEquals(100, CsrfDefenceFilter.MAX_TOKEN_LENGTH);
        post(TOKEN_100, TOKEN_100).then().statusCode(405);
        post(TOKEN_101, TOKEN_101).then().statusCode(403);
    }

    /** Etape 4 : tous les refus standards, « pas de jeton » ne valant jamais « pas de controle ». */
    @Test
    void rejectionCases() {
        post("jeton-valide", "JETON-VALIDE").then().statusCode(403);
        post(null, "jeton-valide").then().statusCode(403);
        post("jeton-valide", null).then().statusCode(403);
        post(null, null).then().statusCode(403);
    }

    /**
     * Etape 5 : les methodes sures ne sont pas refusees. La source n'appliquait son controle
     * qu'aux POST ; l'etendre aux methodes sures casserait toutes les lectures de la passerelle.
     */
    @Test
    void safeMethodsAreUnaffected() {
        for (String method : new String[] {"GET", "HEAD", "OPTIONS"}) {
            given().header(CsrfDefaultsCallback.OPT_OUT, "true")
                    .when().request(method, PATH)
                    .then().statusCode(not(403));
        }
    }

    /**
     * Etape 6, renforcement et seuil NFG-002 : chaque methode non sure du paquet {@code api} est
     * decouverte par reflexion — aucune liste ecrite a la main — et doit repondre 403 sans paire
     * valide. Nombre de points d'entree non controles attendu : 0.
     */
    @Test
    void everyUnsafeEndpointIsControlled() throws IOException, ClassNotFoundException {
        List<Endpoint> endpoints = discoverUnsafeEndpoints();
        assertTrue(endpoints.size() >= 4,
                "la matrice doit couvrir au moins les quatre operations non sures, trouvees : "
                        + endpoints);
        List<Endpoint> uncontrolled = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            int status = given().header(CsrfDefaultsCallback.OPT_OUT, "true")
                    .contentType(ContentType.JSON)
                    .when().request(endpoint.method(), endpoint.path())
                    .then().extract().statusCode();
            if (status != 403) {
                uncontrolled.add(endpoint);
            }
        }
        assertEquals(List.of(), uncontrolled,
                "points d'entree non surs sans controle anti-CSRF");
    }

    /** Etape 7 : le service reste sans cookie — aucune des six operations n'en pose. */
    @Test
    void noResponseSetsACookie() {
        String draft = "/v1/drafts/" + UUID.randomUUID();
        assertNoCookie(() -> given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts"));
        assertNoCookie(() -> given().when().get(draft));
        assertNoCookie(() -> given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
                        + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"1985-04-12\"}")
                .when().put(draft + "/subscriber"));
        assertNoCookie(() -> given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":1000.00,\"monthlyAmount\":100.00,\"durationYears\":10}")
                .when().put(draft + "/simulation"));
        assertNoCookie(() -> given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + UUID.randomUUID() + "\"}")
                .when().post(draft + "/confirmation"));
        assertNoCookie(() -> given().when().get(PATH));
    }

    private static Response post(String headerToken, String cookieToken) {
        var request = given().header(CsrfDefaultsCallback.OPT_OUT, "true")
                .contentType(ContentType.JSON);
        if (headerToken != null) {
            request = request.header(CsrfDefenceFilter.TOKEN_HEADER, headerToken);
        }
        if (cookieToken != null) {
            request = request.cookie(CsrfDefenceFilter.TOKEN_COOKIE, cookieToken);
        }
        return request.when().post(PATH);
    }

    private static void assertNoCookie(Supplier<Response> call) {
        Response response = call.get();
        assertTrue(response.getHeaders().getValues("Set-Cookie").isEmpty(),
                "aucune reponse ne doit poser de cookie");
        assertEquals(0, response.getDetailedCookies().size(),
                "aucun cookie ne doit etre renvoye");
    }

    private static int countOccurrences(String source, String needle) {
        int count = 0;
        for (int index = source.indexOf(needle); index >= 0;
                index = source.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    private static List<Endpoint> discoverUnsafeEndpoints()
            throws IOException, ClassNotFoundException {
        List<Endpoint> endpoints = new ArrayList<>();
        try (Stream<java.nio.file.Path> files = Files.walk(API_CLASSES)) {
            List<java.nio.file.Path> classFiles = files
                    .filter(file -> file.toString().endsWith(".class"))
                    .filter(file -> !file.toString().contains("$"))
                    .sorted()
                    .toList();
            for (java.nio.file.Path file : classFiles) {
                Class<?> type = Class.forName(className(file));
                Path root = type.getAnnotation(Path.class);
                if (root == null) {
                    continue;
                }
                for (Method method : type.getDeclaredMethods()) {
                    String verb = unsafeVerb(method);
                    if (verb != null) {
                        endpoints.add(new Endpoint(verb, concrete(root.value(), method)));
                    }
                }
            }
        }
        return endpoints;
    }

    private static String className(java.nio.file.Path classFile) {
        String relative = API_CLASSES.relativize(classFile).toString()
                .replace(".class", "")
                .replace(File.separatorChar, '.');
        return "fr.demo.souscription.api." + relative;
    }

    private static String unsafeVerb(Method method) {
        if (method.isAnnotationPresent(POST.class)) {
            return HttpMethod.POST;
        }
        if (method.isAnnotationPresent(PUT.class)) {
            return HttpMethod.PUT;
        }
        if (method.isAnnotationPresent(PATCH.class)) {
            return HttpMethod.PATCH;
        }
        if (method.isAnnotationPresent(DELETE.class)) {
            return HttpMethod.DELETE;
        }
        return null;
    }

    private static String concrete(String rootPath, Method method) {
        Path sub = method.getAnnotation(Path.class);
        String path = rootPath + (sub == null ? "" : "/" + sub.value());
        return path.replaceAll("/+", "/")
                .replaceAll("\\{[^}]*[Ii]d\\}", UUID.randomUUID().toString())
                .replaceAll("\\{[^}]*\\}", "REF-2024-000001");
    }
}
