package fr.demo.souscription.api.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Constraint;
import jakarta.validation.Valid;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Controle de parite du plan 34 : la propriete <b>fail-closed</b> du source est-elle encore vraie
 * dans la cible declarative ?
 *
 * <p>Reference de base : BASELINE.md §4.5 — {@code InputValidator.subscriber(form)} rejouait
 * {@code subscriberErrors(form)} et levait {@code IllegalArgumentException("Souscripteur
 * invalide")}. Aucun chemin de code ne pouvait donc construire un {@code Subscriber} a partir
 * d'une saisie non validee : le source etait fail-closed <b>par construction</b>.
 *
 * <p>La cible perd cette propriete si elle n'est pas prouvee mecaniquement : un seul {@code @Valid}
 * oublie desactive <b>toutes</b> les contraintes du point d'entree concerne, et un champ de DTO
 * sans contrainte est accepte tel quel.
 *
 * <p>Volet hors base de donnees (etapes 1 a 3 du plan) : pure reflexion sur les classes compilees,
 * sans demarrage du runtime ni daemon de conteneurs. Le volet HTTP de la revalidation a la
 * confirmation (etapes 4 a 6) est porte par {@link FailClosedParityDatabaseTest}.
 */
class FailClosedParityTest {

    private static final Path API_CLASSES =
            Path.of("target", "classes", "fr", "demo", "souscription", "api");

    private static final Path COVERAGE_TEST = Path.of("src", "test", "java", "fr", "demo",
            "souscription", "api", "validation", "ValidationCoverageTest.java");

    /** Annotations de parametre qui designent une source autre que le corps de la requete. */
    private static final List<Class<? extends Annotation>> NON_BODY = List.of(PathParam.class,
            QueryParam.class, HeaderParam.class, CookieParam.class, MatrixParam.class,
            FormParam.class, BeanParam.class, Context.class);

    private static final List<String> WRITE_DTOS = List.of("SubscriberInput", "SimulationInput",
            "StartDraftRequest", "ConfirmationRequest");

    /** Etape 1 (NFG-006) : 0 point d'entree d'ecriture dont le corps echappe au @Valid. */
    @Test
    void zeroWriteEndpointWithoutValid() throws IOException, ClassNotFoundException {
        List<String> unvalidated = new ArrayList<>();
        int writeMethods = 0;
        for (Class<?> type : apiClasses()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!isWrite(method)) {
                    continue;
                }
                writeMethods++;
                for (Parameter parameter : method.getParameters()) {
                    if (isRequestBody(parameter) && !parameter.isAnnotationPresent(Valid.class)) {
                        unvalidated.add(type.getSimpleName() + "." + method.getName()
                                + "(" + parameter.getType().getSimpleName() + ")");
                    }
                }
            }
        }
        // R1b : une matrice qui ne decouvre rien passe trivialement. L'enumeration est donc
        // verifiee non vide avant d'etre jugee.
        assertTrue(writeMethods >= 4, "l'enumeration par reflexion n'a decouvert aucune methode"
                + " d'ecriture, ou moins que les quatre operations du contrat gele ; trouvees : "
                + writeMethods);
        assertEquals(List.of(), unvalidated, "un @Valid oublie est un contournement TOTAL :"
                + " toutes les contraintes du DTO deviennent decoratives et la seule defense"
                + " restante est les trois CHECK de la base, qui ne couvrent ni les noms, ni"
                + " l'adresse electronique, ni les dates. Sans @Valid : " + unvalidated);
    }

    /** Etape 2 : 0 champ de DTO d'ecriture sans contrainte. */
    @Test
    void zeroWriteDtoFieldWithoutConstraint() throws ClassNotFoundException {
        List<String> unconstrained = new ArrayList<>();
        for (String name : WRITE_DTOS) {
            Class<?> dto = Class.forName("fr.demo.souscription.api.dto." + name);
            RecordComponent[] components = dto.getRecordComponents();
            assertTrue(components != null && components.length > 0,
                    name + " doit rester un record du contrat gele, avec des composants");
            for (RecordComponent component : components) {
                if (!hasConstraint(dto, component)) {
                    unconstrained.add(name + "." + component.getName());
                }
            }
        }
        assertEquals(List.of(), unconstrained, "un champ sans contrainte est accepte pour tout ce"
                + " que la base voudra bien prendre ; StartDraftRequest.productCode est l'oubli"
                + " habituel et part tel quel au client catalogue comme segment de chemin."
                + " Sans contrainte : " + unconstrained);
    }

    /**
     * Etape 3 : l'enumeration de {@code ValidationCoverageTest} est bien reflexive et non une
     * liste ecrite a la main. Une liste serait exacte aujourd'hui et silencieusement incomplete
     * des le prochain point d'entree ajoute — precisement le mode de defaillance que ce plan
     * existe pour empecher.
     */
    @Test
    void theEnumerationIsReflectiveNotHandWritten() throws IOException {
        String source = Files.readString(COVERAGE_TEST);
        assertTrue(source.contains("getDeclaredMethods"),
                "les methodes doivent etre decouvertes par reflexion");
        assertTrue(source.contains("Files.walk"),
                "les classes du paquet api doivent etre decouvertes par parcours des classes"
                + " compilees, jamais listees");
        assertFalse(source.contains("SubscriptionResource")
                        || source.contains("SubscriptionReadResource"),
                "aucune ressource ne doit etre nommee en dur dans l'enumeration");
    }

    private static boolean hasConstraint(Class<?> dto, RecordComponent component) {
        if (anyConstraint(component.getAnnotations())
                || anyConstraint(component.getAccessor().getAnnotations())) {
            return true;
        }
        try {
            return anyConstraint(dto.getDeclaredField(component.getName()).getAnnotations());
        } catch (NoSuchFieldException impossibleOnARecord) {
            return false;
        }
    }

    private static boolean anyConstraint(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (annotation.annotationType().isAnnotationPresent(Constraint.class)
                    || annotation.annotationType().getName().startsWith("jakarta.validation")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWrite(Method method) {
        return method.isAnnotationPresent(POST.class) || method.isAnnotationPresent(PUT.class)
                || method.isAnnotationPresent(PATCH.class)
                || method.isAnnotationPresent(DELETE.class);
    }

    private static boolean isRequestBody(Parameter parameter) {
        return NON_BODY.stream().noneMatch(parameter::isAnnotationPresent);
    }

    private static List<Class<?>> apiClasses() throws IOException, ClassNotFoundException {
        List<Class<?>> types = new ArrayList<>();
        try (Stream<Path> files = Files.walk(API_CLASSES)) {
            List<Path> classFiles = files
                    .filter(file -> file.toString().endsWith(".class"))
                    .filter(file -> !file.toString().contains("$"))
                    .sorted()
                    .toList();
            for (Path file : classFiles) {
                String relative = API_CLASSES.relativize(file).toString()
                        .replace(".class", "")
                        .replace(File.separatorChar, '.');
                types.add(Class.forName("fr.demo.souscription.api." + relative));
            }
        }
        return types;
    }
}
