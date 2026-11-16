package fr.demo.souscription.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * W7-T02, seuil NFG-002 : matrice des points d'entree non surs.
 *
 * <p>La source controlait 100 % des POST {@code *.do} dans le processus. La cible doit controler
 * 100 % des requetes de methode non sure de souscription-service. Ce test enumere par reflexion
 * chaque methode {@code @POST} / {@code @PUT} / {@code @PATCH} / {@code @DELETE} du paquet
 * {@code api} depuis les classes compilees — aucune liste ecrite a la main ne peut donc prendre
 * du retard sur le code — et affirme que chacune repond <b>403</b> sans paire anti-CSRF valide.
 * Le nombre de points d'entree non controles attendu est <b>0</b>.
 */
@QuarkusTest
class UnsafeEndpointMatrixTest {

    private static final java.nio.file.Path API_CLASSES =
            java.nio.file.Path.of("target", "classes", "fr", "demo", "souscription", "api");

    private record Endpoint(String method, String path) { }

    @Test
    void everyUnsafeEndpointIsCsrfControlled() throws IOException, ClassNotFoundException {
        List<Endpoint> endpoints = discoverUnsafeEndpoints();
        assertTrue(endpoints.size() >= 4,
                "la matrice doit couvrir au moins les quatre operations non sures du contrat gele,"
                        + " trouvees : " + endpoints);

        List<Endpoint> uncontrolled = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            int status = given().header(CsrfDefaultsCallback.OPT_OUT, "true")
                    .contentType("application/json")
                    .when().request(endpoint.method(), endpoint.path())
                    .then().extract().statusCode();
            if (status != 403) {
                uncontrolled.add(endpoint);
            }
        }
        assertEquals(List.of(), uncontrolled,
                "points d'entree non surs sans controle anti-CSRF");
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
                .replace(java.io.File.separatorChar, '.');
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

    /** Chemin complet de l'operation, parametres de chemin remplaces par des valeurs valides. */
    private static String concrete(String rootPath, Method method) {
        Path sub = method.getAnnotation(Path.class);
        String path = rootPath + (sub == null ? "" : "/" + sub.value());
        return path.replaceAll("/+", "/")
                .replaceAll("\\{[^}]*[Ii]d\\}", UUID.randomUUID().toString())
                .replaceAll("\\{[^}]*\\}", "REF-2024-000001");
    }
}
