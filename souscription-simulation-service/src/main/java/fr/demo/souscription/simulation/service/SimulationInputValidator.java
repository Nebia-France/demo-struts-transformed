package fr.demo.souscription.simulation.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class SimulationInputValidator {

    public Map<String, String> simulationErrors(String initial, String monthly, String years) {
        Map<String, String> errors = new LinkedHashMap<>();
        validateAmount(initial, "initial", "100", "100000",
                "Le versement initial doit être compris entre 100,00 et 100 000,00 €.", errors);
        validateAmount(monthly, "monthly", "0", "2000",
                "La mensualité doit être comprise entre 0,00 et 2 000,00 €.", errors);
        if (years == null || !years.matches("(?:[1-9]|[12][0-9]|30)")) {
            errors.put("years", "La durée doit être un nombre entier de 1 à 30 ans.");
        }
        return errors;
    }

    private void validateAmount(String raw, String field, String min, String max,
                                String message, Map<String, String> errors) {
        BigDecimal value = amount(raw);
        if (value == null || value.compareTo(new BigDecimal(min)) < 0
                || value.compareTo(new BigDecimal(max)) > 0) {
            errors.put(field, message + " Utilisez au maximum deux décimales, sans séparateur de milliers.");
        }
    }

    public BigDecimal amount(String raw) {
        if (raw == null || !raw.matches("[0-9]{1,6}(?:[.,][0-9]{1,2})?")) {
            return null;
        }
        return new BigDecimal(raw.replace(',', '.'));
    }
}
