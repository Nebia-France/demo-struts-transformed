package fr.demo.souscription.simulation.model;

import java.math.BigDecimal;

public record Simulation(BigDecimal initial, BigDecimal monthly, int years,
                         BigDecimal contributed, BigDecimal finalCapital) {

    public BigDecimal gains() {
        return finalCapital.subtract(contributed);
    }
}
