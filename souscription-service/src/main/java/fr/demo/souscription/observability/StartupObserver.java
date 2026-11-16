package fr.demo.souscription.observability;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Observateur de demarrage instrumente (W5-T05). Remplace le bootstrap servlet de la source
 * ({@code fr.demo.souscription.web.Application}, 84 lignes) : enregistrement manuel du pilote
 * JDBC, creation d'un repertoire de donnees, initialisation du schema et publication des services
 * dans le contexte web ne sont pas portes — Agroal, Flyway et ArC en sont proprietaires.
 *
 * <p>Il ne reste de ce bootstrap que le diagnostic de demarrage, emis ici une fois le conteneur
 * pret, avec les deux parametres qui conditionnent le service des requetes : le schema cible et la
 * taille maximale du pool. La ligne est emise en JSON et portee par le formateur configure au
 * plan 24.
 */
@ApplicationScoped
public class StartupObserver {

    private static final Logger LOG = Logger.getLogger(StartupObserver.class);

    @Inject
    @ConfigProperty(name = "quarkus.hibernate-orm.database.default-schema")
    String schema;

    @Inject
    @ConfigProperty(name = "quarkus.datasource.jdbc.max-size")
    int poolMax;

    void onStart(@Observes StartupEvent ev) {
        LOG.infof("souscription-service pret : schema=%s, pool=%d", schema, poolMax);
    }
}
