package fr.demo.souscription.api.validation;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.api.dto.SubscriberInput;
import fr.demo.souscription.persistence.RequiresContainerRuntime;
import io.quarkus.test.junit.QuarkusTest;
import fr.demo.souscription.persistence.PostgresWithContractStubsProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Parity skeleton for 20-souscription-service-subscriber-validation.
 *
 * Baseline reference: BASELINE.md section 4.5 — service/InputValidator.java.
 * This is a VERDICT PARITY test: for every source rule, the declarative constraints must accept
 * exactly what the imperative validator accepted and reject exactly what it rejected.
 *
 * Les cas pilotes par REST ont besoin de Dev Services PostgreSQL : la classe est donc ignoree sans
 * runtime de conteneurs, comme les autres tests REST du module. Les sept memes verdicts sont
 * verifies sans conteneur par {@code SubscriberValidationParityOfflineTest}, qui construit la
 * fabrique de validation avec une horloge fixe.
 */
@QuarkusTest
@TestProfile(PostgresWithContractStubsProfile.class)
@RequiresContainerRuntime
class SubscriberValidationParityTest {

    @Inject
    Validator validator;

    private static SubscriberInput input(String first, String last, String email, String birth) {
        return new SubscriberInput(first, last, email, birth);
    }

    private boolean accepts(SubscriberInput candidate) {
        return validator.validate(candidate).isEmpty();
    }

    @Test
    void nameBoundsAndControlChars() {
        assertTrue(accepts(input("Claire", "Dupont", "c.d@example.org", "1990-05-04")));
        assertTrue(accepts(input("C".repeat(80), "Dupont", "c.d@example.org", "1990-05-04")),
                "80 characters is the source upper bound, inclusive");
        assertFalse(accepts(input("C".repeat(81), "Dupont", "c.d@example.org", "1990-05-04")),
                "81 characters must be rejected (source: value.trim().length() > 80)");
        assertFalse(accepts(input("", "Dupont", "c.d@example.org", "1990-05-04")),
                "empty name rejected (source: value.trim().isEmpty())");
        assertFalse(accepts(input("Cla\u0007ire", "Dupont", "c.d@example.org", "1990-05-04")),
                "ISO control character rejected (source: codePoints().anyMatch(Character::isISOControl))");
        assertFalse(accepts(input("Claire\n", "Dupont", "c.d@example.org", "1990-05-04")),
                "newline is an ISO control character and was rejected by the source");
    }

    @Test
    void emailFollowsTheSourceRegexNotHibernateEmail() {
        // The source regex is [^\s@<>]+@[^\s@<>]+\.[^\s@<>]+ with a 254-character bound.
        for (String valid : List.of("claire.dupont@example.org", "a@b.c", "x+y@sub.domain.fr")) {
            assertTrue(accepts(input("Claire", "Dupont", valid, "1990-05-04")),
                    "the source regex accepts: " + valid);
        }
        for (String invalid : List.of("a@b", "no-at-sign.example.org", "a b@example.org",
                "<a>@example.org", "a@exa<mple>.org", "a@@example.org")) {
            assertFalse(accepts(input("Claire", "Dupont", invalid, "1990-05-04")),
                    "the source regex rejects: " + invalid
                    + " (Hibernate @Email accepts some of these — hence the explicit @Pattern)");
        }
        String long254 = "a".repeat(240) + "@example.org"; // 252 chars
        assertTrue(accepts(input("Claire", "Dupont", long254, "1990-05-04")));
        assertFalse(accepts(input("Claire", "Dupont", "a".repeat(250) + "@example.org", "1990-05-04")),
                "over 254 characters must be rejected");
    }

    @Test
    void minimumAgeBoundary() {
        LocalDate today = LocalDate.of(2024, 5, 4);
        MinimumAgeValidator subject = validatorWithClock(today);

        assertTrue(subject.isValid(LocalDate.of(2006, 5, 4), null),
                "the EXACT 18th birthday is eligible: the source wrote "
                + "!birth.isAfter(today.minusYears(18)), so the boundary is inclusive. An off-by-one "
                + "here refuses every user on their birthday.");
        assertFalse(subject.isValid(LocalDate.of(2006, 5, 5), null),
                "one day short of 18 must be rejected");
        assertTrue(subject.isValid(LocalDate.of(2006, 5, 3), null), "one day over 18 is eligible");
        assertFalse(subject.isValid(today.plusDays(1), null),
                "a future birth date must be rejected (source: birth.isAfter(today))");
        assertFalse(subject.isValid(LocalDate.of(2008, 2, 29), null),
                "a leap-day birth date must be evaluated without throwing");
    }

    @Test
    void validatorUsesInjectedClock() {
        // Same birth date, two clocks: the verdict must follow the injected clock, proving the
        // validator does not read the system clock (and therefore the container's zone).
        LocalDate birth = LocalDate.of(2006, 5, 4);
        assertTrue(validatorWithClock(LocalDate.of(2024, 5, 4)).isValid(birth, null));
        assertFalse(validatorWithClock(LocalDate.of(2024, 5, 3)).isValid(birth, null));
    }

    @Test
    void impossibleDateIsRejected() {
        String draftId = startDraft();
        // Source: ResolverStyle.STRICT rejected 31/02; a lenient parser would store 2023-03-03,
        // a date the user never entered.
        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Dupont\","
                        + "\"email\":\"c.d@example.org\",\"birthDate\":\"2023-02-31\"}")
            .when().put("/v1/drafts/" + draftId + "/subscriber")
            .then().statusCode(400);
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        String draftId = startDraft();
        String stored = given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"  Claire  \",\"lastName\":\"Dupont\","
                        + "\"email\":\"  c.d@example.org  \",\"birthDate\":\"1990-05-04\"}")
            .when().put("/v1/drafts/" + draftId + "/subscriber")
            .then().statusCode(200).extract().path("subscriber.firstName");
        assertEquals("Claire", stored,
                "source: new Subscriber(form.getFirstName().trim(), ...) — scenario "
                + "'Les espaces superflus sont supprimes des donnees du souscripteur'");

        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"   \",\"lastName\":\"Dupont\","
                        + "\"email\":\"c.d@example.org\",\"birthDate\":\"1990-05-04\"}")
            .when().put("/v1/drafts/" + draftId + "/subscriber")
            .then().statusCode(400);
    }

    @Test
    void futureAndMinorAreDistinctMessages() {
        String draftId = startDraft();
        String future = errorBody(draftId, LocalDate.now().plusYears(1).toString());
        String minor = errorBody(draftId, LocalDate.now().minusYears(10).toString());
        assertFalse(future.equals(minor),
                "the source produced two different messages — 'date in the future' and 'reserved to "
                + "adults'. One shared message cannot tell the user whether to fix a typo or whether "
                + "they are ineligible.");
    }

    private String errorBody(String draftId, String birthDate) {
        return given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Dupont\","
                        + "\"email\":\"c.d@example.org\",\"birthDate\":\"" + birthDate + "\"}")
            .when().put("/v1/drafts/" + draftId + "/subscriber")
            .then().statusCode(400).extract().asString();
    }

    /**
     * Builds the validator directly with a fixed Europe/Paris clock. The MinimumAge annotation
     * instance is read by reflection from the holder below rather than hand-implemented, so this
     * skeleton compiles whatever members the annotation declares (value, message, groups, payload).
     */
    private static MinimumAgeValidator validatorWithClock(LocalDate today) {
        MinimumAgeValidator subject = new MinimumAgeValidator();
        subject.clock = Clock.fixed(
                today.atStartOfDay(ZoneId.of("Europe/Paris")).toInstant().plusSeconds(3600),
                ZoneId.of("Europe/Paris"));
        subject.initialize(AgeHolder.annotation());
        return subject;
    }

    private static final class AgeHolder {
        @MinimumAge(18)
        private LocalDate birthDate;

        static MinimumAge annotation() {
            try {
                return AgeHolder.class.getDeclaredField("birthDate").getAnnotation(MinimumAge.class);
            } catch (NoSuchFieldException e) {
                throw new AssertionError(e);
            }
        }
    }

    private String startDraft() {
        return given().contentType(ContentType.JSON).body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts").then().statusCode(201).extract().path("draftId");
    }
}
