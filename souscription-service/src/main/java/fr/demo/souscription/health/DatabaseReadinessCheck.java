package fr.demo.souscription.health;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Sonde de readiness du datasource (W5-T02) : une connexion est empruntee au pool Agroal et une
 * requete triviale est executee. Le service n'est declare pret que lorsque la base des
 * souscriptions repond, puisque tout appel {@code /v1/*} ecrit ou lit dans le schema
 * {@code subscriptions}.
 *
 * <p>Remplace le diagnostic de demarrage de la source
 * ({@code "Initialisation de la base de donnees impossible"}), emis une seule fois par le
 * bootstrap web : l'etat de la base est desormais observable en continu.
 */
@Readiness
@ApplicationScoped
public class DatabaseReadinessCheck implements HealthCheck {

    static final String CHECK_NAME = "database";

    /**
     * Injecte en {@code Instance} et non directement : le bean Agroal est synthetique et inactif
     * lorsque {@code quarkus.datasource.active=false} (profil de test par defaut du module). Une
     * injection directe ferait echouer le demarrage sur {@code InactiveBeanException} des que
     * l'evenement de demarrage est propage, et pas seulement a l'appel de la sonde.
     */
    @Inject
    Instance<AgroalDataSource> dataSource;

    @Override
    public HealthCheckResponse call() {
        try (Connection connection = dataSource.get().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            return HealthCheckResponse.up(CHECK_NAME);
        } catch (SQLException | RuntimeException ex) {
            return HealthCheckResponse.builder()
                    .name(CHECK_NAME)
                    .down()
                    .withData("reason", String.valueOf(ex.getClass().getSimpleName()))
                    .build();
        }
    }
}
