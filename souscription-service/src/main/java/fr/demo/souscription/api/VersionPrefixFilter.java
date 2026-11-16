package fr.demo.souscription.api;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.PathItem;

/**
 * Aligne les chemins du document publie sur le contrat gele
 * {@code global/contracts/souscription-gateway-to-souscription-service/openapi.yaml}.
 *
 * <p>Le contrat porte le {@code /v1} dans l'URL de serveur et declare ses chemins sans prefixe
 * ({@code /drafts}). Les ressources de ce module portent le chemin complet
 * ({@code @Path("/v1/drafts")}), ce qui ferait apparaitre le prefixe deux fois dans le document :
 * concatene a la base {@code https://...:8443/v1}, un chemin {@code /v1/drafts} designe
 * {@code /v1/v1/drafts}, adresse que le service ne sert pas. Un client genere a partir du document
 * publie appellerait donc une URL inexistante.
 *
 * <p>Ce filtre retire le prefixe des cles de chemin du document uniquement : le routage HTTP reel,
 * porte par les annotations {@code @Path}, est inchange.
 */
public class VersionPrefixFilter implements OASFilter {

    private static final String PREFIX = "/v1";

    @Override
    public void filterOpenAPI(OpenAPI openAPI) {
        if (openAPI.getPaths() == null || openAPI.getPaths().getPathItems() == null) {
            return;
        }
        List<String> prefixed = new ArrayList<>(openAPI.getPaths().getPathItems().keySet()).stream()
                .filter(path -> path.startsWith(PREFIX + "/"))
                .toList();
        for (String path : prefixed) {
            PathItem item = openAPI.getPaths().getPathItem(path);
            openAPI.getPaths().removePathItem(path);
            openAPI.getPaths().addPathItem(path.substring(PREFIX.length()), item);
        }
    }
}
