package fr.demo.souscription.integration;

/**
 * Requete de calcul interne adressee a souscription-simulation-service, alignee sur le schema
 * gele {@code shared-schemas/SimulationComputeRequest.json}.
 *
 * <p>Les montants et le taux voyagent en chaines pour preserver les echelles gelees (2 pour les
 * montants, 6 pour le taux) : un nombre JSON perdrait les zeros de fin. Le taux est fourni par
 * l'appelant depuis le catalogue ; le service de simulation ne lit jamais le catalogue.
 */
public record SimulationComputeRequest(String initialAmount, String monthlyAmount,
                                       Integer durationYears, String annualRate) {
}
