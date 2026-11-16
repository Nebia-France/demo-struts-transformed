package fr.demo.souscription.api.dto;

import fr.demo.souscription.api.validation.MinimumAge;
import fr.demo.souscription.api.validation.NoControlChars;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * DTO d'entree du souscripteur, aligne sur le schema gele SubscriberInput.json.
 *
 * <p>{@code birthDate} est une date ISO-8601 ({@code YYYY-MM-DD}) sur le fil. L'accesseur de
 * libelle de date de naissance du bean source, qui re-affichait le format local jour/mois/annee, n'est
 * volontairement pas porte (ecart FG-005) : c'est une preoccupation de presentation detenue par
 * souscription-web, qui rend et analyse le format local. Cette regle vaut aussi pour la
 * variante requete de ce DTO (SubscriberRequest).
 *
 * <p>Les regles imperatives de {@code service/InputValidator.subscriberErrors} sont ici
 * declaratives (W4-T01) : bornes 1-80 et absence de caractere de controle sur les noms,
 * 254 caracteres et expression reguliere du source <b>caractere pour caractere</b> sur l'adresse
 * electronique, calendrier strict et majorite sur la date de naissance. La contrainte Email de
 * Hibernate Validator n'est deliberement pas utilisee : elle n'est pas equivalente a l'expression du
 * source (traitement different de {@code <} et {@code >}), ce qui ferait basculer le scenario
 * <i>Adresse electronique invalide</i>.
 */
public record SubscriberInput(
        @NotBlank(message = "subscriber.firstName.invalid")
        @Size(max = 80, message = "subscriber.firstName.invalid")
        @NoControlChars(message = "subscriber.firstName.invalid") String firstName,
        @NotBlank(message = "subscriber.lastName.invalid")
        @Size(max = 80, message = "subscriber.lastName.invalid")
        @NoControlChars(message = "subscriber.lastName.invalid") String lastName,
        @NotBlank(message = "subscriber.email.invalid")
        @Size(max = 254, message = "subscriber.email.invalid")
        @Pattern(regexp = "[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+", message = "subscriber.email.invalid") String email,
        @NotBlank(message = "subscriber.birthDate.format")
        @Pattern(regexp = "^[0-9]{4}-[0-9]{2}-[0-9]{2}$",
                message = "subscriber.birthDate.format")
        @MinimumAge(18) @Schema(format = "date") String birthDate) {

    /**
     * Normalisation a la construction, reprise du {@code trim()} que
     * {@code service/InputValidator} appliquait <b>avant</b> ses controles : les bornes de
     * longueur, l'expression reguliere de l'adresse electronique et le calendrier strict portent
     * donc, comme dans le source, sur la valeur debarrassee de ses espaces superflus. La
     * normalisation est faite ici et non dans un validateur : un validateur qui mute son entree
     * serait invisible pour l'appelant et s'executerait dans un ordre indefini.
     */
    public SubscriberInput {
        firstName = trimmedName(firstName);
        lastName = trimmedName(lastName);
        email = trimmed(email);
        birthDate = trimmed(birthDate);
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    /**
     * Le source testait les caracteres de controle sur la valeur <b>brute</b>
     * ({@code value.codePoints().anyMatch(Character::isISOControl)}, sans {@code trim()}) :
     * {@code trim()} retirant les caracteres de controle en bordure, une valeur qui en contient
     * est laissee intacte pour que {@code @NoControlChars} la rejette comme le source.
     */
    private static String trimmedName(String value) {
        if (value == null || value.codePoints().anyMatch(Character::isISOControl)) {
            return value;
        }
        return value.trim();
    }
}
