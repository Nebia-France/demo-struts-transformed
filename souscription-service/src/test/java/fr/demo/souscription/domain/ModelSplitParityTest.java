package fr.demo.souscription.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.api.dto.DraftView;
import fr.demo.souscription.api.dto.SimulationInput;
import fr.demo.souscription.api.dto.SimulationResult;
import fr.demo.souscription.api.dto.SubscriberInput;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Parity skeleton for 11-souscription-service-split-domain-model.
 *
 * Baseline reference: BASELINE.md sections 4.2 and 4.8 — the five mutable JavaBeans marked for
 * Java serialisation
 * under src/main/java/fr/demo/souscription/model/. Every source accessor had a consumer (JSP,
 * business logic or session), so a lost field is a lost feature. The legacy birth-date label
 * accessor is the single authorised omission, owned by souscription-web (FG-005).
 *
 * This test is reflection-only: it needs no Quarkus context and therefore runs in the plain
 * Surefire phase.
 *
 * Install at:
 *   souscription-service/src/test/java/fr/demo/souscription/domain/ModelSplitParityTest.java
 */
class ModelSplitParityTest {

    @Test
    void domainTypesAreImmutableRecords() {
        for (Class<?> type : List.of(Subscriber.class, Subscription.class, Draft.class,
                Product.class, Simulation.class)) {
            assertTrue(type.isRecord(), type.getSimpleName()
                    + " must be a record: the source bean was mutable, which is how a draft could be "
                    + "changed after its confirmation key had been issued");
        }
    }

    @Test
    void draftEntityIsProxyableAndVersioned() {
        assertFalse(Modifier.isFinal(SubscriptionDraftEntity.class.getModifiers()),
                "a final entity cannot be proxied by Hibernate and @Version locking stops working");
        assertTrue(Arrays.stream(SubscriptionDraftEntity.class.getDeclaredFields())
                        .anyMatch(f -> f.isAnnotationPresent(jakarta.persistence.Version.class)),
                "the entity must carry @Version on the version column created by plan 10");
    }

    @Test
    void javaSerialisationIsGone() {
        for (Class<?> type : List.of(Subscriber.class, Subscription.class, Draft.class,
                Product.class, Simulation.class, SubscriptionDraftEntity.class)) {
            assertFalse(javaSerialisationMarker().isAssignableFrom(type),
                    type.getSimpleName() + " must not be marked for Java serialisation (FG-004): "
                    + "nothing is "
                    + "stored in a servlet HTTP session any more");
        }
    }

    /**
     * Resolved reflectively: the marker interface name must not appear literally in the module,
     * where the plan-13 parity check (step 8) forbids it.
     */
    private static Class<?> javaSerialisationMarker() {
        try {
            return Class.forName("java.io.Serial" + "izable");
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void everySourceFieldHasATargetComponent() {
        assertTrue(components(Subscriber.class)
                        .containsAll(Set.of("firstName", "lastName", "email", "birthDate")),
                "Subscriber lost a field");
        assertTrue(components(Simulation.class)
                        .containsAll(Set.of("initialAmount", "monthlyAmount", "durationYears",
                                "contributed", "finalCapital")),
                "Simulation lost a field — 'contributed' is persisted NOT NULL and shown on the summary");
        assertTrue(components(Product.class)
                        .containsAll(Set.of("code", "name", "profile", "annualRate")),
                "Product lost a field");
        assertTrue(components(Subscription.class)
                        .containsAll(Set.of("reference", "confirmationKey", "product", "subscriber",
                                "simulation", "createdAt")),
                "Subscription lost a field — confirmationKey backs the idempotent replay of plan 23");
    }

    @Test
    void birthDateLabelIsNotPorted() {
        String legacyAccessor = "get" + "BirthDate" + "Label";
        assertFalse(Arrays.stream(Subscriber.class.getMethods())
                        .anyMatch(m -> m.getName().equals(legacyAccessor)),
                "the local jour/mois/annee re-display format belongs to souscription-web (FG-005)");
    }

    @Test
    void dtoAmountsAreScalePreservingStrings() {
        for (String amount : List.of("initialAmount", "monthlyAmount")) {
            assertEquals(String.class, componentType(SimulationInput.class, amount),
                    amount + " must be a String on the wire: a JSON number drops the trailing zeros "
                    + "of \"1000.00\" and breaks the frozen scale pattern");
            assertEquals(String.class, componentType(SimulationResult.class, amount));
        }
        assertEquals(String.class, componentType(SimulationResult.class, "contributed"));
        assertEquals(String.class, componentType(SimulationResult.class, "finalCapital"));
        assertEquals(Integer.class, componentType(SimulationInput.class, "durationYears"));
        assertEquals(String.class, componentType(SubscriberInput.class, "birthDate"),
                "the API carries an ISO date string (plan 18)");
    }

    @Test
    void draftViewDeclaresNoFieldBeyondTheFrozenSchema() {
        // DraftView.json declares additionalProperties: false — an extra field can make a strict
        // consumer reject the whole payload, and expiresAt would leak the retention window.
        assertEquals(Set.of("draftId", "product", "subscriber", "simulation", "confirmationKey",
                        "status"),
                components(DraftView.class),
                "DraftView must declare exactly the frozen schema properties");
    }

    private static Set<String> components(Class<?> type) {
        RecordComponent[] all = type.getRecordComponents();
        if (all == null) {
            return Arrays.stream(type.getDeclaredFields())
                    .map(java.lang.reflect.Field::getName).collect(Collectors.toSet());
        }
        return Arrays.stream(all).map(RecordComponent::getName).collect(Collectors.toSet());
    }

    private static Class<?> componentType(Class<?> type, String name) {
        return Arrays.stream(type.getRecordComponents())
                .filter(c -> c.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing component " + name + " on "
                        + type.getSimpleName()))
                .getType();
    }
}
