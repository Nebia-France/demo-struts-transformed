package fr.demo.souscription.gateway.api.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Body of {@code GET /api/v1/csrf-token}, mirroring the provider contract: the double-submit token
 * the SPA must echo in the {@code X-CSRF-Token} header. Replaces the hidden field rendered by
 * {@code csrf.jspf}.
 */
public record CsrfTokenView(@Schema(minLength = 32, required = true) String token) {
}
