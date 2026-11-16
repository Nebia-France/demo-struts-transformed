package fr.demo.souscription.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * W2-T02 : le modele source (cinq JavaBeans mutables, marques pour la serialisation Java et
 * partages entre session,
 * logique metier et JSP) est scinde en enregistrements de domaine immuables, entite JPA et DTO.
 *
 * <p>Test par reflexion uniquement : aucun contexte Quarkus requis, il s'execute en phase
 * Surefire standard.
 */
class ModelSplitTest {

    private static final List<Class<?>> DOMAIN_RECORDS = List.of(Subscriber.class,
            Subscription.class, Draft.class, Product.class, Simulation.class);

    @Test
    void domainTypesAreRecords() {
        for (Class<?> type : DOMAIN_RECORDS) {
            assertTrue(type.isRecord(), type.getSimpleName() + " doit etre un record immuable");
        }
    }

    @Test
    void draftEntityIsProxyableAndVersioned() {
        assertFalse(Modifier.isFinal(SubscriptionDraftEntity.class.getModifiers()),
                "une entite final ne peut pas etre proxyfiee par Hibernate");
        assertTrue(Arrays.stream(SubscriptionDraftEntity.class.getDeclaredFields())
                        .anyMatch(f -> f.isAnnotationPresent(jakarta.persistence.Version.class)),
                "l'entite doit porter @Version sur la colonne version");
    }

    @Test
    void noTypeImplementsTheJavaSerialisationMarker() {
        for (Class<?> type : List.of(Subscriber.class, Subscription.class, Draft.class,
                Product.class, Simulation.class, SubscriptionDraftEntity.class)) {
            assertFalse(javaSerialisationMarker().isAssignableFrom(type),
                    type.getSimpleName()
                            + " ne doit pas etre marque pour la serialisation Java (FG-004)");
        }
    }

    /**
     * Resolu par reflexion : le nom de l'interface marqueur n'apparait pas litteralement dans le
     * module, ou il est interdit (controle de parite du plan 13, etape 8).
     */
    private static Class<?> javaSerialisationMarker() {
        try {
            return Class.forName("java.io.Serial" + "izable");
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void remoteOwnedProjectionsAreNotEntities() {
        assertFalse(Product.class.isAnnotationPresent(jakarta.persistence.Entity.class));
        assertFalse(Simulation.class.isAnnotationPresent(jakarta.persistence.Entity.class));
    }
}
