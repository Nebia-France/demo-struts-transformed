package fr.demo.souscription.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * W7-T02 : les six cas du controle anti-CSRF secondaire.
 *
 * <p>Le filtre est {@code preMatching} : il statue avant la resolution de la ressource. Les cas
 * passants sont donc observes sur {@code POST /v1/subscriptions/{reference}}, route sans methode
 * POST : un <b>405</b> prouve que la requete a franchi le filtre, un <b>403</b> qu'elle a ete
 * refusee. Aucune base de donnees n'est sollicitee.
 */
@QuarkusTest
class CsrfDefenceFilterTest {

    private static final String PATH = "/v1/subscriptions/REF-2024-000001";

    private static final String TOKEN = "jeton-valide";

    /** 101 caracteres : un de plus que la borne reprise de la source. */
    private static final String TOKEN_101 = "j".repeat(101);

    @Test
    void aMatchingPairIsAccepted() {
        given().header(CsrfDefaultsCallback.OPT_OUT, "true")
                .header(CsrfDefenceFilter.TOKEN_HEADER, TOKEN)
                .cookie(CsrfDefenceFilter.TOKEN_COOKIE, TOKEN)
                .contentType(ContentType.JSON)
                .when().post(PATH)
                .then().statusCode(405);
    }

    @Test
    void aMismatchedPairIsRejected() {
        given().header(CsrfDefaultsCallback.OPT_OUT, "true")
                .header(CsrfDefenceFilter.TOKEN_HEADER, TOKEN)
                .cookie(CsrfDefenceFilter.TOKEN_COOKIE, "JETON-VALIDE")
                .contentType(ContentType.JSON)
                .when().post(PATH)
                .then().statusCode(403);
    }

    @Test
    void aMissingHeaderIsRejected() {
        given().header(CsrfDefaultsCallback.OPT_OUT, "true")
                .cookie(CsrfDefenceFilter.TOKEN_COOKIE, TOKEN)
                .contentType(ContentType.JSON)
                .when().post(PATH)
                .then().statusCode(403);
    }

    @Test
    void aMissingCookieIsRejected() {
        given().header(CsrfDefaultsCallback.OPT_OUT, "true")
                .header(CsrfDefenceFilter.TOKEN_HEADER, TOKEN)
                .contentType(ContentType.JSON)
                .when().post(PATH)
                .then().statusCode(403);
    }

    @Test
    void aTokenOf101CharactersIsRejected() {
        given().header(CsrfDefaultsCallback.OPT_OUT, "true")
                .header(CsrfDefenceFilter.TOKEN_HEADER, TOKEN_101)
                .cookie(CsrfDefenceFilter.TOKEN_COOKIE, TOKEN_101)
                .contentType(ContentType.JSON)
                .when().post(PATH)
                .then().statusCode(403);
    }

    /**
     * Les methodes sures ne sont jamais refusees par le filtre, jeton ou pas. Le statut exact
     * depend de la ressource (et de la base, inactive dans ce profil) : seule l'absence de 403
     * est affirmee ici.
     */
    @Test
    void safeMethodsAreUnaffected() {
        for (String method : new String[] {"GET", "HEAD", "OPTIONS"}) {
            given().header(CsrfDefaultsCallback.OPT_OUT, "true")
                    .when().request(method, PATH)
                    .then().statusCode(not(403));
        }
    }
}
