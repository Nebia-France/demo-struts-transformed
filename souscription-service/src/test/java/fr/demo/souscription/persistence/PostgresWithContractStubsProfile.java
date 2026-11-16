package fr.demo.souscription.persistence;

import fr.demo.souscription.integration.ContractStubsResource;
import java.util.List;

/**
 * {@link PostgresDevServicesProfile} augmente des bouchons de contrat catalogue / simulation.
 *
 * <p>Porte par le profil plutot que par {@code @WithTestResource} sur chaque classe : une
 * ressource restreinte a sa classe annotee force un redemarrage de Quarkus (et un nouveau
 * conteneur PostgreSQL) a chaque classe, alors que les classes d'un meme profil partagent une
 * seule application (NFG-010 : duree de pipeline < 5 min).
 */
public class PostgresWithContractStubsProfile extends PostgresDevServicesProfile {

    @Override
    public List<TestResourceEntry> testResources() {
        return List.of(new TestResourceEntry(ContractStubsResource.class));
    }
}
