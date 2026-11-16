package fr.demo.souscription.api.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * W7-T06, seuil NFG-006 : preuve mecanique que la validation est fail-closed.
 *
 * <p>Un {@code @Valid} oublie sur un corps de requete n'est pas une imperfection de style : c'est
 * un contournement <b>complet</b> de la validation, invisible a la lecture et qu'aucune revue ne
 * rattrape de facon fiable. Le controle est donc mecanique et non declaratif : les classes
 * compilees du paquet {@code api} sont enumerees par reflexion — aucune liste ecrite a la main ne
 * peut prendre du retard sur le code — et deux comptes doivent valoir <b>0</b> :
 * <ol>
 *   <li>les parametres de corps de requete d'une methode d'ecriture sans {@code @Valid} ;</li>
 *   <li>les composants d'un DTO d'ecriture sans la moindre contrainte Jakarta Validation.</li>
 * </ol>
 *
 * <p>Test de reflexion pur, sans demarrage du runtime : il doit echouer au plus tot et ne depend
 * d'aucun daemon de conteneurs.
 */
class ValidationCoverageTest {

    private static final Path API_CLASSES =
            Path.of("target", "classes", "fr", "demo", "souscription", "api");

    /** Annotations de parametre qui designent une source autre que le corps de la requete. */
    private static final List<Class<? extends Annotation>> NON_BODY = List.of(PathParam.class,
            QueryParam.class, HeaderParam.class, CookieParam.class, MatrixParam.class,
            FormParam.class, BeanParam.class, Context.class);

    /**
     * DTO dont le nom identifie une requete d'ecriture. Chacun de leurs champs doit porter au
     * moins une contrainte : un champ sans contrainte est une lacune, pas un choix.
     */
    private static final List<String> WRITE_DTOS = List.of("SubscriberInput", "SimulationInput",
            "StartDraftRequest", "ConfirmationRequest");

    @Test
    void everyWriteEndpointValidatesItsRequestBody() throws IOException, ClassNotFoundException {
        List<String> unvalidated = new ArrayList<>();
        int writeEndpoints = 0;
        for (Class<?> type : apiClasses()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!isWrite(method)) {
                    continue;
                }
                writeEndpoints++;
                for (Parameter parameter : method.getParameters()) {
                    if (isRequestBody(parameter) && !parameter.isAnnotationPresent(Valid.class)) {
                        unvalidated.add(type.getSimpleName() + "#" + method.getName()
                                + "(" + parameter.getType().getSimpleName() + ")");
                    }
                }
            }
        }
        assertTrue(writeEndpoints >= 4, "les quatre operations d'ecriture du contrat gele doivent"
                + " etre decouvertes, trouvees : " + writeEndpoints);
        assertEquals(List.of(), unvalidated,
                "corps de requete d'une methode d'ecriture sans @Valid");
    }

    @Test
    void everyWriteDtoFieldCarriesAtLeastOneConstraint() throws ClassNotFoundException {
        List<String> unconstrained = new ArrayList<>();
        for (String name : WRITE_DTOS) {
            Class<?> dto = Class.forName("fr.demo.souscription.api.dto." + name);
            assertTrue(dto.isRecord(), name + " doit rester un record du contrat gele");
            for (RecordComponent component : dto.getRecordComponents()) {
                if (!hasConstraint(dto, component)) {
                    unconstrained.add(name + "." + component.getName());
                }
            }
        }
        assertEquals(List.of(), unconstrained,
                "champ de DTO d'ecriture sans contrainte Jakarta Validation");
    }

    /**
     * Une contrainte est une annotation elle-meme annotee {@link Constraint}.
     *
     * <p>Trois porteurs sont inspectes et non un seul : les annotations standard de Jakarta
     * Validation ne declarent pas {@code RECORD_COMPONENT} dans leur {@code @Target} et sont donc
     * propagees au <b>champ</b> et a l'<b>accesseur</b> du record, jamais au composant lui-meme —
     * seules les contraintes maison du module ({@code @NoControlChars}, {@code @DecimalRange},
     * {@code @MinimumAge}) apparaissent sur le composant. N'interroger que le composant ferait
     * passer ce test pour vert tout en manquant {@code @NotBlank} ou {@code @Min}.
     */
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
            if (annotation.annotationType().isAnnotationPresent(Constraint.class)) {
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
                types.add(Class.forName(className(file)));
            }
        }
        return types;
    }

    private static String className(Path classFile) {
        String relative = API_CLASSES.relativize(classFile).toString()
                .replace(".class", "")
                .replace(java.io.File.separatorChar, '.');
        return "fr.demo.souscription.api." + relative;
    }
}
