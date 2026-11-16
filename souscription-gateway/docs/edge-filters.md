# Edge interception model and input-surface limits

Wave 1 tasks **W1-T04** (interception skeleton) and **W1-T05** (input-surface hardening).

## 1. What replaces the servlet filter

The source declares a single filter in `web.xml`:

```xml
<filter><filter-name>security</filter-name>
        <filter-class>fr.demo.souscription.web.SecurityFilter</filter-class></filter>
<filter-mapping><filter-name>security</filter-name><url-pattern>/*</url-pattern></filter-mapping>
```

`SecurityFilter.doFilter` bundles three concerns, ordered only by statement order inside the method:

| # | Concern in `SecurityFilter`                                                              | Target component          | Plan |
|---|------------------------------------------------------------------------------------------|---------------------------|------|
| 1 | `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, CSP, `Cache-Control`      | `securityHeaderFilter`    | 11   |
| 2 | HTTP method allow-list (`GET`, `HEAD`, `POST`) with `Allow` header and 405               | `MethodAllowListFilter`   | 13   |
| 3 | CSRF token check on `*.do` POSTs (session attribute, length cap, constant-time compare)   | `csrfDoubleSubmitFilter`  | 16   |

There is no `FilterChain` and no declaration order in the target: ordering is explicit and numeric.

## 2. Ordering constants

`fr.demo.souscription.gateway.edge.EdgeFilterOrder` is the single source of truth. No component may
hardcode its own number.

| Constant             | Value | Mechanism                                            |
|----------------------|-------|------------------------------------------------------|
| `SECURITY_HEADERS`   | 100   | Vert.x `@RouteFilter`, writes via `addHeadersEndHandler` |
| `METHOD_ALLOW_LIST`  | 200   | Vert.x `@RouteFilter`                                 |
| `CSRF`               | 300   | Vert.x `@RouteFilter`                                 |

`observability.RequestCorrelation` is ordered at `CSRF + 100` (400), strictly above every edge filter,
so the request id is assigned (routing context and MDC) before any rejection is counted and logged.

A **higher** `@RouteFilter` value runs **earlier** in Vert.x. The effective request order is
therefore:

1. `METHOD_ALLOW_LIST`-then-`CSRF` rejection path — the allow-list must decide before the CSRF check,
   so a disallowed method answers 405 and never reaches the token comparison. The two components are
   registered so that this precedence holds; `CSRF` carries the highest number but short-circuits
   only on requests the allow-list has already admitted (it inspects the method itself and defers to
   the allow-list outcome).
2. `SECURITY_HEADERS` last on the request path, because its work happens on the *response*: it
   registers a headers-end handler so the headers are written even for responses produced by
   short-circuiting filters or by routes that never reach JAX-RS.

Effective order summary: **method allow-list → CSRF → response headers**.

### 2.1 Method allow-list (W2-T02)

`fr.demo.souscription.gateway.edge.MethodAllowListFilter` is registered at
`EdgeFilterOrder.METHOD_ALLOW_LIST` (200), i.e. **before** `EdgeFilterOrder.CSRF` (300) on the
rejection path: a forbidden method is answered 405 and is never CSRF-evaluated. The token
comparison of plan 16 therefore only ever sees a method the allow-list has admitted.

The filter runs **earlier** than `SECURITY_HEADERS` (100) in Vert.x, so it installs the headers-end
handler itself by calling `SecurityHeaderFilter.installHeaders(RoutingContext)`. The five security
header values stay declared in a single place; the 405 carries them, as the source error document
did.

The 405 is `application/problem+json` built from `ProblemFactory.ofStatus(405, ...)` — the single
status decision point of plan 12 — plus the `Allow` header. The source answered the same 405 with
the HTML document `erreur.jsp`; no servlet idiom (`sendError`, hardcoded method literals) is ported.

#### `PUT` decision

| Source method set | Target method set | Source |
|-------------------|-------------------|--------|
| `GET`, `HEAD`, `POST` | `GET`, `HEAD`, `POST`, `PUT` | `gateway.security.allowed-methods` |

`PUT` is **added**: the resource-oriented API of plan 07 updates the draft sub-resources with
`PUT /api/v1/drafts/{draftId}/subscriber` and `PUT /api/v1/drafts/{draftId}/simulation`, which the
Struts front controller could not express. The value is configuration only — the Java code contains
no method literal — and the same set is stated in the OpenAPI document
(`src/main/resources/META-INF/openapi.yaml`).

Consequence on the Gherkin scenario `« Methodes HTTP non autorisees »`, written against the source
set: its `PUT` row still answers **405**, but from *routing* (`/api/v1/products` declares `GET`
only) instead of the allow-list, and that router 405 carries no `Allow` header. The glue therefore
asserts the `Allow` header against the configured allow-list and, for a method the edge accepts,
asserts only the normalised problem+json 405
(`src/test/java/fr/demo/souscription/gateway/steps/SecuriteWebSteps.java`).

## 3. Selection rule: `@RouteFilter` or `@Provider`

* A concern that must cover **all** traffic — including `/q/*` management endpoints and non-JAX-RS
  404s — is a Vert.x **`@RouteFilter`**. The three security concerns above fall in this category.
* A concern that needs the **JAX-RS request context** (matched resource, entity, `UriInfo`,
  declared media types) is a **`@Provider`** ordered with `jakarta.ws.rs.Priorities`.

A `ContainerResponseFilter` **does not see responses that never reach JAX-RS**. That is precisely why
the security response headers use `addHeadersEndHandler` on a route filter instead of a response
provider. `fr.demo.souscription.gateway.edge.ResponseContextFilter`
(`@Provider @Priority(Priorities.HEADER_DECORATOR)`) is an intentionally empty skeleton that proves
the provider wiring and hosts future context-dependent concerns.

## 4. Input-surface limits (W1-T05)

`src/main/resources/application.properties`:

```properties
quarkus.http.limits.max-body-size=64K
quarkus.http.limits.max-header-size=8K
quarkus.http.limits.max-form-attribute-size=2K
quarkus.http.so-reuse-port=false
```

A body above 64 KB is rejected with **413** before any resource method runs. The largest body in the
public contract (`SimulationRequest`) is a few hundred bytes, so the limit is three orders of
magnitude above the legitimate need.

### Bean Validation

Every request-body parameter is annotated `@Valid`. **Count: 5** body parameters over 5 methods:

| Method                                              | Body DTO              |
|-----------------------------------------------------|-----------------------|
| `POST /api/v1/drafts`                               | `DraftStartRequest`   |
| `PUT /api/v1/drafts/{draftId}/subscriber`           | `SubscriberInput`     |
| `PUT /api/v1/drafts/{draftId}/simulation`           | `SimulationInput`     |
| `POST /api/v1/drafts/{draftId}/confirmation`        | `ConfirmationRequest` |
| `POST /api/v1/simulations`                          | `SimulationRequest`   |

Constraints on the input DTOs are **structural only** (`@NotBlank`, `@NotNull`, `@Size`, `@Pattern`
for the product code) and mirror the shared JSON schemas. Business rules — amount ranges, minimum
subscriber age, existence of a product code, match of the `confirmationKey` against the draft —
remain authoritative in the sibling services. A structural failure answers **400**.

### Media types

Every state-changing method declares `@Consumes(MediaType.APPLICATION_JSON)` (at class level on
`DraftResource` and `SimulationResource`). No route accepts `*/*` or `MediaType.WILDCARD`; a
`Content-Type: text/plain` POST answers **415**.

## 5. Origin policy

The SPA (`souscription-web`) and the gateway are served from the **same origin** behind the shared
ingress / reverse proxy (validated topology decision OD-004). Consequently **no CORS configuration
is added** and `quarkus.http.cors` stays disabled — the property is deliberately absent from
`application.properties`.

If a distinct origin ever appears, it must be configured **explicitly** (an exact origin list),
never permissively (`*`, reflected origin, or `allow-credentials` with a wildcard).

Rate limiting is **not** handled here; it is deferred to plan 19 together with pool sizing (AG-010).

## 6. Émission du jeton CSRF (W3-T02)

`CsrfTokenService` remplace l'attribut de session `csrf` de `SecurityFilter` par un jeton
double-submit sans état. Les deux propriétés de sécurité du filtre source sont conservées
telles quelles : comparaison en temps constant via `MessageDigest.isEqual` sur les octets UTF-8
(jamais `String.equals`, qui fuite la longueur du préfixe commun — FG-002) et rejet au-delà de
`gateway.csrf.max-token-length` (100, valeur du source).

Deux renforcements : 32 octets de `SecureRandom` (256 bits) au lieu d'un UUID aléatoire, et un
horodatage d'émission signé en HMAC-SHA256 avec `gateway.csrf.signing-key`. La clé n'a **aucune
valeur par défaut** : elle vient de l'environnement ou du coffre, et son absence fait échouer le
démarrage (`GatewayStartup`). Le format du jeton est `<aléa>.<epochSeconds>.<hmac>`, soit 98
caractères, à l'intérieur de la borne conservée.

La durée de vie (`gateway.csrf.ttl`, 20 minutes, équivalente à l'ancienne fenêtre de session) est
vérifiée **côté serveur** par `isExpired` ; le `Max-Age` du cookie n'est qu'une indication pour le
client. Un jeton dont la signature ne se vérifie pas est traité comme expiré.

`CsrfCookieIssuer` (`EdgeFilterOrder.CSRF_COOKIE_ISSUE`) pose le cookie sur les méthodes **sûres**
(GET/HEAD) uniquement, et seulement si aucun jeton vivant n'est présenté : l'émission est idempotente
et sans verrou (le `synchronized (session)` du source n'a pas d'équivalent, rien de partagé n'étant
muté — FG-003, AG-015). Attributs : `Secure`, `SameSite=Strict`, `Path=/`, `Max-Age` aligné sur le
TTL, et **pas** de `HttpOnly` — la SPA doit lire la valeur pour la réémettre dans l'en-tête
`gateway.csrf.header-name`. `GET /api/v1/csrf-token` (`CsrfTokenResource`) expose le jeton dans le
corps et pose le même cookie. La **vérification** sur les méthodes mutantes reste du ressort du
plan 16.

## 7. Vérification du jeton CSRF (W3-T03)

`CsrfDoubleSubmitFilter` (`EdgeFilterOrder.CSRF`) compare le cookie `gateway.csrf.cookie-name` à
l'en-tête `gateway.csrf.header-name` sur **toute méthode non sûre** (`POST`, `PUT`, `PATCH`,
`DELETE`), dans le périmètre `/api/v1/**` uniquement : les ressources statiques et les endpoints de
management `/q/*` ne sont pas contrôlés, comme le source ne contrôlait pas ses ressources statiques.

Une méthode absente de `gateway.security.allowed-methods` est laissée passer sans contrôle : c'est
`MethodAllowListFilter` qui la refuse en 405. Le verdict de méthode reste ainsi le seul point de
décision des méthodes, et une méthode interdite ne ressort jamais en 403.

Le refus est un 403 `problem+json` construit depuis `ProblemFactory`
(`CsrfValidationException` → 403), identique pour les six cas de rejet : aucune divulgation de la
cause. Les cinq en-têtes de sécurité sont posés avant l'écriture, comme pour le 405.

Les écarts de modèle de confiance par rapport au source sont consignés dans
`docs/csrf-trust-model.md`.
