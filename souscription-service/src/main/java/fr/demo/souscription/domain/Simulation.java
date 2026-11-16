package fr.demo.souscription.domain;

import java.math.BigDecimal;

/**
 * Projection en lecture du resultat de simulation, calcule par souscription-simulation-service.
 *
 * <p>Pas une entite JPA : le calcul reste un appel distant au moment de la confirmation.
 * Les montants conservent l'echelle 2 du DDL.
 */
public record Simulation(BigDecimal initialAmount, BigDecimal monthlyAmount, int durationYears,
                         BigDecimal contributed, BigDecimal finalCapital) {
}
