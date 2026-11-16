package fr.demo.souscription.api.dto;

import fr.demo.souscription.api.validation.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * DTO d'entree de simulation, aligne sur le schema gele SimulationInput.json.
 *
 * <p>Les montants voyagent en chaines JSON (motif {@code ^[0-9]{1,6}\.[0-9]{2}$}) et non en
 * nombres JSON : un nombre JSON perd les zeros de fin de {@code "1000.00"} et casse l'echelle
 * gelee. Le taux annuel n'est jamais fourni par le client.
 *
 * <p>Le motif impose le point decimal et deux decimales exactement, sans separateur de milliers :
 * la virgule decimale du source est refusee en <b>400</b> et n'est plus normalisee cote
 * serveur. Les defauts de montant et de duree du formulaire source ne sont pas portes : ils
 * appartiennent a souscription-web.
 *
 * <p>Les bornes de valeur de {@code service/InputValidator.simulationErrors} sont portees ici
 * (W4-T03) par la contrainte de borne decimale apres conversion en {@code BigDecimal}, et doivent
 * rester
 * egales aux contraintes {@code CHECK} de {@code V1__subscriptions.sql}. Cette validation est
 * volontairement dupliquee avec souscription-simulation-service : voir
 * {@code AMOUNT_BOUNDARY_CONTRACT.md}.
 */
public record SimulationInput(
        // Borne egale au CHECK (initial_amount BETWEEN 100 AND 100000) de V1__subscriptions.sql.
        @NotNull @Pattern(regexp = "^[0-9]{1,6}\\.[0-9]{2}$",
                message = "simulation.initialAmount.format")
        @DecimalRange(min = "100.00", max = "100000.00",
                message = "{horizon.simulation.initialAmount.range}") String initialAmount,
        // Borne egale au CHECK (monthly_amount BETWEEN 0 AND 2000) de V1__subscriptions.sql.
        @NotNull @Pattern(regexp = "^[0-9]{1,6}\\.[0-9]{2}$",
                message = "simulation.monthlyAmount.format")
        @DecimalRange(min = "0.00", max = "2000.00",
                message = "{horizon.simulation.monthlyAmount.range}") String monthlyAmount,
        // Borne egale au CHECK (duration_years BETWEEN 1 AND 30) de V1__subscriptions.sql.
        @NotNull @Min(value = 1, message = "{horizon.simulation.durationYears.range}")
        @Max(value = 30, message = "{horizon.simulation.durationYears.range}") Integer durationYears) {
}
