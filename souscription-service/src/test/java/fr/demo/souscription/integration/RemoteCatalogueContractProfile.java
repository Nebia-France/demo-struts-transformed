package fr.demo.souscription.integration;

/**
 * Meme configuration que {@link RemoteCatalogueProfile}, mais classe de profil distincte pour que
 * Quarkus reconstruise l'application entre les tests de contrat et les tests de resilience.
 *
 * <p>Sans cela, les deux classes de test partagent la meme instance, donc le meme etat de
 * disjoncteur : le test qui ouvre volontairement le circuit ferait echouer en 503 les scenarios
 * de contrat executes ensuite.
 */
public class RemoteCatalogueContractProfile extends RemoteCatalogueProfile {
}
