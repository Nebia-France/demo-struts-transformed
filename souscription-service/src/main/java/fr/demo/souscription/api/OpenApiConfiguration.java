package fr.demo.souscription.api;

import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.info.Contact;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.servers.Server;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Metadonnees du document OpenAPI publie (W3-T09).
 *
 * <p>Les trois serveurs declares sont ceux du contrat gele
 * {@code global/contracts/souscription-gateway-to-souscription-service/openapi.yaml} : des
 * adresses internes au cluster en mTLS, le service n'ayant aucune route externe. Le suffixe
 * {@code /v1} du contrat est porte par l'URL de serveur ; les ressources exposent leur chemin
 * complet {@code /v1/...}, ce que le test de contrat normalise.
 *
 * <p>Cette classe ne porte aucun chemin applicatif : elle n'existe que pour ancrer
 * {@link OpenAPIDefinition}.
 *
 * <p>La liste des serveurs effectivement rendue dans le document provient de
 * {@code mp.openapi.servers} dans {@code application.properties}, avec
 * {@code quarkus.smallrye-openapi.auto-add-server=false} pour empecher la substitution par les
 * adresses locales auto-detectees. Les {@code @Server} ci-dessous documentent la meme liste.
 */
@OpenAPIDefinition(
        info = @Info(
                title = "Horizon Subscription Service Internal API (consumed by souscription-gateway)",
                version = "1.0.0",
                description = "Internal, cluster-only write contract published by "
                        + "souscription-service and consumed by souscription-gateway. Every request "
                        + "is stateless: the draft is a persisted record in the subscriptions "
                        + "schema, and confirmation is idempotent on confirmationKey.",
                contact = @Contact(
                        name = "souscription-service team",
                        url = "https://example.com/horizon-souscription/subscription")),
        servers = {
                @Server(url = "https://souscription-service.dev.svc.cluster.local:8443/v1",
                        description = "Development (cluster-internal, mTLS)"),
                @Server(url = "https://souscription-service.staging.svc.cluster.local:8443/v1",
                        description = "Staging (cluster-internal, mTLS)"),
                @Server(url = "https://souscription-service.prod.svc.cluster.local:8443/v1",
                        description = "Production (cluster-internal, mTLS)")
        },
        tags = {
                @Tag(name = "drafts"),
                @Tag(name = "subscriptions")
        })
public final class OpenApiConfiguration {

    private OpenApiConfiguration() {
    }
}
