package fr.demo.souscription.gateway.client;

import fr.demo.souscription.gateway.runtime.GatewayConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import java.util.List;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;

/** Relaye uniquement la paire CSRF validee a l'entree vers la defense secondaire du service. */
@ApplicationScoped
public class SubscriptionCsrfHeaders implements ClientHeadersFactory {
    private final GatewayConfig config;

    @Inject
    public SubscriptionCsrfHeaders(GatewayConfig config) {
        this.config = config;
    }

    @Override
    public MultivaluedMap<String, String> update(MultivaluedMap<String, String> incoming,
                                                MultivaluedMap<String, String> outgoing) {
        var result = new MultivaluedHashMap<String, String>();
        String token = first(incoming, config.csrf().headerName());
        if (token != null) {
            result.putSingle("X-CSRF-Token", token);
        }
        // Le service utilise encore le nom historique csrf-token ; le navigateur utilise
        // le nom configure par le gateway (XSRF-TOKEN par defaut). Aucun autre cookie ne sort.
        incoming.entrySet().stream()
                .filter(entry -> HttpHeaders.COOKIE.equalsIgnoreCase(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .flatMap(value -> List.of(value.split(";")).stream())
                .map(String::trim)
                .filter(value -> value.startsWith(config.csrf().cookieName() + "="))
                .map(value -> value.substring(value.indexOf('=') + 1))
                .findFirst()
                .ifPresent(value -> result.putSingle(HttpHeaders.COOKIE, "csrf-token=" + value));
        return result;
    }

    private static String first(MultivaluedMap<String, String> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst().orElse(null);
    }
}
