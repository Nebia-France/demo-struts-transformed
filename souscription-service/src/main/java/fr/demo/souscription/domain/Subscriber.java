package fr.demo.souscription.domain;

import java.time.LocalDate;

/**
 * Identite du souscripteur, modele de domaine immuable (W2-T02).
 *
 * <p>Remplace le JavaBean {@code model/Subscriber} : plus de serialisation Java, plus rien
 * en session (ecart FG-004). Le libelle de date de naissance au format local jour/mois/annee n'est
 * pas porte : c'est une preoccupation de presentation detenue par souscription-web
 * (ecart FG-005) ; l'API expose une date ISO-8601.
 */
public record Subscriber(String firstName, String lastName, String email, LocalDate birthDate) {
}
