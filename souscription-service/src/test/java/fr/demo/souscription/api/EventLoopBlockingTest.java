package fr.demo.souscription.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.PostgresWithContractStubsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Preuve d'execution du modele de threads (W6-T05) : aucune des six operations ne bloque la boucle
 * d'evenements.
 *
 * <p>La source occupait un thread Tomcat pendant toute la duree des entrees/sorties JDBC. La cible
 * conserve ce modele bloquant, mais le rend <b>explicite</b> : les six methodes de
 * {@link SubscriptionResource} et {@link SubscriptionReadResource} portent {@code @Blocking} et
 * s'executent donc sur le pool de workers (threads {@code executor-thread-*}). Sans cette
 * annotation, une methode a type de retour synchrone peut etre dispatchee sur la boucle
 * d'evenements selon la facon dont Quarkus classe la signature, et le moindre acces JDBC y bloque
 * un thread partage par toutes les connexions du serveur.
 *
 * <p>La garde est le <b>detecteur de blocage de Vert.x</b> lui-meme, et non une inspection
 * d'annotations : le journal est capture pendant que les six operations sont reellement executees,
 * et aucune ligne ne doit contenir {@code Thread blocked} ni {@code blocked for}. Une assertion
 * portant sur la presence de l'annotation passerait meme si le dispatch reel changeait.
 *
 * <p>Exige une base reelle (Dev Services PostgreSQL) : les six operations ecrivent ou lisent dans
 * le schema {@code subscriptions}. Ignore sans daemon de conteneurs, comme les autres tests de
 * parcours complet.
 */
@QuarkusTest
@TestProfile(PostgresWithContractStubsProfile.class)
@RequiresContainerRuntime
class EventLoopBlockingTest {

    private static final List<String> CAPTURED = new CopyOnWriteArrayList<>();

    private static Handler capture;

    @BeforeAll
    static void captureRootLog() {
        capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage() != null) {
                    CAPTURED.add(record.getLoggerName() + " " + record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        capture.setLevel(Level.ALL);
        rootLogger().addHandler(capture);
    }

    @AfterAll
    static void releaseRootLog() {
        rootLogger().removeHandler(capture);
        CAPTURED.clear();
    }

    @Test
    void aucuneDesSixOperationsNeBloqueLaBoucleDEvenements() {
        CAPTURED.clear();

        // 1. startDraft
        String draftId = given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts")
                .then().statusCode(201)
                .extract().path("draftId");

        // 2. getDraft
        given().when().get("/v1/drafts/" + draftId).then().statusCode(200);

        // 3. putDraftSubscriber
        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
                        + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"1985-04-12\"}")
                .when().put("/v1/drafts/" + draftId + "/subscriber")
                .then().statusCode(200);

        // 4. putDraftSimulation
        String key = given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10}")
                .when().put("/v1/drafts/" + draftId + "/simulation")
                .then().statusCode(200)
                .extract().path("confirmationKey");

        // 5. confirmDraft
        String reference = given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + key + "\"}")
                .when().post("/v1/drafts/" + draftId + "/confirmation")
                .then().statusCode(201)
                .extract().path("reference");

        // 6. getSubscription
        given().when().get("/v1/subscriptions/" + reference).then().statusCode(200);

        List<String> blocked = CAPTURED.stream()
                .filter(line -> line.contains("Thread blocked") || line.contains("blocked for"))
                .collect(Collectors.toList());

        assertTrue(blocked.isEmpty(),
                "le detecteur de Vert.x a signale un blocage de la boucle d'evenements : "
                + blocked);
    }

    private static Logger rootLogger() {
        return LogManager.getLogManager().getLogger("");
    }
}
