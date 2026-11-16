package fr.demo.souscription.catalogue.api;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * RFC 7807 {@code application/problem+json} body. Every field is static : aucune valeur
 * fournie par le client n'est reflechie, aucun detail interne n'est divulgue.
 */
@Schema(name = "ProblemDetail", description = "Erreur au format RFC 7807")
public record ProblemDetail(String type, String title, int status,
                            String detail, String instance) { }
