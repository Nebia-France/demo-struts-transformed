package fr.demo.souscription.catalogue.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.demo.souscription.catalogue.domain.Product;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@ApplicationScoped
public class CatalogueSeedProvider {

    private static final Logger LOG = Logger.getLogger(CatalogueSeedProvider.class);

    @Inject
    CatalogService catalogService;

    @Inject
    ObjectMapper objectMapper;

    private volatile String catalogueFingerprint;

    void onStart(@Observes StartupEvent event) {
        catalogService.seed(referenceProducts());
        List<Product> seeded = catalogService.getProducts();
        catalogueFingerprint = fingerprintOf(seeded);
        catalogService.seedVersion(catalogueFingerprint);
        LOG.infof("Catalogue Horizon amorce avec %d produits, empreinte SHA-256 %s",
                seeded.size(), catalogueFingerprint);
    }

    /** SHA-256 hex digest of the JSON representation of the seeded catalogue (reused as HTTP ETag). */
    public String getCatalogueFingerprint() {
        return catalogueFingerprint;
    }

    private String fingerprintOf(List<Product> seeded) {
        try {
            byte[] json = objectMapper.writeValueAsString(seeded).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Impossible de calculer l'empreinte du catalogue", e);
        }
    }

    private static List<Product> referenceProducts() {
        return List.of(
                Product.of("PRUDENT", "Horizon Prudent", "Prudent",
                        "Une hypothèse de progression modérée pour découvrir le fonctionnement de l'épargne.",
                        "0.02"),
                Product.of("EQUILIBRE", "Horizon Équilibre", "Équilibré",
                        "Une hypothèse intermédiaire pour construire un projet dans la durée.", "0.04"),
                Product.of("DYNAMIQUE", "Horizon Dynamique", "Dynamique",
                        "Une hypothèse de progression plus élevée, associée à un profil de risque supérieur.",
                        "0.06"));
    }
}
