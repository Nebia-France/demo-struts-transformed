# Security response headers — souscription-gateway

Wave 2 task **W2-T01**. Replaces the five response headers set by
`src/main/java/fr/demo/souscription/web/SecurityFilter.java` in the servlet container.

## 1. Mechanism

| Element | Path |
| --- | --- |
| Filter | `src/main/java/fr/demo/souscription/gateway/edge/SecurityHeaderFilter.java` |
| Order | `EdgeFilterOrder.SECURITY_HEADERS` (100) |
| Test | `src/test/java/fr/demo/souscription/gateway/edge/SecurityHeaderFilterTest.java` |
| Gherkin glue | `src/test/java/fr/demo/souscription/gateway/steps/SecuriteWebSteps.java` |

The filter is a Vert.x `@RouteFilter` that registers a `addHeadersEndHandler`. This is **mandatory**:
a JAX-RS `ContainerResponseFilter` never sees responses that do not reach JAX-RS — router-generated
404s, management endpoints under `/q/*`, or responses emitted by a short-circuiting edge filter
(405 of the method allow-list, 403 of the CSRF check). `SecurityHeaderFilterTest` therefore asserts
the five headers on a 200, a router 404, a JAX-RS 404 and a forced 500.

`setCharacterEncoding` from the source filter is **not** reintroduced: UTF-8 is configured globally
(plans 03 and 08) — see `docs/config-mapping.md`.

## 2. The five headers

| Header | Value | Source |
| --- | --- | --- |
| `X-Content-Type-Options` | `nosniff` | constant |
| `X-Frame-Options` | `DENY` | constant |
| `Referrer-Policy` | `no-referrer` | constant |
| `Content-Security-Policy` | from `gateway.security.csp` | configuration (AG-008) |
| `Cache-Control` | `no-store` | constant |

The CSP value is read exclusively from `config.security().csp()`. No policy string is hardcoded in
Java, so the policy is auditable and overridable per environment through `GATEWAY_CSP`.

## 3. CSP split: gateway vs static host (NFG-003)

The source application served HTML from the same container as the actions, so one CSP covered both.
In the target topology the gateway serves **JSON only** — it never returns a document — and the SPA
is delivered by the static host of `souscription-web` behind the shared ingress (same origin,
OD-004).

| Directive of the source CSP | Owner in the target |
| --- | --- |
| `default-src 'self'` | Static host (document) / gateway tightened to `'none'` |
| `style-src 'self'` | Static host of `souscription-web` only |
| `img-src 'self'` | Static host of `souscription-web` only |
| `script-src 'none'` | Static host of `souscription-web` |
| `object-src 'none'` | Static host of `souscription-web` |
| `form-action 'self'` | Static host of `souscription-web` (no HTML form is posted to the gateway) |
| `base-uri 'none'` | Both (kept on the gateway) |
| `frame-ancestors 'none'` | Both (kept on the gateway) |

The gateway policy is consequently reduced to the directives that still protect something on a JSON
response:

```properties
gateway.security.csp=${GATEWAY_CSP:default-src 'none'; frame-ancestors 'none'; base-uri 'none'}
```

`style-src`, `img-src` and `form-action` are **deliberately dropped from the gateway policy**: they
protect document rendering, which no longer happens here. Dropping them is not a relaxation —
`default-src 'none'` already denies every fetch directive, including those three. The document CSP
(script, style and image sources of the SPA) is the responsibility of the `souscription-web` static
host and must be declared there; the gateway cannot emit it because it never emits the document.

The four non-CSP headers remain on every gateway response, JSON included: `X-Frame-Options` and
`Referrer-Policy` are cheap defence in depth, `nosniff` prevents content-type guessing on error
bodies, and `no-store` keeps subscription data out of intermediary caches.
