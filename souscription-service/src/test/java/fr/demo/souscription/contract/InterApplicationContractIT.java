package fr.demo.souscription.contract;

import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Porte bloquante unique des contrats inter-applications de souscription-service (plan 42).
 *
 * <p>Execute en une seule passe tous les tests des trois contrats auxquels le service participe :
 * <ul>
 *   <li><b>fournisseur</b> {@code souscription-gateway-to-souscription-service} :
 *       {@code OpenApiContractTest} (plan 19), {@code ContractRepublishTest} (plan 29) et
 *       {@link ProviderContractSpecTest} ;</li>
 *   <li><b>consommateur</b> {@code souscription-service-to-souscription-catalogue-service} :
 *       {@code CatalogueClientContractTest}, {@code CatalogueResilienceTest} (plan 27) et
 *       {@link CatalogueContractStubTest} ;</li>
 *   <li><b>consommateur</b> {@code souscription-service-to-souscription-simulation-service} :
 *       {@code SimulationClientContractTest}, {@code SimulationResilienceTest} (plan 28) et
 *       {@link SimulationContractStubTest} ;</li>
 *   <li>les deux contrats consommes : {@link ResilienceContractReflectionTest} et le parcours
 *       contre les services soeurs reels {@link RealSiblingsJourneyTest} (ignore sans eux).</li>
 * </ul>
 *
 * <p>Parametres de resilience : {@link ResilienceContractReflectionTest} lit chaque
 * {@code resilience-contract.json} gele et compare, par reflexion
 * ({@code Method.getAnnotation(Timeout.class)}, {@code getAnnotation(Retry.class)},
 * {@code getAnnotation(CircuitBreaker.class)}, {@code Bulkhead.class}), les valeurs des annotations
 * de {@code CatalogueClient.findProduct} et {@code SimulationClient.compute} ; aucune valeur
 * attendue n'est ecrite en dur.
 *
 * <p>Une suite vide ne passe pas : l'execution failsafe dediee est configuree avec
 * {@code failIfNoTests=true}.
 *
 * <p>Les classes des plans precedents sont referencees par nom : elles sont privees a leur
 * paquetage et restent inchangees.
 * Execution isolee : {@code mvn -f souscription-service/pom.xml -B verify -Pcucumber
 * -Dit.test=InterApplicationContractIT}.
 */
@Suite
@SuiteDisplayName("Contrats inter-applications de souscription-service")
@IncludeEngines("junit-jupiter")
@SelectClasses(names = {
        "fr.demo.souscription.api.OpenApiContractTest",
        "fr.demo.souscription.api.ContractRepublishTest",
        "fr.demo.souscription.contract.ProviderContractSpecTest",
        "fr.demo.souscription.integration.CatalogueClientContractTest",
        "fr.demo.souscription.integration.CatalogueResilienceTest",
        "fr.demo.souscription.contract.CatalogueContractStubTest",
        "fr.demo.souscription.integration.SimulationClientContractTest",
        "fr.demo.souscription.integration.SimulationResilienceTest",
        "fr.demo.souscription.contract.SimulationContractStubTest",
        "fr.demo.souscription.contract.ResilienceContractReflectionTest",
        "fr.demo.souscription.contract.RealSiblingsJourneyTest"})
public class InterApplicationContractIT {
}
