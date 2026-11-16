package fr.demo.souscription.api.dto;

/**
 * DTO de sortie de simulation, aligne sur le schema gele SimulationResult.json.
 *
 * <p>{@code contributed} et {@code finalCapital} sont des valeurs serveur autoritaires : une
 * valeur fournie par le client n'est jamais reprise. Montants en chaines pour preserver
 * l'echelle 2.
 */
public record SimulationResult(String initialAmount, String monthlyAmount, Integer durationYears,
                               String contributed, String finalCapital) {
}
