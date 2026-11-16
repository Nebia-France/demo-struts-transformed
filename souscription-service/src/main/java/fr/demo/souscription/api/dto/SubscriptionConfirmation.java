package fr.demo.souscription.api.dto;

/**
 * Corps de reponse de {@code confirmDraft} et de {@code getSubscription}, aligne sur le schema
 * gele SubscriptionConfirmation.json ({@code additionalProperties: false}).
 *
 * <p>Les champs produit sont denormalises au moment de la confirmation, ce qui preserve le schema
 * {@code subscriptions} legataire : les lectures ulterieures n'appellent jamais le catalogue.
 *
 * <p>{@code createdAt} voyage en chaine ISO-8601 UTC ; le rendu au format local jour/mois/annee heure:minute en
 * Europe/Paris est une preoccupation de presentation detenue par souscription-web.
 */
public record SubscriptionConfirmation(String reference, ProductSummary product,
                                       SubscriberInput subscriber, SimulationResult simulation,
                                       String createdAt) {
}
